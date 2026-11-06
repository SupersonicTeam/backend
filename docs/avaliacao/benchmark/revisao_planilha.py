#!/usr/bin/env python3
"""Planilha de revisao das respostas esperadas pela equipe e pelo orientador (issue #387).

    python3 docs/avaliacao/benchmark/revisao_planilha.py gerar
        -> revisao/planilha-revisao-<versao>.csv e .xlsx do conjunto atual

    python3 docs/avaliacao/benchmark/revisao_planilha.py aplicar <planilha preenchida .csv> --versao <nova>
        -> "sim" vira revisado {por, em} no conjunto (versao nova e hash); "nao", "duvida" e
           correcao sugerida vao para revisao/decisoes-<versao da planilha>.md; erro nao aplica nada

Preencha a planilha no Excel ou no LibreOffice e salve como CSV (separador ;, UTF-8). Depois de
aplicar, regere o SQL de conferencia e o exemplo ficticio (README, "Revisao humana").
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ceres_benchmark import revisao  # noqa: E402

if __name__ == "__main__":
    import argparse

    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    sub = p.add_subparsers(dest="comando", required=True)
    sub.add_parser("gerar")
    ap = sub.add_parser("aplicar")
    ap.add_argument("planilha")
    ap.add_argument("--versao", required=True, help="versao nova do conjunto, ex.: 2026-11-05.13")
    args = p.parse_args()
    conjunto = json.loads(revisao.CONJUNTO.read_text(encoding="utf-8"))
    if args.comando == "gerar":
        for caminho in revisao.gerar(conjunto):
            print(caminho)
        sys.exit(0)
    resumo_novo, resumo = revisao.aplicar(conjunto, revisao.ler_planilha(Path(args.planilha)), args.versao)
    saida = revisao.PASTA_REVISAO / f"decisoes-{conjunto['versao']}.md"
    saida.parent.mkdir(parents=True, exist_ok=True)
    saida.write_text(revisao.relatorio_md(resumo, conjunto["versao"], resumo_novo["versao"]), encoding="utf-8")
    print(saida)
    if resumo["erros"]:
        print("\n".join(resumo["erros"]), file=sys.stderr)
        sys.exit(1)
    if resumo["revisadas"]:
        revisao.gravar_conjunto(resumo_novo)
        revisao.gerar(resumo_novo)
        print(f"{len(resumo['revisadas'])} revisadas; conjunto {resumo_novo['versao']}")
