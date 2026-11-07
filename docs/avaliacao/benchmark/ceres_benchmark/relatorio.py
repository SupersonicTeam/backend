"""Relatorio comparativo do benchmark da Ceres (issue #348).

Le a pasta de uma rodada da matriz (``rodada.json`` + uma pasta por celula com ``celula.json`` e o
JSON do comando de avaliacao) e recalcula as metricas a partir dos resultados por pergunta, para
aplicar os dois recortes com o **gabarito canonico** (o da Ceres 2.0). Aceita os dois esquemas do
JSON do comando:

- ``2463c27``: sem ``semBecoSemSaida`` nem ``camadaClassificacao``;
- ``main``: com os dois;
- relatorios anteriores a #264 (uma rodada, ``resultados`` na raiz) tambem sao lidos.

Criterio ausente do JSON (a regua daquele commit nao o calculava) sai como ``n/d``; criterio
presente com ``null`` nao se aplica a pergunta.
"""

from __future__ import annotations

import json
import math
import re
import statistics
from dataclasses import dataclass, field
from pathlib import Path

from . import externas as ext
from . import gabarito as gab
from . import graficos

#: Sem contador (decisao do Murillo, 2026-11-06, #387): sai no relatorio quando todas as externas
#: estao revisadas (revisado.por).
DECLARACAO = ("Respostas esperadas conferidas no texto da lei (normas.leg.br e base da VPS) e "
              "revisadas pela equipe e pelo orientador; sem validação de contador.")

#: Desfechos de falha tecnica (``RelatorioAvaliacao.FALHAS_TECNICAS``).
FALHAS_TECNICAS = {"MODELO_INDISPONIVEL", "MODO_FALLBACK", "TEMPO_ESGOTADO", "CANCELADA", "ERRO"}
#: Respostas cujo texto e do modelo (as outras sao frase fixa do backend).
REDIGIDAS = {"RESPONDIDA", "NEGATIVA_SUBSTITUIDA"}

#: criterio -> (rotulo, chave no ResultadoPergunta, inverte o booleano)
CRITERIOS = {
    "rota": ("Acurácia de rota", "rotaCorreta", False),
    "funcao": ("Acerto de função", "funcaoCorreta", False),
    "parametros": ("Acerto de parâmetros", "parametrosCorretos", False),
    "numero": ("Exatidão numérica", "numeroCorreto", False),
    "citacao": ("Citação válida", "citacaoValida", False),
    "recusa": ("Recusa correta", "recusaCorreta", False),
    #: Recusa certa que veio de RESPOSTA_CORTADA (#270) nao conta: acerto por acidente.
    "recusa_limpa": ("Recusa limpa", "recusaCorreta", False),
    "vazamento": ("Sem vazamento", "vazamento", True),
    "beco": ("Sem beco sem saída", "semBecoSemSaida", False),
    "falha": ("Sem falha técnica", None, False),
}

RECORTE_COMUM = "comum"
RECORTE_INTEIRO = "inteiro"
#: Perguntas ineditas (#352): fora do gabarito contra o qual a Ceres 2.0 foi desenvolvida. Mesma
#: regra do conjunto inteiro (``so_ceres_2`` penaliza por capacidade).
RECORTE_INEDITAS = "ineditas"
_RECORTES_QUE_PENALIZAM = (RECORTE_INTEIRO, RECORTE_INEDITAS)

#: Criterios de viabilidade da "Conclusao para a banca" (README do benchmark).
LIMIAR_SEM_FALHA = 95.0
CRITERIOS_VIABILIDADE = ("rota", "numero", "citacao", "recusa_limpa")

_NEGATIVA = re.compile(
    r"\bn[ãa]o (h[áa]|tem|tenho|houve|encontrei|existe|existem|constam?|possui)\b|\bnenhum[a]?\b",
    re.IGNORECASE,
)


@dataclass
class Faixa:
    """Media e faixa (minimo-maximo) de um criterio entre as rodadas, em percentual."""

    media: float | None
    minimo: float | None
    maximo: float | None
    total: int
    penalizadas: int = 0
    disponivel: bool = True

    def formatada(self) -> str:
        if not self.disponivel:
            return "n/d"
        if self.media is None:
            return "—"
        texto = (
            f"{self.media:.1f}%".replace(".", ",")
            if self.minimo == self.maximo
            else f"{self.media:.1f}% ({self.minimo:.1f}–{self.maximo:.1f})".replace(".", ",")
        )
        return f"{texto} n={self.total}"


@dataclass
class Celula:
    """Uma celula da matriz: arquitetura x modelo."""

    nome: str
    arquitetura: str
    rotulo_arquitetura: str
    modelo: str
    manifesto: dict
    avaliacao: dict
    rodadas: list[list[dict]] = field(default_factory=list)

    @property
    def invalida(self) -> bool:
        """Pensamento ativo apesar de ``think=false``, ou nenhuma prova que mostre o contrario (as
        duas falharam): a celula nao e comparavel (o teto de tokens da Sessao 7 cortaria o
        raciocinio, e o acerto mediria o corte, nao o modelo)."""
        pensamento = self.manifesto.get("pensamento") or {}
        return bool(pensamento.get("raciocinou") or pensamento.get("semProva"))

    @property
    def ficticia(self) -> bool:
        return bool(self.manifesto.get("ficticio"))

    @property
    def perguntas(self) -> dict[str, str]:
        return self.avaliacao.get("perguntas", {})


def _ler_json(caminho: Path) -> dict:
    with caminho.open(encoding="utf-8") as arquivo:
        return json.load(arquivo)


def rodadas_do_json(avaliacao: dict) -> list[list[dict]]:
    """Resultados por rodada nos dois esquemas (com ``rodadas`` desde a #264, ou so ``resultados``)."""
    if "rodadas" in avaliacao:
        return [r["resultados"] for r in avaliacao["rodadas"]]
    if "resultados" in avaliacao:
        return [avaliacao["resultados"]]
    raise ValueError("JSON da avaliacao sem 'rodadas' nem 'resultados'")


def celulas_descartadas(pasta: Path) -> list[tuple[str, str]]:
    """(pasta, motivo) das celulas descartadas e refeitas: ``<celula>.descartada-<data>``.

    Ficam fora das tabelas (so as celulas do ``rodada.json`` entram) e so aparecem nos avisos. O
    motivo e a primeira linha de ``motivo.txt``, registrado antes de refazer a celula.
    """
    achadas = []
    for pasta_celula in sorted(p for p in pasta.iterdir() if p.is_dir() and ".descartada-" in p.name):
        arquivo = pasta_celula / "motivo.txt"
        linhas = arquivo.read_text(encoding="utf-8").strip().splitlines() if arquivo.exists() else []
        achadas.append((pasta_celula.name, linhas[0].strip() if linhas else ""))
    return achadas


def carregar_celulas(pasta: Path) -> list[Celula]:
    """Celulas da rodada, na ordem do ``rodada.json`` (arquitetura, depois modelo)."""
    rodada = _ler_json(pasta / "rodada.json")
    celulas = []
    for item in rodada["celulas"]:
        dir_celula = pasta / item["nome"]
        manifesto_arquivo = dir_celula / "celula.json"
        if not manifesto_arquivo.exists():
            continue
        manifesto = _ler_json(manifesto_arquivo)
        avaliacao_arquivo = dir_celula / (item["nome"] + ".json")
        if manifesto.get("codigoSaida") != 0 or not avaliacao_arquivo.exists():
            celulas.append(Celula(item["nome"], item["arquitetura"], item["rotuloArquitetura"],
                                  item["modelo"], manifesto, {}, []))
            continue
        avaliacao = _ler_json(avaliacao_arquivo)
        celulas.append(Celula(item["nome"], item["arquitetura"], item["rotuloArquitetura"],
                              item["modelo"], manifesto, avaliacao, rodadas_do_json(avaliacao)))
    return celulas


def falha_tecnica(resultado: dict) -> bool:
    execucao = resultado.get("execucao") or {}
    return bool(execucao.get("erro")) or bool(execucao.get("modoFallback")) or (
        execucao.get("desfecho") in FALHAS_TECNICAS
    )


def valor_criterio(
    resultado: dict, criterio: str, pergunta: dict, penalizados: list[str], recorte: str
) -> tuple[bool | None, bool, bool]:
    """(acertou?, disponivel na regua?, penalizada por capacidade?) de um resultado.

    No recorte inteiro, criterio que a adaptacao tirou da pergunta por capacidade (a funcao nao
    existia na arquitetura, ``penalizados``) e que o gabarito canonico pede conta como **erro
    por capacidade**.
    """
    if criterio == "falha":
        return (not falha_tecnica(resultado)), True, False
    if criterio == "citacao" and pergunta.get("aConferir"):
        # Dispositivos ainda nao confirmados na base (#358): ficam so na secao das externas, como
        # provisorios; nao entram no criterio principal nem na viabilidade.
        return None, True, False
    _, chave, inverte = CRITERIOS[criterio]
    if chave not in resultado:
        return None, False, False
    valor = resultado[chave]
    if (
        valor is None
        and recorte in _RECORTES_QUE_PENALIZAM
        and criterio in penalizados
        and gab.aplicavel(pergunta, criterio)
    ):
        return False, True, True
    if valor is None:
        return None, True, False
    if criterio == "citacao":
        # Regra da main nas duas arquiteturas (ext.citacao_vale): o AvaliadorResposta do 2463c27
        # da citacao valida a texto descartado, o da main nao.
        valor = ext.citacao_vale(resultado)
    if criterio == "recusa_limpa" and valor and ext.cortada(resultado)["qualquer"]:
        return False, True, False
    return (not valor) if inverte else bool(valor), True, False


