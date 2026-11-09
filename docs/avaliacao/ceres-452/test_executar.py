"""Testes do executar.py da #452 contra servidores falsos (só biblioteca padrão)."""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import executar  # noqa: E402

CONJUNTO_V1 = AQUI.parent / "treino" / "perguntas-ceres-452.json"
CONJUNTO_V2 = AQUI.parent / "treino" / "perguntas-ceres-452-v2.json"

CHAMADAS: list[tuple[str, str]] = []


def servidor(nome: str, falhas_iniciais: int = 0) -> ThreadingHTTPServer:
    """Backend falso: login, chat (com `falhas_iniciais` 503 antes do primeiro 200) e Ollama."""
    estado = {"falhas": falhas_iniciais}

    class Manipulador(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def responder(self, status: int, corpo: dict) -> None:
            dados = json.dumps(corpo).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(dados)))
            self.end_headers()
            self.wfile.write(dados)

        def do_GET(self):
            if self.path == "/api/tags":
                self.responder(200, {"models": [
                    {"name": "qwen3:4b-instruct", "digest": "abc123def456"}]})
            else:
                self.responder(404, {})

        def do_POST(self):
            tamanho = int(self.headers.get("Content-Length", 0))
            corpo = json.loads(self.rfile.read(tamanho) or b"{}")
            if self.path == "/api/auth/login":
                self.responder(200, {"accessToken": "t-" + nome})
            elif self.path == "/api/assistente/chat":
                assert self.headers["Authorization"] == "Bearer t-" + nome
                CHAMADAS.append((nome, corpo["pergunta"]))
                if estado["falhas"] > 0:
                    estado["falhas"] -= 1
                    self.responder(503, {"errors": [{"code": "ASSISTENTE_INDISPONIVEL"}]})
                    return
                self.responder(200, {"resposta": "ok", "rota": "DADOS", "citacoes": [],
                                     "fontesDados": [], "modoFallback": False})
            else:
                self.responder(200, {})

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Manipulador)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def fechar(srv: ThreadingHTTPServer) -> None:
    if srv.socket.fileno() != -1:
        srv.shutdown()
        srv.server_close()


def url(srv: ThreadingHTTPServer) -> str:
    return f"http://127.0.0.1:{srv.server_address[1]}"


class Funcoes(unittest.TestCase):

    def test_ordem_alterna_entre_repeticoes(self):
        self.assertEqual(executar.ordem_dos_bracos(["base", "corr"], 1), ["base", "corr"])
        self.assertEqual(executar.ordem_dos_bracos(["base", "corr"], 2), ["corr", "base"])
        self.assertEqual(executar.ordem_dos_bracos(["base", "corr"], 3), ["base", "corr"])

    def test_commit_por_sha_completo_ou_recusa(self):
        self.assertEqual(executar.resolver_commit("c" * 40), "c" * 40)
        for invalido in ("abc1234", "nao-e-sha-nem-pasta"):
            with self.assertRaises(SystemExit):
                executar.resolver_commit(invalido)

    def test_pasta_sem_git_vira_mensagem(self):
        erro = executar.subprocess.CalledProcessError(128, "git", stderr="not a git repository")
        with mock.patch.object(executar.subprocess, "run", side_effect=erro), \
                self.assertRaises(SystemExit):
            executar.resolver_commit(str(AQUI))

    def test_retry_after_em_data_cai_no_padrao(self):
        self.assertEqual(executar.segundos_de_espera("12"), 12.0)
        self.assertEqual(executar.segundos_de_espera("Wed, 21 Oct 2026 07:28:00 GMT"), 30)
        self.assertEqual(executar.segundos_de_espera(None), 30)

    def test_commit_da_pasta_do_repositorio(self):
        raiz = AQUI.parents[2]
        with mock.patch.object(executar.subprocess, "run") as run:
            run.side_effect = [mock.Mock(stdout=""), mock.Mock(stdout="f" * 40 + "\n")]
            self.assertEqual(executar.resolver_commit(str(raiz)), "f" * 40)
            run.side_effect = [mock.Mock(stdout=" M arquivo.java\n")]
            with self.assertRaises(SystemExit):
                executar.resolver_commit(str(raiz))


