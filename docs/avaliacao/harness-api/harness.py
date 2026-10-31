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
import socket
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
import zoneinfo

# Maior que o timeout da pergunta no servidor (app.assistente.timeout-pergunta, 5 min).
TIMEOUT_S = 330
FUSO = zoneinfo.ZoneInfo("America/Sao_Paulo")
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
    except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, ValueError) as erro:
        # Rede, tempo esgotado ou corpo que nao e JSON: vira resultado da pergunta, nunca derruba
        # a medicao inteira (o que ja foi medido seria perdido).
        return 0, {"errors": [{"code": "FALHA_" + type(erro).__name__}]}, {}


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
            raise CotaEsgotada()
        if status in (429, 503) and codigo in ("MUITAS_TENTATIVAS", "ASSISTENTE_OCUPADO"):
            time.sleep(int(cabecalhos.get("Retry-After", "5")))
            continue
        return {"status": status, "codigo": codigo, "latenciaMs": latencia_ms,
                "rota": corpo.get("rota"), "modoFallback": corpo.get("modoFallback"),
                "citacoes": len(corpo.get("citacoes") or []),
                "fontesDados": len(corpo.get("fontesDados") or []),
                "resposta": corpo.get("resposta")}
    return {"status": status, "codigo": codigo, "latenciaMs": latencia_ms, "rota": None}


class CotaEsgotada(Exception):
    """429 LIMITE_USO_ASSISTENTE: a medicao para, gravando o que ja foi medido."""


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

    agora = datetime.datetime.now(FUSO).strftime("%Y-%m-%d-%H%M")
    os.makedirs(saida, exist_ok=True)
    destino = os.path.join(saida, f"{agora}-harness-api.json")
    resultados = []
    interrompida = None
    token = login(base, email, senha)
    inicio_token = time.monotonic()
    try:
        for rodada in range(1, repeticoes + 1):
            for p in perguntas:
                if time.monotonic() - inicio_token > 12 * 60:  # access token de 15 min (#206)
                    token = login(base, email, senha)
                    inicio_token = time.monotonic()
                r = perguntar(base, token, p["pergunta"])
                r.update({"id": p["id"], "rodada": rodada, "rotaEsperada": p.get("rotaEsperada")})
                resultados.append(r)
                print(f"rodada {rodada}/{repeticoes} {p['id']} HTTP {r['status']} "
                      f"rota={r['rota']} {r['latenciaMs']} ms", flush=True)
    except CotaEsgotada:
        interrompida = "Cota semanal da Ceres esgotada (429 LIMITE_USO_ASSISTENTE)"
    except (KeyboardInterrupt, SystemExit) as erro:
        interrompida = f"Interrompida ({type(erro).__name__})"
    finally:
        gravar(destino, agora, gabarito["versao"], repeticoes, resultados, interrompida)
    if interrompida:
        sys.exit(interrompida + f": o que foi medido esta em {destino}")


def gravar(destino, agora, versao, repeticoes, resultados, interrompida):
    por_rota = {}
    for r in resultados:
        chave = r.get("rotaEsperada") or "SEM_ROTA"
        grupo = por_rota.setdefault(chave, {"latencias": [], "acertos": 0, "total": 0})
        grupo["latencias"].append(r["latenciaMs"])
        if r.get("rotaEsperada"):
            grupo["total"] += 1
            grupo["acertos"] += int(r.get("rota") == r["rotaEsperada"])
    resumo = {rota: {"respostas": len(g["latencias"]),
                     "p50Ms": percentil(g["latencias"], 50),
                     "p95Ms": percentil(g["latencias"], 95),
                     "mediaMs": round(statistics.mean(g["latencias"])),
                     "acertoRota": f"{g['acertos']}/{g['total']}" if g["total"] else "-"}
              for rota, g in por_rota.items()}
    with open(destino, "w", encoding="utf-8") as arquivo:
        # Sem URL nem e-mail no arquivo: ele pode ser versionado.
        json.dump({"executadoEm": agora, "fuso": "America/Sao_Paulo", "versaoGabarito": versao,
                   "repeticoes": repeticoes, "interrompida": interrompida, "porRota": resumo,
                   "resultados": resultados}, arquivo, ensure_ascii=False, indent=2)
    print(json.dumps(resumo, ensure_ascii=False, indent=2))
    print(f"Gravado em {destino}")


if __name__ == "__main__":
    main()
