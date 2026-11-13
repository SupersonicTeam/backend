"""O conjunto v3 (terceira medição): forma, sha256, classes, a escolha do conjunto e o quarto braço.

O v1, o v2 e os testes deles seguem como estão (`test_conjunto.py`, `test_conjunto_v2.py`). Aqui ficam
só as conferências do v3 e do que a regra ganhou com ele (versão nova da regra, quarto braço).
"""

import contextlib
import io
import json
import re
import tempfile
import unittest
from pathlib import Path

import decidir_fidelidade as dec
import executar_fidelidade as ex
import fidelidade_comum as comum
import leitura_fidelidade as lei

REGRA_MD = comum.DIR / "regra-de-decisao.md"
README_MD = comum.DIR / "README.md"
NORMAS = {"LC 214/2025", "EC 132/2023", "LC 227/2026", "Lei 8.212/1991", "Lei 9.528/1997"}
BRACOS_DA_TERCEIRA_MEDICAO = ["camada1", "camada1+juiz", "camada1+juiz+resgate"]


class ConjuntoV3Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.conjunto = comum.carregar_conjunto(comum.CONJUNTO_V3)
        cls.perguntas = cls.conjunto["perguntas"]

    def test_sha256_registrado_confere(self):
        self.assertEqual(comum.hash_registrado(comum.CONJUNTO_V3), comum.sha256_arquivo(comum.CONJUNTO_V3))

    def test_versao_propria_e_regra_nova(self):
        self.assertEqual(self.conjunto["versao"], comum.VERSAO_CONJUNTO_V3)
        self.assertNotIn(self.conjunto["versao"], (comum.VERSAO_REGRA, comum.VERSAO_REGRA_ATUAL,
                                                   comum.VERSAO_CONJUNTO_V2))
        self.assertEqual(self.conjunto["regra"], comum.VERSAO_REGRA_ATUAL)
        self.assertNotEqual(comum.VERSAO_REGRA_ATUAL, comum.VERSAO_REGRA)

    def test_trinta_e_seis_perguntas_seis_por_classe(self):
        self.assertEqual(len(self.perguntas), 36)
        for classe in comum.CLASSES:
            self.assertEqual(sum(1 for p in self.perguntas if p["classe"] == classe), 6, classe)

    def test_ids_proprios_e_sem_colisao_com_os_outros_conjuntos(self):
        anteriores = [p for caminho in (comum.CONJUNTO, comum.CONJUNTO_V2)
                      for p in comum.carregar_conjunto(caminho)["perguntas"]]
        ids = [p["id"] for p in self.perguntas]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(len({p["pergunta"] for p in self.perguntas}), 36)
        for i in ids:
            self.assertRegex(i, r"^F3-\d{2}$")
        self.assertFalse({p["id"] for p in anteriores} & set(ids))
        self.assertFalse({p["pergunta"] for p in anteriores} & {p["pergunta"] for p in self.perguntas})

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
        readme = README_MD.read_text(encoding="utf-8")
        for texto in (regra, readme):
            self.assertIn("perguntas-fidelidade-lei-v3.json", texto)
            self.assertIn("camada1+juiz+resgate", texto)
        self.assertIn(f"`{comum.VERSAO_CONJUNTO_V3}`", regra)
        self.assertIn(f"Versão da regra: `{comum.VERSAO_REGRA_ATUAL}`", regra)
        self.assertIn(f"`{comum.VERSAO_REGRA}`", regra)  # a versão do v1 e do v2 fica registrada

    def test_hash_diferente_e_recusado(self):
        with tempfile.TemporaryDirectory() as pasta:
            copia = Path(pasta) / "c.json"
            copia.write_bytes(comum.CONJUNTO_V3.read_bytes() + b" ")
            Path(str(copia) + ".sha256").write_text(comum.hash_registrado(comum.CONJUNTO_V3) + "  c.json\n")
            with self.assertRaises(comum.ErroDeConjunto):
                comum.carregar_conjunto(copia)


class EscolhaDoConjuntoV3Test(unittest.TestCase):
    def test_v3_por_nome_curto_arquivo_ou_caminho(self):
        self.assertEqual(comum.resolver_conjunto("v3"), comum.CONJUNTO_V3)
        self.assertEqual(comum.resolver_conjunto(comum.CONJUNTO_V3.name), comum.CONJUNTO_V3)
        self.assertEqual(comum.resolver_conjunto(str(comum.CONJUNTO_V3)), comum.CONJUNTO_V3)
        self.assertEqual(comum.resolver_conjunto("v1"), comum.CONJUNTO)  # o padrão não mudou
        self.assertEqual(comum.resolver_conjunto("v2"), comum.CONJUNTO_V2)

    def test_manifesto_do_v3_aponta_o_conjunto_da_rodada(self):
        manifesto = {"conjunto": {"arquivo": comum.CONJUNTO_V3.name}}
        self.assertEqual(comum.conjunto_do_manifesto(manifesto), comum.CONJUNTO_V3)

    def test_executor_planeja_o_v3(self):
        saida = io.StringIO()
        with contextlib.redirect_stdout(saida):
            self.assertEqual(ex.main(["--so-verificar", "--env-ligada", "FLAG=true",
                                      "--conjunto", "v3"]), 0)
        self.assertIn(f"conjunto {comum.VERSAO_CONJUNTO_V3} ok; 36 perguntas", saida.getvalue())


