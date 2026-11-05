"""Criterios automaticos das perguntas externas (#358), corte pelo teto e CSV cego."""

import csv
import io
import json
import random
import sys
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent))

from ceres_benchmark import externas as ext  # noqa: E402


def resultado(texto, desfecho="RESPONDIDA", rota="LEGISLACAO", citacao_valida=None, **partes):
    return {"citacaoValida": citacao_valida,
            "execucao": {"texto": texto, "desfecho": desfecho, "rota": rota, "citacoes": [], **partes}}


class CriteriosTest(unittest.TestCase):
    def test_classe_de_tratamento_dita(self):
        self.assertTrue(ext.classe_dita("Tem alíquota zero de IBS e CBS.", "ZERO"))
        self.assertTrue(ext.classe_dita("A redução é de 60% nas alíquotas.", "REDUCAO_60"))
        self.assertFalse(ext.classe_dita("A redução é de 60%.", "ZERO"))
        self.assertTrue(ext.classe_dita("Não tem direito à redução: vale a alíquota padrão.", "PADRAO"))
        self.assertTrue(ext.classe_dita("Depende do NCM do produto.", "CONDICIONAL"))
        self.assertTrue(ext.classe_dita("In natura tem redução de 60%; a carne tem alíquota zero.", "CONDICIONAL"))
        self.assertFalse(ext.classe_dita("Tem redução de 60%.", "CONDICIONAL"))
        self.assertTrue(ext.classe_dita("Converse com o seu contador.", "FORA_DO_ESCOPO_SISTEMA"))
        self.assertIsNone(ext.classe_dita("qualquer coisa", "NAO_SE_APLICA"))

    def test_avaliar_externa(self):
        pergunta = {"classeTratamento": "PADRAO", "dispositivosEsperados": ["LC 214/2025, art. 138"],
                    "afirmacoesProibidas": [r"\bsim,? (voc[eê] )?pode aproveitar a redu[cç][aã]o\b"],
                    "encaminharContador": True}
        certo = resultado("Não: fora do Anexo IX vale a alíquota padrão. Confirme com o seu contador.",
                          citacao_valida=True)
        self.assertEqual(ext.avaliar(certo, pergunta),
                         {"fundamentacao": True, "classe": True, "proibidas": True, "encaminhamento": True})
        errado = resultado("Sim, você pode aproveitar a redução de 60%.", citacao_valida=False)
        self.assertEqual(ext.avaliar(errado, pergunta),
                         {"fundamentacao": False, "classe": False, "proibidas": False, "encaminhamento": False})

    def test_a_conferir_e_pendente_nao_medem(self):
        pergunta = {"classeTratamento": "CONDICIONAL", "dispositivosEsperados": [], "aConferir": True,
                    "pendenteContador": "#284, pergunta 2"}
        self.assertEqual(ext.avaliar(resultado("Depende."), pergunta),
                         {"fundamentacao": None, "classe": None, "proibidas": None, "encaminhamento": None})

    def test_negacao_nao_afirma(self):
        self.assertFalse(ext.afirma(r"pode aproveitar o cr[eé]dito", "Você não pode aproveitar o crédito."))
        self.assertTrue(ext.afirma(r"pode aproveitar o cr[eé]dito", "Você pode aproveitar o crédito."))
        self.assertFalse(ext.classe_dita("Não tem alíquota zero: a redução é de 60%.", "ZERO"))
        self.assertTrue(ext.classe_dita("Não tem alíquota zero: a redução é de 60%.", "REDUCAO_60"))

    def test_lista_de_todas_as_classes_nao_acerta_uma(self):
        texto = "Pode ter alíquota zero, redução de 60% ou alíquota padrão."
        self.assertFalse(ext.classe_dita(texto, "ZERO"))
        self.assertTrue(ext.classe_dita(texto, "CONDICIONAL"))

    def test_resposta_nao_redigida_nao_ganha_credito(self):
        pergunta = {"classeTratamento": "PADRAO", "afirmacoesProibidas": [r"pode aproveitar"],
                    "encaminharContador": True}
        recusa = resultado("Não consigo responder; procure o seu contador. Alíquota padrão.",
                           desfecho="FORA_DE_ESCOPO", citacao_valida=None)
        self.assertEqual(ext.avaliar(recusa, pergunta),
                         {"fundamentacao": None, "classe": False, "proibidas": False, "encaminhamento": False})

    def test_corte_por_tipo_de_chamada(self):
        self.assertTrue(ext.cortada(resultado("x", "RESPOSTA_CORTADA", "FORA_DE_ESCOPO"))["classificacao"])
        self.assertTrue(ext.cortada(resultado("x", "RESPOSTA_CORTADA", "LEGISLACAO"))["lei"])
        misto = ext.cortada(resultado("x", "RESPONDIDA", "MISTO", desfechoDados="RESPOSTA_CORTADA"))
        self.assertEqual((misto["funcoes"], misto["lei"], misto["qualquer"]), (True, False, True))
        self.assertFalse(ext.cortada(resultado("x"))["qualquer"])


