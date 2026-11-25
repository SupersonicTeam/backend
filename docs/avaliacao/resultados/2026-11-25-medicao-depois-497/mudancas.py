#!/usr/bin/env python3
"""Ids cuja rota (ou recusa) mudou entre a base e o depois, só com campos estruturados.

Uso: mudancas.py <base.json> <depois.json>
Por id: erros de rota em cada repetição (base -> depois), rota obtida e camada por repetição.
Nunca lê o mapa id -> texto nem execucao.texto.
"""
import json
import sys


def carregar(c):
    with open(c, encoding="utf-8") as f:
        d = json.load(f)
    return {x["id"]: [r_x for r_x in (next(y for y in r["resultados"] if y["id"] == x["id"])
                                       for r in d["rodadas"])]
            for x in d["rodadas"][0]["resultados"]}


def linha(rs, campo):
    return [("E" if r[campo] is False else ("." if r[campo] is not None else "-")) for r in rs]


def main(a, b):
    base, dep = carregar(a), carregar(b)
    print("| Id | Adv. | Esperada | Erros de rota base → depois | Obtida depois (rep. 1 / 2 / 3) | Camada base (1/2/3) | Camada depois (1/2/3) |")
    print("|---|---|---|---|---|---|---|")
    muda = []
    for id_ in base:
        eb = sum(1 for r in base[id_] if r["rotaCorreta"] is False)
        ed = sum(1 for r in dep[id_] if r["rotaCorreta"] is False)
        if eb != ed or [r["execucao"].get("rota") for r in base[id_]] != [r["execucao"].get("rota") for r in dep[id_]]:
            muda.append((ed - eb, id_, eb, ed))
    for _, id_, eb, ed in sorted(muda):
        r0 = base[id_][0]
        obt = " / ".join(f"{r['execucao'].get('rota')}{'' if r['rotaCorreta'] is not False else ' ✗'}" for r in dep[id_])
        cb = " / ".join((r["execucao"].get("camadaClassificacao") or "—") for r in base[id_])
        cd = " / ".join((r["execucao"].get("camadaClassificacao") or "—") for r in dep[id_])
        print(f"| {id_} | {'sim' if r0.get('adversario') else 'não'} | {r0.get('rotaEsperada')} | {eb} → {ed} de 3 | {obt} | {cb} | {cd} |")
    print("\nIds com recusa errada (base → depois, repetições erradas):")
    for id_ in base:
        eb = sum(1 for r in base[id_] if r["recusaCorreta"] is False)
        ed = sum(1 for r in dep[id_] if r["recusaCorreta"] is False)
        if eb or ed:
            print(f"- {id_}: {eb} → {ed} de 3")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1], sys.argv[2])
