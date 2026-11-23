#!/usr/bin/env python3
"""Reanálise da rodada da v3 (#499) de 2026-11-23: só ids, códigos e contagens (#584).

1. Reanexa a auditoria do banco às voltas do braço ligado (o executor da rodada gravou `auditoria` vazia: o
   strip() geral de Banco.consulta comia as colunas vazias do fim da linha) e pontua de novo com a mesma
   regra e o mesmo conjunto. NÃO é a pontuação oficial: é o que a rodada daria sem esse defeito do executor.
2. Diz, por turno de M_rota_funcao que falhou, qual conferência falhou.

  python3 reanalise_499.py <pasta da rodada> <pasta das ferramentas>
"""
import csv, datetime as dt, json, sys, collections
from pathlib import Path

pasta, ferramentas = Path(sys.argv[1]), Path(sys.argv[2])
sys.path.insert(0, str(ferramentas))
import pontuar_continuidade as pc  # noqa: E402
import validar_conjunto as vc  # noqa: E402

linhas = [json.loads(l) for l in (pasta / "rodada.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
aud = list(csv.DictReader(open(pasta / "auditoria-ligado.csv", encoding="utf-8")))
for a in aud:
    t = a["criado_em"].replace(" ", "T")
    a["t"] = dt.datetime.fromisoformat(t + ":00" if t[-3] == "+" else t)
# Cada linha de auditoria vai à volta que começou por último antes dela (ini - 1 s <= horário <= ini + duração + 2 s).
# A versão anterior dava ao turno 1 todas as linhas até o fim da janela dele, e a janela do turno 1 engolia a
# linha do turno 2 quando o turno 2 começava logo depois (turnos curtos, sem modelo): o turno 2 ficava sem
# auditoria e falhava em M_credito_zero e M_caminho_fixo por causa da própria reanálise.
voltas = sorted((x for x in linhas if x.get("tipo") == "pergunta" and x.get("braco") == "ligado"),
                key=lambda x: x["horario"])
inicio = [dt.datetime.fromisoformat(v["horario"]) for v in voltas]
donas = collections.defaultdict(list)
for i, a in enumerate(aud):
    dono = None
    for k, ini in enumerate(inicio):
        if ini - dt.timedelta(seconds=1) <= a["t"]:
            dono = k
        else:
            break
    if dono is not None:
        fim = inicio[dono] + dt.timedelta(seconds=float(voltas[dono].get("segundos") or 0) + 2)
        if a["t"] <= fim:
            donas[dono].append(a)
anexadas = 0
for k, l in enumerate(voltas):
    rs = donas.get(k)
    if not rs:
        continue
    soma = lambda c: sum(int(r[c]) for r in rs if r[c].isdigit()) or None  # noqa: E731
    ult = rs[-1]
    l["auditoria"] = {"rota": ult["rota"], "desfecho": ult["desfecho"],
                      "camada_classificacao": ult["camada_classificacao"] or None,
                      "latencia_ms": ult["latencia_ms"] or None, "tokens_entrada": soma("tokens_entrada"),
                      "tokens_saida": soma("tokens_saida"), "linhas": len(rs),
                      "camadas": [r["camada_classificacao"] or None for r in reversed(rs)]}
    anexadas += 1
(pasta / "rodada-auditoria-reanexada.jsonl").write_text(
    "\n".join(json.dumps(l, ensure_ascii=False) for l in linhas) + "\n", encoding="utf-8")
print("voltas do braço ligado com a auditoria reanexada:", anexadas)

# 2. conferências que falharam em M_rota_funcao (com a pontuação original, sobre as linhas originais)
originais = [json.loads(l) for l in (pasta / "rodada.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
falhas = collections.defaultdict(list)
passa_original = pc.passa


def passa_registrando(metrica, esperado, linha, ctx):
    ok = passa_original(metrica, esperado, linha, ctx)
    if metrica == "M_rota_funcao" and not ok and linha.get("braco") == "ligado" and linha.get("parte") == "teste":
        ruins = [c for c in pc.CHECAGENS[metrica] if c in esperado and not pc.conferir(c, esperado, linha, ctx)]
        falhas[(linha["conversa"], linha["repeticao"], linha["turno"])] = ruins
    return ok


pc.passa = passa_registrando
conjuntos, shas = [], {}
for caminho in vc.ARQUIVOS:
    c = json.loads(caminho.read_text(encoding="utf-8"))
    conjuntos.append(c)
    import hashlib
    shas[c["parte"]] = hashlib.sha256(caminho.read_bytes()).hexdigest()
pc.pontuar(originais, conjuntos, shas)
por_check = collections.Counter(c for v in falhas.values() for c in v)
por_conversa = collections.defaultdict(lambda: collections.Counter())
for (conv, rep, turno), ruins in falhas.items():
    por_conversa[conv][",".join(ruins)] += 1
saida = {"turnos_rota_funcao_que_falharam": len(falhas), "por_conferencia": dict(por_check),
         "por_conversa": {k: dict(v) for k, v in sorted(por_conversa.items())}}
(pasta / "rota-funcao-falhas.json").write_text(json.dumps(saida, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(saida, ensure_ascii=False, indent=1))
