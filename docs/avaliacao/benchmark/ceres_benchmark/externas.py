"""Criterios automaticos das perguntas externas (#358) e o CSV cego da nota humana.

Os 5 criterios do Murillo para as 40 perguntas compiladas por ele:

- **automaticos** (aqui, sobre a resposta entregue, iguais para as duas arquiteturas):
  fundamentacao (dispositivo aceito citado; so depois de ``dispositivosEsperados`` conferidos na
  base), aliquota/classe de tratamento e precisao por heuristica (``classeTratamento`` e
  ``afirmacoesProibidas``) e encaminhamento ao contador;
- **humanos** (CSV cego para o orientador, #275; sem contador, #387): contextualizacao, orientacao pratica e
  precisao final, de 1 a 5 (``docs/avaliacao/rubrica-clareza.md``).

Tudo aqui e **heuristica de texto**: acerta o que a frase diz, nao o raciocinio. Serve para
comparar celulas na mesma regua; a nota que vale para a precisao e a humana.
"""

from __future__ import annotations

import csv
import hashlib
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
#: "conforme" so com o que varia ("conforme o produto"): "conforme o art. 138" e citacao, nao condicao.
_DEPENDE = re.compile(r"\bdepend|\bvaria\b|\bconforme (o|a) (produto|caso|ncm|tipo|destino|uso|"
                      r"finalidade|situa[cç][aã]o|comprador|adquirente)\b|\bcaso a caso\b", re.IGNORECASE)
_CONTADOR = re.compile(r"\bcontador|\bcontabilista|\bescrit[oó]rio (de )?contab", re.IGNORECASE)
#: "Voce nao precisa procurar o contador" fala do contador sem encaminhar a ele (mesma oracao).
_SEM_CONTADOR = re.compile(r"\b(n[aã]o|sem) (precisa|precisar|[eé] necess[aá]rio|h[aá] necessidade)"
                           r"[^.;:!?,\n]{0,40}?(contador|contabilista|contab)", re.IGNORECASE)
#: Um vocabulario so de negadores, para a classe, a afirmacao proibida e a base de reducao.
_NEGADOR = re.compile(r"\b(n[aã]o|nem|nunca|jamais|sem|nenhum|nenhuma)\b", re.IGNORECASE)
#: Negador que nao nega o que vem depois: interjeicao ("Nao, neste caso, ..."), "nao so"/"nao
#: apenas", e "nao contribuinte" (substantivo do art. 164).
_NEGADOR_INERTE = re.compile(r"\s*,|\s+(s[oó]|apenas|somente|contribuintes?)\b", re.IGNORECASE)
#: Conectivo que abre outra oracao: o escopo do negador para nele ("o trator nao e isento e tem
#: aliquota padrao", "nao paga IBS porque tem aliquota zero").
_CONECTIVO = re.compile(r"\b(e|mas|por[eé]m|contudo|todavia|porque|pois|se|ou|enquanto|que|quando)\b",
                        re.IGNORECASE)
#: Fim de oracao: pontuacao forte, quebra de linha e a virgula seguida de espaco (a decimal, 26,5%,
#: nao corta), fora dos apostos curtos (mascarados, ver _mascarar_apostos).
_FIM_ORACAO = re.compile(r"[.;:!?\n]|,\s")
#: Aposto: trecho curto entre virgulas (ate 4 palavras): ", neste caso,", ", hoje,", ", a meu ver,".
_APOSTO = re.compile(r",(\s+[^\s,.;:!?]+){1,4}\s*,")
#: Ate quantas palavras entre o negador e a expressao: "nao ha reducao de 60%" (3), "nao tera
#: direito a aliquota zero" (3).
_JANELA_NEGACAO = 4
_PARTICIPIO = re.compile(r"\w+(ad|id)[oa]s?", re.IGNORECASE)
#: Negacao logo depois da expressao: "a aliquota zero nao se aplica", "a reducao de 60% nao vale".
_NEGADA_DEPOIS = re.compile(r"^[^.;:!?,\n]{0,25}?\bn[aã]o\s+(se\s+aplica|vale|incide|cabe|existe|h[aá])\b",
                            re.IGNORECASE)
#: Gatilho de "aliquota padrao" como base de uma reducao: "reducao de 60% da aliquota padrao",
#: "60% menor que a aliquota padrao".
_GATILHO_REDUCAO = re.compile(r"redu[cç][aã]o|reduzid\w*|\bmenor que\b|\bdesconto\b|\b60\s?%|"
                              r"sessenta por cento", re.IGNORECASE)
