"""Aplica a regra de decisão (`regra-de-decisao.md`) às respostas de uma rodada e grava o resultado.

    python3 -I decidir_ressalva.py --rodada rodadas/<data>-ressalva-443 --base base --candidato candidato

Só biblioteca padrão. A decisão é função pura de `decidir(conjunto, execucoes, base, candidato)`.
"""

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ressalva_comum as comum  # noqa: E402

ENTRA = "ENTRA"
NAO_ENTRA = "NAO_ENTRA"
INCONCLUSIVA = "INCONCLUSIVA"


def _acertos_por_id(perguntas, execucoes, braco):
    """{id: [bool, ...]} em ordem de repetição, só do braço."""
    por_id = defaultdict(dict)
    for e in execucoes:
        if e["braco"] == braco:
            por_id[e["id"]][e["rep"]] = e
    saida = {}
    for pergunta_id, pergunta in perguntas.items():
        reps = por_id.get(pergunta_id, {})
        saida[pergunta_id] = [comum.acertou(pergunta, reps[r]) if r in reps else None
                              for r in range(1, comum.REPETICOES + 1)]
    return saida


def _soma(acertos, ids):
    return sum(sum(1 for a in acertos[i] if a) for i in ids)


def _contagem(acertos, i):
    return sum(1 for a in acertos[i] if a)


def _grupo(conjunto, grupo, **filtro):
    return [p["id"] for p in conjunto["perguntas"]
            if p["grupo"] == grupo and all(p.get(k) == v for k, v in filtro.items())]


