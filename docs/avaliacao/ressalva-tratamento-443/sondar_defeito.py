"""Sondagem da #443 na Ceres publicada: o defeito existe e quanto?

NÃO é a medição da regra (um braço só, sem o candidato): sonda a Ceres publicada. Por padrão, as 36 perguntas
de defeito, 1 repetição; `--grupos` e `--repeticoes` ampliam (linha de base na VPS, por etapas, com
`--retomar` para continuar depois de um token vencido). O resultado não alimenta o `decidir_ressalva.py`
(que recusa rodada fora da regra).

O token vem **só do ambiente** (nunca em argumento, arquivo, log ou relatório; vale 15 minutos):

    RESSALVA_SONDA_URL=https://... RESSALVA_SONDA_TOKEN=... python3 -I sondar_defeito.py --saida <pasta> \
        [--grupos DEFEITO,CONTROLE_LEI,CONTROLE_DADOS] [--repeticoes 3] [--retomar]

Antes e depois, lê `GET /api/assistente/uso` e mostra o `percentualUsado`, os créditos consumidos e os
restantes. Para a cota (429) ou o token vencido (401) o script para sozinho, grava o que mediu e não tenta
outra conta.
"""

import argparse
import json
import os
import random
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import executar_ressalva as ex  # noqa: E402
import ressalva_comum as comum  # noqa: E402

PAUSA_S = 3.1  # limite de 20 perguntas por minuto por usuário (#150), descontada a latência da resposta
DESCARTADO = "DESCARTADO"  # fidelidade da rota de lei reprovou e entregou os trechos no lugar do texto


def descartada_pela_fidelidade(resposta):
    """Não consegui confirmar uma resposta nos trechos de lei que encontrei…: descarte, não defeito."""
    return "nao consegui confirmar uma resposta" in comum.normalizar(resposta)


def classificar(execucao):
    """Três desfechos (OK = correto, DEFEITO, DESCARTADO) mais SEM_TRATAMENTO e FALHA_TECNICA, e se a
    resposta fala do contador sem o detector reconhecer a ressalva."""
    classe = comum.classificar_defeito(execucao)
    if classe == comum.SEM_TRATAMENTO and descartada_pela_fidelidade(execucao.get("resposta")):
        return DESCARTADO, False
    solta = classe == comum.DEFEITO and comum.menciona_contador_solto(execucao.get("resposta"))
    return classe, solta


def classe_da_execucao(pergunta, execucao):
    """Defeito: OK/DEFEITO/DESCARTADO/SEM_TRATAMENTO/FALHA_TECNICA. Controle: ACERTOU/ERROU/DESCARTADO."""
    if pergunta["grupo"] == "DEFEITO":
        return classificar(execucao)[0]
    if comum.e_falha_tecnica(execucao):
        return comum.FALHA_TECNICA
    if descartada_pela_fidelidade(execucao.get("resposta")):
        return DESCARTADO
    return "ACERTOU" if comum.acertou(pergunta, execucao) else "ERROU"


def resumo(execucoes, perguntas=None):
    """Resumo por classe e rota. Com `perguntas` ({id: pergunta}), também por grupo e por id."""
    classes = Counter()
    por_rota = Counter()
    soltas = []
    por_grupo, por_id = {}, {}
    for e in execucoes:
        classe, solta = classificar(e)
        classes[classe] += 1
        por_rota[(e.get("rota"), classe)] += 1
        if solta:
            soltas.append(e["id"])
        if perguntas and e["id"] in perguntas:
            p = perguntas[e["id"]]
            c = classe_da_execucao(p, e)
            por_grupo.setdefault(p["grupo"], Counter())[c] += 1
            por_id.setdefault(e["id"], {})[e["rep"]] = c
    saida = {"classes": dict(classes), "porRotaEClasse": {f"{r}/{c}": n for (r, c), n in sorted(
        por_rota.items(), key=lambda x: (str(x[0][0]), x[0][1]))}, "mencionaContadorSemRessalva": soltas}
    if perguntas:
        saida["porGrupo"] = {g: dict(c) for g, c in sorted(por_grupo.items())}
        saida["porId"] = {i: [v[r] for r in sorted(v)] for i, v in sorted(por_id.items())}
    return saida


def uso(base_url, token):
    status, corpo, _ = ex.requisitar(base_url + "/api/assistente/uso", None,
                                     {"Authorization": "Bearer " + token}, metodo="GET")
    if status != 200:
        return {"http": status}
    return {k: corpo.get(k) for k in ("percentualUsado", "consumidos", "restantes", "creditosSemana")}