def faixa(
    celula: Celula, criterio: str, ids: list[str], canonico: dict[str, dict],
    adaptacoes: dict[str, dict], recorte: str
) -> Faixa:
    """Media e faixa de um criterio nas perguntas ``ids``, rodada a rodada.

    ``adaptacoes`` sao as da arquitetura da celula: so elas penalizam no recorte inteiro.
    """
    if not celula.rodadas:
        return Faixa(None, None, None, 0, disponivel=False)
    alvo = set(ids)
    taxas, total, penalizadas, disponivel = [], 0, 0, True
    for resultados in celula.rodadas:
        acertos = aplicaveis = pen = 0
        for r in resultados:
            if r["id"] not in alvo or r["id"] not in canonico:
                continue
            penalizados = gab.criterios_penalizados(adaptacoes[r["id"]]) if r["id"] in adaptacoes else []
            valor, disp, penal = valor_criterio(r, criterio, canonico[r["id"]], penalizados, recorte)
            if not disp:
                disponivel = False
                continue
            if valor is None:
                continue
            aplicaveis += 1
            acertos += 1 if valor else 0
            pen += 1 if penal else 0
        if aplicaveis:
            taxas.append(100.0 * acertos / aplicaveis)
            total, penalizadas = aplicaveis, pen
    if not disponivel:
        return Faixa(None, None, None, 0, disponivel=False)
    if not taxas:
        return Faixa(None, None, None, 0)
    return Faixa(statistics.fmean(taxas), min(taxas), max(taxas), total, penalizadas)


def percentil(valores: list[int], p: int) -> int:
    """Nearest-rank, igual ao ``RelatorioAvaliacao.percentilDe``."""
    if not valores:
        return 0
    ordenados = sorted(valores)
    posicao = math.ceil(p / 100.0 * len(ordenados))
    return ordenados[max(0, posicao - 1)]


def latencias(celula: Celula, ids: list[str], canonico: dict[str, dict]) -> dict[str, tuple[int, int, int]]:
    """Rota esperada -> (p50 ms, p95 ms, amostras), com as respostas de todas as rodadas."""
    alvo = set(ids)
    por_rota: dict[str, list[int]] = {}
    for resultados in celula.rodadas:
        for r in resultados:
            if r["id"] not in alvo or r["id"] not in canonico:
                continue
            rota = canonico[r["id"]].get("rotaEsperada") or "SEM_ROTA"
            ms = (r.get("execucao") or {}).get("latenciaMs", 0)
            por_rota.setdefault(rota, []).append(ms)
            por_rota.setdefault("TODAS", []).append(ms)
    return {rota: (percentil(v, 50), percentil(v, 95), len(v)) for rota, v in por_rota.items()}


def clareza(celula: Celula) -> tuple[float | None, float | None, int]:
    """(palavras por frase, % com sigla sem explicacao, respostas medidas) do gabarito inteiro.

    Sai do ``porRotaAgregado`` do proprio comando (a ``ClarezaTexto`` do Java), ponderado pelas
    respostas medidas de cada rota: reimplementar a regua aqui criaria uma segunda definicao.
    """
    agregado = celula.avaliacao.get("porRotaAgregado") or {}
    medidas = sum(v.get("respostasMedidas", 0) for v in agregado.values())
    if not medidas:
        return None, None, 0
    palavras = sum(v.get("palavrasPorFrase", 0) * v.get("respostasMedidas", 0)
                   for v in agregado.values()) / medidas
    siglas = sum(v.get("comSiglaSemExplicacao", 0) for v in agregado.values())
    return palavras, 100.0 * siglas / medidas, medidas


_FIM_DE_FRASE = re.compile(r"[.!?…:)\]\"'*»]\s*$")


def possivelmente_cortadas(celula: Celula) -> tuple[int, int]:
    """(respostas redigidas que terminam sem fim de frase, respostas redigidas).

    Heuristica do corte pelo teto de tokens (``num_predict``): a resposta para no meio da frase.
    Nao prova o corte (o JSON nao tem os tokens), mas uma subida entre rodadas aponta para ele.
    """
    cortadas = total = 0
    for resultados in celula.rodadas:
        for r in resultados:
            execucao = r.get("execucao") or {}
            texto = (execucao.get("texto") or "").strip()
            if execucao.get("desfecho") not in REDIGIDAS or not texto or falha_tecnica(r):
                continue
            total += 1
            cortadas += 0 if _FIM_DE_FRASE.search(texto) else 1
    return cortadas, total


def camadas(celula: Celula) -> dict[str, int]:
    """Camada da classificacao (#265) -> respostas; vazio no esquema do 2463c27."""
    contagem: dict[str, int] = {}
    for resultados in celula.rodadas:
        for r in resultados:
            camada = (r.get("execucao") or {}).get("camadaClassificacao")
            if camada:
                contagem[camada] = contagem.get(camada, 0) + 1
    return contagem


def valores_divergentes(celulas: list[Celula]) -> dict[str, dict[str, list[str]]]:
    """Perguntas cujo valor esperado mudou entre as celulas.

    O valor esperado sai do seed de cada commit (``SeedAvaliacao.VERSAO``): o v2 do ``2463c27``
    nao e o v4 da ``main``. Pergunta com valor diferente mede outra coisa em cada arquitetura e sai
    do recorte comum.
    """
    por_id: dict[str, dict[str, list[str]]] = {}
    for c in celulas:
        if not c.rodadas:
            continue
        for r in c.rodadas[0]:
            por_id.setdefault(r["id"], {})[c.nome] = list(r.get("valoresEsperados") or [])
    return {
        id_: valores for id_, valores in por_id.items()
        if len({tuple(v) for v in valores.values()}) > 1
    }


def instaveis(celula: Celula, ids: list[str], canonico: dict[str, dict] | None = None) -> list[str]:
    """Perguntas que mudaram de resultado entre rodadas em algum criterio, pela mesma regua das
    tabelas (``valor_criterio``): a citacao de pergunta ``aConferir`` e a de texto descartado nao
    contam, e a recusa limpa conta."""
    if len(celula.rodadas) < 2:
        return []
    alvo = set(ids)
    por_id: dict[str, list[tuple]] = {}
    for resultados in celula.rodadas:
        for r in resultados:
            if r["id"] in alvo:
                pergunta = (canonico or {}).get(r["id"], {})
                por_id.setdefault(r["id"], []).append(tuple(
                    valor_criterio(r, c, pergunta, [], RECORTE_COMUM)[0] for c in CRITERIOS))
    return [id_ for id_, valores in por_id.items() if len(set(valores)) > 1]


#: Criterios da "pergunta certa": todos os que se aplicam. Sem o beco sem saida, que a regua do
#: 2463c27 nao tinha (o composto tem de ser o mesmo nas duas arquiteturas).
CRITERIOS_COMPOSTOS = ("rota", "funcao", "parametros", "numero", "citacao", "recusa", "vazamento",
                       "falha")


def acerto_pergunta(resultado: dict, pergunta: dict, penalizados: list[str], recorte: str) -> bool:
    """A pergunta saiu certa: todo criterio que se aplica a ela acertou."""
    for criterio in CRITERIOS_COMPOSTOS:
        valor, disponivel, _ = valor_criterio(resultado, criterio, pergunta, penalizados, recorte)
        if disponivel and valor is False:
            return False
    return True


def por_tema(celula: Celula, ids: list[str], canonico: dict[str, dict], adaptacoes: dict[str, dict],
             recorte: str) -> dict[str, Faixa]:
    """Tema -> acerto da pergunta (media e faixa das rodadas), nas perguntas ``ids``."""
    alvo = set(ids)
    if not celula.rodadas:
        return {}
    por_rodada: dict[str, list[float]] = {}
    totais: dict[str, int] = {}
    for resultados in celula.rodadas:
        contagem: dict[str, list[int]] = {}
        for r in resultados:
            pergunta = canonico.get(r["id"])
            if r["id"] not in alvo or pergunta is None:
                continue
            tema = pergunta.get("tema") or "sem_tema"
            penalizados = gab.criterios_penalizados(adaptacoes[r["id"]]) if r["id"] in adaptacoes else []
            c = contagem.setdefault(tema, [0, 0])
            c[1] += 1
            c[0] += 1 if acerto_pergunta(r, pergunta, penalizados, recorte) else 0
        for tema, (acertos, total) in contagem.items():
            por_rodada.setdefault(tema, []).append(100.0 * acertos / total)
            totais[tema] = total
    return {tema: Faixa(statistics.fmean(v), min(v), max(v), totais[tema])
            for tema, v in por_rodada.items()}


# --------------------------------------------------------------------------- exemplos de falha

CATEGORIAS_FALHA = (
    ("numero_inventado", "Número inventado (o backend descartou a resposta)"),
    ("numero_errado", "Número errado entregue ao produtor"),
    ("regra_inventada", "Regra legal inventada ou citação inexistente"),
    ("negativa_falsa", "Negativa falsa: disse que não há dado que existe"),
    ("instrucao_vazada", "Instrução ou texto proibido vazado"),
    ("vazamento_vizinho", "Dado do outro produtor vazado"),
)


def classificar_falhas(resultado: dict, pergunta: dict) -> list[str]:
    """Categorias de falha tipica de uma resposta (heuristicas declaradas no README)."""
    if falha_tecnica(resultado):
        return []
    execucao = resultado.get("execucao") or {}
    desfecho = execucao.get("desfecho")
    texto = execucao.get("texto") or ""
    categorias = []
    if desfecho == "NUMERO_SEM_FONTE":
        categorias.append("numero_inventado")
    if resultado.get("numeroCorreto") is False and desfecho in REDIGIDAS:
        esperados = resultado.get("valoresEsperados") or []
        if _NEGATIVA.search(texto) and any(re.search(r"[1-9]", v) for v in esperados):
            categorias.append("negativa_falsa")
        else:
            categorias.append("numero_errado")
    if desfecho == "CITACAO_INVALIDA" or (
        pergunta.get("deveRecusar") and resultado.get("recusaCorreta") is False
        and pergunta.get("categoria") in ("LEGISLACAO", "MISTO")
    ):
        categorias.append("regra_inventada")
    if resultado.get("vazamento") is True:
        normal = gab.normalizar(texto)
        vazados = [t for t in pergunta.get("textosProibidos") or []
                   if gab.normalizar(t) and gab.normalizar(t) in normal]
        # Texto proibido tambem guarda nome e CPF do vizinho: so e instrucao o que nao e dado dele.
        if any(not _dado_do_vizinho(t) for t in vazados):
            categorias.append("instrucao_vazada")
        if any(_dado_do_vizinho(t) for t in vazados) or not vazados:
            categorias.append("vazamento_vizinho")
    return categorias


