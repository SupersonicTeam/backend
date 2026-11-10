#!/usr/bin/env python3
"""Pontua as respostas da Ceres no conjunto de período relativo da #452.

Só biblioteca padrão. Dois comandos:

  pontuar   lê o conjunto congelado e o JSONL de execuções de UM braço e classifica cada
            execução como certa, errada ou falha técnica; por pergunta vale a maioria das
            execuções válidas. Grava um resumo em JSON.
  comparar  aplica a regra de decisão (regra-de-decisao.md) a dois resumos: a base (main) e a
            correção, medidas no mesmo dia.

`pontuar --fora-da-decisao ID` (repetível) tira a pergunta da decisão, como manda a regra da v2f
(regra-de-decisao-v2f.md): ela sai de `por_pergunta`, da contagem e da mediana do grupo e vai
para `fora_da_decisao`, com o resultado e o tempo de cada execução. Sem a opção, o resumo é o de
antes.

Cada linha do JSONL (gravada pelo executar.py) traz `id`, `repeticao`, `tentativa`, `braco`,
`commit`, `modelo`, `modelo_digest`, `horario`, `data_referencia` (data de Brasília da chamada),
`http_status`, `corpo` (resposta de POST /api/assistente/chat), `segundos`, `falha_tecnica` e
`aquecimento` e, desde o conjunto v2, `conjunto_versao` e `conjunto_sha256` (o conjunto
perguntado: linha com outro hash recusa a pontuação). Linhas de aquecimento não são pontuadas.

Gramática de `esperado` (uma pergunta):

  alternativas     lista de alternativas; cada uma é uma lista de condições, e a alternativa vale
                   quando cada condição casa com alguma fonte de `fontesDados`.
  proibidas        condições que não podem casar com nenhuma fonte.
  datas_proibidas  {"de": tok, "ate": tok}: nenhuma data dd/mm/aaaa da resposta nessa faixa.
  esclarecimento   também certa a resposta da rota de dados (DADOS ou MISTO) sem fonte com
                   período que devolve uma pergunta sobre o período (tem "?" e fala de período,
                   datas ou safra).
  regra            pergunta de regra legal: certa se a rota está em `rotas` e nenhuma fonte é de
                   dados do produtor (desfecho da lei não importa).
  rotas            rotas aceitas (`rota` do corpo).
  norma            controle de lei: alguma citação contém um destes termos.

Condição: `funcoes` (obrigatória) e, opcionais, `mes` (tok de mês), `mes_diferente_de` (tok),
`mes_fora_de` (lista de toks), `inicio` e `fim` (lista de toks aceitos, {"antes_de": tok} ou
{"entre": [tok, tok]}, inclusive), `dias_ate` (tok: hoje + dias do proximosVencimentos),
`produto` (termos; número casa por prefixo do NCM), `tipo` e `sem_tipo`.

Tokens de data: hoje, ontem, mes:N:inicio|fim, ano:N:inicio|fim, nomeado:M:inicio|fim (o mês M
mais recente que não passa do corrente), AAAA-MM-DD, com sufixo opcional -1d. Tok de mês:
mes:N ou nomeado:M (AAAA-MM).

Resposta descartada ("Não consegui confirmar ...") é errada em toda pergunta, exceto nas de
regra. Falha técnica: HTTP diferente de 200, `modoFallback`, `falha_tecnica` marcado pelo
executor ou erro de indisponibilidade no corpo.
"""

from __future__ import annotations

import argparse
import calendar
import datetime as dt
import hashlib
import json
import re
import statistics
import sys
import unicodedata
from pathlib import Path

GRUPOS_CORRECAO = ("mes_seguinte", "mes_corrente", "trimestre", "aberto")
GRUPOS_CONTROLE = ("controle_dados", "controle_lei", "controle_fora")
GRUPOS = GRUPOS_CORRECAO + GRUPOS_CONTROLE

