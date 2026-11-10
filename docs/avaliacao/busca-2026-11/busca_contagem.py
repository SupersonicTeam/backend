#!/usr/bin/env python3
"""Contagem das respostas da rota de lei sobre o conjunto busca-2026-11-v1.

Escrito antes da medição e aplicado sem mudança. Só biblioteca padrão.

Entrada: o JSON de saída da ferramenta de citações, uma lista
``[{id, pergunta, esperado, aceito, entregues: [{posicao, norma, dispositivo,
similaridade}], rodadas: [{cru, ms, desfecho, texto, citacoes: [...]}]}]``.

Chave de artigo: norma + espaço + o dispositivo cortado na primeira vírgula, sem
``)`` final (``LC 214/2025 art. 4º``, ``EC 132/2023 art. 1º (redação do art. 156-A``,
``LC 214/2025 Anexo IX``). A citação ``"<norma> <dispositivo>"`` passa pela mesma
regra.

Rótulo de cada execução (rodada):
  - desfecho ``ERRO_MODELO``                         -> ``erro``
  - desfecho diferente de ``RESPONDIDA``              -> ``descarte``
  - ``RESPONDIDA`` com citação em esperado ∪ aceito   -> ``certa``
  - outra ``RESPONDIDA``                              -> ``outro_artigo``

Rótulo da pergunta: o rótulo de mais da metade das rodadas; sem essa maioria
(empate ou nenhuma rodada), ``sem_maioria``.

Busca (``entregues``, ordenados por ``posicao``): se alguma chave esperada está
entre os 5 primeiros (``top5``) e na primeira posição (``pos1``). Conta duas
vezes: só com ``esperado`` e com ``esperado ∪ aceito`` (``aceitavel``). Pergunta
sem esperado (grupo ``ausente``) não entra nessas contas.

Uso:
  python busca_contagem.py SAIDA.json [--conjunto PERGUNTAS.json] [--json RELATORIO.json]

Com ``--conjunto``, confere que cada pergunta da saída tem o mesmo esperado e
aceito do conjunto congelado e soma os rótulos por grupo.
"""

from __future__ import annotations

import argparse
import json
import sys
import unicodedata
from collections import Counter

ERRO = "erro"
DESCARTE = "descarte"
CERTA = "certa"
OUTRO_ARTIGO = "outro_artigo"
SEM_MAIORIA = "sem_maioria"

ROTULOS_RODADA = (CERTA, OUTRO_ARTIGO, DESCARTE, ERRO)
ROTULOS_PERGUNTA = ROTULOS_RODADA + (SEM_MAIORIA,)

DESFECHO_RESPONDIDA = "RESPONDIDA"
DESFECHO_ERRO_MODELO = "ERRO_MODELO"

TOP_N = 5


class ConjuntoDivergente(ValueError):
    """A saída traz esperado/aceito diferente do conjunto congelado."""


def chave(texto: str) -> str:
    """Normaliza ``"<norma> <dispositivo>"`` para a chave de artigo."""
    texto = unicodedata.normalize("NFC", str(texto))
    texto = " ".join(texto.split())
    texto = texto.split(",", 1)[0].strip()
    if texto.endswith(")"):
        texto = texto[:-1].rstrip()
    return texto


def chave_de(norma: str, dispositivo: str) -> str:
    return chave(f"{norma} {dispositivo}")


def rotulo_rodada(rodada: dict, aceitaveis: set[str]) -> str:
    desfecho = rodada.get("desfecho")
    if desfecho == DESFECHO_ERRO_MODELO:
        return ERRO
    if desfecho != DESFECHO_RESPONDIDA:
        return DESCARTE
    citadas = {chave(c) for c in (rodada.get("citacoes") or [])}
    return CERTA if citadas & aceitaveis else OUTRO_ARTIGO


def rotulo_maioria(rotulos: list[str]) -> str:
    if not rotulos:
        return SEM_MAIORIA
    rotulo, vezes = Counter(rotulos).most_common(1)[0]
    return rotulo if vezes * 2 > len(rotulos) else SEM_MAIORIA


def _ordenados(entregues: list[dict]) -> list[dict]:
    return sorted(entregues or [], key=lambda e: e.get("posicao", 0))


def busca(entregues: list[dict], chaves: set[str]) -> dict | None:
    """``{"top5": bool, "pos1": bool}``, ou ``None`` sem chave a procurar."""
    if not chaves:
        return None
    ordem = [chave_de(e.get("norma", ""), e.get("dispositivo", "")) for e in _ordenados(entregues)]
    return {
        "top5": any(k in chaves for k in ordem[:TOP_N]),
        "pos1": bool(ordem) and ordem[0] in chaves,
    }


def avaliar_pergunta(item: dict) -> dict:
    esperado = {chave(k) for k in item.get("esperado") or []}
    aceito = {chave(k) for k in item.get("aceito") or []}
    aceitaveis = esperado | aceito
    rotulos = [rotulo_rodada(r, aceitaveis) for r in item.get("rodadas") or []]
    entregues = item.get("entregues") or []
    return {
        "id": item.get("id"),
        "rodadas": rotulos,
        "rotulo": rotulo_maioria(rotulos),
        "busca_esperado": busca(entregues, esperado),
        "busca_aceitavel": busca(entregues, aceitaveis),
    }