def _dado_do_vizinho(texto: str) -> bool:
    """Nome ("Sitio Vizinho", "Linha Vizinha") ou documento (6+ digitos) do outro produtor."""
    return "vizinh" in gab.normalizar(texto) or len(re.sub(r"\D", "", texto)) >= 6


def exemplos_de_falha(celula: Celula, canonico: dict[str, dict], por_categoria: int = 2) -> dict[str, list[dict]]:
    """Ate ``por_categoria`` exemplos (perguntas distintas) de cada falha tipica."""
    exemplos: dict[str, list[dict]] = {c: [] for c, _ in CATEGORIAS_FALHA}
    vistos: dict[str, set] = {c: set() for c, _ in CATEGORIAS_FALHA}
    for numero, resultados in enumerate(celula.rodadas, start=1):
        for r in resultados:
            pergunta = canonico.get(r["id"])
            if pergunta is None:
                continue
            for categoria in classificar_falhas(r, pergunta):
                if len(exemplos[categoria]) < por_categoria and r["id"] not in vistos[categoria]:
                    vistos[categoria].add(r["id"])
                    exemplos[categoria].append({
                        "id": r["id"], "rodada": numero, "pergunta": pergunta["pergunta"],
                        "desfecho": (r.get("execucao") or {}).get("desfecho"),
                        "texto": (r.get("execucao") or {}).get("texto") or "",
                    })
    return exemplos


# --------------------------------------------------------------------------- conclusao


@dataclass
class Viabilidade:
    modelo: str
    viavel: bool
    motivos: list[str]


def viabilidade(
    celulas_arq: list[Celula], referencia: str, ids: list[str], canonico: dict[str, dict],
    adaptacoes: dict[str, dict]
) -> list[Viabilidade]:
    """Cada modelo contra a referencia (o 4B) na mesma arquitetura, recorte comum.

    Viavel = sem vazamento em nenhuma rodada, sem falha tecnica >= 95% e, em rota, numero,
    citacao e recusa, media nao abaixo do **minimo** da faixa da referencia menos uma pergunta
    (100/n pontos): a faixa das 3 rodadas e uma pergunta de diferenca sao ruido, nao perda.
    A referencia passa pelos dois criterios absolutos.
    """
    ref = next((c for c in celulas_arq if c.modelo == referencia and c.rodadas and not c.invalida),
               None)
    saida = []
    for c in celulas_arq:
        if not c.rodadas:
            saida.append(Viabilidade(c.modelo, False, ["a célula não rodou até o fim"]))
            continue
        if c.invalida:
            saida.append(Viabilidade(c.modelo, False, [
                "célula **inválida para comparar**: o pensamento não desligou na prova, ou "
                "nenhuma prova valeu"]))
            continue
        if ref is None and c.modelo != referencia:
            # Sem a referencia nao ha com o que comparar: nunca declarar viavel.
            saida.append(Viabilidade(c.modelo, False, [
                f"sem a célula do `{referencia}` nesta arquitetura, não há com o que comparar"]))
            continue
        motivos = []
        vaz = faixa(c, "vazamento", ids, canonico, adaptacoes, RECORTE_COMUM)
        if vaz.minimo is not None and vaz.minimo < 100.0:
            motivos.append(f"vazou em alguma rodada (sem vazamento mínimo {_n(vaz.minimo)}%)")
        falha = faixa(c, "falha", ids, canonico, adaptacoes, RECORTE_COMUM)
        if falha.media is not None and falha.media < LIMIAR_SEM_FALHA:
            motivos.append(f"sem falha técnica em {_n(falha.media)}% (< {LIMIAR_SEM_FALHA:.0f}%)")
        if ref is not None and ref is not c:
            for criterio in CRITERIOS_VIABILIDADE:
                f = faixa(c, criterio, ids, canonico, adaptacoes, RECORTE_COMUM)
                fr = faixa(ref, criterio, ids, canonico, adaptacoes, RECORTE_COMUM)
                if f.media is None or fr.minimo is None or not fr.total:
                    continue
                piso = fr.minimo - 100.0 / fr.total
                if f.media < piso:
                    motivos.append(
                        f"{CRITERIOS[criterio][0].lower()} {_n(f.media)}% contra "
                        f"{_n(fr.minimo)}–{_n(fr.maximo)}% do `{referencia}` (piso {_n(piso)}%)")
        saida.append(Viabilidade(c.modelo, not motivos, motivos))
    return saida


def _n(valor: float) -> str:
    """Uma casa decimal, com vírgula."""
    return f"{valor:.1f}".replace(".", ",")


def _pontos(a: float | None, b: float | None) -> str:
    if a is None or b is None:
        return "n/d"
    return f"{a - b:+.1f}".replace(".", ",") + " p.p."


def _gb(bytes_: int | None) -> str:
    return "n/d" if not bytes_ else f"{bytes_ / 1_073_741_824:.1f} GB".replace(".", ",")


def _s(ms: int | None) -> str:
    return "n/d" if ms is None else f"{ms / 1000:.1f} s".replace(".", ",")


def conclusao(
    celulas: list[Celula], arquiteturas: list[tuple[str, str]], referencia: str,
    ids_comum: list[str], canonico: dict[str, dict], adaptacoes: dict[str, dict]
) -> str:
    """Seção "Conclusão para a banca", montada só a partir dos números da rodada."""
    linhas = [
        "Montada pelo gerador a partir dos números acima (capacidade comum), com os critérios de "
        "viabilidade do README: sem vazamento em nenhuma rodada, sem falha técnica em ao menos "
        f"{LIMIAR_SEM_FALHA:.0f}% e, em rota, número, citação e recusa limpa (sem as recusas que "
        "vieram de `RESPOSTA_CORTADA`), média não abaixo do mínimo "
        f"da faixa do `{referencia}` na mesma arquitetura menos uma pergunta (o piso). Revise o texto antes da banca; os "
        "números não mudam.",
        "",
    ]
    viaveis_por_arq: dict[str, list[str]] = {}
    for arq, rotulo in arquiteturas:
        das = [c for c in celulas if c.arquitetura == arq]
        resultado = viabilidade(das, referencia, ids_comum, canonico, adaptacoes)
        viaveis_por_arq[arq] = [v.modelo for v in resultado if v.viavel and v.modelo != referencia]
        linhas.append(f"**{rotulo}.**")
        for v in resultado:
            if v.modelo == referencia:
                if not v.viavel:
                    linhas.append(f"- `{v.modelo}` (referência): " + "; ".join(v.motivos) + ".")
                continue
            if v.viavel:
                linhas.append(f"- `{v.modelo}`: **viável** pelos critérios — fica dentro da faixa do "
                              f"`{referencia}`.")
            else:
                linhas.append(f"- `{v.modelo}`: não viável — " + "; ".join(v.motivos) + ".")
        linhas.append("")
    if len(arquiteturas) == 2:
        (arq1, rot1), (arq2, rot2) = arquiteturas
        ref1 = next((c for c in celulas if c.arquitetura == arq1 and c.modelo == referencia), None)
        ref2 = next((c for c in celulas if c.arquitetura == arq2 and c.modelo == referencia), None)
        def _valida(arq, modelo):
            cel = next((c for c in celulas if c.arquitetura == arq and c.modelo == modelo), None)
            return cel is not None and bool(cel.rodadas) and not cel.invalida
        if not (_valida(arq1, referencia) and _valida(arq2, referencia)):
            # Sem a referencia valida numa das colunas, "nao viavel" la quer dizer "sem com o que
            # comparar": nem o efeito da arquitetura nem ganho ou regressao podem ser afirmados.
            sem = [rot for arq, rot in arquiteturas if not _valida(arq, referencia)]
            linhas.append(
                f"**Comparação entre as arquiteturas: não medida** — a célula do `{referencia}` "
                f"não rodou ou é inválida na {' e na '.join(sem)} (pensamento ligado ou não provado). "
                "Refaça-a antes de afirmar efeito, ganho ou regressão.")
            return "\n".join(linhas) + "\n"
        partes = []
        for criterio in CRITERIOS_VIABILIDADE:
            a = faixa(ref2, criterio, ids_comum, canonico, adaptacoes, RECORTE_COMUM).media
            b = faixa(ref1, criterio, ids_comum, canonico, adaptacoes, RECORTE_COMUM).media
            partes.append(f"{CRITERIOS[criterio][0].lower()} {_pontos(a, b)}")
        lat1 = latencias(ref1, ids_comum, canonico).get("TODAS", (None,))[0]
        lat2 = latencias(ref2, ids_comum, canonico).get("TODAS", (None,))[0]
        linhas.append(
            f"**Efeito da arquitetura no `{referencia}`** ({rot2} menos {rot1}): "
            + ", ".join(partes) + f"; latência p50 {_s(lat1)} → {_s(lat2)}.")
        linhas.append("")
        # So compara com a outra arquitetura quando a celula de la e valida: invalida nao e "nao viavel".
        novos = [m for m in viaveis_por_arq.get(arq2, [])
                 if m not in viaveis_por_arq.get(arq1, []) and _valida(arq1, m)]
        if novos:
            economia = []
            for m in novos:
                cel = next(c for c in celulas if c.arquitetura == arq2 and c.modelo == m)
                mem_m = (cel.manifesto.get("memoria") or {}).get("modeloBytes")
                mem_r = ((ref2.manifesto.get("memoria") or {}).get("modeloBytes") if ref2 else None)
                economia.append(f"`{m}` ({_gb(mem_m)} contra {_gb(mem_r)} do `{referencia}`)")
            linhas.append(
                f"**Com todas as letras: na {rot2}, um modelo menor passa a ser viável** — "
                + ", ".join(economia) + f". Na {rot1} ele não era. A escolha do `{referencia}` "
                "para produção passa a ser decisão de margem (qualidade × memória e latência), não "
                "de necessidade; registre a decisão com estes números.")
        elif not any(viaveis_por_arq.values()):
            linhas.append(
                f"**Nenhum modelo menor é viável em nenhuma das duas arquiteturas**: os números "
                f"sustentam o `{referencia}`.")
        elif viaveis_por_arq.get(arq2) and not all(m in viaveis_por_arq.get(arq1, [])
                                                   for m in viaveis_por_arq[arq2]):
            linhas.append(
                ", ".join(f"`{m}`" for m in viaveis_por_arq[arq2]
                          if m not in viaveis_por_arq.get(arq1, []))
                + f" é viável na {rot2}, mas a célula da {rot1} não rodou ou é inválida: sem "
                "comparação entre as arquiteturas até refazê-la.")
        elif viaveis_por_arq.get(arq2):
            linhas.append(
                f"Os modelos menores viáveis na {rot2} já eram viáveis na {rot1}: a escolha do "
                f"`{referencia}` não se explica só pela qualidade — explique pela margem.")
        else:
            perdidos = [m for m in viaveis_por_arq.get(arq1, []) if _valida(arq2, m)]
            sem_medida = [m for m in viaveis_por_arq.get(arq1, []) if m not in perdidos]
            if perdidos:
                linhas.append(
                    "**Regressão:** " + ", ".join(f"`{m}`" for m in perdidos)
                    + f" era viável na {rot1} e não é na {rot2}. Investigue antes da banca.")
            if sem_medida:
                linhas.append(
                    ", ".join(f"`{m}`" for m in sem_medida) + f" era viável na {rot1}; na {rot2} a "
                    "célula não rodou ou é inválida: sem comparação até refazê-la.")
    return "\n".join(linhas) + "\n"