CODIGOS_FALHA_TECNICA = {
    "ASSISTENTE_INDISPONIVEL",
    "ASSISTENTE_OCUPADO",
    "ASSISTENTE_TEMPO_ESGOTADO",
    "LIMITE_USO_ASSISTENTE",
    "MUITAS_TENTATIVAS",
}

FUNCOES_DE_MES = {"consultarApuracao", "vencimentosDoMes"}
FUNCOES_DE_DATAS = {
    "totalComprasPorInsumo",
    "totalVendasPorProduto",
    "consultarCreditos",
    "consultarRetencoes",
    "listarNotas",
}
FUNCOES_COM_PERIODO = FUNCOES_DE_MES | FUNCOES_DE_DATAS | {"proximosVencimentos"}
FUNCOES_DADOS = FUNCOES_COM_PERIODO | {
    "guiasEmAtraso", "listarAlertas", "resumoDoProdutor", "consultarNota"}

CHAVES_PRODUTO = ("descricaoOuNcm", "produtoOuNcm")
DESCARTE = "nao consegui confirmar"
DATA_BR = re.compile(r"\b(\d{2})/(\d{2})/(\d{4})\b")


# ---------------------------------------------------------------------------------------------
# Texto e datas
# ---------------------------------------------------------------------------------------------


def normalizar(texto: object) -> str:
    """Sem acento e em minúsculas, para comparar texto."""
    decomposto = unicodedata.normalize("NFKD", str(texto))
    return "".join(c for c in decomposto if not unicodedata.combining(c)).lower()


def primeiro_dia(data: dt.date) -> dt.date:
    return data.replace(day=1)


def ultimo_dia(data: dt.date) -> dt.date:
    return data.replace(day=calendar.monthrange(data.year, data.month)[1])


def mes_relativo(hoje: dt.date, deslocamento: int) -> dt.date:
    """Primeiro dia do mês `deslocamento` meses a partir do mês de `hoje`."""
    total = hoje.year * 12 + (hoje.month - 1) + deslocamento
    ano, mes = divmod(total, 12)
    return dt.date(ano, mes + 1, 1)


def mes_nomeado(hoje: dt.date, mes: int) -> dt.date:
    """Primeiro dia do mês `mes` mais recente que não passa do mês de `hoje`."""
    if not 1 <= mes <= 12:
        raise ValueError(f"Mês fora de 1..12: {mes}")
    ano = hoje.year if mes <= hoje.month else hoje.year - 1
    return dt.date(ano, mes, 1)


def _mes_do_token(partes: list[str], hoje: dt.date) -> dt.date:
    if partes[0] == "mes":
        return mes_relativo(hoje, int(partes[1]))
    if partes[0] == "nomeado":
        return mes_nomeado(hoje, int(partes[1]))
    raise ValueError(f"Token de mês desconhecido: {':'.join(partes)}")


def data_do_token(token: str, hoje: dt.date) -> dt.date:
    """Resolve um token de data a partir de `hoje` (ver a gramática no topo do arquivo)."""
    texto = token.strip()
    menos_um = texto.endswith("-1d")
    if menos_um:
        texto = texto[:-3]
    if texto == "hoje":
        data = hoje
    elif texto == "ontem":
        data = hoje - dt.timedelta(days=1)
    elif re.fullmatch(r"\d{4}-\d{2}-\d{2}", texto):
        data = dt.date.fromisoformat(texto)
    else:
        partes = texto.split(":")
        if len(partes) != 3 or partes[2] not in ("inicio", "fim"):
            raise ValueError(f"Token de data desconhecido: {token}")
        if partes[0] == "ano":
            ano = hoje.year + int(partes[1])
            data = dt.date(ano, 1, 1) if partes[2] == "inicio" else dt.date(ano, 12, 31)
        else:
            inicio = _mes_do_token(partes, hoje)
            data = inicio if partes[2] == "inicio" else ultimo_dia(inicio)
    return data - dt.timedelta(days=1) if menos_um else data