class CsvCegoTest(unittest.TestCase):
    def test_csv_cego_das_externas_com_chave_separada(self):
        from ceres_benchmark.relatorio import Celula
        canonico = {"E01": {"pergunta": "Pergunta externa?", "origem": "inedita_externa_ia"},
                    "D01": {"pergunta": "Do gabarito?", "origem": "gabarito"}}
        rodada = [{"id": "E01", "execucao": {"texto": "Resposta da E01"}},
                  {"id": "D01", "execucao": {"texto": "nao entra"}}]
        celulas = [Celula("ceres-1--m", "ceres-1", "Ceres 1", "m", {}, {}, [rodada]),
                   Celula("ceres-2--m", "ceres-2", "Ceres 2.0", "m", {}, {}, [rodada])]
        texto, chave = ext.csv_cego(celulas, canonico, random.Random(1))
        self.assertTrue(texto.startswith("\ufeff"))
        linhas = list(csv.reader(io.StringIO(texto[1:]), delimiter=";"))
        self.assertEqual(linhas[0][:3], ["codigo", "pergunta", "resposta"])
        self.assertIn("precisao_final_1a5", linhas[0])
        self.assertEqual(len(linhas), 3)
        self.assertTrue(all("ceres" not in ";".join(l) for l in linhas))
        self.assertEqual({v["celula"] for v in json.loads(chave).values()}, {"ceres-1--m", "ceres-2--m"})

    def test_csv_cego_protege_formula_e_pula_vazia_e_contaminada(self):
        from ceres_benchmark.relatorio import Celula
        canonico = {"E01": {"pergunta": "=HYPERLINK(1)", "origem": "inedita_externa_ia"},
                    "E02": {"pergunta": "Vazia?", "origem": "inedita_externa_ia"},
                    "E03": {"pergunta": "Contaminada?", "origem": "inedita_externa_ia", "contaminada": True}}
        rodada = [{"id": "E01", "execucao": {"texto": "-1 resposta", "desfecho": "RESPONDIDA"}},
                  {"id": "E02", "execucao": {"texto": "  ", "desfecho": "RESPONDIDA"}},
                  {"id": "E03", "execucao": {"texto": "algo", "desfecho": "RESPONDIDA"}}]
        texto, _ = ext.csv_cego([Celula("c", "ceres-2", "Ceres 2.0", "m", {}, {}, [rodada])],
                                canonico, random.Random(1))
        linhas = list(csv.reader(io.StringIO(texto[1:]), delimiter=";"))
        self.assertEqual(len(linhas), 2)
        self.assertEqual(linhas[1][1:3], ["'=HYPERLINK(1)", "'-1 resposta"])



class SqlConferenciaTest(unittest.TestCase):
    def test_sql_versionado_e_o_gerado_pelo_conjunto(self):
        conjunto = json.loads((AQUI.parent / "perguntas-benchmark.json").read_text(encoding="utf-8"))
        gerado = ext.sql_conferencia(conjunto)
        versionado = (AQUI.parent / "conferir-dispositivos-externas.sql").read_text(encoding="utf-8")
        self.assertEqual(versionado, gerado, "regere o .sql: python3 -c 'from ceres_benchmark ...'")
        chaves = {c for p in conjunto["perguntas"] if p.get("origem") == "inedita_externa_ia"
                  for c in p.get("dispositivosEsperados") or []}
        self.assertTrue(all(f"('{c}'" in gerado for c in chaves))

    def test_aspas_escapadas(self):
        sql = ext.sql_conferencia({"perguntas": [{"origem": "inedita_externa_ia", "id": "X",
                                                  "dispositivosEsperados": ["LC 1/2000, art. 1º d'x"]}]})
        self.assertIn("'art. 1º d''x'", sql)


