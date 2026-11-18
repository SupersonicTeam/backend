#!/usr/bin/env python3
"""Pontua a rodada da #581 (falso positivo do "corrijo" nos dados da continuidade) pela
regra-de-decisao-581.md. Só biblioteca padrão.

Lê o JSONL do executar_continuidade.py (`--conjunto 581`, só o braço ligado) e o conjunto; grava um
resumo em JSON com os números de cada classe, o veredito e os ids das execuções que pesam.

  python3 pontuar_581.py --execucoes rodada.jsonl --saida resumo-581.json

O gabarito é a função e os parâmetros da consulta (a gramática do `casa` da #499), nunca um número
escrito à mão: "valor certo" = a consulta da confirmação casa as `alternativas` do conjunto.

Não altera o pontuar_continuidade.py nem as métricas da #499: importa os pedaços comuns dele.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI))
import continuidade as ct  # noqa: E402
import pontuar_continuidade as pc  # noqa: E402
import validar_conjunto as vc  # noqa: E402

VERSAO_REGRA = "2026-11-18.1"
# Limiares PROPOSTOS, a aprovar pelo Murillo antes da rodada (regra-de-decisao-581.md, seção 6).
LIMITE_FORA = 0.10          # execuções fora (preparo inválido, sem medição, confirmação fora da rota)
EXERCITADO_MINIMO = 0.30    # núcleo: pelo menos 30% passam pelo ramo em que o modelo refaz a consulta
VP_MINIMO = 0.80            # controle do verdadeiro positivo: corrige para melhor em pelo menos 80%
RISCO_TAXA = 0.05           # núcleo: "corrigiu para pior" acima de 5% das execuções é risco relevante
RISCO_REPRODUZIDO = 2       # ... ou a mesma conversa corrigindo para pior em 2 repetições ou mais
RISCO_CONVERSAS = 2         # ... ou 2 conversas distintas com pelo menos uma ocorrência

# Aberturas em que o backend não repetiu a consulta gravada: o modelo escolheu a consulta de novo,
# o que só acontece quando a régua de texto leu a consulta antiga como incoerente com a pergunta.
ABERTURAS_VIA_MODELO = ("CORRIJO_DADOS", "REFIZ", "REFIZ_COM", "CONFERI", "CONFERI_MESMO")

CLASSES = {"M581_nucleo": "nucleo", "M581_controle_claro": "claro", "M581_controle_vp": "vp"}


def wilson_superior(falhas: int, n: int, z: float = 1.96) -> float | None:
    """Limite superior do intervalo de Wilson (95%) da taxa de `falhas` em `n`."""
    if n == 0:
        return None
    p = falhas / n
    centro = p + z * z / (2 * n)
    margem = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return min(1.0, (centro + margem) / (1 + z * z / n))


def classe_da(conversa: dict) -> str:
    return CLASSES[vc.CATEGORIA_METRICA_581[conversa["categoria"]]]


def avaliar_execucao(conversa: dict, turnos: dict, notas: dict) -> dict:
    """Uma execução (conversa, repetição) do braço ligado: fora (motivo) ou o resultado."""
    registro_par = turnos.get(1, {})
    montado = registro_par.get("par_montado")
    if not montado or not montado.get("preparo_valido"):
        return {"fora": "preparo_invalido"}
    base = (registro_par.get("par_base") or {}).get("corpo") or {}
    linha = turnos.get(2, {}).get("pergunta")
    if linha is None or linha.get("sem_medicao"):
        return {"fora": "sem_medicao"}
    corpo = linha.get("corpo") or {}
    esperado = conversa["turnos"][1]["esperado"]
    contexto = pc.Contexto(dt.date.fromisoformat(linha["data_referencia"]), notas,
                           {1: base.get("resposta", "")}, {1: base})
    if corpo.get("rota") not in esperado["rotas"]:
        return {"fora": "confirmacao_fora_da_rota", "rota": corpo.get("rota")}
    texto = corpo.get("resposta") or ""
    aberturas = pc.categorias_no_inicio(texto)
    corrigiu = "CORRIJO_DADOS" in aberturas
    tem_fonte = bool(ct.fontes(corpo))
    certa = tem_fonte and pc.conferir("alternativas", esperado, linha, contexto) and (
        "proibidas" not in esperado or pc.conferir("proibidas", esperado, linha, contexto))
    consulta_nova, consulta_base = ct.consulta(corpo), ct.consulta(base)
    via_modelo = bool(set(aberturas) & set(ABERTURAS_VIA_MODELO)) or (
        tem_fonte and consulta_nova != consulta_base)
    interpretada = corpo.get("perguntaInterpretada")
    reescrita = bool(interpretada) and ct.normalizar(interpretada) != ct.normalizar(
        linha.get("pergunta_enviada", ""))
    if classe_da(conversa) == "vp":
        resultado = ("corrigiu_para_melhor" if corrigiu and certa
                     else "corrigiu_errado" if corrigiu else "nao_corrigiu")
    elif corrigiu:
        resultado = "corrigiu_equivalente" if certa else "corrigiu_para_pior"
    else:
        resultado = ("manteve_certo" if certa
                     else "refeito_para_pior" if tem_fonte else "sem_consulta")
    return {"fora": None, "resultado": resultado, "corrigiu": corrigiu, "certa": certa,
            "via_modelo": via_modelo, "reescrita": reescrita, "aberturas": aberturas,
            "consulta_nova": consulta_nova, "consulta_anterior": consulta_base,
            "data": linha["data_referencia"]}


def medir(conjunto: dict, grupos: dict, notas: dict) -> dict:
    """Todas as execuções do braço ligado, agrupadas por classe."""
    classes: dict = {nome: {"n": 0, "por_resultado": defaultdict(int), "por_conversa": {},
                            "por_categoria": defaultdict(lambda: defaultdict(int)),
                            "por_mecanismo": defaultdict(lambda: defaultdict(int)),
                            "ids": defaultdict(list), "via_modelo": 0, "corrigiu": 0,
                            "corrigiu_com_reescrita": 0, "corrigiu_por_dia": defaultdict(int),
                            "detalhes": []}
                      for nome in CLASSES.values()}
    fora: dict = defaultdict(list)
    execucoes = 0
    for conversa in conjunto["conversas"]:
        repeticoes = sorted({k[3] for k in grupos
                             if k[1] == conversa["id"] and k[2] == "ligado"})
        for repeticao in repeticoes:
            execucoes += 1
            ident = f"{conversa['id']}/r{repeticao}"
            avaliada = avaliar_execucao(
                conversa, grupos[(conjunto["parte"], conversa["id"], "ligado", repeticao)], notas)
            if avaliada["fora"]:
                fora[avaliada["fora"]].append(ident)
                continue
            c = classes[classe_da(conversa)]
            c["n"] += 1
            c["por_resultado"][avaliada["resultado"]] += 1
            c["ids"][avaliada["resultado"]].append(ident)
            por = c["por_conversa"].setdefault(conversa["id"], defaultdict(int))
            por["execucoes"] += 1
            por[avaliada["resultado"]] += 1
            c["por_categoria"][conversa["categoria"]]["execucoes"] += 1
            c["por_categoria"][conversa["categoria"]][avaliada["resultado"]] += 1
            c["por_mecanismo"][conversa.get("mecanismo")]["execucoes"] += 1
            c["por_mecanismo"][conversa.get("mecanismo")][avaliada["resultado"]] += 1
            c["via_modelo"] += avaliada["via_modelo"]
            if avaliada["corrigiu"]:
                c["corrigiu"] += 1
                c["corrigiu_com_reescrita"] += avaliada["reescrita"]
                c["corrigiu_por_dia"][avaliada["data"]] += 1
            if avaliada["resultado"] in ("corrigiu_para_pior", "corrigiu_equivalente",
                                         "refeito_para_pior", "corrigiu_errado"):
                c["detalhes"].append({"id": ident, "resultado": avaliada["resultado"],
                                      "aberturas": avaliada["aberturas"],
                                      "consulta_anterior": avaliada["consulta_anterior"],
                                      "consulta_nova": avaliada["consulta_nova"]})
    saida = {}
    for nome, c in classes.items():
        c["por_resultado"] = dict(c["por_resultado"])
        c["por_conversa"] = {k: dict(v) for k, v in sorted(c["por_conversa"].items())}
        c["por_categoria"] = {k: dict(v) for k, v in sorted(c["por_categoria"].items())}
        c["por_mecanismo"] = {k: dict(v) for k, v in sorted(c["por_mecanismo"].items())}
        c["ids"] = {k: v for k, v in c["ids"].items()}
        c["corrigiu_por_dia"] = dict(c["corrigiu_por_dia"])
        saida[nome] = c
    return {"execucoes": execucoes, "fora": sum(len(v) for v in fora.values()),
            "fora_por_motivo": {k: v for k, v in fora.items()}, "classes": saida}


def veredito(medido: dict, problemas: list[str]) -> dict:
    """Aplica a regra: INCONCLUSIVA, RISCO_RELEVANTE, INCERTO, NAO_EXERCITADO ou SEM_RISCO_MEDIDO."""
    nucleo, claro, vp = (medido["classes"][k] for k in ("nucleo", "claro", "vp"))
    saida: dict = {"problemas": list(problemas)}
    if medido["execucoes"] and medido["fora"] / medido["execucoes"] > LIMITE_FORA:
        saida["problemas"].append(
            f"{medido['fora']} de {medido['execucoes']} execuções fora (acima de "
            f"{LIMITE_FORA:.0%}): {sorted(medido['fora_por_motivo'])}")
    for nome, c in (("núcleo", nucleo), ("controle claro", claro), ("controle do VP", vp)):
        if c["n"] == 0:
            saida["problemas"].append(f"{nome} sem nenhuma execução válida")
    para_melhor = vp["por_resultado"].get("corrigiu_para_melhor", 0)
    minimo_vp = math.ceil(VP_MINIMO * vp["n"])
    saida["controle_vp"] = {"n": vp["n"], "corrigiu_para_melhor": para_melhor,
                            "minimo": minimo_vp, "cumpre": vp["n"] > 0 and para_melhor >= minimo_vp}
    if vp["n"] and para_melhor < minimo_vp:
        saida["problemas"].append(
            f"controle do VP: corrigiu para melhor em {para_melhor} de {vp['n']} (mínimo "
            f"{minimo_vp}): a rodada não enxerga o corrijo quando ele é devido")
    if saida["problemas"]:
        saida["decisao"] = "INCONCLUSIVA"
        return saida
    k = nucleo["por_resultado"].get("corrigiu_para_pior", 0)
    por_conversa = {i: v.get("corrigiu_para_pior", 0) for i, v in nucleo["por_conversa"].items()}
    conversas_com = sorted(i for i, v in por_conversa.items() if v)
    reproduzidas = sorted(i for i, v in por_conversa.items() if v >= RISCO_REPRODUZIDO)
    exercitado = nucleo["via_modelo"] / nucleo["n"]
    saida["nucleo"] = {
        "n": nucleo["n"], "corrigiu_para_pior": k, "taxa": k / nucleo["n"],
        "limite_superior_95": wilson_superior(k, nucleo["n"]),
        "conversas_com_ocorrencia": conversas_com, "conversas_reproduzidas": reproduzidas,
        "ids": nucleo["ids"].get("corrigiu_para_pior", []),
        "afirmou_erro": nucleo["corrigiu"], "equivalente":
            nucleo["por_resultado"].get("corrigiu_equivalente", 0),
        "refeito_para_pior_sem_corrijo": nucleo["por_resultado"].get("refeito_para_pior", 0),
        "exercitado": exercitado, "exercitado_minimo": EXERCITADO_MINIMO}
    taxa_claro = claro["corrigiu"] / claro["n"]
    taxa_nucleo = nucleo["corrigiu"] / nucleo["n"]
    saida["atribuicao"] = {
        "afirmou_erro_nucleo": taxa_nucleo, "afirmou_erro_claro": taxa_claro,
        "leitura": ("a formulação pesa: o núcleo afirma erro mais que o controle claro"
                    if taxa_nucleo > taxa_claro else
                    "o ruído é do modelo: o controle claro afirma erro tanto quanto o núcleo")}
    if k and (len(conversas_com) >= RISCO_CONVERSAS or reproduzidas
              or k / nucleo["n"] > RISCO_TAXA):
        saida["decisao"] = "RISCO_RELEVANTE"
    elif k:
        saida["decisao"] = "INCERTO"
    elif exercitado < EXERCITADO_MINIMO:
        saida["decisao"] = "NAO_EXERCITADO"
    else:
        saida["decisao"] = "SEM_RISCO_MEDIDO"
    return saida


def pontuar(linhas: list[dict], conjunto: dict, sha: str) -> dict:
    grupos = pc.finais(linhas)
    notas = {l["braco"]: l["notas"] for l in linhas if l.get("tipo") == "notas_seed"}
    problemas = []
    commits = {l.get("commit") for l in linhas if l.get("commit")}
    modelos = {l.get("modelo") for l in linhas if l.get("modelo")}
    if len(commits) != 1:
        problemas.append(f"mais de um commit, ou nenhum: {sorted(commits)}")
    if len(modelos) != 1:
        problemas.append(f"mais de um modelo, ou nenhum: {sorted(modelos)}")
    datas = {l.get("data_referencia") for l in linhas
             if l.get("tipo") == "pergunta" and l.get("parte") == "c581"}
    if len(datas) != 1:
        problemas.append(f"as repetições não são do mesmo dia: {sorted(d for d in datas if d)}")
    vistos = {l.get("conjunto_sha256") for l in linhas if l.get("parte") == "c581"}
    if vistos != {sha}:
        problemas.append(f"execuções com outro sha256: {sorted(v for v in vistos if v)}")
    if any(l.get("braco") == "desligado" for l in linhas):
        problemas.append("a rodada da #581 é só do braço ligado")
    medido = medir(conjunto, grupos, notas.get("ligado", {}))
    resultado = {"regra": VERSAO_REGRA, "conjunto": conjunto["versao"], "conjunto_sha256": sha,
                 "commits": sorted(commits), "modelos": sorted(modelos),
                 "datas_referencia": sorted(d for d in datas if d), "medido": medido}
    resultado.update(veredito(medido, problemas))
    return resultado


def main(argv=None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analisador.add_argument("--execucoes", type=Path, required=True)
    analisador.add_argument("--saida", type=Path, required=True)
    analisador.add_argument("--conjunto", type=Path, default=vc.ARQUIVO_581)
    args = analisador.parse_args(argv)
    linhas = [json.loads(t) for t in args.execucoes.read_text(encoding="utf-8").splitlines()
              if t.strip()]
    conjunto = json.loads(args.conjunto.read_text(encoding="utf-8"))
    sha = hashlib.sha256(args.conjunto.read_bytes()).hexdigest()
    resultado = pontuar(linhas, conjunto, sha)
    args.saida.write_text(json.dumps(resultado, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8")
    print(f"decisão: {resultado['decisao']}")
    for problema in resultado["problemas"]:
        print(f"  problema: {problema}")
    if "nucleo" in resultado:
        n = resultado["nucleo"]
        print(f"  núcleo: {n['corrigiu_para_pior']} de {n['n']} corrigiram para pior "
              f"(limite superior 95%: {n['limite_superior_95']:.3f}); exercitado "
              f"{n['exercitado']:.0%}; atribuição: {resultado['atribuicao']['leitura']}")
    return {"SEM_RISCO_MEDIDO": 0, "NAO_EXERCITADO": 0, "INCERTO": 1,
            "RISCO_RELEVANTE": 1}.get(resultado["decisao"], 2)


if __name__ == "__main__":
    sys.exit(main())
