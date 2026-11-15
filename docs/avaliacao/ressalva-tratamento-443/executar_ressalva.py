"""Roda o conjunto da #443 contra um ou mais braços (instâncias da API) e grava as respostas brutas.

Só biblioteca padrão. URL e credenciais só por variável de ambiente, nunca em argumento nem em arquivo:

    RESSALVA_<BRACO>_URL, RESSALVA_<BRACO>_EMAIL, RESSALVA_<BRACO>_SENHA   (BRACO em maiúsculas)

Cada braço é uma instância já no ar (a `main` e o head da correção, com o Haiku 5.5 pela API, a mesma
conta semeada e o mesmo seed). O executor intercala as execuções entre os braços (cada pergunta vai a
todos em seguida, com a ordem dos braços girando; a ordem das perguntas é sorteada por repetição, com
semente fixa) e refaz uma vez, ao fim, as que falharam por motivo técnico.

    python3 -I executar_ressalva.py --so-verificar --braco base --braco candidato
    python3 -I executar_ressalva.py --braco base --braco candidato \
        --commit base=<sha da main> --commit candidato=<sha do head da correção>
"""

import argparse
import datetime
import json
import os
import random
import re
import socket
import sys
import time
import urllib.error
import urllib.request
import zoneinfo
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ressalva_comum as comum  # noqa: E402

TIMEOUT_S = 330
MAX_ESPERAS = 5
FUSO = zoneinfo.ZoneInfo("America/Sao_Paulo")
SEMENTE = 443
RENOVAR_TOKEN_S = 12 * 60  # o access token dura 15 minutos


class CotaEsgotada(Exception):
    """429 LIMITE_USO_ASSISTENTE: a medição para, gravando o que já foi medido."""


def variavel_do_braco(braco, campo):
    return "RESSALVA_" + re.sub(r"[^A-Z0-9]", "_", braco.upper()) + "_" + campo


def nome_de_braco_valido(nome):
    return bool(re.fullmatch(r"[a-z][a-z0-9-]{0,30}", nome))


def plano(ids, bracos, repeticoes=comum.REPETICOES, semente=SEMENTE):
    """Ordem das execuções: [(repeticao, id, braco)]. Todo (id, braco) aparece uma vez por repetição."""
    saida = []
    for rep in range(1, repeticoes + 1):
        ordem = sorted(ids)
        random.Random(semente * 100 + rep).shuffle(ordem)
        for k, pergunta_id in enumerate(ordem):
            giro = (k + rep) % len(bracos)
            for braco in bracos[giro:] + bracos[:giro]:
                saida.append((rep, pergunta_id, braco))
    return saida


# --- HTTP ---------------------------------------------------------------------------------------

