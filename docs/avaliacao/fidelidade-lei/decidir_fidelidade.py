#!/usr/bin/env python3
"""Aplica a regra de decisão (`regra-de-decisao.md`) a uma rodada da medição de fidelidade (#269).

    python3 -I decidir_fidelidade.py RODADA

Lê, da pasta da rodada: `execucao.json`, `respostas-brutas.jsonl`, `leitura/chave.json` e as leituras
`leitura/juizes/J1|J2|J3/*.json`. Grava `resultado.json` e `resultado.md` ao lado, sem o texto das
respostas, e imprime o desfecho (`ENTRA`, `NAO_ENTRA` ou `INCONCLUSIVA`) com o motivo. Sai com 0 em
qualquer desfecho válido e com 2 se faltar arquivo ou leitura (rodada incompleta).
"""

import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fidelidade_comum as comum  # noqa: E402
import leitura_fidelidade as leitura  # noqa: E402

R = comum.REGRA


class RodadaIncompleta(Exception):
    """Falta arquivo, leitura de juiz ou item: a decisão não pode ser calculada."""


# --- Montagem das execuções ---------------------------------------------------------------------

def vereditos_finais(j1: dict, j2: dict, j3: dict, itens: list) -> dict:
    """{id do item: (veredito, subtipo, concordaram)} pelo consenso dos juízes (regra, seção 4.3)."""
    finais = {}
    for rid in itens:
        if rid not in j1 or rid not in j2:
            raise RodadaIncompleta(f"o item {rid} não foi lido por J1 e J2")
        l1, l2 = comum.veredito_de_leitura(j1[rid]), comum.veredito_de_leitura(j2[rid])
        leituras = [l1, l2]
        if l1[0] != l2[0]:
            if rid not in j3:
                raise RodadaIncompleta(f"o item {rid} está em desacordo e o J3 não o leu")
            leituras.append(comum.veredito_de_leitura(j3[rid]))
        finais[rid] = comum.consenso(leituras)
    return finais


def montar_execucoes(ultimas: dict, esperadas: list, chave: dict, finais: dict) -> list:
    """Uma entrada por (pergunta, braço, repetição) esperada; o que faltou é falha técnica."""
    por_execucao = {(c["id"], c["braco"], c["repeticao"]): rid for rid, c in chave.items()}
    execs = []
    for pid, braco, rep in esperadas:
        r = ultimas.get((pid, braco, rep))
        if r is None:
            execs.append({"id": pid, "braco": braco, "rep": rep, "estado": "falha",
                          "motivo": "execução ausente", "veredito": None, "subtipo": None,
                          "latenciaMs": None, "tokens": 0})
            continue
        classe, motivo = comum.classificar_execucao(r)
        e = {"id": pid, "braco": braco, "rep": rep, "estado": classe, "motivo": motivo,
             "veredito": None, "subtipo": None, "latenciaMs": r.get("latenciaMs"),
             "tokens": comum.tokens_da_auditoria(r.get("auditoria"))}
        if classe == "aceita":
            rid = por_execucao.get((pid, braco, rep))
            if rid is None or rid not in finais:
                raise RodadaIncompleta(f"a execução aceita {pid}/{braco}/{rep} não está na leitura")
            e["veredito"], e["subtipo"], _ = finais[rid]
            if e["veredito"] == "RECUSA":
                e["estado"] = "recusa"
                e["motivo"] = "aceita lida como recusa"
        execs.append(e)
    return execs


# --- Medidas ------------------------------------------------------------------------------------

def defeito(e: dict):
    """'INFIEL', 'CITACAO' ou None: o defeito de uma execução aceita (regra, seção 4.2)."""
    if e["estado"] != "aceita":
        return None
    if e["veredito"] == "INFIEL":
        return "INFIEL"
    if e["veredito"] == "COM_RESSALVA" and e["subtipo"] == "CITACAO":
        return "CITACAO"
    return None


