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


    def test_plano_da_sonda_e_fixo_e_completo(self):
        ps = [{"id": f"Q{i}", "grupo": "DEFEITO"} for i in range(5)]
        a = sd.plano_da_sonda(ps, 3)
        self.assertEqual(len(a), 15)
        self.assertEqual(a, sd.plano_da_sonda(ps, 3))
        self.assertEqual(sorted(r for r, _ in a), [1] * 5 + [2] * 5 + [3] * 5)

    def test_classe_de_controle_separa_descarte_de_erro(self):
        import ressalva_comum as comum
        lei = {"id": "CL01", "grupo": "CONTROLE_LEI",
               "esperado": {"rota": "LEGISLACAO", "normasAceitas": ["LC 214/2025"]}}
        bom = {"status": 200, "rota": "LEGISLACAO", "modoFallback": False, "resposta": "Vale o art. 1º.",
               "citacoes": [{"norma": "LC 214/2025", "dispositivo": "art. 1º"}], "fontes": []}
        self.assertEqual(sd.classe_da_execucao(lei, bom), "ACERTOU")
        descarte = dict(bom, resposta="Não consegui confirmar uma resposta nos trechos de lei que encontrei.")
        self.assertEqual(sd.classe_da_execucao(lei, descarte), sd.DESCARTADO)
        self.assertEqual(sd.classe_da_execucao(lei, dict(bom, rota="DADOS")), "ERROU")
        self.assertEqual(sd.classe_da_execucao(lei, dict(bom, status=503)), comum.FALHA_TECNICA)


if __name__ == "__main__":
    unittest.main()
