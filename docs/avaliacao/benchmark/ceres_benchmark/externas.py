"""Criterios automaticos das perguntas externas (#358) e o CSV cego da nota humana.

Os 5 criterios do Murillo para as 40 perguntas compiladas por ele:

- **automaticos** (aqui, sobre a resposta entregue, iguais para as duas arquiteturas):
  fundamentacao (dispositivo aceito citado; so depois de ``dispositivosEsperados`` conferidos na
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

#: Classe de tratamento -> padrao que a resposta precisa ter para a classe estar dita.
_PADROES_CLASSE = {
    "ZERO": re.compile(r"al[ií]quota (zero|zerada)|reduzid[ao]s? a zero|redu[cç][aã]o de 100\s?%|"
                       r"\bisen[tç]", re.IGNORECASE),
    "REDUCAO_60": re.compile(r"\b60\s?%|sessenta por cento", re.IGNORECASE),
    "PADRAO": re.compile(r"al[ií]quota (padr[aã]o|cheia|integral|normal|de refer[eê]ncia)|"
                         r"sem (nenhuma |qualquer )?redu[cç][aã]o|n[aã]o (tem|ter[aá]|h[aá]|recebe|"
                         r"receber[aá]) (direito [aà] )?(redu[cç][aã]o|benef[ií]cio)", re.IGNORECASE),
}
_DEPENDE = re.compile(r"\bdepend|\bvaria de acordo\b|\bcaso a caso\b", re.IGNORECASE)
_CONTADOR = re.compile(r"\bcontador|\bcontabilista|\bescrit[oó]rio (de )?contab", re.IGNORECASE)
#: "nao" (ou "nem") ate ~3 palavras antes da expressao a nega: "nao tem aliquota zero".
_NEGACAO_ANTES = re.compile(r"\b(n[aã]o|nem|nunca)\b(\s+\S+){0,3}\s*$", re.IGNORECASE)
_DESFECHOS_CORTE = {"RESPOSTA_CORTADA"}
#: Resposta redigida pelo modelo (as outras sao frase fixa do backend: recusa ou descarte).
_REDIGIDAS = {"RESPONDIDA", "NEGATIVA_SUBSTITUIDA"}

#: Criterios automaticos das externas: chave -> rotulo no relatorio.
CRITERIOS_EXTERNAS = {
    "fundamentacao": "Fundamentação (citação válida)",
    "classe": "Alíquota / classe de tratamento",
    "proibidas": "Precisão: responde sem afirmação proibida",
    "encaminhamento": "Encaminha ao contador quando deve",
}


def afirma(padrao: re.Pattern | str, texto: str) -> bool:
    """O texto **afirma** o padrao: alguma ocorrencia sem negacao logo antes.

    "Voce nao pode aproveitar o credito" nao afirma "pode aproveitar o credito".
    """
    regex = padrao if isinstance(padrao, re.Pattern) else re.compile(padrao, re.IGNORECASE)
    return any(not _NEGACAO_ANTES.search(texto[max(0, m.start() - 40):m.start()])
               for m in regex.finditer(texto))


def classe_dita(texto: str, classe: str) -> bool | None:
    """A resposta diz a classe esperada, e so ela? ``None`` para ``NAO_SE_APLICA``.

    Para ZERO, REDUCAO_60 e PADRAO: a classe afirmada (sem negacao) e nenhuma outra afirmada; a
    resposta que lista todas as classes nao acerta nenhuma. CONDICIONAL: duas ou mais classes
    afirmadas, ou "depende".
    """
    afirmadas = {c for c, p in _PADROES_CLASSE.items() if afirma(p, texto)}
    if classe in _PADROES_CLASSE:
        return afirmadas == {classe}
    if classe == "CONDICIONAL":
        return len(afirmadas) >= 2 or bool(_DEPENDE.search(texto))
    if classe == "FORA_DO_ESCOPO_SISTEMA":
        return bool(_CONTADOR.search(texto))
    return None


def avaliar(resultado: dict, pergunta: dict) -> dict[str, bool | None]:
    """Criterio -> acertou? ``None`` quando nao se aplica (ou ainda nao da para medir).

    A fundamentacao e o ``citacaoValida`` do proprio comando (o ``AvaliadorResposta`` mede o
    ``dispositivosEsperados``; vazio enquanto ``aConferir``): uma regra so de citacao. Resposta que
    nao foi redigida pelo modelo (recusa, descarte, corte) nao ganha credito de precisao, classe
    nem encaminhamento: a frase fixa do backend nao responde a pergunta.
    """
    execucao = resultado.get("execucao") or {}
    texto = execucao.get("texto") or ""
    redigida = execucao.get("desfecho") in _REDIGIDAS
    proibidas = pergunta.get("afirmacoesProibidas") or []
    classe = None if pergunta.get("pendenteContador") else classe_dita(
        texto, pergunta.get("classeTratamento", ""))
    return {
        "fundamentacao": resultado.get("citacaoValida"),
        # Pendente do contador (#284): sem valor esperado firme, fora da metrica de tratamento.
        "classe": None if classe is None else (redigida and classe),
        "proibidas": (redigida and not any(afirma(p, texto) for p in proibidas)) if proibidas else None,
        "encaminhamento": (redigida and bool(_CONTADOR.search(texto)))
        if pergunta.get("encaminharContador") else None,
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


def _celula_csv(valor: str) -> str:
    """Celula de texto segura no Excel: ``= + - @`` no inicio viraria formula (como no ``CsvCego``)."""
    return "'" + valor if valor[:1] in ("=", "+", "-", "@") else valor


def csv_cego(celulas, canonico: dict[str, dict], aleatorio: random.Random | None = None):
    """CSV cego das externas para o avaliador externo (#275) e a chave, que nao vai a ele.

    Uma linha por (celula, pergunta) da 1a rodada, embaralhadas; colunas de nota 1 a 5 em branco
    para contextualizacao, orientacao pratica e precisao final. Ficam de fora: pergunta
    contaminada, celula invalida e resposta com falha tecnica ou vazia (nao ha o que avaliar).
    Mesmo formato do ``CsvCego`` do Java: separador ``;``, BOM UTF-8 (Excel em portugues) e
    protecao contra formula.
    """
    from .relatorio import falha_tecnica  # import tardio: relatorio importa este modulo

    aleatorio = aleatorio or random.SystemRandom()
    linhas = []
    for c in celulas:
        if not c.rodadas or c.invalida:
            continue
        for r in c.rodadas[0]:
            p = canonico.get(r["id"])
            if p is None or p.get("origem") != "inedita_externa_ia" or p.get("contaminada"):
                continue
            texto = (r.get("execucao") or {}).get("texto") or ""
            if falha_tecnica(r) or not texto.strip():
                continue
            linhas.append({"celula": c.nome, "id": r["id"], "pergunta": p["pergunta"], "resposta": texto})
    aleatorio.shuffle(linhas)
    saida = io.StringIO()
    escritor = csv.writer(saida, delimiter=";", lineterminator="\n")
    escritor.writerow(["codigo", "pergunta", "resposta", "contextualizacao_1a5",
                       "orientacao_pratica_1a5", "precisao_final_1a5", "comentario"])
    chave = {}
    for i, linha in enumerate(linhas, 1):
        codigo = f"R{i:03d}"
        escritor.writerow([codigo, _celula_csv(linha["pergunta"]), _celula_csv(linha["resposta"]),
                           "", "", "", ""])
        chave[codigo] = {"celula": linha["celula"], "id": linha["id"]}
    return "\ufeff" + saida.getvalue(), json.dumps(chave, ensure_ascii=False, indent=2) + "\n"


def sql_conferencia(conjunto: dict) -> str:
    """Uma consulta que lista, para cada chave dos ``dispositivosEsperados`` das externas, se ela
    existe em ``legislacao_trecho`` (igual ou com subdivisoes, a mesma regra do ``citaAlgum``).

    Rodar na VPS (``/opt/agrocontabil``):
    ``docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < arquivo``.
    A chave que faltar na base sai dos aceitos.
    """
    usos: dict[str, list[str]] = {}
    for p in conjunto["perguntas"]:
        if p.get("origem") == "inedita_externa_ia":
            for chave in p.get("dispositivosEsperados") or []:
                usos.setdefault(chave, []).append(p["id"])
    linhas = []
    for chave in sorted(usos):
        norma, dispositivo = chave.split(", ", 1)
        q = lambda s: "'" + s.replace("'", "''") + "'"  # noqa: E731
        linhas.append(f"  ({q(chave)}, {q(norma)}, {q(dispositivo)}, {q(', '.join(usos[chave]))})")
    return (
        "-- Gerado por ceres_benchmark.externas.sql_conferencia (#358): nao editar a mao.\n"
        "-- Chaves dos dispositivosEsperados das perguntas externas contra a base de lei.\n"
        "-- exato = trecho com a chave inteira; com_subdivisoes = trechos que comecam por ela\n"
        "-- seguida de ',' ou ')' (a regra do AvaliadorResposta.citaAlgum). Zero nos dois = sai dos aceitos.\n"
        f"-- Conjunto {conjunto.get('versao')}.\n"
        "WITH chaves(chave, norma, dispositivo, perguntas) AS (VALUES\n"
        + ",\n".join(linhas) + "\n)\n"
        "SELECT c.chave, c.perguntas,\n"
        "       count(t.*) FILTER (WHERE t.dispositivo = c.dispositivo) AS exato,\n"
        "       count(t.*) FILTER (WHERE t.dispositivo LIKE c.dispositivo || ',%'\n"
        "                            OR t.dispositivo LIKE c.dispositivo || ')%') AS com_subdivisoes\n"
        "FROM chaves c\n"
        "LEFT JOIN legislacao_trecho t ON t.norma = c.norma\n"
        "  AND (t.dispositivo = c.dispositivo OR t.dispositivo LIKE c.dispositivo || ',%'\n"
        "       OR t.dispositivo LIKE c.dispositivo || ')%')\n"
        "GROUP BY c.chave, c.perguntas\n"
        "ORDER BY (count(t.*) = 0) DESC, c.chave;\n"
    )
