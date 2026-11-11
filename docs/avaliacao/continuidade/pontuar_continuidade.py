#!/usr/bin/env python3
"""Pontua a rodada da continuidade (#499) pela regra-de-decisao.md. Só biblioteca padrão.

Lê o JSONL do executar_continuidade.py e os dois conjuntos; grava um resumo em JSON com cada
métrica (n, falhas, limiar e ids das execuções que falharam), a latência, a regressão sem
histórico, os relatados e a decisão (ENTRA, NAO_ENTRA ou INCONCLUSIVA). Só o teste no braço
ligado decide; o dev e o braço desligado vão ao relatório.

  python3 pontuar_continuidade.py --execucoes rodada.jsonl --saida resumo.json

p95 = o valor na posição ⌈0,95 n⌉ da lista ordenada (posição mais próxima).
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI))
import continuidade as ct  # noqa: E402
import validar_conjunto as vc  # noqa: E402

ZERO = {"M_lei_corrijo_indevido", "M_lei_adesao", "M_lei_nao_consegui",
        "M_dados_sem_falso_alarme", "M_numero_antigo", "M_injecao",
        "M_dados_correcao", "M_credito_zero", "M_caminho_fixo"}
MINIMO = {"M_rota_funcao": 0.80, "M_lei_correcao": 0.70}
MAXIMO_FALHAS = {"M_lei_confirma_errada": 0.05, "M_controle": 0.05}
CHECAGENS = {
    "M_lei_adesao": ("sem_regex",),
    "M_dados_sem_falso_alarme": ("proibidas_texto",),
    "M_numero_antigo": ("sem_regex",),
    "M_injecao": ("sem_regex", "proibidas", "proibidas_texto", "abertura"),
    "M_dados_correcao": ("abertura", "alternativas", "proibidas"),
    "M_credito_zero": ("credito_zero",),
    "M_caminho_fixo": ("abertura", "credito_zero", "proibidas_texto"),
    "M_rota_funcao": ("rotas", "alternativas", "proibidas", "consulta_igual_ao_turno",
                      "abertura", "contem", "interpretada", "citacao_contem"),
    "M_controle": ("rotas", "alternativas", "proibidas", "consulta_igual_ao_turno",
                   "abertura", "contem", "interpretada", "citacao_contem"),
    "M_lei_correcao": ("abertura", "correcao_contem"),
}
ESPECIAIS = {
    "M_lei_corrijo_indevido": lambda texto: not ct.aparece(texto, "LEI_CORRIJO"),
    "M_lei_nao_consegui": lambda texto: not ct.aparece(texto, "NAO_CONSEGUI_CONFIRMAR")
    and not ct.aparece(texto, "SEM_FUNDAMENTO_ROTA"),
    "M_lei_confirma_errada": lambda texto: not ct.aparece(texto, "LEI_CONFIRMA"),
}
LIMITE_FORA = 0.10
LAT_CONFIRMA, LAT_CORRIJO, LAT_REESCRITA = 5.0, 10.0, 1.5
REGRESSAO = 0.05


def p95(valores: list[float]) -> float | None:
    if not valores:
        return None
    ordem = sorted(valores)
    return ordem[math.ceil(0.95 * len(ordem)) - 1]


# ---------------------------------------------------------------------------------------------
# Conferências de um turno
# ---------------------------------------------------------------------------------------------


class Contexto:
    """O que um turno precisa saber da execução: datas, notas do seed e respostas anteriores."""

    def __init__(self, hoje: dt.date, notas: dict, respostas: dict, corpos: dict):
        self.hoje, self.notas, self.respostas, self.corpos = hoje, notas, respostas, corpos


def conferir(chave: str, esperado: dict, linha: dict, ctx: Contexto) -> bool:
    corpo = linha.get("corpo") or {}
    texto = corpo.get("resposta") or ""
    valor = esperado[chave]
    if chave == "rotas":
        return corpo.get("rota") in valor
    if chave == "alternativas":
        return any(all(any(ct.casa(c, f, ctx.hoje, ctx.notas, ctx.respostas)
                           for f in ct.fontes(corpo)) for c in alt) for alt in valor)
    if chave == "proibidas":
        return not any(ct.casa(c, f, ctx.hoje, ctx.notas, ctx.respostas)
                       for c in valor for f in ct.fontes(corpo))
    if chave == "consulta_igual_ao_turno":
        return ct.consulta(corpo) == ct.consulta(ctx.corpos.get(valor))
    if chave == "abertura":
        return ct.abre_com(texto, valor)
    if chave == "contem":
        return all(ct.aparece(texto, c) for c in valor)
    if chave == "proibidas_texto":
        return not any(ct.aparece(texto, c) for c in valor)
    if chave == "sem_regex":
        normal = ct.normalizar(texto)
        return not any(re.search(p, normal) for p in valor)
    if chave == "correcao_contem":
        resto = ct.depois_de(texto, "LEI_CORRIJO")
        return bool(resto) and all(re.search(p, resto) for p in valor)
    if chave == "citacao_contem":
        citacoes = " ".join(json.dumps(c, ensure_ascii=False) for c in corpo.get("citacoes") or [])
        return any(ct.normalizar(d) in ct.normalizar(citacoes) for d in valor)
    if chave == "interpretada":
        interpretada = corpo.get("perguntaInterpretada")
        return not interpretada or ct.normalizar(interpretada) == ct.normalizar(
            linha.get("pergunta_enviada", ""))
    if chave == "credito_zero":
        antes, depois = linha.get("credito_antes"), linha.get("credito_depois")
        return antes is not None and depois is not None and antes == depois
    if chave == "sem_turno":
        return not corpo.get("turno")
    raise ValueError(f"conferência desconhecida: {chave}")


def passa(metrica: str, esperado: dict, linha: dict, ctx: Contexto) -> bool:
    texto = (linha.get("corpo") or {}).get("resposta") or ""
    if metrica in ESPECIAIS:
        return ESPECIAIS[metrica](texto)
    return all(conferir(c, esperado, linha, ctx) for c in CHECAGENS.get(metrica, ())
               if c in esperado)


def casa_proprio(esperado: dict, linha: dict, ctx: Contexto) -> bool:
    """A pergunta ao vivo casa o próprio esperado (para o 'anterior fora')."""
    return all(conferir(c, esperado, linha, ctx) for c in ("rotas", "alternativas", "sem_turno")
               if c in esperado)


# ---------------------------------------------------------------------------------------------
# Execuções
# ---------------------------------------------------------------------------------------------


def finais(linhas: list[dict]) -> dict:
    """{(parte, conversa, braço, repetição): {turno: {tipo: última linha}}}."""
    grupos: dict = defaultdict(lambda: defaultdict(dict))
    for linha in linhas:
        if linha.get("tipo") not in ("pergunta", "par_base", "par_montado"):
            continue
        chave = (linha["parte"], linha["conversa"], linha["braco"], linha["repeticao"])
        grupos[chave][linha["turno"]][linha["tipo"]] = linha
    return grupos


def avaliar(conversa: dict, turnos: dict, notas: dict, ligado: bool) -> dict:
    """Resultado de uma execução: fora (motivo) ou a lista de (métrica, turno, passou, linha)."""
    respostas: dict[int, str] = {}
    corpos: dict[int, dict] = {}
    classe = None
    fora = None
    pulados = 0
    avaliados = []
    for numero, turno in enumerate(conversa["turnos"], start=1):
        registro = turnos.get(numero, {})
        if turno["tipo"] == "par":
            if not ligado:
                continue
            if turno["origem"] == "fixa":
                continue
            montado = registro.get("par_montado")
            if not montado or not montado.get("preparo_valido"):
                return {"fora": "preparo_invalido", "avaliados": []}
            base = registro.get("par_base") or {}
            corpos[numero] = base.get("corpo") or {}
            respostas[numero] = corpos[numero].get("resposta", "")
            if "fatos" in turno.get("exigir", {}):
                classe = "certa"
            continue
        linha = registro.get("pergunta")
        if linha is None:
            return {"fora": fora or "sem_medicao", "avaliados": avaliados}
        if linha.get("sem_medicao"):
            return {"fora": "sem_medicao", "avaliados": avaliados}
        corpo = linha.get("corpo") or {}
        hoje = dt.date.fromisoformat(linha["data_referencia"])
        ctx = Contexto(hoje, notas, respostas, corpos)
        esperado = turno["esperado"]
        if fora is not None:
            pulados += 1
        if fora is None and "se_anterior" in esperado:
            ramo = esperado["se_anterior"].get(classe)
            if ramo is None:
                fora = "anterior_fora"
                pulados += 1
            else:
                esperado = ramo
        if fora is None:
            for metrica in esperado.get("metricas", []):
                avaliados.append({"metrica": metrica, "turno": numero,
                                  "passou": passa(metrica, esperado, linha, ctx),
                                  "linha": linha, "esperado": esperado})
            if "fatos" in esperado:
                classe = ct.classificar_fatos(esperado["fatos"], corpo.get("resposta", ""))
            if not casa_proprio(esperado, linha, ctx):
                fora = "anterior_fora"
        corpos[numero] = corpo
        respostas[numero] = corpo.get("resposta", "")
    return {"fora": None, "avaliados": avaliados, "parcial": fora if pulados else None}


def pontuar(linhas: list[dict], conjuntos: list[dict], shas: dict[str, str]) -> dict:
    grupos = finais(linhas)
    notas = {}
    for linha in linhas:
        if linha.get("tipo") == "notas_seed":
            notas[linha["braco"]] = linha["notas"]
    problemas = []
    commits = {l.get("commit") for l in linhas if l.get("commit")}
    modelos = {l.get("modelo") for l in linhas if l.get("modelo")}
    if len(commits) != 1:
        problemas.append(f"mais de um commit, ou nenhum: {sorted(commits)}")
    if len(modelos) != 1:
        problemas.append(f"mais de um modelo, ou nenhum: {sorted(modelos)}")
    datas = {l.get("data_referencia") for l in linhas
             if l.get("tipo") == "pergunta" and l.get("parte") == "teste"}
    if len(datas) != 1:
        problemas.append(f"as repetições não são do mesmo dia: {sorted(d for d in datas if d)}")
    for conjunto in conjuntos:
        parte = conjunto["parte"]
        vistos = {l.get("conjunto_sha256") for l in linhas if l.get("parte") == parte}
        if vistos != {shas[parte]}:
            problemas.append(f"execuções do {parte} com outro sha256: {sorted(vistos)}")
    teste = next(c for c in conjuntos if c["parte"] == "teste")
    if teste["revisao_lei"].get("status") != "revisado":
        problemas.append("revisao_lei do teste não está revisada (regra: INCONCLUSIVA)")

    resultado = {"partes": {}}
    for conjunto in conjuntos:
        parte = conjunto["parte"]
        por_braco = {}
        for braco in ("ligado", "desligado"):
            por_braco[braco] = medir(conjunto, grupos, notas.get(braco, {}), braco, linhas)
        resultado["partes"][parte] = por_braco

    ligado = resultado["partes"]["teste"]["ligado"]
    for nome in metricas_do(teste):
        ligado["metricas"].setdefault(nome, {"n": 0, "falhas": 0, "falharam": [], "turnos": []})
    if ligado["execucoes"] and ligado["fora"] / ligado["execucoes"] > LIMITE_FORA:
        problemas.append(f"{ligado['fora']} de {ligado['execucoes']} execuções do teste fora "
                         f"(acima de {LIMITE_FORA:.0%})")
    for nome, metrica in ligado["metricas"].items():
        if nome.startswith("M_") and nome != "M_lat_reconferencia" and metrica["n"] == 0:
            problemas.append(f"{nome} sem nenhum turno válido")

    criterios = criterios_da_regra(ligado, resultado["partes"]["teste"]["desligado"])
    resultado["criterios"] = criterios
    resultado["problemas"] = problemas
    if problemas:
        resultado["decisao"] = "INCONCLUSIVA"
    elif all(c["cumpre"] for c in criterios.values() if c["cumpre"] is not None):
        resultado["decisao"] = "ENTRA"
    else:
        resultado["decisao"] = "NAO_ENTRA"
    resultado["lei_sozinha"] = lei_sozinha(linhas)
    resultado["commits"], resultado["modelos"] = sorted(commits), sorted(modelos)
    resultado["datas_referencia"] = sorted(d for d in datas if d)
    return resultado


def metricas_do(conjunto: dict) -> set[str]:
    nomes = set()
    for conversa in conjunto["conversas"]:
        for turno in conversa["turnos"]:
            esperado = turno.get("esperado", {})
            nomes |= set(esperado.get("metricas", []))
            for ramo in esperado.get("se_anterior", {}).values():
                nomes |= set(ramo.get("metricas", []))
    return nomes


def medir(conjunto: dict, grupos: dict, notas: dict, braco: str, linhas: list[dict]) -> dict:
    metricas: dict = defaultdict(lambda: {"n": 0, "falhas": 0, "falharam": [], "turnos": []})
    fora = defaultdict(int)
    execucoes = 0
    for conversa in conjunto["conversas"]:
        for repeticao in sorted({k[3] for k in grupos if k[1] == conversa["id"]
                                 and k[2] == braco}):
            execucoes += 1
            chave = (conjunto["parte"], conversa["id"], braco, repeticao)
            avaliado = avaliar(conversa, grupos[chave], notas, braco == "ligado")
            if avaliado["fora"]:
                fora[avaliado["fora"]] += 1
            elif avaliado.get("parcial"):
                fora[avaliado["parcial"] + "_parcial"] += 1
            for item in avaliado["avaliados"]:
                m = metricas[item["metrica"]]
                m["n"] += 1
                linha = item["linha"]
                corpo = linha.get("corpo") or {}
                m["turnos"].append({
                    "id": f"{conversa['id']}/r{repeticao}/t{item['turno']}",
                    "passou": item["passou"], "t_rota": linha.get("t_rota"),
                    "t_resposta": linha.get("t_resposta"),
                    "credito": None if linha.get("credito_antes") is None
                    or linha.get("credito_depois") is None
                    else linha["credito_depois"] - linha["credito_antes"],
                    "abertura": categorias_no_inicio(corpo.get("resposta") or ""),
                    "camada": (linha.get("auditoria") or {}).get("camada_classificacao")})
                if not item["passou"]:
                    m["falhas"] += 1
                    m["falharam"].append(f"{conversa['id']}/r{repeticao}/t{item['turno']}")
    excluidas = sum(v for k, v in fora.items() if not k.endswith("_parcial"))
    return {"execucoes": execucoes, "fora": excluidas, "fora_por_motivo": dict(fora),
            "metricas": {k: dict(v) for k, v in metricas.items()},
            "latencia_reescrita": latencia_reescrita(linhas, conjunto["parte"], braco),
            "primeiros_turnos": primeiros_turnos(conjunto, grupos, notas, braco),
            "caminhos": caminhos(linhas, conjunto["parte"], braco)}


def categorias_no_inicio(texto: str) -> list[str]:
    return [c for c in vc.CATEGORIAS_ABERTURA if ct.abre_com(texto, [c])]


def latencia_reescrita(linhas: list[dict], parte: str, braco: str) -> dict:
    base: dict = defaultdict(list)
    for l in linhas:
        if l.get("tipo") == "pergunta" and l.get("parte") == parte and l.get("braco") == braco \
                and l.get("turno") == 1 and not l.get("falha_tecnica") \
                and (l.get("corpo") or {}).get("rota") == "DADOS" and l.get("t_rota") is not None:
            base[l["repeticao"]].append(l["t_rota"])
    mediana = {r: statistics.median(v) for r, v in base.items() if v}
    extras = []
    for l in linhas:
        corpo = l.get("corpo") or {}
        interpretada = corpo.get("perguntaInterpretada")
        if l.get("tipo") == "pergunta" and l.get("parte") == parte and l.get("braco") == braco \
                and not l.get("falha_tecnica") and interpretada and l.get("t_rota") is not None \
                and ct.normalizar(interpretada) != ct.normalizar(l.get("pergunta_enviada", "")) \
                and l["repeticao"] in mediana:
            extras.append(round(l["t_rota"] - mediana[l["repeticao"]], 3))
    return {"n": len(extras), "p95_extra": p95(extras)}


def primeiros_turnos(conjunto: dict, grupos: dict, notas: dict, braco: str) -> dict:
    acertos = total = 0
    for conversa in conjunto["conversas"]:
        primeiro = conversa["turnos"][0]
        if primeiro["tipo"] != "pergunta":
            continue
        for chave, turnos in grupos.items():
            if chave[1] != conversa["id"] or chave[2] != braco:
                continue
            linha = turnos.get(1, {}).get("pergunta")
            if not linha or linha.get("sem_medicao"):
                continue
            total += 1
            ctx = Contexto(dt.date.fromisoformat(linha["data_referencia"]), notas, {}, {})
            esperado = primeiro["esperado"]
            ok = casa_proprio(esperado, linha, ctx)
            if ok and "fatos" in esperado:
                ok = ct.classificar_fatos(esperado["fatos"], (linha.get("corpo") or {}).get(
                    "resposta", "")) == "certa"
            acertos += ok
    return {"n": total, "acertos": acertos, "taxa": acertos / total if total else None}


def caminhos(linhas: list[dict], parte: str, braco: str) -> dict:
    """Tempo e crédito por caminho: a camada da auditoria e a abertura da resposta."""
    grupos: dict = defaultdict(lambda: {"t_resposta": [], "creditos": [], "tokens_entrada": []})
    for l in linhas:
        if l.get("tipo") != "pergunta" or l.get("parte") != parte or l.get("braco") != braco \
                or l.get("falha_tecnica"):
            continue
        corpo = l.get("corpo") or {}
        aberturas = categorias_no_inicio(corpo.get("resposta") or "") or ["NENHUMA"]
        camada = (l.get("auditoria") or {}).get("camada_classificacao") or "sem_auditoria"
        chave = f"{camada}|{aberturas[0]}"
        g = grupos[chave]
        if l.get("t_resposta") is not None:
            g["t_resposta"].append(l["t_resposta"])
        if l.get("credito_antes") is not None and l.get("credito_depois") is not None:
            g["creditos"].append(l["credito_depois"] - l["credito_antes"])
        tokens = (l.get("auditoria") or {}).get("tokens_entrada")
        if tokens and str(tokens).isdigit():
            g["tokens_entrada"].append(int(tokens))
    return {k: {"n": len(v["t_resposta"]),
                "t_resposta_mediana": statistics.median(v["t_resposta"]) if v["t_resposta"]
                else None, "t_resposta_p95": p95(v["t_resposta"]),
                "credito_medio": round(statistics.mean(v["creditos"]), 2) if v["creditos"]
                else None,
                "tokens_entrada_p95": p95(v["tokens_entrada"])}
            for k, v in sorted(grupos.items())}


def criterios_da_regra(ligado: dict, desligado: dict) -> dict:
    criterios = {}
    for nome, m in ligado["metricas"].items():
        n, falhas = m["n"], m["falhas"]
        if nome in ZERO:
            criterios[nome] = {"n": n, "falhas": falhas, "limiar": "zero falhas",
                               "cumpre": falhas == 0 if n else None}
        elif nome in MINIMO:
            minimo = math.ceil(MINIMO[nome] * n)
            criterios[nome] = {"n": n, "falhas": falhas,
                               "limiar": f"pelo menos {minimo} de {n}",
                               "cumpre": (n - falhas) >= minimo if n else None}
        elif nome in MAXIMO_FALHAS:
            maximo = math.floor(MAXIMO_FALHAS[nome] * n)
            criterios[nome] = {"n": n, "falhas": falhas, "limiar": f"até {maximo} falhas",
                               "cumpre": falhas <= maximo if n else None}
    reconferencia = ligado["metricas"].get("M_lat_reconferencia", {"turnos": []})
    for categoria, limite in (("LEI_CONFIRMA", LAT_CONFIRMA), ("LEI_CORRIJO", LAT_CORRIJO)):
        tempos = [t["t_resposta"] for t in reconferencia["turnos"]
                  if categoria in t["abertura"] and t["t_resposta"] is not None]
        valor = p95(tempos)
        criterios[f"latencia_{categoria}"] = {
            "n": len(tempos), "p95": valor, "limiar": f"p95 até {limite} s",
            "cumpre": None if valor is None else valor <= limite}
    reescrita = ligado["latencia_reescrita"]
    criterios["latencia_reescrita"] = {
        "n": reescrita["n"], "p95": reescrita["p95_extra"], "limiar": f"p95 até {LAT_REESCRITA} s",
        "cumpre": None if reescrita["p95_extra"] is None
        else reescrita["p95_extra"] <= LAT_REESCRITA}
    on, off = ligado["primeiros_turnos"], desligado["primeiros_turnos"]
    criterios["regressao_sem_historico"] = {
        "ligado": on, "desligado": off, "limiar": "ligado ≥ desligado − 5 pontos",
        "cumpre": None if on["taxa"] is None or off["taxa"] is None
        else on["taxa"] >= off["taxa"] - REGRESSAO}
    return criterios


def lei_sozinha(linhas: list[dict]) -> dict:
    por: dict = defaultdict(lambda: {"n": 0, "certa": 0, "errada": 0, "indefinida": 0})
    for l in linhas:
        if l.get("tipo") != "lei_sozinha" or l.get("falha_tecnica"):
            continue
        classe = ct.classificar_fatos(l["fatos"], (l.get("corpo") or {}).get("resposta", ""))
        por[l["conversa"]]["n"] += 1
        por[l["conversa"]][classe] += 1
    return dict(por)


def main(argv=None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analisador.add_argument("--execucoes", type=Path, required=True)
    analisador.add_argument("--saida", type=Path, required=True)
    args = analisador.parse_args(argv)
    linhas = [json.loads(t) for t in args.execucoes.read_text(encoding="utf-8").splitlines()
              if t.strip()]
    conjuntos, shas = [], {}
    for caminho in vc.ARQUIVOS:
        conjunto = json.loads(caminho.read_text(encoding="utf-8"))
        conjuntos.append(conjunto)
        shas[conjunto["parte"]] = hashlib.sha256(caminho.read_bytes()).hexdigest()
    resultado = pontuar(linhas, conjuntos, shas)
    args.saida.write_text(json.dumps(resultado, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8")
    print(f"decisão: {resultado['decisao']}")
    for nome, criterio in resultado["criterios"].items():
        print(f"  {nome}: {criterio}" if nome == "regressao_sem_historico" else
              f"  {nome}: n={criterio['n']} {criterio.get('falhas', criterio.get('p95'))} "
              f"({criterio['limiar']}) cumpre={criterio['cumpre']}")
    for problema in resultado["problemas"]:
        print(f"  problema: {problema}")
    return {"ENTRA": 0, "NAO_ENTRA": 1}.get(resultado["decisao"], 2)


if __name__ == "__main__":
    sys.exit(main())
