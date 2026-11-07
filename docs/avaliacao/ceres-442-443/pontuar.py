#!/usr/bin/env python3
"""Pontua as respostas da Ceres no conjunto das issues #442 e #443.

Só biblioteca padrão. Dois comandos:

  pontuar   lê o conjunto congelado e um JSONL de execuções e classifica cada execução como
            certa, errada ou falha técnica; por pergunta, vale a maioria das execuções válidas
            (3 repetições). Grava um resumo em JSON.
  comparar  aplica a regra de decisão (regra-de-decisao.md) a dois resumos: a linha de base da
            main e a correção, do mesmo dia.

Formato de cada linha do JSONL de execuções (o executor grava, um objeto por chamada):

  {"id": "p443-01", "repeticao": 1, "http_status": 200, "corpo": {...resposta da API...},
   "data_referencia": "2026-11-07", "falha_tecnica": false}

`corpo` é a resposta de POST /api/assistente/chat (resposta, rota, citacoes, fontesDados,
modoFallback). `data_referencia` é a data da execução no fuso de Brasília; sem ela, vale a do
argumento --data-referencia. Os períodos relativos do conjunto ("mês passado", "último trimestre")
são calculados a partir dela.

Falha técnica (não conta como resposta): HTTP diferente de 200, `modoFallback` verdadeiro,
`falha_tecnica` marcado pelo executor ou código de erro de indisponibilidade no corpo.
"""

from __future__ import annotations

import argparse
import calendar
import datetime as dt
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path

GRUPOS_CORRECAO = {"442": "periodo", "443": "produto"}
GRUPOS_CONTROLE = ("controle-dados", "controle-lei")

CODIGOS_FALHA_TECNICA = {
    "ASSISTENTE_INDISPONIVEL",
    "ASSISTENTE_OCUPADO",
    "ASSISTENTE_TEMPO_ESGOTADO",
    "MODO_FALLBACK",
    "MODELO_INDISPONIVEL",
    "TEMPO_ESGOTADO",
}

# Funções cujo período é um mês (AAAA-MM) e o mês assumido quando o parâmetro falta
# (None = parâmetro obrigatório).
FUNCOES_DE_MES = {"consultarApuracao": None, "vencimentosDoMes": "atual"}
# Funções cujo período é dataInicio/dataFim e o padrão de cada uma quando as datas faltam.
FUNCOES_DE_DATAS = {
    "totalComprasPorInsumo": None,
    "consultarCreditos": None,
    "consultarRetencoes": None,
    "totalVendasPorProduto": "ano_ate_hoje",
    "listarNotas": "noventa_dias",
}

CHAVES_PRODUTO = ("descricaoOuNcm", "produtoOuNcm")

MARCAS_REDUCAO = {
    100: re.compile(r"100\s*%|100 por cento|cem por cento|\bzero\b|zerad|\bisent"),
    60: re.compile(r"\b60\s*%|\b60 por cento|sessenta por cento"),
}
VERBOS_RESSALVA = re.compile(r"confirm|enquadr|confer|valid|verific|cheq|consult")
ARTIGO = re.compile(r"\bart(?:igo)?s?\.?\s*(\d+)")


# ---------------------------------------------------------------------------------------------
# Texto e datas
# ---------------------------------------------------------------------------------------------


def normalizar(texto: object) -> str:
    """Sem acento e em minúsculas, para comparar texto."""
    decomposto = unicodedata.normalize("NFKD", str(texto))
    return "".join(c for c in decomposto if not unicodedata.combining(c)).lower()


def somar_meses(data: dt.date, meses: int) -> dt.date:
    """A mesma data `meses` adiante (negativo = para trás), com o dia limitado ao fim do mês."""
    total = data.year * 12 + (data.month - 1) + meses
    ano, mes = divmod(total, 12)
    mes += 1
    return dt.date(ano, mes, min(data.day, calendar.monthrange(ano, mes)[1]))


def primeiro_dia(data: dt.date) -> dt.date:
    return data.replace(day=1)


def ultimo_dia(data: dt.date) -> dt.date:
    return data.replace(day=calendar.monthrange(data.year, data.month)[1])


