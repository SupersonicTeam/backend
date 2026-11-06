#!/usr/bin/env python3
"""Roda a matriz do benchmark da Ceres (issue #348) e gera o relatorio comparativo.

Da raiz do repositorio:  DB_PASSWORD=... python3 docs/avaliacao/benchmark/executar_matriz.py
Guia completo em docs/avaliacao/benchmark/README.md.
"""

import argparse
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))

from ceres_benchmark import executor, relatorio  # noqa: E402


def main() -> int:
    repo = AQUI.parents[2]
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    p.add_argument("--matriz", default=str(AQUI / "matriz.json"))
    p.add_argument("--modelos", help="lista separada por virgula (padrao: a da matriz)")
    p.add_argument("--arquiteturas", help="ceres-1,ceres-2 (padrao: as duas)")
    p.add_argument("--ref-ceres2", help="commit da Ceres 2.0 (padrao: origin/main)")
    p.add_argument("--repeticoes", type=int, help="padrao 3; so mude para depurar")
    p.add_argument("--hardware", help="descricao da maquina (padrao: detectada)")
    p.add_argument("--saida", default=str(repo / "docs/avaliacao/resultados/benchmark"))
    p.add_argument("--trabalho", default="~/.cache/agroconia-benchmark",
                   help="worktrees e JARs (fora do repositorio)")
    p.add_argument("--teste", action="store_true",
                   help="1 repeticao, para conferir o executor: NAO vale para a banca")
    p.add_argument("--ensaio", action="store_true",
                   help="roda antes da revisao humana das externas (#358): NAO vale para a banca")
    p.add_argument("--embedding-cpu", action="store_true",
                   help="bge-m3 sem GPU (cria bge-m3-cpu, num_gpu 0): VRAM pequena, ver README")
    p.add_argument("--comparar-com", help="rodada anterior (ex.: ensaio antes da #270) para o "
                   "relatorio apontar queda de acerto")
    p.add_argument("--cego", help="checkout do SupersonicTeam/ceres-benchmark-cego (padrao: "
                   "CERES_BENCHMARK_CEGO ou ../ceres-benchmark-cego); a rodada da banca o exige")
    p.add_argument("--retomar", help="pasta de uma rodada interrompida: pula as celulas medidas")
    args = p.parse_args()
    cfg = executor.montar_config(args, repo)
    rodada = executor.Executor(cfg).executar()
    relatorio.gerar(rodada, comparar_com=Path(args.comparar_com) if args.comparar_com else None)
    executor.log(f"relatorio em {rodada / 'relatorio.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
