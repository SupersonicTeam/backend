#!/usr/bin/env python3
"""Aplica a regra de decisão (regra-de-decisao.md) às saídas dos quatro braços.

Só biblioteca padrão. Escrito com a regra, antes de qualquer rodada.

Uso:
  python decisao.py --conjunto PERGUNTAS.json --a A.json --b B.json [--c C.json] [--d D.json]
                    [--excluir id,id] [--json RESULTADO.json]

Com só A e B, confere os ``entregues`` e calcula o ruído (o passo antes de rodar C e D). Código de
saída 3 se os ``entregues`` de A e B diferem; 2 se uma saída diverge do conjunto congelado; 4 se um
braço não tem exatamente as perguntas do conjunto fora do ``--excluir`` (a comparação seria sobre
populações diferentes).
"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import busca_contagem as bc  # noqa: E402

GANHO_MINIMO = 3
VANTAGEM_D_SOBRE_C = 2


class IdsDivergentes(ValueError):
    """Um braço não tem exatamente as perguntas do conjunto fora das excluídas."""


def exigir_mesmos_ids(conjunto: dict, bracos: dict[str, list[dict] | None], excluir: set[str]) -> None:
    """Cada braço presente tem as mesmas perguntas do conjunto, fora as excluídas, sem repetir."""
    esperados = {p["id"] for p in conjunto["perguntas"]} - excluir
    for nome, saida in bracos.items():
        if saida is None:
            continue
        ids = [i["id"] for i in saida if i["id"] not in excluir]
        if len(ids) != len(set(ids)) or set(ids) != esperados:
            faltam = sorted(esperados - set(ids))
            sobram = sorted(set(ids) - esperados)
            raise IdsDivergentes(f"braço {nome}: faltam {faltam}, sobram {sobram}, "
                                 f"repetidos {len(ids) - len(set(ids))}")


def rotulos(saida: list[dict], conjunto: dict, excluir: set[str]) -> dict[str, str]:
    relatorio = bc.contar(saida, conjunto)
    return {r["id"]: r["rotulo"] for r in relatorio["perguntas"] if r["id"] not in excluir}


def _entregues(item: dict) -> list[tuple]:
    return [
        (e.get("posicao"), e.get("norma"), e.get("dispositivo"), e.get("similaridade"))
        for e in item.get("entregues") or []
    ]


def diferencas_entregues(a: list[dict], b: list[dict], excluir: set[str]) -> list[str]:
    """Ids cujos ``entregues`` diferem entre as duas saídas (ou que faltam numa delas)."""
    ma = {i["id"]: i for i in a if i["id"] not in excluir}
    mb = {i["id"]: i for i in b if i["id"] not in excluir}
    ids = sorted(set(ma) | set(mb))
    return [pid for pid in ids if pid not in ma or pid not in mb or _entregues(ma[pid]) != _entregues(mb[pid])]


def ruido(ra: dict[str, str], rb: dict[str, str]) -> list[str]:
    return sorted(pid for pid in ra if pid in rb and ra[pid] != rb[pid])


def comparar(base: dict[str, str], variante: dict[str, str], grupos: dict[str, str], ruido_n: int) -> dict:
    ids = sorted(pid for pid in base if pid in variante)
    viram_certa = [p for p in ids if base[p] != bc.CERTA and variante[p] == bc.CERTA]
    deixam_certa = [p for p in ids if base[p] == bc.CERTA and variante[p] != bc.CERTA]
    viram_outro = [p for p in ids if base[p] != bc.OUTRO_ARTIGO and variante[p] == bc.OUTRO_ARTIGO]
    deixam_outro = [p for p in ids if base[p] == bc.OUTRO_ARTIGO and variante[p] != bc.OUTRO_ARTIGO]
    setor_perde = [p for p in deixam_certa if grupos.get(p) == "setor"]
    ausente_responde = [
        p for p in ids
        if grupos.get(p) == "ausente" and base[p] == bc.DESCARTE
        and variante[p] in (bc.CERTA, bc.OUTRO_ARTIGO)
    ]
    saldo = len(viram_certa) - len(deixam_certa)
    condicoes = {
        "a_saldo_certa_ge_3": saldo >= GANHO_MINIMO,
        "b_outro_artigo_nao_sobe": len(viram_outro) <= len(deixam_outro),
        "c_nenhum_setor_perde_certa": not setor_perde,
        "d_nenhuma_ausente_passa_a_responder": not ausente_responde,
        "e_saldo_maior_que_ruido": saldo > ruido_n,
    }
    return {
        "viram_certa": viram_certa,
        "deixam_certa": deixam_certa,
        "saldo_certa": saldo,
        "viram_outro_artigo": viram_outro,
        "deixam_outro_artigo": deixam_outro,
        "setor_perde_certa": setor_perde,
        "ausente_passa_a_responder": ausente_responde,
        "condicoes": condicoes,
        "passa": all(condicoes.values()),
    }


def escolher(rc: dict[str, str] | None, rd: dict[str, str] | None, comp_c: dict | None, comp_d: dict | None) -> str:
    """``"C"``, ``"D"`` ou ``"nenhuma"``, pela regra."""
    passa_c = bool(comp_c and comp_c["passa"])
    passa_d = bool(comp_d and comp_d["passa"])
    if passa_c and passa_d:
        ids = sorted(p for p in rc if p in rd)
        ganha = sum(1 for p in ids if rc[p] != bc.CERTA and rd[p] == bc.CERTA)
        perde = sum(1 for p in ids if rc[p] == bc.CERTA and rd[p] != bc.CERTA)
        outro_c = sum(1 for p in ids if rc[p] == bc.OUTRO_ARTIGO)
        outro_d = sum(1 for p in ids if rd[p] == bc.OUTRO_ARTIGO)
        return "D" if ganha - perde >= VANTAGEM_D_SOBRE_C and outro_d <= outro_c else "C"
    if passa_c:
        return "C"
    if passa_d:
        return "D"
    return "nenhuma"


def decidir(conjunto: dict, a: list[dict], b: list[dict], c: list[dict] | None = None,
            d: list[dict] | None = None, excluir: set[str] | None = None) -> dict:
    excluir = excluir or set()
    exigir_mesmos_ids(conjunto, {"A": a, "B": b, "C": c, "D": d}, excluir)
    grupos = {p["id"]: p.get("grupo", "") for p in conjunto["perguntas"]}
    resultado: dict = {"excluidas": sorted(excluir)}
    diferentes = diferencas_entregues(a, b, excluir)
    resultado["entregues_a_b_diferentes"] = diferentes
    ra, rb = rotulos(a, conjunto, excluir), rotulos(b, conjunto, excluir)
    resultado["rotulos"] = {"A": ra, "B": rb}
    mudam = ruido(ra, rb)
    resultado["ruido"] = {"perguntas": mudam, "n": len(mudam)}
    if diferentes:
        resultado["decisao"] = "parar: entregues de A e B diferem"
        return resultado
    rc = rotulos(c, conjunto, excluir) if c is not None else None
    rd = rotulos(d, conjunto, excluir) if d is not None else None
    comp_c = comparar(rb, rc, grupos, len(mudam)) if rc is not None else None
    comp_d = comparar(rb, rd, grupos, len(mudam)) if rd is not None else None
    if rc is not None:
        resultado["rotulos"]["C"] = rc
        resultado["C"] = comp_c
    if rd is not None:
        resultado["rotulos"]["D"] = rd
        resultado["D"] = comp_d
    if rc is not None and rd is not None:
        resultado["decisao"] = escolher(rc, rd, comp_c, comp_d)
    return resultado


def _ler(caminho: str | None):
    if caminho is None:
        return None
    with open(caminho, encoding="utf-8") as f:
        return json.load(f)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--conjunto", required=True)
    parser.add_argument("--a", required=True)
    parser.add_argument("--b", required=True)
    parser.add_argument("--c")
    parser.add_argument("--d")
    parser.add_argument("--excluir", default="")
    parser.add_argument("--json", dest="json_saida")
    args = parser.parse_args(argv)
    excluir = {x for x in args.excluir.split(",") if x}
    try:
        resultado = decidir(_ler(args.conjunto), _ler(args.a), _ler(args.b), _ler(args.c),
                            _ler(args.d), excluir)
    except bc.ConjuntoDivergente as erro:
        print(f"erro: {erro}", file=sys.stderr)
        return 2
    except IdsDivergentes as erro:
        print(f"erro: {erro}", file=sys.stderr)
        return 4
    texto = json.dumps(resultado, ensure_ascii=False, indent=2)
    if args.json_saida:
        with open(args.json_saida, "w", encoding="utf-8", newline="\n") as f:
            f.write(texto + "\n")
    print(texto)
    return 3 if resultado["entregues_a_b_diferentes"] else 0


if __name__ == "__main__":
    sys.exit(main())
