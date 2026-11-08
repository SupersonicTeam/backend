#!/usr/bin/env python3
"""Tabelas do relatório da medição da busca de lei (#436), a partir das saídas dos braços.

Só biblioteca padrão. A decisão é a do ``decisao.py`` (regra-de-decisao.md); aqui entram também as
medidas descritivas: precisão e cobertura por execução, recall@5 e posição 1 da lista entregue, top 5
que mudou, disparos da guarda e tempo da 1ª repetição.

Uso:
  python relatorio.py --conjunto PERGUNTAS.json --resultados PASTA [--excluir id,id]

A pasta tem ``<B>-citacoes.json`` e ``<B>-recall.json`` de cada braço (A, B, C, D).
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import busca_contagem as bc  # noqa: E402
import decisao as dc  # noqa: E402

BRACOS = ("A", "B", "C", "D")
GRUPOS = ("geral", "setor", "ausente")
GUARDA = "REGIME_ESPECIFICO_SEM_SETOR"


def _ler(caminho: str):
    with open(caminho, encoding="utf-8") as f:
        return json.load(f)


def por_execucao(saida: list[dict], conjunto: dict, excluir: set[str]) -> dict:
    """Precisão e cobertura por execução (descritivas), por grupo e no total."""
    grupos = {p["id"]: p["grupo"] for p in conjunto["perguntas"]}
    aceitaveis = {p["id"]: {bc.chave(k) for k in p["esperado"] + p["aceito"]} for p in conjunto["perguntas"]}
    contas: dict[str, dict[str, int]] = {}
    for item in saida:
        if item["id"] in excluir:
            continue
        g = grupos[item["id"]]
        for rodada in item.get("rodadas") or []:
            rot = bc.rotulo_rodada(rodada, aceitaveis[item["id"]])
            for chave in (g, "total"):
                c = contas.setdefault(chave, {"execucoes": 0, "certa": 0, "outro_artigo": 0,
                                               "descarte": 0, "erro": 0, "guarda": 0})
                c["execucoes"] += 1
                c[rot] += 1
                c["guarda"] += rodada.get("desfecho") == GUARDA
    for c in contas.values():
        respondidas = c["certa"] + c["outro_artigo"]
        validas = c["execucoes"] - c["erro"]
        c["precisao"] = round(c["certa"] / respondidas, 3) if respondidas else None
        c["cobertura"] = round(respondidas / validas, 3) if validas else None
    return contas


def tempo_primeira(saida: list[dict], excluir: set[str]) -> dict:
    ms = [i["rodadas"][0]["ms"] for i in saida
          if i["id"] not in excluir and i.get("rodadas") and "ms" in i["rodadas"][0]]
    if not ms:
        return {"n": 0}
    return {"n": len(ms), "mediana_s": round(statistics.median(ms) / 1000, 1),
            "media_s": round(statistics.mean(ms) / 1000, 1), "total_s": round(sum(ms) / 1000)}


def top5(item: dict) -> list[tuple]:
    return [(e["norma"], e["dispositivo"]) for e in (item.get("entregues") or [])[:5]]


def top5_mudou(base: list[dict], outra: list[dict], excluir: set[str]) -> list[str]:
    mb = {i["id"]: i for i in base}
    return sorted(i["id"] for i in outra if i["id"] not in excluir and i["id"] in mb
                  and top5(i) != top5(mb[i["id"]]))


def montar(conjunto: dict, pasta: str, excluir: set[str]) -> dict:
    saidas = {b: _ler(os.path.join(pasta, f"{b}-citacoes.json")) for b in BRACOS
              if os.path.exists(os.path.join(pasta, f"{b}-citacoes.json"))}
    recalls = {b: _ler(os.path.join(pasta, f"{b}-recall.json")) for b in BRACOS
               if os.path.exists(os.path.join(pasta, f"{b}-recall.json"))}
    rel: dict = {"excluidas": sorted(excluir)}
    if "A" in saidas and "B" in saidas:
        rel["decisao"] = dc.decidir(conjunto, saidas["A"], saidas["B"], saidas.get("C"), saidas.get("D"), excluir)
    rel["por_execucao"] = {b: por_execucao(s, conjunto, excluir) for b, s in saidas.items()}
    # Tempo da 1ª repetição pela saída bruta, quando existe: na mesclada, uma 1ª repetição com
    # ERRO_MODELO foi trocada pela refeita no fim do braço, com outro estado do cache.
    brutas = {b: _ler(os.path.join(pasta, f"{b}-citacoes-bruto.json")) for b in saidas
              if os.path.exists(os.path.join(pasta, f"{b}-citacoes-bruto.json"))}
    rel["tempo_primeira_repeticao"] = {b: tempo_primeira(brutas.get(b, s), excluir)
                                       for b, s in saidas.items()}
    rel["recall"] = {b: r.get("resumo") for b, r in recalls.items()}
    if "B" in saidas:
        rel["top5_mudou_vs_B"] = {b: top5_mudou(saidas["B"], s, excluir) for b, s in saidas.items() if b != "B"}
    rel["guarda_por_pergunta"] = {
        b: sorted({i["id"] for i in s if i["id"] not in excluir
                   for r in i.get("rodadas") or [] if r.get("desfecho") == GUARDA})
        for b, s in saidas.items()
    }
    return rel


def tabela_rotulos(conjunto: dict, rel: dict) -> str:
    rot = rel["decisao"]["rotulos"]
    bracos = [b for b in BRACOS if b in rot]
    linhas = ["| id | grupo | " + " | ".join(bracos) + " |", "|---|---|" + "---|" * len(bracos)]
    for p in conjunto["perguntas"]:
        if p["id"] in rel["excluidas"]:
            continue
        linhas.append(f"| {p['id']} | {p['grupo']} | " + " | ".join(rot[b].get(p["id"], "-") for b in bracos) + " |")
    return "\n".join(linhas)


def contagem_rotulos(conjunto: dict, rel: dict) -> str:
    rot = rel["decisao"]["rotulos"]
    bracos = [b for b in BRACOS if b in rot]
    grupos = {p["id"]: p["grupo"] for p in conjunto["perguntas"]}
    linhas = ["| grupo | rótulo | " + " | ".join(bracos) + " |", "|---|---|" + "---|" * len(bracos)]
    for g in GRUPOS + ("total",):
        for r in bc.ROTULOS_PERGUNTA:
            valores = [sum(1 for pid, x in rot[b].items() if x == r and (g == "total" or grupos[pid] == g))
                       for b in bracos]
            if any(valores):
                linhas.append(f"| {g} | {r} | " + " | ".join(str(v) for v in valores) + " |")
    return "\n".join(linhas)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--conjunto", required=True)
    parser.add_argument("--resultados", required=True)
    parser.add_argument("--excluir", default="")
    parser.add_argument("--json", dest="json_saida")
    args = parser.parse_args(argv)
    conjunto = _ler(args.conjunto)
    excluir = {x for x in args.excluir.split(",") if x}
    try:
        rel = montar(conjunto, args.resultados, excluir)
    except bc.ConjuntoDivergente as erro:
        print(f"erro: {erro}", file=sys.stderr)
        return 2
    except dc.IdsDivergentes as erro:
        print(f"erro: {erro}", file=sys.stderr)
        return 4
    if args.json_saida:
        with open(args.json_saida, "w", encoding="utf-8", newline="\n") as f:
            json.dump(rel, f, ensure_ascii=False, indent=2)
            f.write("\n")
    if "decisao" in rel:
        print(contagem_rotulos(conjunto, rel))
        print()
        print(tabela_rotulos(conjunto, rel))
        print()
    print(json.dumps({k: v for k, v in rel.items() if k != "decisao"}, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
