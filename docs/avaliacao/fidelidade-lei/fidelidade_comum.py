"""Peças comuns da medição de fidelidade da rota de lei da Ceres (#269).

Só biblioteca padrão. Aqui ficam o carregamento do conjunto (com a conferência do sha256), a
classificação de uma execução pela auditoria, as constantes da regra de decisão e as funções
estatísticas simples. Os três scripts (executor, leitura e decisão) importam deste módulo.
"""

import hashlib
import json
import math
import re
from pathlib import Path

DIR = Path(__file__).resolve().parent
CONJUNTO = DIR / "perguntas-fidelidade-lei.json"
#: Segundo conjunto (v2): escrito depois da primeira rodada, por sessão que não viu o v1. A regra e
#: os scripts são os mesmos; o conjunto tem versão e sha256 próprios (regra, seção 9).
CONJUNTO_V2 = DIR / "perguntas-fidelidade-lei-v2.json"
VERSAO_CONJUNTO_V2 = "2026-11-13.v2"
CONJUNTOS = {"v1": CONJUNTO, "v2": CONJUNTO_V2}
VERSAO_REGRA = "2026-11-12.4"

#: O braço de referência: todo braço comparado é medido contra ele (regra, seção 1).
BASE = "desligada"
#: Os dois braços do desenho padrão; a rodada pode trazer outros (manifesto `bracos`).
BRACOS = ("desligada", "ligada")

CLASSES = ("CAPUT", "PARAGRAFO", "EXCECAO", "AMPLA", "AUSENTE", "SIMPLES")

# --- Classificação de uma execução (regra, seção 3) --------------------------------------------

#: Desfechos de auditoria que contam como falha técnica (regra, seção 2).
DESFECHOS_FALHA_TECNICA = frozenset(
    {"MODELO_INDISPONIVEL", "MODO_FALLBACK", "TEMPO_ESGOTADO", "CANCELADA", "ERRO"})

#: Desfechos de recusa. Desfecho novo da correção conta como descartada, salvo decisão registrada
#: antes da rodada (e então a versão da regra sobe). Regra, seção 3.
DESFECHOS_RECUSA = frozenset({"SEM_FUNDAMENTO_LEGAL"})
ROTAS_RECUSA = frozenset({"FORA_DE_ESCOPO"})

ROTA_LEI = "LEGISLACAO"
DESFECHO_ACEITA = "RESPONDIDA"

# --- Leitura dos juízes (regra, seção 4) -------------------------------------------------------

ROTULOS_FRASE = ("SUPORTADA", "CITACAO_ERRADA", "ALEM_DO_TRECHO", "CONTRARIO", "OUTRA_RESSALVA",
                 "NAO_JURIDICA")
TIPOS_RESPOSTA = ("AFIRMA", "RECUSA")
VEREDITOS = ("FIEL", "COM_RESSALVA", "INFIEL")
SUBTIPOS = {"FIEL": (None,), "COM_RESSALVA": ("CITACAO", "OUTRA"),
            "INFIEL": ("ALEM_DO_TRECHO", "CONTRARIO")}
JUIZES = {"J1": "opus", "J2": "sonnet", "J3": "opus"}

# --- Margens e limiares (regra, seção 7.1). Mudar um valor aqui sem subir a versão da regra
# invalida a rodada: o teste `test_regra` compara com o documento. -------------------------------

REGRA = {
    "defeitosMinimosNaBase": 8,
    "melhoraRelativaMinima": 0.25,
    "melhoraAbsolutaMinima": 4,
    "icConfianca": 0.80,
    "bootstrapReamostragens": 10_000,
    "bootstrapSemente": 20261112,
    "pioraMaximaPorTipo": 1,
    "falsoDescarteMaximo": 3,
    "recusaFalsaAltaMaxima": 0.05,
    "ausenteInfielAltaMaxima": 1,
    "p50Fator": 2.0,
    "p95Fator": 2.0,
    "p95TetoMs": 10_000,
    "tokensFator": 2.0,
    "regressoesPorPerguntaMaximas": 1,
    "falhaTecnicaMaxima": 0.10,
    "kappaMinimo": 0.40,
    "kappaAviso": 0.60,
    "desacordoMaximo": 0.30,
    "minimoDeExecucoesValidasPorBraco": 2,
}


class ErroDeConjunto(Exception):
    """O conjunto não bate com o sha256 versionado ou está malformado."""


