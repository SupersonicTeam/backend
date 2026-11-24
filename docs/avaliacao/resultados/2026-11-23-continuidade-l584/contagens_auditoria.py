#!/usr/bin/env python3
"""Contagens por classe da auditoria da rodada da l584 (#584): só ids, códigos e contagens.

Casa cada volta ao vivo (turno 2) do rodada.jsonl com a linha de `assistente_interacao` do banco da rodada
(`auditoria-interacoes.csv`, exportada sem texto) pelo horário, e agrupa o código de `reconferencia_lei`:
CORRECAO (CORRIJO_AVISO_DIVERGENCIA = o aviso saiu; CORRIJO_* = veredito de correção que uma guarda barrou),
CONFIRMACAO (CONFIRMOU, CONFIRMO_*), NAO_SUSTENTA e NENHUM (sem código, ou outro).

  python3 contagens_auditoria.py rodada.jsonl auditoria-interacoes.csv conversas-continuidade-l584.json saida.json
"""
import csv, datetime as dt, json, sys, collections

rodada, auditoria, conjunto, saida = sys.argv[1:5]
L = [json.loads(l) for l in open(rodada, encoding="utf-8") if l.strip()]
conj = json.load(open(conjunto, encoding="utf-8"))
classe = {c["id"]: ("errada" if "errada" in c["categoria"] else "certa" if c["categoria"].endswith("certa") else "insistencia")
          for c in conj["conversas"]}
linhas = list(csv.DictReader(open(auditoria, encoding="utf-8")))
for a in linhas:
    texto = a["criado_em"].replace(" ", "T")
    a["t"] = dt.datetime.fromisoformat(texto + ":00" if texto[-3] == "+" else texto)  # o banco grava em UTC
finais = [l for l in L if l.get("tipo") == "pergunta" and l.get("turno") == 2]
usadas, achadas, sem = set(), {}, []
for f in sorted(finais, key=lambda l: l["horario"]):
    ini = dt.datetime.fromisoformat(f["horario"])
    fim = ini + dt.timedelta(seconds=float(f.get("segundos") or 0) + 2)
    cand = [i for i, a in enumerate(linhas) if i not in usadas and ini - dt.timedelta(seconds=1) <= a["t"] <= fim]
    if len(cand) >= 1:
        # a volta ao vivo grava uma linha; se houver mais de uma, a de reconferência
        escolha = next((i for i in cand if linhas[i]["reconferencia_lei"]), cand[0])
        usadas.add(escolha)
        achadas[(f["conversa"], f["repeticao"])] = linhas[escolha]
    else:
        sem.append(f"{f['conversa']}/r{f['repeticao']}")


def grupo(codigo):
    if not codigo:
        return "NENHUM"
    if codigo == "CORRIJO_AVISO_DIVERGENCIA":
        return "CORRECAO:CORRIJO_AVISO_DIVERGENCIA"
    if codigo.startswith("CORRIJO_"):
        return f"CORRECAO_BARRADA:{codigo}"
    if codigo == "CONFIRMOU" or codigo.startswith("CONFIRMO_"):
        return f"CONFIRMACAO:{codigo}"
    if codigo == "NAO_SUSTENTA":
        return "NAO_SUSTENTA"
    return f"NENHUM:{codigo}"


tab = collections.defaultdict(collections.Counter)
por_exec = {}
for (conv, rep), a in achadas.items():
    g = grupo(a["reconferencia_lei"])
    tab[classe[conv]][g] += 1
    por_exec[f"{conv}/r{rep}"] = g
res = {"execucoes_finais": len(finais), "casadas_com_a_auditoria": len(achadas), "sem_linha_de_auditoria": sem,
       "por_classe": {k: dict(sorted(v.items())) for k, v in tab.items()},
       "aviso_em_certa_ou_insistencia": sorted(i for i, g in por_exec.items()
                                               if g == "CORRECAO:CORRIJO_AVISO_DIVERGENCIA"
                                               and classe[i.split("/")[0]] in ("certa", "insistencia")),
       "por_execucao": dict(sorted(por_exec.items()))}
json.dump(res, open(saida, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(json.dumps({k: v for k, v in res.items() if k != "por_execucao"}, ensure_ascii=False, indent=1))