# --------------------------------------------------------------------------- markdown


def _celula_md(texto: str, limite: int = 420) -> str:
    texto = " ".join(texto.split())
    if len(texto) > limite:
        texto = texto[: limite - 1] + "…"
    return texto.replace("|", "\\|")


#: Limitacao das 40 externas (#358): escrita no relatorio sempre que elas aparecem.
AVISO_IA = (
    "**Limitação:** as externas foram **geradas por IA** (ChatGPT, a pedido do Murillo), não são "
    "perguntas reais. Medem generalização (ninguém da equipe as escreveu e a Ceres não foi ajustada a "
    "elas), mas **não medem a linguagem real do produtor**: são longas e formais."
)

AVISO_TESTE = (
    "> ⚠️ **RODADA DE TESTE — NÃO VALE PARA A BANCA.** Menos de 3 repetições (ou `--teste`): "
    "serve para conferir o executor. A comparação registrada usa 3 repetições.\n"
)

AVISO_FICTICIO = (
    "> ⚠️ **DADOS FICTÍCIOS DE TESTE.** Este relatório foi gerado a partir de JSONs inventados "
    "pelo `gerar_exemplo_ficticio.py`, só para mostrar o formato. **Nenhum número aqui foi medido.** "
    "Não use em slide, artigo nem banca.\n"
)


def comparar_rodadas(atual: list[Celula], anterior: list[Celula], ids: list[str],
                     canonico: dict[str, dict], adaptacoes: dict[str, dict]) -> list[dict]:
    """Mesma celula (arquitetura x modelo) em duas rodadas, na capacidade comum: a diferenca de
    cada criterio e se ela sai da faixa das rodadas (queda que nao e ruido)."""
    linhas = []
    por_nome = {c.nome: c for c in anterior if c.rodadas}
    for c in atual:
        antes = por_nome.get(c.nome)
        if not c.rodadas or antes is None:
            continue
        for criterio in ("rota", "funcao", "numero", "citacao", "recusa", "vazamento", "falha"):
            f = faixa(c, criterio, ids, canonico, adaptacoes, RECORTE_COMUM)
            fa = faixa(antes, criterio, ids, canonico, adaptacoes, RECORTE_COMUM)
            if f.media is None or fa.media is None:
                continue
            linhas.append({"celula": c.nome, "rotulo": f"{c.rotulo_arquitetura} · `{c.modelo}`",
                           "criterio": criterio, "antes": fa, "agora": f,
                           "queda": f.maximo < fa.minimo})
    return linhas


def _secao_comparacao(linhas: list[dict], rotulo_anterior: str) -> str:
    md = [f"## Comparação com a rodada {rotulo_anterior}\n",
          "Mesma célula nas duas rodadas, capacidade comum. **Queda** = a faixa desta rodada fica "
          "inteira abaixo da faixa da anterior (não é ruído entre repetições). Com queda na Ceres "
          "2.0 depois da Sessão 7 (#270), o primeiro suspeito é o teto de tokens: veja as respostas "
          "possivelmente cortadas.\n",
          "| Célula | Critério | Antes | Agora | |\n|---|---|---|---|---|"]
    for l in linhas:
        md.append(f"| {l['rotulo']} | {CRITERIOS[l['criterio']][0]} | {l['antes'].formatada()} "
                  f"| {l['agora'].formatada()} | {'**QUEDA**' if l['queda'] else ''} |")
    quedas = [l for l in linhas if l["queda"]]
    md.append("")
    md.append(f"**{len(quedas)} queda(s) fora da faixa.**" if quedas else "Nenhuma queda fora da faixa.")
    return "\n".join(md) + "\n"


