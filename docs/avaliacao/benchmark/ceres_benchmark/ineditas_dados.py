"""Entrada das ineditas de dados da equipe (#358): ``ineditas-dados/perguntas.txt``.

Luiz e Herick escrevem ali, sem ler a Ceres nem o gabarito, uma pergunta por linha no formato
``AAAA-MM-DD | Autor | pergunta``. A pergunta entra **literal** no conjunto congelado (origem
``inedita_equipe_dados``, com ``autor`` e ``escritaEm``), com o valor esperado calculado a partir do
``SeedAvaliacao`` e testado, como nas N01-N13; a que nao tiver dado no seed fica so de rota.
"""

from __future__ import annotations

import re
from datetime import date
from pathlib import Path

ARQUIVO = Path(__file__).resolve().parent.parent / "ineditas-dados" / "perguntas.txt"
_LINHA = re.compile(r"^(\d{4}-\d{2}-\d{2})\s*\|\s*([^|]+?)\s*\|\s*(.+?)\s*$")


def ler(caminho: Path = ARQUIVO) -> list[dict]:
    """``[{escritaEm, autor, pergunta}]`` na ordem do arquivo.

    :raises ValueError: linha fora do formato, data invalida ou pergunta repetida (com o numero da
        linha), para o erro aparecer antes de a pergunta entrar no conjunto
    """
    saida, vistas = [], set()
    for numero, linha in enumerate(caminho.read_text(encoding="utf-8").splitlines(), 1):
        if not linha.strip() or linha.lstrip().startswith("#"):
            continue
        m = _LINHA.match(linha)
        if not m:
            raise ValueError(f"linha {numero}: use 'AAAA-MM-DD | Autor | pergunta'")
        try:
            date.fromisoformat(m.group(1))
        except ValueError as e:
            raise ValueError(f"linha {numero}: data invalida ({m.group(1)})") from e
        pergunta = m.group(3)
        if pergunta in vistas:
            raise ValueError(f"linha {numero}: pergunta repetida")
        vistas.add(pergunta)
        saida.append({"escritaEm": m.group(1), "autor": m.group(2), "pergunta": pergunta})
    return saida
