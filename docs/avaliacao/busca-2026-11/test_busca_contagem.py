"""Testes do busca_contagem.py sobre saídas fictícias (só biblioteca padrão).

Rodar: python -m unittest discover -s docs/avaliacao/busca-2026-11 -p "test_*.py"
"""

import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import busca_contagem as bc  # noqa: E402


def rodada(desfecho="RESPONDIDA", citacoes=()):
    return {"cru": "", "ms": 10, "desfecho": desfecho, "texto": "", "citacoes": list(citacoes)}


def entregue(posicao, norma, dispositivo, similaridade=0.5):
    return {"posicao": posicao, "norma": norma, "dispositivo": dispositivo, "similaridade": similaridade}


def item(pid, esperado=(), aceito=(), entregues=(), rodadas=()):
    return {
        "id": pid,
        "pergunta": "pergunta fictícia",
        "esperado": list(esperado),
        "aceito": list(aceito),
        "entregues": list(entregues),
        "rodadas": list(rodadas),
    }


class ChaveTest(unittest.TestCase):
    def test_corta_na_primeira_virgula(self):
        self.assertEqual(bc.chave("LC 214/2025 art. 4º, § 2º, inciso I"), "LC 214/2025 art. 4º")

    def test_tira_parentese_final(self):
        self.assertEqual(
            bc.chave("LC 227/2026 art. 174 (redação do art. 6º)"),
            "LC 227/2026 art. 174 (redação do art. 6º",
        )

    def test_redacao_com_paragrafo(self):
        self.assertEqual(
            bc.chave("EC 132/2023 art. 1º (redação do art. 156-A, § 1º, inciso III)"),
            "EC 132/2023 art. 1º (redação do art. 156-A",
        )

    def test_anexo(self):
        self.assertEqual(bc.chave("LC 214/2025 Anexo IX, item 7"), "LC 214/2025 Anexo IX")

    def test_espacos_extras(self):
        self.assertEqual(bc.chave("  Lei 8.212/1991   art. 25 ,  inciso I "), "Lei 8.212/1991 art. 25")

    def test_chave_de_norma_e_dispositivo(self):
        self.assertEqual(bc.chave_de("Lei 8.212/1991", "art. 30, inciso X"), "Lei 8.212/1991 art. 30")

    def test_artigo_sem_virgula_fica_igual(self):
        self.assertEqual(bc.chave("LC 214/2025 art. 43"), "LC 214/2025 art. 43")


class RotuloRodadaTest(unittest.TestCase):
    aceitaveis = {"LC 214/2025 art. 12", "LC 227/2026 art. 174 (redação do art. 12"}

    def test_erro_modelo(self):
        self.assertEqual(bc.rotulo_rodada(rodada("ERRO_MODELO"), self.aceitaveis), bc.ERRO)

    def test_outro_desfecho_e_descarte(self):
        for desfecho in ("DESCARTADA", "SEM_TRECHO", "CITACAO_INVALIDA", None, ""):
            with self.subTest(desfecho=desfecho):
                self.assertEqual(bc.rotulo_rodada(rodada(desfecho), self.aceitaveis), bc.DESCARTE)

    def test_descarte_mesmo_citando_o_artigo_certo(self):
        r = rodada("DESCARTADA", ["LC 214/2025 art. 12, § 2º"])
        self.assertEqual(bc.rotulo_rodada(r, self.aceitaveis), bc.DESCARTE)

    def test_certa_pelo_esperado(self):
        r = rodada(citacoes=["LC 214/2025 art. 12, § 2º, inciso III"])
        self.assertEqual(bc.rotulo_rodada(r, self.aceitaveis), bc.CERTA)

    def test_certa_pelo_aceito(self):
        r = rodada(citacoes=["LC 227/2026 art. 174 (redação do art. 12, § 2º)"])
        self.assertEqual(bc.rotulo_rodada(r, self.aceitaveis), bc.CERTA)

    def test_certa_com_uma_citacao_entre_outras(self):
        r = rodada(citacoes=["LC 214/2025 art. 13", "LC 214/2025 art. 12"])
        self.assertEqual(bc.rotulo_rodada(r, self.aceitaveis), bc.CERTA)

    def test_outro_artigo(self):
        r = rodada(citacoes=["LC 214/2025 art. 120"])
        self.assertEqual(bc.rotulo_rodada(r, self.aceitaveis), bc.OUTRO_ARTIGO)

    def test_prefixo_nao_conta(self):
        r = rodada(citacoes=["LC 214/2025 art. 120, § 1º"])
        self.assertEqual(bc.rotulo_rodada(r, {"LC 214/2025 art. 12"}), bc.OUTRO_ARTIGO)

    def test_respondida_sem_citacao(self):
        self.assertEqual(bc.rotulo_rodada(rodada(citacoes=[]), self.aceitaveis), bc.OUTRO_ARTIGO)

    def test_respondida_em_pergunta_ausente(self):
        r = rodada(citacoes=["LC 214/2025 art. 4º"])
        self.assertEqual(bc.rotulo_rodada(r, set()), bc.OUTRO_ARTIGO)


