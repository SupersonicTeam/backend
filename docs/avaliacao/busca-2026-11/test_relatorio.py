"""Testes do relatorio.py sobre saídas fictícias (só biblioteca padrão)."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import relatorio as rl  # noqa: E402

CONJUNTO = {"perguntas": [
    {"id": "g1", "grupo": "geral", "esperado": ["LC 214/2025 art. 12"], "aceito": []},
    {"id": "a1", "grupo": "ausente", "esperado": [], "aceito": []},
]}


def item(pid, rodadas, entregues=()):
    return {"id": pid, "rodadas": rodadas, "entregues": list(entregues)}


class RelatorioTest(unittest.TestCase):
    def test_precisao_e_cobertura_por_execucao(self):
        saida = [
            item("g1", [
                {"desfecho": "RESPONDIDA", "citacoes": ["LC 214/2025 art. 12, § 2º"], "ms": 1000},
                {"desfecho": "RESPONDIDA", "citacoes": ["LC 214/2025 art. 99"], "ms": 2000},
                {"desfecho": "REGIME_ESPECIFICO_SEM_SETOR", "citacoes": [], "ms": 3000},
            ]),
            item("a1", [{"desfecho": "ERRO_MODELO"}, {"desfecho": "SEM_FUNDAMENTO_LEGAL", "ms": 1}]),
        ]
        c = rl.por_execucao(saida, CONJUNTO, set())
        self.assertEqual(c["geral"]["precisao"], 0.5)
        self.assertEqual(c["geral"]["cobertura"], round(2 / 3, 3))
        self.assertEqual(c["geral"]["guarda"], 1)
        self.assertEqual(c["ausente"]["erro"], 1)
        self.assertEqual(c["ausente"]["cobertura"], 0.0)
        self.assertEqual(c["total"]["execucoes"], 5)

    def test_tempo_so_da_primeira_repeticao(self):
        saida = [item("g1", [{"ms": 1000}, {"ms": 9000}]), item("a1", [{"ms": 3000}])]
        t = rl.tempo_primeira(saida, set())
        self.assertEqual(t["n"], 2)
        self.assertEqual(t["mediana_s"], 2.0)

    def test_top5_mudou(self):
        e1 = [{"norma": "LC 214/2025", "dispositivo": f"art. {i}"} for i in range(1, 7)]
        e2 = e1[:5] + [{"norma": "LC 214/2025", "dispositivo": "art. 99"}]
        e3 = [e1[1], e1[0]] + e1[2:]
        base = [item("g1", [], e1), item("a1", [], e1)]
        outra = [item("g1", [], e2), item("a1", [], e3)]
        self.assertEqual(rl.top5_mudou(base, outra, set()), ["a1"])

    def test_tempo_vem_da_saida_bruta(self):
        import json
        import tempfile
        mesclada = [item("g1", [{"desfecho": "RESPONDIDA", "citacoes": [], "ms": 99000}]),
                    item("a1", [{"desfecho": "SEM_FUNDAMENTO_LEGAL", "ms": 2000}])]
        bruta = [item("g1", [{"desfecho": "ERRO_MODELO"}]),
                 item("a1", [{"desfecho": "SEM_FUNDAMENTO_LEGAL", "ms": 2000}])]
        with tempfile.TemporaryDirectory() as pasta:
            for nome, dados in (("A-citacoes.json", mesclada), ("A-citacoes-bruto.json", bruta)):
                with open(os.path.join(pasta, nome), "w", encoding="utf-8") as f:
                    json.dump(dados, f)
            rel = rl.montar(CONJUNTO, pasta, set())
        self.assertEqual(rel["tempo_primeira_repeticao"]["A"], {"n": 1, "mediana_s": 2.0, "media_s": 2.0, "total_s": 2})

    def test_linha_de_comando_com_braco_divergente_sai_com_4(self):
        import io
        import json
        import tempfile
        from contextlib import redirect_stderr, redirect_stdout
        completa = [item("g1", [{"desfecho": "SEM_FUNDAMENTO_LEGAL", "ms": 1}]),
                    item("a1", [{"desfecho": "SEM_FUNDAMENTO_LEGAL", "ms": 1}])]
        for p in completa:
            p["esperado"] = next(x for x in CONJUNTO["perguntas"] if x["id"] == p["id"])["esperado"]
            p["aceito"] = []
        with tempfile.TemporaryDirectory() as pasta:
            conj = os.path.join(pasta, "conj.json")
            with open(conj, "w", encoding="utf-8") as f:
                json.dump(CONJUNTO, f)
            for nome, dados in (("A-citacoes.json", completa), ("B-citacoes.json", completa[:1])):
                with open(os.path.join(pasta, nome), "w", encoding="utf-8") as f:
                    json.dump(dados, f)
            with redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()):
                codigo = rl.main(["--conjunto", conj, "--resultados", pasta])
        self.assertEqual(codigo, 4)


if __name__ == "__main__":
    unittest.main()
