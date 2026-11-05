#!/usr/bin/env python3
"""Gera (de novo) o relatorio comparativo de uma rodada ja medida (issue #348).

    python3 docs/avaliacao/benchmark/gerar_relatorio.py docs/avaliacao/resultados/benchmark/<rodada>
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ceres_benchmark import relatorio  # noqa: E402

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    pasta = Path(sys.argv[1])
    relatorio.gerar(pasta)
    print(pasta / "relatorio.md")