class QuartoBracoTest(unittest.TestCase):
    """O desenho da terceira medição: desligada + três braços ligados, cada um contra o desligada."""

    @staticmethod
    def _bracos():
        return ex.montar_bracos([], [], ["camada1:F=true",
                                         "camada1+juiz:F=true,J=true",
                                         "camada1+juiz+resgate:F=true,J=true,R=true"])

    def test_executor_aceita_os_quatro_bracos(self):
        self.assertEqual(list(self._bracos()), ["desligada"] + BRACOS_DA_TERCEIRA_MEDICAO)

    def test_plano_intercala_os_quatro_bracos_em_cada_pergunta(self):
        ids = [f"F3-{i:02d}" for i in range(1, 37)]
        nomes = list(self._bracos())
        plano = ex.plano_de_execucao(ids, 3, 20261112, nomes)
        self.assertEqual(len(plano), 108)
        for _, _, bracos in plano:
            self.assertEqual(sorted(bracos), sorted(nomes))

    def test_a_escolha_considera_o_quarto_braco_pela_mesma_ordem_de_desempate(self):
        def comp(nome, infiel, falsos, tokens, desfecho="ENTRA"):
            return {"desfecho": desfecho, "braco": {nome: {"infiel": infiel, "tokensMedios": tokens}},
                    "porPergunta": {"falsosDescartes": ["Q"] * falsos}}
        a, b, c = BRACOS_DA_TERCEIRA_MEDICAO
        esc = dec.escolher_entre_bracos({a: comp(a, 4, 1, 3000), b: comp(b, 3, 1, 4000),
                                         c: comp(c, 2, 3, 6000)})
        self.assertEqual(esc["escolhido"], c)  # menos INFIEL
        esc = dec.escolher_entre_bracos({a: comp(a, 2, 2, 3000), b: comp(b, 2, 1, 4000),
                                         c: comp(c, 2, 0, 6000)})
        self.assertEqual(esc["escolhido"], c)  # empate: menos falso descarte
        esc = dec.escolher_entre_bracos({a: comp(a, 2, 1, 3000), b: comp(b, 2, 1, 4000),
                                         c: comp(c, 2, 1, 2500)})
        self.assertEqual(esc["escolhido"], c)  # empate: o mais barato
        esc = dec.escolher_entre_bracos({a: comp(a, 2, 1, 3000), b: comp(b, 2, 1, 3000),
                                         c: comp(c, 2, 1, 3000)})
        self.assertEqual(esc["escolhido"], a)  # empate total: ordem alfabética
        esc = dec.escolher_entre_bracos({a: comp(a, 3, 0, 3000), b: comp(b, 3, 0, 4000),
                                         c: comp(c, 0, 0, 1, "NAO_ENTRA")})
        self.assertEqual(esc["escolhido"], a)  # só quem deu ENTRA concorre

    def test_rodada_em_disco_com_os_quatro_bracos(self):
        conjunto = comum.carregar_conjunto(comum.CONJUNTO_V3)
        nomes = ["desligada"] + BRACOS_DA_TERCEIRA_MEDICAO
        ids = [p["id"] for p in conjunto["perguntas"] if p["temBaseLegal"]][:2]
        with tempfile.TemporaryDirectory() as pasta:
            raiz = Path(pasta)
            brutas = [{"id": pid, "braco": b, "repeticao": rep, "tentativa": 1, "status": 200,
                       "rota": "LEGISLACAO", "modoFallback": False, "latenciaMs": 1000,
                       "resposta": "texto", "citacoes": [],
                       "auditoria": {"desfecho": "RESPONDIDA", "tokens_entrada": 1, "tokens_saida": 1}}
                      for pid in ids for b in nomes for rep in (1, 2, 3)]
            comum.gravar_jsonl(raiz / "respostas-brutas.jsonl", brutas)
            (raiz / "execucao.json").write_text(json.dumps({
                "conjunto": {"arquivo": comum.CONJUNTO_V3.name,
                             "sha256": comum.sha256_arquivo(comum.CONJUNTO_V3)}, "repeticoes": 3,
                "bracos": nomes, "execucoesPlanejadas": len(ids) * len(nomes) * 3,
                "interrompida": None}), encoding="utf-8")
            lotes, chave = lei.montar_lotes(brutas, conjunto, 1)
            lei.gravar_lotes(raiz, lotes, chave, ("J1", "J2"))
            (raiz / "leitura" / "chave.json").write_text(json.dumps(chave), encoding="utf-8")
            for juiz in ("J1", "J2"):
                for n, lote in enumerate(lotes, start=1):
                    itens = [{"id": i["id"], "tipoResposta": "AFIRMA", "frases": [{"rotulo": "SUPORTADA"}]}
                             for i in lote]
                    (raiz / "leitura" / "juizes" / juiz / f"lote-{n:02d}.json").write_text(
                        json.dumps({"itens": itens}), encoding="utf-8")
            r = dec.decidir_rodada(raiz)
        self.assertEqual(r["bracos"], nomes)
        self.assertEqual(set(r["comparacoes"]), set(BRACOS_DA_TERCEIRA_MEDICAO))
        self.assertEqual(r["versaoDaRegra"], comum.VERSAO_REGRA_ATUAL)
        md = dec.relatorio_md(r)
        for braco in BRACOS_DA_TERCEIRA_MEDICAO:
            self.assertIn(f"desligada × {braco}", md)


if __name__ == "__main__":
    unittest.main()
