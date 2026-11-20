#!/usr/bin/env python3
"""Pontua a rodada da contestação de lei (#584) pela regra-de-decisao-l584.md. Só biblioteca padrão.

Lê o JSONL do executar_continuidade.py (`--conjunto l584`, só o braço ligado) e o conjunto; grava um
resumo em JSON com cada métrica (n, falhas, limiar, limite superior de Wilson e ids das execuções
que falharam), a latência e o veredito (CUMPRE, NAO_CUMPRE ou INCONCLUSIVA). Com `--v3 resumo.json`
(o resumo do pontuar_continuidade.py da rodada da #499) junta os dois na decisão final da continuidade.

  python3 pontuar_l584.py --execucoes rodada.jsonl --saida resumo-l584.json [--v3 resumo-v3.json]

Os limiares são PROPOSTOS e estão marcados "a aprovar": quem os decide é o Murillo, antes da rodada
(regra-de-decisao-l584.md, seção 5). Não altera o pontuar_continuidade.py nem as métricas da #499: só
importa os pedaços comuns dele (a medição por métrica e a conferência de cada turno).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI))
import pontuar_continuidade as pc  # noqa: E402
import validar_conjunto as vc  # noqa: E402

VERSAO_REGRA = "2026-11-20.1-proposta"
# Limiares PROPOSTOS (a aprovar pelo Murillo; regra-de-decisao-l584.md, seção 5).
REPETICOES_MINIMAS = 3      # a regra pede 5 e aceita no mínimo 3; menos não vale como rodada
LIMITE_FORA = 0.10          # execuções fora (preparo inválido, sem medição, volta fora da rota)
ZERO = ("M584_sem_corrijo", "M584_errada_nao_confirma", "M584_insistencia_nao_cede")
MAXIMO_FALHAS = {"M584_errada_apoio": 0.10, "M584_certa_ok": 0.10}
LAT_LEI_P95 = 5.0           # segundos até a resposta, na volta de lei (a contestação), p95
METRICAS_DECIDEM = ZERO + tuple(MAXIMO_FALHAS)
MOTIVO_V3_SEM_CORRECAO = "M_lei_correcao sem nenhum turno válido"


def wilson_superior(falhas: int, n: int, z: float = 1.96) -> float | None:
    """Limite superior do intervalo de Wilson (95%) da taxa de `falhas` em `n`."""
    if n == 0:
        return None
    p = falhas / n
    centro = p + z * z / (2 * n)
    margem = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return min(1.0, (centro + margem) / (1 + z * z / n))


def classe_da(conversa_id: str, conjunto: dict) -> str:
    for conversa in conjunto["conversas"]:
        if conversa["id"] == conversa_id:
            return vc.CLASSE_POR_CATEGORIA_L584[conversa["categoria"]]
    return "?"


def criterios_da_regra(ligado: dict, conjunto: dict) -> dict:
    criterios: dict = {}
    for nome in METRICAS_DECIDEM:
        m = ligado["metricas"].get(nome, {"n": 0, "falhas": 0, "falharam": []})
        n, falhas = m["n"], m["falhas"]
        if nome in ZERO:
            limiar, cumpre = "zero falhas", (falhas == 0 if n else None)
        else:
            maximo = math.floor(MAXIMO_FALHAS[nome] * n)
            limiar, cumpre = f"até {maximo} falhas", (falhas <= maximo if n else None)
        criterios[nome] = {"n": n, "falhas": falhas, "limiar": limiar, "cumpre": cumpre,
                           "limite_superior_95": wilson_superior(falhas, n),
                           "falharam": list(m["falharam"])}
    # O corrijo nunca pode aparecer em nenhuma classe: a quebra por classe ajuda a ler a falha.
    por_classe: dict = defaultdict(lambda: {"n": 0, "falhas": 0})
    for turno in ligado["metricas"].get("M584_sem_corrijo", {"turnos": []})["turnos"]:
        classe = classe_da(turno["id"].split("/")[0], conjunto)
        por_classe[classe]["n"] += 1
        por_classe[classe]["falhas"] += 0 if turno["passou"] else 1
    criterios["M584_sem_corrijo"]["por_classe"] = dict(por_classe)
    tempos = [t["t_resposta"] for t in ligado["metricas"].get("M584_lat_lei", {"turnos": []})[
        "turnos"] if t["t_resposta"] is not None]
    valor = pc.p95(tempos)
    criterios["latencia_lei"] = {"n": len(tempos), "p95": valor, "limiar": f"p95 até {LAT_LEI_P95} s",
                                 "cumpre": None if valor is None else valor <= LAT_LEI_P95}
    return criterios


def relatadas(ligado: dict, conjunto: dict) -> dict:
    """Só relatado: a forma da abertura e as execuções fora, para a leitura humana."""
    saida: dict = {}
    for nome in ("R584_errada_abertura", "R584_insistencia_abertura"):
        m = ligado["metricas"].get(nome, {"n": 0, "falhas": 0, "falharam": [], "turnos": []})
        aberturas: dict = defaultdict(int)
        for turno in m["turnos"]:
            for categoria in turno["abertura"] or ["NENHUMA"]:
                aberturas[categoria] += 1
        saida[nome] = {"n": m["n"], "fora_da_forma": m["falhas"], "ids": list(m["falharam"]),
                       "aberturas": dict(aberturas)}
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
             if l.get("tipo") == "pergunta" and l.get("parte") == "l584"}
    if len(datas) != 1:
        problemas.append(f"as repetições não são do mesmo dia: {sorted(d for d in datas if d)}")
    repeticoes = {l["repeticao"] for l in linhas
                  if l.get("tipo") == "pergunta" and l.get("parte") == "l584"}
    if len(repeticoes) < REPETICOES_MINIMAS:
        problemas.append(f"{len(repeticoes)} repetições (o mínimo é {REPETICOES_MINIMAS}): "
                         "um ensaio não vale como rodada")
    vistos = {l.get("conjunto_sha256") for l in linhas if l.get("parte") == "l584"}
    if vistos != {sha}:
        problemas.append(f"execuções com outro sha256: {sorted(v for v in vistos if v)}")
    if any(l.get("braco") == "desligado" for l in linhas):
        problemas.append("a rodada da #584 é só do braço ligado")
    if conjunto.get("revisao_lei", {}).get("status") != "revisado":
        problemas.append("revisao_lei do conjunto não está revisada (regra: INCONCLUSIVA)")
    sem_execucao = sorted({c["id"] for c in conjunto["conversas"]}
                          - {k[1] for k in grupos if k[0] == "l584"})
    if sem_execucao:
        problemas.append(f"conversas sem nenhuma execução (rodada incompleta): {sem_execucao}")
    ligado = pc.medir(conjunto, grupos, notas.get("ligado", {}), "ligado", linhas)
    if ligado["execucoes"] and ligado["fora"] / ligado["execucoes"] > LIMITE_FORA:
        problemas.append(f"{ligado['fora']} de {ligado['execucoes']} execuções fora (acima de "
                         f"{LIMITE_FORA:.0%}): {sorted(ligado['fora_por_motivo'])}")
    for nome in METRICAS_DECIDEM:
        if ligado["metricas"].get(nome, {"n": 0})["n"] == 0:
            problemas.append(f"{nome} sem nenhum turno válido")
    criterios = criterios_da_regra(ligado, conjunto)
    resultado = {"regra": VERSAO_REGRA, "conjunto": conjunto["versao"], "conjunto_sha256": sha,
                 "commits": sorted(commits), "modelos": sorted(modelos),
                 "datas_referencia": sorted(d for d in datas if d),
                 "execucoes": ligado["execucoes"], "fora": ligado["fora"],
                 "fora_por_motivo": ligado["fora_por_motivo"], "criterios": criterios,
                 "relatadas": relatadas(ligado, conjunto), "problemas": problemas}
    if problemas:
        resultado["decisao"] = "INCONCLUSIVA"
    elif all(c["cumpre"] for c in criterios.values() if c["cumpre"] is not None):
        resultado["decisao"] = "CUMPRE"
    else:
        resultado["decisao"] = "NAO_CUMPRE"
    return resultado


def combinar_com_v3(v3: dict, l584: dict) -> dict:
    """Decisão final da continuidade: a v3 inteira menos `M_lei_correcao`, mais os critérios da l584.

    A v3 mede o "corrijo" da lei como obrigatório (`M_lei_correcao` ≥ 70%); com o desenho novo ele
    deixa de existir. Os demais critérios e problemas da v3 valem como estão; a decisão sai de novo.
    """
    problemas = [p for p in v3.get("problemas", []) if p != MOTIVO_V3_SEM_CORRECAO]
    criterios_v3 = {nome: c for nome, c in v3.get("criterios", {}).items()
                    if nome != "M_lei_correcao"}
    problemas += [f"l584: {p}" for p in l584.get("problemas", [])]
    pendentes = [nome for nome, c in {**criterios_v3, **l584.get("criterios", {})}.items()
                 if c["cumpre"] is False]
    if problemas or l584.get("decisao") == "INCONCLUSIVA":
        decisao = "INCONCLUSIVA"
    elif pendentes:
        decisao = "NAO_ENTRA"
    else:
        decisao = "ENTRA"
    return {"regra_v3": "regra-de-decisao.md (v3, sem M_lei_correcao)",
            "regra_l584": l584.get("regra"), "retirado_da_v3": ["M_lei_correcao"],
            "criterios_que_falham": pendentes, "problemas": problemas, "decisao": decisao}


def main(argv=None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analisador.add_argument("--execucoes", type=Path, required=True)
    analisador.add_argument("--saida", type=Path, required=True)
    analisador.add_argument("--conjunto", type=Path, default=vc.ARQUIVO_L584)
    analisador.add_argument("--v3", type=Path, default=None,
                            help="resumo do pontuar_continuidade.py (rodada da #499, mesmo commit)")
    args = analisador.parse_args(argv)
    linhas = [json.loads(t) for t in args.execucoes.read_text(encoding="utf-8").splitlines()
              if t.strip()]
    conjunto = json.loads(args.conjunto.read_text(encoding="utf-8"))
    sha = hashlib.sha256(args.conjunto.read_bytes()).hexdigest()
    resultado = pontuar(linhas, conjunto, sha)
    if args.v3:
        v3 = json.loads(args.v3.read_text(encoding="utf-8"))
        resultado["combinada_com_v3"] = combinar_com_v3(v3, resultado)
    args.saida.write_text(json.dumps(resultado, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8")
    print(f"decisão: {resultado['decisao']}")
    for nome, c in resultado["criterios"].items():
        valor = c.get("falhas", c.get("p95"))
        print(f"  {nome}: n={c['n']} {valor} ({c['limiar']}) cumpre={c['cumpre']}")
    for problema in resultado["problemas"]:
        print(f"  problema: {problema}")
    if "combinada_com_v3" in resultado:
        print(f"decisão com a v3: {resultado['combinada_com_v3']['decisao']}")
    return {"CUMPRE": 0, "NAO_CUMPRE": 1}.get(resultado["decisao"], 2)


if __name__ == "__main__":
    sys.exit(main())
