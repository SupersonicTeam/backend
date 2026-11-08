"""Testes do decisao.py sobre saídas fictícias (só biblioteca padrão).

Rodar: python -m unittest discover -s docs/avaliacao/busca-2026-11 -p "test_*.py"
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import decisao as dc  # noqa: E402

GRUPOS = (["geral"] * 6) + (["setor"] * 2) + (["ausente"] * 2)
IDS = [f"q{i:02d}" for i in range(1, len(GRUPOS) + 1)]
CONJUNTO = {
    "perguntas": [
        {"id": pid, "pergunta": "x", "esperado": ([] if g == "ausente" else ["LC 214/2025 art. 1º"]),
         "aceito": [], "raciocinio": "", "grupo": g}
        for pid, g in zip(IDS, GRUPOS)
    ]
}
ENTREGUES = [{"posicao": 1, "norma": "LC 214/2025", "dispositivo": "art. 1º", "similaridade": 0.7}]

CITACAO = {"certa": ["LC 214/2025 art. 1º"], "outro_artigo": ["LC 214/2025 art. 99"]}


def rodada(rotulo):
    if rotulo == "descarte":
        return {"desfecho": "CITACAO_INVALIDA", "citacoes": [], "cru": "", "ms": 1, "texto": ""}
    if rotulo == "erro":
        return {"desfecho": "ERRO_MODELO", "citacoes": [], "cru": "", "texto": ""}
    return {"desfecho": "RESPONDIDA", "citacoes": CITACAO[rotulo], "cru": "", "ms": 1, "texto": ""}


def saida(rotulos, entregues=ENTREGUES):
    itens = []
    for pid, rot in zip(IDS, rotulos):
        p = next(x for x in CONJUNTO["perguntas"] if x["id"] == pid)
        itens.append({"id": pid, "pergunta": "x", "esperado": p["esperado"], "aceito": [],
                      "entregues": entregues, "rodadas": [rodada(rot)] * 3})
    return itens


# B: q01-q03 certa, q04-q06 outro_artigo, q07-q08 (setor) certa, q09-q10 (ausente) descarte.
BASE = ["certa"] * 3 + ["outro_artigo"] * 3 + ["certa"] * 2 + ["descarte"] * 2


class DecisaoTest(unittest.TestCase):
    def test_variante_que_passa(self):
        c = list(BASE)
        c[3] = c[4] = c[5] = "certa"  # tres outro_artigo viram certa
        r = dc.decidir(CONJUNTO, saida(BASE), saida(BASE), saida(c), saida(BASE))
        self.assertEqual(r["ruido"]["n"], 0)
        self.assertEqual(r["C"]["saldo_certa"], 3)
        self.assertTrue(r["C"]["passa"])
        self.assertFalse(r["D"]["passa"])
        self.assertEqual(r["decisao"], "C")

    def test_saldo_dois_nao_passa(self):
        c = list(BASE)
        c[3] = c[4] = "certa"
        r = dc.decidir(CONJUNTO, saida(BASE), saida(BASE), saida(c), saida(BASE))
        self.assertFalse(r["C"]["condicoes"]["a_saldo_certa_ge_3"])
        self.assertEqual(r["decisao"], "nenhuma")

    def test_setor_que_perde_certa_reprova(self):
        c = list(BASE)
        c[3] = c[4] = c[5] = "certa"
        c[6] = "descarte"
        c[0] = "certa"
        r = dc.decidir(CONJUNTO, saida(BASE), saida(BASE), saida(c), saida(BASE))
        self.assertEqual(r["C"]["setor_perde_certa"], ["q07"])
        self.assertFalse(r["C"]["passa"])

    def test_ausente_que_passa_a_responder_reprova(self):
        c = list(BASE)
        c[3] = c[4] = c[5] = "certa"
        c[8] = "outro_artigo"
        r = dc.decidir(CONJUNTO, saida(BASE), saida(BASE), saida(c), saida(BASE))
        self.assertEqual(r["C"]["ausente_passa_a_responder"], ["q09"])
        self.assertFalse(r["C"]["condicoes"]["d_nenhuma_ausente_passa_a_responder"])

    def test_outro_artigo_que_sobe_reprova(self):
        c = list(BASE)
        c[0] = c[1] = "outro_artigo"
        c[3] = c[4] = c[5] = "certa"
        # saldo 3 - 2 = 1; outro_artigo: entram 2, saem 3 -> (b) ok, (a) falha
        r = dc.decidir(CONJUNTO, saida(BASE), saida(BASE), saida(c), saida(BASE))
        self.assertTrue(r["C"]["condicoes"]["b_outro_artigo_nao_sobe"])
        self.assertFalse(r["C"]["condicoes"]["a_saldo_certa_ge_3"])

    def test_saldo_igual_ao_ruido_reprova(self):
        a = list(BASE)
        a[0] = a[1] = a[2] = "descarte"  # ruido 3 entre A e B
        c = list(BASE)
        c[3] = c[4] = c[5] = "certa"
        r = dc.decidir(CONJUNTO, saida(a), saida(BASE), saida(c), saida(BASE))
        self.assertEqual(r["ruido"]["n"], 3)
        self.assertFalse(r["C"]["condicoes"]["e_saldo_maior_que_ruido"])

    def test_d_ganha_de_c_por_dois(self):
        base =["descarte"] * 6 + ["certa"] * 2 + ["descarte"] * 2
        c = ["certa"] * 3 + ["descarte"] * 3 + ["certa"] * 2 + ["descarte"] * 2
        d = ["certa"] * 5 + ["descarte"] * 1 + ["certa"] * 2 + ["descarte"] * 2
        r = dc.decidir(CONJUNTO, saida(base), saida(base), saida(c), saida(d))
        self.assertTrue(r["C"]["passa"])
        self.assertTrue(r["D"]["passa"])
        self.assertEqual(r["decisao"], "D")

    def test_d_ganha_por_um_fica_c(self):
        base = ["descarte"] * 6 + ["certa"] * 2 + ["descarte"] * 2
        c = ["certa"] * 3 + ["descarte"] * 3 + ["certa"] * 2 + ["descarte"] * 2
        d = ["certa"] * 4 + ["descarte"] * 2 + ["certa"] * 2 + ["descarte"] * 2
        r = dc.decidir(CONJUNTO, saida(base), saida(base), saida(c), saida(d))
        self.assertEqual(r["decisao"], "C")

    def test_d_com_mais_outro_artigo_fica_c(self):
        base = ["outro_artigo"] * 6 + ["certa"] * 2 + ["descarte"] * 2
        c = ["certa"] * 3 + ["descarte"] * 3 + ["certa"] * 2 + ["descarte"] * 2
        d = ["certa"] * 5 + ["outro_artigo"] + ["certa"] * 2 + ["descarte"] * 2
        r = dc.decidir(CONJUNTO, saida(base), saida(base), saida(c), saida(d))
        self.assertTrue(r["D"]["passa"])
        self.assertEqual(r["decisao"], "C")

    def test_entregues_diferentes_param(self):
        outro = [{"posicao": 1, "norma": "LC 214/2025", "dispositivo": "art. 2º", "similaridade": 0.7}]
        r = dc.decidir(CONJUNTO, saida(BASE), saida(BASE, outro), saida(BASE), saida(BASE))
        self.assertEqual(len(r["entregues_a_b_diferentes"]), len(IDS))
        self.assertTrue(r["decisao"].startswith("parar"))
        self.assertNotIn("C", r)

    def test_excluida_some_de_tudo(self):
        a = list(BASE)
        a[0] = "descarte"
        r = dc.decidir(CONJUNTO, saida(a), saida(BASE), excluir={"q01"})
        self.assertEqual(r["ruido"]["n"], 0)
        self.assertNotIn("q01", r["rotulos"]["A"])

    def test_braco_sem_uma_pergunta_e_recusado(self):
        c = saida(BASE)[1:]
        with self.assertRaises(dc.IdsDivergentes) as erro:
            dc.decidir(CONJUNTO, saida(BASE), saida(BASE), c, saida(BASE))
        self.assertIn("braço C", str(erro.exception))
        self.assertIn("q01", str(erro.exception))

    def test_pergunta_repetida_e_recusada(self):
        d = saida(BASE) + saida(BASE)[:1]
        with self.assertRaises(dc.IdsDivergentes):
            dc.decidir(CONJUNTO, saida(BASE), saida(BASE), saida(BASE), d)

    def test_excluida_pode_faltar_num_braco(self):
        b = saida(BASE)[1:]
        r = dc.decidir(CONJUNTO, saida(BASE), b, excluir={"q01"})
        self.assertEqual(r["entregues_a_b_diferentes"], [])

    def test_linha_de_comando_sai_com_4(self):
        import io
        import json
        import tempfile
        from contextlib import redirect_stderr, redirect_stdout
        with tempfile.TemporaryDirectory() as pasta:
            caminhos = {}
            for nome, dados in (("conj", CONJUNTO), ("a", saida(BASE)), ("b", saida(BASE)[1:])):
                caminhos[nome] = os.path.join(pasta, nome + ".json")
                with open(caminhos[nome], "w", encoding="utf-8") as f:
                    json.dump(dados, f)
            with redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()):
                codigo = dc.main(["--conjunto", caminhos["conj"], "--a", caminhos["a"], "--b", caminhos["b"]])
            self.assertEqual(codigo, 4)

    def test_sem_maioria_nao_conta_como_certa(self):
        c = list(BASE)
        c[3] = c[4] = c[5] = "certa"
        saida_c = saida(c)
        saida_c[3]["rodadas"] = [rodada("certa"), rodada("descarte"), rodada("erro")]
        r = dc.decidir(CONJUNTO, saida(BASE), saida(BASE), saida_c, saida(BASE))
        self.assertEqual(r["rotulos"]["C"]["q04"], "sem_maioria")
        self.assertEqual(r["C"]["saldo_certa"], 2)


if __name__ == "__main__":
    unittest.main()