def sha256_bytes(dados: bytes) -> str:
    return hashlib.sha256(dados).hexdigest()


def sha256_arquivo(caminho: Path) -> str:
    return sha256_bytes(Path(caminho).read_bytes())


def hash_registrado(caminho: Path) -> str:
    """Primeiro campo do arquivo `.sha256` ao lado do conjunto (formato do `sha256sum`)."""
    arquivo = Path(str(caminho) + ".sha256")
    texto = arquivo.read_text(encoding="utf-8").strip()
    return texto.split()[0].lower()


def carregar_conjunto(caminho: Path = CONJUNTO, verificar_hash: bool = True) -> dict:
    caminho = Path(caminho)
    if verificar_hash:
        esperado, real = hash_registrado(caminho), sha256_arquivo(caminho)
        if esperado != real:
            raise ErroDeConjunto(
                f"sha256 do conjunto difere do registrado ({real[:12]}… x {esperado[:12]}…): "
                "o conjunto mudou depois de congelado; suba a versão e registre o novo hash")
    conjunto = json.loads(caminho.read_text(encoding="utf-8"))
    ids = [p["id"] for p in conjunto["perguntas"]]
    if len(ids) != len(set(ids)):
        raise ErroDeConjunto("ids repetidos no conjunto")
    return conjunto


def resolver_conjunto(valor) -> Path:
    """Caminho do conjunto pedido: `v1`, `v2`, o nome de um arquivo da pasta ou um caminho.
    Sem valor, o v1 (o comportamento de antes do v2)."""
    if not valor:
        return CONJUNTO
    if valor in CONJUNTOS:
        return CONJUNTOS[valor]
    caminho = Path(valor)
    if not caminho.is_absolute() and (DIR / valor).exists():
        caminho = DIR / valor  # nome de um arquivo da pasta, qualquer que seja o diretório atual
    return caminho


def conjunto_do_manifesto(manifesto: dict) -> Path:
    """Arquivo do conjunto usado na rodada (campo `conjunto.arquivo`; manifesto de antes do v2 = v1)."""
    return resolver_conjunto((manifesto.get("conjunto") or {}).get("arquivo"))


def perguntas_por_id(conjunto: dict) -> dict:
    return {p["id"]: p for p in conjunto["perguntas"]}


# --- Arquivos de linhas -------------------------------------------------------------------------

def ler_jsonl(caminho: Path) -> list:
    linhas = []
    with open(caminho, encoding="utf-8") as arquivo:
        for linha in arquivo:
            linha = linha.strip()
            if linha:
                linhas.append(json.loads(linha))
    return linhas


def gravar_jsonl(caminho: Path, registros: list) -> None:
    with open(caminho, "w", encoding="utf-8") as arquivo:
        for r in registros:
            arquivo.write(json.dumps(r, ensure_ascii=False) + "\n")


def anexar_jsonl(caminho: Path, registro: dict) -> None:
    with open(caminho, "a", encoding="utf-8") as arquivo:
        arquivo.write(json.dumps(registro, ensure_ascii=False) + "\n")


# --- Classificação de uma execução --------------------------------------------------------------

def desfecho_da_auditoria(registro: dict):
    auditoria = registro.get("auditoria") or {}
    return auditoria.get("desfecho")


def classificar_execucao(registro: dict) -> tuple:
    """(classe, motivo): `falha`, `aceita`, `recusa` ou `descartada` (regra, seções 2 e 3)."""
    if registro.get("status") != 200:
        return "falha", f"HTTP {registro.get('status')} {registro.get('codigo') or ''}".strip()
    if registro.get("modoFallback"):
        return "falha", "modoFallback"
    auditoria = registro.get("auditoria")
    if not auditoria:
        return "falha", "sem linha de auditoria"
    desfecho = auditoria.get("desfecho")
    if desfecho in DESFECHOS_FALHA_TECNICA:
        return "falha", f"desfecho {desfecho}"
    rota = registro.get("rota") or auditoria.get("rota")
    if rota == ROTA_LEI and desfecho == DESFECHO_ACEITA:
        return "aceita", desfecho
    if desfecho in DESFECHOS_RECUSA or rota in ROTAS_RECUSA:
        return "recusa", desfecho or rota
    return "descartada", f"{rota}/{desfecho}"


