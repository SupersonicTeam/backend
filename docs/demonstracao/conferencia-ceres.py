#!/usr/bin/env python3
"""Chamadas da conferencia local da Ceres com as perguntas do roteiro da banca (#435).

So biblioteca padrao. Nada de segredo no arquivo: a senha das contas semeadas vem de ``SEED_SENHA``
no ambiente de quem roda. Cada chamada acrescenta uma linha JSON (``--saida``) com o que o cliente
viu: status, ``errors[0].code``, rota, fontes, citacoes, ``modoFallback``, latencia e o texto.

Uso (a aplicacao e o Ollama ja no ar; ver o documento da conferencia):

    SEED_SENHA=... python3 conferencia-ceres.py --saida chamadas.jsonl aquecer
    SEED_SENHA=... python3 conferencia-ceres.py --saida chamadas.jsonl ps carlos
    SEED_SENHA=... python3 conferencia-ceres.py --saida chamadas.jsonl carlos-atalhos
    SEED_SENHA=... python3 conferencia-ceres.py --saida chamadas.jsonl carlos-livres
    SEED_SENHA=... python3 conferencia-ceres.py --saida chamadas.jsonl icms --numero 2041
    SEED_SENHA=... python3 conferencia-ceres.py --saida chamadas.jsonl rogerio
    SEED_SENHA=... python3 conferencia-ceres.py --saida chamadas.jsonl apuracao --periodo 2026-10
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
OLLAMA = os.environ.get("OLLAMA_URL", "http://localhost:11434")
CHAT = "qwen3:4b-instruct"
EMBEDDING = "bge-m3"
NUM_CTX = 8192
KEEP_ALIVE = "60m"

CARLOS = "carlos.rossi@example.com"
ROGERIO = "rogerio.antunes@example.com"

# Textos fixados antes da primeira execucao.
ATALHOS = ["Quanto eu tenho que pagar este mês?", "Quando vence a minha próxima guia?",
           "O que muda para mim com o IBS e o CBS?"]
LIVRES = ["quanto eu comprei de fertilizante?",
          "qual foi minha apuração de IBS e CBS do mês passado?",
          "a soja tem redução de alíquota?"]
ICMS_GERAL = "Tem alguma nota minha com o ICMS sem definir?"
ICMS_NOTA = "A nota número {numero} está com o CST do ICMS definido?"
ROGERIO_LIVRE_1 = "Quais notas fiscais eu emiti neste mês?"
ROGERIO_LIVRE_2 = "Quanto eu vendi de soja neste ano?"
ROGERIO_ATALHO = ATALHOS[0]
REPETICOES = 3


def http(metodo, url, corpo=None, token=None, timeout=330):
    dados = None if corpo is None else json.dumps(corpo, ensure_ascii=False).encode("utf-8")
    cabecalhos = {"Content-Type": "application/json; charset=utf-8", "Accept": "application/json"}
    if token:
        cabecalhos["Authorization"] = "Bearer " + token
    requisicao = urllib.request.Request(url, data=dados, headers=cabecalhos, method=metodo)
    inicio = time.monotonic()
    try:
        with urllib.request.urlopen(requisicao, timeout=timeout) as resposta:
            texto, status, headers = resposta.read().decode("utf-8"), resposta.status, resposta.headers
    except urllib.error.HTTPError as e:
        texto, status, headers = e.read().decode("utf-8", "replace"), e.code, e.headers
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return {"status": None, "erroCliente": f"{type(e).__name__}: {e}",
                "latenciaMs": round((time.monotonic() - inicio) * 1000)}
    try:
        corpo_json = json.loads(texto) if texto else None
    except ValueError:
        corpo_json = {"textoBruto": texto[:500]}
    return {"status": status, "corpo": corpo_json, "retryAfter": headers.get("Retry-After"),
            "totalCount": headers.get("X-Total-Count"),
            "latenciaMs": round((time.monotonic() - inicio) * 1000)}


def login(email):
    senha = os.environ.get("SEED_SENHA")
    if not senha:
        sys.exit("SEED_SENHA ausente no ambiente")
    r = http("POST", BASE + "/api/auth/login", {"email": email, "senha": senha})
    if r.get("status") != 200:
        sys.exit(f"login de {email} falhou: {r.get('status')}")
    return r["corpo"]["accessToken"]


def gravar(saida, registro):
    registro["quando"] = datetime.now().astimezone().isoformat(timespec="seconds")
    with open(saida, "a", encoding="utf-8") as arquivo:
        arquivo.write(json.dumps(registro, ensure_ascii=False) + "\n")
    resumo = registro.get("resumo", "")
    print(f"{registro.get('conta', '')[:12]:12} {registro['tipo']:8} {registro.get('status')} "
          f"{registro.get('latenciaMs')} ms {resumo}")


def perguntar(saida, token, conta, tipo, texto, repeticao, nota=None):
    r = http("POST", BASE + "/api/assistente/chat", {"pergunta": texto}, token)
    corpo = r.get("corpo") or {}
    erros = corpo.get("errors") or []
    registro = {
        "conta": conta, "tipo": tipo, "pergunta": texto, "repeticao": repeticao, "nota": nota,
        "status": r.get("status"), "erroCliente": r.get("erroCliente"),
        "codigoErro": erros[0].get("code") if erros else None, "retryAfter": r.get("retryAfter"),
        "rota": corpo.get("rota"), "modoFallback": corpo.get("modoFallback"),
        "fontesDados": corpo.get("fontesDados"),
        "citacoes": [{"norma": c.get("norma"), "dispositivo": c.get("dispositivo")}
                     for c in corpo.get("citacoes") or []],
        "atalhos": corpo.get("atalhos"), "latenciaMs": r.get("latenciaMs"),
        "resposta": corpo.get("resposta"),
    }
    registro["resumo"] = f"rota={registro['rota']} fallback={registro['modoFallback']} " \
                         f"erro={registro['codigoErro']}"
    gravar(saida, registro)
    return registro


def ollama(caminho, corpo=None, timeout=600):
    requisicao = urllib.request.Request(
        OLLAMA + caminho, data=None if corpo is None else json.dumps(corpo).encode(),
        headers={"Content-Type": "application/json"}, method="GET" if corpo is None else "POST")
    with urllib.request.urlopen(requisicao, timeout=timeout) as resposta:
        return json.loads(resposta.read().decode() or "{}")


def aquecer(saida):
    """Os dois modelos na memoria, com o num_ctx da Ceres e keep_alive longo."""
    chat = ollama("/api/chat", {"model": CHAT, "stream": False, "think": False,
                                "keep_alive": KEEP_ALIVE, "messages": [{"role": "user", "content": "ok"}],
                                "options": {"num_ctx": NUM_CTX, "num_predict": 8}})
    emb = ollama("/api/embed", {"model": EMBEDDING, "input": "aquecimento", "keep_alive": KEEP_ALIVE})
    gravar(saida, {"tipo": "aquecer", "status": 200,
                   "chatCarregamentoMs": round((chat.get("load_duration") or 0) / 1e6),
                   "embeddingDimensao": len((emb.get("embeddings") or [[]])[0])})


def ps(saida, bloco):
    modelos = [{"name": m.get("name"), "context_length": m.get("context_length"),
                "size": m.get("size"), "size_vram": m.get("size_vram"),
                "expires_at": m.get("expires_at")} for m in ollama("/api/ps", timeout=10).get("models", [])]
    gravar(saida, {"tipo": "ps", "bloco": bloco, "status": 200, "modelos": modelos,
                   "resumo": json.dumps(modelos)})


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--saida", default="chamadas.jsonl")
    sub = p.add_subparsers(dest="comando", required=True)
    sub.add_parser("aquecer")
    sub.add_parser("ps").add_argument("bloco")
    sub.add_parser("carlos-atalhos")
    sub.add_parser("carlos-livres")
    icms = sub.add_parser("icms")
    icms.add_argument("--numero", required=True)
    sub.add_parser("rogerio")
    sub.add_parser("apuracao").add_argument("--periodo", required=True)
    a = p.parse_args()
    s = a.saida
    if a.comando == "aquecer":
        aquecer(s)
    elif a.comando == "ps":
        ps(s, a.bloco)
    elif a.comando == "carlos-atalhos":
        token = login(CARLOS)
        for texto in ATALHOS:
            perguntar(s, token, CARLOS, "atalho", texto, 1)
    elif a.comando == "carlos-livres":
        token = login(CARLOS)
        for texto in LIVRES:
            for n in range(1, REPETICOES + 1):
                perguntar(s, token, CARLOS, "livre", texto, n)
    elif a.comando == "icms":
        token = login(CARLOS)
        for texto in (ICMS_GERAL, ICMS_NOTA.format(numero=a.numero)):
            for n in range(1, REPETICOES + 1):
                perguntar(s, token, CARLOS, "icms", texto, n, nota=a.numero)
    elif a.comando == "rogerio":
        token = login(ROGERIO)
        uso = http("GET", BASE + "/api/assistente/uso", token=token)
        gravar(s, {"conta": ROGERIO, "tipo": "uso", "status": uso.get("status"),
                   "corpo": uso.get("corpo"), "latenciaMs": uso.get("latenciaMs")})
        perguntar(s, token, ROGERIO, "livre", ROGERIO_LIVRE_1, 1)
        perguntar(s, token, ROGERIO, "livre", ROGERIO_LIVRE_2, 1)
        perguntar(s, token, ROGERIO, "atalho", ROGERIO_ATALHO, 1)
    elif a.comando == "apuracao":
        token = login(CARLOS)
        r = http("GET", BASE + "/api/apuracao?periodo=" + a.periodo, token=token)
        gravar(s, {"conta": CARLOS, "tipo": "apuracao", "status": r.get("status"),
                   "corpo": r.get("corpo"), "latenciaMs": r.get("latenciaMs")})


if __name__ == "__main__":
    main()