#: Desfechos em que o comando descarta o texto: a citacao dele nao conta (regra do AvaliadorResposta
#: da main; o do 2463c27 nao a aplica, e as duas arquiteturas tem de ser medidas pela mesma regua).
#: Igual ao ``AvaliadorResposta.RESPOSTA_DESCARTADA`` do Java (#433).
DESCARTADAS = {"CITACAO_INVALIDA", "NUMERO_SEM_FONTE", "RESPOSTA_CORTADA",
               "REGIME_ESPECIFICO_SEM_SETOR"}
#: Funcoes que, ao rodar, sao o sinal (nos campos gravados) de que o backend pode ter juntado citacoes
#: fixas (``FundamentoRetencao`` e ``TextosDoBackend``). E um sinal, nao uma garantia. Limites aceitos
#: na #433: com a funcao, conta qualquer citacao da lista (inclusive a da lei descartada); sem ela, a
#: regua rebaixa ate a citacao fixa que o FundamentoRetencao junta so pelo texto da pergunta.
FUNCOES_COM_CITACAO_FIXA = {"consultarRetencoes", "consultarTratamentoProduto"}
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


def _mascarar_apostos(texto: str) -> str:
    """O texto com as virgulas dos apostos curtos trocadas por espaco (mesmo comprimento): so para
    medir o escopo da negacao; a busca dos padroes roda no texto original, nada some."""
    def trocar(m):
        # "Nao, neste caso, ..." e interjeicao: a virgula fica e corta a oracao.
        if re.search(r"\b(n[aã]o|nem|nunca|jamais)\s*$", texto[:m.start()], re.IGNORECASE):
            return m.group(0)
        return " " + m.group(0)[1:-1] + " "
    return _APOSTO.sub(trocar, texto)


def _oracao_antes(mascarado: str, inicio: int) -> str:
    """O trecho da mesma oracao antes de ``inicio``, no texto mascarado."""
    antes = mascarado[max(0, inicio - 160):inicio]
    cortes = list(_FIM_ORACAO.finditer(antes))
    return antes[cortes[-1].end():] if cortes else antes


def _negada(oracao: str) -> bool:
    """Um negador da oracao governa o que vem no fim dela: ate ``_JANELA_NEGACAO`` palavras antes,
    sem conectivo no meio (o escopo para nele), sem ser inerte (interjeicao, "nao so", "nao
    contribuinte") e sem qualificar um particípio seguido de outro verbo ("nao processados tem",
    "que nao e processado tem")."""
    for m in _NEGADOR.finditer(oracao):
        depois = oracao[m.end():]
        if _NEGADOR_INERTE.match(depois):
            continue
        palavras = depois.split()
        if len(palavras) > _JANELA_NEGACAO or any(_CONECTIVO.fullmatch(p) for p in palavras):
            continue
        if any(_PARTICIPIO.fullmatch(p) for p in palavras[:-1]):
            continue
        return True
    return False


def _base_de_reducao(oracao: str) -> bool:
    """A oracao antes da "aliquota padrao" afirma uma reducao (nao negada, sem conectivo depois)."""
    for m in _GATILHO_REDUCAO.finditer(oracao):
        if _negada(oracao[:m.start()]) or _NEGADA_DEPOIS.match(oracao[m.end():]):
            continue
        if any(_CONECTIVO.fullmatch(p) for p in oracao[m.end():].split()):
            continue
        return True
    return False


def afirma(padrao: re.Pattern | str, texto: str, base_de_reducao: bool = False) -> bool:
    """O texto **afirma** o padrao: alguma ocorrencia que nao esta negada na mesma oracao (antes
    dela, ou logo depois: "a aliquota zero nao se aplica").

    "Voce nao pode aproveitar o credito" nao afirma "pode aproveitar o credito". Com
    ``base_de_reducao``, a ocorrencia que e a base de uma reducao ("60% da aliquota padrao") tambem
    nao conta.
    """
    regex = padrao if isinstance(padrao, re.Pattern) else re.compile(padrao, re.IGNORECASE)
    mascarado = _mascarar_apostos(texto)
    for m in regex.finditer(texto):
        oracao = _oracao_antes(mascarado, m.start())
        if _negada(oracao) or _NEGADA_DEPOIS.match(texto[m.end():]):
            continue
        if base_de_reducao and _base_de_reducao(oracao):
            continue
        return True
    return False


def encaminha(texto: str) -> bool:
    """A resposta manda ao contador: alguma mencao fora de um "nao precisa ... contador"."""
    fora = [(m.start(), m.end()) for m in _SEM_CONTADOR.finditer(_mascarar_apostos(texto))]
    return any(not any(a <= m.start() < b for a, b in fora) for m in _CONTADOR.finditer(texto))


