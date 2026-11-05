"""Criterios automaticos das perguntas externas (#358) e o CSV cego da nota humana.

Os 5 criterios do Murillo para as 40 perguntas compiladas por ele:

- **automaticos** (aqui, sobre a resposta entregue, iguais para as duas arquiteturas):
  fundamentacao (dispositivo aceito citado; so depois de ``dispositivosAceitos`` conferidos na
  base), aliquota/classe de tratamento e precisao por heuristica (``classeTratamento`` e
  ``afirmacoesProibidas``) e encaminhamento ao contador;
- **humanos** (CSV cego para o avaliador externo da #275): contextualizacao, orientacao pratica e
  precisao final, de 1 a 5 (``docs/avaliacao/rubrica-clareza.md``).

Tudo aqui e **heuristica de texto**: acerta o que a frase diz, nao o raciocinio. Serve para
comparar celulas na mesma regua; a nota que vale para a precisao e a humana.
"""

from __future__ import annotations

import csv
import io
import json
import random
import re
import unicodedata

#: Classe de tratamento -> padrao que a resposta precisa ter para a classe estar dita.
_PADROES_CLASSE = {
    "ZERO": re.compile(r"al[ií]quota (zero|zerada)|reduzid[ao]s? a zero|redu[cç][aã]o de 100\s?%|"
                       r"\bisen[tç]", re.IGNORECASE),
    "REDUCAO_60": re.compile(r"\b60\s?%|sessenta por cento", re.IGNORECASE),
    "PADRAO": re.compile(r"al[ií]quota (padr[aã]o|cheia|integral|normal|de refer[eê]ncia)|"
                         r"sem (nenhuma |qualquer )?redu[cç][aã]o|n[aã]o (tem|ter[aá]|h[aá]|recebe|"
                         r"receber[aá]) (direito [aà] )?(redu[cç][aã]o|benef[ií]cio)", re.IGNORECASE),
}
_DEPENDE = re.compile(r"\bdepend|\bvaria\b|\bconforme\b|\bcaso a caso\b", re.IGNORECASE)
_CONTADOR = re.compile(r"\bcontador|\bcontabilista|\bescrit[oó]rio (de )?contab", re.IGNORECASE)
_DESFECHOS_CORTE = {"RESPOSTA_CORTADA"}

#: Criterios automaticos das externas: chave -> rotulo no relatorio.
CRITERIOS_EXTERNAS = {
    "fundamentacao": "Fundamentação (dispositivo aceito citado)",
    "classe": "Alíquota / classe de tratamento",
    "proibidas": "Sem afirmação proibida (precisão)",
    "encaminhamento": "Encaminha ao contador quando deve",
}


def _referencias(execucao: dict) -> list[str]:
    refs = []
    for c in execucao.get("citacoes") or []:
        norma, dispositivo = c.get("norma") or "", c.get("dispositivo") or ""
        refs.append(c.get("referencia") or f"{norma}, {dispositivo}".strip(", "))
    return refs


def cita_algum(referencias: list[str], aceitos: list[str]) -> bool:
    """Igual ao ``AvaliadorResposta.citaAlgum``: aceita paragrafo do artigo, nao o ``164-A``."""
    for ref in referencias:
        for esperado in aceitos:
            if ref == esperado or (ref.startswith(esperado) and len(ref) > len(esperado)
                                   and ref[len(esperado)] in ", )"):
                return True
    return False


def classe_dita(texto: str, classe: str) -> bool | None:
    """A resposta diz a classe esperada? ``None`` para classe sem padrao (``NAO_SE_APLICA``)."""
    if classe in _PADROES_CLASSE:
        return bool(_PADROES_CLASSE[classe].search(texto))
    if classe == "CONDICIONAL":
        ditas = sum(1 for p in _PADROES_CLASSE.values() if p.search(texto))
        return ditas >= 2 or bool(_DEPENDE.search(texto))
    if classe == "FORA_DO_ESCOPO_SISTEMA":
        return bool(_CONTADOR.search(texto))
    return None


