#!/usr/bin/env python3
"""Executor da medição de fidelidade da rota de lei da Ceres (#269).

Roda as 36 perguntas do conjunto escolhido (`--conjunto v1|v2|v3`; padrão `v1`,
`perguntas-fidelidade-lei.json`; o `v2`, `perguntas-fidelidade-lei-v2.json`, é o da segunda
medição e o `v3`, `perguntas-fidelidade-lei-v3.json`, o da terceira) em dois ou mais braços, no MESMO jar (o head
do PR da correção): `desligada` (flag da correção desligada, igual à `main`, o braço de referência) e
`ligada`, mais os braços extras que a correção tiver (`--braco`). Sobe uma instância por braço em
portas diferentes, contra um banco novo e só dele (com a base de lei copiada do banco de origem), e
intercala as execuções (cada pergunta vai a todos os braços em seguida, com a ordem dos braços
girando). Grava as respostas brutas e, de cada uma, a linha de auditoria (`assistente_interacao`):
desfecho, tokens e latência do servidor. Não avalia nada: a leitura dos juízes e a decisão são do
`leitura_fidelidade.py` e do `decidir_fidelidade.py`.

Nada de segredo em argumento nem em arquivo. Por ambiente:

    ANTHROPIC_API_KEY  chave da API (obrigatória; nunca impressa nem gravada)
    DB_PASSWORD        senha do Postgres (obrigatória; a do container de desenvolvimento)

Uso (da raiz do repositório; o jar é o do head do PR da correção, `./mvnw -DskipTests package`):

    python3 -I docs/avaliacao/fidelidade-lei/executar_fidelidade.py \\
        --jar target/backend-tcc-0.0.1-SNAPSHOT.jar \\
        --env-ligada NOME_DA_FLAG=true [--env-desligada NOME_DA_FLAG=false] \\
        --commit <sha do head do PR>

`--env-desligada`/`--env-ligada` repetem (CHAVE=valor). Um terceiro braço (ou mais) vai em
`--braco NOME:CHAVE=valor,CHAVE=valor` (repetível, nome em minúsculas, dígitos, `_` ou `+`, como
`camada1+juiz`; cada braço é comparado ao `desligada`; cada JVM
gasta memória, e todos os braços usam a mesma conta semeada). O que não é a flag é igual em todos os
braços; as variáveis de identidade da medição (modelo, perfil, banco, porta, chave) são do executor e a
tentativa de sobrescrevê-las é recusada. `--so-verificar` confere o conjunto, os argumentos e mostra
o plano sem subir nada.
"""

import argparse
import datetime
import json
import os
import random
import re
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zoneinfo
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fidelidade_comum as comum  # noqa: E402

FUSO = zoneinfo.ZoneInfo("America/Sao_Paulo")
MODELO = "claude-haiku-5-5"
CONTA = "joao.produtor@example.com"  # uma só para todos os braços: a rodada é sequencial
PORTA_BASE = 18110
TIMEOUT_PERGUNTA_S = 330   # acima do timeout da pergunta no servidor (5 min)
INTERVALO_MINIMO_S = 3.5   # o chat tem limite de 20 por minuto por identidade (#150)
MAX_ESPERAS = 5
PRONTO_EM_S = 360

#: Variáveis que definem a identidade da medição: o executor as põe e o braço não as sobrescreve.
PROTEGIDAS = ("SPRING_PROFILES_ACTIVE", "DB_", "SERVER_PORT", "SEED_SENHA", "ANTHROPIC_API_KEY",
              "AI_CHAT_PROVIDER", "AI_ANTHROPIC_MODEL", "AI_ANTHROPIC_THINKING", "AI_OLLAMA",
              "AI_COTA", "JWT_SECRET", "CRIPTO_CHAVE")
PADRAO_SEGREDO = re.compile(r"(KEY|SENHA|SECRET|TOKEN|PASSWORD|CHAVE)", re.I)


def log(mensagem: str) -> None:
    print(f"[{datetime.datetime.now(FUSO):%H:%M:%S}] {mensagem}", flush=True)


