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

    def test_celula_csv_protege_todos_os_prefixos_de_formula_do_csvcego(self):
        for prefixo in ("=", "+", "-", "@", "\t", "\r"):
            with self.subTest(prefixo=repr(prefixo)):
                self.assertEqual(ext._celula_csv(prefixo + "x"), "'" + prefixo + "x")
        # Texto comum, vazio e prefixo so no meio nao ganham o apostrofo.
        for valor in ("texto", "", "a=1", "a\tb", " =1"):
            with self.subTest(valor=valor):
                self.assertEqual(ext._celula_csv(valor), valor)

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

    def test_casos_do_segundo_review(self):
        casos = [("Não há redução de 60% para esse produto.", "PADRAO", True),
                 ("O adubo não terá direito à alíquota zero.", "ZERO", False),
                 ("Para o trator a redução não se aplica e vale a alíquota cheia.", "PADRAO", True),
                 ("Sobre a venda do trator incide a alíquota padrão.", "PADRAO", True),
                 ("Não há, neste caso, alíquota zero.", "ZERO", False),
                 ("A alíquota de 26,5% é a alíquota padrão.", "PADRAO", True),
                 ("A alíquota não varia: é zero para todos.", "CONDICIONAL", False)]
        for texto, classe, esperado in casos:
            self.assertEqual(ext.classe_dita(texto, classe), esperado, texto)
        self.assertTrue(ext.encaminha("Você não precisa pagar nada agora, mas procure o contador."))
        self.assertTrue(ext.encaminha("Sem precisar de nada, fale com seu contador."))
        self.assertFalse(ext.classe_dita("Você não precisa procurar o contador: a regra é a mesma.",
                                         "FORA_DO_ESCOPO_SISTEMA"))

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

    def test_conjuntos_da_regua_sao_os_mesmos_do_java(self):
        """Sem deriva: ``DESCARTADAS`` e ``FUNCOES_COM_CITACAO_FIXA`` lidos do fonte do AvaliadorResposta."""
        import re
        from pathlib import Path
        fonte = (Path(__file__).resolve().parents[4] / "src/main/java/br/com/agrocontabil/config/avaliacao"
                 / "AvaliadorResposta.java").read_text(encoding="utf-8")
        funcoes = re.search(r"FUNCOES_COM_CITACAO_FIXA\s*=\s*Set\.of\(([^)]*)\)", fonte).group(1)
        self.assertEqual(set(re.findall(r'"(\w+)"', funcoes)), ext.FUNCOES_COM_CITACAO_FIXA)
        bloco = re.search(r"RESPOSTA_DESCARTADA\s*=\s*EnumSet\.of\((.*?)\);", fonte, re.DOTALL).group(1)
        self.assertEqual(set(re.findall(r"DesfechoInteracao\.(\w+)", bloco)), ext.DESCARTADAS)

    def test_regime_especifico_sem_setor_e_descartado_como_no_java(self):
        self.assertFalse(ext.citacao_vale(resultado("x", "REGIME_ESPECIFICO_SEM_SETOR", citacao_valida=True)))

    def test_fidelidade_reprovada_e_descartada_como_no_java(self):
        # #269: a lei reprovada na conferencia de fidelidade mostra so os trechos, sem o texto.
        self.assertFalse(ext.citacao_vale(resultado("x", "FIDELIDADE_REPROVADA", citacao_valida=True)))

    def test_misto_com_lei_descartada_so_vale_com_funcao_de_citacao_fixa(self):
        # #433: pergunta e funcoes ficticias; mesma tabela do AvaliadorRespostaTest.
        for lei in ("NUMERO_SEM_FONTE", "CITACAO_INVALIDA", "RESPOSTA_CORTADA", "REGIME_ESPECIFICO_SEM_SETOR"):
            sem = resultado("x", rota="MISTO", citacao_valida=True, desfechoLegislacao=lei,
                            fontes=[{"funcao": "consultarNota"}])
            self.assertFalse(ext.citacao_vale(sem), lei)
            self.assertFalse(ext.citacao_vale(resultado("x", rota="MISTO", citacao_valida=True,
                                                        desfechoLegislacao=lei)), lei)
            for funcao in ("consultarRetencoes", "consultarTratamentoProduto"):
                com = resultado("x", rota="MISTO", citacao_valida=True, desfechoLegislacao=lei,
                                fontes=[{"funcao": "consultarNota"}, {"funcao": funcao}])
                self.assertTrue(ext.citacao_vale(com), (lei, funcao))

    def test_misto_com_afirmacao_sobre_dados_so_vale_com_funcao_de_citacao_fixa(self):
        base = dict(rota="MISTO", citacao_valida=True, desfechoLegislacao="AFIRMACAO_SOBRE_DADOS")
        self.assertFalse(ext.citacao_vale(resultado("x", "AFIRMACAO_SOBRE_DADOS", **base,
                                                    fontes=[{"funcao": "consultarNota"}])))
        for funcao in ("consultarRetencoes", "consultarTratamentoProduto"):
            self.assertTrue(ext.citacao_vale(resultado("x", "AFIRMACAO_SOBRE_DADOS", **base,
                                                       fontes=[{"funcao": funcao}])))

    def test_misto_com_lei_respondida_ou_nula_segue_a_regra_anterior(self):
        for lei in ("RESPONDIDA", None):
            self.assertTrue(ext.citacao_vale(resultado("x", rota="MISTO", citacao_valida=True,
                                                       desfechoLegislacao=lei,
                                                       fontes=[{"funcao": "consultarNota"}])))
        # desfecho geral descartado pesa mesmo com funcao fixa e a lei sem fundamento
        self.assertFalse(ext.citacao_vale(resultado("x", "NUMERO_SEM_FONTE", rota="MISTO", citacao_valida=True,
                                                    desfechoLegislacao="SEM_FUNDAMENTO_LEGAL",
                                                    fontes=[{"funcao": "consultarRetencoes"}])))

    def test_legislacao_com_desfecho_descartado_nao_vale_e_a_regra_so_rebaixa(self):
        for desfecho in ("NUMERO_SEM_FONTE", "CITACAO_INVALIDA", "RESPOSTA_CORTADA",
                         "REGIME_ESPECIFICO_SEM_SETOR"):
            self.assertFalse(ext.citacao_vale(resultado("x", desfecho, citacao_valida=True)))
        # nao promove: falso e nulo seguem como vieram
        self.assertIs(ext.citacao_vale(resultado("x", rota="MISTO", citacao_valida=False,
                                                 desfechoLegislacao="RESPONDIDA")), False)
        self.assertIsNone(ext.citacao_vale(resultado("x", rota="MISTO", desfechoLegislacao="NUMERO_SEM_FONTE")))
        self.assertTrue(ext.citacao_vale(resultado("x", citacao_valida=True)))

    def test_sql_aceita_espaco_depois_da_chave_como_o_cita_algum(self):
        sql = ext.sql_conferencia({"perguntas": [{"origem": "inedita_externa_ia", "id": "X",
                                                  "dispositivosEsperados": ["LC 214/2025, art. 138"]}]})
        self.assertIn("LIKE c.dispositivo || ' %'", sql)



