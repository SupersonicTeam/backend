#!/usr/bin/env python3
"""Ids cuja rota (ou recusa) mudou entre a base e o depois, só com campos estruturados.

Uso: mudancas.py <base.json> <depois.json>
Por id: erros de rota em cada repetição (base -> depois), rota obtida e camada por repetição.
Só ids com rota esperada entram na tabela de rota (pergunta sem rota esperada não tem erro de rota).
Nunca lê o mapa id -> texto nem execucao.texto.
"""
import json
import sys


def carregar(caminho):
    """{id: [resultado da repetição 1, 2, 3]}"""
    with open(caminho, encoding="utf-8") as f:
        d = json.load(f)
    por_id = {}
    for rodada in d["rodadas"]:
        for x in rodada["resultados"]:
            por_id.setdefault(x["id"], []).append(x)
    return por_id


def erros(rs, campo):
    return sum(1 for r in rs if r[campo] is False)


def main(a, b):
    base, dep = carregar(a), carregar(b)
    print("| Id | Adv. | Esperada | Erros de rota base → depois | Obtida depois (rep. 1 / 2 / 3) | Camada base (1/2/3) | Camada depois (1/2/3) |")
    print("|---|---|---|---|---|---|---|")
    muda = []
    for id_ in base:
        if base[id_][0].get("rotaEsperada") is None:
            continue
        eb, ed = erros(base[id_], "rotaCorreta"), erros(dep[id_], "rotaCorreta")
        rotas = lambda rs: [r["execucao"].get("rota") for r in rs]
        if eb != ed or rotas(base[id_]) != rotas(dep[id_]):
            muda.append((ed - eb, id_, eb, ed))
    for _, id_, eb, ed in sorted(muda):
        r0 = base[id_][0]
        obt = " / ".join(f"{r['execucao'].get('rota')}{' ✗' if r['rotaCorreta'] is False else ''}"
                         for r in dep[id_])
        cb = " / ".join((r["execucao"].get("camadaClassificacao") or "—") for r in base[id_])
        cd = " / ".join((r["execucao"].get("camadaClassificacao") or "—") for r in dep[id_])
        print(f"| {id_} | {'sim' if r0.get('adversario') else 'não'} | {r0.get('rotaEsperada')} | "
              f"{eb} → {ed} de 3 | {obt} | {cb} | {cd} |")
    print("\nIds com recusa errada (base → depois, repetições erradas):")
    for id_ in base:
        eb, ed = erros(base[id_], "recusaCorreta"), erros(dep[id_], "recusaCorreta")
        if eb or ed:
            print(f"- {id_}: {eb} → {ed} de 3")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1], sys.argv[2])