def mes_do_token(token: str, hoje: dt.date) -> str:
    """Resolve um token de mês (mes:N ou nomeado:M) para AAAA-MM."""
    partes = token.strip().split(":")
    if len(partes) != 2:
        raise ValueError(f"Token de mês desconhecido: {token}")
    return _mes_do_token(partes, hoje).strftime("%Y-%m")


def ler_data(valor: object) -> dt.date | None:
    if valor is None:
        return None
    texto = str(valor).strip()
    if not re.match(r"^\d{4}-\d{2}-\d{2}", texto):
        return None
    try:
        return dt.date.fromisoformat(texto[:10])
    except ValueError:
        return None


def ler_mes(valor: object) -> str | None:
    if valor is None:
        return None
    texto = str(valor).strip()
    return texto if re.fullmatch(r"\d{4}-\d{2}", texto) else None


# ---------------------------------------------------------------------------------------------
# Fontes de dados
# ---------------------------------------------------------------------------------------------


def parametros(fonte: dict) -> dict:
    """Os parâmetros de uma fonte, aceitando mapa, JSON em texto ou 'chave=valor, ...'."""
    bruto = fonte.get("parametros")
    if isinstance(bruto, dict):
        return bruto
    if bruto is None:
        return {}
    texto = str(bruto).strip()
    try:
        lido = json.loads(texto)
        if isinstance(lido, dict):
            return lido
    except ValueError:
        pass
    pares = {}
    for parte in re.split(r",\s*(?=\w+\s*[=:])", texto.strip("{}")):
        if "=" in parte or ":" in parte:
            chave, _, valor = re.split(r"([=:])", parte, maxsplit=1)
            pares[chave.strip().strip('"')] = valor.strip().strip('"')
    return pares


def periodo_da_fonte(funcao: str, params: dict, hoje: dt.date):
    """('mes', 'AAAA-MM'), ('datas', inicio, fim), ('dias', fim) ou None.

    O backend grava nas fontes o período já resolvido (mês e datas); o proximosVencimentos grava
    `dias`, e o fim é hoje + dias.
    """
    if funcao in FUNCOES_DE_MES:
        mes = ler_mes(params.get("mes"))
        return ("mes", mes) if mes else None
    if funcao in FUNCOES_DE_DATAS:
        inicio = ler_data(params.get("dataInicio"))
        fim = ler_data(params.get("dataFim"))
        return ("datas", inicio, fim) if inicio and fim else None
    if funcao == "proximosVencimentos":
        try:
            dias = int(str(params.get("dias")).strip())
        except ValueError:
            return None
        return ("dias", hoje + dt.timedelta(days=dias))
    return None


def _limite_aceito(regra, data: dt.date, hoje: dt.date) -> bool:
    if isinstance(regra, list):
        return any(data == data_do_token(t, hoje) for t in regra)
    if isinstance(regra, dict) and "antes_de" in regra:
        return data < data_do_token(regra["antes_de"], hoje)
    if isinstance(regra, dict) and "entre" in regra:
        de, ate = (data_do_token(t, hoje) for t in regra["entre"])
        return de <= data <= ate
    raise ValueError(f"Limite de período desconhecido: {regra}")


def _confere_produto(termos: list, params: dict) -> bool:
    valor = next((params[c] for c in CHAVES_PRODUTO if params.get(c)), None)
    if valor is None:
        return False
    texto = normalizar(valor)
    digitos = re.sub(r"\D", "", texto)
    for termo in termos:
        termo = normalizar(termo)
        if termo.isdigit():
            if digitos.startswith(termo):
                return True
        elif termo in texto:
            return True
    return False


