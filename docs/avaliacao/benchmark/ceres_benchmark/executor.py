"""Executor da matriz do benchmark da Ceres (issue #348).

Para cada arquitetura: ``git worktree`` no commit, JAR construído ali, banco proprio (o schema do
``2463c27`` nao e o da ``main``) com a base de lei copiada do banco de origem. Para cada modelo:
``ollama pull``, prova do pensamento desligado, e o comando de avaliacao existente
(``dev,avaliacao``) com ``AI_CHAT_MODEL`` apontando para o modelo, enquanto uma thread amostra a
memoria do Ollama. Cada celula grava em ``<saida>/<rodada>/<celula>/`` o relatorio do comando
renomeado com o nome da celula e um ``celula.json`` com SHAs, hardware, pensamento e memoria.

Nenhum segredo vem do repositorio: senha do banco por ``DB_PASSWORD`` (a mesma do ``.env``).
"""

from __future__ import annotations

import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from . import gabarito as gab

ZONA = ZoneInfo("America/Sao_Paulo")
GABARITO_REPO = "docs/avaliacao/gabarito-ceres.json"
FERRAMENTAS_REPO = "src/main/java/br/com/agrocontabil/assistente/ferramentas/FerramentasDadosProdutor.java"
#: Pergunta curta da prova de pensamento: so mede se o modelo raciocina com think=false.
PERGUNTA_PROVA = "Responda apenas com a palavra: pronto"
_GIB = 1_073_741_824
_UNIDADES = {"b": 1, "kib": 1024, "mib": 1024**2, "gib": 1024**3, "tib": 1024**4,
             "kb": 1000, "mb": 1000**2, "gb": 1000**3, "tb": 1000**4}


def log(mensagem: str) -> None:
    print(f"[{datetime.now(ZONA):%H:%M:%S}] {mensagem}", flush=True)


def nome_celula(arquitetura: str, modelo: str) -> str:
    """``ceres-1--qwen3-0.6b``: arquitetura e modelo, seguros para nome de arquivo."""
    return f"{arquitetura}--" + re.sub(r"[^a-z0-9.]+", "-", modelo.lower()).strip("-")


def bytes_de_docker_stats(texto: str) -> int | None:
    """``"1.234GiB / 15.5GiB"`` -> bytes usados (saida de ``docker stats --format {{.MemUsage}}``)."""
    m = re.match(r"\s*([\d.]+)\s*([KMGT]?i?B)", texto, re.IGNORECASE)
    if not m:
        return None
    return int(float(m.group(1)) * _UNIDADES[m.group(2).lower()])


def resumo_pensamento(prova: dict) -> str:
    """Frase do relatorio a partir da prova de pensamento."""
    if prova.get("erro"):
        return f"prova falhou: {prova['erro']}"
    if prova.get("raciocinou"):
        return (f"ATIVO apesar de think=false ({prova.get('caracteresPensamento', 0)} caracteres de "
                "raciocínio na prova): latência e num_predict afetados")
    return "desligado (think=false; nenhum raciocínio na prova)"


def avaliar_prova(resposta: dict) -> dict:
    """Le a resposta do ``/api/chat`` da prova: raciocinio em ``thinking`` ou ``<think>`` no texto."""
    mensagem = resposta.get("message") or {}
    pensamento = mensagem.get("thinking") or ""
    conteudo = mensagem.get("content") or ""
    no_texto = "<think>" in conteudo
    return {
        "raciocinou": bool(pensamento.strip()) or no_texto,
        "caracteresPensamento": len(pensamento) + (len(conteudo) if no_texto else 0),
        "thinkNoTexto": no_texto,
        "tokensGerados": resposta.get("eval_count"),
        "duracaoMs": round((resposta.get("total_duration") or 0) / 1e6),
        "resposta": conteudo[:200],
    }


@dataclass
class Config:
    """Parametros da execucao (``matriz.json`` + linha de comando + ambiente)."""

    repo: Path
    matriz: dict
    saida: Path
    trabalho: Path
    modelos: list[str]
    arquiteturas: list[dict]
    repeticoes: int
    ollama_url: str
    ollama_container: str | None
    psql: list[str]
    db_origem: str
    db_prefixo: str
    hardware: str
    retomar: Path | None
    ficticio: bool = False


