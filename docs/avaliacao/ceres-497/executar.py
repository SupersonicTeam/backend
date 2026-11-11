#!/usr/bin/env python3
"""Roda o conjunto da #497 contra uma Ceres local e grava um JSONL para o pontuar.py.

Só biblioteca padrão. Cada pergunta é feita `--repeticoes` vezes (repetição por repetição), pela
conta indicada. Depois de cada chamada o executor lê no banco a linha de `assistente_interacao`
que ela gerou (rota, desfecho, camada da classificação, latência; a auditoria não guarda texto),
pelo `docker exec` no container do PostgreSQL. Se o número de linhas não subir exatamente 1, a
auditoria da chamada fica vazia e a linha leva `auditoria_ausente: true`.

Cada linha traz o commit (da pasta git, que precisa estar limpa), o modelo informado, o horário
(ISO, Brasília) e a resposta da API. A senha vem de CERES_SENHA e nunca vai para arquivo nem para
a saída. Falha técnica é repetida até `--tentativas` vezes; cota esgotada interrompe a rodada.
Entre duas chamadas ao chat há pelo menos `--intervalo` segundos: o chat aceita 20 perguntas por
minuto por usuário, e o Haiku responde em 1 a 2 s. Antes de tudo, o bge-m3 é aquecido no Ollama
(a classificação por embedding depende dele). O banco é só desta rodada: com outra escrita na
auditoria, a contagem sobe mais de 1 e a chamada fica marcada sem auditoria (falha técnica).

  CERES_SENHA=... python executar.py --url http://localhost:8497 --pasta <worktree> \\
      --banco agroconia_497 --modelo anthropic/claude-haiku-5-5 \\
      --email carlos.rossi@example.com --saida rodada.jsonl
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

AQUI = Path(__file__).resolve().parent
CONJUNTO = AQUI.parent / "treino" / "perguntas-ceres-497.json"
BRASILIA = dt.timezone(dt.timedelta(hours=-3))
PERGUNTAS_DE_AQUECIMENTO = (
    "Quanto eu vendi de feijão em março de 2026?",
    "O que é o Imposto Seletivo?",
)
CAMPOS_AUDITORIA = ("rota", "desfecho", "camada_classificacao", "desfecho_dados",
                    "desfecho_legislacao", "latencia_ms", "modelo")

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


def resolver_commit(pasta: str) -> str:
    """SHA de uma pasta git limpa."""
    try:
        sujo = subprocess.run(["git", "-C", pasta, "status", "--porcelain"],
                              capture_output=True, text=True, check=True).stdout.strip()
        if sujo:
            raise SystemExit(f"{pasta} tem mudanças fora de commit: o commit não descreve a rodada")
        return subprocess.run(["git", "-C", pasta, "rev-parse", "HEAD"],
                              capture_output=True, text=True, check=True).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError) as erro:
        raise SystemExit(f"{pasta} não é um repositório git: {erro}") from None


class Auditoria:
    """Lê assistente_interacao pelo psql do container (só leitura)."""

    def __init__(self, container: str, banco: str, usuario: str, executar=subprocess.run):
        self.base = ["docker", "exec", container, "psql", "-U", usuario, "-d", banco,
                     "-At", "-F", "\t", "-c"]
        self.executar = executar

    def _consulta(self, sql: str) -> str:
        return self.executar(self.base + [sql], capture_output=True, text=True,
                             check=True).stdout.strip()

    def contagem(self) -> int:
        return int(self._consulta("select count(*) from assistente_interacao") or 0)

    def ultima(self) -> dict:
        colunas = ", ".join(f"coalesce({c}::text, '')" for c in CAMPOS_AUDITORIA)
        linha = self._consulta(
            f"select {colunas} from assistente_interacao order by criado_em desc limit 1")
        valores = linha.split("\t") if linha else []
        if len(valores) != len(CAMPOS_AUDITORIA):
            return {}
        return {c: (v or None) for c, v in zip(CAMPOS_AUDITORIA, valores)}


def aquecer_embedding(ollama: str, modelo: str) -> None:
    """Carrega o modelo de embedding no Ollama com keep_alive longo."""
    requisitar("POST", ollama + "/api/embed",
               {"model": modelo, "input": "aquecimento", "keep_alive": "2h"}, timeout=600)


class Ritmo:
    """Espera até completar `intervalo` segundos desde a chamada anterior."""

    def __init__(self, intervalo: float, relogio=None, dormir=None):
        self.intervalo = intervalo
        # Lidos na hora da chamada: um mock de time.sleep posto depois também vale.
        self.relogio = relogio or (lambda: time.monotonic())
        self.dormir = dormir or (lambda segundos: time.sleep(segundos))
        self.ultima: float | None = None

    def esperar(self) -> None:
        if self.ultima is not None:
            falta = self.intervalo - (self.relogio() - self.ultima)
            if falta > 0:
                self.dormir(falta)
        self.ultima = self.relogio()


def agora_brasilia() -> dt.datetime:
    return dt.datetime.now(BRASILIA)


def segundos_de_espera(valor, padrao: float = 30) -> float:
    try:
        return max(0.0, float(valor))
    except (TypeError, ValueError):
        return padrao


class Sessao:
    def __init__(self, url: str, email: str, senha: str):
        self.url, self.email, self.senha = url.rstrip("/"), email, senha
        self.token: str | None = None
        self.token_em = 0.0

    def token_valido(self) -> str:
        if self.token is None or time.monotonic() - self.token_em > 600:
            status, corpo, _ = requisitar("POST", self.url + "/api/auth/login",
                                          {"email": self.email, "senha": self.senha})
            if status != 200 or not isinstance(corpo, dict) or "accessToken" not in corpo:
                raise SystemExit(f"Login falhou em {self.url}: HTTP {status}")
            self.token, self.token_em = corpo["accessToken"], time.monotonic()
        return self.token


def perguntar(sessao: Sessao, auditoria: Auditoria, args, texto: str, base: dict,
              saida: Path) -> dict:
    token = sessao.token_valido()
    args.ritmo.esperar()
    antes = auditoria.contagem()
    momento = agora_brasilia()
    inicio = time.monotonic()
    status, corpo, cabecalhos = requisitar("POST", sessao.url + "/api/assistente/chat",
                                           {"pergunta": texto}, token, args.timeout)
    segundos = round(time.monotonic() - inicio, 1)
    if status == 401:
        sessao.token = None
    depois = auditoria.contagem()
    registro = dict(base)
    registro.update({
        "commit": args.commit, "modelo": args.modelo,
        "horario": momento.isoformat(timespec="seconds"), "http_status": status,
        "corpo": corpo, "segundos": segundos,
    })
    if depois == antes + 1:
        registro["auditoria"] = auditoria.ultima()
    else:
        registro["auditoria"] = {}
        registro["auditoria_ausente"] = True
    registro["falha_tecnica"] = pontuar.falha_tecnica(registro)
    with saida.open("a", encoding="utf-8", newline="\n") as arquivo:
        arquivo.write(json.dumps(registro, ensure_ascii=False) + "\n")
    rota = corpo.get("rota") if isinstance(corpo, dict) else None
    print(f"{base.get('id', 'aquecimento')} r{base.get('repeticao', 0)} t{base.get('tentativa', 1)}"
          f" HTTP {status} {rota} {registro['auditoria'].get('camada_classificacao')} {segundos}s"
          + (" TECNICA" if registro["falha_tecnica"] else "")
          + (" SEM_AUDITORIA" if registro.get("auditoria_ausente") else ""), flush=True)
    registro["_retry_after"] = cabecalhos.get("Retry-After")
    return registro


def executar_pergunta(sessao, auditoria, args, pergunta: dict, repeticao: int, saida) -> None:
    sem_conexao = 0
    for tentativa in range(1, args.tentativas + 1):
        registro = perguntar(sessao, auditoria, args, pergunta["pergunta"], {
            "id": pergunta["id"], "repeticao": repeticao, "tentativa": tentativa,
            "aquecimento": False}, saida)
        if not registro["falha_tecnica"]:
            return
        corpo = registro["corpo"] if isinstance(registro["corpo"], dict) else {}
        codigos = {e.get("code") for e in corpo.get("errors") or [] if isinstance(e, dict)}
        if "LIMITE_USO_ASSISTENTE" in codigos:
            raise SystemExit("Cota semanal da Ceres esgotada: rodada interrompida")
        sem_conexao += registro["http_status"] == 0
        if sem_conexao == args.tentativas:
            raise SystemExit(f"Backend fora do ar em {sessao.url}: rodada interrompida")
        if registro["http_status"] == 429 or "ASSISTENTE_OCUPADO" in codigos:
            time.sleep(segundos_de_espera(registro.get("_retry_after")))
        else:
            time.sleep(5)


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analisador.add_argument("--url", required=True)
    analisador.add_argument("--pasta", required=True, help="worktree git limpo da aplicação")
    analisador.add_argument("--banco", required=True, help="banco da aplicação, para a auditoria")
    analisador.add_argument("--modelo", required=True, help="ex.: anthropic/claude-haiku-5-5")
    analisador.add_argument("--email", required=True)
    analisador.add_argument("--saida", type=Path, required=True)
    analisador.add_argument("--container", default="agrocontabil-postgres")
    analisador.add_argument("--usuario-banco", default="agrocontabil")
    analisador.add_argument("--repeticoes", type=int, default=3)
    analisador.add_argument("--tentativas", type=int, default=3)
    analisador.add_argument("--ids", help="só estes ids, separados por vírgula (ensaio)")
    analisador.add_argument("--timeout", type=float, default=330)
    analisador.add_argument("--intervalo", type=float, default=3.5,
                            help="segundos mínimos entre duas chamadas ao chat")
    analisador.add_argument("--ollama", default="http://localhost:11434")
    analisador.add_argument("--embedding", default="bge-m3")
    args = analisador.parse_args(argv)

    senha = os.environ.get("CERES_SENHA")
    if not senha:
        raise SystemExit("Defina CERES_SENHA com a senha da conta (nunca em arquivo)")
    conjunto = pontuar.carregar_conjunto(CONJUNTO)
    pontuar.validar_conjunto(conjunto)
    perguntas = conjunto["perguntas"]
    if args.ids:
        escolhidos = set(args.ids.split(","))
        desconhecidos = escolhidos - {p["id"] for p in perguntas}
        if desconhecidos:
            raise SystemExit("ids fora do conjunto: " + ", ".join(sorted(desconhecidos)))
        perguntas = [p for p in perguntas if p["id"] in escolhidos]
    if args.saida.exists() and args.saida.stat().st_size > 0:
        raise SystemExit(f"{args.saida} já tem execuções: use outro arquivo para a rodada")
    args.commit = resolver_commit(args.pasta)
    args.ritmo = Ritmo(args.intervalo)
    sessao = Sessao(args.url, args.email, senha)
    auditoria = Auditoria(args.container, args.banco, args.usuario_banco)
    print(f"Conjunto {conjunto['versao']} ({conjunto['_sha256'][:12]}), commit "
          f"{args.commit[:12]}, modelo {args.modelo}", flush=True)

    aquecer_embedding(args.ollama, args.embedding)
    for texto in PERGUNTAS_DE_AQUECIMENTO:
        perguntar(sessao, auditoria, args, texto, {"aquecimento": True}, args.saida)
    for repeticao in range(1, args.repeticoes + 1):
        for pergunta in perguntas:
            executar_pergunta(sessao, auditoria, args, pergunta, repeticao, args.saida)
    return 0


if __name__ == "__main__":
    sys.exit(main())