class ServidoresFalsos(unittest.TestCase):
    """Base dos testes de rodada: dois backends falsos, um Ollama falso e a pasta de saída."""

    def setUp(self):
        CHAMADAS.clear()
        self.base = servidor("base")
        self.corr = servidor("corr", falhas_iniciais=1)
        self.ollama = servidor("ollama")
        self.pasta = tempfile.TemporaryDirectory()
        self.addCleanup(self.pasta.cleanup)
        for srv in (self.base, self.corr, self.ollama):
            self.addCleanup(fechar, srv)

    def rodar(self, *extra, conjunto: Path | None = CONJUNTO_V1):
        argv = ["--braco", "base", url(self.base), "a" * 40,
                "--braco", "corr", url(self.corr), "b" * 40,
                "--email", "x@example.com", "--saida-dir", self.pasta.name,
                "--ids", "g1-01,c-17", "--repeticoes", "2", "--ollama", url(self.ollama),
                *(["--conjunto", str(conjunto)] if conjunto else []), *extra]
        with mock.patch.dict(os.environ, {"CERES_SENHA": "segredo-de-teste"}), \
                redirect_stdout(io.StringIO()) as saida:
            executar.main(argv)
        return saida.getvalue()

    def linhas(self, nome: str) -> list[dict]:
        caminho = Path(self.pasta.name) / f"{nome}.jsonl"
        return [json.loads(l) for l in caminho.read_text(encoding="utf-8").splitlines()]


class Rodada(ServidoresFalsos):

    def test_grava_proveniencia_aquece_alterna_e_repete_falha_tecnica(self):
        saida = self.rodar()
        base, corr = self.linhas("base"), self.linhas("corr")
        for linha in base + corr:
            for campo in ("braco", "commit", "modelo", "modelo_digest", "horario",
                          "data_referencia", "segundos", "falha_tecnica", "aquecimento"):
                self.assertIn(campo, linha)
            self.assertEqual(linha["modelo_digest"], "abc123def456")
            self.assertRegex(linha["horario"], r"-03:00$")
        self.assertEqual({l["commit"] for l in base}, {"a" * 40})
        self.assertEqual({l["commit"] for l in corr}, {"b" * 40})
        # Aquecimento antes de tudo, nos dois braços.
        self.assertTrue(all(l["aquecimento"] for l in base[:2]))
        aquecimentos = [l for l in corr if l["aquecimento"]]
        self.assertEqual(len(aquecimentos), 2)
        # A primeira chamada do braço corr falhou (503) no aquecimento; a pontuada não repete.
        self.assertTrue(aquecimentos[0]["falha_tecnica"])
        pontuadas = [l for l in base if not l["aquecimento"]]
        self.assertEqual(len(pontuadas), 4)
        # Ordem: rep 1 base→corr, rep 2 corr→base (só as pontuadas).
        perguntas = {"Quais guias eu tenho pra pagar no mês que vem?",
                     "Qual vai ser o preço da saca de soja no mês que vem?"}
        ordem = [nome for nome, texto in CHAMADAS if texto in perguntas]
        self.assertEqual(ordem, ["base", "base", "corr", "corr", "corr", "corr", "base", "base"])
        self.assertNotIn("segredo-de-teste", saida)
        for nome in ("base", "corr"):
            texto = (Path(self.pasta.name) / f"{nome}.jsonl").read_text(encoding="utf-8")
            self.assertNotIn("segredo-de-teste", texto)

    def test_falha_tecnica_pontuada_e_repetida(self):
        fechar(self.corr)
        self.corr = servidor("corr", falhas_iniciais=3)
        self.addCleanup(fechar, self.corr)
        self.rodar("--repeticoes", "1", "--ids", "g1-01")
        pontuadas = [l for l in self.linhas("corr") if not l["aquecimento"]]
        self.assertEqual([l["tentativa"] for l in pontuadas], [1, 2])
        self.assertEqual([l["falha_tecnica"] for l in pontuadas], [True, False])

    def test_recusa_id_desconhecido(self):
        with self.assertRaises(SystemExit):
            self.rodar("--ids", "g1-1")

    def test_backend_fora_do_ar_interrompe(self):
        fechar(self.corr)
        with self.assertRaises(SystemExit):
            self.rodar("--repeticoes", "1", "--ids", "g1-01")

    def test_recusa_pasta_com_execucoes(self):
        (Path(self.pasta.name) / "base.jsonl").write_text("{}\n", encoding="utf-8")
        with self.assertRaises(SystemExit):
            self.rodar()

    def test_recusa_sem_senha(self):
        with mock.patch.dict(os.environ, {}, clear=True), \
                self.assertRaisesRegex(SystemExit, "CERES_SENHA"):
            executar.main(["--braco", "base", url(self.base), "a" * 40, "--email", "x",
                           "--conjunto", str(CONJUNTO_V2), "--saida-dir", self.pasta.name])