class Executor:
    """Roda a matriz; cada passo loga o que faz e falha cedo com a causa."""

    def __init__(self, cfg: Config):
        self.cfg = cfg

    # ------------------------------------------------------------------ utilitarios

    def rodar(self, comando: list[str], cwd: Path | None = None, entrada: bytes | None = None,
              env: dict | None = None, saida: Path | None = None, checar: bool = True) -> int:
        log("$ " + " ".join(shlex.quote(c) for c in comando))
        if saida is not None:
            with saida.open("wb") as arquivo:
                proc = subprocess.run(comando, cwd=cwd, input=entrada, env=env, stdout=arquivo,
                                      stderr=subprocess.STDOUT)
        else:
            proc = subprocess.run(comando, cwd=cwd, input=entrada, env=env)
        if checar and proc.returncode != 0:
            raise RuntimeError(f"comando falhou ({proc.returncode}): {comando[0]}")
        return proc.returncode

    def capturar(self, comando: list[str], cwd: Path | None = None) -> str:
        return subprocess.run(comando, cwd=cwd, check=True, capture_output=True,
                              text=True).stdout.strip()

    def psql(self, banco: str, sql: str) -> str:
        return self.capturar(self.cfg.psql + ["-d", banco, "-tAc", sql])

    def ollama(self, caminho: str, corpo: dict | None = None, timeout: float = 30) -> dict:
        dados = None if corpo is None else json.dumps(corpo).encode()
        requisicao = urllib.request.Request(
            self.cfg.ollama_url.rstrip("/") + caminho, data=dados,
            headers={"Content-Type": "application/json"}, method="GET" if corpo is None else "POST")
        with urllib.request.urlopen(requisicao, timeout=timeout) as resposta:
            return json.loads(resposta.read().decode() or "{}")

    # ------------------------------------------------------------------ passos

    def conferir_pre_requisitos(self) -> dict:
        for programa in ("git", "java"):
            if shutil.which(programa) is None:
                raise RuntimeError(f"{programa} nao encontrado no PATH")
        if not os.environ.get("DB_PASSWORD"):
            raise RuntimeError("DB_PASSWORD ausente: exporte a senha do Postgres (a do .env)")
        versao = self.ollama("/api/version").get("version", "?")
        trechos = int(self.psql(self.cfg.db_origem, "select count(*) from legislacao_trecho") or 0)
        esperado = self.cfg.matriz.get("trechosLegislacao")
        if trechos == 0:
            raise RuntimeError(f"base de lei vazia em {self.cfg.db_origem}: copie conforme o "
                               "deploy/README.md antes de rodar")
        if esperado and trechos != esperado:
            log(f"AVISO: {trechos} trechos de lei em {self.cfg.db_origem}, esperado {esperado}; "
                "o numero vai no relatorio")
        return {"ollama": versao, "trechosLegislacao": trechos}

    def resolver_sha(self, ref: str) -> str:
        return self.capturar(["git", "rev-parse", "--verify", ref + "^{commit}"], self.cfg.repo)

    def worktree(self, arquitetura: dict) -> Path:
        destino = self.cfg.trabalho / "worktrees" / f"{arquitetura['nome']}-{arquitetura['sha'][:7]}"
        if destino.exists():
            atual = self.capturar(["git", "rev-parse", "HEAD"], destino)
            if atual != arquitetura["sha"]:
                raise RuntimeError(f"{destino} esta em {atual[:7]}, nao em {arquitetura['sha'][:7]}:"
                                   " apague o worktree (git worktree remove)")
            return destino
        destino.parent.mkdir(parents=True, exist_ok=True)
        self.rodar(["git", "worktree", "add", "--detach", str(destino), arquitetura["sha"]],
                   self.cfg.repo)
        return destino

    def construir(self, worktree: Path) -> Path:
        jars = sorted(worktree.glob("target/*.jar"))
        jars = [j for j in jars if not j.name.endswith(("-plain.jar", ".original"))]
        if not jars:
            self.rodar(["sh", "mvnw", "-q", "-B", "-DskipTests", "-Djacoco.skip=true",
                        "-Dcheckstyle.skip", "package"], worktree)
            jars = [j for j in sorted(worktree.glob("target/*.jar"))
                    if not j.name.endswith("-plain.jar")]
        if not jars:
            raise RuntimeError(f"JAR nao gerado em {worktree}/target")
        return jars[0]

    def ambiente_java(self, banco: str, modelo: str) -> dict:
        env = dict(os.environ)
        env.update({
            "SPRING_PROFILES_ACTIVE": "dev,avaliacao",
            "DB_NAME": banco,
            "AI_CHAT_PROVIDER": "ollama",
            "AI_CHAT_MODEL": modelo,
            # O pensamento e desligado pela mesma propriedade que a producao usa; a prova confere.
            "AI_OLLAMA_THINK": "false",
            "AI_OLLAMA_BASE_URL": self.cfg.ollama_url,
            "AI_OLLAMA_PULL_STRATEGY": "never",
        })
        return env

    def argumentos_avaliacao(self, gabarito: Path, saida: Path) -> list[str]:
        return [
            "--spring.docker.compose.enabled=false",
            f"--avaliacao.gabarito={gabarito}",
            f"--avaliacao.saida={saida}",
            f"--avaliacao.repeticoes={self.cfg.repeticoes}",
            f"--avaliacao.hardware={self.cfg.hardware}",
        ]

    def preparar_banco(self, arquitetura: dict, worktree: Path, jar: Path, logs: Path) -> str:
        """Banco proprio da arquitetura: Liquibase do commit + base de lei copiada da origem."""
        banco = f"{self.cfg.db_prefixo}_{arquitetura['nome'].replace('-', '_')}"
        trechos = self.psql(banco, "select count(*) from legislacao_trecho") \
            if self._banco_existe(banco) and self._tabela_existe(banco) else ""
        origem = self.psql(self.cfg.db_origem, "select count(*) from legislacao_trecho")
        if trechos and trechos == origem:
            log(f"banco {banco} pronto ({trechos} trechos): reaproveitado")
            return banco
        self.rodar(self.cfg.psql + ["-d", "postgres", "-c", f'DROP DATABASE IF EXISTS "{banco}"'])
        self.rodar(self.cfg.psql + ["-d", "postgres", "-c", f'CREATE DATABASE "{banco}"'])
        # O comando de avaliacao aplica o Liquibase na subida e, com um id que nao existe, para
        # antes do seed (codigo 1 esperado): e a forma de criar o schema daquele commit sem web.
        self.rodar(["java", "-jar", str(jar), "--spring.docker.compose.enabled=false",
                    "--avaliacao.ids=__schema_apenas__"], worktree,
                   env=self.ambiente_java(banco, "nenhum"), saida=logs / f"schema-{banco}.log",
                   checar=False)
        if not self._tabela_existe(banco):
            raise RuntimeError(f"Liquibase nao criou legislacao_trecho em {banco}: ver "
                               f"{logs}/schema-{banco}.log")
        exportar = self.cfg.psql + ["-d", self.cfg.db_origem, "-c",
                                    "\\copy (select id, content, metadata, embedding from "
                                    "legislacao_trecho) to stdout"]
        dados = subprocess.run(exportar, check=True, capture_output=True).stdout
        self.rodar(self.cfg.psql + ["-d", banco, "-c", "\\copy legislacao_trecho(id, content, "
                                    "metadata, embedding) from stdin"], entrada=dados)
        copiados = self.psql(banco, "select count(*) from legislacao_trecho")
        if copiados != origem:
            raise RuntimeError(f"copia da base de lei incompleta: {copiados} de {origem}")
        log(f"banco {banco}: schema do {arquitetura['sha'][:7]} + {copiados} trechos de lei")
        return banco

    def _banco_existe(self, banco: str) -> bool:
        return self.psql("postgres", f"select 1 from pg_database where datname = '{banco}'") == "1"

    def _tabela_existe(self, banco: str) -> bool:
        return self.psql(banco, "select to_regclass('public.legislacao_trecho') is not null") == "t"

    def baixar_modelos(self) -> None:
        for modelo in self.cfg.modelos + [self.cfg.matriz.get("embedding", "bge-m3")]:
            log(f"ollama pull {modelo}")
            self.ollama("/api/pull", {"model": modelo, "stream": False}, timeout=3600)

    def descarregar_outros(self, manter: set[str]) -> None:
        """Tira da memoria os modelos de chat de outras celulas: a memoria medida e so a deste."""
        for carregado in self.ollama("/api/ps").get("models", []):
            nome = carregado.get("name") or carregado.get("model")
            if nome and nome not in manter:
                self.ollama("/api/generate", {"model": nome, "keep_alive": 0}, timeout=120)

    def provar_pensamento(self, modelo: str) -> dict:
        corpo = {"model": modelo, "stream": False, "think": False,
                 "messages": [{"role": "user", "content": PERGUNTA_PROVA}],
                 "options": {"num_predict": 256, "temperature": 0.2}}
        try:
            prova = avaliar_prova(self.ollama("/api/chat", corpo, timeout=600))
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            prova = {"erro": f"{type(e).__name__}: {e}"}
        try:
            capacidades = self.ollama("/api/show", {"model": modelo}).get("capabilities")
        except (urllib.error.URLError, TimeoutError, ValueError):
            capacidades = None
        prova["capacidades"] = capacidades
        prova["resumo"] = resumo_pensamento(prova)
        return prova

    def memoria_processo(self) -> int | None:
        """RSS do Ollama: ``docker stats`` do container ou soma dos processos ``ollama``."""
        try:
            if self.cfg.ollama_container:
                texto = self.capturar(["docker", "stats", "--no-stream", "--format",
                                       "{{.MemUsage}}", self.cfg.ollama_container])
                return bytes_de_docker_stats(texto)
            saida = self.capturar(["ps", "-axo", "rss=,comm="])
            total = sum(int(l.split(None, 1)[0]) * 1024 for l in saida.splitlines()
                        if "ollama" in l.lower() and l.split(None, 1)[0].isdigit())
            return total or None
        except (subprocess.CalledProcessError, FileNotFoundError):
            return None

    def amostrar_memoria(self, modelo: str, parar: threading.Event, resultado: dict) -> None:
        while not parar.wait(5):
            try:
                for m in self.ollama("/api/ps", timeout=5).get("models", []):
                    if (m.get("name") or m.get("model")) == modelo:
                        resultado["modeloBytes"] = max(resultado.get("modeloBytes", 0), m.get("size", 0))
                        resultado["modeloVramBytes"] = max(resultado.get("modeloVramBytes", 0),
                                                           m.get("size_vram", 0))
                        resultado["contexto"] = m.get("context_length")
            except (urllib.error.URLError, TimeoutError, ValueError):
                pass
            rss = self.memoria_processo()
            if rss:
                resultado["processoPicoBytes"] = max(resultado.get("processoPicoBytes", 0), rss)
        resultado["metodoProcesso"] = (f"docker stats {self.cfg.ollama_container}"
                                       if self.cfg.ollama_container else "ps (processos ollama)")

    def rodar_celula(self, rodada_dir: Path, arquitetura: dict, worktree: Path, jar: Path,
                     banco: str, gabarito: Path, modelo: str) -> dict:
        nome = nome_celula(arquitetura["nome"], modelo)
        destino = rodada_dir / nome
        manifesto_arquivo = destino / "celula.json"
        if manifesto_arquivo.exists():
            anterior = json.loads(manifesto_arquivo.read_text(encoding="utf-8"))
            if anterior.get("codigoSaida") == 0:
                log(f"{nome}: ja medida nesta rodada, pulando")
                return anterior
        destino.mkdir(parents=True, exist_ok=True)
        temporario = destino / "saida-do-comando"
        shutil.rmtree(temporario, ignore_errors=True)
        temporario.mkdir()

        self.descarregar_outros({self.cfg.matriz.get("embedding", "bge-m3"),
                                 self.cfg.matriz.get("embedding", "bge-m3") + ":latest"})
        pensamento = self.provar_pensamento(modelo)
        log(f"{nome}: pensamento {pensamento['resumo']}")

        memoria: dict = {}
        parar = threading.Event()
        amostrador = threading.Thread(target=self.amostrar_memoria, args=(modelo, parar, memoria),
                                      daemon=True)
        amostrador.start()
        inicio = time.monotonic()
        try:
            codigo = self.rodar(["java", "-jar", str(jar)]
                                + self.argumentos_avaliacao(gabarito, temporario),
                                worktree, env=self.ambiente_java(banco, modelo),
                                saida=destino / "comando.log", checar=False)
        finally:
            parar.set()
            amostrador.join(timeout=30)
        duracao = round(time.monotonic() - inicio)
        arquivos = self._renomear_saida(temporario, destino, nome)
        manifesto = {
            "celula": nome,
            "arquitetura": arquitetura["nome"],
            "rotuloArquitetura": arquitetura["rotulo"],
            "sha": arquitetura["sha"],
            "modelo": modelo,
            "embedding": self.cfg.matriz.get("embedding", "bge-m3"),
            "repeticoes": self.cfg.repeticoes,
            "hardware": self.cfg.hardware,
            "banco": banco,
            "gabarito": gabarito.name,
            "pensamento": pensamento,
            "memoria": memoria,
            "duracaoSegundos": duracao,
            "codigoSaida": codigo,
            "arquivos": arquivos,
            "terminadaEm": datetime.now(ZONA).isoformat(timespec="seconds"),
        }
        manifesto_arquivo.write_text(json.dumps(manifesto, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
        log(f"{nome}: codigo {codigo} em {duracao} s"
            + ("" if codigo == 0 else f" (ver {destino}/comando.log)"))
        return manifesto

    @staticmethod
    def _renomear_saida(temporario: Path, destino: Path, nome: str) -> list[str]:
        """``AAAA-MM-DD-HHmm-<modelo>.*`` do comando -> ``<celula>.*`` (o resto do nome e o sufixo)."""
        gravados = []
        for arquivo in sorted(temporario.iterdir()):
            sufixo = next((s for s in ("-clareza-cego.csv", "-clareza-chave.json", ".md", ".json")
                           if arquivo.name.endswith(s)), None)
            datado = re.match(r"\d{4}-\d{2}-\d{2}-\d{4}-", arquivo.name)
            novo = destino / (nome + sufixo if sufixo and datado else arquivo.name)
            shutil.move(str(arquivo), novo)
            gravados.append(novo.name)
        temporario.rmdir()
        return gravados

    # ------------------------------------------------------------------ orquestracao

    def executar(self) -> Path:
        cfg = self.cfg
        estado = self.conferir_pre_requisitos()
        for arquitetura in cfg.arquiteturas:
            arquitetura["sha"] = self.resolver_sha(arquitetura["ref"])
        rodada_dir = cfg.retomar or (cfg.saida / datetime.now(ZONA).strftime("%Y-%m-%d-%H%M"))
        rodada_dir.mkdir(parents=True, exist_ok=True)
        logs = rodada_dir / "logs"
        logs.mkdir(exist_ok=True)

        canonica = cfg.arquiteturas[-1]
        canonico_txt = self.capturar(["git", "show", f"{canonica['sha']}:{GABARITO_REPO}"], cfg.repo)
        (rodada_dir / "gabarito-canonico.json").write_text(canonico_txt + "\n", encoding="utf-8")
        canonico = json.loads(canonico_txt)
        antigo_txt = self.capturar(["git", "show", f"{cfg.arquiteturas[0]['sha']}:{GABARITO_REPO}"],
                                   cfg.repo)
        (rodada_dir / "gabarito-antigo.json").write_text(antigo_txt + "\n", encoding="utf-8")

        celulas = []
        for arquitetura in cfg.arquiteturas:
            fonte = self.capturar(["git", "show", f"{arquitetura['sha']}:{FERRAMENTAS_REPO}"], cfg.repo)
            adaptado, adaptacoes = gab.adaptar(
                canonico, gab.assinaturas_ferramentas(fonte),
                arquitetura.get("remocoesExtras"), arquitetura.get("indiceDeLista", True))
            arquivo_gab = rodada_dir / f"gabarito-{arquitetura['nome']}.json"
            arquivo_gab.write_text(json.dumps(adaptado, ensure_ascii=False, indent=2) + "\n",
                                   encoding="utf-8")
            arquivo_adapt = rodada_dir / f"adaptacao-{arquitetura['nome']}.json"
            arquivo_adapt.write_text(json.dumps(adaptacoes, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
            arquitetura["gabarito"] = arquivo_gab
            arquitetura["adaptacao"] = arquivo_adapt.name
            log(f"{arquitetura['rotulo']} ({arquitetura['sha'][:7]}): {len(adaptacoes)} perguntas "
                "adaptadas")
            for modelo in cfg.modelos:
                celulas.append({"nome": nome_celula(arquitetura["nome"], modelo),
                                "arquitetura": arquitetura["nome"],
                                "rotuloArquitetura": arquitetura["rotulo"], "modelo": modelo})

        rodada = {
            "iniciadaEm": datetime.now(ZONA).isoformat(timespec="seconds"),
            "hardware": [cfg.hardware],
            "ollama": estado["ollama"],
            "trechosLegislacao": estado["trechosLegislacao"],
            "repeticoes": cfg.repeticoes,
            "embedding": cfg.matriz.get("embedding", "bge-m3"),
            "modeloReferencia": cfg.matriz.get("modeloReferencia", "qwen3:4b-instruct"),
            "gabaritoCanonico": "gabarito-canonico.json",
            "gabaritoAntigo": "gabarito-antigo.json",
            "arquiteturas": [
                {"nome": a["nome"], "rotulo": a["rotulo"], "ref": a["ref"], "sha": a["sha"],
                 "descricao": a.get("descricao", ""), "adaptacao": a["adaptacao"]}
                for a in cfg.arquiteturas],
            "modelos": cfg.modelos,
            "celulas": celulas,
        }
        anterior = rodada_dir / "rodada.json"
        if anterior.exists():
            rodada["iniciadaEm"] = json.loads(anterior.read_text(encoding="utf-8"))["iniciadaEm"]
        anterior.write_text(json.dumps(rodada, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        self.baixar_modelos()
        for arquitetura in cfg.arquiteturas:
            worktree = self.worktree(arquitetura)
            jar = self.construir(worktree)
            banco = self.preparar_banco(arquitetura, worktree, jar, logs)
            for modelo in cfg.modelos:
                self.rodar_celula(rodada_dir, arquitetura, worktree, jar, banco,
                                  arquitetura["gabarito"], modelo)
        return rodada_dir


def detectar_hardware() -> str:
    """CPU, memoria e GPU detectados (Linux/macOS); o time pode sobrescrever com --hardware."""
    partes = [f"{platform.system()} {platform.machine()}"]
    cpu = None
    try:
        for linha in Path("/proc/cpuinfo").read_text().splitlines():
            if linha.startswith("model name"):
                cpu = linha.split(":", 1)[1].strip()
                break
        memoria = next(l for l in Path("/proc/meminfo").read_text().splitlines()
                       if l.startswith("MemTotal"))
        partes.append(f"{int(memoria.split()[1]) / 1024 / 1024:.0f} GB RAM")
    except (OSError, StopIteration):
        try:
            cpu = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True,
                                 text=True).stdout.strip() or None
            mem = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True,
                                     text=True).stdout.strip() or 0)
            if mem:
                partes.append(f"{mem / _GIB:.0f} GB RAM")
        except (OSError, ValueError):
            pass
    if cpu:
        partes.insert(0, cpu)
    partes.append(f"{os.cpu_count()} núcleos lógicos")
    if shutil.which("nvidia-smi"):
        gpu = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"],
                             capture_output=True, text=True).stdout.strip()
        if gpu:
            partes.append("GPU " + gpu.replace("\n", "; "))
    else:
        partes.append("sem GPU NVIDIA detectada")
    return ", ".join(partes)


def montar_config(argumentos, repo: Path) -> Config:
    matriz = json.loads((Path(argumentos.matriz)).read_text(encoding="utf-8"))
    arquiteturas = [dict(a) for a in matriz["arquiteturas"]]
    if argumentos.arquiteturas:
        pedidas = argumentos.arquiteturas.split(",")
        arquiteturas = [a for a in arquiteturas if a["nome"] in pedidas]
        if len(arquiteturas) != len(pedidas):
            raise SystemExit(f"arquitetura desconhecida em {pedidas}")
    for a in arquiteturas:
        if a["nome"] == matriz["arquiteturas"][-1]["nome"] and argumentos.ref_ceres2:
            a["ref"] = argumentos.ref_ceres2
    modelos = argumentos.modelos.split(",") if argumentos.modelos else list(matriz["modelos"])
    return Config(
        repo=repo,
        matriz=matriz,
        saida=Path(argumentos.saida).resolve(),
        trabalho=Path(argumentos.trabalho).expanduser().resolve(),
        modelos=modelos,
        arquiteturas=arquiteturas,
        repeticoes=argumentos.repeticoes or matriz.get("repeticoes", 3),
        ollama_url=os.environ.get("BENCH_OLLAMA_URL", "http://localhost:11434"),
        ollama_container=os.environ.get("BENCH_OLLAMA_CONTAINER") or None,
        psql=shlex.split(os.environ.get(
            "BENCH_PSQL", "docker exec -i agrocontabil-postgres psql -U agrocontabil -v ON_ERROR_STOP=1")),
        db_origem=os.environ.get("BENCH_DB_ORIGEM", "agrocontabil"),
        db_prefixo=os.environ.get("BENCH_DB_PREFIXO", "agro_bench"),
        hardware=argumentos.hardware or detectar_hardware(),
        retomar=Path(argumentos.retomar).resolve() if argumentos.retomar else None,
    )


if __name__ == "__main__":  # pragma: no cover
    sys.exit("use docs/avaliacao/benchmark/executar_matriz.py")
