"""Executor da matriz do benchmark da Ceres (issue #348).

Para cada arquitetura: ``git worktree`` no commit, JAR construído ali, banco proprio (o schema do
``2463c27`` nao e o da ``main``) com a base de lei copiada do banco de origem. Para cada modelo:
``ollama pull``, prova do pensamento desligado, e o comando de avaliacao existente
(``dev,avaliacao``) com ``AI_CHAT_MODEL`` apontando para o modelo, enquanto uma thread amostra a
memoria do Ollama. Cada celula grava em ``<saida>/<rodada>/<celula>/`` o relatorio do comando
renomeado com o nome da celula e um ``celula.json`` com SHAs, hardware, pensamento e memoria.

Nenhum segredo vem do repositorio: senha do banco por ``DB_PASSWORD`` (a mesma do ``.env``).
"""

from __future__ import annotations

import hashlib
import http.client
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from . import gabarito as gab
from . import similaridade

ZONA = ZoneInfo("America/Sao_Paulo")
GABARITO_REPO = "docs/avaliacao/gabarito-ceres.json"
FERRAMENTAS_REPO = "src/main/java/br/com/agrocontabil/assistente/ferramentas/FerramentasDadosProdutor.java"
#: Pergunta curta da prova de pensamento: so mede se o modelo raciocina com think=false.
PERGUNTA_PROVA = "Responda apenas com a palavra: pronto"
_GIB = 1_073_741_824
#: Falhas de rede que o urllib nao embrulha em URLError (conexao cortada no meio da resposta).
ERROS_HTTP = (OSError, http.client.HTTPException, ValueError)
_UNIDADES = {"b": 1, "kib": 1024, "mib": 1024**2, "gib": 1024**3, "tib": 1024**4,
             "kb": 1000, "mb": 1000**2, "gb": 1000**3, "tb": 1000**4}


#: Valores que nunca podem aparecer em log, relatorio, JSON nem mensagem de erro: a chave da API da
#: Anthropic (``ANTHROPIC_API_KEY``), que so existe no ambiente de quem roda.
_SEGREDOS: set[str] = set()
OCULTO = "[REDIGIDO]"


def registrar_segredo(valor: str | None) -> None:
    """Passa a ocultar ``valor`` em tudo que o executor escreve. Valor curto demais nao e chave."""
    if valor and len(valor) >= 8:
        _SEGREDOS.add(valor)


def ocultar(texto: str) -> str:
    for segredo in _SEGREDOS:
        texto = texto.replace(segredo, OCULTO)
    return texto


def ocultar_em_arquivos(pasta: Path) -> list[str]:
    """Tira os segredos registrados dos arquivos da pasta (o log da JVM, os relatorios do comando) e
    devolve os nomes que o continham. Defesa em profundidade: nada no fluxo grava a chave."""
    achados = []
    if not _SEGREDOS:
        return achados
    for arquivo in sorted(p for p in pasta.rglob("*") if p.is_file()):
        dados = arquivo.read_bytes()
        novos = dados
        for segredo in _SEGREDOS:
            novos = novos.replace(segredo.encode(), OCULTO.encode())
        if novos != dados:
            arquivo.write_bytes(novos)
            achados.append(str(arquivo.relative_to(pasta)))
    return achados


def log(mensagem: str) -> None:
    print(f"[{datetime.now(ZONA):%H:%M:%S}] {ocultar(mensagem)}", flush=True)


def nome_celula(arquitetura: str, modelo: str) -> str:
    """``ceres-1--qwen3-0.6b``: arquitetura e modelo, seguros para nome de arquivo."""
    return f"{arquitetura}--" + re.sub(r"[^a-z0-9.]+", "-", modelo.lower()).strip("-")


def modelo_da_api(api: dict) -> str:
    """O modelo que a API recebe. Uma variante da celula (``variante``, ex.: a fidelidade da lei)
    tem um nome proprio em ``modelo`` (a chave da celula, do relatorio e da pasta) e o modelo real
    em ``modeloApi``; sem ele, o nome da celula e o modelo."""
    return api.get("modeloApi") or api["modelo"]


_NOME_DE_VARIAVEL = re.compile(r"^AI_[A-Z0-9_]+$")


def validar_ambiente_da_celula(api: dict) -> dict[str, str]:
    """``ambiente`` da celula por API: variaveis ``AI_*`` proprias da variante (ex.: as chaves da
    fidelidade da lei), aplicadas **depois** da configuracao publicada do commit e **antes** das que
    o executor fixa. Nao pode tocar no que define o provedor, o modelo, a chave, o endereco nem o
    read-timeout: isso e do executor e da matriz, nao de uma variante.

    :raises RuntimeError: nome fora do padrao ``AI_*``, variavel reservada ou valor que nao e texto
    """
    ambiente = api.get("ambiente") or {}
    reservadas = FORA_DA_CONFIGURACAO | {"AI_READ_TIMEOUT"}
    for nome, valor in ambiente.items():
        if not _NOME_DE_VARIAVEL.match(nome):
            raise RuntimeError(f"{api['modelo']}: variavel de ambiente {nome!r} fora do padrao AI_*")
        if nome in reservadas:
            raise RuntimeError(f"{api['modelo']}: {nome} e do executor (provedor, modelo, chave, "
                               "endereco ou read-timeout) e nao pode ser mudada por uma variante")
        if not isinstance(valor, str) or not valor.strip():
            raise RuntimeError(f"{api['modelo']}: o valor de {nome} tem de ser um texto nao vazio")
    return dict(ambiente)


def variaveis_que_o_commit_nao_conhece(ambiente: dict[str, str], application_yml: str) -> list[str]:
    """Variaveis do ``ambiente`` da celula que o ``application.yml`` do commit nao le
    (``${NOME:padrao}`` ou ``${NOME}``). Variavel que o commit nao conhece e ignorada em silencio
    pelo Spring: a celula mediria outra configuracao e se chamaria pelo nome da pedida."""
    # Linha de comentario nao conta: ``# enabled: ${AI_X:false}`` nao liga nada.
    codigo = "\n".join(l for l in application_yml.splitlines() if not l.lstrip().startswith("#"))
    return [nome for nome in ambiente
            if not re.search(r"\$\{" + re.escape(nome) + r"[:}]", codigo)]


def bytes_de_docker_stats(texto: str) -> int | None:
    """``"1.234GiB / 15.5GiB"`` -> bytes usados (saida de ``docker stats --format {{.MemUsage}}``)."""
    m = re.match(r"\s*([\d.]+)\s*([KMGT]?i?B)", texto, re.IGNORECASE)
    if not m:
        return None
    return int(float(m.group(1)) * _UNIDADES[m.group(2).lower()])


#: Prazo de uma pergunta no comando de avaliacao: ``AvaliacaoCeresRunner`` (5 min + 5 s).
PRAZO_PERGUNTA_S = 305
#: Read-timeout de producao (``AI_READ_TIMEOUT`` do ``.env.example``): na VPS, uma pergunta acima
#: disso cairia no fallback. O aviso da celula conta quantas passaram.
LIMITE_PRODUCAO_MS = 120_000
#: As provas do pensamento mantem o modelo na memoria ate a celula comecar (e entre uma e outra).
KEEP_ALIVE_PROVA = "30m"
#: API da Anthropic: endereco padrao e versao do protocolo da prova do raciocinio. ``BENCH_ANTHROPIC_URL``
#: troca o endereco (so https, ou http local para um servidor de teste): a chave vai nele.
URL_ANTHROPIC = "https://api.anthropic.com"
VERSAO_API_ANTHROPIC = "2023-06-01"


def validar_url_anthropic(url: str) -> str:
    """O endereco para onde a chave vai: ``https``, ou ``http`` so em localhost/127.0.0.1/[::1] (servidor
    de teste), e nunca com usuario ou senha na URL (``http://localhost@outro-host`` tem outro host).

    :raises RuntimeError: endereco que poderia mandar a chave a um destino nao confiavel
    """
    partes = urllib.parse.urlsplit(url)
    try:
        partes.port  # noqa: B018 (porta invalida levanta ValueError)
    except ValueError:
        raise RuntimeError(f"BENCH_ANTHROPIC_URL com porta invalida: {url!r}") from None
    local = partes.hostname in ("localhost", "127.0.0.1", "::1")
    if (partes.scheme != "https" and not (partes.scheme == "http" and local)) \
            or not partes.hostname or partes.username is not None or partes.password is not None:
        raise RuntimeError("BENCH_ANTHROPIC_URL precisa ser https (ou http em localhost, de teste) "
                           "e sem usuario na URL: a chave da API vai nesse endereco")
    return url.rstrip("/")


