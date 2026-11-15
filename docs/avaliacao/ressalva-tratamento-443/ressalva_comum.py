"""Constantes da regra, detectores e classificação das execuções da medição da #443.

Só biblioteca padrão. As margens são as da regra (`regra-de-decisao.md`): mudou uma, mude a outra e a
`VERSAO_REGRA`.
"""

import hashlib
import json
import re
import unicodedata
from pathlib import Path

DIR = Path(__file__).resolve().parent
CONJUNTO = DIR / "perguntas-ressalva-443.json"
VERSAO_CONJUNTO = "2026-11-15.v1"
VERSAO_REGRA = "2026-11-15.1"

REPETICOES = 3
FUNCAO_TRATAMENTO = "consultarTratamentoProduto"

# Régua de ruído (docs/avaliacao/resultados/2026-11-15-comparacao-haiku-2bfb1ef3-main-fc58fa83.md, linha 51):
# efeito real = id que muda nas 3 repetições, ou uma mudança de uns 5 acertos na soma das repetições.
EFEITO_SOMA = 5
# Portão de reprodução: o braço de base precisa falhar em pelo menos 15 das 90 execuções do estrato A.
MINIMO_FALHAS_DA_BASE = 15
# Controle "qualificado": a base acerta em pelo menos 2 das 3 repetições; por grupo, no mínimo 8 de 12.
MINIMO_ACERTOS_DO_CONTROLE = 2
MINIMO_CONTROLES_QUALIFICADOS = 8
# Falha técnica (HTTP diferente de 200 depois da repetição): acima de 5% das execuções de um braço,
# a medição é inconclusiva.
TETO_FALHAS_TECNICAS = 0.05
# Alerta (não bloqueia): estrato B perde pelo menos 3 acertos; controle perde 2 ou mais repetições.
ALERTA_ESTRATO_B = 3
ALERTA_CONTROLE = 2

_ACENTOS = re.compile(r"[̀-ͯ]")


def normalizar(texto):
    """Minúsculas, sem acento, espaços colapsados."""
    sem_acento = _ACENTOS.sub("", unicodedata.normalize("NFD", texto or ""))
    return re.sub(r"\s+", " ", sem_acento.lower()).strip()


# --- detectores (sobre o texto normalizado) -----------------------------------------------------

_CONTADOR = re.compile(r"\b(contador|contadora|contabilista|escritorio de contabilidade)\b")
_ACAO_DA_RESSALVA = re.compile(
    r"confir|enquadr|consult|valid|verific|orient|atende|condicao|procure|pergunt|fale com"
    r"|converse|decid|defin")
_FRASE = re.compile(r"[.!?;\n]+")

# Textos fixos de recusa da rota de lei: citam o contador, mas não são ressalva de enquadramento.
_RECUSAS = (
    "nao consegui localizar a base legal",
    "nao consegui confirmar uma resposta",
    "nao encontrei fundamento legal",
)

_TRATAMENTO = re.compile(
    r"\d+(?:,\d+)?\s*%|\bpor cento\b|aliquota zero|aliquota cheia|aliquota integral"
    r"|sem reducao|\bzerad[ao]s?\b")

_CONTAMINACAO_DE_TABELA = re.compile(r"cclasstrib|tabela oficial de classificacao")


def e_recusa(resposta):
    texto = normalizar(resposta)
    return any(marca in texto for marca in _RECUSAS)


def tem_ressalva(resposta):
    """Alguma frase manda confirmar o enquadramento, a condição ou a venda com o contador."""
    if e_recusa(resposta):
        return False
    for frase in _FRASE.split(normalizar(resposta)):
        if _CONTADOR.search(frase) and _ACAO_DA_RESSALVA.search(frase):
            return True
    return False


def tem_tratamento(resposta):
    """A resposta traz o tratamento do produto: percentual, alíquota zero, cheia ou sem redução."""
    if e_recusa(resposta):
        return False
    return bool(_TRATAMENTO.search(normalizar(resposta)))


def contaminada_por_tabela(resposta):
    return bool(_CONTAMINACAO_DE_TABELA.search(normalizar(resposta)))


# --- classificação de uma execução --------------------------------------------------------------

OK = "OK"
DEFEITO = "DEFEITO"            # trata o produto sem a ressalva (o defeito da #443)
SEM_TRATAMENTO = "SEM_TRATAMENTO"  # recusa, sem número, fora de escopo
FALHA_TECNICA = "FALHA_TECNICA"


def _funcoes(execucao):
    return {f for f in (execucao.get("fontes") or []) if f}


def classificar_defeito(execucao):
    if execucao.get("status") != 200:
        return FALHA_TECNICA
    resposta = execucao.get("resposta") or ""
    if not tem_tratamento(resposta):
        return SEM_TRATAMENTO
    return OK if tem_ressalva(resposta) else DEFEITO


def acertou_controle_de_lei(execucao, esperado):
    if execucao.get("status") != 200:
        return False
    resposta = execucao.get("resposta") or ""
    normas = {c.get("norma") for c in (execucao.get("citacoes") or [])}
    return (execucao.get("rota") == esperado["rota"]
            and bool(normas & set(esperado["normasAceitas"]))
            and not e_recusa(resposta)
            and not contaminada_por_tabela(resposta))


def acertou_controle_de_dados(execucao, esperado):
    if execucao.get("status") != 200 or execucao.get("rota") != esperado["rota"]:
        return False
    resposta = execucao.get("resposta") or ""
    funcoes = _funcoes(execucao)
    if "funcao" in esperado:  # tratamento de produto, que já traz a ressalva
        return (esperado["funcao"] in funcoes and tem_tratamento(resposta)
                and tem_ressalva(resposta))
    return (bool(funcoes) and esperado["semFuncao"] not in funcoes
            and not tem_ressalva(resposta) and not contaminada_por_tabela(resposta))


def acertou(pergunta, execucao):
    """Verdadeiro/falso por execução, para o grupo da pergunta."""
    if pergunta["grupo"] == "DEFEITO":
        return classificar_defeito(execucao) == OK
    if pergunta["grupo"] == "CONTROLE_LEI":
        return acertou_controle_de_lei(execucao, pergunta["esperado"])
    return acertou_controle_de_dados(execucao, pergunta["esperado"])


# --- conjunto -----------------------------------------------------------------------------------

def sha256_arquivo(caminho):
    return hashlib.sha256(Path(caminho).read_bytes()).hexdigest()


def hash_registrado(caminho):
    return (Path(str(caminho) + ".sha256").read_text(encoding="utf-8").split()[0]).lower()


def carregar_conjunto(caminho=CONJUNTO):
    """Carrega o conjunto e recusa o que não bate com o sha256 registrado."""
    if sha256_arquivo(caminho) != hash_registrado(caminho):
        raise SystemExit("O conjunto não bate com o sha256 registrado: subiu a versão? grave o novo hash.")
    return json.loads(Path(caminho).read_text(encoding="utf-8"))
