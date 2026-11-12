"""O conjunto congelado: forma, sha256, classes e coerência com a regra."""

import json
import re
import unittest
from pathlib import Path

import fidelidade_comum as comum

REGRA_MD = comum.DIR / "regra-de-decisao.md"


class ConjuntoTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.conjunto = comum.carregar_conjunto()
        cls.perguntas = cls.conjunto["perguntas"]

    def test_sha256_registrado_confere(self):
        self.assertEqual(comum.hash_registrado(comum.CONJUNTO), comum.sha256_arquivo(comum.CONJUNTO))

    def test_hash_diferente_e_recusado(self):
        import tempfile
        with tempfile.TemporaryDirectory() as pasta:
            copia = Path(pasta) / "c.json"
            copia.write_bytes(comum.CONJUNTO.read_bytes() + b" ")
            Path(str(copia) + ".sha256").write_text(comum.hash_registrado(comum.CONJUNTO) + "  c.json\n")
            with self.assertRaises(comum.ErroDeConjunto):
                comum.carregar_conjunto(copia)

    def test_trinta_e_seis_perguntas_seis_por_classe(self):
        self.assertEqual(len(self.perguntas), 36)
        for classe in comum.CLASSES:
            self.assertEqual(sum(1 for p in self.perguntas if p["classe"] == classe), 6, classe)

    def test_ids_e_perguntas_unicos(self):
        self.assertEqual(len({p["id"] for p in self.perguntas}), 36)
        self.assertEqual(len({p["pergunta"] for p in self.perguntas}), 36)
        for p in self.perguntas:
            self.assertRegex(p["id"], r"^FL\d{2}$")

    def test_perguntas_com_base_tem_fatos_e_dispositivos(self):
        for p in self.perguntas:
            if p["temBaseLegal"]:
                self.assertTrue(p["fatos"], p["id"])
                self.assertTrue(any(f["essencial"] for f in p["fatos"]), p["id"])
                self.assertTrue(p["armadilhas"], p["id"])
                for f in p["fatos"]:
                    self.assertTrue(f["afirmacao"].strip() and f["dispositivos"], (p["id"], f["id"]))
                    ids = [x["id"] for x in p["fatos"]]
                    self.assertEqual(len(ids), len(set(ids)), p["id"])
                    for d in f["dispositivos"]:
                        self.assertIn(d["norma"], {"LC 214/2025", "EC 132/2023", "LC 227/2026",
                                                  "Lei 8.212/1991", "Lei 9.528/1997"})
                        self.assertTrue(d["dispositivo"].strip())

    def test_ausentes_nao_tem_fato_e_dizem_o_que_nao_afirmar(self):
        ausentes = [p for p in self.perguntas if p["classe"] == "AUSENTE"]
        for p in ausentes:
            self.assertFalse(p["temBaseLegal"], p["id"])
            self.assertEqual(p["fatos"], [], p["id"])
            self.assertTrue(p["naoPodeAfirmar"] and p["comportamentoCorreto"], p["id"])
        self.assertTrue(all(p["temBaseLegal"] for p in self.perguntas if p["classe"] != "AUSENTE"))

    def test_perguntas_na_linguagem_do_produtor(self):
        for p in self.perguntas:
            self.assertLessEqual(len(p["pergunta"]), 300, p["id"])  # o chat aceita 1000
            self.assertIn("?", p["pergunta"], p["id"])

    def test_texto_do_conjunto_leva_acento(self):
        # Texto escrito por humano para humano: as palavras mais comuns sem acento denunciam ASCII.
        texto = json.dumps(self.conjunto, ensure_ascii=False)
        for erro in (r"nao", r"sao", r"contribuicao", r"excecao", r"regiao"):
            self.assertIsNone(re.search(erro, texto, re.I), erro)

    def test_versao_da_regra_igual_a_do_conjunto_e_do_documento(self):
        self.assertEqual(self.conjunto["versao"], comum.VERSAO_REGRA)
        self.assertIn(f"`{comum.VERSAO_REGRA}`", REGRA_MD.read_text(encoding="utf-8"))


class RegraTest(unittest.TestCase):
    """Os números do código são os do documento: mudar um sem o outro quebra."""

    def setUp(self):
        self.texto = REGRA_MD.read_text(encoding="utf-8")

    def test_margens_do_documento(self):
        r = comum.REGRA
        esperados = [
            ("**8** execuções", r["defeitosMinimosNaBase"] == 8),
            ("queda de **25%**", r["melhoraRelativaMinima"] == 0.25),
            ("pelo menos **4** execuções", r["melhoraAbsolutaMinima"] == 4),
            ("**IC de 80%**", r["icConfianca"] == 0.80),
            ("semente fixa 20261112", r["bootstrapSemente"] == 20261112),
            ("10.000 reamostragens", r["bootstrapReamostragens"] == 10_000),
            ("**3** perguntas", r["falsoDescarteMaximo"] == 3),
            ("**5 pontos percentuais**", r["recusaFalsaAltaMaxima"] == 0.05),
            ("**2,0 ×**", r["p50Fator"] == 2.0 and r["p95Fator"] == 2.0 and r["tokensFator"] == 2.0),
            ("**10 segundos**", r["p95TetoMs"] == 10_000),
            ("decisão do Murillo, de 2026-11-12", True),
            ("mais de **10%**", r["falhaTecnicaMaxima"] == 0.10),
            ("menor que 0,40", r["kappaMinimo"] == 0.40),
            ("passar de **30%**", r["desacordoMaximo"] == 0.30),
        ]
        for trecho, confere in esperados:
            self.assertIn(trecho, self.texto, trecho)
            self.assertTrue(confere, trecho)

    def test_juizes_fixados_no_documento(self):
        self.assertEqual(comum.JUIZES, {"J1": "opus", "J2": "sonnet", "J3": "opus"})
        self.assertIn("**J1 = `opus`**, **J2 = `sonnet`**, **J3 = `opus`**", self.texto)
        self.assertNotIn("fable", self.texto.lower().split("## 5.")[0].split("4.1")[1])
        self.assertNotIn("haiku", " ".join(comum.JUIZES.values()))


if __name__ == "__main__":
    unittest.main()