# --- Argumentos e ambiente --------------------------------------------------------------------

def analisar_env(itens: list, nome: str) -> dict:
    env = {}
    for item in itens or []:
        chave, separador, valor = item.partition("=")
        if not separador or not re.fullmatch(r"[A-Z][A-Z0-9_]*", chave):
            raise ValueError(f"--env-{nome}: use CHAVE=valor com chave em maiúsculas ({item!r})")
        if any(chave.startswith(p) for p in PROTEGIDAS):
            raise ValueError(f"--env-{nome}: {chave} é do executor e não pode ser sobrescrita")
        env[chave] = valor
    return env


BANCOS_INTOCAVEIS = ("postgres", "template0", "template1", "agrocontabil")


def validar_banco(banco: str, origem: str) -> None:
    """O banco da rodada é apagado e recriado: nunca o de origem, o de desenvolvimento nem o do
    sistema, e só um nome simples (ele entra em comando SQL)."""
    if not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", banco):
        raise ValueError(f"--banco: use só minúsculas, dígitos e _ ({banco!r})")
    if banco == origem or banco in BANCOS_INTOCAVEIS:
        raise ValueError(f"--banco {banco!r} é o banco de origem, o de desenvolvimento ou o do "
                         "sistema: a rodada o apagaria; use um banco só dela (padrão fidelidade_lei)")


def analisar_braco(texto: str) -> tuple:
    """`NOME:CHAVE=valor,CHAVE=valor` -> (nome, env). O valor não pode ter vírgula (ela separa os
    pares); para um valor com vírgula use o `--env-ligada`, que não separa."""
    nome, separador, resto = texto.partition(":")
    if not separador or not re.fullmatch(r"[a-z][a-z0-9_+]{0,23}", nome):
        raise ValueError(f"--braco: use NOME:CHAVE=valor com nome em minúsculas ({texto!r})")
    itens = [i for i in resto.split(",") if i]
    return nome, analisar_env(itens, nome)


def montar_bracos(env_desligada: list, env_ligada: list, extras: list) -> dict:
    """{nome: env}, o `desligada` primeiro. `--env-ligada` é o atalho do braço `ligada`."""
    bracos = {comum.BASE: analisar_env(env_desligada, comum.BASE)}
    if env_ligada:
        bracos["ligada"] = analisar_env(env_ligada, "ligada")
    for texto in extras or []:
        nome, env = analisar_braco(texto)
        if nome in bracos:
            raise ValueError(f"o braço '{nome}' aparece mais de uma vez")
        bracos[nome] = env
    validar_bracos(bracos)
    return bracos


def validar_bracos(bracos: dict) -> None:
    """Ao menos um braço além do `desligada`, e nenhum com o ambiente igual ao de outro."""
    if comum.BASE not in bracos or len(bracos) < 2:
        raise ValueError("informe ao menos um braço além do desligada (--env-ligada ou --braco): "
                         "sem ele não há o que comparar")
    nomes = list(bracos)
    for i, a in enumerate(nomes):
        for b in nomes[i + 1:]:
            if bracos[a] == bracos[b]:
                raise ValueError(f"os braços '{a}' e '{b}' têm o mesmo ambiente: a flag precisa "
                                 "diferenciar")


def mostrar_env(env: dict) -> dict:
    """Ambiente do braço para o manifesto, com o que parece segredo escondido."""
    return {k: ("***" if PADRAO_SEGREDO.search(k) else v) for k, v in env.items()}


def plano_de_execucao(ids: list, repeticoes: int, semente: int, nomes=comum.BRACOS) -> list:
    """[(repeticao, id, [braco, ...])]: ordem das perguntas sorteada por repetição e a ordem dos
    braços girando de pergunta em pergunta (com dois, alternando), para a hora do dia e a latência da
    API pesarem igual em todos."""
    nomes = list(nomes)
    plano = []
    for rep in range(1, repeticoes + 1):
        ordem = list(ids)
        random.Random(semente * 1000 + rep).shuffle(ordem)
        for i, pergunta in enumerate(ordem):
            giro = (i + rep) % len(nomes)
            plano.append((rep, pergunta, nomes[giro:] + nomes[:giro]))
    return plano