def classe_dita(texto: str, classe: str) -> bool | None:
    """A resposta diz a classe esperada, e so ela? ``None`` para ``NAO_SE_APLICA``.

    Para ZERO, REDUCAO_60 e PADRAO: a classe afirmada (sem negacao) e nenhuma outra afirmada; a
    resposta que lista todas as classes nao acerta nenhuma. CONDICIONAL: duas ou mais classes
    afirmadas, ou "depende".
    """
    afirmadas = {c for c, p in _PADROES_CLASSE.items()
                 if afirma(p, texto, base_de_reducao=(c == "PADRAO"))}
    if classe in _PADROES_CLASSE:
        return afirmadas == {classe}
    if classe == "CONDICIONAL":
        return len(afirmadas) >= 2 or afirma(_DEPENDE, texto)
    if classe == "FORA_DO_ESCOPO_SISTEMA":
        return encaminha(texto)
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
        "fundamentacao": citacao_vale(resultado),
        # Pendente do contador (#284): sem valor esperado firme, fora da metrica de tratamento.
        "classe": None if classe is None else (redigida and classe),
        "proibidas": (redigida and not any(afirma(p, texto) for p in proibidas)) if proibidas else None,
        "encaminhamento": (redigida and encaminha(texto))
        if pergunta.get("encaminharContador") else None,
    }


def citacao_vale(resultado: dict) -> bool | None:
    """``citacaoValida`` do comando, com a regra da main nas duas arquiteturas (``AvaliadorResposta
    .citacaoValida``, #433): texto descartado (citacao invalida, numero sem fonte, corte, regime
    especifico sem setor) nao ganha credito de citacao. No MISTO com a lei descartada, ou com o
    desfecho geral ``AFIRMACAO_SOBRE_DADOS``, a citacao so conta se rodou uma funcao de citacao
    fixa (o sinal de que o backend pode ter juntado citacoes fixas; ver os limites acima).
    A regra so rebaixa: ``citacaoValida`` falso ou nulo segue como veio. Regra unica: o
    criterio principal do relatorio e o das externas usam esta funcao."""
    valor = resultado.get("citacaoValida")
    if not valor:
        return valor
    execucao = resultado.get("execucao") or {}
    if execucao.get("desfecho") in DESCARTADAS:
        return False
    if execucao.get("rota") == "MISTO" and (
            execucao.get("desfecho") == "AFIRMACAO_SOBRE_DADOS"
            or execucao.get("desfechoLegislacao") in DESCARTADAS):
        funcoes = {f.get("funcao") for f in execucao.get("fontes") or []}
        if not funcoes & FUNCOES_COM_CITACAO_FIXA:
            return False
    return valor


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


def assinatura_celula(celula) -> str:
    """Hash curto da 1a rodada inteira da celula (id, desfecho e texto de cada resposta): muda
    quando a celula e refeita, mesmo que so uma pergunta que antes falhou passe a ter resposta."""
    partes = sorted(f"{r.get('id')}\x1f{(r.get('execucao') or {}).get('desfecho')}\x1f"
                    f"{(r.get('execucao') or {}).get('texto') or ''}"
                    for r in (celula.rodadas[0] if celula.rodadas else []))
    return hashlib.sha256("\x1e".join(partes).encode()).hexdigest()[:16]


def csv_cego(celulas, canonico: dict[str, dict], aleatorio: random.Random | None = None,
             inicio: int = 1):
    """CSV cego das externas para o orientador (#275, #387) e a chave, que nao vai a ele.

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
    for i, linha in enumerate(linhas, inicio):
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
        "-- seguida de ',', ')' ou espaco (a regra do AvaliadorResposta.citaAlgum). Zero nos dois = sai dos\n"
        "-- aceitos.\n"
        f"-- Conjunto {conjunto.get('versao')}.\n"
        "WITH chaves(chave, norma, dispositivo, perguntas) AS (VALUES\n"
        + ",\n".join(linhas) + "\n)\n"
        "SELECT c.chave, c.perguntas,\n"
        "       count(t.*) FILTER (WHERE t.dispositivo = c.dispositivo) AS exato,\n"
        "       count(t.*) FILTER (WHERE t.dispositivo LIKE c.dispositivo || ',%'\n"
        "                            OR t.dispositivo LIKE c.dispositivo || ')%'\n"
        "                            OR t.dispositivo LIKE c.dispositivo || ' %') AS com_subdivisoes\n"
        "FROM chaves c\n"
        "LEFT JOIN legislacao_trecho t ON t.norma = c.norma\n"
        "  AND (t.dispositivo = c.dispositivo OR t.dispositivo LIKE c.dispositivo || ',%'\n"
        "       OR t.dispositivo LIKE c.dispositivo || ')%' OR t.dispositivo LIKE c.dispositivo || ' %')\n"
        "GROUP BY c.chave, c.perguntas\n"
        "ORDER BY (count(t.*) = 0) DESC, c.chave;\n"
    )