class _SemRedirecionamento(urllib.request.HTTPRedirectHandler):
    """A chave nao acompanha um redirecionamento: o urllib copiaria os cabecalhos para o novo host."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def segundos_de_duracao(texto: str) -> float:
    """``240s``, ``4m`` ou ``500ms`` (a forma simples do ``Duration`` do Spring) em segundos."""
    m = re.fullmatch(r"\s*(\d+)\s*(ms|s|m)\s*", str(texto), re.IGNORECASE)
    if not m:
        raise RuntimeError(f"readTimeoutCelula invalido: {texto!r} (use 240s, 4m ou 500ms)")
    return float(m.group(1)) * {"ms": 0.001, "s": 1, "m": 60}[m.group(2).lower()]


def validar_read_timeout(valor: str | None) -> None:
    """O read-timeout da celula tem de ficar abaixo do prazo da pergunta (senao o prazo da
    pergunta dispara antes e a falha perde a causa)."""
    if valor is not None and segundos_de_duracao(valor) >= PRAZO_PERGUNTA_S:
        raise RuntimeError(f"readTimeoutCelula {valor} nao fica abaixo do prazo da pergunta "
                           f"({PRAZO_PERGUNTA_S} s)")


def avisos_de_falha_tecnica(rodadas: list[list[dict]]) -> list[str]:
    """Aviso da celula com falha tecnica (a mesma regra do relatorio) e quantas respostas
    passaram do read-timeout de producao. O executor nao refaz celula sozinho: o criterio e o
    procedimento estao no README (\"Celula com falha tecnica\")."""
    from . import relatorio  # so aqui: o executor nao precisa dele para o resto
    execucoes = [r for resultados in rodadas for r in resultados]
    falhas = sum(1 for r in execucoes if relatorio.falha_tecnica(r))
    lentas = sum(1 for r in execucoes
                 if ((r.get("execucao") or {}).get("latenciaMs") or 0) >= LIMITE_PRODUCAO_MS)
    limite = LIMITE_PRODUCAO_MS // 1000
    # Curtos: o relatorio corta cada aviso em 300 caracteres, com o prefixo da celula.
    if falhas:
        return [f"{falhas} de {len(execucoes)} respostas com falha técnica; {lentas} com latência total de "
                f"{limite} s ou mais (o read-timeout publicado, {limite} s, vale por chamada ao modelo; "
                "a latência local não é a da VPS). Refazer: critério no README"]
    if lentas:
        return [f"{lentas} de {len(execucoes)} respostas com latência total de {limite} s ou mais (o "
                f"read-timeout publicado, {limite} s, vale por chamada ao modelo; a latência local não é "
                "a da VPS), sem falha técnica"]
    return []


def resumo_pensamento(prova: dict) -> str:
    """Frase do relatorio a partir da prova de pensamento."""
    if prova.get("erro"):
        return f"prova falhou: {prova['erro']}"
    if prova.get("provedor") == "anthropic":
        if prova.get("raciocinou"):
            return (f"ATIVO apesar de thinking={prova.get('thinking')} "
                    f"({prova.get('caracteresPensamento', 0)} caracteres de raciocínio na prova): "
                    "os tetos por chamada seriam consumidos")
        return f"desligado (thinking={prova.get('thinking')}; nenhum bloco de raciocínio na prova)"
    if prova.get("raciocinou"):
        return (f"ATIVO apesar de think=false ({prova.get('caracteresPensamento', 0)} caracteres de "
                "raciocínio na prova): latência e num_predict afetados")
    return "desligado (think=false; nenhum raciocínio na prova)"


def avaliar_prova(resposta: dict, num_ctx: int | None = None) -> dict:
    """Le a resposta do ``/api/chat`` da prova: raciocinio em ``thinking`` ou ``<think>`` no texto.

    ``numCtx`` e o contexto pedido na prova e ``carregamentoMs`` o ``load_duration`` do Ollama:
    mostram se a prova carregou o modelo com o mesmo contexto da celula.
    """
    mensagem = resposta.get("message") or {}
    pensamento = mensagem.get("thinking") or ""
    conteudo = mensagem.get("content") or ""
    # O qwen3 devolve "<think>\n\n</think>" vazio com o raciocinio desligado: so conta o que
    # houver dentro das marcas.
    dentro = "".join(re.findall(r"<think>(.*?)(?:</think>|$)", conteudo, re.DOTALL))
    no_texto = bool(dentro.strip())
    return {
        "raciocinou": bool(pensamento.strip()) or no_texto,
        "caracteresPensamento": len(pensamento.strip()) + len(dentro.strip()),
        "thinkNoTexto": no_texto,
        "tokensGerados": resposta.get("eval_count"),
        "duracaoMs": round((resposta.get("total_duration") or 0) / 1e6),
        "numCtx": num_ctx,
        "carregamentoMs": (round(resposta["load_duration"] / 1e6)
                           if resposta.get("load_duration") is not None else None),
        "resposta": conteudo[:200],
    }


def avaliar_prova_api(resposta: dict, thinking: str, duracao_ms: int) -> dict:
    """Le a resposta do ``/v1/messages`` da prova: raciocinio = bloco ``thinking`` ou
    ``redacted_thinking`` no ``content``. ``thinking`` e o que foi enviado (``disabled``)."""
    blocos = resposta.get("content") or []
    raciocinio = [b for b in blocos if isinstance(b, dict)
                  and b.get("type") in ("thinking", "redacted_thinking")]
    texto = "".join(b.get("text") or "" for b in blocos
                    if isinstance(b, dict) and b.get("type") == "text")
    return {
        "provedor": "anthropic",
        "thinking": thinking,
        "raciocinou": bool(raciocinio),
        "caracteresPensamento": sum(len(b.get("thinking") or b.get("data") or "") for b in raciocinio),
        "tokensGerados": (resposta.get("usage") or {}).get("output_tokens"),
        "duracaoMs": duracao_ms,
        "modeloRetornado": resposta.get("model"),
        "paradaPor": resposta.get("stop_reason"),
        "resposta": texto[:200],
    }


def sha256_arquivo(caminho: Path) -> str:
    return hashlib.sha256(caminho.read_bytes()).hexdigest()


def _opcoes_do_yml(application_yml: str, regras: dict[tuple[str, ...], dict[str, str]]) -> dict[str, str]:
    """Le chaves do ``application.yml`` pelo caminho dos nomes pais. ``regras`` mapeia o caminho (os
    ultimos nomes antes da chave) para ``{chave no yml: nome na saida}``; o valor sai como texto,
    sem o comentario do fim da linha."""
    caminho: list[tuple[int, str]] = []
    opcoes: dict[str, str] = {}
    for linha in application_yml.splitlines():
        if not linha.strip() or linha.lstrip().startswith("#"):
            continue
        recuo = len(linha) - len(linha.lstrip())
        chave, _, valor = linha.strip().partition(":")
        while caminho and caminho[-1][0] >= recuo:
            caminho.pop()
        nomes = [c[1] for c in caminho]
        for pais, aceitas in regras.items():
            if tuple(nomes[-len(pais):]) == pais and chave in aceitas:
                opcoes[aceitas[chave]] = valor.split(" #")[0].strip()
        caminho.append((recuo, chave))
    return opcoes


def opcoes_chat_ollama(application_yml: str) -> dict[str, str]:
    """Opcoes do chat do Ollama no ``application.yml`` de um commit (o bloco
    ``spring.ai.ollama.chat.options``), como texto: ``temperature``, ``num-ctx``,
    ``num-predict``, ``think-option``. So as que mudam a resposta; o modelo vem do executor."""
    return _opcoes_do_yml(application_yml, {("ollama", "chat", "options"): {
        k: k for k in ("temperature", "num-ctx", "num-predict", "think-option")}})


def opcoes_chat_anthropic(application_yml: str) -> dict[str, str]:
    """Opcoes do chat da Anthropic no ``application.yml`` de um commit (bloco
    ``spring.ai.anthropic.chat.options``): ``temperature``, ``max-tokens`` e ``thinking.type``
    (como ``thinking``). Vazio se o commit nao tem o provedor (antes do #483)."""
    return _opcoes_do_yml(application_yml, {
        ("anthropic", "chat", "options"): {"temperature": "temperature", "max-tokens": "max-tokens"},
        ("anthropic", "chat", "options", "thinking"): {"type": "thinking"}})


def resolver_placeholder(valor: str, env: dict) -> str:
    """``${AI_OLLAMA_NUM_CTX:8192}`` -> o valor da variavel no ambiente da celula, ou o padrao."""
    m = re.fullmatch(r"\$\{([A-Z0-9_]+):([^}]*)\}", valor)
    return env.get(m.group(1), m.group(2)) if m else valor


def onde_rodou(modelo_ps: dict) -> str:
    """GPU, CPU ou dividido, pela fracao do modelo na VRAM (``/api/ps``)."""
    total, vram = modelo_ps.get("size") or 0, modelo_ps.get("size_vram") or 0
    if not total:
        return "?"
    fracao = vram / total
    if fracao >= 0.99:
        return "GPU"
    if fracao <= 0.01:
        return "CPU"
    return f"dividido ({round(100 * fracao)}% na GPU)"


def decidir_retomada(anterior: dict, mesma: bool) -> str:
    """O que o ``--retomar`` faz com uma celula que ja tem manifesto: ``pular`` (medida, mesma
    configuracao), ``sem_prova`` (medida sem nenhuma prova do pensamento: refaz, a falha das duas
    provas costuma ser passageira), ``outra`` (medida com outra configuracao: recusa) ou ``medir``
    (a medicao anterior nao terminou)."""
    if mesma and (anterior.get("pensamento") or {}).get("semProva"):
        return "sem_prova"
    if anterior.get("codigoSaida") == 0:
        return "pular" if mesma else "outra"
    return "medir"


def avisos_das_provas(pensamento: dict, final: dict) -> list[str]:
    """Avisos das provas do pensamento (inicio e fim da celula); ajusta ``pensamento``.

    Prova que falhou nao prova nada: o aviso diz qual valeu e o resumo passa a ser o dela. Sem
    nenhuma, ``semProva`` torna a celula invalida (nao ha como mostrar o think=false).
    """
    inicio_erro, fim_erro = pensamento.get("erro"), final.get("erro")
    if inicio_erro and fim_erro:
        pensamento["semProva"] = True
        return [f"as duas provas do pensamento falharam (início: {inicio_erro}; fim: {fim_erro}): "
                "a célula não prova o think=false"]
    if fim_erro:
        return [f"a prova do pensamento no fim da célula falhou ({fim_erro}); vale só a do início"]
    if inicio_erro:
        pensamento["resumo"] = resumo_pensamento(final) + " (na prova do fim da célula)"
        return [f"a prova do pensamento no início da célula falhou ({inicio_erro}); vale só a do fim"]
    return []


def avisos_da_amostra(memoria: dict) -> list[str]:
    """Avisos que mudam a leitura da latencia: descarga/recarga e modelo dividido."""
    avisos = []
    for modelo, vezes in (memoria.get("descargas") or {}).items():
        if vezes:
            avisos.append(f"{modelo} saiu da memória {vezes} vez(es) no meio da célula (descarga e "
                          "recarga: a latência inclui o recarregamento)")
    for modelo, locais in (memoria.get("ondeRodou") or {}).items():
        if any(l.startswith("dividido") for l in locais) or len(set(locais)) > 1:
            avisos.append(f"{modelo} não ficou inteiro num lugar só ({', '.join(locais)})")
    if memoria.get("falhasAmostra"):
        avisos.append(f"{memoria['falhasAmostra']} amostras de memória falharam: o pico pode estar "
                      "subestimado")
    return avisos


#: Aviso gravado na celula com a fidelidade da lei ligada (#269); o relatorio o repete e explica.
AVISO_FIDELIDADE_LEI = (
    "fidelidade da rota de lei LIGADA (#269): a resposta de lei que nao passa na conferencia e "
    "descartada (desfecho FIDELIDADE_REPROVADA, que a regua conta como recusa e texto descartado), "
    "entao citacao e numero das perguntas de lei tendem a ficar abaixo, e a recusa correta acima "
    "por descarte, da celula sem fidelidade; a latencia inclui a chamada do juiz. Nao compare "
    "com as outras celulas: leia so ao lado da do mesmo modelo sem fidelidade")

#: Variaveis do ``.env.example`` que nao sao configuracao da Ceres medida: endereco, chave, modelo
#: (o da celula) e estrategia de pull (o executor baixa). O resto vale como publicado no commit.
FORA_DA_CONFIGURACAO = {"AI_API_KEY", "AI_API_BASE_URL", "AI_API_COMPLETIONS_PATH",
                        "AI_OLLAMA_BASE_URL", "AI_OLLAMA_PORT", "AI_CHAT_MODEL",
                        "AI_CHAT_PROVIDER", "AI_OLLAMA_PULL_STRATEGY",
                        "AI_ANTHROPIC_MODEL", "AI_ANTHROPIC_THINKING"}


def configuracao_publicada(env_example: str) -> dict[str, str]:
    """``AI_*`` do ``.env.example`` de um commit: a configuracao publicada da Ceres naquele SHA
    (teto de tokens, ``num_ctx``, timeouts...). A rodada usa exatamente ela, exceto o read-timeout
    da celula (``readTimeoutCelula`` do ``matriz.json``), que o ``ambiente_java`` aplica depois."""
    publicada = {}
    for linha in env_example.splitlines():
        linha = linha.strip()
        if not linha or linha.startswith("#") or "=" not in linha:
            continue
        chave, _, valor = linha.partition("=")
        chave = chave.strip()
        if chave.startswith("AI_") and chave not in FORA_DA_CONFIGURACAO:
            publicada[chave] = valor.split(" #")[0].strip().strip('"').strip("'")
    return publicada


def commits_da_issue(assuntos: list[str], issue: str) -> list[str]:
    """``<hash> <assunto>`` cujo assunto e o squash de um PR da issue: termina em
    ``(#270) (#NNN)`` ou ``(#270)`` seguido so do numero do PR."""
    numero = re.escape(issue.lstrip("#"))
    padrao = re.compile(rf"\(#{numero}\)\s*\(#\d+\)\s*$")
    return [a for a in assuntos if padrao.search(a)]


@dataclass
class Config:
    """Parametros da execucao (``matriz.json`` + linha de comando + ambiente)."""

    repo: Path
    matriz: dict
    saida: Path
    trabalho: Path
    modelos: list[str]
    arquiteturas: list[dict]
    repeticoes: int
    ollama_url: str
    ollama_container: str | None
    psql: list[str]
    db_origem: str
    db_prefixo: str
    hardware: str
    retomar: Path | None
    todas_arquiteturas: list[dict] = None
    teste: bool = False
    embedding_cpu: bool = False
    #: Rodada antes da revisao humana das externas (#358): vale para conferir, nao para a banca.
    ensaio: bool = False
    #: Checkout do repositorio cego (SupersonicTeam/ceres-benchmark-cego): o texto das ineditas.
    cego: Path | None = None
    #: Modelos por API pedidos (``modelosApi`` da matriz): celulas sem Ollama, so nas arquiteturas
    #: que a matriz lista (a Ceres 1 nao tem o provedor da Anthropic).
    modelos_api: list = field(default_factory=list)

    def celulas_da(self, arquitetura: dict) -> list[tuple[str, dict | None]]:
        """(modelo, api) das celulas da arquitetura: os locais (``api`` ``None``), depois os por API."""
        return ([(m, None) for m in self.modelos]
                + [(m["modelo"], m) for m in self.modelos_api
                   if arquitetura["nome"] in m.get("arquiteturas", [])])

    def celulas_api(self) -> list[tuple[dict, dict]]:
        """(arquitetura, api) das celulas por API que esta execucao vai rodar."""
        return [(a, m) for a in self.arquiteturas for m in self.modelos_api
                if a["nome"] in m.get("arquiteturas", [])]

    @property
    def tipo(self) -> str:
        return "teste" if self.teste else "ensaio" if self.ensaio else "banca"

    @property
    def read_timeout_celula(self) -> str | None:
        """``AI_READ_TIMEOUT`` da celula (``matriz.json``), aplicado depois da configuracao
        publicada. A carga a frio ou a fila da maquina local nao e propriedade da arquitetura: o
        desvio vale igual para as duas colunas. Sem a chave, vale o publicado."""
        return self.matriz.get("readTimeoutCelula")

    @property
    def embedding(self) -> str:
        """Nome do embedding que a celula usa (``bge-m3-cpu`` com ``--embedding-cpu``)."""
        base = self.matriz.get("embedding", "bge-m3")
        return f"{base}-cpu" if self.embedding_cpu else base



#: A regra unica de "revisada" (gabarito.py): a trava da banca e o relatorio leem a mesma.
ORIGENS_COM_REVISAO = gab.ORIGENS_COM_REVISAO
revisada = gab.revisada
perguntas_sem_revisao = gab.perguntas_sem_revisao


def juntar_ineditas(base: dict, pasta_cego: Path | None, tipo: str) -> tuple[dict, dict | None]:
    """O conjunto inteiro: as herdadas do backend mais as ineditas do repositorio cego.

    O backend guarda so o compromisso (``ineditas``: arquivo, versao, sha256 e ids). A rodada da
    banca exige o arquivo cego com o mesmo sha256, a mesma versao e os mesmos ids; ensaio e teste
    sem ele rodam so com as herdadas (marcado), e com ele diferente, com aviso.

    :raises RuntimeError: rodada da banca sem o conjunto cego ou com ele diferente do compromisso
    """
    compromisso = base.get("ineditas")
    if not compromisso:
        return base, None
    arquivo = (pasta_cego / compromisso["arquivo"]) if pasta_cego is not None else None
    if arquivo is None or not arquivo.exists():
        mensagem = (f"conjunto cego ausente ({arquivo or 'sem --cego'}): as {len(compromisso['ids'])} "
                    "ineditas ficam no repositorio SupersonicTeam/ceres-benchmark-cego; aponte para o "
                    "checkout dele com --cego ou CERES_BENCHMARK_CEGO")
        if tipo == "banca":
            raise RuntimeError(mensagem)
        log(f"AVISO: {mensagem}. Esta rodada mede so as herdadas.")
        return base, {"presente": False, "confere": False, "arquivo": str(arquivo) if arquivo else None}
    # Le uma vez: o hash e o conteudo sao dos mesmos bytes (BOM aceito, como no Bloco de Notas).
    bruto = arquivo.read_bytes()
    sha = hashlib.sha256(bruto).hexdigest()
    cego = json.loads(bruto.decode("utf-8-sig"))
    ids = [p["id"] for p in cego["perguntas"]]
    problemas = []
    if sha != compromisso["sha256"]:
        problemas.append(f"sha256 {sha[:12]} diferente do compromisso {compromisso['sha256'][:12]}")
    if cego.get("versao") != compromisso.get("versao"):
        problemas.append(f"versao {cego.get('versao')} diferente do compromisso {compromisso.get('versao')}")
    if sorted(ids) != sorted(compromisso["ids"]) or len(set(ids)) != len(ids):
        problemas.append("ids diferentes dos do compromisso")
    herdados = {p["id"] for p in base["perguntas"]}
    if herdados & set(ids):
        problemas.append(f"id repetido entre herdadas e ineditas: {sorted(herdados & set(ids))[:5]}")
    if problemas:
        mensagem = "o conjunto cego nao confere com o compromisso do backend: " + "; ".join(problemas)
        if tipo == "banca" or herdados & set(ids):
            raise RuntimeError(mensagem)
        log(f"AVISO: {mensagem}")
    junto = dict(base)
    junto["perguntas"] = list(base["perguntas"]) + list(cego["perguntas"])
    return junto, {"presente": True, "confere": not problemas, "arquivo": str(arquivo),
                   "versao": cego.get("versao"), "sha256": sha, "total": len(ids)}


def texto_canonico(conjunto: dict) -> str:
    """O texto do ``gabarito-canonico.json`` de uma rodada com as ineditas juntas."""
    return json.dumps(conjunto, ensure_ascii=False, indent=2) + "\n"

class Executor:
    """Roda a matriz; cada passo loga o que faz e falha cedo com a causa."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        # A chave nunca sai: se ela estiver no ambiente, tudo que o executor loga ou grava a oculta.
        registrar_segredo(os.environ.get("ANTHROPIC_API_KEY"))
        self.config_rodada: dict | None = None
        self.perguntas_info: dict = {}
        self.opcoes: dict = {}
        self.parecidas: list[str] = []
        self.parecidas_gabarito: list[str] = []

    # ------------------------------------------------------------------ utilitarios

    def rodar(self, comando: list[str], cwd: Path | None = None, entrada: bytes | None = None,
              env: dict | None = None, saida: Path | None = None, checar: bool = True) -> int:
        log("$ " + " ".join(shlex.quote(c) for c in comando))
        if saida is not None:
            with saida.open("wb") as arquivo:
                proc = subprocess.run(comando, cwd=cwd, input=entrada, env=env, stdout=arquivo,
                                      stderr=subprocess.STDOUT)
        else:
            proc = subprocess.run(comando, cwd=cwd, input=entrada, env=env)
        if checar and proc.returncode != 0:
            raise RuntimeError(f"comando falhou ({proc.returncode}): {comando[0]}")
        return proc.returncode

    def capturar(self, comando: list[str], cwd: Path | None = None) -> str:
        return subprocess.run(comando, cwd=cwd, check=True, capture_output=True,
                              text=True).stdout.strip()

    def psql(self, banco: str, sql: str) -> str:
        return self.capturar(self.cfg.psql + ["-d", banco, "-tAc", sql])

    def ollama(self, caminho: str, corpo: dict | None = None, timeout: float = 30) -> dict:
        dados = None if corpo is None else json.dumps(corpo).encode()
        requisicao = urllib.request.Request(
            self.cfg.ollama_url.rstrip("/") + caminho, data=dados,
            headers={"Content-Type": "application/json"}, method="GET" if corpo is None else "POST")
        with urllib.request.urlopen(requisicao, timeout=timeout) as resposta:
            return json.loads(resposta.read().decode() or "{}")

    def anthropic(self, corpo: dict, timeout: float = 120) -> dict:
        """``POST /v1/messages`` com a chave do ambiente (``ANTHROPIC_API_KEY``). So a prova do
        raciocinio usa: as perguntas da celula vao pela JVM. Nenhum teste chama este metodo de verdade."""
        requisicao = urllib.request.Request(
            self.url_anthropic() + "/v1/messages", data=json.dumps(corpo).encode(), method="POST",
            headers={"content-type": "application/json", "anthropic-version": VERSAO_API_ANTHROPIC,
                     "x-api-key": os.environ.get("ANTHROPIC_API_KEY", "")})
        with urllib.request.build_opener(_SemRedirecionamento).open(requisicao, timeout=timeout) as resposta:
            return json.loads(resposta.read().decode() or "{}")

    @staticmethod
    def url_anthropic() -> str:
        """Endereco da API (``BENCH_ANTHROPIC_URL`` ou o padrao), ja validado."""
        return validar_url_anthropic(os.environ.get("BENCH_ANTHROPIC_URL", URL_ANTHROPIC))

    # ------------------------------------------------------------------ passos

    def conferir_pre_requisitos(self) -> dict:
        for programa in ("git", "java"):
            if shutil.which(programa) is None:
                raise RuntimeError(f"{programa} nao encontrado no PATH")
        if not os.environ.get("DB_PASSWORD"):
            raise RuntimeError("DB_PASSWORD ausente: exporte a senha do Postgres (a do .env)")
        validar_read_timeout(self.cfg.read_timeout_celula)
        self.conferir_celulas_api()
        versao = self.ollama("/api/version").get("version", "?")
        self.provar_celulas_api()
        trechos = int(self.psql(self.cfg.db_origem, "select count(*) from legislacao_trecho") or 0)
        esperado = self.cfg.matriz.get("trechosLegislacao")
        if trechos == 0:
            raise RuntimeError(f"base de lei vazia em {self.cfg.db_origem}: copie conforme o "
                               "deploy/README.md antes de rodar")
        if esperado and trechos != esperado:
            log(f"AVISO: {trechos} trechos de lei em {self.cfg.db_origem}, esperado {esperado}; "
                "o numero vai no relatorio")
        return {"ollama": versao, "trechosLegislacao": trechos}

    def conferir_celulas_api(self) -> None:
        """Celula por API: provedor suportado, raciocinio desligado e a chave no ambiente.

        :raises RuntimeError: antes de qualquer trabalho de horas; sem a chave a celula nao roda
        """
        pedidas = self.cfg.celulas_api()
        if not pedidas:
            return
        for _, api in pedidas:
            if api.get("provedor") != "anthropic":
                raise RuntimeError(f"provedor {api.get('provedor')!r} de {api.get('modelo')} nao e "
                                   "suportado pelo executor (so anthropic)")
            if api.get("raciocinio") != "disabled":
                raise RuntimeError(f"{api.get('modelo')}: o raciocinio tem de ficar desligado "
                                   "(raciocinio=disabled na matriz): ele gastaria os tetos por chamada")
            validar_ambiente_da_celula(api)
        self.url_anthropic()  # endereco invalido para aqui, nao na ultima celula
        if not os.environ.get("ANTHROPIC_API_KEY"):
            raise RuntimeError(
                "ANTHROPIC_API_KEY ausente: a celula " + ", ".join(sorted({a["modelo"] for _, a in pedidas}))
                + " chama a API da Anthropic com a chave do ambiente de quem roda (nunca de arquivo). "
                "Exporte a variavel, ou rode so os modelos locais com --modelos")

    def provar_celulas_api(self) -> None:
        """Uma prova do raciocinio por modelo por API **antes** da rodada: a celula por API e a
        ultima de cada arquitetura, e chave errada, sem saldo ou sem acesso ao modelo so apareceria
        depois das celulas locais (horas). Custa uma pergunta curta por modelo.

        :raises RuntimeError: a API nao respondeu, ou o raciocinio veio ligado
        """
        vistos = set()
        for _, api in self.cfg.celulas_api():
            real = modelo_da_api(api)  # a variante prova o mesmo modelo da celula base: uma prova so
            if real in vistos:
                continue
            vistos.add(real)
            prova = self.provar_pensamento_api(real, api)
            if prova.get("erro"):
                raise RuntimeError(f"a API da Anthropic nao respondeu a prova de {real} "
                                   f"({prova['erro']}): confira a chave, o saldo e o acesso ao modelo "
                                   "antes de comecar a rodada")
            if prova.get("raciocinou"):
                raise RuntimeError(f"{real} raciocinou apesar de thinking={api['raciocinio']}: "
                                   "a celula seria invalida; nao vale comecar a rodada")
            log(f"{real} (API): pensamento {prova['resumo']}")

    def conferir_ambiente_no_commit(self) -> None:
        """Variante com ``ambiente`` (ex.: a fidelidade da lei): o ``application.yml`` do commit da
        arquitetura tem de ler cada variavel. O Spring ignora em silencio a que ele nao conhece, e
        a celula sairia com o nome da variante medindo a configuracao de antes dela. Conferido
        **antes** de qualquer trabalho de horas, em toda rodada (banca, ensaio ou teste).

        :raises RuntimeError: o commit nao conhece alguma variavel da celula
        """
        for arquitetura, api in self.cfg.celulas_api():
            ambiente = validar_ambiente_da_celula(api)
            if not ambiente:
                continue
            yml = self.capturar(["git", "show", f"{arquitetura['sha']}:src/main/resources/application.yml"],
                                self.cfg.repo)
            faltam = variaveis_que_o_commit_nao_conhece(ambiente, yml)
            if faltam:
                raise RuntimeError(
                    f"a celula {api['modelo']} pede {', '.join(faltam)}, que o application.yml de "
                    f"{arquitetura['nome']} em {arquitetura['sha'][:7]} nao le: ela mediria outra "
                    "configuracao que a do nome dela. Use um commit que as tenha (--ref-ceres2 <sha>, "
                    "valendo para todas as celulas da arquitetura) ou rode sem --com-fidelidade-lei")

    def resolver_sha(self, ref: str) -> str:
        return self.capturar(["git", "rev-parse", "--verify", ref + "^{commit}"], self.cfg.repo)

    def worktree(self, arquitetura: dict) -> Path:
        destino = self.cfg.trabalho / "worktrees" / f"{arquitetura['nome']}-{arquitetura['sha'][:7]}"
        if destino.exists():
            atual = self.capturar(["git", "rev-parse", "HEAD"], destino)
            if atual != arquitetura["sha"]:
                raise RuntimeError(f"{destino} esta em {atual[:7]}, nao em {arquitetura['sha'][:7]}:"
                                   " apague o worktree (git worktree remove)")
            return destino
        destino.parent.mkdir(parents=True, exist_ok=True)
        self.rodar(["git", "worktree", "add", "--detach", str(destino), arquitetura["sha"]],
                   self.cfg.repo)
        return destino

    def construir(self, worktree: Path) -> Path:
        jars = sorted(worktree.glob("target/*.jar"))
        jars = [j for j in jars if not j.name.endswith(("-plain.jar", ".original"))]
        if not jars:
            self.rodar(["sh", "mvnw", "-q", "-B", "-DskipTests", "-Djacoco.skip=true",
                        "-Dcheckstyle.skip", "package"], worktree)
            jars = [j for j in sorted(worktree.glob("target/*.jar"))
                    if not j.name.endswith("-plain.jar")]
        if not jars:
            raise RuntimeError(f"JAR nao gerado em {worktree}/target")
        return jars[0]

    def read_timeout(self, api: dict | None = None) -> str | None:
        """Read-timeout da celula. A celula por API usa o publicado: a carga a frio e a fila da
        maquina local, que justificam o ajuste, nao existem nela, e um aumento esconderia chamada
        que a producao (120 s) cortaria."""
        return None if api else self.cfg.read_timeout_celula

    def ambiente_java(self, banco: str, modelo: str, arquitetura: dict | None = None,
                      api: dict | None = None) -> dict:
        """Ambiente da JVM: sem nenhum ``AI_*`` herdado do shell (um ``export AI_MAX_TOKENS``
        esquecido mediria outra configuracao), com a configuracao publicada do commit e o
        read-timeout da celula (``readTimeoutCelula``).

        Celula por API (``api``): provedor ``anthropic``, modelo e raciocinio da matriz e a chave
        ``ANTHROPIC_API_KEY`` do ambiente. So essa celula leva a chave: as locais nunca a veem.
        """
        env = {k: v for k, v in os.environ.items()
               if not k.startswith("AI_") and k != "ANTHROPIC_API_KEY"}
        if arquitetura is not None:
            env.update(arquitetura.get("configuracaoPublicada") or {})
        # Variante da celula por API (ex.: fidelidade da lei): depois da publicada, antes do que o
        # executor fixa (provedor, modelo, chave e endereco nunca saem do lugar).
        if api is not None:
            env.update(validar_ambiente_da_celula(api))
        # Depois da publicada, a unica excecao a ela (``matriz.json``): ver Config.read_timeout_celula.
        validar_read_timeout(self.cfg.read_timeout_celula)
        leitura = self.read_timeout(api)
        if leitura:
            env["AI_READ_TIMEOUT"] = leitura
        env.update({
            "SPRING_PROFILES_ACTIVE": "dev,avaliacao",
            "DB_NAME": banco,
            "AI_OLLAMA_BASE_URL": self.cfg.ollama_url,
            "AI_OLLAMA_PULL_STRATEGY": "never",
        })
        if api is None:
            env.update({
                "AI_CHAT_PROVIDER": "ollama",
                "AI_CHAT_MODEL": modelo,
                # O pensamento e desligado pela mesma propriedade que a producao usa; a prova confere.
                "AI_OLLAMA_THINK": "false",
            })
        else:
            # O embedding segue no Ollama local (bge-m3); so o chat vai pela API.
            env.update({
                "AI_CHAT_PROVIDER": api["provedor"],
                "AI_ANTHROPIC_MODEL": modelo_da_api(api),
                "AI_ANTHROPIC_THINKING": api["raciocinio"],
                "ANTHROPIC_API_KEY": os.environ.get("ANTHROPIC_API_KEY", ""),
            })
        return env

    def argumentos_avaliacao(self, gabarito: Path, saida: Path, api: dict | None = None) -> list[str]:
        # O relatorio do comando diz onde o modelo rodou: na celula por API, a inferencia nao e da
        # maquina e a latencia inclui a rede.
        hardware = (self.cfg.hardware if api is None else
                    f"{self.cfg.hardware} (JVM, banco e embedding); chat pela API da Anthropic, "
                    "latência de rede, não de inferência local")
        return [
            "--spring.docker.compose.enabled=false",
            f"--avaliacao.gabarito={gabarito}",
            f"--avaliacao.saida={saida}",
            f"--avaliacao.repeticoes={self.cfg.repeticoes}",
            f"--avaliacao.hardware={hardware}",
        ] + ([f"--spring.ai.ollama.embedding.options.model={self.cfg.embedding}"]
             if self.cfg.embedding_cpu else [])

    def preparar_banco(self, arquitetura: dict, worktree: Path, jar: Path, logs: Path) -> str:
        """Banco proprio da arquitetura: Liquibase do commit + base de lei copiada da origem."""
        # Um banco por commit: o schema e o seed sao do commit, e um banco de outro SHA (main que
        # andou, --ref-ceres2) pararia no seed de outra versao ou num schema de outro commit.
        banco = f"{self.cfg.db_prefixo}_{arquitetura['nome'].replace('-', '_')}_{arquitetura['sha'][:7]}"
        trechos = self.psql(banco, "select count(*) from legislacao_trecho") \
            if self._banco_existe(banco) and self._tabela_existe(banco) else ""
        origem = self.psql(self.cfg.db_origem, "select count(*) from legislacao_trecho")
        if trechos and trechos == origem:
            log(f"banco {banco} pronto ({trechos} trechos): reaproveitado")
            return banco
        self.rodar(self.cfg.psql + ["-d", "postgres", "-c", f'DROP DATABASE IF EXISTS "{banco}"'])
        self.rodar(self.cfg.psql + ["-d", "postgres", "-c", f'CREATE DATABASE "{banco}"'])
        # O comando de avaliacao aplica o Liquibase na subida e, com um id que nao existe, para
        # antes do seed (codigo 1 esperado): e a forma de criar o schema daquele commit sem web.
        self.rodar(["java", "-jar", str(jar), "--spring.docker.compose.enabled=false",
                    "--avaliacao.ids=__schema_apenas__"], worktree,
                   env=self.ambiente_java(banco, "nenhum", arquitetura),
                   saida=logs / f"schema-{banco}.log",
                   checar=False)
        if not self._tabela_existe(banco):
            raise RuntimeError(f"Liquibase nao criou legislacao_trecho em {banco}: ver "
                               f"{logs}/schema-{banco}.log")
        exportar = self.cfg.psql + ["-d", self.cfg.db_origem, "-c",
                                    "\\copy (select id, content, metadata, embedding from "
                                    "legislacao_trecho) to stdout"]
        importar = self.cfg.psql + ["-d", banco, "-c", "\\copy legislacao_trecho(id, content, "
                                    "metadata, embedding) from stdin"]
        # Em stream (os vetores em texto passam de 100 MB): nada fica na memoria do Python, que e
        # a da maquina medida.
        log("copiando a base de lei de " + self.cfg.db_origem + " para " + banco)
        origem_proc = subprocess.Popen(exportar, stdout=subprocess.PIPE)
        destino_proc = subprocess.Popen(importar, stdin=origem_proc.stdout)
        origem_proc.stdout.close()
        if destino_proc.wait() != 0 or origem_proc.wait() != 0:
            raise RuntimeError(f"copia da base de lei para {banco} falhou")
        copiados = self.psql(banco, "select count(*) from legislacao_trecho")
        if copiados != origem:
            raise RuntimeError(f"copia da base de lei incompleta: {copiados} de {origem}")
        log(f"banco {banco}: schema do {arquitetura['sha'][:7]} + {copiados} trechos de lei")
        return banco

    def _banco_existe(self, banco: str) -> bool:
        return self.psql("postgres", f"select 1 from pg_database where datname = '{banco}'") == "1"

    def _tabela_existe(self, banco: str) -> bool:
        return self.psql(banco, "select to_regclass('public.legislacao_trecho') is not null") == "t"

    def modelos_locais(self) -> set[str]:
        nomes = set()
        for m in self.ollama("/api/tags").get("models", []):
            nome = m.get("name") or ""
            nomes.update({nome, nome.removesuffix(":latest")})
        return nomes

    def baixar_modelos(self) -> None:
        """``pull`` so do que falta. Puxar de novo um modelo presente trocaria os pesos por uma
        republicacao do upstream no meio da rodada, e a conferencia de digest a recusaria sem volta."""
        base = self.cfg.matriz.get("embedding", "bge-m3")
        locais = self.modelos_locais()
        for modelo in self.cfg.modelos + [base]:
            if modelo in locais:
                log(f"{modelo} ja esta no Ollama: sem pull")
                continue
            log(f"ollama pull {modelo}")
            self.ollama("/api/pull", {"model": modelo, "stream": False}, timeout=3600)
        if self.cfg.embedding_cpu and self.cfg.embedding not in locais:
            # Mesmos pesos do bge-m3 (os vetores da base de lei continuam valendo), sem camada na
            # GPU: a VRAM fica toda para o modelo de chat. Equivale ao Modelfile do README.
            log(f"criando {self.cfg.embedding} (num_gpu 0) a partir de {base}")
            self.ollama("/api/create", {"model": self.cfg.embedding, "from": base,
                                        "parameters": {"num_gpu": 0}, "stream": False}, timeout=600)

    def configuracao_ollama(self) -> dict:
        """Versao do Ollama e, por modelo, quantizacao, tamanho e digest: o que tem de ser igual nas
        celulas."""
        digests = {m.get("name"): m.get("digest") for m in self.ollama("/api/tags").get("models", [])}
        modelos = {}
        for modelo in self.cfg.modelos + [self.cfg.embedding]:
            detalhes = self.ollama("/api/show", {"model": modelo}).get("details") or {}
            nome = modelo if ":" in modelo else modelo + ":latest"
            modelos[modelo] = {"quantizacao": detalhes.get("quantization_level"),
                               "parametros": detalhes.get("parameter_size"),
                               "formato": detalhes.get("format"),
                               "digest": digests.get(nome) or digests.get(modelo)}
        return {"versao": self.ollama("/api/version").get("version"), "modelos": modelos}

    def conferir_configuracao(self, gravada: dict | None) -> dict:
        """A configuracao atual do Ollama e a gravada na rodada sao iguais (mesma versao, mesma
        quantizacao e digest por modelo); a primeira vez, grava.

        :raises RuntimeError: se mudou: as celulas nao seriam comparaveis
        """
        atual = self.configuracao_ollama()
        if gravada is None:
            return atual
        diferencas = []
        mesclada = {"versao": gravada.get("versao"), "modelos": dict(gravada.get("modelos") or {})}
        if atual["versao"] != gravada.get("versao"):
            diferencas.append(f"Ollama {gravada.get('versao')} -> {atual['versao']}")
        for modelo, detalhe in atual["modelos"].items():
            antes = (gravada.get("modelos") or {}).get(modelo)
            if antes is None:
                # Modelo que entrou numa retomada (--modelos): trava a partir de agora.
                mesclada["modelos"][modelo] = detalhe
            elif antes != detalhe:
                diferencas.append(f"{modelo}: {antes} -> {detalhe}")
        if diferencas:
            raise RuntimeError("a configuracao do Ollama mudou desde o inicio da rodada ("
                               + "; ".join(diferencas) + "): as celulas nao seriam comparaveis. "
                               "Volte a configuracao ou comece uma rodada nova")
        return mesclada

    def descarregar_outros(self, manter: set[str]) -> None:
        """Tira da memoria os modelos de chat de outras celulas: a memoria medida e so a deste."""
        for carregado in self.ollama("/api/ps").get("models", []):
            nome = carregado.get("name") or carregado.get("model")
            if nome and nome not in manter:
                self.ollama("/api/generate", {"model": nome, "keep_alive": 0}, timeout=120)

    def num_ctx(self, arquitetura: dict) -> int | None:
        """``num_ctx`` do chat da arquitetura, como inteiro (``self.opcoes`` guarda texto); ``None``
        se o commit nao o publica ou nao e numero."""
        valor = ((self.opcoes.get(arquitetura["nome"]) or {}).get("chat") or {}).get("num-ctx")
        return int(valor) if valor is not None and str(valor).strip().isdigit() else None

    def provar_pensamento(self, modelo: str, num_ctx: int | None = None) -> dict:
        """Prova do ``think=false`` num ``/api/chat``. Com o ``num_ctx`` da celula o Ollama carrega
        o modelo ja com o contexto da Ceres (sem ele, carrega com o padrao dele e a JVM recarrega
        depois, com o amostrador de memoria ligado: o pico sairia superestimado). ``keep_alive``
        explicito mantem o modelo carregado ate a celula comecar."""
        opcoes = {"num_predict": 256, "temperature": 0.2}
        if num_ctx is not None:
            opcoes["num_ctx"] = num_ctx
        corpo = {"model": modelo, "stream": False, "think": False, "keep_alive": KEEP_ALIVE_PROVA,
                 "messages": [{"role": "user", "content": PERGUNTA_PROVA}],
                 "options": opcoes}
        try:
            prova = avaliar_prova(self.ollama("/api/chat", corpo, timeout=600), num_ctx)
        except ERROS_HTTP as e:
            prova = {"erro": f"{type(e).__name__}: {e}"}
        try:
            capacidades = self.ollama("/api/show", {"model": modelo}).get("capabilities")
        except ERROS_HTTP:
            capacidades = None
        prova["capacidades"] = capacidades
        prova["resumo"] = resumo_pensamento(prova)
        return prova

    def provar_pensamento_api(self, modelo: str, api: dict) -> dict:
        """Prova do raciocinio desligado na API: uma pergunta curta com ``thinking`` igual ao que a
        JVM manda (``disabled``) e a conferencia de que nenhum bloco de raciocinio voltou. Sem
        keep_alive, num_ctx nem memoria: nada disso existe na API."""
        corpo = {"model": modelo, "max_tokens": 256, "thinking": {"type": api["raciocinio"]},
                 "messages": [{"role": "user", "content": PERGUNTA_PROVA}]}
        inicio = time.monotonic()
        try:
            prova = avaliar_prova_api(self.anthropic(corpo), api["raciocinio"],
                                      round((time.monotonic() - inicio) * 1000))
        except ERROS_HTTP as e:
            # A mensagem do erro passa por ocultar: nenhum texto da chave sai em JSON nem em log.
            prova = {"provedor": "anthropic", "erro": ocultar(f"{type(e).__name__}: {e}")}
        prova["capacidades"] = None
        prova["resumo"] = resumo_pensamento(prova)
        return prova

    def memoria_processo(self) -> int | None:
        """RSS do Ollama: ``docker stats`` do container ou soma dos processos ``ollama``."""
        try:
            if self.cfg.ollama_container:
                texto = self.capturar(["docker", "stats", "--no-stream", "--format",
                                       "{{.MemUsage}}", self.cfg.ollama_container])
                return bytes_de_docker_stats(texto)
            saida = self.capturar(["ps", "-axo", "rss=,comm="])
            total = sum(int(l.split(None, 1)[0]) * 1024 for l in saida.splitlines()
                        if "ollama" in l.lower() and l.split(None, 1)[0].isdigit())
            return total or None
        except (subprocess.CalledProcessError, OSError, ValueError):
            return None

    def amostrar_memoria(self, modelo: str, parar: threading.Event, resultado: dict) -> None:
        resultado["falhasAmostra"] = 0
        acompanhados = {modelo: modelo, self.cfg.embedding: self.cfg.embedding,
                        self.cfg.embedding + ":latest": self.cfg.embedding}
        presentes: dict[str, bool] = {}
        resultado["descargas"] = {modelo: 0, self.cfg.embedding: 0}
        resultado["ondeRodou"] = {}
        while not parar.wait(5):
            try:
                carregados = set()
                for m in self.ollama("/api/ps", timeout=5).get("models", []):
                    nome = acompanhados.get(m.get("name") or m.get("model"))
                    if nome is None:
                        continue
                    carregados.add(nome)
                    lugar = onde_rodou(m)
                    lugares = resultado["ondeRodou"].setdefault(nome, [])
                    if lugar not in lugares:
                        lugares.append(lugar)
                    if nome == modelo:
                        resultado["modeloBytes"] = max(resultado.get("modeloBytes", 0), m.get("size", 0))
                        resultado["modeloVramBytes"] = max(resultado.get("modeloVramBytes", 0),
                                                           m.get("size_vram", 0))
                        resultado["contexto"] = m.get("context_length")
                for nome in (modelo, self.cfg.embedding):
                    # Estava na memoria e saiu: o Ollama descarregou (falta de VRAM, keep_alive).
                    if presentes.get(nome) and nome not in carregados:
                        resultado["descargas"][nome] += 1
                        log(f"AVISO: {nome} saiu da memoria no meio da celula")
                    presentes[nome] = nome in carregados
            except ERROS_HTTP:
                # Conta a falha em vez de morrer: uma thread morta deixaria o pico subestimado
                # sem aviso no relatorio.
                resultado["falhasAmostra"] += 1
            rss = self.memoria_processo()
            if rss:
                resultado["processoPicoBytes"] = max(resultado.get("processoPicoBytes", 0), rss)
        resultado["metodoProcesso"] = (f"docker stats {self.cfg.ollama_container}"
                                       if self.cfg.ollama_container else "ps (processos ollama)")

    def rodar_celula(self, rodada_dir: Path, arquitetura: dict, worktree: Path, jar: Path,
                     banco: str, gabarito: Path, modelo: str, api: dict | None = None) -> dict:
        """Uma celula. ``api`` (um item de ``modelosApi``) = chat pela API: sem ``pull``, sem
        descarga de modelos, sem amostra de memoria e sem ``onde rodou`` (nada disso existe nela); a
        prova do pensamento vira a conferencia de ``thinking=disabled`` na API."""
        nome = nome_celula(arquitetura["nome"], modelo)
        destino = rodada_dir / nome
        manifesto_arquivo = destino / "celula.json"
        if manifesto_arquivo.exists():
            anterior = json.loads(manifesto_arquivo.read_text(encoding="utf-8"))
            # O read-timeout entra na comparacao (sem default): celula medida antes dele existir, ou
            # com outro valor, nao se mistura com as novas.
            # O ambiente da variante tambem (celula antiga e celula comum nao tem: ``{}``).
            mesma = (anterior.get("sha"), anterior.get("modelo"), anterior.get("repeticoes"),
                     anterior.get("embedding", self.cfg.embedding),
                     anterior.get("readTimeoutCelula"), anterior.get("ambienteCelula") or {}) == (
                arquitetura["sha"], modelo, self.cfg.repeticoes, self.cfg.embedding,
                self.read_timeout(api), (api or {}).get("ambiente") or {})
            decisao = decidir_retomada(anterior, mesma)
            if decisao == "pular":
                log(f"{nome}: ja medida nesta rodada, pulando")
                return anterior
            if decisao == "outra":
                raise RuntimeError(f"{nome} ja foi medida com outro SHA, modelo, embedding, numero "
                                   "de repeticoes, read-timeout ou ambiente da variante: use uma rodada nova "
                                   "em vez de --retomar")
            if decisao == "sem_prova":
                log(f"{nome}: medida sem nenhuma prova do pensamento, medindo de novo")
        destino.mkdir(parents=True, exist_ok=True)
        temporario = destino / "saida-do-comando"
        shutil.rmtree(temporario, ignore_errors=True)
        temporario.mkdir()

        self.config_rodada = self.conferir_configuracao(self.config_rodada)
        if api is None:
            self.descarregar_outros({self.cfg.embedding, self.cfg.embedding + ":latest"})
        num_ctx = None if api else self.num_ctx(arquitetura)

        def provar() -> dict:
            return (self.provar_pensamento_api(modelo_da_api(api), api) if api
                    else self.provar_pensamento(modelo, num_ctx))

        pensamento = provar()
        pensamento["raciocinouInicio"] = bool(pensamento.get("raciocinou"))
        log(f"{nome}: pensamento {pensamento['resumo']}")

        lido = {"arquivoLido": gabarito.name, "sha256Lido": sha256_arquivo(gabarito)}
        memoria: dict = {}
        parar = threading.Event()
        # Na celula por API nao ha modelo na memoria local para amostrar.
        amostrador = (None if api else
                      threading.Thread(target=self.amostrar_memoria, args=(modelo, parar, memoria),
                                       daemon=True))
        if amostrador:
            amostrador.start()
        env = self.ambiente_java(banco, modelo, arquitetura, api)
        inicio = time.monotonic()
        try:
            codigo = self.rodar(["java", "-jar", str(jar)]
                                + self.argumentos_avaliacao(gabarito, temporario, api),
                                worktree, env=env, saida=destino / "comando.log", checar=False)
        finally:
            parar.set()
            if amostrador:
                amostrador.join(timeout=30)
        duracao = round(time.monotonic() - inicio)
        # A prova vale para a celula inteira: repetida no fim (o Ollama pode ter recarregado o
        # modelo com outro template no meio). Raciocinou em qualquer uma = celula invalida.
        final = provar()
        pensamento = {**pensamento, "final": final,
                      "raciocinou": bool(pensamento.get("raciocinou") or final.get("raciocinou"))}
        if final.get("raciocinou") and not pensamento.get("raciocinouInicio"):
            pensamento["resumo"] = resumo_pensamento(final) + " (na prova do fim da célula)"
        if pensamento["raciocinou"]:
            log(f"AVISO: {nome} raciocinou apesar de think=false: celula invalida para comparar")
        avisos = avisos_da_amostra(memoria)
        for aviso in avisos_das_provas(pensamento, final):
            avisos.append(aviso)
            log(f"AVISO: {nome}: {aviso}")
        arquivos = self._renomear_saida(temporario, destino, nome)
        for aviso in self._avisos_de_falha_tecnica(destino / f"{nome}.json"):
            avisos.append(aviso)
            log(f"AVISO: {nome}: {aviso}")
        if api:
            avisos.append("o chat desta célula foi pela API da Anthropic: a latência é de API pela "
                          "rede (com a fila do provedor), não de inferência local; não a compare "
                          "com a das células do qwen3")
            if api.get("variante") == "fidelidade-lei":
                avisos.append(AVISO_FIDELIDADE_LEI)
            for achado in ocultar_em_arquivos(destino):
                aviso = f"a chave da API apareceu em {achado} e foi redigida; confira antes de publicar"
                avisos.append(aviso)
                log(f"AVISO: {nome}: {aviso}")
        manifesto = {
            "celula": nome,
            "arquitetura": arquitetura["nome"],
            "rotuloArquitetura": arquitetura["rotulo"],
            "sha": arquitetura["sha"],
            "modelo": modelo,
            "embedding": self.cfg.embedding,
            "repeticoes": self.cfg.repeticoes,
            "readTimeoutCelula": self.read_timeout(api),
            "readTimeoutEfetivo": env.get("AI_READ_TIMEOUT"),
            "provedor": api["provedor"] if api else "ollama",
            "modeloApi": modelo_da_api(api) if api else None,
            "variante": api.get("variante") if api else None,
            "ambienteCelula": (api.get("ambiente") or {}) if api else {},
            "inferenciaEm": "API da Anthropic (rede)" if api else "local (Ollama)",
            "numCtx": num_ctx,
            "tipo": self.cfg.tipo,
            "perguntas": {**self.perguntas_info, **lido},
            "hardware": self.cfg.hardware,
            "banco": banco,
            "gabarito": gabarito.name,
            "pensamento": pensamento,
            "memoria": memoria,
            "avisos": avisos,
            "duracaoSegundos": duracao,
            "codigoSaida": codigo,
            "arquivos": arquivos,
            "terminadaEm": datetime.now(ZONA).isoformat(timespec="seconds"),
        }
        manifesto_arquivo.write_text(json.dumps(manifesto, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
        log(f"{nome}: codigo {codigo} em {duracao} s"
            + ("" if codigo == 0 else f" (ver {destino}/comando.log)"))
        return manifesto

    @staticmethod
    def _avisos_de_falha_tecnica(avaliacao: Path) -> list[str]:
        """Avisos de falha tecnica a partir do JSON que o comando gravou (nenhum se ele nao existe:
        a celula saiu com codigo diferente de zero e o relatorio ja a mostra assim)."""
        from . import relatorio
        if not avaliacao.exists():
            return []
        try:
            rodadas = relatorio.rodadas_do_json(json.loads(avaliacao.read_text(encoding="utf-8")))
        except (OSError, ValueError, KeyError) as e:
            # Celula com codigo 0 e JSON ilegivel: sem este aviso ela pareceria limpa.
            return [f"não foi possível ler {avaliacao.name} para contar as falhas técnicas "
                    f"({type(e).__name__}): confira a célula antes de usá-la"]
        return avisos_de_falha_tecnica(rodadas)

    @staticmethod
    def _renomear_saida(temporario: Path, destino: Path, nome: str) -> list[str]:
        """``AAAA-MM-DD-HHmm-<modelo>.*`` do comando -> ``<celula>.*`` (o resto do nome e o sufixo)."""
        gravados = []
        for arquivo in sorted(temporario.iterdir()):
            sufixo = next((s for s in ("-clareza-cego.csv", "-clareza-chave.json", ".md", ".json")
                           if arquivo.name.endswith(s)), None)
            datado = re.match(r"\d{4}-\d{2}-\d{2}-\d{4}-", arquivo.name)
            novo = destino / (nome + sufixo if sufixo and datado else arquivo.name)
            shutil.move(str(arquivo), novo)
            gravados.append(novo.name)
        temporario.rmdir()
        return gravados

    # ------------------------------------------------------------------ orquestracao

    def executar(self) -> Path:
        cfg = self.cfg
        estado = self.conferir_pre_requisitos()
        rodada_dir = cfg.retomar or (cfg.saida / datetime.now(ZONA).strftime("%Y-%m-%d-%H%M"))
        rodada_dir.mkdir(parents=True, exist_ok=True)
        logs = rodada_dir / "logs"
        logs.mkdir(exist_ok=True)
        arquivo_rodada = rodada_dir / "rodada.json"
        anterior = (json.loads(arquivo_rodada.read_text(encoding="utf-8"))
                    if arquivo_rodada.exists() else None)
        if anterior and anterior.get("tipo", "banca") != cfg.tipo:
            raise RuntimeError(f"nao misture rodada de {anterior.get('tipo', 'banca')} com rodada de "
                               f"{cfg.tipo}: use uma pasta nova")
        self.fixar_shas(anterior)
        self.conferir_ambiente_no_commit()
        if anterior:
            self.conferir_retomada(anterior)

        self.preparar_gabaritos(rodada_dir)
        self.gravar_similaridade(rodada_dir)

        self.baixar_modelos()
        self.config_rodada = self.conferir_configuracao((anterior or {}).get("configuracaoOllama"))
        self.opcoes = self.conferir_opcoes_chat()
        arquivo_rodada.write_text(json.dumps(
            self.manifesto_rodada(anterior, estado), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")

        for arquitetura in cfg.arquiteturas:
            worktree = self.worktree(arquitetura)
            jar = self.construir(worktree)
            banco = self.preparar_banco(arquitetura, worktree, jar, logs)
            for modelo, api in cfg.celulas_da(arquitetura):
                self.rodar_celula(rodada_dir, arquitetura, worktree, jar, banco,
                                  arquitetura["gabarito"], modelo, api)
        return rodada_dir

    def preparar_gabaritos(self, rodada_dir: Path) -> None:
        """Conjunto congelado (#352) e a copia adaptada de cada arquitetura da matriz.

        O conjunto e ``matriz["perguntas"]`` (o arquivo do repositorio, conferido contra o
        ``.sha256`` ao lado), o mesmo para todas as celulas: a arquitetura que nao precisa de
        adaptacao le **o proprio arquivo**; a outra, a copia adaptada (o hash dela tambem vai na
        celula). Sem ``perguntas`` na matriz, vale o gabarito do commit da ultima arquitetura.

        :raises RuntimeError: hash que nao confere, capacidade marcada errada, ou a arquitetura
            canonica (a ultima) sem saber medir alguma pergunta
        """
        cfg = self.cfg
        todas = cfg.todas_arquiteturas
        if cfg.matriz.get("perguntas"):
            arquivo = cfg.repo / cfg.matriz["perguntas"]
            sha = sha256_arquivo(arquivo)
            lado = arquivo.with_name(arquivo.name + ".sha256")
            partes = lado.read_text(encoding="utf-8").split() if lado.exists() else []
            if not partes:
                raise RuntimeError(f"{lado.name} ausente ou vazio: o conjunto congelado exige o hash")
            if partes[0] != sha:
                raise RuntimeError(f"{arquivo.name} nao confere com {lado.name}: mudou o conjunto "
                                   "sem mudar a versao e o hash")
            canonico_txt = arquivo.read_text(encoding="utf-8")
        else:
            arquivo = None
            canonico_txt = self.capturar(["git", "show", f"{todas[-1]['sha']}:{GABARITO_REPO}"],
                                         cfg.repo) + "\n"
            sha = hashlib.sha256(canonico_txt.encode()).hexdigest()
        # Tudo vai primeiro para uma pasta de preparo e so entra na rodada depois de todas as
        # conferencias: uma recusa nao deixa a pasta com o gabarito de uma medicao e as celulas de
        # outra.
        preparo = rodada_dir / ".preparo"
        shutil.rmtree(preparo, ignore_errors=True)
        preparo.mkdir()
        canonico = json.loads(canonico_txt)
        if arquivo is not None and canonico.get("baseadoEm") and not canonico.get("rodadaRealizada"):
            # O conjunto vem da arvore local e a Ceres 2.0 do commit medido: um checkout atrasado
            # passa no hash com herdadas velhas (#371). O baseadoEm tem de ser o gabarito do commit.
            gabarito_commit = json.loads(self.capturar(
                ["git", "show", f"{todas[-1]['sha']}:{GABARITO_REPO}"], cfg.repo))
            if gabarito_commit.get("versao") != canonico["baseadoEm"]:
                mensagem = (f"o conjunto e baseado no gabarito {canonico['baseadoEm']}, mas o commit "
                            f"medido ({todas[-1]['sha'][:7]}) tem o {gabarito_commit.get('versao')}: "
                            "atualize o conjunto (herdadas, versao, baseadoEm e hash) ou o checkout")
                if cfg.tipo == "banca":
                    raise RuntimeError(mensagem)
                log(f"AVISO: {mensagem}")
        # As ineditas vem do repositorio cego (#387), conferidas contra o compromisso do backend.
        canonico, info_cego = juntar_ineditas(canonico, cfg.cego, cfg.tipo)
        if canonico.get("ineditas"):
            canonico_txt = texto_canonico(canonico)
        (preparo / "gabarito-canonico.json").write_text(canonico_txt, encoding="utf-8")
        if cfg.tipo == "banca":
            # Revisao humana bloqueante (#358, #387): dispositivos, classe e afirmacoes proibidas
            # das externas (e o valor das ineditas de dados) sao provisorios ate a equipe ou o
            # orientador revisarem pela planilha do repositorio cego (revisado.por); sem contador.
            sem_revisao = perguntas_sem_revisao(canonico["perguntas"])
            if sem_revisao:
                raise RuntimeError(
                    f"{len(sem_revisao)} perguntas sem a revisao da equipe ou do orientador (aConferir "
                    f"ou sem revisado.por: {', '.join(sem_revisao[:5])}{'...' if len(sem_revisao) > 5 else ''})"
                    ": a rodada da banca espera a planilha de revisao do repositorio cego; para conferir "
                    "antes, use --ensaio")
        # sha256 = o arquivo do backend; sha256Junto = o gabarito-canonico.json da rodada (herdadas
        # mais ineditas), que e o que a Ceres 2.0 le (o sha256Lido do celula.json).
        self.perguntas_info = {"arquivo": cfg.matriz.get("perguntas") or GABARITO_REPO,
                               "versao": canonico.get("versao"), "sha256": sha,
                               "sha256Junto": hashlib.sha256(canonico_txt.encode("utf-8")).hexdigest(),
                               "total": len(canonico["perguntas"]), "cego": info_cego}

        adaptacoes_por_arq = {}
        for arquitetura in todas:
            fonte = self.capturar(["git", "show", f"{arquitetura['sha']}:{FERRAMENTAS_REPO}"], cfg.repo)
            funcoes = gab.assinaturas_ferramentas(fonte)
            conhecidas = arquitetura.get("funcoesConhecidas")
            if conhecidas is not None and sorted(conhecidas) != sorted(funcoes):
                raise RuntimeError(f"funcoes do {arquitetura['sha'][:7]} diferentes das da matriz "
                                   f"({sorted(funcoes)} x {sorted(conhecidas)}): atualize "
                                   "funcoesConhecidas e o campo capacidade do conjunto")
            adaptado, adaptacoes = gab.adaptar(
                canonico, funcoes, arquitetura.get("remocoesExtras"),
                arquitetura.get("indiceDeLista", True))
            adaptacoes_por_arq[arquitetura["nome"]] = adaptacoes
            arquivo_adapt = preparo / f"adaptacao-{arquitetura['nome']}.json"
            arquivo_adapt.write_text(json.dumps(adaptacoes, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
            arquitetura["adaptacao"] = arquivo_adapt.name
            if not adaptacoes:
                # Le a copia congelada da rodada (o conjunto do backend, com as ineditas juntas: o
                # sha256Junto de perguntas_info): um checkout no meio de uma rodada de horas nao troca
                # as perguntas das celulas seguintes.
                arquitetura["gabarito"] = rodada_dir / "gabarito-canonico.json"
            else:
                arquivo_gab = preparo / f"gabarito-{arquitetura['nome']}.json"
                arquivo_gab.write_text(json.dumps(adaptado, ensure_ascii=False, indent=2) + "\n",
                                       encoding="utf-8")
                arquitetura["gabarito"] = rodada_dir / arquivo_gab.name
            log(f"{arquitetura['rotulo']} ({arquitetura['sha'][:7]}): {len(adaptacoes)} perguntas "
                "adaptadas")
        if adaptacoes_por_arq[todas[-1]["nome"]]:
            raise RuntimeError(f"a {todas[-1]['rotulo']} nao sabe medir "
                               f"{sorted(adaptacoes_por_arq[todas[-1]['nome']])}: o conjunto tem de "
                               "caber inteiro na arquitetura canonica")
        problemas = gab.conferir_capacidade(canonico, adaptacoes_por_arq)
        if problemas:
            raise RuntimeError("campo capacidade errado no conjunto: " + "; ".join(problemas))
        for arquivo_preparo in preparo.iterdir():
            os.replace(arquivo_preparo, rodada_dir / arquivo_preparo.name)
        preparo.rmdir()

    def conferir_retomada(self, anterior: dict) -> None:
        """Retomar so a mesma rodada: o mesmo conjunto (hash), o mesmo embedding, e nunca uma rodada
        anterior ao conjunto congelado. Conferido **antes** de reescrever qualquer arquivo da pasta.

        :raises RuntimeError: se a retomada misturaria duas medicoes diferentes
        """
        cfg = self.cfg
        gravado = (anterior.get("perguntas") or {}).get("sha256")
        if cfg.matriz.get("perguntas"):
            if not gravado:
                raise RuntimeError("a rodada e anterior ao conjunto congelado (#352): nao da para "
                                   "retomar com o conjunto novo; use uma pasta nova")
            atual = sha256_arquivo(cfg.repo / cfg.matriz["perguntas"])
            if atual != gravado:
                raise RuntimeError("o conjunto de perguntas mudou desde o inicio da rodada (sha256 "
                                   f"{gravado[:12]} -> {atual[:12]}): use uma rodada nova")
            # As ineditas tambem: o mesmo arquivo cego (ou a mesma ausencia dele) do inicio.
            antes = ((anterior.get("perguntas") or {}).get("cego") or {}).get("sha256")
            compromisso = json.loads((cfg.repo / cfg.matriz["perguntas"]).read_text(encoding="utf-8"))
            agora = None
            if compromisso.get("ineditas") and cfg.cego is not None:
                arquivo_cego = cfg.cego / compromisso["ineditas"]["arquivo"]
                agora = sha256_arquivo(arquivo_cego) if arquivo_cego.exists() else None
            if antes != agora:
                raise RuntimeError("o conjunto cego mudou (ou apareceu, ou sumiu) desde o inicio da "
                                   f"rodada ({(antes or 'ausente')[:12]} -> {(agora or 'ausente')[:12]}): "
                                   "use uma rodada nova")
        if anterior.get("embedding", cfg.embedding) != cfg.embedding:
            raise RuntimeError(f"a rodada usou o embedding {anterior.get('embedding')}, nao "
                               f"{cfg.embedding} (--embedding-cpu): use a mesma opcao ou uma pasta nova")
        # Sem o campo (rodada de antes dele), o valor e "ausente" e so bate com uma matriz sem a chave.
        if anterior.get("readTimeoutCelula") != cfg.read_timeout_celula:
            raise RuntimeError(f"a rodada usou o read-timeout da celula {anterior.get('readTimeoutCelula')},"
                               f" nao {cfg.read_timeout_celula} (matriz.json): as celulas nao seriam "
                               "comparaveis; use uma pasta nova")

    def gravar_similaridade(self, rodada_dir: Path) -> None:
        """Aviso (nao bloqueia) das perguntas parecidas com o treino, para a revisao humana."""
        canonico = json.loads((rodada_dir / "gabarito-canonico.json").read_text(encoding="utf-8"))
        pares = similaridade.pares_semelhantes(canonico["perguntas"],
                                               similaridade.textos_de_treino(self.cfg.repo))
        # Ineditas tambem contra o resto do conjunto (o gabarito): parafrase de uma pergunta que a
        # Ceres 2.0 ja "viu" no desenvolvimento nao mede generalizacao.
        ineditas = [p for p in canonico["perguntas"] if str(p.get("origem", "")).startswith("inedita")]
        do_conjunto = [(f"conjunto/{p['id']}", p["pergunta"]) for p in canonico["perguntas"]
                       if p.get("origem") == "gabarito"]
        com_gabarito = similaridade.pares_semelhantes(ineditas, do_conjunto)
        (rodada_dir / "similaridade-treino.md").write_text(similaridade.markdown(pares + com_gabarito),
                                                           encoding="utf-8")
        # Separadas: a do treino e exemplo que o modelo ve; a do gabarito e inedita que parafraseia
        # uma pergunta usada no desenvolvimento (o relatorio trata cada uma no seu recorte).
        self.parecidas = sorted({p["id"] for p in pares})
        self.parecidas_gabarito = sorted({p["id"] for p in com_gabarito})
        pares = pares + com_gabarito
        if pares:
            log(f"AVISO: {len(pares)} perguntas parecidas com o treino; ver similaridade-treino.md")

    def conferir_opcoes_chat(self) -> dict:
        """Configuracao publicada (``.env.example``) e opcoes efetivas do chat de cada commit.

        Diferenca entre os commits **nao** e recusada: a Ceres 2.0 e medida com a configuracao
        publicada dela (a Sessao 7, #270, muda o teto de tokens e o ``num_ctx``), e isso faz parte
        da arquitetura. O relatorio mostra as duas. O que tem de ser igual e o que vem do Ollama
        (versao, quantizacao), conferido em cada celula.
        """
        por_arq = {}
        for arquitetura in self.cfg.todas_arquiteturas:
            try:
                env_example = self.capturar(["git", "show", f"{arquitetura['sha']}:.env.example"],
                                            self.cfg.repo)
            except subprocess.CalledProcessError:
                env_example = ""
            arquitetura["configuracaoPublicada"] = configuracao_publicada(env_example)
            env = self.ambiente_java("x", "x", arquitetura)
            yml = self.capturar(["git", "show", f"{arquitetura['sha']}:src/main/resources/application.yml"],
                                self.cfg.repo)
            por_arq[arquitetura["nome"]] = {
                # Opcoes do chat da Anthropic no yml do commit (vazio antes do #483): a celula por
                # API as usa; o relatorio as mostra ao lado das do Ollama.
                "anthropic": opcoes_chat_anthropic(yml),
                "publicada": arquitetura["configuracaoPublicada"],
                # O que a celula muda sobre a publicada (hoje so o read-timeout, ``matriz.json``).
                "ajustes": ({"AI_READ_TIMEOUT": env["AI_READ_TIMEOUT"]}
                            if self.cfg.read_timeout_celula else {}),
                "chat": {k: resolver_placeholder(v, env) for k, v in opcoes_chat_ollama(yml).items()},
            }
        diferentes = {k for d in por_arq.values() for k in d["chat"]
                      if len({d2["chat"].get(k) for d2 in por_arq.values()}) > 1}
        if diferentes:
            log("opcoes do chat diferentes entre os commits (parte da arquitetura medida): "
                + ", ".join(sorted(diferentes)))
        return por_arq

    def sessao_paralela(self) -> dict | None:
        """O SHA da Ceres 2.0 ja tem a sessao que muda a main em paralelo (ex.: #270)?

        ``confirmado`` so com ``commit`` na matriz (o merge do **ultimo** PR da sessao) e o SHA
        medido o contendo: a sessao entra em varios PRs, e ter um deles no historico nao e ter a
        sessao inteira. ``commits`` lista os merges da issue ja presentes (assunto terminado em
        ``(#270) (#NNN)``, o squash dos PRs do repositorio; commit que so cita a issue no corpo nao
        conta), para o relatorio dizer o que o ensaio ja tem.
        """
        alvo = self.cfg.matriz.get("sessaoParalela")
        if not alvo:
            return None
        sha = self.cfg.todas_arquiteturas[-1]["sha"]
        base = {"issue": alvo["issue"], "rotulo": alvo.get("rotulo")}
        try:
            assuntos = self.capturar(["git", "log", "--format=%h %s", sha], self.cfg.repo).splitlines()
            confirmado = False
            if alvo.get("commit"):
                ancestral = subprocess.run(
                    ["git", "merge-base", "--is-ancestor", alvo["commit"], sha], cwd=self.cfg.repo)
                if ancestral.returncode not in (0, 1):
                    return {**base, "contem": None, "confirmado": False}
                confirmado = ancestral.returncode == 0
            # Outras sessoes que a coluna oficial tambem exige (ex.: 8a, #360): faltou uma, e ensaio.
            faltando = []
            for extra in alvo.get("tambemExige") or []:
                resposta = subprocess.run(
                    ["git", "merge-base", "--is-ancestor", extra["commit"], sha], cwd=self.cfg.repo)
                if resposta.returncode not in (0, 1):
                    return {**base, "contem": None, "confirmado": False}
                if resposta.returncode == 1:
                    faltando.append(extra.get("rotulo") or extra["commit"][:7])
        except (subprocess.CalledProcessError, OSError):
            return {**base, "contem": None, "confirmado": False}
        commits = commits_da_issue(assuntos, alvo["issue"])
        return {**base, "contem": bool(commits) or confirmado,
                "confirmado": confirmado and not faltando, "inteira": confirmado, "faltando": faltando,
                "commits": commits[:10]}

    def fixar_shas(self, anterior: dict | None) -> None:
        """SHA de cada arquitetura da matriz; numa retomada, o da rodada (a main pode ter andado).

        :raises RuntimeError: se a retomada pede um commit diferente do que a rodada mediu
        """
        guardados = {a["nome"]: a["sha"] for a in (anterior or {}).get("arquiteturas", [])}
        for arquitetura in self.cfg.todas_arquiteturas:
            guardado = guardados.get(arquitetura["nome"])
            if guardado and not arquitetura.get("refExplicita"):
                arquitetura["sha"] = guardado
                continue
            sha = self.resolver_sha(arquitetura["ref"])
            if guardado and sha != guardado:
                raise RuntimeError(f"a rodada mediu {arquitetura['nome']} em {guardado[:7]}, nao em "
                                   f"{sha[:7]}: use uma rodada nova")
            arquitetura["sha"] = sha

    def manifesto_rodada(self, anterior: dict | None, estado: dict) -> dict:
        """``rodada.json``: todas as arquiteturas da matriz e a uniao das celulas ja pedidas.

        Retomar com ``--modelos`` ou ``--arquiteturas`` nao tira do relatorio as celulas medidas
        antes.
        """
        cfg = self.cfg
        celulas = list((anterior or {}).get("celulas", []))
        nomes = {c["nome"] for c in celulas}
        modelos = list((anterior or {}).get("modelos", []))
        for arquitetura in cfg.arquiteturas:
            for modelo, _ in cfg.celulas_da(arquitetura):
                nome = nome_celula(arquitetura["nome"], modelo)
                if nome not in nomes:
                    nomes.add(nome)
                    celulas.append({"nome": nome, "arquitetura": arquitetura["nome"],
                                    "rotuloArquitetura": arquitetura["rotulo"], "modelo": modelo})
                if modelo not in modelos:
                    modelos.append(modelo)
        # Modelos por API: a uniao com os da rodada anterior, com as opcoes do chat do commit.
        por_api = {m["modelo"]: m for m in (anterior or {}).get("modelosApi", [])}
        for arquitetura, api in cfg.celulas_api():
            antigo = por_api.get(api["modelo"])
            opcoes = (self.opcoes.get(arquitetura["nome"]) or {}).get("anthropic") or {}
            por_api[api["modelo"]] = {
                "modelo": api["modelo"], "provedor": api["provedor"],
                "raciocinio": api["raciocinio"], "nota": api.get("nota"),
                "modeloApi": modelo_da_api(api), "variante": api.get("variante"),
                "ambiente": api.get("ambiente") or {},
                "arquiteturas": sorted({*(antigo or {}).get("arquiteturas", []), arquitetura["nome"]}),
                "opcoes": opcoes or (antigo or {}).get("opcoes") or {},
            }
        ordem_arq = [a["nome"] for a in cfg.todas_arquiteturas]
        celulas.sort(key=lambda c: (ordem_arq.index(c["arquitetura"]), modelos.index(c["modelo"])))
        return {
            "iniciadaEm": (anterior or {}).get(
                "iniciadaEm", datetime.now(ZONA).isoformat(timespec="seconds")),
            "hardware": [cfg.hardware],
            "ollama": estado["ollama"],
            "trechosLegislacao": estado["trechosLegislacao"],
            "tipo": cfg.tipo,
            "repeticoes": cfg.repeticoes,
            "embedding": cfg.embedding,
            "readTimeoutCelula": cfg.read_timeout_celula,
            "perguntas": self.perguntas_info,
            "configuracaoOllama": self.config_rodada,
            "opcoesChat": self.opcoes,
            "sessao7": self.sessao_paralela(),
            "divergentesConhecidos": cfg.matriz.get("divergentesConhecidos", []),
            "parecidasComTreino": self.parecidas,
            "parecidasComGabarito": self.parecidas_gabarito,
            "modeloReferencia": cfg.matriz.get("modeloReferencia", "qwen3:4b-instruct"),
            "gabaritoCanonico": "gabarito-canonico.json",
            "arquiteturas": [
                {"nome": a["nome"], "rotulo": a["rotulo"], "ref": a["ref"], "sha": a["sha"],
                 "descricao": a.get("descricao", ""), "adaptacao": a.get("adaptacao")}
                for a in cfg.todas_arquiteturas],
            "modelos": modelos,
            "modelosApi": list(por_api.values()),
            "celulas": celulas,
        }


def detectar_hardware() -> str:
    """CPU, memoria e GPU detectados (Linux/macOS); o time pode sobrescrever com --hardware."""
    partes = [f"{platform.system()} {platform.machine()}"]
    cpu = None
    try:
        for linha in Path("/proc/cpuinfo").read_text().splitlines():
            if linha.startswith("model name"):
                cpu = linha.split(":", 1)[1].strip()
                break
        memoria = next(l for l in Path("/proc/meminfo").read_text().splitlines()
                       if l.startswith("MemTotal"))
        partes.append(f"{int(memoria.split()[1]) / 1024 / 1024:.0f} GB RAM")
    except (OSError, StopIteration):
        try:
            cpu = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True,
                                 text=True).stdout.strip() or None
            mem = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True,
                                     text=True).stdout.strip() or 0)
            if mem:
                partes.append(f"{mem / _GIB:.0f} GB RAM")
        except (OSError, ValueError):
            pass
    if cpu:
        partes.insert(0, cpu)
    partes.append(f"{os.cpu_count()} núcleos lógicos")
    if shutil.which("nvidia-smi"):
        gpu = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"],
                             capture_output=True, text=True).stdout.strip()
        if gpu:
            partes.append("GPU " + gpu.replace("\n", "; "))
    else:
        partes.append("sem GPU NVIDIA detectada")
    return ", ".join(partes)


def montar_config(argumentos, repo: Path) -> Config:
    matriz = json.loads((Path(argumentos.matriz)).read_text(encoding="utf-8"))
    todas = [dict(a) for a in matriz["arquiteturas"]]
    if argumentos.ref_ceres2:
        todas[-1]["ref"] = argumentos.ref_ceres2
        todas[-1]["refExplicita"] = True
    arquiteturas = todas
    if argumentos.arquiteturas:
        pedidas = argumentos.arquiteturas.split(",")
        arquiteturas = [a for a in todas if a["nome"] in pedidas]
        if len(arquiteturas) != len(pedidas):
            raise SystemExit(f"arquitetura desconhecida em {pedidas}")
    # Modelos por API (``modelosApi``): nao passam pelo Ollama. ``--modelos`` aceita os dois tipos;
    # sem ele valem todos os da matriz.
    por_api = {m["modelo"]: m for m in matriz.get("modelosApi", [])}
    pedidos = argumentos.modelos.split(",") if argumentos.modelos else None
    modelos = ([m for m in pedidos if m not in por_api] if pedidos else list(matriz["modelos"]))
    # Celula ``opcional`` (variante, ex.: a fidelidade da lei): fora do padrao; so entra pelo nome em
    # ``--modelos`` ou pela opcao da variante. Sem isso a matriz roda exatamente como sempre.
    modelos_api = ([por_api[m] for m in pedidos if m in por_api] if pedidos
                   else [m for m in por_api.values() if not m.get("opcional")])
    if getattr(argumentos, "com_fidelidade_lei", False):
        modelos_api += [m for m in por_api.values()
                        if m.get("variante") == "fidelidade-lei" and m not in modelos_api]
    return Config(
        repo=repo,
        matriz=matriz,
        saida=Path(argumentos.saida).resolve(),
        trabalho=Path(argumentos.trabalho).expanduser().resolve(),
        modelos=modelos,
        arquiteturas=arquiteturas,
        repeticoes=(1 if getattr(argumentos, "teste", False) else
                    argumentos.repeticoes or matriz.get("repeticoes", 3)),
        ollama_url=os.environ.get("BENCH_OLLAMA_URL", "http://localhost:11434"),
        ollama_container=os.environ.get("BENCH_OLLAMA_CONTAINER") or None,
        psql=shlex.split(os.environ.get(
            "BENCH_PSQL", "docker exec -i agrocontabil-postgres psql -U agrocontabil -v ON_ERROR_STOP=1")),
        db_origem=os.environ.get("BENCH_DB_ORIGEM", "agrocontabil"),
        db_prefixo=os.environ.get("BENCH_DB_PREFIXO", "agro_bench"),
        hardware=argumentos.hardware or detectar_hardware(),
        retomar=Path(argumentos.retomar).resolve() if argumentos.retomar else None,
        todas_arquiteturas=todas,
        # Menos de 3 repeticoes nunca vale para a banca, com ou sem --teste (da linha de comando
        # ou da matriz).
        teste=bool(getattr(argumentos, "teste", False))
        or (argumentos.repeticoes or matriz.get("repeticoes", 3)) < 3,
        embedding_cpu=bool(getattr(argumentos, "embedding_cpu", False)),
        ensaio=bool(getattr(argumentos, "ensaio", False)),
        cego=caminho_cego(getattr(argumentos, "cego", None), repo),
        modelos_api=modelos_api,
    )


def caminho_cego(argumento: str | None, repo: Path) -> Path | None:
    """O checkout do repositorio cego: so ``--cego`` ou ``CERES_BENCHMARK_CEGO``, nunca achado
    sozinho (#387): uma sessao de codigo com o cego ao lado nao o le por acaso."""
    escolhido = argumento or os.environ.get("CERES_BENCHMARK_CEGO")
    return Path(escolhido).expanduser().resolve() if escolhido else None


if __name__ == "__main__":  # pragma: no cover
    sys.exit("use docs/avaliacao/benchmark/executar_matriz.py")
