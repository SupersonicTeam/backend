"""O conjunto da #443: forma, contagens, sha256 e a ligação com a regra de decisão."""

import json
import unittest
from collections import Counter

import ressalva_comum as comum

NORMAS = {"LC 214/2025", "EC 132/2023", "LC 227/2026", "Lei 8.212/1991", "Lei 9.528/1997"}
FORMAS = {"nome_termo_lei", "ncm", "hipotese", "indireta", "contexto_venda", "lei_produto", "coloquial"}


class ConjuntoTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.conjunto = comum.carregar_conjunto()
        cls.perguntas = cls.conjunto["perguntas"]

    def grupo(self, nome):
        return [p for p in self.perguntas if p["grupo"] == nome]

    def test_sha256_registrado_confere(self):
        self.assertEqual(comum.hash_registrado(comum.CONJUNTO), comum.sha256_arquivo(comum.CONJUNTO))

    def test_versao(self):
        self.assertEqual(self.conjunto["versao"], comum.VERSAO_CONJUNTO)

    def test_tres_grupos_com_as_contagens_da_regra(self):
        self.assertEqual(Counter(p["grupo"] for p in self.perguntas),
                         {"DEFEITO": 36, "CONTROLE_LEI": 12, "CONTROLE_DADOS": 12})

    def test_ids_unicos_e_perguntas_distintas(self):
        ids = [p["id"] for p in self.perguntas]
        self.assertEqual(len(ids), len(set(ids)))
        textos = [comum.normalizar(p["pergunta"]) for p in self.perguntas]
        self.assertEqual(len(textos), len(set(textos)))

    def test_pergunta_cabe_no_limite_do_chat(self):
        for p in self.perguntas:
            self.assertLessEqual(len(p["pergunta"]), 1000, p["id"])

    def test_defeito_tem_sete_formas_e_dois_estratos(self):
        defeito = self.grupo("DEFEITO")
        self.assertEqual({p["forma"] for p in defeito}, FORMAS)
        self.assertEqual(Counter(p["estrato"] for p in defeito), {"A": 30, "B": 6})
        for p in defeito:
            self.assertEqual(p["esperado"], {"tratamento": True, "ressalva": True}, p["id"])
        # o estrato B tem uma pergunta em cada forma que o permite, e nenhum produto repete a mesma forma
        formas_por_produto = Counter((p["produto"], p["forma"]) for p in defeito)
        self.assertTrue(all(v == 1 for v in formas_por_produto.values()))

    def test_defeito_varia_o_produto(self):
        produtos = {p["produto"] for p in self.grupo("DEFEITO")}
        self.assertGreaterEqual(len(produtos), 28)

    def test_controles_de_lei(self):
        lei = self.grupo("CONTROLE_LEI")
        self.assertEqual(Counter(p["tipo"] for p in lei), {"comum": 9, "fronteira": 3})
        for p in lei:
            self.assertEqual(p["esperado"]["rota"], "LEGISLACAO", p["id"])
            self.assertTrue(set(p["esperado"]["normasAceitas"]) <= NORMAS, p["id"])
            self.assertTrue(p["esperado"]["normasAceitas"], p["id"])

    def test_controles_de_dados(self):
        dados = self.grupo("CONTROLE_DADOS")
        self.assertEqual(Counter(p["tipo"] for p in dados), {"tratamento": 9, "dado-puro": 3})
        for p in dados:
            self.assertEqual(p["esperado"]["rota"], "DADOS", p["id"])
            if p["tipo"] == "tratamento":
                self.assertEqual(p["esperado"]["funcao"], comum.FUNCAO_TRATAMENTO)
            else:
                self.assertEqual(p["esperado"]["semFuncao"], comum.FUNCAO_TRATAMENTO)

    def test_arquivo_em_utf8_com_lf(self):
        bruto = comum.CONJUNTO.read_bytes()
        self.assertNotIn(b"\r\n", bruto)
        self.assertEqual(json.loads(bruto.decode("utf-8"))["versao"], comum.VERSAO_CONJUNTO)

    def test_regra_e_readme_citam_a_versao_e_as_margens(self):
        regra = (comum.DIR / "regra-de-decisao.md").read_text(encoding="utf-8")
        self.assertIn(comum.VERSAO_REGRA, regra)
        self.assertIn(comum.VERSAO_CONJUNTO, regra)
        for margem in (comum.EFEITO_SOMA, comum.MINIMO_FALHAS_DA_BASE, comum.MINIMO_CONTROLES_QUALIFICADOS):
            self.assertIn(str(margem), regra)
        self.assertIn("2026-11-15-comparacao-haiku-2bfb1ef3-main-fc58fa83.md", regra)
        self.assertIn("linha 51", regra)


if __name__ == "__main__":
    unittest.main()