def medidas_do_braco(execs: list, com_base: dict, braco: str) -> dict:
    do_braco = [e for e in execs if e["braco"] == braco]
    validas = [e for e in do_braco if e["estado"] != "falha"]
    base = [e for e in validas if com_base[e["id"]]]
    ausentes = [e for e in validas if not com_base[e["id"]]]
    conta = lambda lista, f: sum(1 for e in lista if f(e))  # noqa: E731
    n = len(base) or None
    latencias = [e["latenciaMs"] for e in validas if e["latenciaMs"] is not None]
    tokens = [e["tokens"] for e in validas]
    return {
        "execucoes": len(do_braco), "falhasTecnicas": len(do_braco) - len(validas),
        "validas": len(validas), "validasComBase": len(base), "validasAusentes": len(ausentes),
        "infiel": conta(base, lambda e: defeito(e) == "INFIEL"),
        "ressalvaCitacao": conta(base, lambda e: defeito(e) == "CITACAO"),
        "ressalvaOutra": conta(base, lambda e: e["estado"] == "aceita"
                               and e["veredito"] == "COM_RESSALVA" and e["subtipo"] == "OUTRA"),
        "fiel": conta(base, lambda e: e["estado"] == "aceita" and e["veredito"] == "FIEL"),
        "aceitasComBase": conta(base, lambda e: e["estado"] == "aceita"),
        "recusasComBase": conta(base, lambda e: e["estado"] == "recusa"),
        "descartadasComBase": conta(base, lambda e: e["estado"] == "descartada"),
        "taxaAceitas": conta(base, lambda e: e["estado"] == "aceita") / n if n else None,
        "taxaRecusaFalsa": conta(base, lambda e: e["estado"] == "recusa") / n if n else None,
        "taxaInfielEntreAceitas": (conta(base, lambda e: defeito(e) == "INFIEL")
                                   / conta(base, lambda e: e["estado"] == "aceita")
                                   if conta(base, lambda e: e["estado"] == "aceita") else None),
        "ausenteInfiel": conta(ausentes, lambda e: defeito(e) == "INFIEL"),
        "ausenteAcertos": conta(ausentes, lambda e: defeito(e) != "INFIEL"),
        "p50Ms": comum.percentil(latencias, 50), "p95Ms": comum.percentil(latencias, 95),
        "tokensMedios": comum.media(tokens),
    }


def visao_por_pergunta(execs: list, com_base: dict, nomes: tuple = comum.BRACOS) -> dict:
    """Por pergunta e braço: execuções válidas, com defeito e a maioria (regra, seção 6)."""
    minimo = R["minimoDeExecucoesValidasPorBraco"]
    por = {}
    for e in execs:
        if e["estado"] == "falha" or e["braco"] not in nomes:
            continue
        por.setdefault(e["id"], {b: [] for b in nomes})[e["braco"]].append(e)
    elegiveis, excluidas = {}, []
    for pid, bracos in sorted(por.items()):
        if all(len(bracos[b]) >= minimo for b in nomes):
            elegiveis[pid] = bracos
        else:
            excluidas.append(pid)
    ids_sem_nenhuma = sorted(set(com_base) - set(por))
    excluidas += [p for p in ids_sem_nenhuma if p not in excluidas]
    resumo = {}
    for pid, bracos in elegiveis.items():
        linha = {}
        for b, lista in bracos.items():
            defeitos = sum(1 for e in lista if defeito(e))
            infieis = sum(1 for e in lista if defeito(e) == "INFIEL")
            aceitas = sum(1 for e in lista if e["estado"] == "aceita")
            linha[b] = {"validas": len(lista), "defeitos": defeitos, "infieis": infieis,
                        "aceitas": aceitas, "aceitaEmMaioria": aceitas * 2 > len(lista),
                        "defeituosa": defeitos * 2 > len(lista),
                        "afirmaEmMaioria": infieis * 2 > len(lista),
                        "algumaRepeticao": defeitos > 0}
        resumo[pid] = linha
    return {"porPergunta": resumo, "excluidas": sorted(set(excluidas))}


def intervalo_da_queda(com_base: dict, resumo: dict, base: str, comparado: str) -> dict:
    """Bootstrap por pergunta (regra, seção 7.1) da queda de defeitos base - comparado."""
    ids = [p for p in resumo if com_base[p]]
    if not ids:
        return {"inferior": None, "superior": None, "pontual": 0, "perguntas": 0}
    dif = {p: resumo[p][base]["defeitos"] - resumo[p][comparado]["defeitos"] for p in ids}
    sorteador = random.Random(R["bootstrapSemente"])
    somas = []
    for _ in range(R["bootstrapReamostragens"]):
        somas.append(sum(dif[sorteador.choice(ids)] for _ in ids))
    cauda = (1 - R["icConfianca"]) / 2
    return {"inferior": comum.quantil(somas, cauda), "superior": comum.quantil(somas, 1 - cauda),
            "pontual": sum(dif.values()), "perguntas": len(ids)}


