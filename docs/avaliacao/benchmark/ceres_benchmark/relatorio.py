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

from . import gabarito as gab
from . import graficos

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
    "vazamento": ("Sem vazamento", "vazamento", True),
    "beco": ("Sem beco sem saída", "semBecoSemSaida", False),
    "falha": ("Sem falha técnica", None, False),
}

RECORTE_COMUM = "comum"
RECORTE_INTEIRO = "inteiro"

#: Criterios de viabilidade da "Conclusao para a banca" (README do benchmark).
LIMIAR_SEM_FALHA = 95.0
CRITERIOS_VIABILIDADE = ("rota", "numero", "citacao", "recusa")

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
    resultado: dict, criterio: str, pergunta: dict, adaptada: bool, recorte: str
) -> tuple[bool | None, bool, bool]:
    """(acertou?, disponivel na regua?, penalizada por capacidade?) de um resultado.

    No recorte inteiro, criterio que a adaptacao tirou da pergunta (a funcao nao existia na
    arquitetura) e que o gabarito canonico pede conta como **erro por capacidade**.
    """
    if criterio == "falha":
        return (not falha_tecnica(resultado)), True, False
    _, chave, inverte = CRITERIOS[criterio]
    if chave not in resultado:
        return None, False, False
    valor = resultado[chave]
    if (
        valor is None
        and recorte == RECORTE_INTEIRO
        and adaptada
        and criterio in gab.CRITERIOS_ADAPTAVEIS
        and gab.aplicavel(pergunta, criterio)
    ):
        return False, True, True
    if valor is None:
        return None, True, False
    return (not valor) if inverte else bool(valor), True, False


def faixa(
    celula: Celula, criterio: str, ids: list[str], canonico: dict[str, dict],
    adaptacoes: dict[str, dict], recorte: str
) -> Faixa:
    """Media e faixa de um criterio nas perguntas ``ids``, rodada a rodada."""
    if not celula.rodadas:
        return Faixa(None, None, None, 0, disponivel=False)
    alvo = set(ids)
    taxas, total, penalizadas, disponivel = [], 0, 0, True
    for resultados in celula.rodadas:
        acertos = aplicaveis = pen = 0
        for r in resultados:
            if r["id"] not in alvo or r["id"] not in canonico:
                continue
            valor, disp, penal = valor_criterio(
                r, criterio, canonico[r["id"]], r["id"] in adaptacoes, recorte)
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


def instaveis(celula: Celula, ids: list[str]) -> list[str]:
    """Perguntas que mudaram de resultado entre rodadas em algum criterio."""
    if len(celula.rodadas) < 2:
        return []
    alvo, saida = set(ids), []
    chaves = [c[1] for c in CRITERIOS.values() if c[1]]
    por_id: dict[str, list[tuple]] = {}
    for resultados in celula.rodadas:
        for r in resultados:
            if r["id"] in alvo:
                por_id.setdefault(r["id"], []).append(
                    tuple(r.get(k) for k in chaves) + (falha_tecnica(r),))
    for id_, valores in por_id.items():
        if len(set(valores)) > 1:
            saida.append(id_)
    return saida


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
        if any(gab.normalizar(t) and gab.normalizar(t) in normal
               for t in pergunta.get("textosProibidos") or []):
            categorias.append("instrucao_vazada")
        else:
            categorias.append("vazamento_vizinho")
    return categorias


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
    ref = next((c for c in celulas_arq if c.modelo == referencia and c.rodadas), None)
    saida = []
    for c in celulas_arq:
        if not c.rodadas:
            saida.append(Viabilidade(c.modelo, False, ["a célula não rodou até o fim"]))
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
        "Montada pelo gerador a partir dos números acima (recorte comum), com os critérios de "
        "viabilidade do README: sem vazamento em nenhuma rodada, sem falha técnica em ao menos "
        f"{LIMIAR_SEM_FALHA:.0f}% e, em rota, número, citação e recusa, média não abaixo do mínimo "
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
        if ref1 and ref2 and ref1.rodadas and ref2.rodadas:
            partes = []
            for criterio in ("rota", "numero", "citacao", "recusa"):
                a = faixa(ref2, criterio, ids_comum, canonico, adaptacoes, RECORTE_COMUM).media
                b = faixa(ref1, criterio, ids_comum, canonico, adaptacoes, RECORTE_COMUM).media
                partes.append(f"{CRITERIOS[criterio][0].lower()} {_pontos(a, b)}")
            lat1 = latencias(ref1, ids_comum, canonico).get("TODAS", (None,))[0]
            lat2 = latencias(ref2, ids_comum, canonico).get("TODAS", (None,))[0]
            linhas.append(
                f"**Efeito da arquitetura no `{referencia}`** ({rot2} menos {rot1}): "
                + ", ".join(partes) + f"; latência p50 {_s(lat1)} → {_s(lat2)}.")
            linhas.append("")
        novos = [m for m in viaveis_por_arq.get(arq2, []) if m not in viaveis_por_arq.get(arq1, [])]
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
        else:
            linhas.append(
                f"Os modelos menores viáveis na {rot2} já eram viáveis na {rot1}: a escolha do "
                f"`{referencia}` não se explica só pela qualidade — explique pela margem.")
    return "\n".join(linhas) + "\n"


