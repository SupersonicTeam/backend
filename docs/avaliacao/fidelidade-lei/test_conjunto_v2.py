"""O conjunto v2 (segunda medição): forma, sha256, classes, e o parâmetro que escolhe o conjunto.

O v1 e os testes dele seguem como estão (`test_conjunto.py`). Aqui ficam só as conferências do v2 e
do mecanismo que deixa os scripts aceitarem um ou outro conjunto.
"""

import contextlib
import io
import json
import re
import tempfile
import unittest
from pathlib import Path

import executar_fidelidade as ex
import fidelidade_comum as comum

REGRA_MD = comum.DIR / "regra-de-decisao.md"
NORMAS = {"LC 214/2025", "EC 132/2023", "LC 227/2026", "Lei 8.212/1991", "Lei 9.528/1997"}


class ConjuntoV2Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.conjunto = comum.carregar_conjunto(comum.CONJUNTO_V2)
        cls.perguntas = cls.conjunto["perguntas"]

    def test_sha256_registrado_confere(self):
        self.assertEqual(comum.hash_registrado(comum.CONJUNTO_V2), comum.sha256_arquivo(comum.CONJUNTO_V2))

    def test_versao_propria_e_regra_a_mesma(self):
        self.assertEqual(self.conjunto["versao"], comum.VERSAO_CONJUNTO_V2)
        self.assertNotEqual(self.conjunto["versao"], comum.VERSAO_REGRA)
        self.assertEqual(self.conjunto["regra"], comum.VERSAO_REGRA)

    def test_trinta_e_seis_perguntas_seis_por_classe(self):
        self.assertEqual(len(self.perguntas), 36)
        for classe in comum.CLASSES:
            self.assertEqual(sum(1 for p in self.perguntas if p["classe"] == classe), 6, classe)

    def test_ids_proprios_e_sem_colisao_com_o_v1(self):
        v1 = {p["id"] for p in comum.carregar_conjunto()["perguntas"]}
        ids = [p["id"] for p in self.perguntas]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(len({p["pergunta"] for p in self.perguntas}), 36)
        for i in ids:
            self.assertRegex(i, r"^F2-\d{2}$")
        self.assertFalse(v1 & set(ids))

    def test_perguntas_com_base_tem_fatos_e_dispositivos(self):
        for p in self.perguntas:
            if not p["temBaseLegal"]:
                continue
            self.assertTrue(any(f["essencial"] for f in p["fatos"]), p["id"])
            self.assertTrue(p["armadilhas"], p["id"])
            ids = [f["id"] for f in p["fatos"]]
            self.assertEqual(len(ids), len(set(ids)), p["id"])
            for f in p["fatos"]:
                self.assertTrue(f["afirmacao"].strip() and f["dispositivos"], (p["id"], f["id"]))
                for d in f["dispositivos"]:
                    self.assertIn(d["norma"], NORMAS)
                    self.assertTrue(d["dispositivo"].strip())

    def test_ausentes_nao_tem_fato_e_dizem_o_que_nao_afirmar(self):
        for p in self.perguntas:
            if p["classe"] == "AUSENTE":
                self.assertFalse(p["temBaseLegal"], p["id"])
                self.assertEqual(p["fatos"], [], p["id"])
                self.assertTrue(p["naoPodeAfirmar"] and p["comportamentoCorreto"], p["id"])
            else:
                self.assertTrue(p["temBaseLegal"], p["id"])

    def test_perguntas_na_linguagem_do_produtor(self):
        for p in self.perguntas:
            self.assertLessEqual(len(p["pergunta"]), 300, p["id"])  # o chat aceita 1000
            self.assertIn("?", p["pergunta"], p["id"])

    def test_texto_do_conjunto_leva_acento(self):
        # só os textos escritos para humanos: o nome da classe (EXCECAO) e os ids ficam de fora
        partes = []
        for p in self.perguntas:
            partes += [p["pergunta"], p.get("comportamentoCorreto", ""), p.get("assuntoAusente", ""),
                       p.get("observacaoDoGabarito", "")]
            partes += p.get("armadilhas", []) + p.get("permitidos", []) + p.get("naoPodeAfirmar", [])
            partes += [f["afirmacao"] for f in p["fatos"]]
        texto = "\n".join(partes)
        for erro in (r"\bnao\b", r"\bsao\b", r"contribuicao", r"excecao", r"regiao"):
            self.assertIsNone(re.search(erro, texto, re.I), erro)

    def test_registrado_na_regra_e_no_readme(self):
        regra = REGRA_MD.read_text(encoding="utf-8")
        readme = (comum.DIR / "README.md").read_text(encoding="utf-8")
        self.assertIn("perguntas-fidelidade-lei-v2.json", regra)
        self.assertIn(f"`{comum.VERSAO_CONJUNTO_V2}`", regra)
        self.assertIn("perguntas-fidelidade-lei-v2.json", readme)
        self.assertIn(f"`{comum.VERSAO_REGRA}`", regra)  # a regra não mudou

    def test_hash_diferente_e_recusado(self):
        with tempfile.TemporaryDirectory() as pasta:
            copia = Path(pasta) / "c.json"
            copia.write_bytes(comum.CONJUNTO_V2.read_bytes() + b" ")
            Path(str(copia) + ".sha256").write_text(comum.hash_registrado(comum.CONJUNTO_V2) + "  c.json\n")
            with self.assertRaises(comum.ErroDeConjunto):
                comum.carregar_conjunto(copia)


class EscolhaDoConjuntoTest(unittest.TestCase):
    def test_sem_valor_e_o_v1(self):
        self.assertEqual(comum.resolver_conjunto(None), comum.CONJUNTO)
        self.assertEqual(comum.resolver_conjunto("v1"), comum.CONJUNTO)

    def test_v2_por_nome_curto_arquivo_ou_caminho(self):
        self.assertEqual(comum.resolver_conjunto("v2"), comum.CONJUNTO_V2)
        self.assertEqual(comum.resolver_conjunto(comum.CONJUNTO_V2.name), comum.CONJUNTO_V2)
        self.assertEqual(comum.resolver_conjunto(str(comum.CONJUNTO_V2)), comum.CONJUNTO_V2)

    def test_manifesto_antigo_sem_arquivo_vale_o_v1(self):
        self.assertEqual(comum.conjunto_do_manifesto({"conjunto": {"sha256": "x"}}), comum.CONJUNTO)
        self.assertEqual(comum.conjunto_do_manifesto({}), comum.CONJUNTO)

    def test_manifesto_com_arquivo_aponta_o_conjunto_da_rodada(self):
        manifesto = {"conjunto": {"arquivo": comum.CONJUNTO_V2.name}}
        self.assertEqual(comum.conjunto_do_manifesto(manifesto), comum.CONJUNTO_V2)

    def test_executor_planeja_o_v2_e_o_v1_continua_o_padrao(self):
        saida = io.StringIO()
        with contextlib.redirect_stdout(saida):
            self.assertEqual(ex.main(["--so-verificar", "--env-ligada", "FLAG=true",
                                      "--conjunto", "v2"]), 0)
        self.assertIn(f"conjunto {comum.VERSAO_CONJUNTO_V2} ok; 36 perguntas", saida.getvalue())
        saida = io.StringIO()
        with contextlib.redirect_stdout(saida):
            self.assertEqual(ex.main(["--so-verificar", "--env-ligada", "FLAG=true"]), 0)
        self.assertIn(f"conjunto {comum.VERSAO_REGRA} ok; 36 perguntas", saida.getvalue())


if __name__ == "__main__":
    unittest.main()