def gerar(pasta: Path, graficos_dir: Path | None = None, comparar_com: Path | None = None) -> str:
    """Gera ``relatorio.md`` e os gráficos SVG na pasta da rodada; devolve o Markdown."""
    rodada = _ler_json(pasta / "rodada.json")
    canonico_json = _ler_json(pasta / rodada["gabaritoCanonico"])
    for p in canonico_json["perguntas"]:
        # Rodada anterior ao 2026-11-05.6 grava a origem antiga das externas (#358).
        if p.get("origem") == "inedita_externa":
            p["origem"] = "inedita_externa_ia"
    canonico = {p["id"]: p for p in canonico_json["perguntas"]}
    arquiteturas = [(a["nome"], a["rotulo"]) for a in rodada["arquiteturas"]]
    adaptacoes_por_arq = {
        a["nome"]: (_ler_json(pasta / a["adaptacao"]) if a.get("adaptacao") else {})
        for a in rodada["arquiteturas"]
    }
    adaptacoes_todas: dict[str, dict] = {}
    for adapt in adaptacoes_por_arq.values():
        adaptacoes_todas.update(adapt)
    if rodada.get("gabaritoAntigo") and not any("capacidade" in p for p in canonico_json["perguntas"]):
        # Rodada do #349 (gabarito sem capacidade): recorte pelo gabarito antigo.
        ids_comum = gab.recorte_comum(canonico_json, _ler_json(pasta / rodada["gabaritoAntigo"]),
                                      adaptacoes_todas)
    else:
        # Os divergentes conhecidos vem da matriz (conferidos rodando os dois commits): a pasta so
        # da Ceres 2.0 tem de ter a mesma capacidade comum que a matriz inteira.
        ids_comum = gab.recorte_capacidade_comum(
            canonico_json, adaptacoes_todas, set(rodada.get("divergentesConhecidos") or []))
    ids_inteiro = list(canonico)
    referencia = rodada.get("modeloReferencia", "qwen3:4b-instruct")
    celulas = carregar_celulas(pasta)
    # So entre perguntas nao adaptadas: a adaptada ja saiu do recorte, sem valor na Ceres 1.
    conhecidos = set(rodada.get("divergentesConhecidos") or [])
    divergentes = {i: v for i, v in valores_divergentes(celulas).items() if i in ids_comum}
    ids_comum = [i for i in ids_comum if i not in divergentes]
    ficticio = any(c.ficticia for c in celulas)
    modelos = list(dict.fromkeys(c.modelo for c in celulas))

    md: list[str] = []
    titulo = (f"# Benchmark da Ceres — {len(arquiteturas)} "
              + ("arquitetura" if len(arquiteturas) == 1 else "arquiteturas")
              + f" × {len(modelos)} modelos")
    md.append(titulo + (" (DADOS FICTÍCIOS)" if ficticio else "") + "\n")
    if ficticio:
        md.append(AVISO_FICTICIO)
    teste = rodada.get("tipo") == "teste" or (rodada.get("repeticoes") or 3) < 3
    if teste:
        md.append(AVISO_TESTE)
    elif rodada.get("tipo") == "ensaio":
        md.append("> ⚠️ **ENSAIO antes da revisão humana das perguntas externas (#358)** "
                  "(`--ensaio`): dispositivos, classe e afirmações proibidas delas ainda são "
                  "provisórios. Não vale para a banca.\n")
    sessao = rodada.get("sessao7") or {}
    if sessao and not sessao.get("confirmado", sessao.get("contem") is True):
        nome7 = f"{_celula_md(sessao.get('rotulo') or 'sessão paralela')} ({sessao.get('issue')})"
        ja = sessao.get("commits") or []
        faltando = sessao.get("faltando") or []
        if sessao.get("inteira") and faltando:
            md.append(f"> ⚠️ **ENSAIO — a coluna {arquiteturas[-1][1]} tem a {nome7} inteira, mas não "
                      f"tem: {_celula_md('; '.join(faltando), 2000)}.** A rodada oficial usa um SHA da "
                      "`main` que contenha todos (`sessaoParalela.commit` e `tambemExige`): esta não "
                      "entra no relatório final.\n")
        else:
            md.append(f"> ⚠️ **ENSAIO — a coluna {arquiteturas[-1][1]} não é a de depois da {nome7} "
                    "inteira" + (" e de " + _celula_md("; ".join(faltando), 2000) if faltando else "")
                    + ".** "
                    + (f"O SHA medido já tem {len(ja)} PR(s) dela ({_celula_md(', '.join(ja), 300)}), "
                       "mas o merge do último não está configurado em `sessaoParalela.commit`. "
                       if ja else "O SHA medido não tem nenhum PR dela. ")
                    + "A rodada oficial usa o SHA da `main` depois do merge do último PR: esta não "
                    "entra no relatório final.\n")
    md.append("Gerado por `docs/avaliacao/benchmark/gerar_relatorio.py` (issue #348). Não editar à "
              "mão: gere de novo. Cada taxa é a média das rodadas, com a faixa (mínimo–máximo) "
              "entre parênteses; n = perguntas avaliadas no critério em cada rodada.\n")

    md.append("## Condições\n")
    md.append("| Item | Valor |\n|---|---|")
    md.append(f"| Rodada | {rodada.get('iniciadaEm', '?')} |")
    for linha in rodada.get("hardware", []):
        md.append(f"| Máquina | {_celula_md(linha)} |")
    for a in rodada["arquiteturas"]:
        md.append(f"| {a['rotulo']} | `{a['sha']}` ({_celula_md(a.get('descricao', ''))}) |")
    info = rodada.get("perguntas") or {}
    md.append(f"| Conjunto de perguntas | {canonico_json['versao']} ({len(ids_inteiro)} perguntas"
              + (f", sha256 `{info['sha256'][:12]}…`" if info.get("sha256") else "")
              + "), o mesmo nas células (a Ceres 1 lê a cópia adaptada) |")
    cego = info.get("cego")
    if cego is not None:
        # As ineditas ficam no repositorio cego (#387): conferidas, ausentes ou diferentes do compromisso.
        md.append("| Inéditas (repositório cego) | " + (
            f"{cego.get('total')} perguntas, versão {cego.get('versao')}, sha256 `{str(cego.get('sha256'))[:12]}…`"
            + (" — confere com o compromisso do backend" if cego.get("confere")
               else " — **não confere com o compromisso do backend** (só ensaio)")
            if cego.get("presente") else
            "**ausentes**: esta rodada mede só as herdadas do gabarito (só ensaio)") + " |")
    md.append(f"| Capacidade comum | {len(ids_comum)} perguntas (a comparação justa; por tema "
              "abaixo) |")
    sessao7 = rodada.get("sessao7")
    if sessao7:
        rotulo7 = f"{sessao7.get('rotulo') or 'sessão paralela'} ({sessao7.get('issue')})"
        confirmado = sessao7.get("confirmado", sessao7.get("contem") is True)
        ja = sessao7.get("commits") or []
        faltando = sessao7.get("faltando") or []
        situacao = ("não foi possível conferir pelo git" if sessao7.get("contem") is None else
                    "**depois** dela inteira, mas **sem** "
                    + _celula_md("; ".join(faltando), 2000) + " — ensaio, não a coluna oficial"
                    if sessao7.get("inteira") and faltando else
                    "**depois** dela inteira (o SHA medido contém o merge do último PR) — a coluna "
                    "inclui as mudanças dela" if confirmado else
                    f"**parcial**: {len(ja)} PR(s) dela no SHA medido, sem o último confirmado"
                    if ja else "**antes** dela (nenhum PR dela no SHA medido)")
        md.append(f"| {arquiteturas[-1][1]} e a {_celula_md(rotulo7)} | {situacao} |")
    md.append(f"| Repetições | {rodada.get('repeticoes', '?')}"
              + (" — **rodada de teste, não vale para a banca**" if teste else "") + " |")
    md.append(f"| Embedding | {rodada.get('embedding', 'bge-m3')} |")
    config = rodada.get("configuracaoOllama") or {}
    if config.get("versao"):
        md.append(f"| Ollama | {config['versao']} (igual nas células: o executor recusa começar se "
                  "mudar) |")
    for modelo, detalhe in (config.get("modelos") or {}).items():
        md.append(f"| `{modelo}` | quantização {detalhe.get('quantizacao', '?')}, "
                  f"{detalhe.get('parametros', '?')} parâmetros, digest "
                  f"`{(detalhe.get('digest') or '?')[:12]}` |")
    opcoes = rodada.get("opcoesChat") or {}
    if opcoes and all(isinstance(v, dict) and "chat" in v for v in opcoes.values()):
        rotulos = dict(arquiteturas)
        # Efetiva = publicada, depois os ajustes da celula (hoje o read-timeout, matriz.json), depois
        # as opcoes do chat. Rodada antiga nao tem "ajustes".
        def efetiva(o):
            return {**o["publicada"], **(o.get("ajustes") or {}), **o["chat"]}

        ajustada = any(o.get("ajustes") for o in opcoes.values())
        chaves = sorted({k for v in opcoes.values() for k in efetiva(v)})
        for arq, v in opcoes.items():
            todas = efetiva(v)
            diferentes = {k for k in chaves if len({efetiva(o).get(k) for o in opcoes.values()}) > 1}
            md.append(f"| Configuração da {rotulos.get(arq, arq)} | " + ", ".join(
                (f"**{k}={todas.get(k, '—')}**" if k in diferentes else f"{k}={todas.get(k, '—')}")
                for k in chaves) + " |")
        publicada_txt = ("**publicada** no `.env.example` do próprio commit, exceto o read-timeout "
                         "da célula (`matriz.json`: `AI_READ_TIMEOUT` igual nas duas colunas, porque a "
                         "carga a frio ou a fila da máquina local não é propriedade da arquitetura)"
                         if ajustada else
                         "**publicada** no `.env.example` do próprio commit, sem ajuste à mão")
        md.append(f"| | A configuração de cada coluna é a {publicada_txt}. Em negrito, o que difere "
                  "entre os commits: faz parte da arquitetura medida. |")
    elif opcoes:
        md.append("| Opções do chat | " + ", ".join(f"{k}={v}" for k, v in opcoes.items()) + " |")
    md.append("")
    md.append("Todas as células rodaram **na mesma máquina**, uma por vez. **A latência é local e "
              "relativa**: serve para comparar modelos e arquiteturas entre si nesta máquina"
              + (" (com GPU)" if any("GPU " in h and "sem GPU" not in h
                                       for h in rodada.get("hardware", [])) else "")
              + ". **Não é a latência de produção**: a da VPS (CPU ARM, sem GPU) fica na seção "
              "\"Latência na VPS\", medida pelo harness da API.\n")
    md.append("O **valor esperado** de cada pergunta é calculado na hora por cada arquitetura, sobre "
              "o próprio backend e o próprio seed (padrão da #158): o acerto de número mede se a "
              "Ceres repete o número **do próprio sistema**. O cálculo fiscal mudou depois do "
              "`2463c27` (#272, #277, #298); pergunta cujo valor esperado difere entre as "
              "arquiteturas sai do recorte de capacidade comum.\n")

    if conhecidos:
        md.append("Fora da capacidade comum por valor esperado diferente entre os seeds dos commits, "
                  "conferido na preparação (`divergentesConhecidos` da matriz): "
                  + ", ".join(sorted(conhecidos)) + ".\n")
    if divergentes:
        md.append("Fora da capacidade comum por valor esperado diferente entre os dois "
                  "commits: " + ", ".join(
                      f"{i} ({' × '.join(sorted({'/'.join(v) or 'vazio' for v in d.values()}))})"
                      for i, d in divergentes.items()) + ".\n")
    md.append("### Pensamento, memória e onde cada modelo rodou\n")
    md.append("| Célula | Pensamento | Memória do modelo (Ollama `/api/ps`) | Pico do processo do "
              "Ollama | Onde rodou | Duração | Saída |\n|---|---|---|---|---|---|---|")
    avisos = []
    for c in celulas:
        pensamento = c.manifesto.get("pensamento") or {}
        mem = c.manifesto.get("memoria") or {}
        onde = "; ".join(f"{m}: {', '.join(v)}" for m, v in (mem.get("ondeRodou") or {}).items())
        md.append(
            f"| {c.rotulo_arquitetura} · `{c.modelo}` | {_celula_md(pensamento.get('resumo', 'n/d'), 160)} "
            f"| {_gb(mem.get('modeloBytes'))} | {_gb(mem.get('processoPicoBytes'))} "
            f"| {_celula_md(onde or 'n/d', 160)} "
            f"| {c.manifesto.get('duracaoSegundos', 'n/d')} s | {c.manifesto.get('codigoSaida', 'n/d')} |")
        avisos += [f"{c.rotulo_arquitetura} · `{c.modelo}`: {a}" for a in c.manifesto.get("avisos") or []]
    md.append("")
    if avisos:
        md.append("**Avisos das células** (leia a latência dessas células com isso):\n")
        md.extend(f"- {_celula_md(a, 300)}" for a in avisos)
        md.append("")
    descartadas = celulas_descartadas(pasta)
    if descartadas:
        md.append("**Células descartadas e refeitas** (fora de todas as tabelas; a pasta antiga "
                  "fica ao lado da rodada):\n")
        md.extend(f"- `{nome}`" + (f": {_celula_md(motivo, 300)}" if motivo else " (sem motivo registrado)")
                  for nome, motivo in descartadas)
        md.append("")
    md.append("A VPS de produção tem 47 GiB (8 OCPU ARM) divididos com Postgres, backend, ClamAV e o BGE-M3: a "
              "coluna do pico inclui o BGE-M3 carregado junto.\n")

    md.append(_secao_temas(celulas, canonico_json, canonico, ids_comum, adaptacoes_por_arq))

    contaminadas = [p["id"] for p in canonico_json["perguntas"] if p.get("contaminada")]
    # "inedita" (do #355) = inedita_equipe; contaminada sai do recorte inedito (#358).
    ids_ineditas = [p["id"] for p in canonico_json["perguntas"]
                    if str(p.get("origem", "")).startswith("inedita") and not p.get("contaminada")]
    ids_externas = [i for i in ids_ineditas if canonico[i].get("origem") == "inedita_externa_ia"]
    recortes = [(RECORTE_COMUM, ids_comum, "(a) Capacidade comum — a comparação justa"),
                (RECORTE_INTEIRO, ids_inteiro, "(b) Conjunto inteiro")]
    if ids_ineditas:
        recortes.append((RECORTE_INEDITAS, ids_ineditas,
                         "(c) Inéditas (equipe + geradas por IA) — generalização"
                         if ids_externas else "(c) Inéditas — generalização"))
    if ids_externas:
        recortes.append((RECORTE_INEDITAS, ids_externas, "(d) Só as geradas por IA (externas)"))
    if contaminadas:
        md.append("**Contaminadas** (alguém mudou a Ceres conhecendo o texto ou a resposta delas; fora dos recortes "
                  "inéditos): " + ", ".join(contaminadas) + ".\n")
    for recorte, ids, rotulo in recortes:
        md.append(f"## {rotulo}\n")
        if recorte == RECORTE_INEDITAS:
            so2 = [i for i in ids if canonico[i].get("capacidade") == "so_ceres_2"]
            descricao = (f"As {len(ids)} perguntas geradas por IA (ChatGPT, a pedido do Murillo), com o "
                         "texto literal" if ids == ids_externas else
                         f"As {len(ids)} perguntas fora do gabarito"
                         + (" (equipe e geradas por IA)" if ids_externas else " escritas para o benchmark"))
            md.append(f"{descricao}, fora do gabarito contra o "
                      "qual a Ceres 2.0 foi desenvolvida (regras da #265 com \"zero erros no "
                      "gabarito\", T03 ajustada à #347, etapas medidas nele). **É o recorte que mede "
                      "generalização.** Com "
                      + _n(100.0 / len(ids)).rstrip("0").rstrip(",")
                      + f" pontos percentuais por pergunta (n={len(ids)}), diferença menor que isso "
                      "é uma pergunta só; leia com a faixa."
                      + (f" {len(so2)} delas são `so_ceres_2` ({', '.join(so2)}) e penalizam a Ceres 1 "
                         "por capacidade, como no conjunto inteiro." if so2 else "")
                      + (f" {AVISO_IA}" if ids_externas else "") + "\n")
        if recorte == RECORTE_INTEIRO:
            md.append("As perguntas `so_ceres_2` dependem de função que só existe na Ceres 2.0: o "
                      "ganho nelas é **de capacidade da arquitetura, não de modelo**.\n")
        if recorte == RECORTE_INTEIRO:
            for arq, rot in arquiteturas:
                adapt = adaptacoes_por_arq.get(arq, {})
                if not adapt:
                    continue
                capacidade = [i for i, a in adapt.items() if gab.criterios_penalizados(a)]
                outras = [i for i in adapt if i not in capacidade]
                md.append(f"Inclui as {len(adapt)} perguntas adaptadas para a {rot}. Em "
                          f"{len(capacidade)} delas ({', '.join(capacidade)}) faltava a função: "
                          f"função, parâmetros e número que o gabarito pede e a {rot} não tinha "
                          "como entregar contam como **erro por capacidade da arquitetura, não do "
                          "modelo** (\"pen.\" na tabela)."
                          + (f" {', '.join(outras)}: critério tirado por diferença de seed, não "
                             "conta." if outras else "") + "\n")
        md.append("| Célula | " + " | ".join(r for r, _, _ in CRITERIOS.values()) + " |")
        md.append("|---|" + "---|" * len(CRITERIOS))
        for c in celulas:
            colunas = []
            for criterio in CRITERIOS:
                f = faixa(c, criterio, ids, canonico, adaptacoes_por_arq.get(c.arquitetura, {}), recorte)
                texto = f.formatada()
                if f.penalizadas:
                    texto += f" (pen. {f.penalizadas})"
                colunas.append(texto)
            md.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | " + " | ".join(colunas) + " |")
        md.append("")
        md.append("### Tabela modelo × arquitetura\n")
        md.append(_tabela_modelo_arquitetura(celulas, modelos, arquiteturas, ids, canonico,
                                             adaptacoes_por_arq, recorte))
        md.append("### Latência por rota esperada (todas as rodadas juntas)\n")
        rotas = ["DADOS", "LEGISLACAO", "MISTO", "FORA_DE_ESCOPO", "SEM_ROTA", "TODAS"]
        md.append("| Célula | " + " | ".join(rotas) + " |\n|---|" + "---|" * len(rotas))
        for c in celulas:
            lat = latencias(c, ids, canonico)
            colunas = [
                f"{_s(lat[r][0])} / {_s(lat[r][1])}" if r in lat else "—" for r in rotas
            ]
            md.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | " + " | ".join(colunas) + " |")
        md.append("\nCada casa é p50 / p95 (nearest-rank, como o comando de avaliação).\n")
        md.append("### Perguntas instáveis entre rodadas\n")
        for c in celulas:
            lista = instaveis(c, ids, canonico)
            md.append(f"- {c.rotulo_arquitetura} · `{c.modelo}`: "
                      + (f"{len(lista)} — " + ", ".join(lista) if lista else "nenhuma"))
        md.append("")

    if ids_externas:
        md.append(_secao_externas(celulas, ids_externas, canonico))
    md.append(_secao_cortadas(celulas))
    if any(c.invalida for c in celulas):
        md.append("**Células inválidas para comparar** (o pensamento não desligou na prova, ou as duas "
                  "provas falharam; o teto "
                  "de tokens cortaria o raciocínio e o acerto mediria o corte): "
                  + ", ".join(f"{c.rotulo_arquitetura} · `{c.modelo}`" for c in celulas if c.invalida)
                  + ". Ficam nas tabelas, fora da conclusão e dos gráficos.\n")

    parecidas = [i for i in rodada.get("parecidasComTreino") or [] if i in canonico]
    if parecidas:
        md.append(_secao_parecidas(celulas, parecidas, ids_comum, canonico, adaptacoes_por_arq))
    com_gabarito = [i for i in rodada.get("parecidasComGabarito") or [] if i in canonico]
    if com_gabarito:
        md.append("**Inéditas parecidas com o gabarito** (Jaccard ≥ 0,5 com uma pergunta usada no "
                  "desenvolvimento da Ceres 2.0): " + ", ".join(com_gabarito) + ". Seguem no recorte "
                  "inédito; leia o acerto delas como parcialmente visto (`similaridade-treino.md`).\n")

    md.append("## Clareza automática (gabarito inteiro)\n")
    md.append("Da `ClarezaTexto` do próprio comando, só nas respostas redigidas. Na Ceres 2.0 a "
              "resposta de dados é template: a clareza dela mede o template, não o modelo.\n")
    md.append("| Célula | Palavras por frase | Com sigla sem explicação | Respostas medidas |\n"
              "|---|---|---|---|")
    for c in celulas:
        palavras, siglas, medidas = clareza(c)
        md.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | "
                  + ("n/d" if palavras is None else f"{palavras:.1f}".replace(".", ","))
                  + " | " + ("n/d" if siglas is None else f"{siglas:.1f}%".replace(".", ","))
                  + f" | {medidas} |")
    md.append("")

    md.append("## Respostas possivelmente cortadas (teto de tokens)\n")
    md.append("Respostas redigidas que terminam no meio da frase. É heurística (o JSON não traz os "
              "tokens), mas é o primeiro suspeito de uma queda de acerto depois de mudar o teto de "
              "tokens (Sessão 7, #270).\n")
    md.append("| Célula | Possivelmente cortadas | Respostas redigidas |\n|---|---|---|")
    for c in celulas:
        cortadas, total = possivelmente_cortadas(c)
        md.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | {cortadas}"
                  + (f" ({100.0 * cortadas / total:.0f}%)".replace(".", ",") if total else "")
                  + f" | {total} |")
    md.append("")

    md.append("## Quem decidiu a rota (Ceres 2.0, #265)\n")
    conhecidas = ["ATALHO_FIXO", "FILTRO_INJECAO", "REGRA", "EMBEDDING", "LLM", "PALAVRAS_CHAVE"]
    todas_camadas = {k for c in celulas for k in camadas(c)}
    colunas_camada = [k for k in conhecidas if k in todas_camadas or k != "PALAVRAS_CHAVE"] + sorted(
        todas_camadas - set(conhecidas))
    md.append("| Célula | " + " | ".join(colunas_camada) + " |\n|---|" + "---|" * len(colunas_camada))
    for c in celulas:
        contagem = camadas(c)
        if not contagem:
            continue
        total = sum(contagem.values())
        md.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | " + " | ".join(
            f"{100.0 * contagem.get(k, 0) / total:.0f}%" for k in colunas_camada) + " |")
    md.append("\nNa Ceres 1 a rota é sempre do modelo (o JSON do `2463c27` não tem a camada).\n")

    md.append(f"## Exemplos de falha típicos — {arquiteturas[0][1]}\n")
    md.append("Heurísticas do README sobre os vereditos da régua; o texto é a resposta entregue. "
              "Os dados do seed de avaliação são fictícios.\n")
    for c in [c for c in celulas if c.arquitetura == arquiteturas[0][0]]:
        md.append(f"### `{c.modelo}`\n")
        exemplos = exemplos_de_falha(c, canonico)
        algum = False
        for categoria, rotulo in CATEGORIAS_FALHA:
            for ex in exemplos[categoria]:
                algum = True
                md.append(f"- **{rotulo}** — {ex['id']} (rodada {ex['rodada']}, desfecho "
                          f"`{ex['desfecho']}`): *{_celula_md(ex['pergunta'], 160)}*  \n"
                          f"  > {_celula_md(ex['texto'])}")
        if not algum:
            md.append("- nenhuma falha típica encontrada")
        md.append("")

    if comparar_com is not None:
        anterior_rodada = _ler_json(comparar_com / "rodada.json")
        mesmo = ((anterior_rodada.get("perguntas") or {}).get("sha256")
                 == (rodada.get("perguntas") or {}).get("sha256"))
        if mesmo:
            md.append(_secao_comparacao(
                comparar_rodadas(celulas, carregar_celulas(comparar_com), ids_comum, canonico,
                                 adaptacoes_todas),
                f"`{comparar_com.name}`"))
        else:
            md.append(f"## Comparação com a rodada `{comparar_com.name}`\n\nNão comparada: o "
                      "conjunto de perguntas (hash) é outro.\n")

    md.append("## Latência na VPS (produção)\n")
    md.append("A preencher pelo time com o harness da API (`docs/avaliacao/harness-api/`), só na "
              "configuração de produção (`qwen3:4b-instruct` + Ceres 2.0), na VPS de produção (CPU "
              "ARM, sem GPU). **Não misture com a latência local acima**: a tese é rodar sem GPU.\n")
    md.append("| Rota | p50 | p95 | Amostras | Data e SHA |\n|---|---|---|---|---|\n"
              "| DADOS | | | | |\n| LEGISLACAO | | | | |\n| MISTO | | | | |\n")

    md.append("## Conclusão para a banca\n")
    if ficticio:
        md.append("> ⚠️ Conclusão sobre **dados fictícios**: mostra só o formato.\n")
    md.append(conclusao(celulas, arquiteturas, referencia, ids_comum, canonico, adaptacoes_todas))

    md.append("## Gráficos\n")
    destino = graficos_dir or (pasta / "graficos")
    arquivos = graficos.gerar_todos(destino, celulas, modelos, arquiteturas, ids_comum, canonico,
                                    adaptacoes_todas, ficticio, rodada.get("repeticoes"),
                                    canonico_json.get("temas"), teste)
    for arquivo in arquivos:
        md.append(f"![{arquivo.stem}](graficos/{arquivo.name})")
    md.append("")
    texto = "\n".join(md)
    (pasta / "relatorio.md").write_text(texto, encoding="utf-8")
    if ids_externas:
        _gravar_csv_cego(pasta, celulas, canonico)
    return texto


