"""Testes do pontuar.py sobre saídas fictícias (python3 -m unittest, nesta pasta)."""

import contextlib
import datetime as dt
import io
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pontuar  # noqa: E402

HOJE = dt.date(2026, 11, 7)
CONJUNTO = Path(__file__).resolve().parents[1] / "treino" / "perguntas-ceres-442-443.json"


def fonte(funcao, **params):
    return {"funcao": funcao, "parametros": params, "origem": "PRODUTOR"}


def corpo(resposta="ok", rota="DADOS", fontes=(), citacoes=(), fallback=False):
    return {"resposta": resposta, "rota": rota, "fontesDados": list(fontes),
            "citacoes": list(citacoes), "modoFallback": fallback, "atalhos": []}


def registro(ident, rep, c, status=200, data="2026-11-07"):
    return {"id": ident, "repeticao": rep, "http_status": status, "corpo": c,
            "data_referencia": data}


class PeriodosRelativosTest(unittest.TestCase):

    def test_mes_anterior_e_o_mes_inteiro(self):
        self.assertEqual(pontuar.janelas("mes_anterior", HOJE),
                         [({dt.date(2026, 10, 1)}, {dt.date(2026, 10, 31)})])
        self.assertEqual(pontuar.meses_aceitos("mes_anterior", HOJE), {"2026-10"})

    def test_mes_corrente_aceita_ate_hoje_ou_fim_do_mes(self):
        [(inicios, fins)] = pontuar.janelas("mes_corrente", HOJE)
        self.assertEqual(inicios, {dt.date(2026, 11, 1)})
        self.assertEqual(fins, {HOJE, dt.date(2026, 11, 6), dt.date(2026, 11, 30)})

    def test_virada_de_ano(self):
        janeiro = dt.date(2027, 1, 15)
        self.assertEqual(pontuar.meses_aceitos("mes_anterior", janeiro), {"2026-12"})
        self.assertEqual(pontuar.meses_aceitos("mes_retrasado", janeiro), {"2026-11"})
        self.assertEqual(pontuar.janelas("ano_anterior", janeiro),
                         [({dt.date(2026, 1, 1)}, {dt.date(2026, 12, 31)})])

    def test_trimestre_civil_ou_movel(self):
        civil, movel = pontuar.janelas("trimestre_anterior", HOJE)
        self.assertEqual(civil, ({dt.date(2026, 7, 1)}, {dt.date(2026, 9, 30)}))
        self.assertIn(dt.date(2026, 8, 1), movel[0])
        self.assertIn(dt.date(2026, 8, 7), movel[0])
        self.assertIn(dt.date(2026, 10, 31), movel[1])
        self.assertNotIn(dt.date(2026, 1, 1), movel[0])

    def test_seis_meses(self):
        [(inicios, fins)] = pontuar.janelas("ultimos_6_meses", HOJE)
        self.assertIn(dt.date(2026, 5, 1), inicios)
        self.assertIn(dt.date(2026, 5, 7), inicios)
        self.assertIn(HOJE, fins)
        self.assertNotIn(dt.date(2026, 1, 1), inicios)

    def test_periodo_desconhecido(self):
        with self.assertRaises(ValueError):
            pontuar.janelas("esta_safra", HOJE)


