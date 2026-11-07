#!/usr/bin/env python3
"""Troca as execuções com ERRO_MODELO pelas refeitas (regra-de-decisao.md, "Erro do modelo").

Só biblioteca padrão.

  python refazer_erros.py ids SAIDA.json
      imprime os ids com alguma execução ERRO_MODELO, separados por vírgula (vazio se nenhum)
  python refazer_erros.py mesclar SAIDA.json REFEITAS.json MESCLADA.json
      cada execução com ERRO_MODELO em SAIDA é trocada pela de mesmo índice em REFEITAS; imprime os
      ids em que a de mesmo índice também deu ERRO_MODELO (essas saem dos quatro braços)
"""

from __future__ import annotations

import json
import sys

ERRO = "ERRO_MODELO"


def ids_com_erro(saida: list[dict]) -> list[str]:
    return [i["id"] for i in saida if any(r.get("desfecho") == ERRO for r in i.get("rodadas") or [])]


def mesclar(saida: list[dict], refeitas: list[dict]) -> tuple[list[dict], list[str]]:
    novas = {i["id"]: i for i in refeitas}
    persistem = []
    mescladas = []
    for item in saida:
        rodadas = list(item.get("rodadas") or [])
        if item["id"] in novas:
            refeita = novas[item["id"]].get("rodadas") or []
            for indice, rodada in enumerate(rodadas):
                if rodada.get("desfecho") != ERRO:
                    continue
                nova = refeita[indice] if indice < len(refeita) else {"desfecho": ERRO}
                rodadas[indice] = nova
                if nova.get("desfecho") == ERRO and item["id"] not in persistem:
                    persistem.append(item["id"])
        elif any(r.get("desfecho") == ERRO for r in rodadas):
            persistem.append(item["id"])
        mescladas.append({**item, "rodadas": rodadas})
    return mescladas, persistem


def _ler(caminho: str):
    with open(caminho, encoding="utf-8") as f:
        return json.load(f)


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if argv[0] == "ids":
        print(",".join(ids_com_erro(_ler(argv[1]))))
        return 0
    if argv[0] == "mesclar":
        mescladas, persistem = mesclar(_ler(argv[1]), _ler(argv[2]))
        with open(argv[3], "w", encoding="utf-8", newline="\n") as f:
            json.dump(mescladas, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(",".join(persistem))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