def mes_relativo(hoje: dt.date, deslocamento: int) -> dt.date:
    """Primeiro dia do mês `deslocamento` meses a partir do mês de `hoje`."""
    return somar_meses(primeiro_dia(hoje), deslocamento)


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
    return texto[:7] if re.match(r"^\d{4}-\d{2}$", texto[:7]) and len(texto) >= 7 else None


# ---------------------------------------------------------------------------------------------
# Períodos relativos
# ---------------------------------------------------------------------------------------------

DESLOCAMENTO_DE_MES = {
    "mes_corrente": 0,
    "mes_anterior": -1,
    "mes_retrasado": -2,
    "mes_seguinte": 1,
}


def janelas(periodo: str, hoje: dt.date) -> list[tuple[set, set]]:
    """Janelas aceitas para um período relativo: lista de (inícios aceitos, fins aceitos)."""
    ate_hoje = {hoje, hoje - dt.timedelta(days=1)}
    if periodo in DESLOCAMENTO_DE_MES:
        inicio = mes_relativo(hoje, DESLOCAMENTO_DE_MES[periodo])
        fins = {ultimo_dia(inicio)}
        if DESLOCAMENTO_DE_MES[periodo] == 0:
            fins |= ate_hoje
        return [({inicio}, fins)]
    if periodo == "ano_corrente":
        return [({dt.date(hoje.year, 1, 1)}, ate_hoje | {dt.date(hoje.year, 12, 31)})]
    if periodo == "ano_anterior":
        return [({dt.date(hoje.year - 1, 1, 1)}, {dt.date(hoje.year - 1, 12, 31)})]
    if periodo == "trimestre_anterior":
        inicio_trimestre_atual = dt.date(hoje.year, 3 * ((hoje.month - 1) // 3) + 1, 1)
        inicio_civil = somar_meses(inicio_trimestre_atual, -3)
        civil = ({inicio_civil}, {ultimo_dia(somar_meses(inicio_civil, 2))})
        movel = (
            _inicios_moveis(hoje, 3, (90, 91, 92)),
            ate_hoje | {ultimo_dia(mes_relativo(hoje, -1))},
        )
        return [civil, movel]
    if periodo == "ultimos_6_meses":
        return [(
            _inicios_moveis(hoje, 6, (180, 181, 182, 183, 184)),
            ate_hoje | {ultimo_dia(mes_relativo(hoje, -1)), ultimo_dia(hoje)},
        )]
    raise ValueError(f"Período relativo desconhecido: {periodo}")


def _inicios_moveis(hoje: dt.date, meses: int, dias: tuple[int, ...]) -> set:
    mesmo_dia = somar_meses(hoje, -meses)
    inicios = {
        mes_relativo(hoje, -meses),
        mes_relativo(hoje, -(meses - 1)),
        mesmo_dia,
        mesmo_dia + dt.timedelta(days=1),
        mesmo_dia - dt.timedelta(days=1),
    }
    inicios |= {hoje - dt.timedelta(days=d) for d in dias}
    return inicios


def meses_aceitos(periodo: str, hoje: dt.date) -> set:
    """Meses (AAAA-MM) aceitos para um período relativo nas funções de mês."""
    if periodo in DESLOCAMENTO_DE_MES:
        return {mes_relativo(hoje, DESLOCAMENTO_DE_MES[periodo]).strftime("%Y-%m")}
    return set()


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
    texto = texto.strip("{}")
    pares = {}
    for parte in re.split(r",\s*(?=\w+\s*[=:])", texto):
        if "=" in parte or ":" in parte:
            chave, _, valor = re.split(r"([=:])", parte, maxsplit=1)
            pares[chave.strip().strip('"')] = valor.strip().strip('"')
    return pares


def periodo_da_fonte(funcao: str, params: dict, hoje: dt.date):
    """('mes', 'AAAA-MM') ou ('datas', inicio, fim), aplicando o padrão da função; None se não há."""
    if funcao in FUNCOES_DE_MES:
        mes = ler_mes(params.get("mes"))
        if mes is None and FUNCOES_DE_MES[funcao] == "atual" and not params.get("mes"):
            mes = hoje.strftime("%Y-%m")
        return ("mes", mes) if mes else None
    if funcao in FUNCOES_DE_DATAS:
        padrao = FUNCOES_DE_DATAS[funcao]
        inicio = ler_data(params.get("dataInicio"))
        fim = ler_data(params.get("dataFim"))
        if padrao == "ano_ate_hoje":
            fim = fim or (hoje if not params.get("dataFim") else None)
            inicio = inicio or (dt.date(fim.year, 1, 1) if fim and not params.get("dataInicio")
                                else None)
        elif padrao == "noventa_dias":
            fim = fim or (hoje if not params.get("dataFim") else None)
            inicio = inicio or (hoje - dt.timedelta(days=90) if not params.get("dataInicio")
                                else None)
        return ("datas", inicio, fim) if inicio and fim else None
    return None


def _confere_periodo(esperado: dict, periodo, hoje: dt.date) -> bool:
    if "periodo" in esperado:
        if periodo is None:
            return False
        if periodo[0] == "mes":
            return periodo[1] in meses_aceitos(esperado["periodo"], hoje)
        _, inicio, fim = periodo
        return any(inicio in inicios and fim in fins
                   for inicios, fins in janelas(esperado["periodo"], hoje))
    if "mes" in esperado:
        if periodo is None:
            return False
        if periodo[0] == "mes":
            return periodo[1] == esperado["mes"]
        alvo = dt.date.fromisoformat(esperado["mes"] + "-01")
        return periodo[1:] == (alvo, ultimo_dia(alvo))
    if "inicio" in esperado:
        if periodo is None:
            return False
        inicio = dt.date.fromisoformat(esperado["inicio"])
        fim = dt.date.fromisoformat(esperado["fim"])
        if periodo[0] == "mes":
            return (inicio == primeiro_dia(inicio) and fim == ultimo_dia(inicio)
                    and periodo[1] == inicio.strftime("%Y-%m"))
        return periodo[1:] == (inicio, fim)
    return True


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


def confere_fonte(esperado: dict, fonte: dict, hoje: dt.date) -> bool:
    funcao = fonte.get("funcao")
    if funcao not in esperado["funcoes"]:
        return False
    params = parametros(fonte)
    if not _confere_periodo(esperado, periodo_da_fonte(funcao, params, hoje), hoje):
        return False
    if "produto" in esperado and not _confere_produto(esperado["produto"], params):
        return False
    if "tipo" in esperado and str(params.get("tipo", "")).upper() != esperado["tipo"]:
        return False
    if "numero" in esperado:
        try:
            if int(str(params.get("numero")).strip()) != esperado["numero"]:
                return False
        except ValueError:
            return False
    return True


def confere_dados(esperado: dict, corpo: dict, hoje: dt.date) -> bool:
    fontes = corpo.get("fontesDados") or []
    return any(isinstance(f, dict) and confere_fonte(esperado, f, hoje) for f in fontes)


# ---------------------------------------------------------------------------------------------
# Produto e lei
# ---------------------------------------------------------------------------------------------


def frases(texto: str) -> list[str]:
    return [f for f in re.split(r"[.;!?\n]+", texto) if f.strip()]


def tem_ressalva(texto: str) -> bool:
    """Uma frase que fala do contador e de confirmar, conferir ou enquadrar."""
    return any("contador" in f and VERBOS_RESSALVA.search(f) for f in frases(normalizar(texto)))


def confere_produto(esperado: dict, corpo: dict) -> bool:
    texto = normalizar(corpo.get("resposta") or "")
    if not MARCAS_REDUCAO[esperado["reducao"]].search(texto):
        return False
    return tem_ressalva(corpo.get("resposta") or "") if esperado.get("ressalva") else True


def artigos_da_citacao(citacao: object) -> tuple[str, set]:
    """(texto da norma, artigos) de uma citação, preferindo o campo do dispositivo."""
    if isinstance(citacao, dict):
        norma = normalizar(json.dumps(citacao.get("norma", citacao), ensure_ascii=False))
        dispositivo = citacao.get("dispositivo") or citacao.get("artigo")
        fonte = normalizar(dispositivo if dispositivo else json.dumps(citacao, ensure_ascii=False))
    else:
        norma = fonte = normalizar(citacao)
    return norma, {int(n) for n in ARTIGO.findall(fonte)}


def confere_lei(esperado: dict, corpo: dict) -> bool:
    if corpo.get("rota") not in esperado["rotas"]:
        return False
    alvo = set(esperado["artigos"])
    for citacao in corpo.get("citacoes") or []:
        norma, artigos = artigos_da_citacao(citacao)
        if esperado["norma"] in norma and artigos & alvo:
            return True
    return False


# ---------------------------------------------------------------------------------------------
# Execuções e votação
# ---------------------------------------------------------------------------------------------


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
    grupo = pergunta["grupo"]
    esperado = pergunta["esperado"]
    if grupo in ("periodo", "controle-dados"):
        certa = confere_dados(esperado, corpo, hoje)
    elif grupo == "produto":
        certa = confere_produto(esperado, corpo)
    elif grupo == "controle-lei":
        certa = confere_lei(esperado, corpo)
    else:
        raise ValueError(f"Grupo desconhecido: {grupo}")
    return "certa" if certa else "errada"


def carregar_conjunto(caminho: Path) -> dict:
    """Lê o conjunto e confere os bytes com o .sha256 ao lado, quando existe."""
    conteudo = caminho.read_bytes()
    hash_lido = hashlib.sha256(conteudo).hexdigest()
    arquivo_hash = caminho.with_name(caminho.name + ".sha256")
    if arquivo_hash.exists():
        esperado = arquivo_hash.read_text(encoding="utf-8").split()[0]
        if esperado != hash_lido:
            raise SystemExit(f"Conjunto alterado: sha256 {hash_lido}, .sha256 diz {esperado}")
    conjunto = json.loads(conteudo.decode("utf-8"))
    conjunto["_sha256"] = hash_lido
    return conjunto


def ler_execucoes(caminho: Path) -> list[dict]:
    registros = []
    with caminho.open(encoding="utf-8") as arquivo:
        for linha in arquivo:
            if linha.strip():
                registros.append(json.loads(linha))
    return registros


def pontuar(conjunto: dict, registros: list[dict], data_padrao: dt.date | None) -> dict:
    perguntas = {p["id"]: p for p in conjunto["perguntas"]}
    por_pergunta = {i: {"grupo": p["grupo"], "execucoes": []} for i, p in perguntas.items()}
    datas = set()
    for registro in registros:
        pergunta = perguntas.get(registro.get("id"))
        if pergunta is None:
            raise SystemExit(f"Execução de pergunta fora do conjunto: {registro.get('id')}")
        hoje = ler_data(registro.get("data_referencia")) or data_padrao
        if hoje is None:
            raise SystemExit("Sem data de referência: use --data-referencia ou o campo da linha")
        datas.add(hoje.isoformat())
        por_pergunta[pergunta["id"]]["execucoes"].append(classificar(pergunta, registro, hoje))

    por_grupo: dict = {}
    for ident, item in por_pergunta.items():
        execucoes = item["execucoes"]
        validas = [e for e in execucoes if e != "tecnica"]
        certas = validas.count("certa")
        if not validas:
            resultado = "sem_medicao"
        else:
            resultado = "certa" if 2 * certas > len(validas) else "errada"
        item.update(resultado=resultado, certas=certas, validas=len(validas),
                    tecnicas=len(execucoes) - len(validas))
        grupo = por_grupo.setdefault(item["grupo"], {"certas": 0, "total": 0, "sem_medicao": 0})
        grupo["total"] += 1
        grupo["certas"] += resultado == "certa"
        grupo["sem_medicao"] += resultado == "sem_medicao"

    return {
        "versao_conjunto": conjunto.get("versao"),
        "sha256_conjunto": conjunto["_sha256"],
        "datas_referencia": sorted(datas),
        "por_grupo": por_grupo,
        "por_pergunta": por_pergunta,
    }


# ---------------------------------------------------------------------------------------------
# Regra de decisão
# ---------------------------------------------------------------------------------------------


def comparar(base: dict, candidato: dict, issue: str) -> dict:
    """Aplica a regra: o grupo da correção sobe e nenhum controle passa de certa a errada."""
    if issue not in GRUPOS_CORRECAO:
        raise SystemExit(f"Issue sem grupo no conjunto: {issue}")
    problemas = []
    if base["sha256_conjunto"] != candidato["sha256_conjunto"]:
        problemas.append("os dois resumos são de conjuntos diferentes")
    if base["datas_referencia"] != candidato["datas_referencia"] or \
            len(base["datas_referencia"]) != 1:
        problemas.append("as rodadas não são do mesmo dia (uma data de referência só, a mesma)")

    grupo = GRUPOS_CORRECAO[issue]
    certas_base = base["por_grupo"].get(grupo, {}).get("certas", 0)
    certas_cand = candidato["por_grupo"].get(grupo, {}).get("certas", 0)
    pioras = sorted(
        ident for ident, item in base["por_pergunta"].items()
        if item["grupo"] in GRUPOS_CONTROLE and item["resultado"] == "certa"
        and candidato["por_pergunta"].get(ident, {}).get("resultado") != "certa")
    sem_medicao = sorted(
        ident for ident, item in candidato["por_pergunta"].items()
        if item["resultado"] == "sem_medicao")
    if sem_medicao:
        problemas.append("perguntas sem execução válida na correção: " + ", ".join(sem_medicao))

    entra = not problemas and certas_cand > certas_base and not pioras
    return {
        "issue": issue,
        "grupo": grupo,
        "certas_base": certas_base,
        "certas_correcao": certas_cand,
        "controles_que_pioraram": pioras,
        "problemas": problemas,
        "decisao": "ENTRA" if entra else "NAO_ENTRA",
    }


# ---------------------------------------------------------------------------------------------
# Linha de comando
# ---------------------------------------------------------------------------------------------


def _imprimir_resumo(resumo: dict) -> None:
    print(f"Conjunto {resumo['versao_conjunto']} ({resumo['sha256_conjunto'][:12]}), "
          f"data(s) {', '.join(resumo['datas_referencia'])}")
    for grupo, item in sorted(resumo["por_grupo"].items()):
        print(f"  {grupo:15} {item['certas']:2}/{item['total']:2} certas"
              + (f" ({item['sem_medicao']} sem medição)" if item["sem_medicao"] else ""))
    for ident, item in sorted(resumo["por_pergunta"].items()):
        print(f"  {ident} {item['grupo']:15} {item['resultado']:12} "
              f"{item['certas']}/{item['validas']} válidas, {item['tecnicas']} técnicas")


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    comandos = analisador.add_subparsers(dest="comando", required=True)

    p = comandos.add_parser("pontuar", help="pontua um JSONL de execuções")
    p.add_argument("--perguntas", type=Path, required=True)
    p.add_argument("--execucoes", type=Path, required=True)
    p.add_argument("--data-referencia", type=dt.date.fromisoformat)
    p.add_argument("--saida", type=Path)

    c = comandos.add_parser("comparar", help="aplica a regra de decisão a dois resumos")
    c.add_argument("--base", type=Path, required=True)
    c.add_argument("--correcao", type=Path, required=True)
    c.add_argument("--issue", choices=sorted(GRUPOS_CORRECAO), required=True)

    args = analisador.parse_args(argv)
    if args.comando == "pontuar":
        resumo = pontuar(carregar_conjunto(args.perguntas), ler_execucoes(args.execucoes),
                         args.data_referencia)
        _imprimir_resumo(resumo)
        if args.saida:
            args.saida.write_text(json.dumps(resumo, ensure_ascii=False, indent=2) + "\n",
                                  encoding="utf-8")
        return 0
    base = json.loads(args.base.read_text(encoding="utf-8"))
    correcao = json.loads(args.correcao.read_text(encoding="utf-8"))
    decisao = comparar(base, correcao, args.issue)
    print(json.dumps(decisao, ensure_ascii=False, indent=2))
    return 0 if decisao["decisao"] == "ENTRA" else 1


if __name__ == "__main__":
    sys.exit(main())