# --- Decisão ------------------------------------------------------------------------------------

def teste(nome: str, valor, limite, ok: bool, descricao: str) -> dict:
    return {"teste": nome, "valor": valor, "limite": limite, "ok": bool(ok), "descricao": descricao}


def avaliar(execs: list, com_base: dict, info_leitura: dict, interrompida=None,
            base: str = "desligada", comparado: str = "ligada") -> dict:
    """Uma comparação: o braço `comparado` contra o `base`, com a regra da seção 7."""
    execs = [e for e in execs if e["braco"] in (base, comparado)]
    off = medidas_do_braco(execs, com_base, base)
    on = medidas_do_braco(execs, com_base, comparado)
    visao = visao_por_pergunta(execs, com_base, (base, comparado))
    resumo = visao["porPergunta"]
    ic = intervalo_da_queda(com_base, resumo, base, comparado)

    defeitos_off = off["infiel"] + off["ressalvaCitacao"]
    defeitos_on = on["infiel"] + on["ressalvaCitacao"]
    queda = defeitos_off - defeitos_on
    queda_rel = queda / defeitos_off if defeitos_off else 0.0

    perguntas_base = [p for p in resumo if com_base[p]]
    perguntas_aus = [p for p in resumo if not com_base[p]]
    defeituosas = {b: sum(1 for p in perguntas_base if resumo[p][b]["defeituosa"])
                   for b in (base, comparado)}
    qualquer = {b: sum(1 for p in perguntas_base if resumo[p][b]["algumaRepeticao"])
                for b in (base, comparado)}
    regressoes = [p for p in perguntas_base
                  if resumo[p][base]["defeitos"] == 0 and resumo[p][comparado]["defeituosa"]]
    aus_novas = [p for p in perguntas_aus
                 if resumo[p][comparado]["afirmaEmMaioria"] and not resumo[p][base]["afirmaEmMaioria"]]
    # Falso descarte (regra, seção 5, M2b): pergunta com base que no braço base não é defeituosa e é
    # aceita na maioria das repetições e que, no comparado, deixa de ser aceita na maioria.
    falsos_descartes = [p for p in perguntas_base
                        if not resumo[p][base]["defeituosa"] and resumo[p][base]["aceitaEmMaioria"]
                        and not resumo[p][comparado]["aceitaEmMaioria"]]

    def razao(a, b):
        return None if a is None or not b else a / b

    def diferenca(a, b):
        return None if a is None or b is None else round(a - b, 4)

    nao_piora = [
        teste("M2b falso descarte por pergunta", len(falsos_descartes), R["falsoDescarteMaximo"],
              len(falsos_descartes) <= R["falsoDescarteMaximo"],
              "no máximo 3 perguntas boas e aceitas na maioria no base que deixam de ser aceitas"),
        teste("M3 recusa falsa", diferenca(on["taxaRecusaFalsa"], off["taxaRecusaFalsa"]),
              R["recusaFalsaAltaMaxima"],
              on["taxaRecusaFalsa"] is not None and off["taxaRecusaFalsa"] is not None
              and on["taxaRecusaFalsa"] <= off["taxaRecusaFalsa"] + R["recusaFalsaAltaMaxima"],
              "comparado <= base + 5 pontos percentuais"),
        teste("M4 lei ausente (execuções que afirmam)", on["ausenteInfiel"] - off["ausenteInfiel"],
              R["ausenteInfielAltaMaxima"],
              on["ausenteInfiel"] <= off["ausenteInfiel"] + R["ausenteInfielAltaMaxima"],
              "comparado <= base + 1 execução"),
        teste("M4 lei ausente (perguntas que passam a afirmar em maioria)", len(aus_novas), 0,
              not aus_novas, "nenhuma pergunta ausente com maioria afirmando só no comparado"),
        teste("M5 p50", razao(on["p50Ms"], off["p50Ms"]), R["p50Fator"],
              off["p50Ms"] is not None and on["p50Ms"] is not None
              and on["p50Ms"] <= off["p50Ms"] * R["p50Fator"], "p50 comparado <= 2,0 x base"),
        teste("M5 p95", razao(on["p95Ms"], off["p95Ms"]), R["p95Fator"],
              off["p95Ms"] is not None and on["p95Ms"] is not None
              and on["p95Ms"] <= off["p95Ms"] * R["p95Fator"], "p95 comparado <= 2,0 x base"),
        teste("M5 p95 (teto absoluto)", on["p95Ms"], R["p95TetoMs"],
              on["p95Ms"] is not None and on["p95Ms"] <= R["p95TetoMs"],
              "p95 do comparado <= 10 segundos"),
        teste("M6 tokens", razao(on["tokensMedios"], off["tokensMedios"]), R["tokensFator"],
              off["tokensMedios"] is not None and on["tokensMedios"] is not None
              and on["tokensMedios"] <= off["tokensMedios"] * R["tokensFator"],
              "média comparado <= 2,0 x base"),
        teste("Piora de INFIEL", on["infiel"] - off["infiel"], R["pioraMaximaPorTipo"],
              on["infiel"] <= off["infiel"] + R["pioraMaximaPorTipo"],
              "INFIEL comparado <= base + 1"),
        teste("Piora de ressalva de citação", on["ressalvaCitacao"] - off["ressalvaCitacao"],
              R["pioraMaximaPorTipo"],
              on["ressalvaCitacao"] <= off["ressalvaCitacao"] + R["pioraMaximaPorTipo"],
              "ressalva de citação comparado <= base + 1"),
        teste("Regressões por pergunta", len(regressoes), R["regressoesPorPerguntaMaximas"],
              len(regressoes) <= R["regressoesPorPerguntaMaximas"],
              "no máximo 1 pergunta sem defeito no base e defeituosa em maioria no comparado"),
        teste("Perguntas defeituosas por maioria", defeituosas[comparado] - defeituosas[base], 0,
              defeituosas[comparado] <= defeituosas[base],
              "o comparado não pode ter mais perguntas defeituosas que o base"),
    ]
    violadas = [t for t in nao_piora if not t["ok"]]

    melhora = [
        teste("M1 defeitos (queda absoluta)", queda, R["melhoraAbsolutaMinima"],
              queda >= R["melhoraAbsolutaMinima"], "queda de pelo menos 4 execuções"),
        teste("M1 defeitos (queda relativa)", round(queda_rel, 4), R["melhoraRelativaMinima"],
              queda_rel >= R["melhoraRelativaMinima"], "queda de pelo menos 25%"),
        teste("M1 IC de 80% (limite inferior)", ic["inferior"], 0,
              ic["inferior"] is not None and ic["inferior"] > 0,
              "o limite inferior da queda por pergunta fica acima de zero"),
    ]

    falha_max = max((off["falhasTecnicas"] / off["execucoes"] if off["execucoes"] else 1.0),
                    (on["falhasTecnicas"] / on["execucoes"] if on["execucoes"] else 1.0))
    kappa = info_leitura.get("kappa")
    desacordo = info_leitura.get("desacordo")

    if interrompida:
        desfecho, motivo = "INCONCLUSIVA", f"rodada interrompida: {interrompida}"
    elif falha_max > R["falhaTecnicaMaxima"]:
        desfecho = "INCONCLUSIVA"
        motivo = f"rodada inválida: falha técnica de {falha_max:.0%} em um braço (limite 10%)"
    elif (kappa is not None and kappa < R["kappaMinimo"]) or \
            (desacordo is not None and desacordo > R["desacordoMaximo"]):
        desfecho = "INCONCLUSIVA"
        motivo = (f"leitura não confiável (kappa {kappa if kappa is None else round(kappa, 2)}, "
                  f"desacordo {desacordo if desacordo is None else f'{desacordo:.0%}'})")
    elif violadas:
        desfecho = "NAO_ENTRA"
        motivo = "fora da margem: " + "; ".join(t["teste"] for t in violadas)
    elif defeitos_off < R["defeitosMinimosNaBase"]:
        desfecho = "INCONCLUSIVA"
        motivo = (f"o braço {base} tem só {defeitos_off} defeitos de fidelidade "
                  f"(mínimo {R['defeitosMinimosNaBase']}): não há o que medir")
    elif defeitos_on >= defeitos_off:
        desfecho = "NAO_ENTRA"
        motivo = f"sem melhora: {defeitos_on} defeitos no {comparado} contra {defeitos_off} no {base}"
    elif all(t["ok"] for t in melhora):
        desfecho = "ENTRA"
        motivo = (f"defeitos de fidelidade de {defeitos_off} para {defeitos_on} "
                  f"(queda de {queda_rel:.0%}), IC de 80% acima de zero e nenhuma margem violada")
    else:
        desfecho = "INCONCLUSIVA"
        motivo = ("melhora abaixo do mínimo ou dentro do ruído: "
                  + "; ".join(t["teste"] for t in melhora if not t["ok"]))

    return {
        "desfecho": desfecho, "motivo": motivo, "versaoDaRegra": comum.VERSAO_REGRA,
        "base": base, "comparado": comparado,
        "braco": {base: off, comparado: on},
        "m1": {"defeitosBase": defeitos_off, "defeitosComparado": defeitos_on,
               "queda": queda, "quedaRelativa": queda_rel, "ic80": ic},
        "porPergunta": {"defeituosasPorMaioria": defeituosas, "comDefeitoEmQualquerRepeticao": qualquer,
                        "regressoes": regressoes, "ausentesQuePassamAAfirmar": aus_novas,
                        "falsosDescartes": falsos_descartes,
                        "excluidas": visao["excluidas"], "tabela": resumo},
        "testesDeNaoPiora": nao_piora, "testesDeMelhora": melhora,
    }


