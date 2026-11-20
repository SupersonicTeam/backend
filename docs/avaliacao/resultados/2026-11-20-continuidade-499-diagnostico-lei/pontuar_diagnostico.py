#!/usr/bin/env python3
"""Tabelas da rodada de DIAGNÓSTICO da correção de lei (#584). Só ids, códigos e contagens.

Lê o rodada.jsonl do driver_diagnostico.py e os conjuntos da pasta do commit medido; usa a mesma
conferência do pontuar_continuidade.py para dizer se a execução passou no M_lei_correcao (turno 2).
Não decide nada: não há veredito.

  python3 pontuar_diagnostico.py --pasta <worktree> --execucoes rodada.jsonl --saida resumo.json --md tabelas.md
"""

from __future__ import annotations

import argparse
import json
import statistics as st
import sys
from collections import Counter, defaultdict
from pathlib import Path

CAMPOS_NOVOS = ("reconferencia_lei", "reconferencia_busca", "reconferencia_dispositivos",
                "reconferencia_trechos_citados", "reconferencia_trechos_busca",
                "reconferencia_trechos_prompt", "reconferencia_trechos_prompt_citados",
                "reconferencia_caracteres_trechos", "reconferencia_tokens_concordancia",
                "reconferencia_concordancia")
CONVERSAS = (("teste", "t-lei-04"), ("teste", "t-lei-05"), ("dev", "d-lei-02"))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pasta", required=True)
    ap.add_argument("--execucoes", type=Path, required=True)
    ap.add_argument("--saida", type=Path, required=True)
    ap.add_argument("--md", type=Path, required=True)
    ap.add_argument("--auditoria", type=Path, default=None,
                    help="CSV das colunas sem texto de assistente_interacao (camada RECONFERENCIA_LEI), em ordem "
                         "cronológica: a auditoria que o executor lê logo depois da resposta chega atrasada em "
                         "respostas rápidas; o banco é a fonte dos campos da reconferência")
    args = ap.parse_args(argv)
    sys.path.insert(0, str(Path(args.pasta) / "docs" / "avaliacao" / "continuidade"))
    sys.dont_write_bytecode = True
    import pontuar_continuidade as pc  # noqa: E402
    import validar_conjunto as vc  # noqa: E402

    conjuntos = {"teste": json.loads(vc.ARQUIVOS[1].read_text(encoding="utf-8")),
                 "dev": json.loads(vc.ARQUIVOS[0].read_text(encoding="utf-8"))}
    linhas = [json.loads(t) for t in args.execucoes.read_text(encoding="utf-8").splitlines() if t.strip()]
    grupos = pc.finais(linhas)
    notas = {l["braco"]: l["notas"] for l in linhas if l.get("tipo") == "notas_seed"}.get("ligado", {})
    agenda = [l for l in linhas if l.get("tipo") == "agenda"]
    ini = next((l for l in linhas if l.get("tipo") == "agenda_inicio"), {})
    fim = next((l for l in linhas if l.get("tipo") == "agenda_fim"), {})
    import csv
    import datetime as dt
    csv_linhas = []
    if args.auditoria:
        with args.auditoria.open(encoding="utf-8", newline="") as f:
            csv_linhas = sorted(csv.DictReader(f), key=lambda r: r["criado_em"])
    if csv_linhas and len(csv_linhas) != len(agenda):
        raise SystemExit(f"o CSV tem {len(csv_linhas)} linhas de reconferência e a agenda, {len(agenda)} execuções: "
                         "o casamento pela ordem não vale")
    execs = []
    for a in sorted(agenda, key=lambda x: x["unidade"]):
        parte, cid, rep = a["parte"], a["conversa"], a["repeticao"]
        conversa = next(c for c in conjuntos[parte]["conversas"] if c["id"] == cid)
        turnos = grupos.get((parte, cid, "ligado", rep), {})
        res = pc.avaliar(conversa, turnos, notas, True)
        lei = [x for x in res["avaliados"] if x["metrica"] == "M_lei_correcao"]
        linha = (turnos.get(2) or {}).get("pergunta")
        e = {"id": f"{cid}/r{rep}", "parte": parte, "conversa": cid, "rep": rep,
             "unidade": a["unidade"], "tipo": a["tipo_unidade"], "hora": a["horario_inicio"][11:19],
             "desde_a_anterior": a["segundos_desde_a_anterior"],
             "ollama_antes": (a.get("ollama_antes_da_pausa") or {}).get("bge_m3_carregado"),
             "ollama_depois": (a.get("ollama_depois_da_pausa") or {}).get("bge_m3_carregado"),
             "fora": res.get("fora"), "passou": None, "abertura": None, "desfecho": None}
        if lei and linha:
            aud = dict(linha.get("auditoria") or {})
            corpo = linha.get("corpo") or {}
            if csv_linhas:
                r = csv_linhas[a["unidade"] - 1]
                pedido = dt.datetime.fromisoformat(linha["horario"]).replace(tzinfo=None)
                gravado = dt.datetime.fromisoformat(r["criado_em"])
                e["auditoria_confere_hora"] = 0 <= (gravado - pedido).total_seconds() <= 40
                aud = {k: (v if v != "" else None) for k, v in r.items() if k != "criado_em"}
                aud["latencia_ms"] = r["latencia_ms"]
            e.update(passou=lei[0]["passou"], abertura=",".join(pc.categorias_no_inicio(corpo.get("resposta") or "")),
                     desfecho=aud.get("desfecho"), tok_ent=aud.get("tokens_entrada"),
                     tok_sai=aud.get("tokens_saida"), lat_ms=aud.get("latencia_ms"),
                     t_resp=linha.get("t_resposta"), n_cit=len(corpo.get("citacoes") or []),
                     **{c: aud.get(c) for c in CAMPOS_NOVOS})
        execs.append(e)

    def passou(e):
        return "passou" if e["passou"] else ("**falhou**" if e["passou"] is False else f"fora ({e['fora']})")

    md = []
    md.append("### 1. Por conversa\n\n| Conversa | Execuções | Passaram | Falharam | Fora |\n|---|---|---|---|---|")
    for p, c in CONVERSAS:
        l = [e for e in execs if e["conversa"] == c]
        md.append(f"| `{c}` | {len(l)} | {sum(1 for e in l if e['passou'])} | "
                  f"{sum(1 for e in l if e['passou'] is False)} | {sum(1 for e in l if e['passou'] is None)} |")
    md.append("\n### 2. As execuções\n\nTipo: `pausa` = veio depois de uma pausa; `logo` = veio logo depois de outra execução. "
              "bge antes/depois = bge-m3 carregado antes e depois da pausa (`ollama ps`).\n")
    cab = ["#", "Execução", "Tipo", "bge antes/depois", "Hora", "Resultado", "Abertura", "Desfecho", "reconferencia_lei",
           "busca", "dispositivos", "trechos citados", "trechos prompt", "tokens concordância", "concordância",
           "Tokens ent/saí", "Latência ms", "Citações"]
    md.append("| " + " | ".join(cab) + " |\n|" + "---|" * len(cab))
    for e in execs:
        bge = "" if e["ollama_antes"] is None else f"{'sim' if e['ollama_antes'] else 'não'}/{'sim' if e['ollama_depois'] else 'não'}"
        md.append("| " + " | ".join(str(v) for v in [
            e["unidade"], f"`{e['id']}`", "pausa" if e["tipo"] == "apos_pausa" else "logo", bge, e["hora"], passou(e),
            e["abertura"], e["desfecho"], e.get("reconferencia_lei"), e.get("reconferencia_busca"),
            e.get("reconferencia_dispositivos"), e.get("reconferencia_trechos_citados"), e.get("reconferencia_trechos_prompt"),
            e.get("reconferencia_tokens_concordancia"), e.get("reconferencia_concordancia"),
            f"{e.get('tok_ent')}/{e.get('tok_sai')}", e.get("lat_ms"), e.get("n_cit")]) + " |")
    md.append("\n### 3. Falhas por código de `reconferencia_lei`, por conversa\n")
    codigos = sorted({e.get("reconferencia_lei") or "(vazio)" for e in execs if e["passou"] is False})
    md.append("| Conversa | " + " | ".join(f"`{c}`" for c in codigos) + " | Total de falhas |\n|" + "---|" * (len(codigos) + 2))
    for p, c in CONVERSAS:
        f = Counter((e.get("reconferencia_lei") or "(vazio)") for e in execs if e["conversa"] == c and e["passou"] is False)
        md.append(f"| `{c}` | " + " | ".join(str(f.get(k, 0)) for k in codigos) + f" | {sum(f.values())} |")
    md.append("\nCódigo de `reconferencia_lei` em TODAS as execuções (passaram e falharam), por conversa:\n")
    todos = sorted({e.get("reconferencia_lei") or "(vazio)" for e in execs if e["passou"] is not None})
    md.append("| Conversa | Resultado | " + " | ".join(f"`{c}`" for c in todos) + " |\n|" + "---|" * (len(todos) + 2))
    for p, c in CONVERSAS:
        for r in (True, False):
            f = Counter((e.get("reconferencia_lei") or "(vazio)") for e in execs if e["conversa"] == c and e["passou"] is r)
            md.append(f"| `{c}` | {'passou' if r else 'falhou'} | " + " | ".join(str(f.get(k, 0)) for k in todos) + " |")

    def faixa(v):
        v = [int(x) for x in v if x is not None and str(x).lstrip('-').isdigit()]
        return "-" if not v else (f"{min(v)}" if min(v) == max(v) else f"{min(v)} a {max(v)} (mediana {int(st.median(v))})")

    md.append("\n### 4. Passou × falhou na mesma conversa (campos novos)\n\n| Conversa | Resultado | n | Tokens ent | Tokens saí | Latência ms | dispositivos | trechos citados | trechos prompt | tokens concordância | concordância (códigos) | busca (códigos) |\n|---|---|---|---|---|---|---|---|---|---|---|---|")
    for p, c in CONVERSAS:
        for r in (True, False):
            l = [e for e in execs if e["conversa"] == c and e["passou"] is r]
            if not l:
                continue
            md.append(f"| `{c}` | {'passou' if r else 'falhou'} | {len(l)} | {faixa([e.get('tok_ent') for e in l])} | "
                      f"{faixa([e.get('tok_sai') for e in l])} | {faixa([e.get('lat_ms') for e in l])} | "
                      f"{dict(Counter(str(e.get('reconferencia_dispositivos')) for e in l))} | "
                      f"{dict(Counter(str(e.get('reconferencia_trechos_citados')) for e in l))} | "
                      f"{faixa([e.get('reconferencia_trechos_prompt') for e in l])} | "
                      f"{faixa([e.get('reconferencia_tokens_concordancia') for e in l])} | "
                      f"{dict(Counter(str(e.get('reconferencia_concordancia')) for e in l))} | "
                      f"{dict(Counter(str(e.get('reconferencia_busca')) for e in l))} |")
    md.append("\n### 5. A pausa mudou alguma coisa?\n\n| Conversa | Tipo | n | Passaram | Falharam | Códigos das falhas |\n|---|---|---|---|---|---|")
    for p, c in list(CONVERSAS) + [(None, "todas")]:
        for t, nome in (("apos_pausa", "pausa"), ("logo_depois", "logo")):
            l = [e for e in execs if (c == "todas" or e["conversa"] == c) and e["tipo"] == t and e["passou"] is not None]
            f = Counter((e.get("reconferencia_lei") or "(vazio)") for e in l if e["passou"] is False)
            md.append(f"| `{c}` | {nome} | {len(l)} | {sum(1 for e in l if e['passou'])} | {sum(1 for e in l if e['passou'] is False)} | {dict(f)} |")
    md.append(f"\nbge-m3 carregado: no início {ini.get('ollama')}, no fim {fim.get('ollama')}; "
              f"antes e depois de cada pausa, na coluna 'bge antes/depois' da tabela 2.")
    if csv_linhas:
        desencontradas = [e["id"] for e in execs if e.get("passou") is not None and not e.get("auditoria_confere_hora")]
        if desencontradas:
            raise SystemExit(f"auditoria do banco fora da janela de 40 s do pedido em {desencontradas}: "
                             "o casamento pela ordem não vale")
    args.md.write_text("\n".join(md) + "\n", encoding="utf-8")
    args.saida.write_text(json.dumps({"execucoes": execs, "inicio": ini, "fim": fim}, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8")
    print(f"{len(execs)} execuções: {sum(1 for e in execs if e['passou'])} passaram, "
          f"{sum(1 for e in execs if e['passou'] is False)} falharam, {sum(1 for e in execs if e['passou'] is None)} fora")
    return 0


if __name__ == "__main__":
    sys.exit(main())