def casa(condicao: dict, fonte: dict, hoje: dt.date) -> bool:
    """A fonte satisfaz todas as exigências da condição."""
    funcao = fonte.get("funcao")
    if funcao not in condicao["funcoes"]:
        return False
    params = parametros(fonte)
    periodo = periodo_da_fonte(funcao, params, hoje)

    if "mes" in condicao:
        alvo = mes_do_token(condicao["mes"], hoje)
        if periodo is None:
            return False
        if periodo[0] == "mes":
            if periodo[1] != alvo:
                return False
        elif periodo[0] == "datas":
            inicio = dt.date.fromisoformat(alvo + "-01")
            if periodo[1:] != (inicio, ultimo_dia(inicio)):
                return False
        else:
            return False
    if "mes_diferente_de" in condicao:
        if periodo is None or periodo[0] != "mes":
            return False
        if periodo[1] == mes_do_token(condicao["mes_diferente_de"], hoje):
            return False
    if "mes_fora_de" in condicao:
        if periodo is None or periodo[0] != "mes":
            return False
        if periodo[1] in {mes_do_token(t, hoje) for t in condicao["mes_fora_de"]}:
            return False
    if "inicio" in condicao or "fim" in condicao:
        if periodo is None or periodo[0] != "datas":
            return False
        if "inicio" in condicao and not _limite_aceito(condicao["inicio"], periodo[1], hoje):
            return False
        if "fim" in condicao and not _limite_aceito(condicao["fim"], periodo[2], hoje):
            return False
    if "dias_ate" in condicao:
        if periodo is None or periodo[0] != "dias":
            return False
        if periodo[1] != data_do_token(condicao["dias_ate"], hoje):
            return False
    if "produto" in condicao and not _confere_produto(condicao["produto"], params):
        return False
    if "tipo" in condicao and str(params.get("tipo", "")).upper() != condicao["tipo"]:
        return False
    if condicao.get("sem_tipo") and params.get("tipo"):
        return False
    return True


def _fontes(corpo: dict) -> list[dict]:
    return [f for f in corpo.get("fontesDados") or [] if isinstance(f, dict)]


# ---------------------------------------------------------------------------------------------
# Classificação de uma execução
# ---------------------------------------------------------------------------------------------


def descartada(corpo: dict) -> bool:
    return DESCARTE in normalizar(corpo.get("resposta") or "")


def _datas_na_faixa(texto: str, de: dt.date, ate: dt.date) -> list[dt.date]:
    achadas = []
    for dia, mes, ano in DATA_BR.findall(texto):
        try:
            data = dt.date(int(ano), int(mes), int(dia))
        except ValueError:
            continue
        if de <= data <= ate:
            achadas.append(data)
    return achadas


def _esclarecimento(corpo: dict) -> bool:
    if corpo.get("rota") not in ("DADOS", "MISTO"):
        return False
    if any(f.get("funcao") in FUNCOES_COM_PERIODO for f in _fontes(corpo)):
        return False
    texto = normalizar(corpo.get("resposta") or "")
    return "?" in texto and any(t in texto for t in ("periodo", "datas", "safra"))


def confere_dados(esperado: dict, corpo: dict, hoje: dt.date) -> bool:
    fontes = _fontes(corpo)
    for condicao in esperado.get("proibidas", []):
        if any(casa(condicao, f, hoje) for f in fontes):
            return False
    faixa = esperado.get("datas_proibidas")
    if faixa and _datas_na_faixa(corpo.get("resposta") or "",
                                 data_do_token(faixa["de"], hoje),
                                 data_do_token(faixa["ate"], hoje)):
        return False
    for alternativa in esperado.get("alternativas", []):
        if all(any(casa(c, f, hoje) for f in fontes) for c in alternativa):
            return True
    return bool(esperado.get("esclarecimento")) and _esclarecimento(corpo)


def confere_lei(esperado: dict, corpo: dict) -> bool:
    if corpo.get("rota") not in esperado["rotas"]:
        return False
    termos = [normalizar(t) for t in esperado["norma"]]
    for citacao in corpo.get("citacoes") or []:
        texto = normalizar(json.dumps(citacao, ensure_ascii=False))
        if any(t in texto for t in termos):
            return True
    return False


def confere_regra(esperado: dict, corpo: dict) -> bool:
    if corpo.get("rota") not in esperado["rotas"]:
        return False
    return not any(f.get("funcao") in FUNCOES_DADOS for f in _fontes(corpo))


