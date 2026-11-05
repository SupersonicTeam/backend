"""Aviso de semelhanca com o treino (#352): nao bloqueia, so lista para a revisao humana."""

import json
import sys
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent))

from ceres_benchmark import similaridade as sim  # noqa: E402


class SimilaridadeTest(unittest.TestCase):
    def test_jaccard_ignora_acento_caixa_e_palavra_vazia(self):
        self.assertEqual(sim.jaccard("Quanto tô devendo?", "quanto to devendo"), 1.0)
        self.assertEqual(sim.jaccard("de a o", "o a de"), 0.0)
        self.assertAlmostEqual(sim.jaccard("quanto comprei adubo", "quanto comprei semente"), 0.5)

    def test_pares_acima_do_limiar_do_mais_parecido(self):
        perguntas = [{"id": "N1", "tema": "linguagem", "pergunta": "quanto to devendo?"},
                     {"id": "N2", "tema": "compras", "pergunta": "quanto comprei de adubo"}]
        treino = [("t.json", "Quanto eu estou devendo de imposto?"), ("t.json", "quanto to devendo")]
        pares = sim.pares_semelhantes(perguntas, treino, temas={"linguagem"})
        self.assertEqual([(p["id"], p["jaccard"]) for p in pares][0], ("N1", 1.0))
        self.assertTrue(all(p["id"] == "N1" for p in pares))
        self.assertIn("| N1 | linguagem |", sim.markdown(pares))
        self.assertIn("Nenhum par acima do limiar", sim.markdown([]))

    def test_le_o_treino_real_do_repositorio(self):
        raiz = AQUI.parents[3]
        treino = sim.textos_de_treino(raiz)
        self.assertGreater(len(treino), 100)
        conjunto = json.loads((AQUI.parent / "perguntas-benchmark.json").read_text(encoding="utf-8"))
        # So confere que roda sobre o conjunto real; o resultado e para a revisao humana.
        self.assertIsInstance(sim.pares_semelhantes(conjunto["perguntas"], treino), list)


if __name__ == "__main__":
    unittest.main()