class FontesDeDadosTest(unittest.TestCase):

    def esperado(self, **kw):
        return {"funcoes": ["consultarCreditos"], **kw}

    def test_funcao_e_periodo_certos(self):
        c = corpo(fontes=[fonte("consultarCreditos", dataInicio="2026-10-01",
                                dataFim="2026-10-31")])
        self.assertTrue(pontuar.confere_dados(self.esperado(periodo="mes_anterior"), c, HOJE))

    def test_periodo_errado(self):
        c = corpo(fontes=[fonte("consultarCreditos", dataInicio="2026-08-01",
                                dataFim="2026-10-31")])
        self.assertFalse(pontuar.confere_dados(self.esperado(periodo="mes_anterior"), c, HOJE))

    def test_sem_fonte_e_errada(self):
        self.assertFalse(pontuar.confere_dados(self.esperado(periodo="mes_anterior"), corpo(),
                                               HOJE))

    def test_funcao_errada(self):
        c = corpo(fontes=[fonte("consultarRetencoes", dataInicio="2026-10-01",
                                dataFim="2026-10-31")])
        self.assertFalse(pontuar.confere_dados(self.esperado(periodo="mes_anterior"), c, HOJE))

    def test_basta_uma_fonte_certa(self):
        c = corpo(fontes=[fonte("listarNotas"),
                          fonte("consultarCreditos", dataInicio="2026-10-01",
                                dataFim="2026-10-31")])
        self.assertTrue(pontuar.confere_dados(self.esperado(periodo="mes_anterior"), c, HOJE))

    def test_funcao_de_mes(self):
        esperado = {"funcoes": ["consultarApuracao"], "periodo": "mes_anterior"}
        self.assertTrue(pontuar.confere_dados(
            esperado, corpo(fontes=[fonte("consultarApuracao", mes="2026-10")]), HOJE))
        self.assertFalse(pontuar.confere_dados(
            esperado, corpo(fontes=[fonte("consultarApuracao", mes="2026-11")]), HOJE))
        self.assertFalse(pontuar.confere_dados(
            esperado, corpo(fontes=[fonte("consultarApuracao")]), HOJE))

    def test_vencimentos_do_mes_sem_mes_e_o_atual(self):
        atual = {"funcoes": ["vencimentosDoMes"], "periodo": "mes_corrente"}
        seguinte = {"funcoes": ["vencimentosDoMes"], "periodo": "mes_seguinte"}
        sem_mes = corpo(fontes=[fonte("vencimentosDoMes")])
        self.assertTrue(pontuar.confere_dados(atual, sem_mes, HOJE))
        self.assertFalse(pontuar.confere_dados(seguinte, sem_mes, HOJE))
        self.assertTrue(pontuar.confere_dados(
            seguinte, corpo(fontes=[fonte("vencimentosDoMes", mes="2026-12")]), HOJE))

    def test_padroes_de_datas_omitidas(self):
        vendas = {"funcoes": ["totalVendasPorProduto"], "periodo": "ano_corrente",
                  "produto": ["milho"]}
        self.assertTrue(pontuar.confere_dados(
            vendas, corpo(fontes=[fonte("totalVendasPorProduto", descricaoOuNcm="milho")]),
            HOJE))
        notas = {"funcoes": ["listarNotas"], "periodo": "mes_anterior"}
        self.assertFalse(pontuar.confere_dados(notas, corpo(fontes=[fonte("listarNotas")]),
                                               HOJE))

    def test_produto_por_nome_ou_ncm(self):
        esperado = {"funcoes": ["totalComprasPorInsumo"], "periodo": "ano_anterior",
                    "produto": ["adubo", "fertiliz", "31"]}
        datas = {"dataInicio": "2025-01-01", "dataFim": "2025-12-31"}
        for valor, certo in (("Fertilizante", True), ("31052000", True), ("adubo NPK", True),
                             ("diesel", False), ("2710", False)):
            c = corpo(fontes=[fonte("totalComprasPorInsumo", descricaoOuNcm=valor, **datas)])
            self.assertEqual(pontuar.confere_dados(esperado, c, HOJE), certo, valor)

    def test_tipo_e_numero(self):
        notas = {"funcoes": ["listarNotas"], "periodo": "mes_anterior", "tipo": "SAIDA"}
        datas = {"dataInicio": "2026-10-01", "dataFim": "2026-10-31"}
        self.assertTrue(pontuar.confere_dados(
            notas, corpo(fontes=[fonte("listarNotas", tipo="SAIDA", **datas)]), HOJE))
        self.assertFalse(pontuar.confere_dados(
            notas, corpo(fontes=[fonte("listarNotas", **datas)]), HOJE))
        nota = {"funcoes": ["consultarNota"], "numero": 77}
        self.assertTrue(pontuar.confere_dados(
            nota, corpo(fontes=[fonte("consultarNota", numero=77)]), HOJE))
        self.assertFalse(pontuar.confere_dados(
            nota, corpo(fontes=[fonte("consultarNota", numero=78)]), HOJE))

    def test_periodo_absoluto(self):
        esperado = {"funcoes": ["consultarRetencoes"], "inicio": "2026-03-01",
                    "fim": "2026-03-31"}
        self.assertTrue(pontuar.confere_dados(esperado, corpo(fontes=[fonte(
            "consultarRetencoes", dataInicio="2026-03-01", dataFim="2026-03-31")]), HOJE))
        self.assertFalse(pontuar.confere_dados(esperado, corpo(fontes=[fonte(
            "consultarRetencoes", dataInicio="2026-03-01", dataFim="2026-04-30")]), HOJE))
        mes = {"funcoes": ["consultarApuracao"], "mes": "2026-03"}
        self.assertTrue(pontuar.confere_dados(
            mes, corpo(fontes=[fonte("consultarApuracao", mes="2026-03")]), HOJE))

    def test_parametros_em_texto(self):
        self.assertEqual(pontuar.parametros({"parametros": '{"mes": "2026-10"}'}),
                         {"mes": "2026-10"})
        self.assertEqual(pontuar.parametros({"parametros": "dataInicio=2026-10-01, "
                                                           "dataFim=2026-10-31"}),
                         {"dataInicio": "2026-10-01", "dataFim": "2026-10-31"})
        self.assertEqual(pontuar.parametros({}), {})