# --------------------------------------------------------------------------- markdown


def _celula_md(texto: str, limite: int = 420) -> str:
    texto = " ".join(texto.split())
    if len(texto) > limite:
        texto = texto[: limite - 1] + "…"
    return texto.replace("|", "\\|")


AVISO_FICTICIO = (
    "> ⚠️ **DADOS FICTÍCIOS DE TESTE.** Este relatório foi gerado a partir de JSONs inventados "
    "pelo `gerar_exemplo_ficticio.py`, só para mostrar o formato. **Nenhum número aqui foi medido.** "
    "Não use em slide, artigo nem banca.\n"
)


def gerar(pasta: Path, graficos_dir: Path | None = None) -> str:
    """Gera ``relatorio.md`` e os gráficos SVG na pasta da rodada; devolve o Markdown."""
    rodada = _ler_json(pasta / "rodada.json")
    canonico_json = _ler_json(pasta / rodada["gabaritoCanonico"])
    antigo_json = _ler_json(pasta / rodada["gabaritoAntigo"])
    canonico = {p["id"]: p for p in canonico_json["perguntas"]}
    arquiteturas = [(a["nome"], a["rotulo"]) for a in rodada["arquiteturas"]]
    adaptacoes_por_arq = {
        a["nome"]: (_ler_json(pasta / a["adaptacao"]) if a.get("adaptacao") else {})
        for a in rodada["arquiteturas"]
    }
    adaptacoes_todas: dict[str, dict] = {}
    for adapt in adaptacoes_por_arq.values():
        adaptacoes_todas.update(adapt)
    ids_comum = gab.recorte_comum(canonico_json, antigo_json, adaptacoes_todas)
    ids_inteiro = list(canonico)
    referencia = rodada.get("modeloReferencia", "qwen3:4b-instruct")
    celulas = carregar_celulas(pasta)
    # So entre perguntas nao adaptadas: a adaptada ja saiu do recorte, sem valor na Ceres 1.
    divergentes = {i: v for i, v in valores_divergentes(celulas).items() if i in ids_comum}
    ids_comum = [i for i in ids_comum if i not in divergentes]
    ficticio = any(c.ficticia for c in celulas)
    modelos = list(dict.fromkeys(c.modelo for c in celulas))

    md: list[str] = []
    titulo = "# Benchmark da Ceres — 2 arquiteturas × " + str(len(modelos)) + " modelos"
    md.append(titulo + (" (DADOS FICTÍCIOS)" if ficticio else "") + "\n")
    if ficticio:
        md.append(AVISO_FICTICIO)
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
    md.append(f"| Gabarito | {canonico_json['versao']} ({len(ids_inteiro)} perguntas), o da "
              f"{arquiteturas[-1][1]} nas duas arquiteturas |")
    md.append(f"| Recorte comum | {len(ids_comum)} perguntas: presentes no gabarito do "
              f"`{rodada['arquiteturas'][0]['sha'][:7]}` com o mesmo texto, sem adaptação e com o "
              "mesmo valor esperado nas duas |")
    md.append(f"| Repetições | {rodada.get('repeticoes', '?')} |")
    md.append(f"| Embedding | {rodada.get('embedding', 'bge-m3')} |")
    md.append("")
    md.append("Todas as células rodaram **na mesma máquina**, uma por vez. A latência é desta "
              "máquina, não da VPS: a de produção é medida à parte pelo harness da API "
              "(`docs/avaliacao/harness-api/`), só na configuração de produção.\n")

    if divergentes:
        md.append("Fora do recorte comum por valor esperado diferente entre os seeds dos dois "
                  "commits: " + ", ".join(
                      f"{i} ({' × '.join(sorted({'/'.join(v) or 'vazio' for v in d.values()}))})"
                      for i, d in divergentes.items()) + ".\n")
    md.append("### Pensamento (thinking) e memória por célula\n")
    md.append("| Célula | Pensamento | Memória do modelo (Ollama `/api/ps`) | Pico do processo do "
              "Ollama | Duração | Saída |\n|---|---|---|---|---|---|")
    for c in celulas:
        pensamento = c.manifesto.get("pensamento") or {}
        mem = c.manifesto.get("memoria") or {}
        md.append(
            f"| {c.rotulo_arquitetura} · `{c.modelo}` | {_celula_md(pensamento.get('resumo', 'n/d'), 160)} "
            f"| {_gb(mem.get('modeloBytes'))} | {_gb(mem.get('processoPicoBytes'))} "
            f"| {c.manifesto.get('duracaoSegundos', 'n/d')} s | {c.manifesto.get('codigoSaida', 'n/d')} |")
    md.append("")
    md.append("A VPS de produção tem 24 GB divididos com Postgres, backend, ClamAV e o BGE-M3: a "
              "coluna do pico inclui o BGE-M3 carregado junto.\n")

    for recorte, ids, rotulo in (
        (RECORTE_COMUM, ids_comum, "(a) Recorte comum — a comparação justa"),
        (RECORTE_INTEIRO, ids_inteiro, "(b) Gabarito inteiro"),
    ):
        md.append(f"## {rotulo}\n")
        if recorte == RECORTE_INTEIRO:
            n_adapt = len(adaptacoes_todas)
            md.append(f"Inclui as {n_adapt} perguntas adaptadas para a Ceres 1 (função que ela não "
                      "tinha). Nelas, função, parâmetros e número que o gabarito pede e a Ceres 1 "
                      "não tinha como entregar contam como **erro por capacidade da arquitetura, "
                      "não do modelo** (coluna \"pen.\").\n")
        md.append("| Célula | " + " | ".join(r for r, _, _ in CRITERIOS.values()) + " |")
        md.append("|---|" + "---|" * len(CRITERIOS))
        for c in celulas:
            colunas = []
            for criterio in CRITERIOS:
                f = faixa(c, criterio, ids, canonico, adaptacoes_todas, recorte)
                texto = f.formatada()
                if f.penalizadas:
                    texto += f" (pen. {f.penalizadas})"
                colunas.append(texto)
            md.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | " + " | ".join(colunas) + " |")
        md.append("")
        md.append("### Tabela modelo × arquitetura\n")
        md.append(_tabela_modelo_arquitetura(celulas, modelos, arquiteturas, ids, canonico,
                                             adaptacoes_todas, recorte))
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
            lista = instaveis(c, ids)
            md.append(f"- {c.rotulo_arquitetura} · `{c.modelo}`: "
                      + (f"{len(lista)} — " + ", ".join(lista) if lista else "nenhuma"))
        md.append("")

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

    md.append("## Quem decidiu a rota (Ceres 2.0, #265)\n")
    md.append("| Célula | " + " | ".join(["ATALHO_FIXO", "FILTRO_INJECAO", "REGRA", "EMBEDDING", "LLM"])
              + " |\n|---|---|---|---|---|---|")
    for c in celulas:
        contagem = camadas(c)
        if not contagem:
            continue
        total = sum(contagem.values())
        md.append(f"| {c.rotulo_arquitetura} · `{c.modelo}` | " + " | ".join(
            f"{100.0 * contagem.get(k, 0) / total:.0f}%" for k in
            ["ATALHO_FIXO", "FILTRO_INJECAO", "REGRA", "EMBEDDING", "LLM"]) + " |")
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

    md.append("## Conclusão para a banca\n")
    if ficticio:
        md.append("> ⚠️ Conclusão sobre **dados fictícios**: mostra só o formato.\n")
    md.append(conclusao(celulas, arquiteturas, referencia, ids_comum, canonico, adaptacoes_todas))

    md.append("## Gráficos\n")
    destino = graficos_dir or (pasta / "graficos")
    arquivos = graficos.gerar_todos(destino, celulas, modelos, arquiteturas, ids_comum, canonico,
                                    adaptacoes_todas, ficticio)
    for arquivo in arquivos:
        md.append(f"![{arquivo.stem}](graficos/{arquivo.name})")
    md.append("")
    texto = "\n".join(md)
    (pasta / "relatorio.md").write_text(texto, encoding="utf-8")
    return texto


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
                f = faixa(c, criterio, ids, canonico, adaptacoes, recorte) if c else None
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