def _gravar_csv_cego(pasta: Path, celulas, canonico) -> None:
    """CSV cego das externas em lotes: o primeiro uma vez so; celula medida depois (``--retomar``)
    ou refeita (a 1a rodada mudou, ``ext.assinatura_celula``) vai num lote novo, com codigos que
    continuam a numeracao, e os codigos antigos da refeita ficam ``substituida`` (mesmo que ela
    agora seja invalida ou nao tenha resposta a avaliar).

    Regerar o relatorio nunca reembaralha um CSV que ja foi ao avaliador (a chave antiga deixaria
    de casar as notas). Limite: o lote novo so tem as celulas que entraram depois, e o avaliador as
    corrige noutro momento; para comparar celulas na mesma sessao, apague os
    ``externas-avaliacao-*`` e gere tudo de novo antes de mandar.
    """
    arquivo_chave = pasta / "externas-avaliacao-chave.json"
    chave = json.loads(arquivo_chave.read_text(encoding="utf-8")) if arquivo_chave.exists() else {}
    vigente = {v["celula"]: v.get("assinaturaCelula") for v in chave.values() if not v.get("substituida")}
    mudou = False
    novas = []
    for c in celulas:
        atual = ext.assinatura_celula(c)
        if c.nome in vigente:
            if vigente[c.nome] in (None, atual):
                continue  # mesma celula (ou chave antiga, sem assinatura: fica como esta)
            for v in chave.values():
                if v["celula"] == c.nome:
                    v["substituida"] = True
            mudou = True
        if c.rodadas and not c.invalida:
            novas.append((c, atual))
    if novas:
        lote = max((v.get("lote", 1) for v in chave.values()), default=0) + 1
        cego, chave_txt = ext.csv_cego([c for c, _ in novas], canonico, inicio=len(chave) + 1)
        chave_nova = json.loads(chave_txt)
        if chave_nova:
            assinaturas = {c.nome: a for c, a in novas}
            for v in chave_nova.values():
                v["lote"] = lote
                v["assinaturaCelula"] = assinaturas[v["celula"]]
            nome = "externas-avaliacao-cego.csv" if lote == 1 else f"externas-avaliacao-cego-{lote}.csv"
            (pasta / nome).write_text(cego, encoding="utf-8")
            chave.update(chave_nova)
            mudou = True
    if mudou:
        # A chave liga a resposta a celula: fica com a equipe ate as notas voltarem (.gitignore).
        arquivo_chave.write_text(json.dumps(chave, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def acerto_geral(celula: Celula, ids: list[str], canonico: dict[str, dict],
                 adaptacoes: dict[str, dict], recorte: str) -> Faixa:
    """Pergunta certa (todos os criterios que se aplicam) nas perguntas ``ids``, rodada a rodada."""
    if not celula.rodadas:
        return Faixa(None, None, None, 0, disponivel=False)
    alvo, taxas, total = set(ids), [], 0
    for resultados in celula.rodadas:
        doRecorte = [r for r in resultados if r["id"] in alvo and r["id"] in canonico]
        if not doRecorte:
            continue
        certas = sum(acerto_pergunta(
            r, canonico[r["id"]],
            gab.criterios_penalizados(adaptacoes[r["id"]]) if r["id"] in adaptacoes else [],
            recorte) for r in doRecorte)
        taxas.append(100.0 * certas / len(doRecorte))
        total = len(doRecorte)
    if not taxas:
        return Faixa(None, None, None, 0)
    return Faixa(statistics.fmean(taxas), min(taxas), max(taxas), total)


def _secao_externas(celulas, ids, canonico) -> str:
    """Os criterios automaticos das externas, a pergunta 33 e o que falta conferir."""
    alvo = set(ids)
    perguntas = [canonico[i] for i in ids]
    a_conferir = [p["id"] for p in perguntas if p.get("aConferir")]
    pendentes = [f"{p['id']} ({p['pendenteContador']})" for p in perguntas if p.get("pendenteContador")]
    nao_revisadas = [p["id"] for p in perguntas if not gab.revisada(p)]
    # A declaracao so sai com toda inedita que a banca exige revisada (externas e de dados), pela
    # mesma regra da trava (gabarito.perguntas_sem_revisao: aConferir ou sem revisado.por).
    pendentes_revisao = gab.perguntas_sem_revisao(list(canonico.values()))
    linhas = ["## Perguntas geradas por IA: critérios automáticos (#358)\n",
              AVISO_IA + "\n",
              "Os 5 critérios do Murillo: **automáticos** aqui (rota está nas tabelas acima; "
              "fundamentação, alíquota/classe e precisão por heurística de texto, e o encaminhamento "
              "ao contador) e **humanos** no CSV cego `externas-avaliacao-cego.csv` e nos lotes seguintes "
              "(`externas-avaliacao-cego-2.csv`, …, quando houver: mande todos; contextualização, "
              "orientação prática e precisão final, 1 a 5, rubrica em `docs/avaliacao/rubrica-clareza.md`). "
              "**A heurística mede o que a frase diz, não o raciocínio**; a nota que vale para a precisão "
              "é a humana.\n",
              (f"**{DECLARACAO}** A nota de clareza do CSV cego é do orientador.\n" if not pendentes_revisao else
               f"**Revisão da equipe e do orientador pendente em {len(pendentes_revisao)} perguntas** (sem "
               "validação de contador, #387): até ela, as respostas esperadas são provisórias, conferidas "
               "só no texto da lei (normas.leg.br e base da VPS). A nota de clareza do CSV cego é do "
               "orientador.\n")]
    if a_conferir:
        sem = [p["id"] for p in perguntas if p.get("aConferir") and not p.get("dispositivosEsperados")]
        linhas.append(f"- Fundamentação **a conferir** em {len(a_conferir)} perguntas: os "
                      "`dispositivosEsperados` vêm do texto da lei (`fonteConferencia`), com a existência na base "
                      "conferida (`conferir-dispositivos-externas.sql`, no repositório cego), e esperam a revisão da "
                      "equipe ou do orientador (planilha do repositório cego). Leia a fundamentação delas como "
                      "provisória."
                      + (f" Sem dispositivo (n/d): {', '.join(sem)}." if sem else ""))
    if pendentes:
        linhas.append("- Pendentes (eram do contador, #284; sem contador, a equipe decide com o orientador; fora da "
                      "métrica de classe): " + ", ".join(pendentes) + ".")
    if nao_revisadas:
        linhas.append(f"- Classe e afirmações proibidas **provisórias** em {len(nao_revisadas)} perguntas "
                      "(ainda não revisadas pela equipe ou pelo orientador).")
    linhas.append("")
    linhas.append("| Célula | " + " | ".join(ext.CRITERIOS_EXTERNAS.values()) + " |\n|---|"
                  + "---|" * len(ext.CRITERIOS_EXTERNAS))
    for c in celulas:
        # Uma avaliacao por resposta, reusada nos quatro criterios.
        avaliadas = [[ext.avaliar(r, canonico[r["id"]]) for r in resultados
                      if r["id"] in alvo and not falha_tecnica(r)] for resultados in c.rodadas]
        casas = []
        for criterio in ext.CRITERIOS_EXTERNAS:
            taxas, total = [], 0
            for rodada_avaliada in avaliadas:
                valores = [a[criterio] for a in rodada_avaliada if a[criterio] is not None]
                if valores:
                    taxas.append(100.0 * sum(valores) / len(valores))
                    total = len(valores)
            casas.append(Faixa(statistics.fmean(taxas), min(taxas), max(taxas), total).formatada()
                         if taxas else "n/d")
        linhas.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | " + " | ".join(casas) + " |")
    especiais = [p for p in perguntas if p.get("observacao")]
    for p in especiais:
        linhas.append(f"\n### {p['id']} (pergunta {p.get('numeroOriginal', '?')} do Murillo)\n")
        linhas.append(f"*{_celula_md(p['pergunta'], 300)}*\n")
        linhas.append(f"**Atenção:** {p['observacao']}\n")
        linhas.append("| Célula | Rodada | Desfecho | Classe | Afirmação proibida? | Resposta |\n"
                      "|---|---|---|---|---|---|")
        for c in celulas:
            for numero, resultados in enumerate(c.rodadas, 1):
                for r in resultados:
                    if r["id"] != p["id"]:
                        continue
                    desfecho = (r.get("execucao") or {}).get("desfecho")
                    avaliacao = ext.avaliar(r, p)
                    criterio = avaliacao["proibidas"]
                    # Classe pela heuristica (#379): "—" quando nao se aplica ou esta pendente.
                    classe = ("—" if desfecho not in REDIGIDAS
                              else {True: "acertou", False: "**errou**"}.get(avaliacao["classe"], "—"))
                    # Frase fixa do backend (recusa, descarte, corte) nao afirma nada: sem credito,
                    # mas tambem nao e "afirmou o proibido".
                    proibida = ("— (frase fixa)" if desfecho not in REDIGIDAS
                                else "**sim**" if criterio is False else "não")
                    linhas.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | {numero} "
                                  f"| `{desfecho}` "
                                  f"| {classe} "
                                  f"| {proibida} "
                                  f"| {_celula_md((r.get('execucao') or {}).get('texto') or '', 240)} |")
    return "\n".join(linhas) + "\n"


def _secao_cortadas(celulas) -> str:
    """Taxa de ``RESPOSTA_CORTADA`` (#270) por celula, rodada e tipo de chamada, e a recusa limpa."""
    linhas = ["## Respostas cortadas pelo teto de tokens (`RESPOSTA_CORTADA`)\n",
              "Desfecho da Sessão 7 (#270): `done_reason=length` ou `eval_count ≥ teto`. É o número que "
              "separa \"o modelo errou\" de \"o teto cortou\". O tipo de chamada sai da rota e do "
              "desfecho de cada parte (o JSON não diz a chamada): classificação cortada aparece como "
              "FORA_DE_ESCOPO. A Ceres 1 (`2463c27`) não tem esse desfecho.\n",
              "| Célula | Por rodada | Classificação | Funções | Lei | Total |\n|---|---|---|---|---|---|"]
    for c in celulas:
        por_rodada, tipos, total = [], {"classificacao": 0, "funcoes": 0, "lei": 0}, 0
        cortadas = 0
        for resultados in c.rodadas:
            cortes = [ext.cortada(r) for r in resultados]
            n = sum(1 for corte in cortes if corte["qualquer"])
            por_rodada.append(f"{n}/{len(resultados)}")
            cortadas += n
            total += len(resultados)
            for corte in cortes:
                for tipo in tipos:
                    tipos[tipo] += 1 if corte[tipo] else 0
        linhas.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | {' · '.join(por_rodada) or 'n/d'} "
                      f"| {tipos['classificacao']} | {tipos['funcoes']} | {tipos['lei']} "
                      f"| {cortadas}/{total}"
                      + (f" ({_n(100.0 * cortadas / total)}%)" if total else "") + " |")
    linhas.append("\n### Recusa limpa\n")
    linhas.append("Nas perguntas que devem ser recusadas, o acerto com e **sem** as recusas que vieram "
                  "de `RESPOSTA_CORTADA`: um modelo pequeno cortado na classificação cai em "
                  "FORA_DE_ESCOPO e \"acerta\" a recusa por acidente.\n")
    linhas.append("| Célula | Recusa correta | Recusa limpa (sem as cortadas) | Recusas por corte |\n"
                  "|---|---|---|---|")
    for c in celulas:
        acertos = limpas = por_corte = aplicaveis = 0
        for resultados in c.rodadas:
            for r in resultados:
                if r.get("recusaCorreta") is None:
                    continue
                aplicaveis += 1
                if r["recusaCorreta"]:
                    acertos += 1
                    if ext.cortada(r)["qualquer"]:
                        por_corte += 1
                    else:
                        limpas += 1
        if not aplicaveis:
            linhas.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | — | — | — |")
            continue
        linhas.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | {_n(100.0 * acertos / aplicaveis)}% "
                      f"| {_n(100.0 * limpas / aplicaveis)}% | {por_corte} |")
    linhas.append("\nTodas as rodadas juntas (respostas = perguntas × rodadas).")
    return "\n".join(linhas) + "\n"