class ProdutoTest(unittest.TestCase):

    def test_percentual_e_ressalva(self):
        esperado = {"reducao": 60, "ressalva": True}
        certa = corpo("O mel tem redução de 60% no IBS e na CBS. Confirme o enquadramento "
                      "do produto com o seu contador.")
        self.assertTrue(pontuar.confere_produto(esperado, certa))

    def test_ressalva_no_imperativo_de_conferir(self):
        esperado = {"reducao": 60, "ressalva": True}
        self.assertTrue(pontuar.confere_produto(esperado, corpo(
            "Redução de 60%. Confira com o seu contador se a venda atende à condição.")))

    def test_sem_ressalva_e_errada(self):
        esperado = {"reducao": 60, "ressalva": True}
        self.assertFalse(pontuar.confere_produto(
            esperado, corpo("O mel tem redução de 60% no IBS e na CBS.")))

    def test_contador_sem_ressalva_nao_conta(self):
        esperado = {"reducao": 60, "ressalva": True}
        self.assertFalse(pontuar.confere_produto(
            esperado, corpo("Redução de 60%. O contador emite a nota.")))

    def test_percentual_errado(self):
        esperado = {"reducao": 100, "ressalva": True}
        self.assertFalse(pontuar.confere_produto(esperado, corpo(
            "Tem redução de 60%. Confirme com o contador.")))
        self.assertTrue(pontuar.confere_produto(esperado, corpo(
            "A alíquota fica reduzida a zero. Vale confirmar o enquadramento com o contador.")))
        self.assertTrue(pontuar.confere_produto(esperado, corpo(
            "Redução de 100%; o contador confere o enquadramento.")))


class LeiTest(unittest.TestCase):

    esperado = {"rotas": ["LEGISLACAO"], "norma": "214", "artigos": [79, 80]}

    def test_rota_e_citacao(self):
        c = corpo(rota="LEGISLACAO", citacoes=[{"norma": "LC 214/2025",
                                                "dispositivo": "art. 79, caput"}])
        self.assertTrue(pontuar.confere_lei(self.esperado, c))

    def test_rota_errada(self):
        c = corpo(rota="DADOS", citacoes=[{"norma": "LC 214/2025", "dispositivo": "art. 79"}])
        self.assertFalse(pontuar.confere_lei(self.esperado, c))

    def test_artigo_ou_norma_errados(self):
        self.assertFalse(pontuar.confere_lei(self.esperado, corpo(
            rota="LEGISLACAO", citacoes=[{"norma": "LC 214/2025", "dispositivo": "art. 8"}])))
        self.assertFalse(pontuar.confere_lei(self.esperado, corpo(
            rota="LEGISLACAO", citacoes=[{"norma": "EC 132/2023", "dispositivo": "art. 79"}])))
        self.assertFalse(pontuar.confere_lei(self.esperado, corpo(rota="LEGISLACAO")))

    def test_citacao_em_texto(self):
        c = corpo(rota="LEGISLACAO", citacoes=["LC 214/2025, art. 80"])
        self.assertTrue(pontuar.confere_lei(self.esperado, c))


