#!/usr/bin/env python3
"""Decide se a correção da #497 entra, pela regra de regra-de-decisao.md.

Só biblioteca padrão. Entrada: o conjunto congelado e os dois resumos do pontuar.py (braço base e
braço correção, da mesma rodada). Saída: a comparação em JSON, com o veredito, os bloqueios, o
ganho, a ressalva e as diferenças por classe, por pergunta e por camada.

  python comparar.py --perguntas <conjunto.json> --base <resumo-base.json> \
      --correcao <resumo-correcao.json> --saida comparacao.json
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import pontuar  # noqa: E402

MODELO = "anthropic/claude-haiku-5-5"
VERSAO = "ceres-497-v1"
GANHO_MINIMO = 3
QUEDA_BLOQUEANTE = 2


def problemas_de_medicao(conjunto: dict, base: dict, correcao: dict,
                         contaminadas: frozenset = frozenset()) -> list[str]:
    """Motivos para não decidir (INCONCLUSIVO); vazio quando a medição vale.

    Pergunta contaminada sem medição não impede a decisão: ela já está fora dela.
    """
    problemas = []
    for rotulo, resumo in (("base", base), ("correcao", correcao)):
        if resumo.get("versao_conjunto") != VERSAO:
            problemas.append(f"{rotulo}: conjunto {resumo.get('versao_conjunto')}, não {VERSAO}")
        if resumo.get("sha256_conjunto") != conjunto["_sha256"]:
            problemas.append(f"{rotulo}: sha256 do conjunto diferente do .sha256")
        if len(resumo.get("commits", [])) != 1:
            problemas.append(f"{rotulo}: {len(resumo.get('commits', []))} commits, não 1")
        if resumo.get("modelos") != [MODELO]:
            problemas.append(f"{rotulo}: modelos {resumo.get('modelos')}, não [{MODELO}]")
        sem = [i for i in resumo.get("numeros", {}).get("sem_medicao", [])
               if i not in contaminadas]
        if sem:
            problemas.append(f"{rotulo}: sem medição em {', '.join(sem)}")
    if base.get("commits") and base.get("commits") == correcao.get("commits"):
        problemas.append("os dois braços têm o mesmo commit")
    return problemas


def tipo_trocado(perguntas: dict, resumo: dict) -> list[str]:
    """Recusadas pela maioria com o tipo diferente do esperado."""
    pp = resumo["por_pergunta"]
    return sorted(i for i in pontuar.espera_recusa(perguntas)
                  if pp[i]["recusou"] and not pontuar.tipo_certo(perguntas[i], pp[i]))


def _fracao_menor(certos_a: int, total_a: int, certos_b: int, total_b: int) -> bool:
    """certos_a/total_a < certos_b/total_b; sem recusas não há fração."""
    if total_a == 0:
        return False
    if total_b == 0:
        return certos_a < total_a
    return certos_a * total_b < certos_b * total_a


def _situacao(item: dict) -> dict:
    return {chave: item.get(chave) for chave in
            ("resultado", "certas", "validas", "rota", "camada", "recusou", "tipo_recusa",
             "desfechos")}


def comparar(conjunto: dict, base: dict, correcao: dict, contaminadas=()) -> dict:
    """Aplica a regra; as `contaminadas` rodam nos dois braços, mas ficam fora da decisão."""
    todas = {p["id"]: p for p in conjunto["perguntas"]}
    contaminadas = frozenset(contaminadas)
    desconhecidas = sorted(contaminadas - set(todas))
    if desconhecidas:
        raise ValueError(f"contaminadas fora do conjunto: {', '.join(desconhecidas)}")
    perguntas = {i: p for i, p in todas.items() if i not in contaminadas}
    resultado = {
        "commits": {"base": base.get("commits"), "correcao": correcao.get("commits")},
        "modelos": {"base": base.get("modelos"), "correcao": correcao.get("modelos")},
        "problemas_de_medicao": problemas_de_medicao(conjunto, base, correcao, contaminadas),
        "contaminadas": {
            i: {"classe": todas[i]["classe"],
                "base": _situacao(base.get("por_pergunta", {}).get(i, {})),
                "correcao": _situacao(correcao.get("por_pergunta", {}).get(i, {}))}
            for i in sorted(contaminadas)},
    }
    if resultado["problemas_de_medicao"]:
        resultado["veredito"] = "INCONCLUSIVO"
        return resultado

    pb, pc = base["por_pergunta"], correcao["por_pergunta"]
    # Os números da decisão saem só das perguntas fora das contaminadas.
    nb, nc = pontuar.numeros(perguntas, pb), pontuar.numeros(perguntas, pc)
    bloqueios = []

    indevidas = nc["recusa_indevida"]["ids"]
    if indevidas:
        bloqueios.append(f"recusa indevida na correção: {', '.join(indevidas)}")

    por_classe = {}
    for classe in pontuar.CLASSES:
        b = nb["por_classe"][classe]["certas"]
        c = nc["por_classe"][classe]["certas"]
        por_classe[classe] = {"base": b, "correcao": c, "diferenca": c - b,
                              "total": nb["por_classe"][classe]["total"]}
        if b - c >= QUEDA_BLOQUEANTE:
            bloqueios.append(f"queda na classe {classe}: {b} -> {c}")

    nos_dois = [i for i in pontuar.espera_recusa(perguntas)
                if pb[i]["recusou"] and pc[i]["recusou"]]
    tipo_b = sum(pontuar.tipo_certo(perguntas[i], pb[i]) for i in nos_dois)
    tipo_c = sum(pontuar.tipo_certo(perguntas[i], pc[i]) for i in nos_dois)
    if tipo_b - tipo_c >= QUEDA_BLOQUEANTE:
        bloqueios.append(f"tipo de recusa piora nas recusadas pelos dois braços: {tipo_b} -> "
                         f"{tipo_c} de {len(nos_dois)}")

    rb = nb["recusa_correta"]["recusaram"]
    rc = nc["recusa_correta"]["recusaram"]
    ganho = rc - rb >= GANHO_MINIMO

    tb, tc = nb["tipo_de_recusa_certo"], nc["tipo_de_recusa_certo"]
    ressalva = _fracao_menor(tc["certos"], tc["recusaram"], tb["certos"], tb["recusaram"])

    if bloqueios or not ganho:
        veredito = "NAO_ENTRA"
    elif ressalva:
        veredito = "ENTRA_COM_RESSALVA"
    else:
        veredito = "ENTRA"

    resultado.update({
        "veredito": veredito,
        "bloqueios": bloqueios,
        "ganho": {"recusa_correta_base": rb, "recusa_correta_correcao": rc,
                  "total": nb["recusa_correta"]["total"], "minimo": GANHO_MINIMO,
                  "atingido": ganho},
        "ressalva": {"tipo_certo_base": tb, "tipo_certo_correcao": tc,
                     "tipo_trocado_correcao": tipo_trocado(perguntas, correcao),
                     "tipo_trocado_base": tipo_trocado(perguntas, base),
                     "aplicada": ressalva},
        "tipo_nas_recusadas_pelos_dois": {"perguntas": len(nos_dois), "certos_base": tipo_b,
                                          "certos_correcao": tipo_c},
        "acerto_de_rota": {"base": nb["acerto_de_rota"]["certas"],
                           "correcao": nc["acerto_de_rota"]["certas"],
                           "total": nb["acerto_de_rota"]["total"]},
        "recusa_indevida": {"base": nb["recusa_indevida"]["ids"], "correcao": indevidas,
                            "total": nb["recusa_indevida"]["total"]},
        "por_classe": por_classe,
        "melhoraram": sorted(i for i in perguntas
                             if pb[i]["resultado"] == "errada" and pc[i]["resultado"] == "certa"),
        "pioraram": sorted(i for i in perguntas
                           if pb[i]["resultado"] == "certa" and pc[i]["resultado"] == "errada"),
        "por_camada": {"base": base["por_camada"], "correcao": correcao["por_camada"]},
        "erros_por_grupo_correcao": correcao["erros_por_grupo"],
        "desfechos": {"base": _desfechos(base), "correcao": _desfechos(correcao)},
    })
    return resultado


def _desfechos(resumo: dict) -> dict:
    total: Counter = Counter()
    for item in resumo["por_pergunta"].values():
        total.update(item["desfechos"])
    return dict(total.most_common())


def _imprimir(r: dict) -> None:
    print(f"Base {r['commits']['base']} x correção {r['commits']['correcao']}")
    print(f"Veredito: {r['veredito']}")
    print(f"Contaminadas (fora da decisão): {', '.join(r['contaminadas']) or 'nenhuma'}")
    for problema in r["problemas_de_medicao"]:
        print(f"  medição: {problema}")
    if r["veredito"] == "INCONCLUSIVO":
        return
    for ident, item in r["contaminadas"].items():
        print(f"  contaminada (fora da decisão) {ident} [{item['classe']}]: base "
              f"{item['base']['rota']}/{item['base']['camada']} ({item['base']['resultado']}), "
              f"correção {item['correcao']['rota']}/{item['correcao']['camada']} "
              f"({item['correcao']['resultado']})")
    for bloqueio in r["bloqueios"]:
        print(f"  bloqueio: {bloqueio}")
    g = r["ganho"]
    print(f"Recusa correta: {g['recusa_correta_base']} -> {g['recusa_correta_correcao']} de "
          f"{g['total']} (mínimo +{g['minimo']}: {'sim' if g['atingido'] else 'não'})")
    s = r["ressalva"]
    print(f"Tipo certo: {s['tipo_certo_base']['certos']}/{s['tipo_certo_base']['recusaram']} -> "
          f"{s['tipo_certo_correcao']['certos']}/{s['tipo_certo_correcao']['recusaram']}; "
          f"trocado na correção: {', '.join(s['tipo_trocado_correcao']) or '-'}")
    a = r["acerto_de_rota"]
    print(f"Acerto de rota: {a['base']} -> {a['correcao']} de {a['total']}")
    i = r["recusa_indevida"]
    print(f"Recusa indevida: {len(i['base'])} -> {len(i['correcao'])} de {i['total']}")
    for classe, item in r["por_classe"].items():
        print(f"  {classe:18} {item['base']:2} -> {item['correcao']:2} de {item['total']:2}")
    print(f"Melhoraram: {', '.join(r['melhoraram']) or '-'}")
    print(f"Pioraram: {', '.join(r['pioraram']) or '-'}")
    for rotulo in ("base", "correcao"):
        print(f"Por camada ({rotulo}):")
        for camada, item in r["por_camada"][rotulo].items():
            print(f"  {camada or '-':15} decidiu {item['decidiu']:3}, errou {item['errou']:3}")


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analisador.add_argument("--perguntas", type=Path, required=True)
    analisador.add_argument("--base", type=Path, required=True)
    analisador.add_argument("--correcao", type=Path, required=True)
    analisador.add_argument("--saida", type=Path)
    analisador.add_argument("--contaminadas", default="",
                            help="ids fora da decisão, separados por vírgula (ex.: l-08)")
    args = analisador.parse_args(argv)
    conjunto = pontuar.carregar_conjunto(args.perguntas)
    pontuar.validar_conjunto(conjunto)
    base = json.loads(args.base.read_text(encoding="utf-8"))
    correcao = json.loads(args.correcao.read_text(encoding="utf-8"))
    contaminadas = [i.strip() for i in args.contaminadas.split(",") if i.strip()]
    try:
        resultado = comparar(conjunto, base, correcao, contaminadas)
    except ValueError as erro:
        raise SystemExit(str(erro)) from None
    _imprimir(resultado)
    if args.saida:
        args.saida.write_text(json.dumps(resultado, ensure_ascii=False, indent=2) + "\n",
                              encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