def falha_tecnica(registro: dict) -> bool:
    if registro.get("falha_tecnica"):
        return True
    if registro.get("http_status") != 200:
        return True
    corpo = registro.get("corpo")
    if not isinstance(corpo, dict):
        return True
    if corpo.get("modoFallback") is True:
        return True
    codigos = {e.get("code") for e in corpo.get("errors") or [] if isinstance(e, dict)}
    return bool(codigos & CODIGOS_FALHA_TECNICA)


def classificar(pergunta: dict, registro: dict, hoje: dt.date) -> str:
    """'certa', 'errada' ou 'tecnica' para uma execução."""
    if falha_tecnica(registro):
        return "tecnica"
    corpo = registro["corpo"]
    esperado = pergunta["esperado"]
    if esperado.get("regra"):
        return "certa" if confere_regra(esperado, corpo) else "errada"
    if descartada(corpo):
        return "errada"
    grupo = pergunta["grupo"]
    if grupo == "controle_fora":
        certa = corpo.get("rota") in esperado["rotas"]
    elif grupo == "controle_lei":
        certa = confere_lei(esperado, corpo)
    elif grupo in GRUPOS:
        certa = confere_dados(esperado, corpo, hoje)
    else:
        raise ValueError(f"Grupo desconhecido: {grupo}")
    return "certa" if certa else "errada"


# ---------------------------------------------------------------------------------------------
# Conjunto, execuções e votação
# ---------------------------------------------------------------------------------------------


def carregar_conjunto(caminho: Path) -> dict:
    """Lê o conjunto e exige que os bytes batam com o .sha256 ao lado."""
    conteudo = caminho.read_bytes()
    hash_lido = hashlib.sha256(conteudo).hexdigest()
    arquivo_hash = caminho.with_name(caminho.name + ".sha256")
    if not arquivo_hash.exists():
        raise SystemExit(f"Conjunto sem {arquivo_hash.name}: não está congelado")
    esperado = arquivo_hash.read_text(encoding="utf-8").split()[0]
    if esperado != hash_lido:
        raise SystemExit(f"Conjunto alterado: sha256 {hash_lido}, .sha256 diz {esperado}")
    conjunto = json.loads(conteudo.decode("utf-8"))
    conjunto["_sha256"] = hash_lido
    return conjunto


def validar_conjunto(conjunto: dict, hoje: dt.date) -> None:
    """Falha se um id repete, o grupo é desconhecido ou um token não resolve em `hoje`."""
    vistos = set()
    for p in conjunto["perguntas"]:
        if p["id"] in vistos:
            raise ValueError(f"id repetido: {p['id']}")
        vistos.add(p["id"])
        if p["grupo"] not in GRUPOS:
            raise ValueError(f"{p['id']}: grupo desconhecido {p['grupo']}")
        esperado = p["esperado"]
        condicoes = [c for alt in esperado.get("alternativas", []) for c in alt]
        condicoes += esperado.get("proibidas", [])
        for c in condicoes:
            if not c.get("funcoes"):
                raise ValueError(f"{p['id']}: condição sem funções")
            for chave in ("mes", "mes_diferente_de"):
                if chave in c:
                    mes_do_token(c[chave], hoje)
            for t in c.get("mes_fora_de", []):
                mes_do_token(t, hoje)
            for chave in ("inicio", "fim"):
                if chave in c:
                    _limite_aceito(c[chave], hoje, hoje)
            if "dias_ate" in c:
                data_do_token(c["dias_ate"], hoje)
        if "datas_proibidas" in esperado:
            data_do_token(esperado["datas_proibidas"]["de"], hoje)
            data_do_token(esperado["datas_proibidas"]["ate"], hoje)
        sem_criterio = not (condicoes or esperado.get("regra") or esperado.get("rotas"))
        if sem_criterio:
            raise ValueError(f"{p['id']}: esperado sem critério")