def tokens_da_auditoria(auditoria) -> int:
    """Soma de todos os campos `tokens_entrada*` e `tokens_saida*` (inteiros) da linha de
    auditoria: chamada que a correção acrescentar entra, se gravar nesses campos."""
    if not auditoria:
        return 0
    total = 0
    for chave, valor in auditoria.items():
        if re.match(r"^tokens_(entrada|saida)", chave) and isinstance(valor, int) \
                and not isinstance(valor, bool):
            total += valor
    return total


# --- Estatística --------------------------------------------------------------------------------

def percentil(valores: list, p: int):
    """Vizinho mais próximo (nearest-rank), igual ao relatório Java da avaliação."""
    if not valores:
        return None
    ordenados = sorted(valores)
    posicao = max(1, math.ceil(p * len(ordenados) / 100))
    return ordenados[posicao - 1]


def media(valores: list):
    return sum(valores) / len(valores) if valores else None


def kappa_de_cohen(pares: list) -> tuple:
    """(concordância simples, kappa) de uma lista de pares de rótulos. Sem pares: (None, None)."""
    if not pares:
        return None, None
    n = len(pares)
    concordam = sum(1 for a, b in pares if a == b)
    po = concordam / n
    rotulos = {r for par in pares for r in par}
    pe = 0.0
    for r in rotulos:
        pa = sum(1 for a, _ in pares if a == r) / n
        pb = sum(1 for _, b in pares if b == r) / n
        pe += pa * pb
    if pe >= 1.0:
        return po, 1.0 if po == 1.0 else 0.0
    return po, (po - pe) / (1 - pe)


def quantil(valores: list, q: float) -> float:
    ordenados = sorted(valores)
    if not ordenados:
        raise ValueError("sem valores")
    posicao = q * (len(ordenados) - 1)
    baixo, alto = math.floor(posicao), math.ceil(posicao)
    if baixo == alto:
        return ordenados[int(posicao)]
    return ordenados[baixo] + (ordenados[alto] - ordenados[baixo]) * (posicao - baixo)


# --- Veredito de uma leitura e consenso dos juízes (regra, seção 4.2 e 4.3) ---------------------

ORDEM_VEREDITO = ("RECUSA", "FIEL", "COM_RESSALVA", "INFIEL")


def veredito_de_leitura(leitura: dict) -> tuple:
    """(veredito, subtipo) de uma leitura de juiz, calculado só pelos rótulos das frases:
    `RECUSA` quando o juiz leu o texto como recusa; senão `INFIEL`, `COM_RESSALVA` ou `FIEL`."""
    if leitura.get("tipoResposta") == "RECUSA":
        return "RECUSA", None
    rotulos = {f.get("rotulo") for f in leitura.get("frases", [])}
    if "CONTRARIO" in rotulos:
        return "INFIEL", "CONTRARIO"
    if "ALEM_DO_TRECHO" in rotulos:
        return "INFIEL", "ALEM_DO_TRECHO"
    if "CITACAO_ERRADA" in rotulos:
        return "COM_RESSALVA", "CITACAO"
    if "OUTRA_RESSALVA" in rotulos:
        return "COM_RESSALVA", "OUTRA"
    return "FIEL", None


def _prioridade_subtipo(subtipo):
    ordem = ("CONTRARIO", "ALEM_DO_TRECHO", "CITACAO", "OUTRA")
    return ordem.index(subtipo) if subtipo in ordem else len(ordem)


def consenso(leituras: list) -> tuple:
    """(veredito, subtipo, concordaram) de uma lista de [(veredito, subtipo)] de 2 ou 3 juízes.
    Maioria; se os três divergem, o do meio na ordem RECUSA < FIEL < COM_RESSALVA < INFIEL; o
    subtipo é o dos juízes da maioria (o contrário prevalece sobre além do trecho, e a citação
    sobre outra). `concordaram` = os dois primeiros juízes deram o mesmo veredito."""
    vereditos = [v for v, _ in leituras]
    concordaram = len(leituras) >= 2 and vereditos[0] == vereditos[1]
    contagem = {v: vereditos.count(v) for v in set(vereditos)}
    mais = max(contagem.values())
    candidatos = [v for v, n in contagem.items() if n == mais]
    if len(candidatos) == 1:
        final = candidatos[0]
    else:
        ordenados = sorted(candidatos, key=ORDEM_VEREDITO.index)
        final = ordenados[len(ordenados) // 2]
    subtipos = [s for v, s in leituras if v == final and s]
    subtipo = min(subtipos, key=_prioridade_subtipo) if subtipos else None
    return final, subtipo, concordaram