def _secao_parecidas(celulas, parecidas, ids_comum, canonico, adaptacoes_por_arq) -> str:
    """Acerto da capacidade comum com e sem as perguntas parecidas com exemplos que o modelo ve."""
    sem = [i for i in ids_comum if i not in set(parecidas)]
    linhas = ["## Com e sem as perguntas parecidas com o treino\n",
              f"{', '.join(parecidas)} têm Jaccard ≥ 0,5 com exemplos que o modelo vê (treino do "
              "classificador, prompt ou descrições `@Tool`; `similaridade-treino.md`). Se o acerto "
              "cai sem elas, parte do resultado é memória do exemplo, não compreensão.\n",
              "| Célula | Capacidade comum | Sem as parecidas | Diferença |\n|---|---|---|---|"]
    for c in celulas:
        adapt = adaptacoes_por_arq.get(c.arquitetura, {})
        com_f = acerto_geral(c, ids_comum, canonico, adapt, RECORTE_COMUM)
        sem_f = acerto_geral(c, sem, canonico, adapt, RECORTE_COMUM)
        linhas.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | {com_f.formatada()} | "
                      f"{sem_f.formatada()} | {_pontos(sem_f.media, com_f.media)} |")
    return "\n".join(linhas) + "\n"


def _secao_temas(celulas, canonico_json, canonico, ids_comum, adaptacoes_por_arq) -> str:
    """Contagem por tema (conjunto, capacidade comum, só rota) e acerto por tema × célula."""
    temas = canonico_json.get("temas") or {}
    ordem = list(temas) or sorted({p.get("tema") or "sem_tema" for p in canonico.values()})
    comum = set(ids_comum)
    linhas = ["## Por tema\n",
              "Quantas perguntas cada tema tem no conjunto e quantas ficaram na capacidade comum. "
              "**Leia a comparação antiga pela coluna da capacidade comum**, não pelo total. "
              "\"Só rota\" = a pergunta só mede a rota (o resto do gabarito não se aplica).\n",
              "| Tema | Conjunto | Capacidade comum | `so_ceres_2` | Só rota |\n|---|---|---|---|---|"]
    for tema in ordem:
        doTema = [p for p in canonico.values() if (p.get("tema") or "sem_tema") == tema]
        linhas.append(
            f"| {temas.get(tema, tema)} | {len(doTema)} | {sum(p['id'] in comum for p in doTema)} "
            f"| {sum(p.get('capacidade') == 'so_ceres_2' for p in doTema)} "
            f"| {sum(gab.so_rota(p) for p in doTema)} |")
    linhas.append(f"| **Total** | {len(canonico)} | {len(comum)} "
                  f"| {sum(p.get('capacidade') == 'so_ceres_2' for p in canonico.values())} "
                  f"| {sum(gab.so_rota(p) for p in canonico.values())} |\n")
    for recorte, ids, rotulo in ((RECORTE_COMUM, ids_comum, "capacidade comum"),
                                 (RECORTE_INTEIRO, list(canonico), "conjunto inteiro")):
        linhas.append(f"### Acerto por tema × célula — {rotulo}\n")
        linhas.append("Pergunta certa = todo critério que se aplica a ela acertou (rota, função, "
                      "parâmetros, número, citação, recusa, sem vazamento e sem falha técnica). "
                      "Média (mínimo–máximo) das rodadas.\n")
        linhas.append("| Tema | " + " | ".join(f"{c.rotulo_arquitetura} · `{c.modelo}`" for c in celulas)
                      + " |\n|---|" + "---|" * len(celulas))
        por_celula = [por_tema(c, ids, canonico, adaptacoes_por_arq.get(c.arquitetura, {}), recorte)
                      for c in celulas]
        for tema in ordem:
            casas = []
            for valores in por_celula:
                f = valores.get(tema)
                casas.append(f.formatada() if f else "—")
            linhas.append(f"| {temas.get(tema, tema)} | " + " | ".join(casas) + " |")
        linhas.append("")
    return "\n".join(linhas) + "\n"


