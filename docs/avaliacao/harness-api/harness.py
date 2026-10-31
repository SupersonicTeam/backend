#!/usr/bin/env python3
"""Harness da Ceres contra o ambiente publicado (issue #264, item 7).

Roda as perguntas do gabarito pela API publica (login + POST /api/assistente/chat) e mede a
latencia real, de ponta a ponta, como o produtor a sente. Nao avalia numero nem citacao: isso e do
comando `dev,avaliacao`, que conhece o seed. Aqui o que conta e o tempo e a rota.

Nada de URL, conta ou senha no repositorio: tudo por variavel de ambiente.

    AGRO_API_URL        ex.: https://www.exemplo.com.br (sem barra no fim)          [obrigatoria]
    AGRO_EMAIL          e-mail de uma conta de PRODUTOR                              [obrigatoria]
    AGRO_SENHA          senha dessa conta                                            [obrigatoria]
    AGRO_BASIC_USUARIO  usuario do Basic Auth do Caddy da demonstracao (opcional)
    AGRO_BASIC_SENHA    senha do Basic Auth (opcional; o login passa por ele)
    AGRO_REPETICOES     rodadas (padrao 3)
    AGRO_IDS            ids do gabarito separados por virgula (padrao: todos)
    AGRO_GABARITO       caminho do gabarito (padrao docs/avaliacao/gabarito-ceres.json)
    AGRO_SAIDA          diretorio de saida (padrao docs/avaliacao/resultados)

Uso (da raiz do repositorio):

    AGRO_API_URL=... AGRO_EMAIL=... AGRO_SENHA=... python3 -I docs/avaliacao/harness-api/harness.py

Cada pergunta gasta creditos da cota semanal da Ceres (#166). Com 429 LIMITE_USO_ASSISTENTE o
harness para (esperar a semana virar nao cabe numa medicao); com 429 MUITAS_TENTATIVAS ou 503
ASSISTENTE_OCUPADO, espera o Retry-After e tenta de novo.
"""

import base64
import datetime
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

TIMEOUT_S = 120
MAX_ESPERAS = 5


def obrigatoria(nome):
    valor = os.environ.get(nome, "").strip()
    if not valor:
        sys.exit(f"Defina a variavel de ambiente {nome}")
    return valor


def requisitar(url, corpo, cabecalhos):
    dados = json.dumps(corpo).encode("utf-8")
    req = urllib.request.Request(url, data=dados, method="POST", headers={
        "Content-Type": "application/json", "Accept": "application/json", **cabecalhos})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            return resp.status, json.loads(resp.read() or b"{}"), resp.headers
    except urllib.error.HTTPError as erro:
        try:
            corpo_erro = json.loads(erro.read() or b"{}")
        except ValueError:
            corpo_erro = {}
        return erro.code, corpo_erro, erro.headers


def codigo_erro(corpo):
    erros = corpo.get("errors") or [{}]
    return erros[0].get("code", "")


def login(base, email, senha):
    cabecalhos = {}
    usuario = os.environ.get("AGRO_BASIC_USUARIO")
    if usuario:
        par = f"{usuario}:{os.environ.get('AGRO_BASIC_SENHA', '')}".encode("utf-8")
        cabecalhos["Authorization"] = "Basic " + base64.b64encode(par).decode("ascii")
    status, corpo, _ = requisitar(base + "/api/auth/login", {"email": email, "senha": senha},
                                  cabecalhos)
    if status != 200 or "accessToken" not in corpo:
        # Nunca imprime a senha nem o corpo da resposta.
        sys.exit(f"Login falhou: HTTP {status} {codigo_erro(corpo)}")
    return corpo["accessToken"]