#: Regras de mentira com a mesma forma das afirmacoes proibidas (#387): as de verdade, de cada
#: inedita, ficam no repositorio cego (tests/test_heuristica.py de la), com este mesmo corpus.
PROIBIDA_PODE = r"\b(poder[aá]|pode) plantar (agora |hoje )?(o|os|esse|esses) milhos?\b"

#: Corpus de regressao da heuristica: toda frase que um review (#361, #368) usou para mostrar um
#: erro entra aqui com o resultado certo. Mudou a regra, o corpus inteiro tem de continuar valendo.
CORPUS_CLASSE = [
    ("Tem alíquota zero de IBS e CBS.", "ZERO", True),
    ("Não tem alíquota zero: a redução é de 60%.", "ZERO", False),
    ("Não tem alíquota zero: a redução é de 60%.", "REDUCAO_60", True),
    ("Pode ter alíquota zero, redução de 60% ou alíquota padrão.", "ZERO", False),
    ("Pode ter alíquota zero, redução de 60% ou alíquota padrão.", "CONDICIONAL", True),
    ("Sim, esse insumo tem redução de 60% da alíquota padrão (Anexo IX).", "REDUCAO_60", True),
    ("Sim, esse insumo tem redução de 60% da alíquota padrão (Anexo IX).", "CONDICIONAL", False),
    ("O milho tem redução de 60% na alíquota padrão.", "REDUCAO_60", True),
    ("A redução de 60% aplica-se à alíquota padrão.", "REDUCAO_60", True),
    ("O milho tem alíquota 60% menor que a alíquota padrão.", "REDUCAO_60", True),
    ("Não tem redução: vale a alíquota padrão.", "PADRAO", True),
    ("Não tem direito à redução: vale a alíquota padrão.", "PADRAO", True),
    ("Não há redução de 60% para esse produto.", "PADRAO", True),
    ("Para o trator a redução não se aplica e vale a alíquota cheia.", "PADRAO", True),
    ("O trator não tem nenhuma redução e paga a alíquota padrão.", "PADRAO", True),
    ("Sobre a venda do trator incide a alíquota padrão.", "PADRAO", True),
    ("A alíquota de 26,5% é a alíquota padrão.", "PADRAO", True),
    ("O trator nunca foi isento e paga a alíquota padrão.", "PADRAO", True),
    ("Os produtos hortícolas não processados têm alíquota zero.", "ZERO", True),
    ("Isso não muda nada. Alíquota zero para o ovo.", "ZERO", True),
    ("O produto que não é processado tem alíquota zero.", "ZERO", True),
    ("O ovo, que não é industrializado, tem alíquota zero.", "ZERO", True),
    ("O adubo não terá direito à alíquota zero.", "ZERO", False),
    ("Não há, neste caso, alíquota zero.", "ZERO", False),
    ("Não há, a meu ver, alíquota zero.", "ZERO", False),
    ("O milho não fica isento: tem redução de 60%.", "REDUCAO_60", True),
    ("Os insumos não ficam isentos; têm redução de 60%.", "REDUCAO_60", True),
    ("Depende do NCM do produto.", "CONDICIONAL", True),
    ("A alíquota varia conforme o produto: o milho em grão tem redução de 60%.", "CONDICIONAL", True),
    ("Tem redução de 60%, conforme o art. 138.", "CONDICIONAL", False),
    ("A alíquota não varia: é zero para todos.", "CONDICIONAL", False),
    ("Não se preocupe, depende do produto.", "CONDICIONAL", True),
    ("Venda de produtor não contribuinte tem alíquota zero.", "ZERO", True),
    ("Para o produtor não contribuinte depende do regime do comprador.", "CONDICIONAL", True),
    ("O milho tem redução de 60% e o trator paga a alíquota padrão.", "CONDICIONAL", True),
    ("Esse produto fica fora da redução e paga a alíquota padrão.", "PADRAO", True),
    ("A redução do Anexo IX não se aplica e vale a alíquota padrão.", "PADRAO", True),
    ("O produtor de menor porte paga a alíquota padrão.", "PADRAO", True),
    ("O ovo, que tem alíquota zero de IBS e CBS, pode ser vendido sem destaque.", "ZERO", True),
    ("Não, neste caso, tem redução de 60%.", "REDUCAO_60", True),
    ("Não é isento, contudo, tem 60% de redução.", "REDUCAO_60", True),
    ("O trator não é isento e tem alíquota padrão.", "PADRAO", True),
    ("O produtor não paga IBS porque tem alíquota zero.", "ZERO", True),
    ("A soja não é processada e tem alíquota zero.", "ZERO", True),
    ("Se não for processado tem alíquota zero.", "ZERO", True),
    ("Para o trator a redução não vale e paga a alíquota cheia.", "PADRAO", True),
    ("Não só o milho tem 60% de redução.", "REDUCAO_60", True),
    ("Informo que não há alíquota zero para o adubo.", "ZERO", False),
    ("Acho que não tem alíquota zero.", "ZERO", False),
    ("Não há, hoje, alíquota zero para o adubo.", "ZERO", False),
    ("Não terá, assim, alíquota zero.", "ZERO", False),
    ("Não tem, infelizmente, alíquota zero.", "ZERO", False),
    ("O trator fica sem isenção e paga a alíquota padrão.", "PADRAO", True),
    ("Nenhum benefício de alíquota zero: paga a alíquota padrão.", "PADRAO", True),
    ("A alíquota zero não se aplica ao adubo.", "ZERO", False),
    ("A redução de 60% não vale para o trator.", "REDUCAO_60", False),
    ("Converse com o seu contador.", "FORA_DO_ESCOPO_SISTEMA", True),
    ("Você não precisa procurar o contador: a regra é a mesma.", "FORA_DO_ESCOPO_SISTEMA", False),
]
CORPUS_PROIBIDA = [
    ("Você pode plantar o milho.", True),
    ("Você não pode plantar o milho.", False),
    ("Mesmo que a chuva não venha, você pode plantar o milho do talhão norte.", True),
    ("Não há impedimento, pode plantar os milhos.", True),
    ("Não tem problema, pode plantar o milho.", True),
    ("Não, nesse caso, pode plantar o milho.", True),
    ("Não importa e pode plantar o milho.", True),
    ("Entendo que não pode plantar o milho.", False),
    ("Lembre que não pode plantar o milho.", False),
]
PROIBIDA_NAO_PODERA = r"\bn[aã]o poder[aá] plantar (nenhum|qualquer) milho\b"
CORPUS_PROIBIDA_NAO_PODERA = [
    ("A fazenda que compra de produtor não contribuinte não poderá plantar nenhum milho.", True),
]
PROIBIDA_TERA = r"\bter[aá] chuva forte"
CORPUS_PROIBIDA_TERA = [
    ("Essa semana terá chuva forte na região.", True),
    ("Essa semana não terá chuva forte: o céu fica limpo.", False),
]
CORPUS_ENCAMINHA = [
    ("Procure o seu contador.", True),
    ("Você não precisa procurar o contador: a regra é a mesma para todos.", False),
    ("Você não precisa pagar nada agora, mas procure o contador para confirmar.", True),
    ("Sem precisar de nada, fale com seu contador.", True),
    ("Você não precisa, neste caso, procurar o contador.", False),
    ("Não é necessário, em regra, consultar o contador.", False),
    ("Para isso, procure um profissional, que pode ser o seu contador, e confirme.", True),
    ("Você não precisa, hoje, procurar o contador.", False),
]


class CorpusHeuristicaTest(unittest.TestCase):
    def test_classe(self):
        for texto, classe, esperado in CORPUS_CLASSE:
            self.assertEqual(ext.classe_dita(texto, classe), esperado, f"{classe}: {texto}")

    def test_afirmacao_proibida(self):
        for regra, corpus in ((PROIBIDA_PODE, CORPUS_PROIBIDA), (PROIBIDA_NAO_PODERA, CORPUS_PROIBIDA_NAO_PODERA),
                              (PROIBIDA_TERA, CORPUS_PROIBIDA_TERA)):
            for texto, esperado in corpus:
                self.assertEqual(ext.afirma(regra, texto), esperado, texto)

    def test_encaminhamento(self):
        for texto, esperado in CORPUS_ENCAMINHA:
            self.assertEqual(ext.encaminha(texto), esperado, texto)


if __name__ == "__main__":
    unittest.main()