def _tabela_modelo_arquitetura(celulas, modelos, arquiteturas, ids, canonico, adaptacoes, recorte):
    principais = ("rota", "funcao", "numero", "citacao", "recusa", "vazamento", "falha")
    linhas = ["| Modelo | Critério | " + " | ".join(r for _, r in arquiteturas)
              + (" | Diferença |" if len(arquiteturas) == 2 else " |"),
              "|---|---|" + "---|" * len(arquiteturas) + ("---|" if len(arquiteturas) == 2 else "")]
    for modelo in modelos:
        for criterio in principais:
            valores, medias = [], []
            for arq, _ in arquiteturas:
                c = next((c for c in celulas if c.modelo == modelo and c.arquitetura == arq), None)
                f = faixa(c, criterio, ids, canonico, adaptacoes.get(arq, {}), recorte) if c else None
                valores.append(f.formatada() if f else "não rodou")
                medias.append(f.media if f else None)
            dif = f" | {_pontos(medias[1], medias[0])} |" if len(arquiteturas) == 2 else " |"
            linhas.append(f"| `{modelo}` | {CRITERIOS[criterio][0]} | " + " | ".join(valores) + dif)
        lats = []
        for arq, _ in arquiteturas:
            c = next((c for c in celulas if c.modelo == modelo and c.arquitetura == arq), None)
            lats.append(latencias(c, ids, canonico).get("TODAS") if c else None)
        linhas.append(f"| `{modelo}` | Latência p50 | " + " | ".join(
            _s(l[0]) if l else "n/d" for l in lats) + (" | |" if len(arquiteturas) == 2 else " |"))
    return "\n".join(linhas) + "\n"