# --- Rodada em disco ----------------------------------------------------------------------------

def informacao_da_leitura(j1: dict, j2: dict, lidas: list) -> dict:
    pares = [(comum.veredito_de_leitura(j1[r])[0], comum.veredito_de_leitura(j2[r])[0])
             for r in lidas if r in j1 and r in j2]
    concordancia, kappa = comum.kappa_de_cohen(pares)
    desacordo = (sum(1 for a, b in pares if a != b) / len(pares)) if pares else None
    return {"itensLidos": len(pares), "concordanciaJ1J2": concordancia, "kappaJ1J2": kappa,
            "kappa": kappa, "desacordo": desacordo, "itensAoJ3": sum(1 for a, b in pares if a != b),
            "avisoKappa": kappa is not None and comum.REGRA["kappaMinimo"] <= kappa
            < comum.REGRA["kappaAviso"],
            "juizes": comum.JUIZES}


def decidir_rodada(rodada: Path) -> dict:
    """Resultado de uma rodada: uma comparação de cada braço contra o `desligada`."""
    for nome in ("execucao.json", "respostas-brutas.jsonl", "leitura/chave.json"):
        if not (rodada / nome).exists():
            raise RodadaIncompleta(f"falta {nome} em {rodada}")
    manifesto = json.loads((rodada / "execucao.json").read_text(encoding="utf-8"))
    conjunto = comum.carregar_conjunto()
    if manifesto["conjunto"]["sha256"] != comum.sha256_arquivo(comum.CONJUNTO):
        raise RodadaIncompleta("o conjunto da rodada não é o conjunto congelado atual")
    bracos = manifesto.get("bracos") or list(comum.BRACOS)
    if bracos[0] != comum.BASE or len(bracos) < 2:
        raise RodadaIncompleta(f"o primeiro braço do manifesto tem de ser '{comum.BASE}' e ter outro "
                               f"para comparar ({bracos})")
    perguntas = comum.perguntas_por_id(conjunto)
    com_base = {pid: bool(p["temBaseLegal"]) for pid, p in perguntas.items()}
    ids_rodada = sorted({r["id"] for r in comum.ler_jsonl(rodada / "respostas-brutas.jsonl")})
    esperadas = [(pid, b, rep) for pid in ids_rodada for b in bracos
                 for rep in range(1, manifesto["repeticoes"] + 1)]
    planejadas = manifesto.get("execucoesPlanejadas")
    if planejadas and not manifesto.get("interrompida") and len(esperadas) != planejadas:
        raise RodadaIncompleta(f"o manifesto planejou {planejadas} execuções e as respostas brutas "
                               f"cobrem {len(esperadas)}: pergunta ausente da rodada")
    ultimas = leitura.ultimas_execucoes(comum.ler_jsonl(rodada / "respostas-brutas.jsonl"))
    chave = json.loads((rodada / "leitura" / "chave.json").read_text(encoding="utf-8"))
    j1, j2, j3 = (leitura.ler_leituras(rodada, j) for j in ("J1", "J2", "J3"))
    finais = vereditos_finais(j1, j2, j3, sorted(chave))
    execs = montar_execucoes(ultimas, esperadas, chave, finais)
    com_base_da_rodada = {pid: com_base[pid] for pid in ids_rodada}
    info = informacao_da_leitura(j1, j2, sorted(chave))
    comparacoes = {b: avaliar(execs, com_base_da_rodada, info, manifesto.get("interrompida"),
                              comum.BASE, b) for b in bracos[1:]}
    return {"versaoDaRegra": comum.VERSAO_REGRA, "bracos": bracos,
            "desfechos": {b: c["desfecho"] for b, c in comparacoes.items()},
            "escolha": escolher_entre_bracos(comparacoes),
            "comparacoes": comparacoes, "leitura": info,
            "rodada": {"commit": manifesto.get("commit"), "conjunto": manifesto["conjunto"],
                       "modelo": manifesto.get("modelo"), "repeticoes": manifesto["repeticoes"]}}


