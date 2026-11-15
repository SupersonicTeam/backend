"""Detectores, classificação das execuções, plano do executor e a decisão (ENTRA / NAO_ENTRA / INCONCLUSIVA)."""

import unittest

import decidir_ressalva as dec
import executar_ressalva as ex
import ressalva_comum as comum

# Texto do TemplateTratamentoProduto (rota de dados), com a ressalva da condição.
TEMPLATE_COM_RESSALVA = (
    "Mel natural (NCM 0409.00.00): redução de 60% do IBS e da CBS, pelo Anexo VII (LC 214/2025, art. 138, "
    "Anexo VII; cClassTrib 200034). Condição: alimentos destinados ao consumo humano.\n"
    "Tratamento pela tabela oficial de classificação tributária (v1.70). "
    "Confira com seu contador se a sua venda atende à condição.")
TEMPLATE_DO_LEITE = (
    "Leite (NCM 0401.10.90): redução de 60% do IBS e da CBS. Já o leite de consumo direto tem alíquota zero. "
    "O enquadramento da sua venda em uma das duas situações é com o seu contador.")
# O defeito da #443: a rota de lei cita o artigo e afirma o percentual sem a ressalva.
LEI_SEM_RESSALVA = ("O queijo coalho tem redução de 60% da alíquota do IBS e da CBS, conforme o art. 125 e o "
                    "Anexo I da LC 214/2025.")
LEI_COM_RESSALVA = LEI_SEM_RESSALVA + " Confirme o enquadramento do seu produto com o seu contador."
RECUSA_DE_LEI = ("Não consegui localizar a base legal para responder isso — o que não quer dizer que ela não "
                 "exista. O seu contador pode confirmar; se for de outra área da contabilidade, é com ele também.")
TRECHOS_SEM_CONFIRMACAO = ("Não consegui confirmar uma resposta nos trechos de lei que encontrei. Estes são os mais "
                           "próximos da sua pergunta; se não responderem, leve a dúvida ao seu contador. 60%")


class DetectoresTest(unittest.TestCase):
    def test_ressalva_do_template_de_dados(self):
        self.assertTrue(comum.tem_ressalva(TEMPLATE_COM_RESSALVA))
        self.assertTrue(comum.tem_ressalva(TEMPLATE_DO_LEITE))

    def test_ressalva_dita_de_outro_jeito(self):
        for frase in ("Procure um escritório de contabilidade para conferir o enquadramento.",
                      "Vale confirmar com o seu contador antes de emitir a nota.",
                      "Consulte o seu contador sobre a condição.",
                      "Fale com a sua contadora para validar o enquadramento do produto."):
            self.assertTrue(comum.tem_ressalva("Redução de 60%. " + frase), frase)

    def test_lei_sem_ressalva_nao_tem(self):
        self.assertFalse(comum.tem_ressalva(LEI_SEM_RESSALVA))
        self.assertTrue(comum.tem_ressalva(LEI_COM_RESSALVA))

    def test_recusa_que_cita_o_contador_nao_e_ressalva(self):
        self.assertTrue(comum.e_recusa(RECUSA_DE_LEI))
        self.assertFalse(comum.tem_ressalva(RECUSA_DE_LEI))
        self.assertFalse(comum.tem_ressalva(TRECHOS_SEM_CONFIRMACAO))
        self.assertFalse(comum.tem_tratamento(TRECHOS_SEM_CONFIRMACAO))

    def test_ponto_de_ncm_e_de_artigo_nao_separa_a_frase(self):
        self.assertTrue(comum.tem_ressalva("Mel: redução de 60%. Confira o enquadramento do NCM 0409.00.00 "
                                           "com o seu contador."))
        self.assertTrue(comum.tem_ressalva("Redução de 60% (LC 214/2025, art. 138). Valide o art. 138 com o "
                                           "seu contador antes de emitir."))

    def test_plural_e_contabil_tambem_valem(self):
        for frase in ("Confirme com um profissional contábil o enquadramento.",
                      "Consulte o seu escritório contábil sobre a condição.",
                      "Valide com os seus contadores a condição de venda."):
            self.assertTrue(comum.tem_ressalva("Redução de 60%. " + frase), frase)

    def test_mencao_solta_serve_a_conferencia_humana(self):
        self.assertTrue(comum.menciona_contador_solto("Fale com a assessoria contábil."))
        self.assertFalse(comum.menciona_contador_solto(LEI_SEM_RESSALVA))

    def test_contador_sem_pedir_conferencia_nao_conta(self):
        self.assertFalse(comum.tem_ressalva("A nota ficou aguardando aprovação do contador."))
        self.assertFalse(comum.tem_ressalva("Leve a dúvida ao seu contador."))

    def test_tratamento(self):
        for texto in ("redução de 60%", "tem alíquota zero", "paga a alíquota cheia", "sem redução",
                      "reduzida em sessenta por cento", "reduzida em 37,5 %"):
            self.assertTrue(comum.tem_tratamento(texto), texto)
        self.assertFalse(comum.tem_tratamento("Não encontrei esse produto na tabela oficial."))
        self.assertFalse(comum.tem_tratamento(RECUSA_DE_LEI))

    def test_contaminacao_de_tabela(self):
        self.assertTrue(comum.contaminada_por_tabela(TEMPLATE_COM_RESSALVA))
        self.assertTrue(comum.contaminada_por_tabela("cClassTrib 200034"))
        self.assertFalse(comum.contaminada_por_tabela(LEI_SEM_RESSALVA))


