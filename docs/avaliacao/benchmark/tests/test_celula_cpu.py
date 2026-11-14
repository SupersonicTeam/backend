"""A marca (CPU) das celulas locais que rodaram so em CPU, simetrica a (API)."""
import unittest

from ceres_benchmark.relatorio import Celula


def celula(manifesto):
    return Celula("c", "ceres-2", "Ceres 2.0", "qwen3:4b-instruct", manifesto, {})


class CelulaCpuTest(unittest.TestCase):
    def test_so_cpu_e_marcada_e_o_embedding_nao_conta(self):
        c = celula({"memoria": {"ondeRodou": {"qwen3:4b-instruct": ["CPU"], "bge-m3-cpu": ["CPU"]}}})
        self.assertTrue(c.em_cpu)
        self.assertTrue(c.titulo.endswith("(CPU)"))
        c = celula({"memoria": {"ondeRodou": {"qwen3:4b-instruct": ["GPU"], "bge-m3-cpu": ["CPU"]}}})
        self.assertFalse(c.em_cpu)
        self.assertNotIn("(CPU)", c.titulo)

    def test_dividido_sem_amostra_e_api_nao_sao_cpu(self):
        self.assertFalse(celula({"memoria": {"ondeRodou": {"m": ["CPU", "GPU"]}}}).em_cpu)
        self.assertFalse(celula({}).em_cpu)
        api = celula({"provedor": "anthropic", "memoria": {"ondeRodou": {"m": ["CPU"]}}})
        self.assertFalse(api.em_cpu)
        self.assertTrue(api.titulo.endswith("(API)"))


if __name__ == "__main__":
    unittest.main()
