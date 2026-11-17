"""A marca (CPU) das celulas locais que rodaram so em CPU: so onde ha latencia, por celula."""
import unittest

from ceres_benchmark.relatorio import (Celula, LIMITE_OBSERVACOES, _tabela_modelo_arquitetura,
                                       observacoes_md)


def celula(manifesto, arquitetura="ceres-2", modelo="qwen3:4b-instruct"):
    return Celula("c", arquitetura, "Ceres 2.0", modelo, manifesto, {})


def onde(**por_modelo):
    return {"memoria": {"ondeRodou": por_modelo}}


MODELO = "qwen3:4b-instruct"


class CelulaCpuTest(unittest.TestCase):
    def test_so_cpu_e_marcada_e_o_embedding_nao_conta(self):
        c = celula(onde(**{MODELO: ["CPU"], "bge-m3-cpu": ["CPU"]}))
        self.assertTrue(c.em_cpu)
        self.assertEqual(c.marca_latencia, " (CPU)")
        c = celula(onde(**{MODELO: ["GPU"], "bge-m3-cpu": ["CPU"]}))
        self.assertFalse(c.em_cpu)
        self.assertEqual(c.marca_latencia, "")

    def test_titulo_nao_leva_cpu_porque_o_acerto_nao_depende_de_onde_rodou(self):
        c = celula(onde(**{MODELO: ["CPU"]}))
        self.assertTrue(c.em_cpu)
        self.assertNotIn("CPU", c.titulo)
        api = celula({"provedor": "anthropic", "memoria": {"ondeRodou": {"m": ["CPU"]}}})
        self.assertFalse(api.em_cpu)
        self.assertTrue(api.titulo.endswith("(API)"))

    def test_o_chat_ausente_da_amostra_nao_e_cpu_so_pelo_embedding(self):
        # Sem o modelo de chat amostrado, so o embedding em CPU nao marca a celula.
        self.assertFalse(celula(onde(**{"bge-m3-cpu": ["CPU"]})).em_cpu)
        self.assertFalse(celula(onde(**{"outro-embedding": ["CPU"]})).em_cpu)
        self.assertFalse(celula({}).em_cpu)

    def test_dividido_e_desconhecido_nao_sao_cpu(self):
        self.assertFalse(celula(onde(**{MODELO: ["CPU", "GPU"]})).em_cpu)
        self.assertFalse(celula(onde(**{MODELO: ["dividido (40% na GPU)"]})).em_cpu)
        self.assertFalse(celula(onde(**{MODELO: ["?"]})).em_cpu)
        self.assertFalse(celula(onde(**{MODELO: []})).em_cpu)

    def test_tabela_modelo_x_arquitetura_marca_so_a_coluna_que_rodou_em_cpu(self):
        # Uma arquitetura em CPU e a outra em GPU: a marca vai so na primeira coluna de latencia.
        c1 = celula(onde(**{MODELO: ["CPU"]}), "ceres-1")
        c2 = celula(onde(**{MODELO: ["GPU"]}), "ceres-2")
        vazio = {}
        lat = {"TODAS": (1000.0, 2000.0)}
        import ceres_benchmark.relatorio as rel
        original = rel.latencias
        rel.latencias = lambda c, ids, canonico: lat
        original_faixa = rel.faixa
        try:
            rel.faixa = lambda *a, **k: type("F", (), {"formatada": lambda s: "x", "media": 1.0})()
            tabela = _tabela_modelo_arquitetura(
                [c1, c2], [MODELO], [("ceres-1", "Ceres 1"), ("ceres-2", "Ceres 2.0")],
                set(), vazio, vazio, None)
        finally:
            rel.latencias = original
            rel.faixa = original_faixa
        linha = next(l for l in tabela.splitlines() if "Latência p50" in l)
        self.assertEqual(linha.count("(CPU)"), 1)
        self.assertIn("(CPU)", linha.split("|")[3])
        self.assertNotIn("(CPU)", linha.split("|")[4])
        self.assertNotIn("(CPU)", linha.split("|")[1])
        self.assertTrue(all("(CPU)" not in l for l in tabela.splitlines() if "Latência" not in l))


class ObservacoesTest(unittest.TestCase):
    def test_vazio_nao_gera_secao(self):
        self.assertEqual(observacoes_md("  \n\n"), "")

    def test_titulos_descem_para_baixo_das_secoes_geradas(self):
        saida = observacoes_md("# A\ntexto\n## B\n### C\n###### D\n#nao-titulo")
        self.assertEqual(saida.splitlines(),
                         ["### A", "texto", "#### B", "##### C", "###### D", "#nao-titulo"])

    def test_texto_longo_e_cortado_com_aviso(self):
        saida = observacoes_md("a" * (LIMITE_OBSERVACOES + 50))
        self.assertIn("cortado", saida)
        self.assertLess(len(saida), LIMITE_OBSERVACOES + 200)


if __name__ == "__main__":
    unittest.main()