def execucao(texto=None, rota="LEGISLACAO", status=200, normas=("LC 214/2025",), fontes=(), fallback=False):
    return {"status": status, "resposta": texto, "rota": rota, "modoFallback": fallback,
            "citacoes": [{"norma": n, "dispositivo": "art. 1º"} for n in normas], "fontes": list(fontes)}


class ClassificacaoTest(unittest.TestCase):
    def test_defeito(self):
        self.assertEqual(comum.classificar_defeito(execucao(TEMPLATE_COM_RESSALVA, "DADOS")), comum.OK)
        self.assertEqual(comum.classificar_defeito(execucao(LEI_COM_RESSALVA)), comum.OK)
        self.assertEqual(comum.classificar_defeito(execucao(LEI_SEM_RESSALVA)), comum.DEFEITO)
        self.assertEqual(comum.classificar_defeito(execucao(RECUSA_DE_LEI)), comum.SEM_TRATAMENTO)
        self.assertEqual(comum.classificar_defeito(execucao(None, status=503)), comum.FALHA_TECNICA)

    def test_resposta_em_modo_fallback_e_falha_tecnica(self):
        fallback = execucao(LEI_COM_RESSALVA, fallback=True)
        self.assertEqual(comum.classificar_defeito(fallback), comum.FALHA_TECNICA)
        self.assertFalse(comum.acertou_controle_de_lei(fallback, {"rota": "LEGISLACAO",
                                                                   "normasAceitas": ["LC 214/2025"]}))

    def test_controle_de_lei(self):
        esperado = {"rota": "LEGISLACAO", "normasAceitas": ["LC 214/2025"]}
        self.assertTrue(comum.acertou_controle_de_lei(execucao("O split payment é o recolhimento na liquidação."),
                                                      esperado))
        self.assertFalse(comum.acertou_controle_de_lei(execucao("texto", rota="MISTO"), esperado))
        self.assertFalse(comum.acertou_controle_de_lei(execucao("texto", normas=("EC 132/2023",)), esperado))
        self.assertFalse(comum.acertou_controle_de_lei(execucao(RECUSA_DE_LEI), esperado))
        self.assertFalse(comum.acertou_controle_de_lei(execucao(TEMPLATE_COM_RESSALVA), esperado))

    def test_controle_de_dados(self):
        trat = {"rota": "DADOS", "funcao": comum.FUNCAO_TRATAMENTO, "tratamento": True, "ressalva": True}
        puro = {"rota": "DADOS", "semFuncao": comum.FUNCAO_TRATAMENTO, "ressalva": False}
        bom = execucao(TEMPLATE_COM_RESSALVA, "DADOS", fontes=[comum.FUNCAO_TRATAMENTO])
        self.assertTrue(comum.acertou_controle_de_dados(bom, trat))
        self.assertFalse(comum.acertou_controle_de_dados(execucao(LEI_COM_RESSALVA, "LEGISLACAO"), trat))
        self.assertFalse(comum.acertou_controle_de_dados(execucao(TEMPLATE_COM_RESSALVA, "DADOS"), trat))
        self.assertTrue(comum.acertou_controle_de_dados(
            execucao("Você vendeu R$ 10,00 de soja.", "DADOS", fontes=["totalVendas"]), puro))
        self.assertFalse(comum.acertou_controle_de_dados(bom, puro))
        self.assertFalse(comum.acertou_controle_de_dados(
            execucao("Você vendeu R$ 10,00. Confira com seu contador.", "DADOS", fontes=["totalVendas"]), puro))