def perguntar(base, token, pergunta):
    for _ in range(MAX_ESPERAS + 1):
        inicio = time.monotonic()
        status, corpo, cabecalhos = requisitar(base + "/api/assistente/chat",
                                               {"pergunta": pergunta},
                                               {"Authorization": "Bearer " + token})
        latencia_ms = round((time.monotonic() - inicio) * 1000)
        codigo = codigo_erro(corpo) if status >= 400 else ""
        if codigo == "LIMITE_USO_ASSISTENTE":
            sys.exit("Cota semanal da Ceres esgotada (429 LIMITE_USO_ASSISTENTE): medicao parada")
        if status in (429, 503) and codigo in ("MUITAS_TENTATIVAS", "ASSISTENTE_OCUPADO"):
            time.sleep(int(cabecalhos.get("Retry-After", "5")))
            continue
        return {"status": status, "codigo": codigo, "latenciaMs": latencia_ms,
                "rota": corpo.get("rota"), "modoFallback": corpo.get("modoFallback"),
                "citacoes": len(corpo.get("citacoes") or []),
                "fontesDados": len(corpo.get("fontesDados") or []),
                "resposta": corpo.get("resposta")}
    return {"status": status, "codigo": codigo, "latenciaMs": latencia_ms, "rota": None}


def percentil(valores, p):
    if not valores:
        return 0
    ordenados = sorted(valores)
    posicao = max(1, -(-p * len(ordenados) // 100))  # nearest-rank, igual ao relatorio Java
    return ordenados[posicao - 1]


def main():
    base = obrigatoria("AGRO_API_URL").rstrip("/")
    email = obrigatoria("AGRO_EMAIL")
    senha = obrigatoria("AGRO_SENHA")
    repeticoes = int(os.environ.get("AGRO_REPETICOES", "3"))
    gabarito_arquivo = os.environ.get("AGRO_GABARITO", "docs/avaliacao/gabarito-ceres.json")
    saida = os.environ.get("AGRO_SAIDA", "docs/avaliacao/resultados")
    ids = {i.strip() for i in os.environ.get("AGRO_IDS", "").split(",") if i.strip()}

    with open(gabarito_arquivo, encoding="utf-8") as arquivo:
        gabarito = json.load(arquivo)
    perguntas = [p for p in gabarito["perguntas"] if not ids or p["id"] in ids]

    token = login(base, email, senha)
    inicio_token = time.monotonic()
    resultados = []
    for rodada in range(1, repeticoes + 1):
        for p in perguntas:
            if time.monotonic() - inicio_token > 12 * 60:  # access token de 15 min (#206)
                token = login(base, email, senha)
                inicio_token = time.monotonic()
            r = perguntar(base, token, p["pergunta"])
            r.update({"id": p["id"], "rodada": rodada, "rotaEsperada": p.get("rotaEsperada")})
            resultados.append(r)
            print(f"rodada {rodada}/{repeticoes} {p['id']} HTTP {r['status']} rota={r['rota']} "
                  f"{r['latenciaMs']} ms", flush=True)

    por_rota = {}
    for r in resultados:
        chave = r.get("rotaEsperada") or "SEM_ROTA"
        grupo = por_rota.setdefault(chave, {"latencias": [], "acertos": 0, "total": 0})
        grupo["latencias"].append(r["latenciaMs"])
        if r.get("rotaEsperada"):
            grupo["total"] += 1
            grupo["acertos"] += int(r.get("rota") == r["rotaEsperada"])
    resumo = {rota: {"perguntas": len(g["latencias"]),
                     "p50Ms": percentil(g["latencias"], 50),
                     "p95Ms": percentil(g["latencias"], 95),
                     "mediaMs": round(statistics.mean(g["latencias"])),
                     "acertoRota": f"{g['acertos']}/{g['total']}" if g["total"] else "-"}
              for rota, g in por_rota.items()}

    os.makedirs(saida, exist_ok=True)
    agora = datetime.datetime.now().strftime("%Y-%m-%d-%H%M")
    destino = os.path.join(saida, f"{agora}-harness-api.json")
    with open(destino, "w", encoding="utf-8") as arquivo:
        # Sem URL nem e-mail no arquivo: ele pode ser versionado.
        json.dump({"executadoEm": agora, "versaoGabarito": gabarito["versao"],
                   "repeticoes": repeticoes, "porRota": resumo, "resultados": resultados},
                  arquivo, ensure_ascii=False, indent=2)
    print(json.dumps(resumo, ensure_ascii=False, indent=2))
    print(f"Gravado em {destino}")


if __name__ == "__main__":
    main()
