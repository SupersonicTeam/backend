"""Sondagem de uma passada: o defeito da #443 existe na Ceres publicada?

NÃO é a medição da regra (sem 3 repetições, sem dois braços): serve para saber se o conjunto reproduz
o defeito antes de gastar a cota numa linha de base completa. Só as 36 perguntas de defeito, 1 repetição,
um braço. O resultado não alimenta o `decidir_ressalva.py` (que recusa rodada fora da regra).

O token vem **só do ambiente** (nunca em argumento, arquivo, log ou relatório; vale 15 minutos):

    RESSALVA_SONDA_URL=https://... RESSALVA_SONDA_TOKEN=... python3 -I sondar_defeito.py --saida <pasta>

Antes e depois, lê `GET /api/assistente/uso` e mostra o `percentualUsado`.
"""

import argparse
import json
import os
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import executar_ressalva as ex  # noqa: E402
import ressalva_comum as comum  # noqa: E402

PAUSA_S = 3.1  # limite de 20 perguntas por minuto por usuário (#150)


DESCARTADO = "DESCARTADO"  # fidelidade da rota de lei reprovou e entregou os trechos no lugar do texto


def descartada_pela_fidelidade(resposta):
    """"Não consegui confirmar uma resposta nos trechos de lei que encontrei…": descarte, não defeito."""
    return "nao consegui confirmar uma resposta" in comum.normalizar(resposta)


def classificar(execucao):
    """Três desfechos (OK = correto, DEFEITO, DESCARTADO) mais SEM_TRATAMENTO e FALHA_TECNICA, e se a
    resposta fala do contador sem o detector reconhecer a ressalva."""
    classe = comum.classificar_defeito(execucao)
    if classe == comum.SEM_TRATAMENTO and descartada_pela_fidelidade(execucao.get("resposta")):
        return DESCARTADO, False
    solta = classe == comum.DEFEITO and comum.menciona_contador_solto(execucao.get("resposta"))
    return classe, solta


def resumo(execucoes):
    classes = Counter()
    por_rota = Counter()
    soltas = []
    for e in execucoes:
        classe, solta = classificar(e)
        classes[classe] += 1
        por_rota[(e.get("rota"), classe)] += 1
        if solta:
            soltas.append(e["id"])
    return {"classes": dict(classes), "porRotaEClasse": {f"{r}/{c}": n for (r, c), n in sorted(
        por_rota.items(), key=lambda x: (str(x[0][0]), x[0][1]))}, "mencionaContadorSemRessalva": soltas}


def uso(base_url, token):
    status, corpo, _ = ex.requisitar(base_url + "/api/assistente/uso", None,
                                     {"Authorization": "Bearer " + token}, metodo="GET")
    return corpo.get("percentualUsado") if status == 200 else f"HTTP {status}"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--saida", required=True, help="pasta fora do repositório")
    args = ap.parse_args(argv)
    base_url = os.environ.get("RESSALVA_SONDA_URL", "").strip().rstrip("/")
    token = os.environ.get("RESSALVA_SONDA_TOKEN", "").strip()
    if not base_url or not token:
        sys.exit("Defina RESSALVA_SONDA_URL e RESSALVA_SONDA_TOKEN no ambiente.")
    conjunto = comum.carregar_conjunto()
    perguntas = [p for p in conjunto["perguntas"] if p["grupo"] == "DEFEITO"]
    pasta = Path(args.saida)
    pasta.mkdir(parents=True, exist_ok=True)
    print("percentualUsado antes:", uso(base_url, token), flush=True)
    execucoes, interrompida = [], None
    try:
        with open(pasta / "sondagem.jsonl", "w", encoding="utf-8") as saida:
            for p in perguntas:
                r = ex.perguntar(base_url, token, p["pergunta"])
                r.update({"id": p["id"], "braco": "sonda", "rep": 1})
                if r["status"] == 401:
                    interrompida = "Token vencido ou recusado (HTTP 401)"
                    break
                execucoes.append(r)
                saida.write(json.dumps(r, ensure_ascii=False) + "\n")
                saida.flush()
                classe, _ = classificar(r)
                print(f"{p['id']} {p['estrato']} {p['forma']:<15} HTTP {r['status']} rota={r['rota']} {classe}",
                      flush=True)
                time.sleep(PAUSA_S)
    except ex.CotaEsgotada:
        interrompida = "Cota semanal esgotada (429 LIMITE_USO_ASSISTENTE)"
    except KeyboardInterrupt:
        interrompida = "Interrompida"
    depois = uso(base_url, token)
    relatorio = {"conjunto": conjunto["versao"], "perguntas": len(perguntas), "respondidas": len(execucoes),
                 "interrompida": interrompida, "percentualUsadoDepois": depois, **resumo(execucoes)}
    (pasta / "sondagem-resumo.json").write_text(json.dumps(relatorio, ensure_ascii=False, indent=2) + "\n",
                                                encoding="utf-8")
    print("percentualUsado depois:", depois)
    print(json.dumps(relatorio, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