def ambiente_da_jvm(braco: str, porta: int, banco: str, senha_seed: str, env_do_braco: dict,
                    ollama_url: str) -> dict:
    """Sem nenhum AI_* nem chave herdados do shell; o do braço só depois do que é fixo."""
    env = {k: v for k, v in os.environ.items()
           if not k.startswith("AI_") and k not in ("ANTHROPIC_API_KEY", "SEED_SENHA")}
    env.update({
        "SPRING_PROFILES_ACTIVE": "dev",
        "DB_NAME": banco,
        "SERVER_PORT": str(porta),
        "SEED_SENHA": senha_seed,
        "AI_CHAT_PROVIDER": "anthropic",
        "AI_ANTHROPIC_MODEL": MODELO,
        "AI_ANTHROPIC_THINKING": "disabled",
        "ANTHROPIC_API_KEY": os.environ.get("ANTHROPIC_API_KEY", ""),
        "AI_OLLAMA_BASE_URL": ollama_url,
        "AI_OLLAMA_PULL_STRATEGY": "never",
        # A cota semanal protege o produtor, não é o que se mede: sem ela a rodada não para.
        "AI_COTA_CREDITOS_PRODUTOR": "1000000000",
    })
    env.update(env_do_braco)
    return env


# --- HTTP -------------------------------------------------------------------------------------

def requisitar(url: str, corpo: dict, cabecalhos: dict, metodo: str = "POST"):
    dados = json.dumps(corpo).encode("utf-8") if corpo is not None else None
    req = urllib.request.Request(url, data=dados, method=metodo, headers={
        "Content-Type": "application/json", "Accept": "application/json", **cabecalhos})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_PERGUNTA_S) as resp:
            return resp.status, json.loads(resp.read() or b"{}"), resp.headers
    except urllib.error.HTTPError as erro:
        try:
            corpo_erro = json.loads(erro.read() or b"{}")
        except ValueError:
            corpo_erro = {}
        return erro.code, corpo_erro, erro.headers
    except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, ValueError) as erro:
        return 0, {"errors": [{"code": "FALHA_" + type(erro).__name__}]}, {}


def codigo_erro(corpo: dict) -> str:
    erros = corpo.get("errors") or [{}]
    return erros[0].get("code", "")


class CotaEsgotada(Exception):
    """429 LIMITE_USO_ASSISTENTE: a rodada é inválida (regra, seção 2)."""


# --- Banco e instâncias ------------------------------------------------------------------------