def avaliar(resultado: dict, pergunta: dict) -> dict[str, bool | None]:
    """Criterio -> acertou? ``None`` quando nao se aplica (ou ainda nao da para medir)."""
    execucao = resultado.get("execucao") or {}
    texto = execucao.get("texto") or ""
    aceitos = pergunta.get("dispositivosAceitos") or []
    proibidas = pergunta.get("afirmacoesProibidas") or []
    return {
        "fundamentacao": (cita_algum(_referencias(execucao), aceitos)
                          and execucao.get("desfecho") not in ("CITACAO_INVALIDA", "NUMERO_SEM_FONTE",
                                                               "RESPOSTA_CORTADA"))
        if aceitos else None,
        # Pendente do contador (#284): sem valor esperado firme, fora da metrica de tratamento.
        "classe": None if pergunta.get("pendenteContador")
        else classe_dita(texto, pergunta.get("classeTratamento", "")),
        "proibidas": (not any(re.search(p, texto, re.IGNORECASE) for p in proibidas))
        if proibidas else None,
        "encaminhamento": bool(_CONTADOR.search(texto)) if pergunta.get("encaminharContador") else None,
    }


def cortada(resultado: dict) -> dict[str, bool]:
    """Onde a resposta foi cortada pelo teto de tokens (#270): classificacao, funcoes ou lei.

    O JSON do comando nao diz a chamada; sai da rota e do desfecho de cada parte. Classificacao
    cortada sai como FORA_DE_ESCOPO com o desfecho ``RESPOSTA_CORTADA``.
    """
    execucao = resultado.get("execucao") or {}
    desfecho = execucao.get("desfecho")
    rota = execucao.get("rota")
    lei = execucao.get("desfechoLegislacao") in _DESFECHOS_CORTE
    dados = execucao.get("desfechoDados") in _DESFECHOS_CORTE
    geral = desfecho in _DESFECHOS_CORTE
    return {
        "classificacao": geral and rota == "FORA_DE_ESCOPO",
        "funcoes": dados or (geral and rota == "DADOS"),
        "lei": lei or (geral and rota == "LEGISLACAO"),
        "qualquer": geral or lei or dados,
    }


def _normalizar(texto: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", texto) if unicodedata.category(c) != "Mn")


def csv_cego(celulas, canonico: dict[str, dict], aleatorio: random.Random | None = None):
    """CSV cego das externas para o avaliador externo (#275) e a chave, que nao vai a ele.

    Uma linha por (celula, pergunta) da 1a rodada, embaralhadas; colunas de nota 1 a 5 em branco
    para contextualizacao, orientacao pratica e precisao final.
    """
    aleatorio = aleatorio or random.SystemRandom()
    linhas = []
    for c in celulas:
        if not c.rodadas:
            continue
        for r in c.rodadas[0]:
            p = canonico.get(r["id"])
            if p is None or p.get("origem") != "inedita_externa":
                continue
            linhas.append({"celula": c.nome, "id": r["id"], "pergunta": p["pergunta"],
                           "resposta": (r.get("execucao") or {}).get("texto") or ""})
    aleatorio.shuffle(linhas)
    saida = io.StringIO()
    escritor = csv.writer(saida)
    escritor.writerow(["codigo", "pergunta", "resposta", "contextualizacao_1a5",
                       "orientacao_pratica_1a5", "precisao_final_1a5", "comentario"])
    chave = {}
    for i, linha in enumerate(linhas, 1):
        codigo = f"R{i:03d}"
        escritor.writerow([codigo, linha["pergunta"], linha["resposta"], "", "", "", ""])
        chave[codigo] = {"celula": linha["celula"], "id": linha["id"]}
    return saida.getvalue(), json.dumps(chave, ensure_ascii=False, indent=2) + "\n"
