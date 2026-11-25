#!/usr/bin/env python3
"""Análise estruturada de uma rodada do comando de avaliação (relatório .json), sem texto de pergunta.

Uso: analisar.py <rodada.json> [<outra.json> ...] [--ficha saida.md]

Lê SÓ campos estruturados: id, rotaEsperada, adversario, execucao.rota, execucao.desfecho,
execucao.camadaClassificacao, execucao.latenciaMs e os flags de acerto. Nunca lê `perguntas`
(id -> texto) nem `execucao.texto`. Com mais de um arquivo, compara por critério (soma de
acertos nas repetições) e aplica a régua de ruído do projeto.

A saída traz: acertos por critério e repetição; recorte por camada (decididas e erradas de rota,
por repetição); a ficha cega (ids de rota errada e de recusa errada, com esperada, obtida,
camada e em quantas repetições errou).
"""
import json
import sys
from collections import Counter, defaultdict

CRITERIOS = [
    ("Rota", "rotaCorreta"),
    ("Função", "funcaoCorreta"),
    ("Parâmetros", "parametrosCorretos"),
    ("Número", "numeroCorreto"),
    ("Citação válida", "citacaoValida"),
    ("Recusa correta", "recusaCorreta"),
    ("Sem vazamento (falha = vazou)", "vazamento"),
]
CAMADAS_ORDEM = ["REGRA", "EMBEDDING", "LLM", "ATALHO_FIXO", "SINAL_RECUSA_FIRME",
                 "FILTRO_INJECAO"]


def carregar(caminho):
    with open(caminho, encoding="utf-8") as f:
        d = json.load(f)
    # descarta explicitamente o que tem texto
    d.pop("perguntas", None)
    for r in d["rodadas"]:
        for res in r["resultados"]:
            res["execucao"].pop("texto", None)
            res["execucao"].pop("citacoes", None)
    return d


def p(valor):
    return "·" if valor is None else str(valor)


def acertos_por_criterio(d):
    """{criterio: ([acertos por repetição], n por repetição)}"""
    saida = {}
    for nome, chave in CRITERIOS:
        por_rep, ns = [], []
        for r in d["rodadas"]:
            vals = [x[chave] for x in r["resultados"] if x[chave] is not None]
            ns.append(len(vals))
            if chave == "vazamento":
                por_rep.append(sum(1 for v in vals if v))  # vazamentos (queremos 0)
            else:
                por_rep.append(sum(1 for v in vals if v))
        saida[nome] = (por_rep, ns)
    # rota nos adversários
    por_rep, ns = [], []
    for r in d["rodadas"]:
        vals = [x["rotaCorreta"] for x in r["resultados"]
                if x.get("adversario") and x["rotaCorreta"] is not None]
        ns.append(len(vals))
        por_rep.append(sum(1 for v in vals if v))
    saida["Rota nos adversários"] = (por_rep, ns)
    # falha técnica
    por_rep, ns = [], []
    for r in d["rodadas"]:
        ns.append(len(r["resultados"]))
        por_rep.append(sum(1 for x in r["resultados"] if x["execucao"].get("erro")
                           or x["execucao"].get("modoFallback")))
    saida["Falha técnica (modelo fora, fallback ou erro)"] = (por_rep, ns)
    return saida


def percentil(valores, q):
    if not valores:
        return None
    v = sorted(valores)
    import math
    k = max(1, math.ceil(q * len(v)))
    return v[k - 1]


def latencias(d):
    todas = [x["execucao"]["latenciaMs"] for r in d["rodadas"] for x in r["resultados"]
             if x["execucao"].get("latenciaMs") is not None]
    return percentil(todas, 0.5), percentil(todas, 0.95)


def por_camada(d):
    """por repetição: camada -> (decididas com rota esperada, erradas); e total decidido (todas)."""
    linhas = []
    for r in d["rodadas"]:
        dec, err, tot = Counter(), Counter(), Counter()
        for x in r["resultados"]:
            cam = x["execucao"].get("camadaClassificacao") or "(sem camada)"
            tot[cam] += 1
            if x["rotaCorreta"] is not None:
                dec[cam] += 1
                if not x["rotaCorreta"]:
                    err[cam] += 1
        linhas.append((dec, err, tot))
    return linhas


def ficha(d):
    """ids com rota errada em alguma repetição; ids com recusa errada."""
    rota = defaultdict(list)
    recusa = defaultdict(list)
    esperada = {}
    for i, r in enumerate(d["rodadas"], 1):
        for x in r["resultados"]:
            ex = x["execucao"]
            esperada[x["id"]] = (x.get("rotaEsperada"), bool(x.get("adversario")))
            if x["rotaCorreta"] is False:
                rota[x["id"]].append((i, ex.get("rota"), ex.get("camadaClassificacao"),
                                      ex.get("desfecho")))
            if x["recusaCorreta"] is False:
                recusa[x["id"]].append((i, ex.get("rota"), ex.get("camadaClassificacao"),
                                        ex.get("desfecho")))
    return rota, recusa, esperada


def comprimir(lista, indice):
    """valores por repetição ('·' onde acertou), em ordem de repetição."""
    return lista


