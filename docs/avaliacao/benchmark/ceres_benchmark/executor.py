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
import urllib.request
from dataclasses import dataclass
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


def log(mensagem: str) -> None:
    print(f"[{datetime.now(ZONA):%H:%M:%S}] {mensagem}", flush=True)


def nome_celula(arquitetura: str, modelo: str) -> str:
    """``ceres-1--qwen3-0.6b``: arquitetura e modelo, seguros para nome de arquivo."""
    return f"{arquitetura}--" + re.sub(r"[^a-z0-9.]+", "-", modelo.lower()).strip("-")


def bytes_de_docker_stats(texto: str) -> int | None:
    """``"1.234GiB / 15.5GiB"`` -> bytes usados (saida de ``docker stats --format {{.MemUsage}}``)."""
    m = re.match(r"\s*([\d.]+)\s*([KMGT]?i?B)", texto, re.IGNORECASE)
    if not m:
        return None
    return int(float(m.group(1)) * _UNIDADES[m.group(2).lower()])


def resumo_pensamento(prova: dict) -> str:
    """Frase do relatorio a partir da prova de pensamento."""
    if prova.get("erro"):
        return f"prova falhou: {prova['erro']}"
    if prova.get("raciocinou"):
        return (f"ATIVO apesar de think=false ({prova.get('caracteresPensamento', 0)} caracteres de "
                "raciocínio na prova): latência e num_predict afetados")
    return "desligado (think=false; nenhum raciocínio na prova)"


def avaliar_prova(resposta: dict) -> dict:
    """Le a resposta do ``/api/chat`` da prova: raciocinio em ``thinking`` ou ``<think>`` no texto."""
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
        "resposta": conteudo[:200],
    }


def sha256_arquivo(caminho: Path) -> str:
    return hashlib.sha256(caminho.read_bytes()).hexdigest()


def opcoes_chat_ollama(application_yml: str) -> dict[str, str]:
    """Opcoes do chat do Ollama no ``application.yml`` de um commit (o bloco
    ``spring.ai.ollama.chat.options``), como texto: ``temperature``, ``num-ctx``,
    ``num-predict``, ``think-option``. So as que mudam a resposta; o modelo vem do executor."""
    linhas = application_yml.splitlines()
    caminho: list[tuple[int, str]] = []
    opcoes: dict[str, str] = {}
    for linha in linhas:
        if not linha.strip() or linha.lstrip().startswith("#"):
            continue
        recuo = len(linha) - len(linha.lstrip())
        chave, _, valor = linha.strip().partition(":")
        while caminho and caminho[-1][0] >= recuo:
            caminho.pop()
        nomes = [c[1] for c in caminho]
        if nomes[-3:] == ["ollama", "chat", "options"] and chave in (
                "temperature", "num-ctx", "num-predict", "think-option"):
            opcoes[chave] = valor.split(" #")[0].strip()
        caminho.append((recuo, chave))
    return opcoes


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


#: Variaveis do ``.env.example`` que nao sao configuracao da Ceres medida: endereco, chave, modelo
#: (o da celula) e estrategia de pull (o executor baixa). O resto vale como publicado no commit.
FORA_DA_CONFIGURACAO = {"AI_API_KEY", "AI_API_BASE_URL", "AI_API_COMPLETIONS_PATH",
                        "AI_OLLAMA_BASE_URL", "AI_OLLAMA_PORT", "AI_CHAT_MODEL",
                        "AI_CHAT_PROVIDER", "AI_OLLAMA_PULL_STRATEGY"}


def configuracao_publicada(env_example: str) -> dict[str, str]:
    """``AI_*`` do ``.env.example`` de um commit: a configuracao publicada da Ceres naquele SHA
    (teto de tokens, ``num_ctx``, timeouts...). A rodada usa exatamente ela, sem ajuste a mao."""
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

    @property
    def embedding(self) -> str:
        """Nome do embedding que a celula usa (``bge-m3-cpu`` com ``--embedding-cpu``)."""
        base = self.matriz.get("embedding", "bge-m3")
        return f"{base}-cpu" if self.embedding_cpu else base


