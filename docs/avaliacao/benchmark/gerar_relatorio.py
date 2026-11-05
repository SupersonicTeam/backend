#!/usr/bin/env python3
"""Gera (de novo) o relatorio comparativo de uma rodada ja medida (issue #348).

    python3 docs/avaliacao/benchmark/gerar_relatorio.py docs/avaliacao/resultados/benchmark/<rodada> \
        [--comparar-com docs/avaliacao/resultados/benchmark/<ensaio-anterior>]

Com --comparar-com, o relatorio ganha a comparacao celula a celula e aponta queda fora da faixa
(ex.: a Ceres 2.0 depois da Sessao 7, #270, contra um ensaio de antes dela).
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ceres_benchmark import relatorio  # noqa: E402

if __name__ == "__main__":
    import argparse

    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    p.add_argument("rodada")
    p.add_argument("--comparar-com")
    args = p.parse_args()
    pasta = Path(args.rodada)
    relatorio.gerar(pasta, comparar_com=Path(args.comparar_com) if args.comparar_com else None)
    print(pasta / "relatorio.md")