class Ambiente:
    """O banco novo da rodada e uma instância da aplicação por braço."""

    def __init__(self, args, senha_seed: str, saida: Path):
        self.args = args
        self.senha_seed = senha_seed
        self.saida = saida
        self.psql_base = args.psql.split()
        self.procs = {}
        self.tokens = {}
        self.ids_produtor = {}
        self.inicio_token = {}
        self.auditorias_vistas = set()

    def psql(self, banco: str, sql: str, saida: bool = True) -> str:
        proc = subprocess.run(self.psql_base + ["-d", banco, "-At", "-c", sql],
                              capture_output=True, text=True, encoding="utf-8")
        if proc.returncode != 0:
            raise RuntimeError(f"psql falhou em {banco}: {proc.stderr.strip()[:300]}")
        return proc.stdout.strip()

    def preparar_banco(self) -> None:
        banco = self.args.banco
        log(f"banco {banco}: recriado (a rodada nunca toca no banco de desenvolvimento)")
        # FORCE: uma rodada anterior que caiu pode ter deixado conexões abertas (PostgreSQL 13+).
        self.psql("postgres", f'DROP DATABASE IF EXISTS "{banco}" WITH (FORCE)')
        self.psql("postgres", f'CREATE DATABASE "{banco}"')

    def copiar_lei(self) -> None:
        origem, destino = self.args.banco_origem, self.args.banco
        total = self.psql(origem, "select count(*) from legislacao_trecho")
        exportar = self.psql_base + ["-d", origem, "-c",
                                     "\\copy (select id, content, metadata, embedding from "
                                     "legislacao_trecho) to stdout"]
        importar = self.psql_base + ["-d", destino, "-c",
                                     "\\copy legislacao_trecho(id, content, metadata, embedding) "
                                     "from stdin"]
        log(f"copiando a base de lei ({total} trechos) de {origem} para {destino}")
        de = subprocess.Popen(exportar, stdout=subprocess.PIPE)
        para = subprocess.Popen(importar, stdin=de.stdout, stdout=subprocess.DEVNULL)
        de.stdout.close()
        if para.wait() != 0 or de.wait() != 0:
            raise RuntimeError("a cópia da base de lei falhou")
        copiados = self.psql(destino, "select count(*) from legislacao_trecho")
        if copiados != total:
            raise RuntimeError(f"cópia incompleta da base de lei: {copiados} de {total}")

    def subir(self, braco: str, porta: int, env_do_braco: dict) -> None:
        env = ambiente_da_jvm(braco, porta, self.args.banco, self.senha_seed, env_do_braco,
                              self.args.ollama_url)
        logs = self.saida / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        arquivo = open(logs / f"{braco}.log", "w", encoding="utf-8")
        cmd = ["java", "-jar", str(self.args.jar), "--spring.docker.compose.enabled=false"]
        log(f"subindo a instância '{braco}' na porta {porta}")
        self.procs[braco] = (subprocess.Popen(cmd, env=env, stdout=arquivo, stderr=subprocess.STDOUT),
                             arquivo)
        self.esperar_pronta(braco, porta)

    def esperar_pronta(self, braco: str, porta: int) -> None:
        limite = time.monotonic() + PRONTO_EM_S
        while time.monotonic() < limite:
            if self.procs[braco][0].poll() is not None:
                raise RuntimeError(f"a instância '{braco}' terminou na subida: ver logs/{braco}.log")
            status, _, _ = requisitar(f"http://127.0.0.1:{porta}/api/v1/health", None, {}, "GET")
            if status == 200:
                return
            time.sleep(3)
        raise RuntimeError(f"a instância '{braco}' não ficou pronta em {PRONTO_EM_S} s")

    def entrar(self, braco: str, porta: int) -> None:
        status, corpo, _ = requisitar(f"http://127.0.0.1:{porta}/api/auth/login",
                                      {"email": CONTA, "senha": self.senha_seed}, {})
        if status != 200 or "accessToken" not in corpo:
            # Nunca imprime a senha nem o corpo da resposta.
            raise RuntimeError(f"login do braço '{braco}' falhou: HTTP {status} {codigo_erro(corpo)}")
        self.tokens[braco] = corpo["accessToken"]
        self.ids_produtor[braco] = corpo["usuarioId"]
        self.inicio_token[braco] = time.monotonic()

    def token(self, braco: str, porta: int) -> str:
        if time.monotonic() - self.inicio_token[braco] > 12 * 60:  # access token de 15 min (#206)
            self.entrar(braco, porta)
        return self.tokens[braco]

    def auditoria(self, braco: str, desde: datetime.datetime):
        """Linha de auditoria mais recente do produtor depois de `desde` que ainda não foi vista
        (sem o texto da pergunta nem o da resposta: a tabela nunca os guarda, #144). A rodada é
        sequencial e todos os braços usam a mesma conta; a linha vista não vale duas vezes."""
        sql = ("select row_to_json(a) from assistente_interacao a "
               f"where a.produtor_id = '{self.ids_produtor[braco]}' "
               f"and a.criado_em >= '{desde.astimezone(datetime.timezone.utc).isoformat()}' "
               "order by a.criado_em desc limit 1")
        for _ in range(10):
            saida = self.psql(self.args.banco, sql)
            if saida:
                linha = json.loads(saida)
                if linha.get("id") not in self.auditorias_vistas:
                    self.auditorias_vistas.add(linha.get("id"))
                    return linha
            time.sleep(0.5)
        return None

    def derrubar(self, segredos: list) -> None:
        for braco, (proc, arquivo) in self.procs.items():
            if proc.poll() is None:
                encerrar(proc)
                try:
                    proc.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    proc.kill()
            arquivo.close()
        for caminho in (self.saida / "logs").glob("*.log"):
            texto = caminho.read_text(encoding="utf-8", errors="replace")
            for segredo in segredos:
                if segredo and len(segredo) >= 8:  # um valor curto trocaria letras soltas do log
                    texto = texto.replace(segredo, "***")
            caminho.write_text(texto, encoding="utf-8")


