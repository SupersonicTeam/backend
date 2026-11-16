"""Testes do harness-api (#497): token fixo, parada pela cota e linha de base de rede.

Servidor falso em thread, sem rede externa, sem Ceres e sem custo. Rodam na CI:

    python3 -m unittest discover -s docs/avaliacao/harness-api -p "test_*.py"
"""

import contextlib
import importlib.util
import io
import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

AQUI = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("harness", os.path.join(AQUI, "harness.py"))
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class ApiFalsa:
    """Chat que sobe `passo` pontos de uso por pergunta; `uso_status` e `health_status` mudam as
    respostas auxiliares; `token_valido` simula a expiracao do access token."""

    def __init__(self, uso=10, passo=20, uso_status=200, health_status=200):
        self.uso, self.passo = uso, passo
        self.uso_status, self.health_status = uso_status, health_status
        self.token_valido = "tokenok"
        self.perguntas = 0
        api = self

        class Handler(BaseHTTPRequestHandler):
            def _json(self, status, corpo):
                dados = json.dumps(corpo).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(dados)))
                self.end_headers()
                self.wfile.write(dados)

            def do_GET(self):
                if self.path == "/api/v1/health":
                    return self._json(api.health_status, {"status": "UP"})
                if self.headers.get("Authorization") != "Bearer " + api.token_valido:
                    return self._json(401, {"errors": [{"code": "NAO_AUTENTICADO"}]})
                if self.path == "/api/assistente/uso":
                    if api.uso_status != 200:
                        return self._json(api.uso_status, {})
                    return self._json(200, {"percentualUsado": api.uso})
                self._json(404, {})

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                if self.headers.get("Authorization") != "Bearer " + api.token_valido:
                    return self._json(401, {"errors": [{"code": "NAO_AUTENTICADO"}]})
                api.perguntas += 1
                api.uso += api.passo
                self._json(200, {"resposta": "x", "rota": "DADOS", "citacoes": [],
                                 "fontesDados": []})

            def log_message(self, *args):
                pass

        self.servidor = HTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.servidor.server_port}"
        threading.Thread(target=self.servidor.serve_forever, daemon=True).start()

    def fechar(self):
        self.servidor.shutdown()
        self.servidor.server_close()


class HarnessTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        gabarito = os.path.join(self.tmp.name, "gabarito.json")
        with open(gabarito, "w", encoding="utf-8") as arquivo:
            json.dump({"versao": "teste", "perguntas": [
                {"id": f"D0{i}", "pergunta": f"pergunta {i}", "rotaEsperada": "DADOS"}
                for i in range(1, 6)]}, arquivo)
        self.ambiente = {"AGRO_GABARITO": gabarito, "AGRO_SAIDA": self.tmp.name,
                         "AGRO_REPETICOES": "1", "AGRO_TOKEN": "tokenok"}

    def rodar(self, api, **extra):
        ambiente = {**self.ambiente, "AGRO_API_URL": api.url, **extra}
        antigo = dict(os.environ)
        for chave in list(os.environ):
            if chave.startswith("AGRO_"):
                del os.environ[chave]
        os.environ.update(ambiente)
        motivo = ""
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                try:
                    harness.main()
                except SystemExit as saida:  # a medicao interrompida sai com o motivo
                    motivo = str(saida)
        finally:
            os.environ.clear()
            os.environ.update(antigo)
        arquivos = [f for f in os.listdir(self.tmp.name) if f.endswith("-harness-api.json")]
        with open(os.path.join(self.tmp.name, arquivos[0]), encoding="utf-8") as arquivo:
            resultado = json.load(arquivo)
        for f in arquivos:
            os.remove(os.path.join(self.tmp.name, f))
        return resultado, motivo

    def test_para_quando_o_uso_passa_do_teto(self):
        api = ApiFalsa(uso=10, passo=20)
        self.addCleanup(api.fechar)
        resultado, motivo = self.rodar(api)  # 10 -> 30 -> 50 -> 70: para na terceira
        self.assertEqual(3, api.perguntas)
        self.assertIn("acima de 50%", motivo)
        self.assertEqual({"antes": 10, "depois": 70, "maximo": 50}, resultado["usoCotaPercentual"])

    def test_nao_comeca_se_o_uso_ja_passou_do_teto(self):
        api = ApiFalsa(uso=60)
        self.addCleanup(api.fechar)
        resultado, _ = self.rodar(api)
        self.assertEqual(0, api.perguntas)
        self.assertEqual(60, resultado["usoCotaPercentual"]["antes"])

    def test_uso_ilegivel_no_inicio_nao_mede(self):
        api = ApiFalsa(uso_status=429)
        self.addCleanup(api.fechar)
        resultado, motivo = self.rodar(api)
        self.assertEqual(0, api.perguntas)  # nunca trata a leitura que falhou como 0%
        self.assertIn("nao e protegida", motivo)

    def test_token_expirado_para_e_guarda_o_ultimo_uso_lido(self):
        api = ApiFalsa(uso=10, passo=5)
        self.addCleanup(api.fechar)
        # a segunda pergunta ja encontra o token vencido
        chamadas = {"n": 0}
        passo_original = harness.perguntar

        def perguntar(base, token, pergunta, token_fixo=False):
            chamadas["n"] += 1
            if chamadas["n"] == 2:
                api.token_valido = "outro"
            return passo_original(base, token, pergunta, token_fixo)

        harness.perguntar = perguntar
        self.addCleanup(setattr, harness, "perguntar", passo_original)
        resultado, motivo = self.rodar(api)
        self.assertIn("Token expirado", motivo)
        self.assertEqual(1, len(resultado["resultados"]))
        self.assertEqual(15, resultado["usoCotaPercentual"]["depois"])  # nao vira None

    def test_rede_sem_amostra_e_nula_nao_zero(self):
        api = ApiFalsa(uso=10, passo=1, health_status=401)
        self.addCleanup(api.fechar)
        resultado, _ = self.rodar(api, AGRO_IDS="D01")
        self.assertEqual({"amostras": 0, "p50Ms": None, "maxMs": None}, resultado["redeBaseline"])

    def test_completa_a_rodada_dentro_do_teto(self):
        api = ApiFalsa(uso=1, passo=1)
        self.addCleanup(api.fechar)
        resultado, motivo = self.rodar(api)
        self.assertEqual("", motivo)
        self.assertEqual(5, api.perguntas)
        self.assertEqual(6, resultado["usoCotaPercentual"]["depois"])


if __name__ == "__main__":
    unittest.main()
