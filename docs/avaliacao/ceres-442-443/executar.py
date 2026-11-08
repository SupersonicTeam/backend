#!/usr/bin/env python3
"""Executa o conjunto da #442/#443 contra uma Ceres local e grava um JSONL para o pontuar.py.

Só biblioteca padrão. Cada pergunta é feita `--repeticoes` vezes, em ordem fixa (repetição por
repetição), pela conta indicada. A senha vem da variável de ambiente CERES_SENHA e nunca vai para
o arquivo nem para a saída.

Falha técnica (HTTP diferente de 200, `modoFallback`, erro de indisponibilidade ou de tempo) é
registrada com `falha_tecnica: true`, o modelo é reaquecido e a execução se repete, até
`--tentativas` vezes; as duas linhas ficam no JSONL (regra-de-decisao.md).

Exemplo:
  CERES_SENHA=... python3 executar.py --url http://localhost:8442 \\
      --email carlos.rossi@example.com --saida base.jsonl
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

AQUI = Path(__file__).resolve().parent
CONJUNTO = AQUI.parent / "treino" / "perguntas-ceres-442-443.json"
# Brasília não tem horário de verão desde 2019: o fuso fixo dispensa o tzdata no Windows.
BRASILIA = dt.timezone(dt.timedelta(hours=-3))

sys.path.insert(0, str(AQUI))
import pontuar  # noqa: E402


def requisitar(metodo: str, url: str, corpo=None, token: str | None = None,
               timeout: float = 60) -> tuple[int, dict | None, dict]:
    dados = None if corpo is None else json.dumps(corpo).encode("utf-8")
    pedido = urllib.request.Request(url, data=dados, method=metodo)
    pedido.add_header("Content-Type", "application/json")
    pedido.add_header("Accept", "application/json")
    if token:
        pedido.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(pedido, timeout=timeout) as resposta:
            texto = resposta.read().decode("utf-8")
            return resposta.status, (json.loads(texto) if texto else None), dict(resposta.headers)
    except urllib.error.HTTPError as erro:
        texto = erro.read().decode("utf-8", "replace")
        try:
            lido = json.loads(texto) if texto else None
        except ValueError:
            lido = None
        return erro.code, lido, dict(erro.headers)
    except (urllib.error.URLError, TimeoutError, ConnectionError) as erro:
        return 0, {"erro": type(erro).__name__}, {}


def entrar(url: str, email: str, senha: str) -> str:
    status, corpo, _ = requisitar("POST", url + "/api/auth/login",
                                  {"email": email, "senha": senha})
    if status != 200 or not corpo or "accessToken" not in corpo:
        raise SystemExit(f"Login falhou: HTTP {status}")
    return corpo["accessToken"]


def aquecer(ollama: str, modelo: str, embedding: str) -> None:
    """Carrega o modelo de chat e o de embedding no Ollama, com contexto e keep_alive longos."""
    requisitar("POST", ollama + "/api/chat", {
        "model": modelo, "messages": [{"role": "user", "content": "ok"}], "stream": False,
        "think": False, "keep_alive": "2h", "options": {"num_ctx": 8192, "num_predict": 1},
    }, timeout=600)
    requisitar("POST", ollama + "/api/embed", {
        "model": embedding, "input": "aquecimento", "keep_alive": "2h"}, timeout=600)


def agora_brasilia() -> dt.datetime:
    return dt.datetime.now(BRASILIA)


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analisador.add_argument("--url", required=True, help="base da aplicação, sem barra no fim")
    analisador.add_argument("--email", required=True)
    analisador.add_argument("--saida", type=Path, required=True)
    analisador.add_argument("--repeticoes", type=int, default=3)
    analisador.add_argument("--tentativas", type=int, default=3,
                            help="execuções por pergunta e repetição, contando as técnicas")
    analisador.add_argument("--ids", help="só estes ids, separados por vírgula (ensaio)")
    analisador.add_argument("--ollama", default="http://localhost:11434")
    analisador.add_argument("--modelo", default="qwen3:4b-instruct")
    analisador.add_argument("--embedding", default="bge-m3")
    analisador.add_argument("--timeout", type=float, default=330)
    args = analisador.parse_args(argv)

    senha = os.environ.get("CERES_SENHA")
    if not senha:
        raise SystemExit("Defina CERES_SENHA com a senha da conta (nunca em arquivo)")
    conjunto = pontuar.carregar_conjunto(CONJUNTO)
    perguntas = conjunto["perguntas"]
    if args.ids:
        escolhidos = set(args.ids.split(","))
        perguntas = [p for p in perguntas if p["id"] in escolhidos]

    if args.saida.exists() and args.saida.stat().st_size > 0:
        # Duas rodadas no mesmo arquivo somariam os votos das duas.
        raise SystemExit(f"{args.saida} já tem execuções: use outro arquivo para a rodada")
    token = entrar(args.url, args.email, senha)
    token_em = time.monotonic()
    aquecer(args.ollama, args.modelo, args.embedding)
    with args.saida.open("a", encoding="utf-8") as saida:
        for repeticao in range(1, args.repeticoes + 1):
            for pergunta in perguntas:
                for tentativa in range(1, args.tentativas + 1):
                    if time.monotonic() - token_em > 600:
                        token = entrar(args.url, args.email, senha)
                        token_em = time.monotonic()
                    inicio = time.monotonic()
                    data = agora_brasilia().date().isoformat()
                    status, corpo, cabecalhos = requisitar(
                        "POST", args.url + "/api/assistente/chat",
                        {"pergunta": pergunta["pergunta"]}, token, args.timeout)
                    if status == 401:
                        token = entrar(args.url, args.email, senha)
                        token_em = time.monotonic()
                    registro = {
                        "id": pergunta["id"], "repeticao": repeticao, "tentativa": tentativa,
                        "http_status": status, "corpo": corpo, "data_referencia": data,
                        "segundos": round(time.monotonic() - inicio, 1),
                    }
                    tecnica = pontuar.falha_tecnica(registro)
                    registro["falha_tecnica"] = tecnica
                    saida.write(json.dumps(registro, ensure_ascii=False) + "\n")
                    saida.flush()
                    rota = (corpo or {}).get("rota") if isinstance(corpo, dict) else None
                    print(f"{pergunta['id']} r{repeticao} t{tentativa} HTTP {status} "
                          f"{rota} {registro['segundos']}s" + (" TECNICA" if tecnica else ""),
                          flush=True)
                    if not tecnica:
                        break
                    if status == 429:
                        time.sleep(float(cabecalhos.get("Retry-After", 60)))
                    else:
                        aquecer(args.ollama, args.modelo, args.embedding)
    return 0


if __name__ == "__main__":
    sys.exit(main())
