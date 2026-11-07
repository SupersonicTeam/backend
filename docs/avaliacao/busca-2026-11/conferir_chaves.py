#!/usr/bin/env python3
"""Confere o conjunto busca-2026-11-v1 contra a base de lei do Postgres local.

Só biblioteca padrão. Lê a tabela ``legislacao_trecho`` (só trechos vigentes,
``vigencia_fim = '9999-12-31'``) por ``docker exec`` no contêiner do Postgres, em
transação somente leitura, e confere:

- que toda chave de ``esperado`` e ``aceito`` existe no banco, com a mesma
  normalização do ``busca_contagem.chave`` (norma + dispositivo cortado na
  primeira vírgula, sem ``)`` final);
- a forma do conjunto: 40 perguntas, ids ``busca-01`` a ``busca-40``, grupos
  20 ``geral`` / 10 ``setor`` / 10 ``ausente``, ``aceito`` presente, ausente sem
  chave e geral/setor com ao menos uma chave em ``esperado``;
- no grupo ``setor``, quantas perguntas têm o esperado entre os arts. 172 e 307
  da LC 214/2025.

Não usa senha: o ``psql`` roda dentro do contêiner.

Uso:
  python conferir_chaves.py [CONJUNTO.json] [--container agrocontabil-postgres]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from busca_contagem import chave_de  # noqa: E402

PADRAO_CONJUNTO = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "treino", "perguntas-lei-busca-2026-11.json"
)
CONSULTA = (
    "select norma, dispositivo from legislacao_trecho "
    "where vigencia_fim = '9999-12-31' order by norma, dispositivo"
)
GRUPOS_ESPERADOS = {"geral": 20, "setor": 10, "ausente": 10}
TOTAL = 40


def chaves_do_banco(container: str) -> set[str]:
    comando = [
        "docker", "exec",
        "-e", "PGOPTIONS=-c default_transaction_read_only=on",
        container,
        "psql", "-U", "agrocontabil", "-d", "agrocontabil",
        "-At", "-F", "\t", "-c", CONSULTA,
    ]
    resultado = subprocess.run(comando, capture_output=True, check=True)
    texto = resultado.stdout.decode("utf-8")
    chaves = set()
    for linha in texto.splitlines():
        if not linha.strip():
            continue
        norma, dispositivo = linha.split("\t", 1)
        chaves.add(chave_de(norma, dispositivo))
    return chaves


def numero_artigo_lc214(k: str) -> int | None:
    m = re.fullmatch(r"LC 214/2025 art\. (\d+)(?:º)?(?:-[A-Z])?", k)
    return int(m.group(1)) if m else None


def conferir_forma(conjunto: dict) -> list[str]:
    erros = []
    perguntas = conjunto.get("perguntas", [])
    if conjunto.get("versao") != "busca-2026-11-v1":
        erros.append(f"versao inesperada: {conjunto.get('versao')!r}")
    if not conjunto.get("observacao"):
        erros.append("observacao vazia")
    ids = [p.get("id") for p in perguntas]
    if ids != [f"busca-{i:02d}" for i in range(1, TOTAL + 1)]:
        erros.append("ids fora da sequência busca-01 a busca-40")
    contagem = {g: sum(1 for p in perguntas if p.get("grupo") == g) for g in GRUPOS_ESPERADOS}
    if contagem != GRUPOS_ESPERADOS or len(perguntas) != TOTAL:
        erros.append(f"grupos {contagem} (total {len(perguntas)}), esperado {GRUPOS_ESPERADOS}")
    campos = {"id", "pergunta", "esperado", "aceito", "raciocinio", "grupo"}
    for p in perguntas:
        pid = p.get("id")
        if set(p) != campos:
            erros.append(f"{pid}: campos {sorted(p)}")
        if not isinstance(p.get("aceito"), list) or not isinstance(p.get("esperado"), list):
            erros.append(f"{pid}: esperado/aceito precisam ser listas")
            continue
        if not p.get("pergunta") or not p.get("raciocinio"):
            erros.append(f"{pid}: pergunta ou raciocinio vazio")
        if p.get("grupo") == "ausente" and (p["esperado"] or p["aceito"]):
            erros.append(f"{pid}: ausente com chave")
        if p.get("grupo") in ("geral", "setor") and not p["esperado"]:
            erros.append(f"{pid}: sem esperado")
        if set(p["esperado"]) & set(p["aceito"]):
            erros.append(f"{pid}: chave repetida em esperado e aceito")
    return erros


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("conjunto", nargs="?", default=PADRAO_CONJUNTO)
    parser.add_argument("--container", default="agrocontabil-postgres")
    args = parser.parse_args(argv)

    with open(args.conjunto, encoding="utf-8") as f:
        conjunto = json.load(f)
    perguntas = conjunto.get("perguntas", [])

    banco = chaves_do_banco(args.container)
    print(f"chaves de artigo vigentes no banco: {len(banco)}")

    erros = conferir_forma(conjunto)
    total_chaves = 0
    for p in perguntas:
        for campo in ("esperado", "aceito"):
            for k in p.get(campo) or []:
                total_chaves += 1
                if k not in banco:
                    erros.append(f"{p.get('id')}: {campo} {k!r} não existe no banco")
        marcas = ", ".join(p.get("esperado") or []) or "(vazio)"
        extra = f" | aceito: {', '.join(p['aceito'])}" if p.get("aceito") else ""
        print(f"{p.get('id')} [{p.get('grupo')}] esperado: {marcas}{extra}")

    setor = [p for p in perguntas if p.get("grupo") == "setor"]
    na_faixa = [
        p["id"] for p in setor
        if any(172 <= (numero_artigo_lc214(k) or 0) <= 307 for k in p.get("esperado") or [])
    ]
    contagem = {g: sum(1 for p in perguntas if p.get("grupo") == g) for g in GRUPOS_ESPERADOS}
    print(f"grupos: {contagem}")
    print(f"setor com esperado entre os arts. 172 e 307 da LC 214/2025: {len(na_faixa)} ({', '.join(na_faixa)})")
    print(f"chaves conferidas: {total_chaves}")

    if erros:
        for e in erros:
            print(f"ERRO {e}")
        return 1
    print("OK: todas as chaves existem no banco e a forma do conjunto confere")
    return 0


if __name__ == "__main__":
    sys.exit(main())