def encerrar(proc) -> None:
    """Encerra a JVM e o que ela gerou. No Windows o `java` pode ser um atalho que lança o processo
    de verdade: só `taskkill /T` derruba a árvore inteira (o smoke deixou duas JVMs vivas com o
    `terminate` simples)."""
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
    else:
        proc.terminate()


# --- Uma execução ------------------------------------------------------------------------------

def perguntar(amb: Ambiente, braco: str, porta: int, pergunta: dict, repeticao: int,
              tentativa: int) -> dict:
    desde = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=0.5)
    for _ in range(MAX_ESPERAS + 1):
        inicio = time.monotonic()
        status, corpo, cabecalhos = requisitar(
            f"http://127.0.0.1:{porta}/api/assistente/chat", {"pergunta": pergunta["pergunta"]},
            {"Authorization": "Bearer " + amb.token(braco, porta)})
        latencia = round((time.monotonic() - inicio) * 1000)
        codigo = codigo_erro(corpo) if status >= 400 or status == 0 else ""
        if codigo == "LIMITE_USO_ASSISTENTE":
            raise CotaEsgotada()
        if status in (429, 503) and codigo in ("MUITAS_TENTATIVAS", "ASSISTENTE_OCUPADO"):
            time.sleep(int(cabecalhos.get("Retry-After", "5")))
            continue
        break
    registro = {
        "id": pergunta["id"], "braco": braco, "repeticao": repeticao, "tentativa": tentativa,
        "iniciadoEm": desde.isoformat(), "status": status, "codigo": codigo,
        "latenciaMs": latencia, "rota": corpo.get("rota"),
        "modoFallback": bool(corpo.get("modoFallback")), "resposta": corpo.get("resposta"),
        "citacoes": [{"norma": c.get("norma"), "dispositivo": c.get("dispositivo"),
                      "trecho": c.get("trecho")} for c in (corpo.get("citacoes") or [])],
        "auditoria": None,
    }
    if status == 200:
        registro["auditoria"] = amb.auditoria(braco, desde)
    return registro