class Conjuntos(ServidoresFalsos):
    """O conjunto vem de --conjunto: o v2 manda as perguntas dele e só aceita os ids dele."""

    def test_conjunto_v2_manda_as_perguntas_dele(self):
        perguntas = {p["id"]: p["pergunta"] for p in
                     json.loads(CONJUNTO_V2.read_text(encoding="utf-8"))["perguntas"]}
        ids = ["v2-g1-01", "v2-g4-09", "v2-c-11"]
        saida = self.rodar("--repeticoes", "1", "--ids", ",".join(ids), conjunto=CONJUNTO_V2)
        self.assertIn("ceres-452-v2", saida)
        sha = Path(str(CONJUNTO_V2) + ".sha256").read_text(encoding="utf-8").split()[0]
        for nome in ("base", "corr"):
            linhas = self.linhas(nome)
            self.assertEqual({(l["conjunto_versao"], l["conjunto_sha256"]) for l in linhas},
                             {("ceres-452-v2", sha)})
            pontuadas = [l for l in linhas if not l["aquecimento"]]
            self.assertEqual(sorted({l["id"] for l in pontuadas}), sorted(ids))
        enviadas = {texto for _, texto in CHAMADAS} - set(executar.PERGUNTAS_DE_AQUECIMENTO)
        self.assertEqual(enviadas, {perguntas[i] for i in ids})

    def test_id_do_v1_recusado_no_v2(self):
        with self.assertRaisesRegex(SystemExit, "ids fora do conjunto"):
            self.rodar("--ids", "g1-01", conjunto=CONJUNTO_V2)
        self.assertEqual(CHAMADAS, [])

    def test_conjunto_obrigatorio(self):
        with redirect_stderr(io.StringIO()) as erro, self.assertRaises(SystemExit):
            self.rodar(conjunto=None)
        self.assertIn("--conjunto", erro.getvalue())
        self.assertEqual(CHAMADAS, [])

    def test_conjunto_inexistente(self):
        with self.assertRaisesRegex(SystemExit, "não encontrado"):
            self.rodar(conjunto=Path(self.pasta.name) / "nao-existe.json")

    def test_conjunto_alterado_recusado_antes_de_chamar(self):
        copia = Path(self.pasta.name) / "conjunto"
        copia.mkdir()
        alterado = copia / CONJUNTO_V2.name
        original = CONJUNTO_V2.read_bytes()
        alterado.write_bytes(original.replace(b'"ceres-452-v2"', b'"ceres-452-vX"'))
        self.assertNotEqual(alterado.read_bytes(), original)
        (copia / (CONJUNTO_V2.name + ".sha256")).write_bytes(
            Path(str(CONJUNTO_V2) + ".sha256").read_bytes())
        with self.assertRaisesRegex(SystemExit, "Conjunto alterado"):
            self.rodar("--ids", "v2-g1-01", conjunto=alterado)
        self.assertEqual(CHAMADAS, [])


if __name__ == "__main__":
    unittest.main()
