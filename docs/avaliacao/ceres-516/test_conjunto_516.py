"""Confere o formato do conjunto da #516 (ceres-516-v1). Só biblioteca padrão.

    python3 -m unittest discover -s docs/avaliacao/ceres-516 -p "test_*.py"
"""

import hashlib
import json
import re
import unicodedata
import unittest
from pathlib import Path

PASTA = Path(__file__).resolve().parent
CONJUNTO = PASTA / "conjunto-516.json"
HASH = PASTA / "conjunto-516.json.sha256"

VERSAO = "ceres-516-v1"
CAMPOS = {"id", "classe", "pergunta", "esperado", "rota_esperada", "nota"}
ROTAS = {"LEGISLACAO", "DADOS", "MISTO", "FORA_DE_ESCOPO"}
CLASSES = {
    "legitima_parecida": {"prefixo": "p516", "esperado": "RESPONDER", "minimo": 20, "maximo": 30},
    "ataque": {"prefixo": "a516", "esperado": "RECUSAR", "minimo": 10, "maximo": 15},
}
# O mesmo limite do POST /api/assistente/chat.
LIMITE_PERGUNTA = 1000


def normalizar(texto):
    """Sem acento, caixa nem pontuação e com os espaços colapsados: duas grafias da mesma pergunta se igualam."""
    decomposto = unicodedata.normalize("NFKD", texto)
    sem_acento = "".join(c for c in decomposto if not unicodedata.combining(c))
    sem_pontuacao = re.sub(r"[^\w\s]", " ", sem_acento.casefold())
    return " ".join(sem_pontuacao.split())


def objeto_sem_chave_repetida(pares):
    """O json.loads guarda só a última de duas chaves iguais; aqui chave repetida é erro."""
    chaves = [chave for chave, _ in pares]
    repetidas = sorted({chave for chave in chaves if chaves.count(chave) > 1})
    if repetidas:
        raise ValueError(f"chave repetida no mesmo objeto: {repetidas}")
    return dict(pares)


class ConjuntoCeres516Test(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.bruto = CONJUNTO.read_bytes()
        cls.dados = json.loads(cls.bruto.decode("utf-8"), object_pairs_hook=objeto_sem_chave_repetida)
        cls.perguntas = cls.dados["perguntas"]

    def test_bytes_em_utf8_sem_bom_e_com_lf(self):
        self.assertFalse(self.bruto.startswith(b"\xef\xbb\xbf"), "conjunto com BOM")
        self.assertNotIn(b"\r", self.bruto, "conjunto com CRLF: o hash é dos bytes com LF")

    def test_hash_confere_com_os_bytes(self):
        partes = HASH.read_text(encoding="ascii").split()
        self.assertEqual(2, len(partes), "o .sha256 tem o formato '<hash>  conjunto-516.json'")
        esperado, nome = partes
        self.assertEqual(CONJUNTO.name, nome)
        self.assertRegex(esperado, r"^[0-9a-f]{64}$")
        self.assertEqual(esperado, hashlib.sha256(self.bruto).hexdigest(),
                         "o conjunto mudou: suba a versão e regrave o .sha256")

    def test_versao(self):
        self.assertEqual(VERSAO, self.dados["versao"])

    def test_cada_pergunta_tem_exatamente_os_campos(self):
        self.assertIsInstance(self.perguntas, list)
        for pergunta in self.perguntas:
            with self.subTest(id=pergunta.get("id")):
                self.assertEqual(CAMPOS, set(pergunta))
                for campo in CAMPOS:
                    self.assertIsInstance(pergunta[campo], str, campo)

    def test_ids_nao_se_repetem(self):
        ids = [p["id"] for p in self.perguntas]
        repetidos = sorted({i for i in ids if ids.count(i) > 1})
        self.assertEqual([], repetidos)

    def test_id_no_formato_da_classe_e_em_sequencia(self):
        for classe, regra in CLASSES.items():
            with self.subTest(classe=classe):
                numeros = []
                for pergunta in (p for p in self.perguntas if p["classe"] == classe):
                    casou = re.fullmatch(regra["prefixo"] + r"-([0-9]{2})", pergunta["id"])
                    self.assertIsNotNone(casou, f"{pergunta['id']} fora do formato {regra['prefixo']}-NN")
                    numeros.append(int(casou.group(1)))
                self.assertEqual(list(range(1, len(numeros) + 1)), numeros,
                                 "ids da classe fora de ordem ou com buraco")

    def test_classe_esperado_e_rota_coerentes(self):
        for pergunta in self.perguntas:
            with self.subTest(id=pergunta["id"]):
                self.assertIn(pergunta["classe"], CLASSES)
                self.assertEqual(CLASSES[pergunta["classe"]]["esperado"], pergunta["esperado"])
                self.assertIn(pergunta["rota_esperada"], ROTAS)
                if pergunta["classe"] == "ataque":
                    self.assertEqual("FORA_DE_ESCOPO", pergunta["rota_esperada"])
                else:
                    self.assertNotEqual("FORA_DE_ESCOPO", pergunta["rota_esperada"])

    def test_textos_preenchidos(self):
        for pergunta in self.perguntas:
            with self.subTest(id=pergunta["id"]):
                texto = pergunta["pergunta"]
                self.assertEqual(texto.strip(), texto)
                self.assertTrue(texto)
                self.assertLessEqual(len(texto), LIMITE_PERGUNTA)
                self.assertTrue(pergunta["nota"].strip())
                self.assertNotIn("\n", pergunta["nota"], "a nota tem uma linha")

    def test_perguntas_nao_se_repetem(self):
        vistas = {}
        for pergunta in self.perguntas:
            chave = normalizar(pergunta["pergunta"])
            with self.subTest(id=pergunta["id"]):
                self.assertNotIn(chave, vistas, f"mesma pergunta de {vistas.get(chave)}")
            vistas[chave] = pergunta["id"]

    def test_quantidade_por_classe(self):
        for classe, regra in CLASSES.items():
            with self.subTest(classe=classe):
                quantas = sum(1 for p in self.perguntas if p["classe"] == classe)
                self.assertGreaterEqual(quantas, regra["minimo"])
                self.assertLessEqual(quantas, regra["maximo"])


if __name__ == "__main__":
    unittest.main()
