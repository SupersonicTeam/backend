"""Testes do refazer_erros.py (só biblioteca padrão)."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import refazer_erros as re_  # noqa: E402


def r(desfecho, marca=""):
    return {"desfecho": desfecho, "texto": marca}


class RefazerErrosTest(unittest.TestCase):
    def test_ids_com_erro(self):
        saida = [{"id": "a", "rodadas": [r("RESPONDIDA")]},
                 {"id": "b", "rodadas": [r("RESPONDIDA"), r("ERRO_MODELO")]}]
        self.assertEqual(re_.ids_com_erro(saida), ["b"])

    def test_troca_so_a_execucao_com_erro_pelo_mesmo_indice(self):
        saida = [{"id": "b", "rodadas": [r("RESPONDIDA", "1"), r("ERRO_MODELO"), r("RESPONDIDA", "3")]}]
        refeitas = [{"id": "b", "rodadas": [r("RESPONDIDA", "x"), r("CITACAO_INVALIDA", "y"), r("RESPONDIDA", "z")]}]
        mescladas, persistem = re_.mesclar(saida, refeitas)
        self.assertEqual([x["texto"] for x in mescladas[0]["rodadas"]], ["1", "y", "3"])
        self.assertEqual(persistem, [])

    def test_erro_que_persiste(self):
        saida = [{"id": "b", "rodadas": [r("ERRO_MODELO"), r("RESPONDIDA")]}]
        refeitas = [{"id": "b", "rodadas": [r("ERRO_MODELO"), r("RESPONDIDA")]}]
        _, persistem = re_.mesclar(saida, refeitas)
        self.assertEqual(persistem, ["b"])

    def test_erro_sem_refeita_persiste(self):
        saida = [{"id": "b", "rodadas": [r("ERRO_MODELO")]}]
        _, persistem = re_.mesclar(saida, [])
        self.assertEqual(persistem, ["b"])

    def test_linha_de_comando_sem_operandos_mostra_o_uso(self):
        import io
        from contextlib import redirect_stderr
        for argv in ([], ["ids"], ["mesclar", "a.json"], ["outro"]):
            with self.subTest(argv=argv), redirect_stderr(io.StringIO()):
                self.assertEqual(re_.main(argv), 2)


if __name__ == "__main__":
    unittest.main()