def decidir(conjunto, execucoes, base, candidato):
    perguntas = {p["id"]: p for p in conjunto["perguntas"]}
    ac = {b: _acertos_por_id(perguntas, execucoes, b) for b in (base, candidato)}
    inconclusiva, bloqueios, alertas = [], [], []

    # Portão 1: a rodada está completa e as falhas técnicas são poucas.
    for b in (base, candidato):
        faltando = [i for i, v in ac[b].items() if any(a is None for a in v)]
        if faltando:
            inconclusiva.append(f"braço {b}: execuções ausentes em {len(faltando)} perguntas")
        total = [e for e in execucoes if e["braco"] == b]
        falhas = sum(1 for e in total if comum.e_falha_tecnica(e))
        if total and falhas / len(total) > comum.TETO_FALHAS_TECNICAS:
            inconclusiva.append(f"braço {b}: {falhas} falhas técnicas em {len(total)} execuções")

    ids_a = _grupo(conjunto, "DEFEITO", estrato="A")
    ids_b = _grupo(conjunto, "DEFEITO", estrato="B")
    resultado = {"regra": comum.VERSAO_REGRA, "conjunto": conjunto["versao"],
                 "base": base, "candidato": candidato}

    # Defeito (estrato A, decide; estrato B, informa).
    sb, sc = _soma(ac[base], ids_a), _soma(ac[candidato], ids_a)
    delta_a = sc - sb
    corrigidos = [i for i in ids_a if _contagem(ac[base], i) == 0 and _contagem(ac[candidato], i) == 3]
    piorados = [i for i in ids_a if _contagem(ac[base], i) == 3 and _contagem(ac[candidato], i) == 0]
    falhas_da_base = len(ids_a) * comum.REPETICOES - sb
    classes = {b: Counter(comum.classificar_defeito(e) for e in execucoes
                          if e["braco"] == b and e["id"] in ids_a) for b in (base, candidato)}
    sem_trat_delta = (classes[candidato][comum.SEM_TRATAMENTO] - classes[base][comum.SEM_TRATAMENTO])
    delta_b = _soma(ac[candidato], ids_b) - _soma(ac[base], ids_b)
    resultado["defeito"] = {
        "estratoA": {"acertosBase": sb, "acertosCandidato": sc, "delta": delta_a,
                     "execucoes": len(ids_a) * comum.REPETICOES, "falhasDaBase": falhas_da_base,
                     "idsCorrigidosEstaveis": corrigidos, "idsPioradosEstaveis": piorados,
                     "classes": {b: dict(classes[b]) for b in classes}},
        "estratoB": {"acertosBase": _soma(ac[base], ids_b), "acertosCandidato": _soma(ac[candidato], ids_b),
                     "delta": delta_b}}
    if falhas_da_base < comum.MINIMO_FALHAS_DA_BASE:  # Portão 2: o defeito se reproduz na base
        inconclusiva.append(f"o defeito não se reproduz na base: {falhas_da_base} falhas em "
                            f"{len(ids_a) * comum.REPETICOES} execuções (mínimo {comum.MINIMO_FALHAS_DA_BASE})")
    perdas_a = [i for i in ids_a if i not in piorados
                and _contagem(ac[base], i) - _contagem(ac[candidato], i) >= comum.ALERTA_CONTROLE]
    if perdas_a:
        alertas.append(f"estrato A com perda de 2 repetições ou mais: {perdas_a}")
    if delta_b <= -comum.ALERTA_ESTRATO_B:
        alertas.append(f"estrato B perdeu {-delta_b} acertos")

    # Controles: só os que a base acerta em 2 das 3 repetições entram na comparação.
    resultado["controles"] = {}
    for nome, grupo in (("lei", "CONTROLE_LEI"), ("dados", "CONTROLE_DADOS")):
        ids = _grupo(conjunto, grupo)
        qualificados = [i for i in ids if _contagem(ac[base], i) >= comum.MINIMO_ACERTOS_DO_CONTROLE]
        excluidos = [i for i in ids if i not in qualificados]
        if len(qualificados) < comum.MINIMO_CONTROLES_QUALIFICADOS:  # Portão 3
            inconclusiva.append(f"controles de {nome}: só {len(qualificados)} de {len(ids)} qualificados "
                                f"(mínimo {comum.MINIMO_CONTROLES_QUALIFICADOS})")
        delta = _soma(ac[candidato], qualificados) - _soma(ac[base], qualificados)
        perdas_estaveis = [i for i in qualificados
                           if _contagem(ac[base], i) == 3 and _contagem(ac[candidato], i) == 0]
        perdas = [i for i in qualificados if i not in perdas_estaveis
                  and _contagem(ac[base], i) - _contagem(ac[candidato], i) >= comum.ALERTA_CONTROLE]
        if delta <= -comum.EFEITO_SOMA or perdas_estaveis:
            bloqueios.append(f"controles de {nome} pioraram (delta {delta}; "
                             f"ids com perda estável: {perdas_estaveis or 'nenhum'})")
        if perdas:
            alertas.append(f"controles de {nome} com perda de 2 repetições ou mais: {perdas}")
        resultado["controles"][nome] = {
            "qualificados": qualificados, "excluidosPorNaoQualificarNaBase": excluidos,
            "acertosBase": _soma(ac[base], qualificados), "acertosCandidato": _soma(ac[candidato], qualificados),
            "delta": delta, "idsComPerdaEstavel": perdas_estaveis, "idsComPerda": perdas}

    # Efeito no estrato A, na régua do projeto: id que muda nas 3 repetições ou ~5 acertos na soma.
    melhora = delta_a >= comum.EFEITO_SOMA or bool(corrigidos)
    piora = delta_a <= -comum.EFEITO_SOMA or bool(piorados)
    if piora:
        bloqueios.append(f"o estrato A piorou (delta {delta_a}; ids com perda estável: {piorados or 'nenhum'})")
    if sem_trat_delta >= comum.EFEITO_SOMA:
        bloqueios.append(f"respostas sem o tratamento do produto subiram {sem_trat_delta} no estrato A "
                         "(a ressalva não pode vir à custa da resposta)")

    if inconclusiva:
        veredito, motivos = INCONCLUSIVA, inconclusiva
    elif bloqueios:
        veredito, motivos = NAO_ENTRA, bloqueios
    elif melhora:
        veredito, motivos = ENTRA, [f"estrato A: {sb} → {sc} acertos (delta {delta_a}); "
                                    f"ids corrigidos nas 3 repetições: {corrigidos or 'nenhum'}"]
    else:
        veredito, motivos = NAO_ENTRA, [f"sem efeito distinguível do ruído no estrato A (delta {delta_a}, "
                                        "nenhum id muda nas 3 repetições)"]
    resultado.update({"veredito": veredito, "motivos": motivos, "alertas": alertas})

    # Leitura humana: onde os braços discordam no estrato A/B, a repetição 1 de cada um.
    resultado["paraLeitura"] = [
        {"id": i, "pergunta": perguntas[i]["pergunta"],
         "contagem": {base: _contagem(ac[base], i), candidato: _contagem(ac[candidato], i)},
         "resposta": {b: next((e.get("resposta") for e in execucoes
                               if e["braco"] == b and e["id"] == i and e["rep"] == 1), None)
                      for b in (base, candidato)}}
        for i in ids_a + ids_b if _contagem(ac[base], i) != _contagem(ac[candidato], i)]
    # O detector é lexical: o que o candidato escreveu sobre o contador e ele não reconheceu como ressalva
    # não aparece na lista acima quando a contagem não muda. A leitura confere esses também.
    resultado["paraConferirDetector"] = [
        {"id": e["id"], "rep": e["rep"], "resposta": e.get("resposta")}
        for e in execucoes
        if e["braco"] == candidato and e["id"] in ids_a + ids_b
        and comum.classificar_defeito(e) == comum.DEFEITO and comum.menciona_contador_solto(e.get("resposta"))]
    return resultado