class MaioriaTest(unittest.TestCase):
    def test_maioria_simples(self):
        self.assertEqual(bc.rotulo_maioria([bc.CERTA, bc.CERTA, bc.DESCARTE]), bc.CERTA)

    def test_unanime(self):
        self.assertEqual(bc.rotulo_maioria([bc.DESCARTE] * 3), bc.DESCARTE)

    def test_tres_diferentes(self):
        self.assertEqual(bc.rotulo_maioria([bc.CERTA, bc.DESCARTE, bc.ERRO]), bc.SEM_MAIORIA)

    def test_empate_em_par(self):
        self.assertEqual(bc.rotulo_maioria([bc.CERTA, bc.OUTRO_ARTIGO]), bc.SEM_MAIORIA)

    def test_sem_rodadas(self):
        self.assertEqual(bc.rotulo_maioria([]), bc.SEM_MAIORIA)

    def test_uma_rodada(self):
        self.assertEqual(bc.rotulo_maioria([bc.ERRO]), bc.ERRO)


class BuscaTest(unittest.TestCase):
    def test_posicao_1_e_top5(self):
        entregues = [entregue(1, "LC 214/2025", "art. 12, § 2º"), entregue(2, "LC 214/2025", "art. 13")]
        self.assertEqual(bc.busca(entregues, {"LC 214/2025 art. 12"}), {"top5": True, "pos1": True})

    def test_ordena_pela_posicao(self):
        entregues = [entregue(2, "LC 214/2025", "art. 12"), entregue(1, "LC 214/2025", "art. 13")]
        self.assertEqual(bc.busca(entregues, {"LC 214/2025 art. 12"}), {"top5": True, "pos1": False})

    def test_quinta_posicao_entra(self):
        entregues = [entregue(i, "LC 214/2025", f"art. {100 + i}") for i in range(1, 5)]
        entregues.append(entregue(5, "LC 214/2025", "art. 12, inciso I"))
        self.assertEqual(bc.busca(entregues, {"LC 214/2025 art. 12"}), {"top5": True, "pos1": False})

    def test_sexta_posicao_fica_fora(self):
        entregues = [entregue(i, "LC 214/2025", f"art. {100 + i}") for i in range(1, 6)]
        entregues.append(entregue(6, "LC 214/2025", "art. 12"))
        self.assertEqual(bc.busca(entregues, {"LC 214/2025 art. 12"}), {"top5": False, "pos1": False})

    def test_sem_entregues(self):
        self.assertEqual(bc.busca([], {"LC 214/2025 art. 12"}), {"top5": False, "pos1": False})

    def test_sem_chave_a_procurar(self):
        self.assertIsNone(bc.busca([entregue(1, "LC 214/2025", "art. 12")], set()))

    def test_redacao_da_lc_227_nos_entregues(self):
        entregues = [entregue(1, "LC 227/2026", "art. 174 (redação do art. 12, § 2º)")]
        chaves = {"LC 227/2026 art. 174 (redação do art. 12"}
        self.assertEqual(bc.busca(entregues, chaves), {"top5": True, "pos1": True})


class AvaliarPerguntaTest(unittest.TestCase):
    def test_busca_separa_esperado_de_aceitavel(self):
        r = bc.avaliar_pergunta(
            item(
                "busca-01",
                esperado=["LC 214/2025 art. 8º"],
                aceito=["LC 214/2025 art. 79"],
                entregues=[entregue(1, "LC 214/2025", "art. 79, § 1º"), entregue(2, "LC 214/2025", "art. 8º")],
                rodadas=[rodada(citacoes=["LC 214/2025 art. 79"])] * 3,
            )
        )
        self.assertEqual(r["rotulo"], bc.CERTA)
        self.assertEqual(r["busca_esperado"], {"top5": True, "pos1": False})
        self.assertEqual(r["busca_aceitavel"], {"top5": True, "pos1": True})

    def test_ausente_respondida_e_descartada(self):
        r = bc.avaliar_pergunta(
            item(
                "busca-31",
                entregues=[entregue(1, "LC 214/2025", "art. 4º")],
                rodadas=[rodada("SEM_TRECHO"), rodada(citacoes=["LC 214/2025 art. 4º"]), rodada("SEM_TRECHO")],
            )
        )
        self.assertEqual(r["rodadas"], [bc.DESCARTE, bc.OUTRO_ARTIGO, bc.DESCARTE])
        self.assertEqual(r["rotulo"], bc.DESCARTE)
        self.assertIsNone(r["busca_esperado"])
        self.assertIsNone(r["busca_aceitavel"])


CONJUNTO = {
    "versao": "teste",
    "perguntas": [
        {"id": "busca-01", "pergunta": "a", "esperado": ["LC 214/2025 art. 12"], "aceito": [], "raciocinio": "", "grupo": "geral"},
        {"id": "busca-02", "pergunta": "b", "esperado": ["LC 214/2025 art. 271"], "aceito": [], "raciocinio": "", "grupo": "setor"},
        {"id": "busca-03", "pergunta": "c", "esperado": [], "aceito": [], "raciocinio": "", "grupo": "ausente"},
    ],
}