class AchadosReviewDelta361Test(unittest.TestCase):
    """Regressoes do review do delta do #361 (#368)."""

    def test_aliquota_padrao_como_base_da_reducao_nao_e_padrao(self):
        texto = "Sim, esse insumo tem redução de 60% da alíquota padrão (Anexo IX)."
        self.assertTrue(ext.classe_dita(texto, "REDUCAO_60"))
        self.assertFalse(ext.classe_dita(texto, "CONDICIONAL"))
        self.assertTrue(ext.classe_dita("Não tem redução: vale a alíquota padrão.", "PADRAO"))

    def test_negacao_de_outra_oracao_nao_apaga_a_afirmacao(self):
        proibida = r"\b(poder[aá]|pode) aproveitar (integralmente |normalmente )?(o|os|esse|esses) cr[eé]ditos?\b"
        self.assertTrue(ext.afirma(proibida, "Mesmo que o insumo não seja tributado, você pode "
                                             "aproveitar o crédito do IBS e da CBS."))
        self.assertTrue(ext.classe_dita("Os produtos hortícolas não processados têm alíquota zero.", "ZERO"))
        self.assertTrue(ext.classe_dita("Isso não muda nada. Alíquota zero para o ovo.", "ZERO"))
        self.assertFalse(ext.afirma(proibida, "Você não pode aproveitar o crédito."))

    def test_varia_conforme_e_condicional_mas_conforme_o_artigo_nao(self):
        self.assertTrue(ext.classe_dita("A alíquota varia conforme o produto: o milho em grão tem "
                                        "redução de 60%.", "CONDICIONAL"))
        self.assertFalse(ext.classe_dita("Tem redução de 60%, conforme o art. 138.", "CONDICIONAL"))

    def test_descartar_o_contador_nao_e_encaminhar(self):
        pergunta = {"encaminharContador": True}
        self.assertFalse(ext.avaliar(resultado("Você não precisa procurar o contador: a regra é a "
                                               "mesma para todos."), pergunta)["encaminhamento"])
        self.assertTrue(ext.avaliar(resultado("Procure o seu contador."), pergunta)["encaminhamento"])

    def test_fundamentacao_de_texto_descartado_nao_conta_nas_duas_arquiteturas(self):
        # O AvaliadorResposta do 2463c27 da citacaoValida=true a uma CITACAO_INVALIDA com citacao.
        for desfecho in ("CITACAO_INVALIDA", "NUMERO_SEM_FONTE", "RESPOSTA_CORTADA"):
            self.assertFalse(ext.avaliar(resultado("x", desfecho, citacao_valida=True), {})["fundamentacao"])
        self.assertTrue(ext.avaliar(resultado("x", citacao_valida=True), {})["fundamentacao"])

    def test_sql_aceita_espaco_depois_da_chave_como_o_cita_algum(self):
        sql = ext.sql_conferencia({"perguntas": [{"origem": "inedita_externa_ia", "id": "X",
                                                  "dispositivosEsperados": ["LC 214/2025, art. 138"]}]})
        self.assertIn("LIKE c.dispositivo || ' %'", sql)

    def test_ineditas_de_dados_aceita_arquivo_com_bom(self):
        import tempfile
        from ceres_benchmark import ineditas_dados as ind
        with tempfile.TemporaryDirectory() as d:
            caminho = Path(d) / "perguntas.txt"
            caminho.write_bytes("\ufeff# cabecalho\r\n2026-11-06 | Luiz | oq vence?\r\n".encode("utf-8"))
            self.assertEqual([e["pergunta"] for e in ind.ler(caminho)], ["oq vence?"])


if __name__ == "__main__":
    unittest.main()
