#!/usr/bin/env python3
"""Executa o conjunto da #452 em dois braços (base e correção) e grava um JSONL por braço.

Só biblioteca padrão. Os dois braços ficam no ar ao mesmo tempo (portas diferentes, mesmo
Ollama) e se alternam a cada repetição: na 1ª e na 3ª, base e depois correção; na 2ª, correção e
depois base. Antes da primeira pergunta pontuada o executor aquece o modelo no Ollama e faz
PERGUNTAS_DE_AQUECIMENTO em cada braço (linhas com "aquecimento": true, que o pontuar.py ignora);
no começo de cada bloco o modelo é aquecido de novo.

Cada linha traz o braço, o commit, o modelo (nome e digest do Ollama), o horário (ISO, Brasília)
e a data de referência. O commit vem de uma pasta (`git rev-parse HEAD`, e a pasta precisa estar
limpa) ou de um SHA informado.

A senha da conta vem da variável de ambiente CERES_SENHA e nunca vai para o arquivo nem para a
saída. Falha técnica (HTTP diferente de 200, `modoFallback`, indisponibilidade ou tempo) é
gravada com `falha_tecnica: true`; o modelo é reaquecido e a chamada se repete até
`--tentativas` vezes. 429 de cota da Ceres (LIMITE_USO_ASSISTENTE) interrompe a rodada: esperar
a semana virar não é opção.

Exemplo:
  CERES_SENHA=... python executar.py --email produtor@example.com --saida-dir rodada \\
      --braco base http://localhost:8452 ../../../../main-worktree \\
      --braco correcao http://localhost:8453 ../../../../correcao-worktree
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

AQUI = Path(__file__).resolve().parent
CONJUNTO = AQUI.parent / "treino" / "perguntas-ceres-452.json"
# Brasília não tem horário de verão desde 2019: o fuso fixo dispensa o tzdata no Windows.
BRASILIA = dt.timezone(dt.timedelta(hours=-3))
# Fora do conjunto e sem período relativo: só aquecem o modelo, o JIT e os caches de cada braço.
PERGUNTAS_DE_AQUECIMENTO = (
    "Quanto eu vendi de feijão em março de 2026?",
    "O que é o Imposto Seletivo?",
)

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
        with erro:
            texto = erro.read().decode("utf-8", "replace")
        try:
            lido = json.loads(texto) if texto else None
        except ValueError:
            lido = None
        return erro.code, lido, dict(erro.headers)
    except (urllib.error.URLError, TimeoutError, ConnectionError) as erro:
        return 0, {"erro": type(erro).__name__}, {}


def resolver_commit(referencia: str) -> str:
    """SHA de uma pasta git limpa, ou o próprio SHA informado (7 a 40 hex)."""
    pasta = Path(referencia)
    if pasta.is_dir():
        sujo = subprocess.run(["git", "-C", str(pasta), "status", "--porcelain"],
                              capture_output=True, text=True, check=True).stdout.strip()
        if sujo:
            raise SystemExit(f"{pasta} tem mudanças fora de commit: o commit não descreve o braço")
        return subprocess.run(["git", "-C", str(pasta), "rev-parse", "HEAD"],
                              capture_output=True, text=True, check=True).stdout.strip()
    if re.fullmatch(r"[0-9a-f]{7,40}", referencia):
        return referencia
    raise SystemExit(f"Commit inválido (nem pasta nem SHA): {referencia}")


def ordem_dos_bracos(bracos: list, repeticao: int) -> list:
    """Repetições ímpares na ordem dada; pares, na inversa."""
    return list(bracos) if repeticao % 2 == 1 else list(reversed(bracos))


def entrar(url: str, email: str, senha: str) -> str:
    status, corpo, _ = requisitar("POST", url + "/api/auth/login",
                                  {"email": email, "senha": senha})
    if status != 200 or not corpo or "accessToken" not in corpo:
        raise SystemExit(f"Login falhou em {url}: HTTP {status}")
    return corpo["accessToken"]


def digest_do_modelo(ollama: str, modelo: str) -> str | None:
    status, corpo, _ = requisitar("GET", ollama + "/api/tags")
    if status != 200 or not isinstance(corpo, dict):
        return None
    for item in corpo.get("models") or []:
        if item.get("name") == modelo or item.get("model") == modelo:
            return item.get("digest")
    return None


def aquecer(ollama: str, modelo: str, embedding: str) -> None:
    """Carrega o modelo de chat e o de embedding no Ollama, com keep_alive longo."""
    requisitar("POST", ollama + "/api/chat", {
        "model": modelo, "messages": [{"role": "user", "content": "ok"}], "stream": False,
        "think": False, "keep_alive": "2h", "options": {"num_ctx": 8192, "num_predict": 1},
    }, timeout=600)
    requisitar("POST", ollama + "/api/embed", {
        "model": embedding, "input": "aquecimento", "keep_alive": "2h"}, timeout=600)


def agora_brasilia() -> dt.datetime:
    return dt.datetime.now(BRASILIA)


def codigos_de_erro(corpo) -> set:
    if not isinstance(corpo, dict):
        return set()
    return {e.get("code") for e in corpo.get("errors") or [] if isinstance(e, dict)}


class Braco:
    """Um braço da medição: URL, commit, sessão e arquivo de saída."""

    def __init__(self, nome: str, url: str, commit: str, saida: Path):
        self.nome, self.url, self.commit, self.saida = nome, url.rstrip("/"), commit, saida
        self.token: str | None = None
        self.token_em = 0.0

    def token_valido(self, email: str, senha: str) -> str:
        if self.token is None or time.monotonic() - self.token_em > 600:
            self.token = entrar(self.url, email, senha)
            self.token_em = time.monotonic()
        return self.token


def perguntar(braco: Braco, args, senha: str, pergunta: str, base: dict) -> dict:
    """Uma chamada ao chat; devolve a linha do JSONL (já gravada)."""
    token = braco.token_valido(args.email, senha)
    momento = agora_brasilia()
    inicio = time.monotonic()
    status, corpo, cabecalhos = requisitar(
        "POST", braco.url + "/api/assistente/chat", {"pergunta": pergunta}, token, args.timeout)
    if status == 401:
        braco.token = None
    registro = dict(base)
    registro.update({
        "braco": braco.nome, "commit": braco.commit, "modelo": args.modelo,
        "modelo_digest": args.modelo_digest, "horario": momento.isoformat(timespec="seconds"),
        "data_referencia": momento.date().isoformat(), "http_status": status, "corpo": corpo,
        "segundos": round(time.monotonic() - inicio, 1),
    })
    registro["falha_tecnica"] = pontuar.falha_tecnica(registro)
    registro["_retry_after"] = cabecalhos.get("Retry-After")
    gravado = {k: v for k, v in registro.items() if not k.startswith("_")}
    with braco.saida.open("a", encoding="utf-8") as saida:
        saida.write(json.dumps(gravado, ensure_ascii=False) + "\n")
    rota = corpo.get("rota") if isinstance(corpo, dict) else None
    print(f"[{braco.nome}] {base.get('id', 'aquecimento')} r{base.get('repeticao', 0)} "
          f"t{base.get('tentativa', 1)} HTTP {status} {rota} {registro['segundos']}s"
          + (" TECNICA" if registro["falha_tecnica"] else ""), flush=True)
    return registro


def executar_pergunta(braco: Braco, args, senha: str, pergunta: dict, repeticao: int) -> None:
    for tentativa in range(1, args.tentativas + 1):
        registro = perguntar(braco, args, senha, pergunta["pergunta"], {
            "id": pergunta["id"], "repeticao": repeticao, "tentativa": tentativa,
            "aquecimento": False})
        if not registro["falha_tecnica"]:
            return
        codigos = codigos_de_erro(registro["corpo"])
        if "LIMITE_USO_ASSISTENTE" in codigos:
            raise SystemExit(f"[{braco.nome}] cota semanal da Ceres esgotada: rodada interrompida "
                             "(aumente a cota do plano no ambiente local e refaça os dois braços)")
        if registro["http_status"] == 429 or "ASSISTENTE_OCUPADO" in codigos:
            time.sleep(float(registro.get("_retry_after") or 30))
        else:
            aquecer(args.ollama, args.modelo, args.embedding)


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analisador.add_argument("--braco", nargs=3, action="append", required=True,
                            metavar=("NOME", "URL", "PASTA_OU_SHA"),
                            help="repita para os dois braços, a base primeiro")
    analisador.add_argument("--email", required=True)
    analisador.add_argument("--saida-dir", type=Path, required=True)
    analisador.add_argument("--repeticoes", type=int, default=3)
    analisador.add_argument("--tentativas", type=int, default=3,
                            help="chamadas por pergunta e repetição, contando as técnicas")
    analisador.add_argument("--ids", help="só estes ids, separados por vírgula (ensaio)")
    analisador.add_argument("--ollama", default="http://localhost:11434")
    analisador.add_argument("--modelo", default="qwen3:4b-instruct")
    analisador.add_argument("--embedding", default="bge-m3")
    analisador.add_argument("--timeout", type=float, default=330)
    args = analisador.parse_args(argv)

    senha = os.environ.get("CERES_SENHA")
    if not senha:
        raise SystemExit("Defina CERES_SENHA com a senha da conta (nunca em arquivo)")
    nomes = [b[0] for b in args.braco]
    if len(set(nomes)) != len(nomes):
        raise SystemExit("Nomes de braço repetidos")
    conjunto = pontuar.carregar_conjunto(CONJUNTO)
    pontuar.validar_conjunto(conjunto, agora_brasilia().date())
    perguntas = conjunto["perguntas"]
    if args.ids:
        escolhidos = set(args.ids.split(","))
        perguntas = [p for p in perguntas if p["id"] in escolhidos]

    args.saida_dir.mkdir(parents=True, exist_ok=True)
    bracos = []
    for nome, url, referencia in args.braco:
        saida = args.saida_dir / f"{nome}.jsonl"
        if saida.exists() and saida.stat().st_size > 0:
            # Duas rodadas no mesmo arquivo somariam os votos das duas.
            raise SystemExit(f"{saida} já tem execuções: use outra pasta para a rodada")
        bracos.append(Braco(nome, url, resolver_commit(referencia), saida))
    args.modelo_digest = digest_do_modelo(args.ollama, args.modelo)
    if args.modelo_digest is None:
        raise SystemExit(f"Modelo {args.modelo} não está no Ollama de {args.ollama}")
    print(f"Conjunto {conjunto['versao']} ({conjunto['_sha256'][:12]}), modelo {args.modelo} "
          f"({args.modelo_digest[:12]}), braços: "
          + ", ".join(f"{b.nome}={b.commit[:12]}" for b in bracos), flush=True)

    aquecer(args.ollama, args.modelo, args.embedding)
    for braco in bracos:
        for texto in PERGUNTAS_DE_AQUECIMENTO:
            perguntar(braco, args, senha, texto, {"aquecimento": True})
    for repeticao in range(1, args.repeticoes + 1):
        for braco in ordem_dos_bracos(bracos, repeticao):
            aquecer(args.ollama, args.modelo, args.embedding)
            for pergunta in perguntas:
                executar_pergunta(braco, args, senha, pergunta, repeticao)
    return 0


if __name__ == "__main__":
    sys.exit(main())
