#!/usr/bin/env python3
"""Confere, antes de medir, como o conjunto cai nas faixas das variantes da partição (#436).

Só biblioteca padrão. Para cada variante (``regimes-especificos`` e ``ampliada``):

- quais perguntas ``geral`` têm alguma chave de ``esperado`` ou ``aceito`` numa faixa;
- quais perguntas ``setor`` têm o ``esperado`` numa faixa e, nesse caso, se a regex de setor da
  faixa casa a pergunta normalizada (se não casa, a partição empurraria o trecho esperado para o
  fim);
- em quais perguntas alguma regex de setor casa (nessas, os trechos daquela faixa não descem).

A tabela dos regimes específicos é cópia da ``GuardaRegimeEspecifico`` (commit ``4a0b3a8``); a
ampliada é lida do JSON da tabela. Chave da LC 227/2026 com a redação de um artigo da LC 214/2025
(``art. 174 (redação do art. M``) conta como o art. M da LC 214. Artigo com sufixo entra na faixa
pelo número inteiro, exceto numa faixa de um artigo só (os arts. 471-A a 471-F não entram na 471,
pelas ``observacoes`` da tabela ampliada).

Uso:
  python conferir_faixas.py CONJUNTO.json TABELA-AMPLIADA.json
"""

from __future__ import annotations

import json
import re
import sys
import unicodedata

LC214 = "LC 214/2025"

REGIMES_ESPECIFICOS = [
    (172, 180, "combustíveis",
     "combust|gasolina|diesel|etanol|biodiesel|petroleo|gas natural|lubrificante|querosene|abastec"),
    (181, 233, "serviços financeiros",
     "financ|banc|segur|consorcio|previdencia|capitaliz|cambio|fundo|emprest|juro|cedula"
     "|\\bcpr\\b|cartao|\\bpix\\b|credito rural|custeio|debenture"),
    (234, 243, "planos de assistência à saúde", "saude|medic|hospital|assistencia"),
    (244, 250, "concursos de prognósticos", "prognost|loteria|aposta|sorteio|bingo|rifa|premio"),
    (251, 270, "bens imóveis",
     "imovel|imoveis|imobiliar|terreno|\\blote|loteamento|\\bsolo\\b|incorporac|locac|alug"
     "|arrend|cessao|constru|propriedade|fazenda|sitio|gleba|\\bterras?\\b|apartamento"
     "|\\bcasa\\b"),
    (271, 272, "sociedades cooperativas", "cooperativ"),
    (273, 291, "bares, restaurantes, hotelaria, transporte e turismo",
     "\\bbar\\b|\\bbares\\b|restaurante|lanchonete|hotel|hospedagem|pousada|turism|viagem"
     "|parque|transporte|passageiro"),
    (292, 296, "sociedade anônima do futebol", "futebol|\\bsaf\\b|clube"),
    (297, 299, "missões diplomáticas", "diplomat|consular|embaixada|tratado"),
    (300, 307, "disposições comuns aos regimes específicos", "regime especifico|regimes especificos"),
]

ARTIGO = re.compile(r"^art\. (\d+)(?:º)?(?:-([A-Z]))?")
REDACAO_LC214 = re.compile(r"^LC 227/2026 art\. 174 \(redação do art\. (\d+)(?:º)?(?:-([A-Z]))?")


def normalizar(texto: str) -> str:
    sem = unicodedata.normalize("NFD", texto or "")
    sem = "".join(c for c in sem if not unicodedata.category(c).startswith("M"))
    sem = unicodedata.normalize("NFC", sem)
    return " ".join(sem.lower().split())


def artigo_lc214(chave: str):
    """(número, sufixo) do artigo da LC 214/2025 a que a chave se refere, ou None."""
    m = REDACAO_LC214.match(chave)
    if m:
        return int(m.group(1)), m.group(2) or ""
    if chave.startswith(LC214 + " "):
        m = ARTIGO.match(chave[len(LC214) + 1:])
        if m:
            return int(m.group(1)), m.group(2) or ""
    return None


def faixa_de(chave: str, faixas):
    art = artigo_lc214(chave)
    if art is None:
        return None
    numero, sufixo = art
    for f in faixas:
        inicio, fim = f[0], f[1]
        if inicio <= numero <= fim and (not sufixo or inicio < fim):
            return f
    return None


def variantes(tabela_ampliada: dict):
    ampliada = [(x["inicio"], x["fim"], x["titulo"], x["regex"]) for x in tabela_ampliada["faixas"]]
    return {
        "regimes-especificos": REGIMES_ESPECIFICOS,
        "ampliada": REGIMES_ESPECIFICOS + ampliada,
    }


def conferir(conjunto: dict, tabela_ampliada: dict) -> dict:
    resultado = {}
    for nome, faixas in variantes(tabela_ampliada).items():
        geral_na_faixa = []
        setor = []
        com_regex = []
        for p in conjunto["perguntas"]:
            pergunta = normalizar(p["pergunta"])
            casadas = sorted({f[2] for f in faixas if re.search(f[3], pergunta)})
            if casadas:
                com_regex.append({"id": p["id"], "grupo": p["grupo"], "faixas": casadas})
            chaves = list(p["esperado"]) + list(p["aceito"])
            if p["grupo"] == "geral":
                caem = [(k, faixa_de(k, faixas)[2]) for k in chaves if faixa_de(k, faixas)]
                if caem:
                    geral_na_faixa.append({"id": p["id"], "chaves": caem})
            elif p["grupo"] == "setor":
                for k in p["esperado"]:
                    f = faixa_de(k, faixas)
                    setor.append({
                        "id": p["id"], "esperado": k, "faixa": f[2] if f else None,
                        "regex_casa": bool(f and re.search(f[3], pergunta)) if f else None,
                    })
        resultado[nome] = {"geral_com_chave_na_faixa": geral_na_faixa, "setor": setor,
                           "perguntas_com_regex_de_setor": com_regex}
    return resultado


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    with open(argv[0], encoding="utf-8") as f:
        conjunto = json.load(f)
    with open(argv[1], encoding="utf-8") as f:
        tabela = json.load(f)
    resultado = conferir(conjunto, tabela)
    for nome, r in resultado.items():
        print(f"== {nome}")
        print("geral com esperado/aceito numa faixa:", r["geral_com_chave_na_faixa"] or "nenhuma")
        print("setor:")
        for s in r["setor"]:
            situacao = "fora das faixas" if s["faixa"] is None else (
                f"faixa {s['faixa']}, regex de setor {'casa' if s['regex_casa'] else 'NAO casa'}")
            print(f"  {s['id']} {s['esperado']}: {situacao}")
        grupos = {}
        for c in r["perguntas_com_regex_de_setor"]:
            grupos.setdefault(c["grupo"], []).append(c)
        for g in ("geral", "setor", "ausente"):
            itens = grupos.get(g, [])
            print(f"perguntas {g} com alguma regex de setor casando: {len(itens)}")
            for c in itens:
                print(f"  {c['id']}: {', '.join(c['faixas'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