def _mapa_conjunto(conjunto: dict) -> dict[str, dict]:
    return {p["id"]: p for p in conjunto.get("perguntas", [])}


def conferir_contra_conjunto(saida: list[dict], conjunto: dict) -> list[str]:
    """Erros de divergência; devolve os ids do conjunto ausentes na saída."""
    mapa = _mapa_conjunto(conjunto)
    for item in saida:
        pid = item.get("id")
        if pid not in mapa:
            raise ConjuntoDivergente(f"{pid}: id fora do conjunto")
        for campo in ("esperado", "aceito"):
            na_saida = sorted(chave(k) for k in item.get(campo) or [])
            no_conjunto = sorted(chave(k) for k in mapa[pid].get(campo) or [])
            if na_saida != no_conjunto:
                raise ConjuntoDivergente(f"{pid}: {campo} diferente do conjunto congelado")
    vistos = {item.get("id") for item in saida}
    return sorted(pid for pid in mapa if pid not in vistos)


def _somar_busca(resultados: list[dict], campo: str) -> dict:
    validos = [r[campo] for r in resultados if r[campo] is not None]
    return {
        "perguntas": len(validos),
        "top5": sum(1 for b in validos if b["top5"]),
        "pos1": sum(1 for b in validos if b["pos1"]),
    }


def _resumo(resultados: list[dict]) -> dict:
    por_pergunta = Counter(r["rotulo"] for r in resultados)
    por_rodada = Counter(rot for r in resultados for rot in r["rodadas"])
    return {
        "perguntas": len(resultados),
        "rotulo_pergunta": {k: por_pergunta.get(k, 0) for k in ROTULOS_PERGUNTA},
        "rotulo_rodada": {k: por_rodada.get(k, 0) for k in ROTULOS_RODADA},
        "busca_esperado": _somar_busca(resultados, "busca_esperado"),
        "busca_aceitavel": _somar_busca(resultados, "busca_aceitavel"),
    }


def contar(saida: list[dict], conjunto: dict | None = None) -> dict:
    faltando: list[str] = []
    grupos: dict[str, str] = {}
    if conjunto is not None:
        faltando = conferir_contra_conjunto(saida, conjunto)
        grupos = {pid: p.get("grupo", "") for pid, p in _mapa_conjunto(conjunto).items()}
    resultados = [avaliar_pergunta(item) for item in saida]
    for r in resultados:
        r["grupo"] = grupos.get(r["id"])
    relatorio = {"total": _resumo(resultados), "perguntas": resultados, "faltando": faltando}
    if grupos:
        relatorio["por_grupo"] = {
            g: _resumo([r for r in resultados if r["grupo"] == g])
            for g in sorted(set(grupos.values()))
        }
    return relatorio


def _linha_resumo(nome: str, resumo: dict) -> list[str]:
    rp = resumo["rotulo_pergunta"]
    be, ba = resumo["busca_esperado"], resumo["busca_aceitavel"]
    return [
        f"{nome}: {resumo['perguntas']} perguntas",
        "  pergunta: " + ", ".join(f"{k} {rp[k]}" for k in ROTULOS_PERGUNTA),
        "  rodada:   " + ", ".join(f"{k} {resumo['rotulo_rodada'][k]}" for k in ROTULOS_RODADA),
        f"  busca (esperado):  top5 {be['top5']}/{be['perguntas']}, pos1 {be['pos1']}/{be['perguntas']}",
        f"  busca (aceitável): top5 {ba['top5']}/{ba['perguntas']}, pos1 {ba['pos1']}/{ba['perguntas']}",
    ]


def texto_relatorio(relatorio: dict) -> str:
    linhas = _linha_resumo("total", relatorio["total"])
    for grupo, resumo in (relatorio.get("por_grupo") or {}).items():
        linhas += _linha_resumo(grupo, resumo)
    if relatorio["faltando"]:
        linhas.append("faltando na saída: " + ", ".join(relatorio["faltando"]))
    linhas.append("")
    for r in relatorio["perguntas"]:
        be = r["busca_esperado"]
        busca_txt = "-" if be is None else f"top5={'s' if be['top5'] else 'n'} pos1={'s' if be['pos1'] else 'n'}"
        linhas.append(f"{r['id']}\t{r['grupo'] or ''}\t{r['rotulo']}\t{'/'.join(r['rodadas'])}\t{busca_txt}")
    return "\n".join(linhas) + "\n"


def _ler_json(caminho: str):
    with open(caminho, encoding="utf-8") as f:
        return json.load(f)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("saida", help="JSON de saída da ferramenta de citações")
    parser.add_argument("--conjunto", help="conjunto congelado (perguntas-lei-busca-2026-11.json)")
    parser.add_argument("--json", dest="json_saida", help="grava o relatório completo em JSON")
    args = parser.parse_args(argv)

    saida = _ler_json(args.saida)
    conjunto = _ler_json(args.conjunto) if args.conjunto else None
    try:
        relatorio = contar(saida, conjunto)
    except ConjuntoDivergente as erro:
        print(f"erro: {erro}", file=sys.stderr)
        return 2
    if args.json_saida:
        with open(args.json_saida, "w", encoding="utf-8", newline="\n") as f:
            json.dump(relatorio, f, ensure_ascii=False, indent=2)
            f.write("\n")
    sys.stdout.write(texto_relatorio(relatorio))
    return 0


if __name__ == "__main__":
    sys.exit(main())
