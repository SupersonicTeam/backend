"""A sondagem de uma passada: classificação e resumo (sem rede)."""

import unittest

import sondar_defeito as sd


def ex(texto, rota="LEGISLACAO", status=200):
    return {"id": "RS01", "status": status, "resposta": texto, "rota": rota, "modoFallback": False}


class SondagemTest(unittest.TestCase):
    def test_resumo_conta_classes_e_rotas(self):
        r = sd.resumo([
            ex("Redução de 60%. Confira com seu contador se a sua venda atende à condição.", "DADOS"),
            ex("Redução de 60% pelo art. 125."),
            ex("Redução de 60%. Vale ver com a assessoria contábil."),
            ex("Não consegui localizar a base legal para responder isso."),
            ex(None, status=503),
        ])
        self.assertEqual(r["classes"], {"OK": 1, "DEFEITO": 1 + 1, "SEM_TRATAMENTO": 1, "FALHA_TECNICA": 1})

        self.assertEqual(r["porRotaEClasse"]["LEGISLACAO/DEFEITO"], 2)
        # "Vale ver com a assessoria contábil" cita a contabilidade, mas sem verbo de conferência: o detector
        # a deixa como DEFEITO e a sondagem a lista para a leitura humana.
        self.assertEqual(r["mencionaContadorSemRessalva"], ["RS01"])


    def test_descarte_da_fidelidade_nao_e_defeito(self):
        texto = ("Não consegui confirmar uma resposta nos trechos de lei que encontrei. "
                 "Base legal: LC 214/2025, art. 137 (60%). Confirme com o contador.")
        self.assertEqual(sd.classificar(ex(texto))[0], sd.DESCARTADO)
        self.assertEqual(sd.classificar(ex("Não consegui localizar a base legal."))[0], "SEM_TRATAMENTO")


if __name__ == "__main__":
    unittest.main()