def executar(args, conjunto: dict, saida: Path) -> int:
    perguntas = comum.perguntas_por_id(conjunto)
    ids = [i for i in perguntas if not args.perguntas or i in args.perguntas]
    senha_seed = secrets.token_urlsafe(24)
    chave_api = os.environ.get("ANTHROPIC_API_KEY", "")
    amb = Ambiente(args, senha_seed, saida)
    brutas = saida / "respostas-brutas.jsonl"
    nomes = list(args.bracos)
    portas = {nome: args.porta + i for i, nome in enumerate(nomes)}
    plano = plano_de_execucao(ids, args.repeticoes, args.semente, nomes)
    interrompida = None
    registros = []
    ultimo_pedido = {b: 0.0 for b in nomes}

    def uma(braco, p, rep, tentativa):
        espera = INTERVALO_MINIMO_S - (time.monotonic() - ultimo_pedido[braco])
        if espera > 0:
            time.sleep(espera)
        r = perguntar(amb, braco, portas[braco], perguntas[p], rep, tentativa)
        ultimo_pedido[braco] = time.monotonic()
        classe, motivo = comum.classificar_execucao(r)
        r["classe"], r["motivo"] = classe, motivo
        comum.anexar_jsonl(brutas, r)
        registros.append(r)
        log(f"{braco:9} rep {rep} {p} HTTP {r['status']} {r['rota']} {r['latenciaMs']} ms "
            f"-> {classe}")
        return r

    try:
        amb.preparar_banco()
        amb.subir(comum.BASE, portas[comum.BASE], args.bracos[comum.BASE])
        amb.copiar_lei()
        for braco in nomes[1:]:
            amb.subir(braco, portas[braco], args.bracos[braco])
        for braco in nomes:
            amb.entrar(braco, portas[braco])
        for rep, p, bracos in plano:
            for braco in bracos:
                uma(braco, p, rep, 1)
        # Refaz uma vez só as execuções com falha técnica (regra, seção 2, item 1).
        falhas = [(r["repeticao"], r["id"], r["braco"]) for r in registros if r["classe"] == "falha"]
        if falhas:
            log(f"refazendo {len(falhas)} execução(ões) com falha técnica")
        for rep, p, braco in falhas:
            uma(braco, p, rep, 2)
    except CotaEsgotada:
        interrompida = "429 LIMITE_USO_ASSISTENTE: rodada inválida (regra, seção 2)"
    except (KeyboardInterrupt, SystemExit) as erro:
        interrompida = f"interrompida ({type(erro).__name__})"
    except RuntimeError as erro:
        interrompida = f"erro do executor: {erro}"
    finally:
        amb.derrubar([chave_api, senha_seed, os.environ.get("DB_PASSWORD", "")])
        gravar_manifesto(args, conjunto, saida, plano, registros, interrompida)
    if interrompida:
        log(interrompida)
        return 1
    return 0


def nome_do_conjunto(arquivo: Path) -> str:
    """O nome, se o conjunto está na pasta do executor; senão o caminho completo, para a leitura e a
    decisão acharem o mesmo arquivo."""
    arquivo = Path(arquivo).resolve()
    return arquivo.name if arquivo.parent == comum.DIR else str(arquivo)


def gravar_manifesto(args, conjunto, saida: Path, plano: list, registros: list, interrompida) -> None:
    ultimas = {}
    for r in registros:  # vale a última tentativa de cada (pergunta, braço, repetição)
        ultimas[(r["id"], r["braco"], r["repeticao"])] = r
    contagem = {b: {} for b in args.bracos}
    for (_, braco, _), r in ultimas.items():
        contagem[braco][r["classe"]] = contagem[braco].get(r["classe"], 0) + 1
    manifesto = {
        "regra": {"versao": comum.VERSAO_REGRA_ATUAL, "parametros": comum.REGRA},
        "conjunto": {"versao": conjunto["versao"],
                     "arquivo": nome_do_conjunto(args.arquivo_conjunto),
                     "sha256": comum.sha256_arquivo(args.arquivo_conjunto),
                     "perguntas": len({r["id"] for r in registros}) or None},
        "commit": args.commit, "jarSha256": comum.sha256_arquivo(args.jar) if args.jar and args.jar.exists()
        else None,
        "modelo": MODELO, "repeticoes": args.repeticoes, "semente": args.semente,
        "bancoDaRodada": args.banco, "execucoesPlanejadas": len(plano) * len(args.bracos),
        "bracos": list(args.bracos),
        "ambientes": {nome: mostrar_env(env) for nome, env in args.bracos.items()},
        "iniciadoEm": args.iniciado_em, "terminadoEm": datetime.datetime.now(FUSO).isoformat(),
        "contagemPorBraco": contagem, "interrompida": interrompida,
        "observacao": "A URL, as contas e as senhas da rodada nunca entram neste arquivo.",
    }
    (saida / "execucao.json").write_text(json.dumps(manifesto, ensure_ascii=False, indent=2),
                                         encoding="utf-8")
    log(f"manifesto gravado em {saida / 'execucao.json'}")