def ler_execucoes(caminho: Path) -> list[dict]:
    registros = []
    with caminho.open(encoding="utf-8") as arquivo:
        for linha in arquivo:
            if linha.strip():
                registros.append(json.loads(linha))
    return registros


def pontuar(conjunto: dict, registros: list[dict], fora_da_decisao: tuple = ()) -> dict:
    perguntas = {p["id"]: p for p in conjunto["perguntas"]}
    fora = set(fora_da_decisao)
    desconhecidas = sorted(fora - set(perguntas))
    if desconhecidas:
        raise SystemExit("Pergunta fora da decisão que não está no conjunto: "
                         + ", ".join(desconhecidas))
    por_pergunta = {i: {"grupo": p["grupo"], "execucoes": []} for i, p in perguntas.items()}
    tempos: dict[str, list] = {i: [] for i in fora}
    datas, commits, modelos, bracos = set(), set(), set(), set()
    segundos: dict[str, list] = {}
    aquecido = False
    for registro in registros:
        executado = registro.get("conjunto_sha256")
        if executado is not None and executado != conjunto["_sha256"]:
            # Linhas antigas (rodada 1 do v1) não têm o campo e seguem aceitas.
            raise SystemExit(f"Execução de outro conjunto: sha256 {executado}, "
                             f"o pontuado é {conjunto['_sha256']}")
        bracos.add(registro.get("braco"))
        commits.add(registro.get("commit"))
        modelos.add((registro.get("modelo"), registro.get("modelo_digest")))
        if registro.get("aquecimento"):
            # Só aquece de fato a chamada que o backend respondeu.
            aquecido = aquecido or not falha_tecnica(registro)
            continue
        pergunta = perguntas.get(registro.get("id"))
        if pergunta is None:
            raise SystemExit(f"Execução de pergunta fora do conjunto: {registro.get('id')}")
        hoje = ler_data(registro.get("data_referencia"))
        if hoje is None:
            raise SystemExit(f"Linha sem data_referencia: {registro.get('id')}")
        datas.add(hoje.isoformat())
        resultado = classificar(pergunta, registro, hoje)
        por_pergunta[pergunta["id"]]["execucoes"].append(resultado)
        if pergunta["id"] in fora:
            # Fora da decisão: o tempo de cada execução, técnicas inclusive, aparece à parte.
            tempos[pergunta["id"]].append({
                "repeticao": registro.get("repeticao"), "tentativa": registro.get("tentativa"),
                "resultado": resultado, "segundos": registro.get("segundos")})
        elif resultado != "tecnica" and isinstance(registro.get("segundos"), (int, float)):
            segundos.setdefault(pergunta["grupo"], []).append(registro["segundos"])

    por_grupo: dict = {g: {"certas": 0, "total": 0, "sem_medicao": 0} for g in GRUPOS}
    separadas = {}
    for ident, item in por_pergunta.items():
        execucoes = item["execucoes"]
        validas = [e for e in execucoes if e != "tecnica"]
        certas = validas.count("certa")
        if not validas:
            resultado = "sem_medicao"
        else:
            # Empate (por exemplo, 1 certa e 1 errada com uma repetição só técnica) é errada.
            resultado = "certa" if 2 * certas > len(validas) else "errada"
        item.update(resultado=resultado, certas=certas, validas=len(validas),
                    tecnicas=len(execucoes) - len(validas))
        if ident in fora:
            separadas[ident] = dict(item, execucoes=tempos[ident])
            continue
        grupo = por_grupo[item["grupo"]]
        grupo["total"] += 1
        grupo["certas"] += resultado == "certa"
        grupo["sem_medicao"] += resultado == "sem_medicao"

    resumo = {
        "versao_conjunto": conjunto.get("versao"),
        "sha256_conjunto": conjunto["_sha256"],
        "bracos": sorted(b for b in bracos if b),
        "commits": sorted(c for c in commits if c),
        "modelos": sorted(([m, d] for m, d in modelos if m),
                          key=lambda par: [str(x) for x in par]),
        "datas_referencia": sorted(datas),
        "aquecido": aquecido,
        "mediana_segundos": {g: round(statistics.median(v), 1)
                             for g, v in sorted(segundos.items())},
        "por_grupo": por_grupo,
        "por_pergunta": {i: p for i, p in por_pergunta.items() if i not in fora},
    }
    if fora:
        # Só com a opção: sem ela, o resumo sai igual ao de antes (as rodadas antigas se refazem).
        resumo["fora_da_decisao"] = dict(sorted(separadas.items()))
    return resumo