def escolher_entre_bracos(comparacoes: dict) -> dict:
    """Entre os braços que deram `ENTRA`, o escolhido (regra, seção 7.4): menos `INFIEL`; no empate,
    menos falsos descartes; depois, menos tokens por execução (o mais barato); por fim, o nome."""
    entram = {b: c for b, c in comparacoes.items() if c["desfecho"] == "ENTRA"}
    if not entram:
        return {"escolhido": None, "candidatos": [], "criterio": "nenhum braço deu ENTRA"}

    def chave(item):
        nome, c = item
        medidas = c["braco"][nome]
        return (medidas["infiel"], len(c["porPergunta"]["falsosDescartes"]),
                medidas["tokensMedios"] if medidas["tokensMedios"] is not None else float("inf"), nome)

    ordenados = sorted(entram.items(), key=chave)
    return {"escolhido": ordenados[0][0], "candidatos": [n for n, _ in ordenados],
            "criterio": "menos INFIEL, depois menos falsos descartes, depois menos tokens"}


def percentual(x):
    return "—" if x is None else f"{x:.1%}"


def secao_md(c: dict) -> list:
    base, comp = c["base"], c["comparado"]
    off, on = c["braco"][base], c["braco"][comp]
    linhas = [f"## {base} × {comp}: {c['desfecho']}", "", f"{c['motivo']}", "",
              f"| Medida | {base} | {comp} |", "|---|---|---|"]
    for nome, chave in (("execuções", "execucoes"), ("falhas técnicas", "falhasTecnicas"),
                        ("válidas com base", "validasComBase"), ("INFIEL", "infiel"),
                        ("COM_RESSALVA/citação", "ressalvaCitacao"),
                        ("COM_RESSALVA/outra", "ressalvaOutra"), ("FIEL", "fiel"),
                        ("aceitas (com base)", "aceitasComBase"),
                        ("recusas (com base)", "recusasComBase"),
                        ("descartadas (com base)", "descartadasComBase"),
                        ("lei ausente que afirma", "ausenteInfiel")):
        linhas.append(f"| {nome} | {off[chave]} | {on[chave]} |")
    for nome, chave in (("taxa de aceitas (M2, informativa)", "taxaAceitas"),
                        ("taxa de recusa falsa", "taxaRecusaFalsa"),
                        ("INFIEL entre as aceitas", "taxaInfielEntreAceitas")):
        linhas.append(f"| {nome} | {percentual(off[chave])} | {percentual(on[chave])} |")
    linhas.append(f"| p50 / p95 (ms) | {off['p50Ms']} / {off['p95Ms']} | {on['p50Ms']} / {on['p95Ms']} |")
    tok = lambda b: "—" if b["tokensMedios"] is None else f"{b['tokensMedios']:.0f}"  # noqa: E731
    linhas.append(f"| tokens médios | {tok(off)} | {tok(on)} |")
    m1 = c["m1"]
    linhas += ["", f"**M1:** defeitos {m1['defeitosBase']} → {m1['defeitosComparado']} "
               f"(queda {m1['queda']}, {m1['quedaRelativa']:.0%}); IC de 80% da queda por pergunta: "
               f"[{m1['ic80']['inferior']}, {m1['ic80']['superior']}] "
               f"({m1['ic80']['perguntas']} perguntas).", "",
               "### Testes de melhora", "", "| Teste | Valor | Limite | OK |", "|---|---|---|---|"]
    for t in c["testesDeMelhora"]:
        linhas.append(f"| {t['teste']} | {t['valor']} | {t['limite']} | {'sim' if t['ok'] else 'NÃO'} |")
    linhas += ["", "### Testes de não piora", "", "| Teste | Valor | Limite | OK |", "|---|---|---|---|"]
    for t in c["testesDeNaoPiora"]:
        linhas.append(f"| {t['teste']} | {t['valor']} | {t['limite']} | {'sim' if t['ok'] else 'NÃO'} |")
    pp = c["porPergunta"]
    linhas += ["", "### Por pergunta", "",
               f"Defeituosas por maioria: {base} {pp['defeituosasPorMaioria'][base]}, "
               f"{comp} {pp['defeituosasPorMaioria'][comp]}. Com defeito em qualquer repetição: "
               f"{base} {pp['comDefeitoEmQualquerRepeticao'][base]}, "
               f"{comp} {pp['comDefeitoEmQualquerRepeticao'][comp]}. "
               f"Regressões: {pp['regressoes'] or 'nenhuma'}. Falsos descartes: "
               f"{pp['falsosDescartes'] or 'nenhum'}. Ausentes que passam a afirmar: "
               f"{pp['ausentesQuePassamAAfirmar'] or 'nenhuma'}. Excluídas: "
               f"{pp['excluidas'] or 'nenhuma'}.", ""]
    return linhas


