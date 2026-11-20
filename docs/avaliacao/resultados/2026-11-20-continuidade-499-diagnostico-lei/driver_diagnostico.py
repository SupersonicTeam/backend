#!/usr/bin/env python3
"""Rodada de DIAGNÓSTICO da correção de lei da continuidade (#584). Não é medição oficial.

Importa as classes do executar_continuidade.py SEM alterá-lo (Braco, Rodada) e roda só o braço ligado, só
as conversas que alimentam o M_lei_correcao (t-lei-04, t-lei-05 e d-lei-02), com um agendamento próprio:

- 36 execuções (12 de cada conversa), em 18 pares. Em cada par, a primeira execução vem DEPOIS DE UMA
  PAUSA (`--pausa` segundos, sem chamar a Ceres) e a segunda vem LOGO DEPOIS da primeira.
- cada execução é uma chamada a `Rodada.conversa`, a mesma do executor: par real, histórico e
  confirmação ao vivo, tudo igual à medição.
- antes de começar e antes de cada pausa e depois dela, registra se o bge-m3 está carregado
  (`ollama ps`, só leitura; nada é recarregado à força).

  CERES_SENHA=... python3 driver_diagnostico.py --pasta <worktree> --url http://localhost:8502 \\
      --banco agroconia_diag_lei_ligado --modelo anthropic/claude-haiku-5-5 --saida-dir <pasta>
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import subprocess
import sys
import time
from pathlib import Path

CONVERSAS = (("teste", "t-lei-04"), ("teste", "t-lei-05"), ("dev", "d-lei-02"))


def ollama_carregado(container: str) -> dict:
    """O bge-m3 está carregado? Só leitura (`ollama ps`)."""
    try:
        saida = subprocess.run(["docker", "exec", container, "ollama", "ps"], capture_output=True,
                               text=True, timeout=30, encoding="utf-8").stdout
    except (OSError, subprocess.SubprocessError):
        return {"bge_m3_carregado": None}
    linhas = [l for l in saida.splitlines()[1:] if l.strip()]
    return {"bge_m3_carregado": any("bge-m3" in l for l in linhas), "modelos_carregados": len(linhas)}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pasta", required=True, help="worktree limpo do commit medido")
    ap.add_argument("--url", required=True)
    ap.add_argument("--banco", required=True)
    ap.add_argument("--modelo", required=True)
    ap.add_argument("--saida-dir", required=True)
    ap.add_argument("--por-conversa", type=int, default=12)
    ap.add_argument("--pausa", type=float, default=360.0, help="segundos de pausa antes de cada execução 'depois da pausa'")
    ap.add_argument("--intervalo", type=float, default=3.2)
    ap.add_argument("--tentativas", type=int, default=3)
    ap.add_argument("--ollama", default="agrocontabil-ollama")
    args = ap.parse_args(argv)
    senha = os.environ.get("CERES_SENHA")
    if not senha:
        raise SystemExit("CERES_SENHA ausente")
    sys.path.insert(0, str(Path(args.pasta) / "docs" / "avaliacao" / "continuidade"))
    sys.dont_write_bytecode = True
    import executar_continuidade as ex  # noqa: E402
    import validar_conjunto as vc  # noqa: E402

    commit = ex.commit_limpo(args.pasta)
    carregados = {"teste": ex.carregar(vc.ARQUIVOS[1]), "dev": ex.carregar(vc.ARQUIVOS[0])}
    conjuntos = [carregados["teste"], carregados["dev"]]
    conversa_por_id = {(parte, c["id"]): (carregados[parte][0], carregados[parte][1], c)
                       for parte, _ in CONVERSAS for c in carregados[parte][0]["conversas"]
                       if (parte, c["id"]) in CONVERSAS}
    braco = ex.Braco("ligado", args.url.rstrip("/"), args.banco, senha, args.intervalo)
    saida_dir = Path(args.saida_dir)
    saida_dir.mkdir(parents=True, exist_ok=True)
    ns = argparse.Namespace(modelo=args.modelo, tentativas=args.tentativas, ids=None)
    with (saida_dir / "rodada.jsonl").open("a", encoding="utf-8") as saida:
        rodada = ex.Rodada(ns, {"ligado": braco}, conjuntos, commit, saida)
        rodada.preparar()
        braco.renovar()
        rodada.aquecer("ligado")
        rodada.autoteste()
        # 18 pares: a primeira de cada par vem depois de uma pausa, a segunda logo depois dela.
        # Cada conversa fica com 6 execuções "depois da pausa" e 6 "logo depois".
        plano = []
        for i in range(args.por_conversa * len(CONVERSAS) // 2):
            plano.append((CONVERSAS[i % 3], "apos_pausa"))
            plano.append((CONVERSAS[(i + 1) % 3], "logo_depois"))
        reps = {c: 0 for c in CONVERSAS}
        fim_anterior = time.monotonic()
        rodada.gravar({"tipo": "agenda_inicio", "braco": "ligado"}, dict(
            ollama=ollama_carregado(args.ollama), pausa_segundos=args.pausa,
            horario=ex.agora().isoformat(), unidades=len(plano)))
        for n, (chave, tipo) in enumerate(plano, start=1):
            estado_antes = None
            if tipo == "apos_pausa":
                estado_antes = ollama_carregado(args.ollama)
                print(f"[{n}/{len(plano)}] pausa de {args.pausa:.0f} s antes de {chave[1]}", flush=True)
                time.sleep(args.pausa)
            estado_depois = ollama_carregado(args.ollama) if tipo == "apos_pausa" else None
            conjunto, sha, conversa = conversa_por_id[chave]
            reps[chave] += 1
            braco.renovar()
            inicio = ex.agora()
            desde = round(time.monotonic() - fim_anterior, 1)
            rodada.gravar({"tipo": "agenda", "braco": "ligado"}, dict(
                unidade=n, parte=chave[0], conversa=chave[1], repeticao=reps[chave],
                tipo_unidade=tipo, horario_inicio=inicio.isoformat(), segundos_desde_a_anterior=desde,
                ollama_antes_da_pausa=estado_antes, ollama_depois_da_pausa=estado_depois))
            print(f"[{n}/{len(plano)}] {chave[1]} r{reps[chave]} ({tipo}) {inicio:%H:%M:%S}", flush=True)
            rodada.conversa("ligado", conjunto, sha, conversa, reps[chave])
            fim_anterior = time.monotonic()
        rodada.gravar({"tipo": "agenda_fim", "braco": "ligado"}, dict(
            ollama=ollama_carregado(args.ollama), horario=ex.agora().isoformat()))
    print(f"fim: {ex.agora():%H:%M:%S}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