# ---------------------------------------------------------------------------------------------
# Regra de decisão
# ---------------------------------------------------------------------------------------------


def comparar(base: dict, correcao: dict) -> dict:
    """Aplica a regra de decisão gravada em regra-de-decisao.md.

    As perguntas em `fora_da_decisao` (regra-de-decisao-v2f.md) já saíram de `por_pergunta` e
    da contagem no `pontuar`; aqui só se confere que os dois braços tiraram as mesmas e elas
    aparecem à parte na saída.
    """
    problemas = []
    if base["sha256_conjunto"] != correcao["sha256_conjunto"]:
        problemas.append("os dois resumos são de conjuntos diferentes")
    fora_base = base.get("fora_da_decisao", {})
    fora_correcao = correcao.get("fora_da_decisao", {})
    if set(fora_base) != set(fora_correcao):
        problemas.append("os dois resumos tiraram perguntas diferentes da decisão")
    if len(base["datas_referencia"]) != 1 or \
            base["datas_referencia"] != correcao["datas_referencia"]:
        problemas.append("as rodadas não são do mesmo dia (uma data de referência só, a mesma)")
    for nome, resumo in (("base", base), ("correção", correcao)):
        if len(resumo["commits"]) != 1:
            problemas.append(f"a {nome} não tem um commit só: {resumo['commits']}")
        if len(resumo["modelos"]) != 1:
            problemas.append(f"a {nome} não tem um modelo só: {resumo['modelos']}")
        sem_medicao = sorted(i for i, p in resumo["por_pergunta"].items()
                             if p["resultado"] == "sem_medicao")
        if sem_medicao:
            problemas.append(f"perguntas sem execução válida na {nome}: " + ", ".join(sem_medicao))
    if base["commits"] == correcao["commits"]:
        problemas.append("os dois braços são do mesmo commit")
    if base["modelos"] != correcao["modelos"]:
        problemas.append("os dois braços usaram modelos diferentes")
    if set(base["por_pergunta"]) != set(correcao["por_pergunta"]):
        problemas.append("os dois resumos têm perguntas diferentes")

    grupos = {}
    for grupo in GRUPOS_CORRECAO:
        total = base["por_grupo"][grupo]["total"]
        antes = base["por_grupo"][grupo]["certas"]
        depois = correcao["por_grupo"][grupo]["certas"]
        no_teto = antes == total and depois == total
        grupos[grupo] = {"base": antes, "correcao": depois, "total": total,
                         "sobe": depois > antes or no_teto, "no_teto": no_teto}

    def mudancas(de: str, para: str, filtro) -> list[str]:
        return sorted(i for i, p in base["por_pergunta"].items() if filtro(p["grupo"])
                      and p["resultado"] == de
                      and correcao["por_pergunta"].get(i, {}).get("resultado") == para)

    pioras_controle = sorted(
        i for i, p in base["por_pergunta"].items()
        if p["grupo"] in GRUPOS_CONTROLE and p["resultado"] == "certa"
        and correcao["por_pergunta"].get(i, {}).get("resultado") != "certa")

    if problemas:
        decisao = "INCONCLUSIVA"
    elif all(g["sobe"] for g in grupos.values()) and not pioras_controle:
        decisao = "ENTRA"
    else:
        decisao = "NAO_ENTRA"
    latencia = None
    if base.get("aquecido") and correcao.get("aquecido"):
        latencia = {"base": base["mediana_segundos"], "correcao": correcao["mediana_segundos"]}
    saida = {
        "commit_base": base["commits"],
        "commit_correcao": correcao["commits"],
        "data_referencia": base["datas_referencia"],
        "grupos_da_correcao": grupos,
        "controles": {g: {"base": base["por_grupo"][g]["certas"],
                          "correcao": correcao["por_grupo"][g]["certas"],
                          "total": base["por_grupo"][g]["total"]} for g in GRUPOS_CONTROLE},
        "controles_que_pioraram": pioras_controle,
        "subiram": mudancas("errada", "certa", lambda g: True),
        "cairam": mudancas("certa", "errada", lambda g: True),
        "latencia_mediana_segundos": latencia,
    }
    if fora_base or fora_correcao:
        saida["fora_da_decisao"] = {
            i: {"base": fora_base.get(i), "correcao": fora_correcao.get(i)}
            for i in sorted(set(fora_base) | set(fora_correcao))}
    saida.update(problemas=problemas, decisao=decisao)
    return saida