def requisitar(url, corpo, cabecalhos, metodo="POST"):
    dados = None if corpo is None else json.dumps(corpo).encode("utf-8")
    req = urllib.request.Request(url, data=dados, method=metodo, headers={
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
        return 0, {"errors": [{"code": "FALHA_" + type(erro).__name__}]}, {}


def codigo_erro(corpo):
    return ((corpo.get("errors") or [{}])[0]).get("code", "")


def entrar(base_url, email, senha):
    status, corpo, _ = requisitar(base_url + "/api/auth/login", {"email": email, "senha": senha}, {})
    if status != 200 or "accessToken" not in corpo:
        sys.exit(f"Login falhou: HTTP {status} {codigo_erro(corpo)}")  # nunca imprime a senha
    return corpo["accessToken"]


def perguntar(base_url, token, pergunta):
    status, corpo, cabecalhos = 0, {}, {}
    for _ in range(MAX_ESPERAS + 1):
        inicio = time.monotonic()
        status, corpo, cabecalhos = requisitar(base_url + "/api/assistente/chat",
                                               {"pergunta": pergunta},
                                               {"Authorization": "Bearer " + token})
        latencia_ms = round((time.monotonic() - inicio) * 1000)
        codigo = codigo_erro(corpo) if status >= 400 or status == 0 else ""
        if codigo == "LIMITE_USO_ASSISTENTE":
            raise CotaEsgotada()
        if status in (429, 503) and codigo in ("MUITAS_TENTATIVAS", "ASSISTENTE_OCUPADO"):
            time.sleep(int(cabecalhos.get("Retry-After", "5")))
            continue
        return {
            "status": status, "codigo": codigo, "latenciaMs": latencia_ms,
            "rota": corpo.get("rota"), "modoFallback": corpo.get("modoFallback"),
            "resposta": corpo.get("resposta"),
            "citacoes": [{"norma": c.get("norma"), "dispositivo": c.get("dispositivo")}
                         for c in (corpo.get("citacoes") or [])],
            "fontes": [f.get("funcao") for f in (corpo.get("fontesDados") or [])]}
    return {"status": status, "codigo": codigo_erro(corpo), "latenciaMs": 0, "rota": None,
            "resposta": None, "citacoes": [], "fontes": []}


# --- execução -----------------------------------------------------------------------------------

def ler_bracos(nomes):
    bracos = {}
    for nome in nomes:
        if not nome_de_braco_valido(nome):
            sys.exit(f"Nome de braço inválido: {nome!r} (minúsculas, dígitos e hífen).")
        faltando = [variavel_do_braco(nome, c) for c in ("URL", "EMAIL", "SENHA")
                    if not os.environ.get(variavel_do_braco(nome, c), "").strip()]
        if faltando:
            sys.exit("Defina as variáveis de ambiente: " + ", ".join(faltando))
        bracos[nome] = {c.lower(): os.environ[variavel_do_braco(nome, c)].strip()
                        for c in ("URL", "EMAIL", "SENHA")}
        bracos[nome]["url"] = bracos[nome]["url"].rstrip("/")
    return bracos


def ler_commits(valores, nomes):
    """`NOME=SHA` por braço, obrigatório: a decisão precisa saber o que estava no ar."""
    commits = {}
    for valor in valores or []:
        nome, separador, sha = valor.partition("=")
        if not separador or nome not in nomes or not re.fullmatch(r"[0-9a-f]{7,40}", sha):
            sys.exit(f"--commit inválido: {valor.split('=')[0]!r} (use NOME=SHA, com o nome de um braço e o SHA).")
        commits[nome] = sha
    faltando = [n for n in nomes if n not in commits]
    if faltando:
        sys.exit("Informe --commit NOME=SHA de cada braço; faltam: " + ", ".join(faltando))
    return commits


def rodar(args):
    conjunto = comum.carregar_conjunto()
    perguntas = {p["id"]: p for p in conjunto["perguntas"]}
    ids = [i for i in perguntas if not args.ids or i in args.ids]
    nomes = args.braco
    if len(nomes) < 2 or len(set(nomes)) != len(nomes):
        sys.exit("Informe pelo menos dois braços distintos (--braco base --braco candidato).")
    execucoes_planejadas = plano(ids, nomes)
    if args.so_verificar:
        print(f"Conjunto {conjunto['versao']} confere com o sha256. {len(ids)} perguntas x "
              f"{comum.REPETICOES} repetições x {len(nomes)} braços = {len(execucoes_planejadas)} "
              "execuções.")
        return
    commits = ler_commits(args.commit, nomes)
    bracos = ler_bracos(nomes)
    agora = datetime.datetime.now(FUSO).strftime("%Y-%m-%d-%H%M")
    pasta = Path(args.saida) / f"{agora}-ressalva-443"
    pasta.mkdir(parents=True, exist_ok=True)
    tokens = {n: [entrar(b["url"], b["email"], b["senha"]), time.monotonic()] for n, b in bracos.items()}

    def executar(rep, pergunta_id, nome):
        token = tokens[nome]
        if time.monotonic() - token[1] > RENOVAR_TOKEN_S:
            b = bracos[nome]
            tokens[nome] = token = [entrar(b["url"], b["email"], b["senha"]), time.monotonic()]
        r = perguntar(bracos[nome]["url"], token[0], perguntas[pergunta_id]["pergunta"])
        r.update({"id": pergunta_id, "braco": nome, "rep": rep})
        return r

    resultados, interrompida = [], None
    try:
        with open(pasta / "respostas-brutas.jsonl", "w", encoding="utf-8") as saida:
            for rep, pergunta_id, nome in execucoes_planejadas:
                r = executar(rep, pergunta_id, nome)
                resultados.append(r)
                saida.write(json.dumps(r, ensure_ascii=False) + "\n")
                saida.flush()
                print(f"rep {rep} {pergunta_id} {nome} HTTP {r['status']} rota={r['rota']}", flush=True)
    except CotaEsgotada:
        interrompida = "Cota semanal da Ceres esgotada (429 LIMITE_USO_ASSISTENTE)"
    except (KeyboardInterrupt, SystemExit) as erro:
        interrompida = f"Interrompida ({type(erro).__name__})"
    if not interrompida:
        interrompida = refazer_falhas(pasta, resultados, executar)
    manifesto = {
        "executadoEm": agora, "fuso": "America/Sao_Paulo", "issue": conjunto["issue"],
        "conjunto": {"arquivo": comum.CONJUNTO.name, "versao": conjunto["versao"],
                     "sha256": comum.sha256_arquivo(comum.CONJUNTO)},
        "versaoRegra": comum.VERSAO_REGRA, "repeticoes": comum.REPETICOES, "semente": SEMENTE,
        "recorteDeIds": sorted(args.ids) if args.ids else None,
        "bracos": nomes, "commits": commits, "execucoes": len(resultados),
        "planejadas": len(execucoes_planejadas), "interrompida": interrompida}
    (pasta / "execucao.json").write_text(json.dumps(manifesto, ensure_ascii=False, indent=2) + "\n",
                                         encoding="utf-8")
    print(f"Gravado em {pasta}")
    if interrompida:
        sys.exit(interrompida)


def refazer_falhas(pasta, resultados, executar):
    """Uma segunda tentativa das execuções com falha técnica; reescreve o arquivo com o que valeu."""
    try:
        for i, r in enumerate(resultados):
            if comum.e_falha_tecnica(r):
                resultados[i] = executar(r["rep"], r["id"], r["braco"])
    except CotaEsgotada:
        return "Cota semanal da Ceres esgotada (429 LIMITE_USO_ASSISTENTE)"
    except (KeyboardInterrupt, SystemExit) as erro:
        return f"Interrompida ({type(erro).__name__})"
    finally:
        with open(pasta / "respostas-brutas.jsonl", "w", encoding="utf-8") as saida:
            for r in resultados:
                saida.write(json.dumps(r, ensure_ascii=False) + "\n")
    return None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--braco", action="append", required=True, help="nome do braço (repetível)")
    ap.add_argument("--commit", action="append", help="NOME=SHA do que está no ar em cada braço (obrigatório)")
    ap.add_argument("--ids", type=lambda s: {i for i in s.split(",") if i}, default=set())
    ap.add_argument("--saida", default=str(comum.DIR / "rodadas"))
    ap.add_argument("--so-verificar", action="store_true")
    rodar(ap.parse_args(argv))


if __name__ == "__main__":
    main()