def montar_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    p.add_argument("--jar", type=Path, help="jar do head do PR da correção")
    p.add_argument("--commit", default=None, help="sha do head do PR (registrado no manifesto)")
    p.add_argument("--env-desligada", action="append", default=[], metavar="CHAVE=valor")
    p.add_argument("--env-ligada", action="append", default=[], metavar="CHAVE=valor",
                   help="atalho do braço 'ligada'")
    p.add_argument("--braco", action="append", default=[], metavar="NOME:CHAVE=valor,CHAVE=valor",
                   help="braço extra, comparado ao desligada (repetível)")
    p.add_argument("--repeticoes", type=int, default=3)
    p.add_argument("--semente", type=int, default=20261112)
    p.add_argument("--conjunto", default="v1", metavar="v1|v2|v3|ARQUIVO",
                   help="conjunto de perguntas (padrão: v1; o v2 e o v3 são os da segunda e da terceira "
                        "medição)")
    p.add_argument("--perguntas", default="", help="ids separados por vírgula (padrão: todos)")
    p.add_argument("--saida", type=Path, default=comum.DIR / "rodadas")
    p.add_argument("--banco", default="fidelidade_lei", help="banco novo da rodada (recriado)")
    p.add_argument("--banco-origem", default="agrocontabil", help="banco com a base de lei")
    p.add_argument("--psql", default="docker exec -i agrocontabil-postgres psql -U agrocontabil",
                   help="comando do psql (sem -d)")
    p.add_argument("--ollama-url", default="http://localhost:11434",
                   help="Ollama do embedding (o chat vai pela API)")
    p.add_argument("--porta", type=int, default=PORTA_BASE)
    p.add_argument("--so-verificar", action="store_true")
    return p


def main(argv=None) -> int:
    for fluxo in (sys.stdout, sys.stderr):  # o console do Windows (cp1252) não pode derrubar a rodada
        if hasattr(fluxo, "reconfigure"):
            fluxo.reconfigure(encoding="utf-8", errors="replace")
    args = montar_parser().parse_args(argv)
    args.perguntas = {i.strip() for i in args.perguntas.split(",") if i.strip()}
    args.iniciado_em = datetime.datetime.now(FUSO).isoformat()
    try:
        args.bracos = montar_bracos(args.env_desligada, args.env_ligada, args.braco)
        validar_banco(args.banco, args.banco_origem)
        args.arquivo_conjunto = comum.resolver_conjunto(args.conjunto)
        conjunto = comum.carregar_conjunto(args.arquivo_conjunto)
    except (ValueError, OSError, comum.ErroDeConjunto) as erro:
        sys.exit(str(erro))
    desconhecidas = args.perguntas - set(comum.perguntas_por_id(conjunto))
    if desconhecidas:
        sys.exit(f"ids fora do conjunto: {sorted(desconhecidas)}")
    ids = [i for i in comum.perguntas_por_id(conjunto) if not args.perguntas or i in args.perguntas]
    plano = plano_de_execucao(ids, args.repeticoes, args.semente, list(args.bracos))
    if args.so_verificar:
        print(f"conjunto {conjunto['versao']} ok; {len(ids)} perguntas x {args.repeticoes} "
              f"repetições x {len(args.bracos)} braços = {len(plano) * len(args.bracos)} execuções")
        for nome, env in args.bracos.items():
            print(f"{nome}:", json.dumps(mostrar_env(env)))
        return 0
    if not args.jar or not args.jar.exists():
        sys.exit("--jar: informe o jar do head do PR da correção (./mvnw -DskipTests package)")
    for nome in ("ANTHROPIC_API_KEY", "DB_PASSWORD"):
        if not os.environ.get(nome, "").strip():
            sys.exit(f"Defina a variável de ambiente {nome}")
    saida = args.saida / (datetime.datetime.now(FUSO).strftime("%Y-%m-%d-%H%M") + "-fidelidade-lei")
    saida.mkdir(parents=True, exist_ok=True)
    return executar(args, conjunto, saida)


if __name__ == "__main__":
    sys.exit(main())