def plano_da_sonda(perguntas, repeticoes):
    """(rep, pergunta), repetição por repetição, com a ordem sorteada com semente fixa por repetição."""
    saida = []
    for rep in range(1, repeticoes + 1):
        ordem = list(perguntas)
        random.Random(4430 + rep).shuffle(ordem)
        saida += [(rep, p) for p in ordem]
    return saida


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--saida", required=True, help="pasta fora do repositório")
    ap.add_argument("--grupos", default="DEFEITO", help="DEFEITO, CONTROLE_LEI e/ou CONTROLE_DADOS, com vírgula")
    ap.add_argument("--repeticoes", type=int, default=1)
    ap.add_argument("--retomar", action="store_true", help="continua a pasta: pula o que já está gravado")
    args = ap.parse_args(argv)
    base_url = os.environ.get("RESSALVA_SONDA_URL", "").strip().rstrip("/")
    token = os.environ.get("RESSALVA_SONDA_TOKEN", "").strip()
    if not base_url or not token:
        sys.exit("Defina RESSALVA_SONDA_URL e RESSALVA_SONDA_TOKEN no ambiente.")
    grupos = [g.strip() for g in args.grupos.split(",") if g.strip()]
    if not grupos or any(g not in ("DEFEITO", "CONTROLE_LEI", "CONTROLE_DADOS") for g in grupos):
        sys.exit("--grupos aceita DEFEITO, CONTROLE_LEI e CONTROLE_DADOS.")
    conjunto = comum.carregar_conjunto()
    todas = {p["id"]: p for p in conjunto["perguntas"]}
    selecionadas = [p for p in conjunto["perguntas"] if p["grupo"] in grupos]
    pasta = Path(args.saida)
    pasta.mkdir(parents=True, exist_ok=True)
    arquivo = pasta / "sondagem.jsonl"
    feitas = []
    if args.retomar and arquivo.exists():
        feitas = [json.loads(linha) for linha in arquivo.read_text(encoding="utf-8").splitlines() if linha]
    elif arquivo.exists():
        sys.exit(f"{arquivo} já existe: use --retomar para continuar ou outra pasta.")
    ja = {(e["id"], e["rep"]) for e in feitas}
    antes = uso(base_url, token)
    print("uso antes:", json.dumps(antes), flush=True)
    execucoes, interrompida = list(feitas), None
    try:
        with open(arquivo, "a", encoding="utf-8") as saida:
            for rep, p in plano_da_sonda(selecionadas, args.repeticoes):
                if (p["id"], rep) in ja:
                    continue
                inicio = time.monotonic()
                r = ex.perguntar(base_url, token, p["pergunta"])
                r.update({"id": p["id"], "grupo": p["grupo"], "braco": "sonda", "rep": rep})
                if r["status"] == 401:
                    interrompida = "Token vencido ou recusado (HTTP 401)"
                    break
                execucoes.append(r)
                saida.write(json.dumps(r, ensure_ascii=False) + "\n")
                saida.flush()
                print(f"{p['id']} r{rep} {p['grupo']:<14} HTTP {r['status']} rota={r['rota']} "
                      f"{classe_da_execucao(p, r)}", flush=True)
                time.sleep(max(0.0, PAUSA_S - (time.monotonic() - inicio)))
    except ex.CotaEsgotada:
        interrompida = "Cota semanal esgotada (429 LIMITE_USO_ASSISTENTE)"
    except KeyboardInterrupt:
        interrompida = "Interrompida"
    depois = uso(base_url, token)
    total = len(selecionadas) * args.repeticoes
    relatorio = {"conjunto": conjunto["versao"], "regra": comum.VERSAO_REGRA, "grupos": grupos,
                 "repeticoes": args.repeticoes, "previstas": total, "respondidas": len(execucoes),
                 "interrompida": interrompida, "usoAntes": antes, "usoDepois": depois,
                 **resumo(execucoes, todas)}
    (pasta / "sondagem-resumo.json").write_text(json.dumps(relatorio, ensure_ascii=False, indent=2) + "\n",
                                                encoding="utf-8")
    print("uso depois:", json.dumps(depois))
    print(json.dumps({k: v for k, v in relatorio.items() if k != "porId"}, ensure_ascii=False, indent=2))
    if interrompida:
        sys.exit(2)


if __name__ == "__main__":
    main()