def para_markdown(r):
    d = r["defeito"]
    linhas = [f"# Resultado da medição da #443 — {r['veredito']}", "",
              f"Regra `{r['regra']}`, conjunto `{r['conjunto']}`. Base: `{r['base']}`; candidato: `{r['candidato']}`.", "",
              "## Motivos", ""] + [f"- {m}" for m in r["motivos"]]
    if r["alertas"]:
        linhas += ["", "## Alertas (não bloqueiam)", ""] + [f"- {a}" for a in r["alertas"]]
    a = d["estratoA"]
    linhas += ["", "## Defeito (36 perguntas)", "",
               "| Estrato | Base | Candidato | Delta |", "|---|---|---|---|",
               f"| A (decide, {a['execucoes']} execuções) | {a['acertosBase']} | {a['acertosCandidato']} | {a['delta']} |",
               f"| B (informa) | {d['estratoB']['acertosBase']} | {d['estratoB']['acertosCandidato']} | "
               f"{d['estratoB']['delta']} |", "",
               f"Classes no estrato A: {json.dumps(a['classes'], ensure_ascii=False)}", "",
               "## Controles (só os qualificados na base)", "",
               "| Grupo | Qualificados | Base | Candidato | Delta | Perda estável |", "|---|---|---|---|---|---|"]
    for nome, c in r["controles"].items():
        linhas.append(f"| {nome} | {len(c['qualificados'])} | {c['acertosBase']} | {c['acertosCandidato']} | "
                      f"{c['delta']} | {', '.join(c['idsComPerdaEstavel']) or '-'} |")
        if c["excluidosPorNaoQualificarNaBase"]:
            linhas.append(f"| ↳ excluídos na base | {', '.join(c['excluidosPorNaoQualificarNaBase'])} | | | | |")
    linhas += ["", "## Para a leitura humana", "",
               "Quem lê não é quem escreveu a correção. Discordância entre o detector e a leitura em um id "
               "decisivo (corrigido ou piorado nas 3 repetições) invalida a decisão.", ""]
    for item in r["paraLeitura"]:
        linhas += [f"### {item['id']} — {item['pergunta']}", f"Acertos: {item['contagem']}", ""]
        for braco, resposta in item["resposta"].items():
            linhas += [f"**{braco}** (repetição 1):", "", f"> {(resposta or '(sem resposta)').replace(chr(10), chr(10) + '> ')}", ""]
    if r["paraConferirDetector"]:
        linhas += ["## O detector não reconheceu ressalva, mas a resposta do candidato fala do contador", ""]
        for item in r["paraConferirDetector"]:
            texto = (item["resposta"] or "").replace("\n", " ")
            linhas += [f"- {item['id']} (repetição {item['rep']}): {texto}", ""]
    return "\n".join(linhas) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--rodada", required=True, type=Path)
    ap.add_argument("--base", default=None)
    ap.add_argument("--candidato", default=None)
    args = ap.parse_args(argv)
    manifesto = json.loads((args.rodada / "execucao.json").read_text(encoding="utf-8"))
    conjunto = comum.carregar_conjunto()
    if manifesto["conjunto"]["sha256"] != comum.sha256_arquivo(comum.CONJUNTO):
        sys.exit("A rodada foi feita com outro conjunto: o sha256 do manifesto não confere.")
    if manifesto.get("repeticoes") != comum.REPETICOES or manifesto.get("recorteDeIds"):
        sys.exit("Rodada fora da regra (repetições diferentes de 3 ou só parte das perguntas): INCONCLUSIVA.")
    if manifesto.get("interrompida"):
        sys.exit("Rodada interrompida: " + manifesto["interrompida"] + " (INCONCLUSIVA).")
    base = args.base or manifesto["bracos"][0]
    candidato = args.candidato or manifesto["bracos"][1]
    sem_commit = [b for b in (base, candidato) if not (manifesto.get("commits") or {}).get(b)]
    if sem_commit:
        sys.exit("O manifesto não traz o commit de: " + ", ".join(sem_commit) + " (INCONCLUSIVA).")
    execucoes = [json.loads(linha) for linha in
                 (args.rodada / "respostas-brutas.jsonl").read_text(encoding="utf-8").splitlines() if linha]
    resultado = decidir(conjunto, execucoes, base, candidato)
    (args.rodada / "resultado.json").write_text(json.dumps(resultado, ensure_ascii=False, indent=2) + "\n",
                                                encoding="utf-8")
    (args.rodada / "resultado.md").write_text(para_markdown(resultado), encoding="utf-8")
    print(resultado["veredito"], "-", "; ".join(resultado["motivos"]))


if __name__ == "__main__":
    main()