def md_ficha(d):
    rota, recusa, esperada = ficha(d)
    n = len(d["rodadas"])
    out = []
    out.append("### Rota errada em alguma repetição\n")
    out.append("| Id | Adversária | Esperada | Obtida (rep. 1 / 2 / 3) | Camada (rep. 1 / 2 / 3) | Erros |")
    out.append("|---|---|---|---|---|---|")

    def ordenar(item):
        return (-len(item[1]), item[0])

    for id_, erros in sorted(rota.items(), key=ordenar):
        por_rep = {e[0]: e for e in erros}
        # obtida e camada em TODAS as repetições (inclusive onde acertou), para ver a oscilação
        obtidas, camadas = [], []
        for i, r in enumerate(d["rodadas"], 1):
            x = next(y for y in r["resultados"] if y["id"] == id_)
            ex = x["execucao"]
            marca = "" if i in por_rep else " ✓"
            obtidas.append(f"{ex.get('rota')}{marca}")
            camadas.append(ex.get("camadaClassificacao") or "—")
        esp, adv = esperada[id_]
        out.append(f"| {id_} | {'sim' if adv else 'não'} | {esp} | {' / '.join(obtidas)} | "
                   f"{' / '.join(camadas)} | {len(erros)} de {n} |")
    out.append("")
    out.append("### Recusa errada em alguma repetição\n")
    out.append("| Id | Rota esperada | Rota obtida (rep. 1 / 2 / 3) | Camada (rep. 1 / 2 / 3) | Desfecho (rep. 1 / 2 / 3) | Erros |")
    out.append("|---|---|---|---|---|---|")
    for id_, erros in sorted(recusa.items(), key=ordenar):
        por_rep = {e[0] for e in erros}
        rotas, camadas, desf = [], [], []
        for i, r in enumerate(d["rodadas"], 1):
            x = next(y for y in r["resultados"] if y["id"] == id_)
            ex = x["execucao"]
            marca = "" if i in por_rep else " ✓"
            rotas.append(f"{ex.get('rota')}{marca}")
            camadas.append(ex.get("camadaClassificacao") or "—")
            desf.append(ex.get("desfecho") or "—")
        esp, _ = esperada[id_]
        out.append(f"| {id_} | {esp} | {' / '.join(rotas)} | {' / '.join(camadas)} | "
                   f"{' / '.join(desf)} | {len(erros)} de {n} |")
    if not recusa:
        out.append("| (nenhuma) | | | | | |")
    out.append("")
    out.append("`✓` = nessa repetição o critério da própria tabela (rota ou recusa) estava certo. Só ids, rótulos de rota, camada e desfecho: nenhum texto de "
               "pergunta nem de resposta.")
    return "\n".join(out)


def md_criterios(d):
    out = ["| Critério | Acertos por repetição | Soma | n por repetição |", "|---|---|---|---|"]
    for nome, (acs, ns) in acertos_por_criterio(d).items():
        out.append(f"| {nome} | {' / '.join(map(str, acs))} | {sum(acs)} | {ns[0] if len(set(ns)) == 1 else ns} |")
    p50, p95 = latencias(d)
    out.append(f"| Latência p50 / p95 (3 rodadas juntas) | | {p50} ms / {p95} ms | |")
    return "\n".join(out)


def md_camada(d):
    linhas = por_camada(d)
    camadas = [c for c in CAMADAS_ORDEM if any(c in l[2] for l in linhas)]
    camadas += sorted({c for l in linhas for c in l[2]} - set(camadas))
    out = ["| Camada | Decidiu (rep. 1 / 2 / 3, todas as perguntas) | Decidiu com rota esperada | Errou rota | Erro / decididas |",
           "|---|---|---|---|---|"]
    for c in camadas:
        tot = [l[2][c] for l in linhas]
        dec = [l[0][c] for l in linhas]
        err = [l[1][c] for l in linhas]
        taxa = [f"{e}/{dd}" for e, dd in zip(err, dec)]
        out.append(f"| {c} | {' / '.join(map(str, tot))} | {' / '.join(map(str, dec))} | "
                   f"{' / '.join(map(str, err))} | {' · '.join(taxa)} |")
    tot = [sum(l[2].values()) for l in linhas]
    dec = [sum(l[0].values()) for l in linhas]
    err = [sum(l[1].values()) for l in linhas]
    out.append(f"| **Total** | {' / '.join(map(str, tot))} | {' / '.join(map(str, dec))} | "
               f"{' / '.join(map(str, err))} | |")
    return "\n".join(out)


def leitura_regua(a, b):
    """régua do projeto: efeito = as 3 repetições de um lado acima das 3 do outro E soma >= 5."""
    if len(a) != len(b):
        return "n/a"
    soma = sum(a) - sum(b)  # positivo = A acertou mais
    sem_sobreposicao = min(b) > max(a) or max(b) < min(a)
    if abs(soma) >= 5 and sem_sobreposicao:
        return f"**efeito** ({soma:+d})"
    return f"ruído ({soma:+d})"


def main(argv):
    arquivos = [a for a in argv[1:] if not a.startswith("--")]
    d = [carregar(a) for a in arquivos]
    for arq, x in zip(arquivos, d):
        print(f"## {arq}\n")
        print(md_criterios(x))
        print("\n### Por camada\n")
        print(md_camada(x))
        print("\n" + md_ficha(x))
    if len(d) == 2:
        print("\n## Comparação (A = primeiro arquivo, B = segundo) pela régua de ruído\n")
        ca, cb = acertos_por_criterio(d[0]), acertos_por_criterio(d[1])
        print("| Critério | A | B | Leitura |\n|---|---|---|---|")
        for k in ca:
            print(f"| {k} | {' / '.join(map(str, ca[k][0]))} | {' / '.join(map(str, cb[k][0]))} | "
                  f"{leitura_regua(ca[k][0], cb[k][0])} |")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv)