class Executor:
    """Roda a matriz; cada passo loga o que faz e falha cedo com a causa."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.config_rodada: dict | None = None
        self.perguntas_info: dict = {}
        self.opcoes: dict = {}

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

    # ------------------------------------------------------------------ passos

    def conferir_pre_requisitos(self) -> dict:
        for programa in ("git", "java"):
            if shutil.which(programa) is None:
                raise RuntimeError(f"{programa} nao encontrado no PATH")
        if not os.environ.get("DB_PASSWORD"):
            raise RuntimeError("DB_PASSWORD ausente: exporte a senha do Postgres (a do .env)")
        versao = self.ollama("/api/version").get("version", "?")
        trechos = int(self.psql(self.cfg.db_origem, "select count(*) from legislacao_trecho") or 0)
        esperado = self.cfg.matriz.get("trechosLegislacao")
        if trechos == 0:
            raise RuntimeError(f"base de lei vazia em {self.cfg.db_origem}: copie conforme o "
                               "deploy/README.md antes de rodar")
        if esperado and trechos != esperado:
            log(f"AVISO: {trechos} trechos de lei em {self.cfg.db_origem}, esperado {esperado}; "
                "o numero vai no relatorio")
        return {"ollama": versao, "trechosLegislacao": trechos}

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

    def ambiente_java(self, banco: str, modelo: str, arquitetura: dict | None = None) -> dict:
        """Ambiente da JVM: sem nenhum ``AI_*`` herdado do shell (um ``export AI_MAX_TOKENS``
        esquecido mediria outra configuracao) e com a configuracao publicada do commit."""
        env = {k: v for k, v in os.environ.items() if not k.startswith("AI_")}
        if arquitetura is not None:
            env.update(arquitetura.get("configuracaoPublicada") or {})
        env.update({
            "SPRING_PROFILES_ACTIVE": "dev,avaliacao",
            "DB_NAME": banco,
            "AI_CHAT_PROVIDER": "ollama",
            "AI_CHAT_MODEL": modelo,
            # O pensamento e desligado pela mesma propriedade que a producao usa; a prova confere.
            "AI_OLLAMA_THINK": "false",
            "AI_OLLAMA_BASE_URL": self.cfg.ollama_url,
            "AI_OLLAMA_PULL_STRATEGY": "never",
        })
        return env

    def argumentos_avaliacao(self, gabarito: Path, saida: Path) -> list[str]:
        return [
            "--spring.docker.compose.enabled=false",
            f"--avaliacao.gabarito={gabarito}",
            f"--avaliacao.saida={saida}",
            f"--avaliacao.repeticoes={self.cfg.repeticoes}",
            f"--avaliacao.hardware={self.cfg.hardware}",
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

    def provar_pensamento(self, modelo: str) -> dict:
        corpo = {"model": modelo, "stream": False, "think": False,
                 "messages": [{"role": "user", "content": PERGUNTA_PROVA}],
                 "options": {"num_predict": 256, "temperature": 0.2}}
        try:
            prova = avaliar_prova(self.ollama("/api/chat", corpo, timeout=600))
        except ERROS_HTTP as e:
            prova = {"erro": f"{type(e).__name__}: {e}"}
        try:
            capacidades = self.ollama("/api/show", {"model": modelo}).get("capabilities")
        except ERROS_HTTP:
            capacidades = None
        prova["capacidades"] = capacidades
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
                     banco: str, gabarito: Path, modelo: str) -> dict:
        nome = nome_celula(arquitetura["nome"], modelo)
        destino = rodada_dir / nome
        manifesto_arquivo = destino / "celula.json"
        if manifesto_arquivo.exists():
            anterior = json.loads(manifesto_arquivo.read_text(encoding="utf-8"))
            mesma = (anterior.get("sha"), anterior.get("modelo"), anterior.get("repeticoes"),
                     anterior.get("embedding", self.cfg.embedding)) == (
                arquitetura["sha"], modelo, self.cfg.repeticoes, self.cfg.embedding)
            if anterior.get("codigoSaida") == 0 and mesma:
                log(f"{nome}: ja medida nesta rodada, pulando")
                return anterior
            if anterior.get("codigoSaida") == 0:
                raise RuntimeError(f"{nome} ja foi medida com outro SHA, modelo, embedding ou "
                                   "numero de repeticoes: use uma rodada nova em vez de --retomar")
        destino.mkdir(parents=True, exist_ok=True)
        temporario = destino / "saida-do-comando"
        shutil.rmtree(temporario, ignore_errors=True)
        temporario.mkdir()

        self.config_rodada = self.conferir_configuracao(self.config_rodada)
        self.descarregar_outros({self.cfg.embedding, self.cfg.embedding + ":latest"})
        pensamento = self.provar_pensamento(modelo)
        log(f"{nome}: pensamento {pensamento['resumo']}")

        lido = {"arquivoLido": gabarito.name, "sha256Lido": sha256_arquivo(gabarito)}
        memoria: dict = {}
        parar = threading.Event()
        amostrador = threading.Thread(target=self.amostrar_memoria, args=(modelo, parar, memoria),
                                      daemon=True)
        amostrador.start()
        inicio = time.monotonic()
        try:
            codigo = self.rodar(["java", "-jar", str(jar)]
                                + self.argumentos_avaliacao(gabarito, temporario),
                                worktree, env=self.ambiente_java(banco, modelo, arquitetura),
                                saida=destino / "comando.log", checar=False)
        finally:
            parar.set()
            amostrador.join(timeout=30)
        duracao = round(time.monotonic() - inicio)
        arquivos = self._renomear_saida(temporario, destino, nome)
        manifesto = {
            "celula": nome,
            "arquitetura": arquitetura["nome"],
            "rotuloArquitetura": arquitetura["rotulo"],
            "sha": arquitetura["sha"],
            "modelo": modelo,
            "embedding": self.cfg.embedding,
            "repeticoes": self.cfg.repeticoes,
            "tipo": "teste" if self.cfg.teste else "banca",
            "perguntas": {**self.perguntas_info, **lido},
            "hardware": self.cfg.hardware,
            "banco": banco,
            "gabarito": gabarito.name,
            "pensamento": pensamento,
            "memoria": memoria,
            "avisos": avisos_da_amostra(memoria),
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
        if anterior and anterior.get("tipo", "banca") != ("teste" if cfg.teste else "banca"):
            raise RuntimeError("nao misture rodada de teste com rodada da banca: use uma pasta nova")
        self.fixar_shas(anterior)
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
            for modelo in cfg.modelos:
                self.rodar_celula(rodada_dir, arquitetura, worktree, jar, banco,
                                  arquitetura["gabarito"], modelo)
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
        (preparo / "gabarito-canonico.json").write_text(canonico_txt, encoding="utf-8")
        canonico = json.loads(canonico_txt)
        self.perguntas_info = {"arquivo": cfg.matriz.get("perguntas") or GABARITO_REPO,
                               "versao": canonico.get("versao"), "sha256": sha,
                               "total": len(canonico["perguntas"])}

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
                # Le a copia congelada da rodada (os mesmos bytes do conjunto): um checkout no meio
                # de uma rodada de horas nao troca as perguntas das celulas seguintes.
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
        if anterior.get("embedding", cfg.embedding) != cfg.embedding:
            raise RuntimeError(f"a rodada usou o embedding {anterior.get('embedding')}, nao "
                               f"{cfg.embedding} (--embedding-cpu): use a mesma opcao ou uma pasta nova")

    def gravar_similaridade(self, rodada_dir: Path) -> None:
        """Aviso (nao bloqueia) das perguntas parecidas com o treino, para a revisao humana."""
        canonico = json.loads((rodada_dir / "gabarito-canonico.json").read_text(encoding="utf-8"))
        pares = similaridade.pares_semelhantes(canonico["perguntas"],
                                               similaridade.textos_de_treino(self.cfg.repo))
        (rodada_dir / "similaridade-treino.md").write_text(similaridade.markdown(pares),
                                                           encoding="utf-8")
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
                "publicada": arquitetura["configuracaoPublicada"],
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
        except (subprocess.CalledProcessError, OSError):
            return {**base, "contem": None, "confirmado": False}
        commits = commits_da_issue(assuntos, alvo["issue"])
        return {**base, "contem": bool(commits) or confirmado, "confirmado": confirmado,
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
            for modelo in cfg.modelos:
                nome = nome_celula(arquitetura["nome"], modelo)
                if nome not in nomes:
                    nomes.add(nome)
                    celulas.append({"nome": nome, "arquitetura": arquitetura["nome"],
                                    "rotuloArquitetura": arquitetura["rotulo"], "modelo": modelo})
                if modelo not in modelos:
                    modelos.append(modelo)
        ordem_arq = [a["nome"] for a in cfg.todas_arquiteturas]
        celulas.sort(key=lambda c: (ordem_arq.index(c["arquitetura"]), modelos.index(c["modelo"])))
        return {
            "iniciadaEm": (anterior or {}).get(
                "iniciadaEm", datetime.now(ZONA).isoformat(timespec="seconds")),
            "hardware": [cfg.hardware],
            "ollama": estado["ollama"],
            "trechosLegislacao": estado["trechosLegislacao"],
            "tipo": "teste" if cfg.teste else "banca",
            "repeticoes": cfg.repeticoes,
            "embedding": cfg.embedding,
            "perguntas": self.perguntas_info,
            "configuracaoOllama": self.config_rodada,
            "opcoesChat": self.opcoes,
            "sessao7": self.sessao_paralela(),
            "divergentesConhecidos": cfg.matriz.get("divergentesConhecidos", []),
            "modeloReferencia": cfg.matriz.get("modeloReferencia", "qwen3:4b-instruct"),
            "gabaritoCanonico": "gabarito-canonico.json",
            "arquiteturas": [
                {"nome": a["nome"], "rotulo": a["rotulo"], "ref": a["ref"], "sha": a["sha"],
                 "descricao": a.get("descricao", ""), "adaptacao": a.get("adaptacao")}
                for a in cfg.todas_arquiteturas],
            "modelos": modelos,
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
    modelos = argumentos.modelos.split(",") if argumentos.modelos else list(matriz["modelos"])
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
    )


if __name__ == "__main__":  # pragma: no cover
    sys.exit("use docs/avaliacao/benchmark/executar_matriz.py")
