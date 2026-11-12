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

    def test_valor_desconhecido_e_recusado_com_mensagem_clara(self):
        for valor in ("V2", "v3", "nao-existe.json"):
            with self.assertRaises(comum.ErroDeConjunto, msg=valor):
                comum.resolver_conjunto(valor)

    def test_manifesto_guarda_nome_na_pasta_e_caminho_fora_dela(self):
        self.assertEqual(ex.nome_do_conjunto(comum.CONJUNTO_V2), comum.CONJUNTO_V2.name)
        with tempfile.TemporaryDirectory() as pasta:
            fora = Path(pasta) / "meu.json"
            fora.write_text("{}", encoding="utf-8")
            guardado = ex.nome_do_conjunto(fora)
            self.assertEqual(Path(guardado), fora.resolve())
            self.assertEqual(comum.conjunto_do_manifesto({"conjunto": {"arquivo": guardado}}), fora.resolve())


class RodadaDoV2Test(unittest.TestCase):
    """A leitura usa o conjunto do manifesto: a rodada do v2 não pode ser lida contra o v1."""

    def _rodada(self, pasta: Path, arquivo):
        manifesto = {"conjunto": {"sha256": "x"}}
        if arquivo:
            manifesto["conjunto"]["arquivo"] = arquivo
        (pasta / "execucao.json").write_text(json.dumps(manifesto), encoding="utf-8")
        resposta = {"id": "F2-01", "braco": "desligada", "repeticao": 1, "tentativa": 1, "status": 200,
                    "modoFallback": False, "rota": "LEGISLACAO", "resposta": "texto", "citacoes": [],
                    "auditoria": {"desfecho": "RESPONDIDA", "rota": "LEGISLACAO"}}
        comum.gravar_jsonl(pasta / "respostas-brutas.jsonl", [resposta])

    def test_lotes_da_rodada_do_v2_levam_a_pergunta_do_v2(self):
        import leitura_fidelidade as leitura
        pergunta = comum.perguntas_por_id(comum.carregar_conjunto(comum.CONJUNTO_V2))["F2-01"]["pergunta"]
        with tempfile.TemporaryDirectory() as dir_:
            pasta = Path(dir_)
            self._rodada(pasta, comum.CONJUNTO_V2.name)
            with contextlib.redirect_stdout(io.StringIO()):
                leitura.comando_lotes(pasta)
            lote = json.loads((pasta / "leitura" / "lotes" / "lote-01.json").read_text(encoding="utf-8"))
            self.assertEqual(lote[0]["pergunta"], pergunta)

    def test_lotes_sem_manifesto_param_com_mensagem(self):
        import leitura_fidelidade as leitura
        with tempfile.TemporaryDirectory() as dir_:
            pasta = Path(dir_)
            comum.gravar_jsonl(pasta / "respostas-brutas.jsonl", [])
            with self.assertRaises(SystemExit):
                leitura.comando_lotes(pasta)

    def test_decisao_le_o_conjunto_do_manifesto(self):
        import decidir_fidelidade as dec
        with tempfile.TemporaryDirectory() as dir_:
            pasta = Path(dir_)
            (pasta / "leitura").mkdir()
            (pasta / "leitura" / "chave.json").write_text("{}", encoding="utf-8")
            self._rodada(pasta, comum.CONJUNTO_V2.name)
            # o sha256 do manifesto não é o do v2: a decisão recusa pelo conjunto do manifesto
            with self.assertRaises(dec.RodadaIncompleta) as erro:
                dec.decidir_rodada(pasta)
            self.assertIn("conjunto", str(erro.exception))


if __name__ == "__main__":
    unittest.main()