class VotacaoTest(unittest.TestCase):

    def conjunto(self):
        return {"versao": "t", "_sha256": "abc", "perguntas": [
            {"id": "a-1", "grupo": "controle-dados",
             "esperado": {"funcoes": ["resumoDoProdutor"]}},
            {"id": "a-2", "grupo": "controle-dados",
             "esperado": {"funcoes": ["resumoDoProdutor"]}},
            {"id": "a-3", "grupo": "controle-dados",
             "esperado": {"funcoes": ["resumoDoProdutor"]}}]}

    def test_maioria_e_falha_tecnica(self):
        certa = corpo(fontes=[fonte("resumoDoProdutor")])
        errada = corpo()
        registros = [
            registro("a-1", 1, certa), registro("a-1", 2, errada), registro("a-1", 3, certa),
            registro("a-2", 1, certa), registro("a-2", 2, errada), registro("a-2", 3, errada),
            # Falha técnica não vota: duas válidas, uma certa, empate = errada.
            registro("a-3", 1, certa), registro("a-3", 2, errada),
            registro("a-3", 3, corpo(fallback=True)),
        ]
        resumo = pontuar.pontuar(self.conjunto(), registros, None)
        itens = resumo["por_pergunta"]
        self.assertEqual(itens["a-1"]["resultado"], "certa")
        self.assertEqual(itens["a-2"]["resultado"], "errada")
        self.assertEqual(itens["a-3"]["resultado"], "errada")
        self.assertEqual(itens["a-3"]["tecnicas"], 1)
        self.assertEqual(resumo["por_grupo"]["controle-dados"]["certas"], 1)
        self.assertEqual(resumo["datas_referencia"], ["2026-11-07"])

    def test_sem_execucao_valida(self):
        registros = [registro("a-1", 1, corpo(), status=503)]
        resumo = pontuar.pontuar(self.conjunto(), registros, None)
        self.assertEqual(resumo["por_pergunta"]["a-1"]["resultado"], "sem_medicao")
        self.assertEqual(resumo["por_pergunta"]["a-2"]["resultado"], "sem_medicao")

    def test_falhas_tecnicas(self):
        self.assertTrue(pontuar.falha_tecnica(registro("x", 1, corpo(), status=429)))
        self.assertTrue(pontuar.falha_tecnica(registro("x", 1, corpo(fallback=True))))
        self.assertTrue(pontuar.falha_tecnica({**registro("x", 1, corpo()),
                                               "falha_tecnica": True}))
        self.assertTrue(pontuar.falha_tecnica(registro("x", 1, {"errors": [
            {"code": "ASSISTENTE_TEMPO_ESGOTADO"}]})))
        self.assertFalse(pontuar.falha_tecnica(registro("x", 1, corpo())))

    def test_pergunta_fora_do_conjunto(self):
        with self.assertRaises(SystemExit):
            pontuar.pontuar(self.conjunto(), [registro("zz", 1, corpo())], None)


