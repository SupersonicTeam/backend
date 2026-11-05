"""Formato da entrada das ineditas de dados da equipe (#358)."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent))

from ceres_benchmark import ineditas_dados as ind  # noqa: E402


class IneditasDadosTest(unittest.TestCase):
    def _arquivo(self, texto):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        caminho = Path(d.name) / "perguntas.txt"
        caminho.write_text(texto, encoding="utf-8")
        return caminho

    def test_le_autor_data_e_texto_literal(self):
        caminho = self._arquivo("# comentario\n\n2026-11-06 | Luiz | oq eu tenho pra pagar essa semana??\n")
        self.assertEqual(ind.ler(caminho), [{"escritaEm": "2026-11-06", "autor": "Luiz",
                                             "pergunta": "oq eu tenho pra pagar essa semana??"}])

    def test_recusa_linha_fora_do_formato_data_invalida_e_repetida(self):
        for texto in ("sem autor nem data\n", "2026-13-01 | Luiz | x\n",
                      "2026-11-06 | Luiz | x\n2026-11-07 | Herick | x\n"):
            with self.assertRaises(ValueError):
                ind.ler(self._arquivo(texto))

    def test_arquivo_versionado_e_valido_e_as_que_ja_entraram_sao_literais(self):
        # Vazio ate a equipe escrever. Depois: cada pergunta do arquivo que entrou no conjunto entra
        # com o mesmo texto, autor e data.
        entradas = ind.ler()
        conjunto = json.loads((AQUI.parent / "perguntas-benchmark.json").read_text(encoding="utf-8"))
        do_conjunto = {p["pergunta"]: p for p in conjunto["perguntas"]
                       if p.get("origem") == "inedita_equipe_dados"}
        textos = {e["pergunta"] for e in entradas}
        self.assertTrue(set(do_conjunto) <= textos, "pergunta de dados fora do arquivo de entrada")
        for e in entradas:
            p = do_conjunto.get(e["pergunta"])
            if p is not None:
                self.assertEqual((p.get("autor"), p.get("escritaEm")), (e["autor"], e["escritaEm"]))


if __name__ == "__main__":
    unittest.main()