class ExecutorTest(unittest.TestCase):
    def test_commit_de_cada_braco_e_obrigatorio_e_validado(self):
        self.assertEqual(ex.ler_commits(["base=abc1234", "cand=" + "f" * 40], ["base", "cand"]),
                         {"base": "abc1234", "cand": "f" * 40})
        for invalido in (["base=abc1234"], ["base=abc1234", "cand=xyz"], ["base=abc1234", "outro=abc1234"],
                         ["base=abc1234", "cand"]):
            with self.assertRaises(SystemExit):
                ex.ler_commits(invalido, ["base", "cand"])

    def test_refazer_falhas_troca_so_a_falha_tecnica_e_reescreve_o_arquivo(self):
        import json
        import tempfile
        from pathlib import Path
        resultados = [{"id": "A", "braco": "b", "rep": 1, "status": 200, "resposta": "ok", "modoFallback": False},
                      {"id": "B", "braco": "b", "rep": 1, "status": 503, "resposta": None},
                      {"id": "C", "braco": "b", "rep": 1, "status": 200, "resposta": "x", "modoFallback": True}]
        chamadas = []

        def executar(rep, pergunta_id, braco):
            chamadas.append(pergunta_id)
            return {"id": pergunta_id, "braco": braco, "rep": rep, "status": 200, "resposta": "refeita",
                    "modoFallback": False}

        with tempfile.TemporaryDirectory() as pasta:
            self.assertIsNone(ex.refazer_falhas(Path(pasta), resultados, executar))
            linhas = [json.loads(l) for l in (Path(pasta) / "respostas-brutas.jsonl").read_text("utf-8").splitlines()]
        self.assertEqual(chamadas, ["B", "C"])
        self.assertEqual([l["resposta"] for l in linhas], ["ok", "refeita", "refeita"])

    def test_refazer_falhas_interrompido_grava_o_que_tem(self):
        import json
        import tempfile
        from pathlib import Path
        resultados = [{"id": "B", "braco": "b", "rep": 1, "status": 503, "resposta": None}]

        def cota(rep, pergunta_id, braco):
            raise ex.CotaEsgotada()

        with tempfile.TemporaryDirectory() as pasta:
            motivo = ex.refazer_falhas(Path(pasta), resultados, cota)
            linhas = (Path(pasta) / "respostas-brutas.jsonl").read_text("utf-8").splitlines()
        self.assertIn("Cota", motivo)
        self.assertEqual(json.loads(linhas[0])["status"], 503)


class PlanoTest(unittest.TestCase):
    def test_todo_id_e_braco_aparece_uma_vez_por_repeticao_e_o_sorteio_e_fixo(self):
        ids = [f"Q{i:02d}" for i in range(10)]
        a = ex.plano(ids, ["base", "cand"])
        self.assertEqual(a, ex.plano(ids, ["base", "cand"]))
        self.assertEqual(len(a), 10 * 2 * 3)
        self.assertEqual(len(set(a)), len(a))
        # as execuções de um mesmo id e repetição ficam juntas, e a ordem dos braços gira
        self.assertEqual({a[0][:2], a[1][:2]}, {a[0][:2]})
        primeiros = {a[k][2] for k in range(0, len(a), 2)}
        self.assertEqual(primeiros, {"base", "cand"})

    def test_nome_de_braco_e_variaveis(self):
        self.assertTrue(ex.nome_de_braco_valido("candidato-1"))
        self.assertFalse(ex.nome_de_braco_valido("Base"))
        self.assertEqual(ex.variavel_do_braco("candidato-1", "URL"), "RESSALVA_CANDIDATO_1_URL")


def _execs(conjunto, braco, ok_por_id):
    """Gera 3 execuções por pergunta; `ok_por_id[id]` = quantas das 3 acertam (padrão: 3)."""
    saida = []
    for p in conjunto["perguntas"]:
        certas = ok_por_id.get(p["id"], 3)
        for rep in range(1, 4):
            acerta = rep <= certas
            if p["grupo"] == "DEFEITO":
                e = execucao(LEI_COM_RESSALVA if acerta else LEI_SEM_RESSALVA)
            elif p["grupo"] == "CONTROLE_LEI":
                e = execucao("Resposta de lei.", normas=(p["esperado"]["normasAceitas"][0],)) if acerta \
                    else execucao(RECUSA_DE_LEI)
            elif p["tipo"] == "tratamento":
                e = execucao(TEMPLATE_COM_RESSALVA, "DADOS", fontes=[comum.FUNCAO_TRATAMENTO]) if acerta \
                    else execucao(LEI_SEM_RESSALVA)
            else:
                e = execucao("Você vendeu.", "DADOS", fontes=["totalVendas"]) if acerta else execucao(None, "DADOS")
            e.update({"id": p["id"], "braco": braco, "rep": rep})
            saida.append(e)
    return saida


class DecisaoTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.conjunto = comum.carregar_conjunto()
        cls.ids_a = [p["id"] for p in cls.conjunto["perguntas"] if p["grupo"] == "DEFEITO" and p["estrato"] == "A"]
        cls.ids_b = [p["id"] for p in cls.conjunto["perguntas"] if p["grupo"] == "DEFEITO" and p["estrato"] == "B"]
        cls.lei = [p["id"] for p in cls.conjunto["perguntas"] if p["grupo"] == "CONTROLE_LEI"]
        cls.dados = [p["id"] for p in cls.conjunto["perguntas"] if p["grupo"] == "CONTROLE_DADOS"]

    def decidir(self, base, cand):
        ex_ = _execs(self.conjunto, "base", base) + _execs(self.conjunto, "cand", cand)
        return dec.decidir(self.conjunto, ex_, "base", "cand")

    def base_com_defeito(self, quantos=15):
        """Base que falha em `quantos` ids do estrato A, nas 3 repetições."""
        return {i: 0 for i in self.ids_a[:quantos]}

    def test_entra_quando_o_defeito_melhora_e_nada_piora(self):
        r = self.decidir(self.base_com_defeito(), {})
        self.assertEqual(r["veredito"], dec.ENTRA, r["motivos"])
        self.assertEqual(r["defeito"]["estratoA"]["delta"], 45)

    def test_diferenca_pequena_sem_id_estavel_e_ruido(self):
        base = {i: 2 for i in self.ids_a[:15]}  # 15 falhas, uma por id
        cand = {**base, self.ids_a[14]: 3}      # um acerto a mais: ruído
        r = self.decidir(base, cand)
        self.assertEqual(r["defeito"]["estratoA"]["delta"], 1)
        self.assertEqual(r["veredito"], dec.NAO_ENTRA)

    def test_um_id_estavel_basta_como_efeito(self):
        base = {i: 0 for i in self.ids_a[:15]}
        cand = {i: 0 for i in self.ids_a[1:15]}  # só o primeiro id passa de 0 a 3
        r = self.decidir(base, cand)
        self.assertEqual(r["defeito"]["estratoA"]["idsCorrigidosEstaveis"], [self.ids_a[0]])
        self.assertEqual(r["veredito"], dec.ENTRA)

    def test_sem_efeito_nao_entra(self):
        r = self.decidir(self.base_com_defeito(), self.base_com_defeito())
        self.assertEqual(r["veredito"], dec.NAO_ENTRA)
        self.assertIn("sem efeito", r["motivos"][0])

    def test_controle_de_lei_que_piora_barra(self):
        cand = {i: 0 for i in self.lei[:2]}
        r = self.decidir(self.base_com_defeito(), cand)
        self.assertEqual(r["veredito"], dec.NAO_ENTRA)
        self.assertEqual(set(r["controles"]["lei"]["idsComPerdaEstavel"]), set(self.lei[:2]))

    def test_controle_de_dados_que_perde_um_id_estavel_barra(self):
        r = self.decidir(self.base_com_defeito(), {self.dados[0]: 0})
        self.assertEqual(r["veredito"], dec.NAO_ENTRA)

    def test_perda_de_duas_repeticoes_e_so_alerta(self):
        r = self.decidir(self.base_com_defeito(), {self.dados[0]: 1})
        self.assertEqual(r["veredito"], dec.ENTRA, r["motivos"])
        self.assertTrue(any("2 repetições" in a for a in r["alertas"]))

    def test_estrato_A_que_piora_barra(self):
        base = {i: 0 for i in self.ids_a[:15]}
        cand = {**{i: 3 for i in self.ids_a[:15]}, self.ids_a[20]: 0}
        r = self.decidir(base, cand)
        self.assertEqual(r["veredito"], dec.NAO_ENTRA)

    def test_defeito_que_nao_se_reproduz_na_base_e_inconclusivo(self):
        r = self.decidir({i: 0 for i in self.ids_a[:4]}, {})  # 12 falhas, abaixo do mínimo
        self.assertEqual(r["veredito"], dec.INCONCLUSIVA)

    def test_controle_que_a_base_nao_acerta_e_excluido(self):
        base = {**self.base_com_defeito(), self.lei[0]: 0}
        r = self.decidir(base, {})
        self.assertIn(self.lei[0], r["controles"]["lei"]["excluidosPorNaoQualificarNaBase"])
        self.assertEqual(r["veredito"], dec.ENTRA)

    def test_poucos_controles_qualificados_e_inconclusivo(self):
        base = {**self.base_com_defeito(), **{i: 0 for i in self.dados[:5]}}
        r = self.decidir(base, base)
        self.assertEqual(r["veredito"], dec.INCONCLUSIVA)

    def test_rodada_incompleta_e_inconclusiva(self):
        execs = _execs(self.conjunto, "base", self.base_com_defeito()) + _execs(self.conjunto, "cand", {})[:-5]
        r = dec.decidir(self.conjunto, execs, "base", "cand")
        self.assertEqual(r["veredito"], dec.INCONCLUSIVA)

    def test_falhas_tecnicas_demais_sao_inconclusivas(self):
        execs = _execs(self.conjunto, "base", self.base_com_defeito()) + _execs(self.conjunto, "cand", {})
        derrubadas = 0
        for e in execs:
            if e["braco"] == "cand" and derrubadas < 15:
                e["status"], e["resposta"] = 503, None
                derrubadas += 1
        r = dec.decidir(self.conjunto, execs, "base", "cand")
        self.assertEqual(r["veredito"], dec.INCONCLUSIVA)

    def test_resposta_que_vira_recusa_nao_vale_como_correcao(self):
        # o candidato troca o defeito por recusa em todos os ids falhos: sem tratamento, sem ganho
        execs = _execs(self.conjunto, "base", self.base_com_defeito())
        cand = _execs(self.conjunto, "cand", self.base_com_defeito())
        for e in cand:
            if e["id"] in self.ids_a[:15]:
                e["resposta"] = RECUSA_DE_LEI
        r = dec.decidir(self.conjunto, execs + cand, "base", "cand")
        self.assertEqual(r["veredito"], dec.NAO_ENTRA)
        self.assertTrue(any("sem o tratamento" in m for m in r["motivos"]), r["motivos"])

    def test_estrato_b_so_alerta(self):
        r = self.decidir(self.base_com_defeito(), {i: 0 for i in self.ids_b[:2]})
        self.assertEqual(r["veredito"], dec.ENTRA, r["motivos"])
        self.assertTrue(any("estrato B" in a for a in r["alertas"]))

    def test_ressalva_que_o_detector_nao_reconheceu_vai_para_a_conferencia(self):
        execs = _execs(self.conjunto, "base", self.base_com_defeito())
        cand = _execs(self.conjunto, "cand", self.base_com_defeito())
        for e in cand:
            if e["id"] == self.ids_a[0] and e["rep"] == 1:
                e["resposta"] = "Redução de 60%. Vale conversar com a assessoria contábil."
        r = dec.decidir(self.conjunto, execs + cand, "base", "cand")
        conferir = r["paraConferirDetector"]
        self.assertIn({"id": self.ids_a[0], "rep": 1,
                       "resposta": "Redução de 60%. Vale conversar com a assessoria contábil."}, conferir)
        self.assertIn("não reconheceu", dec.para_markdown(r))

    def test_fallback_conta_como_falha_tecnica_no_portao(self):
        execs = _execs(self.conjunto, "base", self.base_com_defeito()) + _execs(self.conjunto, "cand", {})
        marcadas = 0
        for e in execs:
            if e["braco"] == "cand" and marcadas < 15:
                e["modoFallback"] = True
                marcadas += 1
        self.assertEqual(dec.decidir(self.conjunto, execs, "base", "cand")["veredito"], dec.INCONCLUSIVA)

    def test_markdown_traz_leitura_humana(self):
        r = self.decidir(self.base_com_defeito(), {})
        md = dec.para_markdown(r)
        self.assertIn("ENTRA", md)
        self.assertIn("Para a leitura humana", md)
        self.assertEqual(len(r["paraLeitura"]), 15)


if __name__ == "__main__":
    unittest.main()