class ComparacaoTest(unittest.TestCase):

    def resumo(self, periodo_certas, controles, data="2026-11-07", sha="abc"):
        por_pergunta = {f"c-{i}": {"grupo": "controle-lei", "resultado": r}
                        for i, r in enumerate(controles)}
        por_pergunta.update({f"p-{i:02d}": {
            "grupo": "periodo", "resultado": "certa" if i < periodo_certas else "errada"}
            for i in range(12)})
        return {"sha256_conjunto": sha, "datas_referencia": [data],
                "por_grupo": {"periodo": {"certas": periodo_certas, "total": 12}},
                "por_pergunta": por_pergunta}

    def test_sem_ids_tira_as_perguntas_das_duas_rodadas(self):
        base = self.resumo(3, ["certa", "certa"])
        correcao = self.resumo(4, ["certa", "errada"])
        # Com todas: o controle c-1 piorou. Sem ele (visto no ensaio), a regra so ve o resto.
        self.assertEqual(pontuar.comparar(base, correcao, "442")["decisao"], "NAO_ENTRA")
        sem = pontuar.comparar(base, correcao, "442", frozenset({"c-1"}))
        self.assertEqual(sem["decisao"], "ENTRA")
        self.assertEqual(sem["sem_ids"], ["c-1"])
        # Tirar a unica pergunta que subiu (p-03) deixa o grupo igual: nao entra.
        sem_subida = pontuar.comparar(base, correcao, "442", frozenset({"c-1", "p-03"}))
        self.assertEqual(sem_subida["decisao"], "NAO_ENTRA")
        self.assertEqual((sem_subida["certas_base"], sem_subida["certas_correcao"]), (3, 3))

    def test_entra_quando_sobe_e_nenhum_controle_piora(self):
        decisao = pontuar.comparar(self.resumo(3, ["certa", "errada"]),
                                   self.resumo(9, ["certa", "certa"]), "442")
        self.assertEqual(decisao["decisao"], "ENTRA")

    def test_controle_que_piora_barra(self):
        decisao = pontuar.comparar(self.resumo(3, ["certa", "errada"]),
                                   self.resumo(12, ["errada", "certa"]), "442")
        self.assertEqual(decisao["decisao"], "NAO_ENTRA")
        self.assertEqual(decisao["controles_que_pioraram"], ["c-0"])

    def test_sem_subida_nao_entra(self):
        decisao = pontuar.comparar(self.resumo(5, ["certa"]), self.resumo(5, ["certa"]), "442")
        self.assertEqual(decisao["decisao"], "NAO_ENTRA")

    def test_dias_ou_conjuntos_diferentes(self):
        outro_dia = pontuar.comparar(self.resumo(1, []),
                                     self.resumo(5, [], data="2026-11-08"), "442")
        self.assertEqual(outro_dia["decisao"], "NAO_ENTRA")
        outro_conjunto = pontuar.comparar(self.resumo(1, []), self.resumo(5, [], sha="x"),
                                          "442")
        self.assertEqual(outro_conjunto["decisao"], "NAO_ENTRA")


class ConjuntoVersionadoTest(unittest.TestCase):
    """O conjunto congelado tem a forma combinada e confere com o .sha256."""

    def test_formato_e_hash(self):
        conjunto = pontuar.carregar_conjunto(CONJUNTO)
        perguntas = conjunto["perguntas"]
        contagem = {}
        for p in perguntas:
            contagem[p["grupo"]] = contagem.get(p["grupo"], 0) + 1
            self.assertRegex(p["id"], r"^p443-\d{2}$")
            self.assertTrue(p["pergunta"].strip() and p["raciocinio"].strip())
        self.assertEqual(contagem, {"periodo": 12, "produto": 10, "controle-dados": 10,
                                    "controle-lei": 8})
        self.assertEqual(len({p["id"] for p in perguntas}), len(perguntas))
        self.assertEqual(len({p["pergunta"] for p in perguntas}), len(perguntas))

    def test_periodos_e_funcoes_conhecidos(self):
        funcoes = set()
        for p in pontuar.carregar_conjunto(CONJUNTO)["perguntas"]:
            esperado = p["esperado"]
            if "periodo" in esperado:
                pontuar.janelas(esperado["periodo"], HOJE)
            if p["grupo"] == "periodo":
                funcoes |= set(esperado["funcoes"])
        self.assertGreaterEqual(len(funcoes), 4)

    def test_hash_alterado_e_recusado(self):
        with tempfile.TemporaryDirectory() as pasta:
            copia = Path(pasta) / "c.json"
            copia.write_bytes(CONJUNTO.read_bytes() + b" ")
            (Path(pasta) / "c.json.sha256").write_text(
                hashlib.sha256(CONJUNTO.read_bytes()).hexdigest() + "  c.json\n")
            with self.assertRaises(SystemExit):
                pontuar.carregar_conjunto(copia)

    def test_linha_de_comando(self):
        with tempfile.TemporaryDirectory() as pasta:
            execucoes = Path(pasta) / "e.jsonl"
            saida = Path(pasta) / "r.json"
            execucoes.write_text(json.dumps(registro("p443-26", 1, corpo(
                fontes=[fonte("proximosVencimentos", dias=21)]))) + "\n", encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                codigo = pontuar.main(["pontuar", "--perguntas", str(CONJUNTO), "--execucoes",
                                       str(execucoes), "--saida", str(saida)])
            self.assertEqual(codigo, 0)
            resumo = json.loads(saida.read_text(encoding="utf-8"))
            self.assertEqual(resumo["por_pergunta"]["p443-26"]["resultado"], "certa")


if __name__ == "__main__":
    unittest.main()