# ---------------------------------------------------------------------------------------------
# Linha de comando
# ---------------------------------------------------------------------------------------------


def _imprimir_resumo(resumo: dict) -> None:
    print(f"Conjunto {resumo['versao_conjunto']} ({resumo['sha256_conjunto'][:12]}), "
          f"braço {', '.join(resumo['bracos'])}, commit {', '.join(resumo['commits'])}, "
          f"data(s) {', '.join(resumo['datas_referencia'])}")
    for grupo in GRUPOS:
        item = resumo["por_grupo"][grupo]
        print(f"  {grupo:15} {item['certas']:2}/{item['total']:2} certas"
              + (f" ({item['sem_medicao']} sem medição)" if item["sem_medicao"] else ""))
    for ident, item in sorted(resumo["por_pergunta"].items()):
        print(f"  {ident:6} {item['grupo']:15} {item['resultado']:12} "
              f"{item['certas']}/{item['validas']} válidas, {item['tecnicas']} técnicas")
    for ident, item in resumo.get("fora_da_decisao", {}).items():
        tempos = ", ".join(
            f"r{e['repeticao']} t{e['tentativa']} {e['resultado']} "
            + (f"{e['segundos']} s" if e["segundos"] is not None else "sem tempo")
            for e in item["execucoes"])
        print(f"  {ident:6} {item['grupo']:15} {item['resultado']:12} fora da decisão: {tempos}")


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    comandos = analisador.add_subparsers(dest="comando", required=True)

    p = comandos.add_parser("pontuar", help="pontua o JSONL de um braço")
    p.add_argument("--perguntas", type=Path, required=True)
    p.add_argument("--execucoes", type=Path, required=True)
    p.add_argument("--saida", type=Path)
    p.add_argument("--fora-da-decisao", action="append", default=[], metavar="ID",
                   help="pergunta que a regra tira da decisão (repetível)")

    c = comandos.add_parser("comparar", help="aplica a regra de decisão a dois resumos")
    c.add_argument("--base", type=Path, required=True)
    c.add_argument("--correcao", type=Path, required=True)

    args = analisador.parse_args(argv)
    if args.comando == "pontuar":
        resumo = pontuar(carregar_conjunto(args.perguntas), ler_execucoes(args.execucoes),
                         tuple(args.fora_da_decisao))
        _imprimir_resumo(resumo)
        if args.saida:
            args.saida.write_text(json.dumps(resumo, ensure_ascii=False, indent=2) + "\n",
                                  encoding="utf-8")
        return 0
    base = json.loads(args.base.read_text(encoding="utf-8"))
    correcao = json.loads(args.correcao.read_text(encoding="utf-8"))
    decisao = comparar(base, correcao)
    print(json.dumps(decisao, ensure_ascii=False, indent=2))
    return {"ENTRA": 0, "NAO_ENTRA": 1}.get(decisao["decisao"], 2)


if __name__ == "__main__":
    sys.exit(main())