def relatorio_md(r: dict) -> str:
    rod = r.get("rodada", {})
    linhas = ["# Resultado da medição de fidelidade da rota de lei (#269)", "",
              f"Regra `{r['versaoDaRegra']}`; commit `{rod.get('commit')}`; modelo "
              f"`{rod.get('modelo')}`; {rod.get('repeticoes')} repetições; braços {r['bracos']}. "
              "Cada braço é comparado ao desligada.", ""]
    for c in r["comparacoes"].values():
        linhas += secao_md(c)
    if len(r["comparacoes"]) > 1 and r.get("escolha"):
        esc = r["escolha"]
        linhas += ["## Escolha entre os braços", "",
                   f"Escolhido: {esc['escolhido'] or 'nenhum'} (candidatos com ENTRA, em ordem: "
                   f"{esc['candidatos']}); critério: {esc['criterio']}.", ""]
    lei = r["leitura"]
    linhas += ["## Leitura dos juízes", "",
               f"Itens lidos: {lei.get('itensLidos')}; concordância J1 × J2: "
               f"{percentual(lei.get('concordanciaJ1J2'))}; kappa: "
               f"{'—' if lei.get('kappaJ1J2') is None else round(lei['kappaJ1J2'], 3)}; itens ao J3: "
               f"{lei.get('itensAoJ3')}. Juízes: {lei.get('juizes')}."
               + (" **Aviso: kappa entre 0,40 e 0,60.**" if lei.get("avisoKappa") else ""), ""]
    return "\n".join(linhas)


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) != 1:
        print(__doc__)
        return 2
    rodada = Path(argv[0])
    try:
        resultado = decidir_rodada(rodada)
    except (RodadaIncompleta, comum.ErroDeConjunto) as erro:
        print(f"rodada incompleta: {erro}")
        return 2
    (rodada / "resultado.json").write_text(json.dumps(resultado, ensure_ascii=False, indent=2),
                                           encoding="utf-8")
    (rodada / "resultado.md").write_text(relatorio_md(resultado), encoding="utf-8")
    for braco, c in resultado["comparacoes"].items():
        print(f"{comum.BASE} x {braco}: {c['desfecho']}: {c['motivo']}")
    if len(resultado["comparacoes"]) > 1:
        print(f"escolhido: {resultado['escolha']['escolhido']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
