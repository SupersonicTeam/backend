"""Testes do executar.py da #497 contra um servidor falso e uma auditoria falsa."""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import threading
import unittest
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import executar  # noqa: E402


class BancoFalso:
    """Faz o papel do psql: conta as linhas de auditoria que o servidor falso grava."""

    def __init__(self):
        self.linhas: list[tuple] = []
        self.pular_proxima = False

    def __call__(self, comando, **_):
        sql = comando[-1]
        if sql.startswith("select count(*)"):
            return mock.Mock(stdout=f"{len(self.linhas)}\n")
        if not self.linhas:
            return mock.Mock(stdout="")
        return mock.Mock(stdout="\t".join(self.linhas[-1]) + "\n")

    def gravar(self, rota: str, camada: str):
        if self.pular_proxima:
            self.pular_proxima = False
            return
        self.linhas.append((rota, "RESPONDIDA", camada, "", "", "1200", "claude-haiku-5-5"))


def servidor(banco: BancoFalso, falhas: int = 0) -> ThreadingHTTPServer:
    estado = {"falhas": falhas}

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

        def do_POST(self):
            tamanho = int(self.headers.get("Content-Length", 0))
            corpo = json.loads(self.rfile.read(tamanho) or b"{}")
            if self.path == "/api/auth/login":
                self.responder(200, {"accessToken": "tok"})
                return
            if estado["falhas"] > 0:
                estado["falhas"] -= 1
                self.responder(503, {"errors": [{"code": "ASSISTENTE_INDISPONIVEL"}]})
                return
            rota = "FORA_DE_ESCOPO" if "Ignore" in corpo["pergunta"] else "DADOS"
            banco.gravar(rota, "REGRA")
            self.responder(200, {"resposta": "ok", "rota": rota, "citacoes": [],
                                 "fontesDados": [], "modoFallback": False, "atalhos": []})

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Manipulador)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


class Rodada(unittest.TestCase):

    def setUp(self):
        self.banco = BancoFalso()
        self.pasta = tempfile.TemporaryDirectory()
        self.addCleanup(self.pasta.cleanup)
        self.saida = Path(self.pasta.name) / "rodada.jsonl"

    def rodar(self, srv, *extra):
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        url = f"http://127.0.0.1:{srv.server_address[1]}"
        argv = ["--url", url, "--pasta", ".", "--banco", "b", "--modelo", "anthropic/x",
                "--email", "x@example.com", "--saida", str(self.saida), *extra]
        auditoria_real = executar.Auditoria
        with mock.patch.dict(os.environ, {"CERES_SENHA": "segredo-de-teste"}), \
                mock.patch.object(executar, "resolver_commit", return_value="c" * 40), \
                mock.patch.object(executar, "Auditoria",
                                  lambda c, b, u: auditoria_real(c, b, u, executar=self.banco)), \
                mock.patch.object(executar.time, "sleep"),                 mock.patch.object(executar, "aquecer_embedding"), \
                redirect_stdout(io.StringIO()) as saida:
            executar.main(argv)
        return saida.getvalue()

    def linhas(self):
        return [json.loads(l) for l in self.saida.read_text(encoding="utf-8").splitlines()]

    def test_grava_auditoria_proveniencia_e_sem_senha(self):
        saida = self.rodar(servidor(self.banco), "--ids", "a-01,l-01", "--repeticoes", "2")
        linhas = self.linhas()
        self.assertEqual(len(linhas), 2 + 4)
        pontuadas = [l for l in linhas if not l["aquecimento"]]
        self.assertEqual([l["id"] for l in pontuadas], ["a-01", "l-01", "a-01", "l-01"])
        for l in pontuadas:
            self.assertEqual(l["commit"], "c" * 40)
            self.assertEqual(l["modelo"], "anthropic/x")
            self.assertRegex(l["horario"], r"-03:00$")
            self.assertEqual(l["auditoria"]["camada_classificacao"], "REGRA")
            self.assertEqual(l["auditoria"]["rota"], l["corpo"]["rota"])
        self.assertEqual(pontuadas[0]["auditoria"]["rota"], "FORA_DE_ESCOPO")
        self.assertNotIn("segredo-de-teste", saida + self.saida.read_text(encoding="utf-8"))
        self.assertNotIn("\r\n", self.saida.read_bytes().decode("utf-8"))

    def test_roda_o_conjunto_da_516_por_conjunto(self):
        conjunto = AQUI.parent / "ceres-516" / "conjunto-516.json"
        saida = self.rodar(servidor(self.banco), "--conjunto", str(conjunto), "--ids",
                           "p516-01,a516-01", "--repeticoes", "1")
        self.assertIn("ceres-516-v1", saida)
        pontuadas = [l for l in self.linhas() if not l["aquecimento"]]
        self.assertEqual([l["id"] for l in pontuadas], ["p516-01", "a516-01"])

    def test_auditoria_que_nao_sobe_fica_marcada(self):
        self.banco.pular_proxima = True
        self.rodar(servidor(self.banco), "--ids", "l-01", "--repeticoes", "1")
        primeira = self.linhas()[0]
        self.assertTrue(primeira["auditoria_ausente"])
        self.assertEqual(primeira["auditoria"], {})
        self.assertTrue(primeira["falha_tecnica"])

    def test_falha_tecnica_repetida(self):
        self.rodar(servidor(self.banco, falhas=3), "--ids", "l-01", "--repeticoes", "1")
        pontuadas = [l for l in self.linhas() if not l["aquecimento"]]
        self.assertEqual([l["tentativa"] for l in pontuadas], [1, 2])
        self.assertEqual([l["falha_tecnica"] for l in pontuadas], [True, False])

    def test_recusa_id_desconhecido_e_arquivo_usado(self):
        with self.assertRaises(SystemExit):
            self.rodar(servidor(self.banco), "--ids", "zz")
        self.saida.write_text("{}\n", encoding="utf-8")
        with self.assertRaises(SystemExit):
            self.rodar(servidor(self.banco), "--ids", "l-01")


class Funcoes(unittest.TestCase):

    def test_commit_de_pasta_suja_ou_sem_git(self):
        with mock.patch.object(executar.subprocess, "run",
                               return_value=mock.Mock(stdout=" M a.java\n")):
            with self.assertRaises(SystemExit):
                executar.resolver_commit(".")
        erro = executar.subprocess.CalledProcessError(128, "git")
        with mock.patch.object(executar.subprocess, "run", side_effect=erro):
            with self.assertRaises(SystemExit):
                executar.resolver_commit(".")

    def test_ritmo_espera_o_intervalo(self):
        relogio = iter([0.0, 1.0, 1.0, 10.0, 10.0])
        dormiu = []
        ritmo = executar.Ritmo(3.5, relogio=lambda: next(relogio), dormir=dormiu.append)
        ritmo.esperar()
        ritmo.esperar()
        ritmo.esperar()
        self.assertEqual(dormiu, [2.5])

    def test_auditoria_vazia(self):
        auditoria = executar.Auditoria("c", "b", "u",
                                       executar=lambda *a, **k: mock.Mock(stdout=""))
        self.assertEqual(auditoria.ultima(), {})
        self.assertEqual(auditoria.contagem(), 0)


if __name__ == "__main__":
    unittest.main()