def saida_ficticia():
    return [
        item(
            "busca-01",
            esperado=["LC 214/2025 art. 12"],
            entregues=[entregue(1, "LC 214/2025", "art. 12, § 2º")],
            rodadas=[rodada(citacoes=["LC 214/2025 art. 12"]), rodada(citacoes=["LC 214/2025 art. 12"]), rodada("ERRO_MODELO")],
        ),
        item(
            "busca-02",
            esperado=["LC 214/2025 art. 271"],
            entregues=[entregue(1, "LC 214/2025", "art. 6º, inciso XI"), entregue(2, "LC 214/2025", "art. 271")],
            rodadas=[rodada(citacoes=["LC 214/2025 art. 6º"])] * 3,
        ),
        item("busca-03", rodadas=[rodada("SEM_TRECHO")] * 3),
    ]


class ContarTest(unittest.TestCase):
    def test_totais_e_grupos(self):
        rel = bc.contar(saida_ficticia(), CONJUNTO)
        total = rel["total"]
        self.assertEqual(total["perguntas"], 3)
        self.assertEqual(total["rotulo_pergunta"][bc.CERTA], 1)
        self.assertEqual(total["rotulo_pergunta"][bc.OUTRO_ARTIGO], 1)
        self.assertEqual(total["rotulo_pergunta"][bc.DESCARTE], 1)
        self.assertEqual(total["rotulo_rodada"][bc.ERRO], 1)
        self.assertEqual(total["rotulo_rodada"][bc.CERTA], 2)
        self.assertEqual(total["busca_esperado"], {"perguntas": 2, "top5": 2, "pos1": 1})
        self.assertEqual(rel["por_grupo"]["ausente"]["rotulo_pergunta"][bc.DESCARTE], 1)
        self.assertEqual(rel["por_grupo"]["setor"]["busca_esperado"], {"perguntas": 1, "top5": 1, "pos1": 0})
        self.assertEqual(rel["faltando"], [])

    def test_sem_conjunto_nao_agrupa(self):
        rel = bc.contar(saida_ficticia())
        self.assertNotIn("por_grupo", rel)
        self.assertEqual(rel["perguntas"][0]["grupo"], None)

    def test_esperado_divergente_do_conjunto(self):
        saida = saida_ficticia()
        saida[0]["esperado"] = ["LC 214/2025 art. 13"]
        with self.assertRaises(bc.ConjuntoDivergente):
            bc.contar(saida, CONJUNTO)

    def test_aceito_divergente_do_conjunto(self):
        saida = saida_ficticia()
        saida[1]["aceito"] = ["LC 214/2025 art. 272"]
        with self.assertRaises(bc.ConjuntoDivergente):
            bc.contar(saida, CONJUNTO)

    def test_id_fora_do_conjunto(self):
        saida = saida_ficticia() + [item("busca-99")]
        with self.assertRaises(bc.ConjuntoDivergente):
            bc.contar(saida, CONJUNTO)

    def test_pergunta_faltando_na_saida(self):
        rel = bc.contar(saida_ficticia()[:2], CONJUNTO)
        self.assertEqual(rel["faltando"], ["busca-03"])


class MainTest(unittest.TestCase):
    def _gravar(self, pasta, nome, dados):
        caminho = os.path.join(pasta, nome)
        with open(caminho, "w", encoding="utf-8") as f:
            json.dump(dados, f, ensure_ascii=False)
        return caminho

    def test_linha_de_comando(self):
        with tempfile.TemporaryDirectory() as pasta:
            saida = self._gravar(pasta, "saida.json", saida_ficticia())
            conjunto = self._gravar(pasta, "conjunto.json", CONJUNTO)
            relatorio = os.path.join(pasta, "relatorio.json")
            out = io.StringIO()
            with redirect_stdout(out):
                codigo = bc.main([saida, "--conjunto", conjunto, "--json", relatorio])
            self.assertEqual(codigo, 0)
            self.assertIn("busca-01\tgeral\tcerta\tcerta/certa/erro\ttop5=s pos1=s", out.getvalue())
            self.assertIn("busca-03\tausente\tdescarte\tdescarte/descarte/descarte\t-", out.getvalue())
            with open(relatorio, encoding="utf-8") as f:
                self.assertEqual(json.load(f)["total"]["perguntas"], 3)

    def test_linha_de_comando_recusa_divergencia(self):
        with tempfile.TemporaryDirectory() as pasta:
            dados = saida_ficticia()
            dados[0]["esperado"] = []
            saida = self._gravar(pasta, "saida.json", dados)
            conjunto = self._gravar(pasta, "conjunto.json", CONJUNTO)
            err = io.StringIO()
            with redirect_stderr(err), redirect_stdout(io.StringIO()):
                codigo = bc.main([saida, "--conjunto", conjunto])
            self.assertEqual(codigo, 2)
            self.assertIn("busca-01", err.getvalue())


if __name__ == "__main__":
    unittest.main()
