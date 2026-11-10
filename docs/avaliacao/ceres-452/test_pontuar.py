"""Testes do pontuar.py da #452 (só biblioteca padrão: python -m unittest)."""

from __future__ import annotations

import datetime as dt
import sys
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import pontuar  # noqa: E402

CONJUNTO = AQUI.parent / "treino" / "perguntas-ceres-452.json"
CONJUNTO_V2 = AQUI.parent / "treino" / "perguntas-ceres-452-v2.json"
NOV = dt.date(2026, 11, 9)
DEZ = dt.date(2026, 12, 3)


def fonte(funcao: str, **params) -> dict:
    return {"funcao": funcao, "parametros": {k: str(v) for k, v in params.items()},
            "origem": "PRODUTOR"}


def corpo(*fontes, rota="DADOS", resposta="Resposta.", citacoes=None) -> dict:
    return {"resposta": resposta, "rota": rota, "citacoes": citacoes or [],
            "fontesDados": list(fontes), "modoFallback": False, "atalhos": []}


def registro(ident: str, corpo_: dict | None, data: dt.date = NOV, status: int = 200,
             **extra) -> dict:
    linha = {"id": ident, "repeticao": 1, "tentativa": 1, "http_status": status,
             "corpo": corpo_, "data_referencia": data.isoformat(), "braco": "base",
             "commit": "a" * 40, "modelo": "qwen3:4b-instruct", "modelo_digest": "d1",
             "segundos": 10.0, "falha_tecnica": False, "aquecimento": False}
    linha.update(extra)
    return linha


class Tokens(unittest.TestCase):

    def test_trimestre_e_tres_meses_fechados_antes_do_corrente(self):
        self.assertEqual(pontuar.data_do_token("mes:-3:inicio", NOV), dt.date(2026, 8, 1))
        self.assertEqual(pontuar.data_do_token("mes:-1:fim", NOV), dt.date(2026, 10, 31))
        self.assertEqual(pontuar.data_do_token("mes:-3:inicio", DEZ), dt.date(2026, 9, 1))
        self.assertEqual(pontuar.data_do_token("mes:-1:fim", DEZ), dt.date(2026, 11, 30))

    def test_mes_seguinte_vira_o_ano(self):
        self.assertEqual(pontuar.mes_do_token("mes:1", NOV), "2026-12")
        self.assertEqual(pontuar.mes_do_token("mes:1", DEZ), "2027-01")
        self.assertEqual(pontuar.data_do_token("mes:13:fim", DEZ), dt.date(2028, 1, 31))

    def test_mes_nomeado_e_o_mais_recente_que_nao_passa_do_corrente(self):
        self.assertEqual(pontuar.mes_do_token("nomeado:9", NOV), "2026-09")
        self.assertEqual(pontuar.mes_do_token("nomeado:11", NOV), "2026-11")
        self.assertEqual(pontuar.mes_do_token("nomeado:9", dt.date(2027, 2, 10)), "2026-09")
        self.assertEqual(pontuar.mes_do_token("nomeado:2", dt.date(2027, 2, 10)), "2027-02")

    def test_um_dia_antes_hoje_ontem_e_ano(self):
        self.assertEqual(pontuar.data_do_token("nomeado:10:inicio-1d", NOV), dt.date(2026, 9, 30))
        self.assertEqual(pontuar.data_do_token("ontem", dt.date(2027, 1, 1)), dt.date(2026, 12, 31))
        self.assertEqual(pontuar.data_do_token("ano:-1:fim", NOV), dt.date(2025, 12, 31))
        self.assertEqual(pontuar.data_do_token("2026-06-30", NOV), dt.date(2026, 6, 30))

    def test_token_invalido_quebra(self):
        for token in ("semana:1:inicio", "mes:1", "mes:x:inicio", "nomeado:13:inicio"):
            with self.assertRaises(ValueError):
                pontuar.data_do_token(token, NOV)
        with self.assertRaises(ValueError):
            pontuar.mes_do_token("mes:1:inicio", NOV)


class Condicoes(unittest.TestCase):

    def test_mes_vale_para_funcao_de_mes_e_para_funcao_de_datas(self):
        cond = {"funcoes": ["vencimentosDoMes", "totalVendasPorProduto"], "mes": "mes:1"}
        self.assertTrue(pontuar.casa(cond, fonte("vencimentosDoMes", mes="2026-12"), NOV))
        self.assertFalse(pontuar.casa(cond, fonte("vencimentosDoMes", mes="2026-11"), NOV))
        self.assertTrue(pontuar.casa(cond, fonte(
            "totalVendasPorProduto", descricaoOuNcm="soja", dataInicio="2026-12-01",
            dataFim="2026-12-31"), NOV))
        self.assertFalse(pontuar.casa(cond, fonte(
            "totalVendasPorProduto", descricaoOuNcm="soja", dataInicio="2026-12-01",
            dataFim="2026-12-15"), NOV))

    def test_mes_diferente_e_fora_de(self):
        cond = {"funcoes": ["vencimentosDoMes"], "mes_diferente_de": "mes:0"}
        self.assertTrue(pontuar.casa(cond, fonte("vencimentosDoMes", mes="2026-12"), NOV))
        self.assertFalse(pontuar.casa(cond, fonte("vencimentosDoMes", mes="2026-11"), NOV))
        fora = {"funcoes": ["consultarApuracao"], "mes_fora_de": ["mes:-2", "mes:-1"]}
        self.assertTrue(pontuar.casa(fora, fonte("consultarApuracao", mes="2026-07"), NOV))
        self.assertFalse(pontuar.casa(fora, fonte("consultarApuracao", mes="2026-10"), NOV))

    def test_dias_ate_confere_o_fim_da_janela(self):
        cond = {"funcoes": ["proximosVencimentos"], "dias_ate": "mes:0:fim"}
        self.assertTrue(pontuar.casa(cond, fonte("proximosVencimentos", dias=21), NOV))
        self.assertFalse(pontuar.casa(cond, fonte("proximosVencimentos", dias=30), NOV))
        self.assertFalse(pontuar.casa(cond, fonte("proximosVencimentos", dias="x"), NOV))

    def test_limites_lista_antes_de_e_entre(self):
        cond = {"funcoes": ["listarNotas"], "inicio": {"antes_de": "nomeado:8:inicio"},
                "fim": ["nomeado:8:fim"]}
        self.assertTrue(pontuar.casa(cond, fonte(
            "listarNotas", dataInicio="2026-01-01", dataFim="2026-08-31"), NOV))
        # Agosto fechado não é "de agosto pra trás".
        self.assertFalse(pontuar.casa(cond, fonte(
            "listarNotas", dataInicio="2026-08-01", dataFim="2026-08-31"), NOV))
        entre = {"funcoes": ["listarNotas"], "fim": {"entre": ["2026-06-30", "hoje"]}}
        self.assertTrue(pontuar.casa(entre, fonte(
            "listarNotas", dataInicio="2025-08-01", dataFim="2026-11-09"), NOV))
        self.assertFalse(pontuar.casa(entre, fonte(
            "listarNotas", dataInicio="2025-08-01", dataFim="2026-11-10"), NOV))

    def test_condicao_de_datas_nao_casa_com_funcao_sem_datas(self):
        cond = {"funcoes": ["consultarApuracao"], "inicio": ["mes:-3:inicio"]}
        self.assertFalse(pontuar.casa(cond, fonte("consultarApuracao", mes="2026-08"), NOV))

    def test_produto_tipo_e_sem_tipo(self):
        cond = {"funcoes": ["totalVendasPorProduto"], "produto": ["soja", "1201"]}
        self.assertTrue(pontuar.casa(cond, fonte(
            "totalVendasPorProduto", descricaoOuNcm="Soja em grão"), NOV))
        self.assertTrue(pontuar.casa(cond, fonte(
            "totalVendasPorProduto", descricaoOuNcm="1201.90.00"), NOV))
        self.assertFalse(pontuar.casa(cond, fonte(
            "totalVendasPorProduto", descricaoOuNcm="milho"), NOV))
        self.assertFalse(pontuar.casa(cond, fonte("totalVendasPorProduto"), NOV))
        tipo = {"funcoes": ["listarNotas"], "tipo": "SAIDA"}
        self.assertTrue(pontuar.casa(tipo, fonte("listarNotas", tipo="saida"), NOV))
        self.assertFalse(pontuar.casa(tipo, fonte("listarNotas"), NOV))
        sem = {"funcoes": ["listarNotas"], "sem_tipo": True}
        self.assertTrue(pontuar.casa(sem, fonte("listarNotas"), NOV))
        self.assertFalse(pontuar.casa(sem, fonte("listarNotas", tipo="ENTRADA"), NOV))

    def test_parametros_em_texto(self):
        f = {"funcao": "vencimentosDoMes", "parametros": "mes=2026-12"}
        self.assertTrue(pontuar.casa({"funcoes": ["vencimentosDoMes"], "mes": "mes:1"}, f, NOV))
        f = {"funcao": "vencimentosDoMes", "parametros": '{"mes": "2026-12"}'}
        self.assertTrue(pontuar.casa({"funcoes": ["vencimentosDoMes"], "mes": "mes:1"}, f, NOV))


class ConjuntoReal(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.conjunto = pontuar.carregar_conjunto(CONJUNTO)
        cls.perguntas = {p["id"]: p for p in cls.conjunto["perguntas"]}

    def classificar(self, ident: str, corpo_: dict, data: dt.date = NOV) -> str:
        return pontuar.classificar(self.perguntas[ident], registro(ident, corpo_, data), data)

    def test_congelado_e_valido_em_qualquer_dia(self):
        dia = dt.date(2026, 1, 1)
        while dia <= dt.date(2027, 12, 31):
            pontuar.validar_conjunto(self.conjunto, dia)
            dia += dt.timedelta(days=1)

    def test_tamanho_e_grupos(self):
        contagem = {}
        for p in self.conjunto["perguntas"]:
            contagem[p["grupo"]] = contagem.get(p["grupo"], 0) + 1
        self.assertEqual(set(contagem), set(pontuar.GRUPOS))
        self.assertGreaterEqual(sum(contagem.values()), 40)
        for grupo in pontuar.GRUPOS_CORRECAO:
            self.assertGreaterEqual(contagem[grupo], 8, grupo)

    def test_nenhum_texto_de_atalho_da_tela(self):
        atalhos = {"quanto eu tenho que pagar este mes", "quando vence a minha proxima guia",
                   "o que muda para mim com o ibs e o cbs"}
        for p in self.conjunto["perguntas"]:
            texto = pontuar.normalizar(p["pergunta"]).strip(" ?.!")
            self.assertNotIn(texto, atalhos, p["id"])

    def test_mes_seguinte_certo_e_errado(self):
        certo = corpo(fonte("vencimentosDoMes", mes="2026-12"),
                      resposta="Em dezembro de 2026: 1 guia, vence em 15/12/2026.")
        self.assertEqual(self.classificar("g1-01", certo), "certa")
        self.assertEqual(self.classificar("g1-01", corpo(
            fonte("vencimentosDoMes", mes="2027-01")), DEZ), "certa")
        mes_atual = corpo(fonte("vencimentosDoMes", mes="2026-11"))
        self.assertEqual(self.classificar("g1-01", mes_atual), "errada")
        guia_deste_mes = corpo(fonte("vencimentosDoMes", mes="2026-12"),
                               resposta="Guia de ICMS vence em 20/11/2026.")
        self.assertEqual(self.classificar("g1-01", guia_deste_mes), "errada")
        com_proximos = corpo(fonte("vencimentosDoMes", mes="2026-12"),
                             fonte("proximosVencimentos", dias=30))
        self.assertEqual(self.classificar("g1-01", com_proximos), "errada")

    def test_mes_corrente_ate_o_fim_do_mes(self):
        self.assertEqual(self.classificar("g2-01", corpo(
            fonte("vencimentosDoMes", mes="2026-11"))), "certa")
        self.assertEqual(self.classificar("g2-01", corpo(
            fonte("proximosVencimentos", dias=21))), "certa")
        self.assertEqual(self.classificar("g2-01", corpo(
            fonte("proximosVencimentos", dias=0))), "errada")
        self.assertEqual(self.classificar("g2-01", corpo(
            fonte("vencimentosDoMes", mes="2026-11"),
            resposta="E a próxima vence em 05/12/2026.")), "errada")

    def test_trimestre_nao_e_o_do_calendario(self):
        movel = corpo(fonte("consultarCreditos", dataInicio="2026-08-01", dataFim="2026-10-31"))
        civil = corpo(fonte("consultarCreditos", dataInicio="2026-07-01", dataFim="2026-09-30"))
        noventa = corpo(fonte("consultarCreditos", dataInicio="2026-08-11", dataFim="2026-11-09"))
        self.assertEqual(self.classificar("g3-03", movel), "certa")
        self.assertEqual(self.classificar("g3-03", civil), "errada")
        self.assertEqual(self.classificar("g3-03", noventa), "errada")

    def test_apuracao_do_trimestre_exige_os_tres_meses(self):
        tres = corpo(*(fonte("consultarApuracao", mes=m)
                       for m in ("2026-08", "2026-09", "2026-10")))
        self.assertEqual(self.classificar("g3-06", tres), "certa")
        dois = corpo(*(fonte("consultarApuracao", mes=m) for m in ("2026-09", "2026-10")))
        self.assertEqual(self.classificar("g3-06", dois), "errada")
        com_atual = corpo(*(fonte("consultarApuracao", mes=m)
                            for m in ("2026-08", "2026-09", "2026-10", "2026-11")))
        self.assertEqual(self.classificar("g3-06", com_atual), "errada")

    def test_intervalo_aberto_nao_vira_mes_fechado(self):
        aberto = corpo(fonte("totalVendasPorProduto", descricaoOuNcm="soja",
                             dataInicio="2026-08-01", dataFim="2026-11-09"))
        fechado = corpo(fonte("totalVendasPorProduto", descricaoOuNcm="soja",
                              dataInicio="2026-08-01", dataFim="2026-08-31"))
        self.assertEqual(self.classificar("g4-01", aberto), "certa")
        self.assertEqual(self.classificar("g4-01", fechado), "errada")

    def test_safra_aceita_o_ano_safra_ou_pergunta_de_volta(self):
        safra = corpo(fonte("totalVendasPorProduto", descricaoOuNcm="soja",
                            dataInicio="2025-07-01", dataFim="2026-06-30"))
        este_ano = corpo(fonte("totalVendasPorProduto", descricaoOuNcm="soja",
                               dataInicio="2026-01-01", dataFim="2026-11-09"))
        pergunta = corpo(resposta="De qual período você fala: a safra vai de quando a quando?")
        self.assertEqual(self.classificar("g4-09", safra), "certa")
        self.assertEqual(self.classificar("g4-09", este_ano), "errada")
        self.assertEqual(self.classificar("g4-09", pergunta), "certa")
        fora = dict(pergunta, rota="FORA_DE_ESCOPO")
        self.assertEqual(self.classificar("g4-09", fora), "errada")

    def test_dois_periodos_exigem_as_duas_fontes(self):
        ago = fonte("totalVendasPorProduto", descricaoOuNcm="soja", dataInicio="2026-08-01",
                    dataFim="2026-08-31")
        setembro = fonte("totalVendasPorProduto", descricaoOuNcm="soja",
                         dataInicio="2026-09-01", dataFim="2026-09-30")
        self.assertEqual(self.classificar("g4-10", corpo(ago, setembro)), "certa")
        self.assertEqual(self.classificar("g4-10", corpo(setembro)), "errada")

    def test_regra_com_mes_seguinte_vai_para_a_lei_mesmo_descartada(self):
        descartada = corpo(rota="LEGISLACAO",
                           resposta="Não consegui confirmar uma resposta com a legislação.")
        self.assertEqual(self.classificar("g4-12", descartada), "certa")
        dados = corpo(fonte("vencimentosDoMes", mes="2026-12"), rota="LEGISLACAO")
        self.assertEqual(self.classificar("g4-12", dados), "errada")
        self.assertEqual(self.classificar("g4-12", corpo(rota="DADOS")), "errada")

    def test_controles(self):
        self.assertEqual(self.classificar("c-04", corpo(fonte(
            "totalVendasPorProduto", descricaoOuNcm="milho", dataInicio="2026-09-01",
            dataFim="2026-09-30"))), "certa")
        lei = corpo(rota="LEGISLACAO", citacoes=[{"norma": "LC 214/2025", "dispositivo": "art. 31"}])
        self.assertEqual(self.classificar("c-13", lei), "certa")
        descartada = dict(lei, resposta="Não consegui confirmar uma resposta com a legislação.")
        self.assertEqual(self.classificar("c-13", descartada), "errada")
        self.assertEqual(self.classificar("c-13", corpo(rota="LEGISLACAO")), "errada")
        self.assertEqual(self.classificar("c-17", corpo(rota="FORA_DE_ESCOPO")), "certa")
        self.assertEqual(self.classificar("c-17", corpo(rota="DADOS")), "errada")

    def test_resposta_de_dados_descartada_e_errada(self):
        descartada = corpo(fonte("vencimentosDoMes", mes="2026-12"),
                           resposta="Não consegui confirmar os valores da resposta.")
        self.assertEqual(self.classificar("g1-01", descartada), "errada")

    def test_falhas_tecnicas(self):
        p = self.perguntas["g1-01"]
        self.assertEqual(pontuar.classificar(p, registro("g1-01", None, status=503), NOV),
                         "tecnica")
        fallback = dict(corpo(), modoFallback=True)
        self.assertEqual(pontuar.classificar(p, registro("g1-01", fallback), NOV), "tecnica")
        cota = {"errors": [{"code": "LIMITE_USO_ASSISTENTE"}]}
        self.assertEqual(pontuar.classificar(p, registro("g1-01", cota), NOV), "tecnica")


class ConjuntoV2(unittest.TestCase):
    """Conjunto v2 (regra-de-decisao-v2.md): tamanhos, ids próprios e o critério de cada grupo."""

    ATALHOS = {"quanto eu tenho que pagar este mes", "quando vence a minha proxima guia",
               "o que muda para mim com o ibs e o cbs"}

    @classmethod
    def setUpClass(cls):
        cls.conjunto = pontuar.carregar_conjunto(CONJUNTO_V2)
        cls.perguntas = {p["id"]: p for p in cls.conjunto["perguntas"]}

    def ids(self, grupo: str) -> list[str]:
        return [i for i, p in self.perguntas.items() if p["grupo"] == grupo]

    def classificar(self, ident: str, corpo_: dict, data: dt.date = NOV) -> str:
        return pontuar.classificar(self.perguntas[ident], registro(ident, corpo_, data), data)

    def confere(self, ident: str, casos: list, data: dt.date = NOV) -> None:
        for esperado, corpo_ in casos:
            self.assertEqual(self.classificar(ident, corpo_, data), esperado, (ident, corpo_))

    # --- forma do conjunto -------------------------------------------------------------------

    def test_versao_congelada_e_valida_em_qualquer_dia(self):
        self.assertEqual(self.conjunto["versao"], "ceres-452-v2")
        dia = dt.date(2026, 1, 1)
        while dia <= dt.date(2027, 12, 31):
            pontuar.validar_conjunto(self.conjunto, dia)
            dia += dt.timedelta(days=1)

    def test_tamanhos_da_regra_v2(self):
        contagem = {}
        for p in self.conjunto["perguntas"]:
            contagem[p["grupo"]] = contagem.get(p["grupo"], 0) + 1
        self.assertEqual(contagem, {"mes_seguinte": 6, "mes_corrente": 6, "trimestre": 6,
                                    "aberto": 10, "controle_dados": 8, "controle_lei": 2,
                                    "controle_fora": 2})

    def test_ids_e_textos_proprios_e_criterio_explicito(self):
        v1 = pontuar.carregar_conjunto(CONJUNTO)["perguntas"]
        self.assertFalse({p["id"] for p in v1} & set(self.perguntas))
        textos_v1 = {pontuar.normalizar(p["pergunta"]).strip(" ?.!") for p in v1}
        for ident, p in self.perguntas.items():
            self.assertTrue(ident.startswith("v2-"), ident)
            self.assertTrue(p.get("criterio", "").strip(), ident)
            self.assertNotIn(pontuar.normalizar(p["pergunta"]).strip(" ?.!"), textos_v1, ident)

    def test_nenhum_texto_de_atalho_nem_de_aquecimento(self):
        import executar
        proibidos = self.ATALHOS | {pontuar.normalizar(t).strip(" ?.!")
                                    for t in executar.PERGUNTAS_DE_AQUECIMENTO}
        for ident, p in self.perguntas.items():
            self.assertNotIn(pontuar.normalizar(p["pergunta"]).strip(" ?.!"), proibidos, ident)

    def test_vocabulario_dos_grupos(self):
        def textos(grupo):
            return [pontuar.normalizar(self.perguntas[i]["pergunta"]) for i in self.ids(grupo)]
        for expressao in ("ultimo trimestre", "ultimos tres meses", "ultimos 3 meses"):
            self.assertEqual(sum(expressao in t for t in textos("trimestre")), 2, expressao)
        aberto = " ".join(textos("aberto"))
        for marca in ("desde", "ate o fim", "antes de", "a partir de", "pra ca", "pra tras",
                      "safra"):
            self.assertIn(marca, aberto, marca)
        regras = [i for i, p in self.perguntas.items() if p["esperado"].get("regra")]
        self.assertEqual(len(regras), 2)
        for ident in regras:
            self.assertEqual(self.perguntas[ident]["grupo"], "aberto")
            self.assertIn("mes seguinte", pontuar.normalizar(self.perguntas[ident]["pergunta"]))

    def test_execucao_do_v1_nao_pontua_no_v2(self):
        with self.assertRaises(SystemExit):
            pontuar.pontuar(self.conjunto, [registro("g1-01", corpo())])

    def test_execucao_de_outro_conjunto_recusada_pelo_hash(self):
        certo = corpo(fonte("vencimentosDoMes", mes="2026-12"))
        linha = registro("v2-g1-01", certo, conjunto_sha256=self.conjunto["_sha256"])
        self.assertEqual(pontuar.pontuar(self.conjunto, [linha])["por_grupo"]["mes_seguinte"]
                         ["certas"], 1)
        sem_campo = registro("v2-g1-01", certo)
        self.assertEqual(pontuar.pontuar(self.conjunto, [sem_campo])["por_grupo"]
                         ["mes_seguinte"]["certas"], 1)
        for outro in (registro("v2-g1-01", certo, conjunto_sha256="0" * 64),
                      registro("aquecimento", corpo(), aquecimento=True,
                               conjunto_sha256="0" * 64)):
            with self.assertRaisesRegex(SystemExit, "outro conjunto"):
                pontuar.pontuar(self.conjunto, [outro])

    # --- critério por grupo ------------------------------------------------------------------

    def test_mes_seguinte(self):
        dez = fonte("vencimentosDoMes", mes="2026-12")
        for ident in self.ids("mes_seguinte"):
            self.confere(ident, [
                ("certa", corpo(dez, resposta="Em dezembro de 2026 não há guia a vencer.")),
                ("certa", corpo(dez, fonte("listarNotas", dataInicio="2026-12-01",
                                           dataFim="2026-12-31"))),
                ("errada", corpo(fonte("vencimentosDoMes", mes="2026-11"))),
                ("errada", corpo(dez, fonte("vencimentosDoMes", mes="2026-11"))),
                ("errada", corpo(dez, fonte("guiasEmAtraso"))),
                ("errada", corpo(dez, fonte("proximosVencimentos", dias=45))),
                ("errada", corpo(dez, fonte("listarAlertas"))),
                ("errada", corpo(dez, fonte("resumoDoProdutor"))),
                ("errada", corpo(dez, fonte("listarNotas", dataInicio="2026-11-01",
                                            dataFim="2026-11-09"))),
                ("errada", corpo(dez, resposta="A guia do Funrural vence em 20/11/2026.")),
                ("errada", corpo(dez, resposta="Não consegui confirmar os valores da resposta.")),
                ("errada", corpo()),
            ])
            self.confere(ident, [("certa", corpo(fonte("vencimentosDoMes", mes="2027-01"))),
                                 ("errada", corpo(dez))], DEZ)

    def test_mes_corrente(self):
        for ident in self.ids("mes_corrente"):
            self.confere(ident, [
                ("certa", corpo(fonte("vencimentosDoMes", mes="2026-11"),
                                resposta="Em atraso desde 20/10/2026; vence 20/11/2026.")),
                ("certa", corpo(fonte("proximosVencimentos", dias=21))),
                ("errada", corpo(fonte("proximosVencimentos", dias=30))),
                ("errada", corpo(fonte("vencimentosDoMes", mes="2026-12"))),
                ("errada", corpo(fonte("vencimentosDoMes", mes="2026-11"),
                                 resposta="E a próxima vence em 05/12/2026.")),
                ("errada", corpo(fonte("guiasEmAtraso"))),
            ])

    def test_trimestre_sao_os_tres_meses_fechados(self):
        casos = {"v2-g3-01": ("totalVendasPorProduto", {"descricaoOuNcm": "algodão em caroço"}),
                 "v2-g3-02": ("totalComprasPorInsumo", {"descricaoOuNcm": "óleo diesel"}),
                 "v2-g3-04": ("consultarRetencoes", {}),
                 "v2-g3-05": ("consultarCreditos", {}),
                 "v2-g3-06": ("listarNotas", {"tipo": "SAIDA"})}
        self.assertEqual(set(casos) | {"v2-g3-03"}, set(self.ids("trimestre")))
        for ident, (funcao, extra) in casos.items():
            def f(inicio, fim, **outros):
                return corpo(fonte(funcao, dataInicio=inicio, dataFim=fim, **{**extra, **outros}))
            self.confere(ident, [("certa", f("2026-08-01", "2026-10-31")),
                                 ("errada", f("2026-07-01", "2026-09-30")),
                                 ("errada", f("2026-08-11", "2026-11-09")),
                                 ("errada", f("2026-08-01", "2026-11-09"))])
            self.confere(ident, [("certa", f("2026-09-01", "2026-11-30")),
                                 ("errada", f("2026-08-01", "2026-10-31"))], DEZ)
            # Os dois períodos na mesma resposta: "nem o trimestre do calendário nem os 90 dias".
            certo = f("2026-08-01", "2026-10-31")["fontesDados"][0]
            outro = {"descricaoOuNcm": "soja"}
            self.confere(ident, [
                ("errada", corpo(certo, *f("2026-07-01", "2026-09-30")["fontesDados"])),
                ("errada", corpo(certo, *f("2026-08-11", "2026-11-09")["fontesDados"])),
                ("errada", corpo(certo, fonte("totalVendasPorProduto", dataInicio="2026-08-10",
                                              dataFim="2026-11-09", **outro))),
                ("certa", corpo(certo, fonte("totalVendasPorProduto", dataInicio="2026-08-01",
                                             dataFim="2026-10-31", **outro))),
                ("certa", corpo(certo, fonte("consultarApuracao", mes="2026-11")))])
        produto_errado = corpo(fonte("totalComprasPorInsumo", descricaoOuNcm="ureia",
                                     dataInicio="2026-08-01", dataFim="2026-10-31"))
        self.assertEqual(self.classificar("v2-g3-02", produto_errado), "errada")
        sem_tipo = corpo(fonte("listarNotas", dataInicio="2026-08-01", dataFim="2026-10-31"))
        self.assertEqual(self.classificar("v2-g3-06", sem_tipo), "errada")

    def test_apuracao_dos_ultimos_3_meses(self):
        def ap(*meses):
            return corpo(*(fonte("consultarApuracao", mes=m) for m in meses))
        self.confere("v2-g3-03", [("certa", ap("2026-08", "2026-09", "2026-10")),
                                  ("errada", ap("2026-09", "2026-10")),
                                  ("errada", ap("2026-08", "2026-09", "2026-10", "2026-11")),
                                  ("errada", ap("2026-07", "2026-08", "2026-09"))])
        self.confere("v2-g3-03", [("certa", ap("2026-09", "2026-10", "2026-11"))], DEZ)

    def test_intervalos_abertos(self):
        def f(funcao, inicio, fim, **extra):
            return corpo(fonte(funcao, dataInicio=inicio, dataFim=fim, **extra))
        diesel = {"descricaoOuNcm": "diesel"}
        self.confere("v2-g4-01", [
            ("certa", f("totalComprasPorInsumo", "2026-07-01", "2026-11-09", **diesel)),
            ("certa", f("totalComprasPorInsumo", "2026-07-01", "2026-11-08", **diesel)),
            ("certa", f("totalComprasPorInsumo", "2026-07-01", "2026-11-30", **diesel)),
            ("errada", f("totalComprasPorInsumo", "2026-07-01", "2026-07-31", **diesel)),
            ("errada", f("totalComprasPorInsumo", "2025-07-01", "2026-11-09", **diesel))])
        self.confere("v2-g4-01", [
            ("certa", f("totalComprasPorInsumo", "2026-07-01", "2027-02-10", **diesel))],
            dt.date(2027, 2, 10))
        self.confere("v2-g4-02", [
            ("certa", f("consultarCreditos", "2026-08-01", "2026-11-09")),
            ("errada", f("consultarCreditos", "2026-08-01", "2026-08-31"))])
        self.confere("v2-g4-03", [
            ("certa", f("listarNotas", "2026-09-01", "2026-11-09", tipo="SAIDA")),
            ("errada", f("listarNotas", "2026-09-01", "2026-11-09")),
            ("errada", f("listarNotas", "2026-09-01", "2026-09-30", tipo="SAIDA"))])
        self.confere("v2-g4-04", [
            ("certa", f("totalVendasPorProduto", "2026-01-01", "2026-06-30",
                        descricaoOuNcm="soja")),
            ("errada", f("totalVendasPorProduto", "2026-06-01", "2026-06-30",
                         descricaoOuNcm="soja")),
            ("errada", f("totalVendasPorProduto", "2026-01-01", "2026-11-09",
                         descricaoOuNcm="soja"))])
        self.confere("v2-g4-05", [
            ("certa", f("totalVendasPorProduto", "2026-01-01", "2026-07-31",
                        descricaoOuNcm="milho")),
            ("errada", f("totalVendasPorProduto", "2026-07-01", "2026-07-31",
                         descricaoOuNcm="milho")),
            ("errada", f("totalVendasPorProduto", "2026-01-01", "2026-08-31",
                         descricaoOuNcm="milho"))])
        self.confere("v2-g4-06", [
            ("certa", f("consultarRetencoes", "2026-01-01", "2026-07-31")),
            ("errada", f("consultarRetencoes", "2026-07-01", "2026-07-31")),
            ("errada", f("consultarRetencoes", "2026-07-01", "2026-11-09"))])

    def test_safra_e_dois_periodos(self):
        def milho(inicio, fim):
            return fonte("totalVendasPorProduto", descricaoOuNcm="milho", dataInicio=inicio,
                         dataFim=fim)
        volta = corpo(resposta="Qual período você chama de safra 25/26: de quando a quando?")
        self.confere("v2-g4-07", [
            ("certa", corpo(milho("2025-07-01", "2026-06-30"))),
            ("certa", corpo(milho("2025-09-01", "2026-11-09"))),
            ("errada", corpo(milho("2026-01-01", "2026-11-09"))),
            ("certa", volta),
            ("errada", dict(volta, rota="FORA_DE_ESCOPO"))])

        def diesel(inicio, fim):
            return fonte("totalComprasPorInsumo", descricaoOuNcm="diesel", dataInicio=inicio,
                         dataFim=fim)
        maio, outubro = diesel("2026-05-01", "2026-05-31"), diesel("2026-10-01", "2026-10-31")
        self.confere("v2-g4-08", [("certa", corpo(maio, outubro)),
                                  ("errada", corpo(outubro)),
                                  ("errada", corpo(diesel("2026-05-01", "2026-10-31")))])

    def test_regra_com_mes_seguinte(self):
        for ident in ("v2-g4-09", "v2-g4-10"):
            self.confere(ident, [
                ("certa", corpo(rota="LEGISLACAO",
                                resposta="Não consegui confirmar uma resposta com a legislação.")),
                ("errada", corpo(fonte("vencimentosDoMes", mes="2026-12"), rota="LEGISLACAO")),
                ("errada", corpo(rota="DADOS")),
                ("errada", corpo(rota="MISTO"))])

    def test_controles(self):
        def f(funcao, inicio, fim, **extra):
            return corpo(fonte(funcao, dataInicio=inicio, dataFim=fim, **extra))
        self.confere("v2-c-01", [
            ("certa", f("totalComprasPorInsumo", "2026-10-01", "2026-10-31",
                        descricaoOuNcm="fungicida")),
            ("certa", f("totalComprasPorInsumo", "2026-10-01", "2026-10-31",
                        descricaoOuNcm="38089219")),
            ("errada", f("totalComprasPorInsumo", "2026-10-01", "2026-10-31",
                         descricaoOuNcm="3808")),
            ("errada", f("totalComprasPorInsumo", "2026-09-01", "2026-09-30",
                         descricaoOuNcm="fungicida"))])
        self.confere("v2-c-02", [("certa", corpo(fonte("consultarApuracao", mes="2026-09"))),
                                 ("errada", corpo(fonte("consultarApuracao", mes="2026-10")))])
        self.confere("v2-c-03", [
            ("certa", f("totalVendasPorProduto", "2026-09-01", "2026-09-30",
                        descricaoOuNcm="algodão")),
            ("errada", f("totalVendasPorProduto", "2025-09-01", "2025-09-30",
                         descricaoOuNcm="algodão"))])
        self.confere("v2-c-04", [("certa", f("consultarRetencoes", "2026-07-01", "2026-07-31")),
                                 ("errada", f("consultarRetencoes", "2026-07-01", "2026-11-09"))])
        self.confere("v2-c-05", [
            ("certa", f("totalVendasPorProduto", "2026-01-01", "2026-11-09",
                        descricaoOuNcm="soja")),
            ("certa", f("totalVendasPorProduto", "2026-01-01", "2026-12-31",
                        descricaoOuNcm="soja")),
            ("errada", f("totalVendasPorProduto", "2025-11-10", "2026-11-09",
                         descricaoOuNcm="soja"))])
        self.confere("v2-c-06", [("certa", f("consultarCreditos", "2025-01-01", "2025-12-31")),
                                 ("errada", f("consultarCreditos", "2026-01-01", "2026-11-09"))])
        self.confere("v2-c-07", [("certa", corpo(fonte("guiasEmAtraso"))),
                                 ("certa", corpo(fonte("resumoDoProdutor"))),
                                 ("certa", corpo(fonte("vencimentosDoMes", mes="2026-11"))),
                                 ("errada", corpo(fonte("vencimentosDoMes", mes="2026-12"))),
                                 ("errada", corpo(fonte("listarNotas")))])
        self.confere("v2-c-08", [("certa", corpo(fonte("listarNotas", tipo="SAIDA"))),
                                 ("errada", corpo(fonte("listarNotas", tipo="ENTRADA")))])
        lei = corpo(rota="LEGISLACAO", citacoes=[{"norma": "LC 214/2025", "dispositivo": "art. 138"}])
        extenso = corpo(rota="LEGISLACAO",
                        citacoes=[{"norma": "Lei Complementar nº 214/2025", "dispositivo": "art. 31"}])
        for ident in self.ids("controle_lei"):
            self.confere(ident, [
                ("certa", lei), ("certa", extenso),
                ("errada", dict(lei, resposta="Não consegui confirmar uma resposta com a lei.")),
                ("errada", corpo(rota="LEGISLACAO")),
                ("errada", dict(lei, rota="DADOS")),
                ("errada", corpo(rota="LEGISLACAO",
                                 citacoes=[{"norma": "EC 132/2023", "dispositivo": "art. 1"}]))])
        for ident in self.ids("controle_fora"):
            self.confere(ident, [("certa", corpo(rota="FORA_DE_ESCOPO")),
                                 ("errada", corpo(rota="DADOS"))])


def resumo(certas_por_grupo: dict, commit: str = "a" * 40, data: str = "2026-11-09",
           sem: tuple = (), aquecido: bool = True) -> dict:
    """Resumo sintético: em cada grupo, `n` perguntas certas de `total`."""
    por_pergunta, por_grupo = {}, {}
    for grupo in pontuar.GRUPOS:
        certas, total = certas_por_grupo.get(grupo, (2, 2))
        por_grupo[grupo] = {"certas": certas, "total": total, "sem_medicao": 0}
        for i in range(total):
            ident = f"{grupo}-{i}"
            resultado = "sem_medicao" if ident in sem else ("certa" if i < certas else "errada")
            por_pergunta[ident] = {"grupo": grupo, "resultado": resultado}
    return {"sha256_conjunto": "x", "datas_referencia": [data], "commits": [commit],
            "modelos": [["qwen3:4b-instruct", "d1"]], "aquecido": aquecido,
            "mediana_segundos": {}, "por_grupo": por_grupo, "por_pergunta": por_pergunta}


class Votacao(unittest.TestCase):

    def setUp(self):
        self.conjunto = pontuar.carregar_conjunto(CONJUNTO)

    def test_maioria_empate_e_sem_medicao(self):
        certo = corpo(fonte("vencimentosDoMes", mes="2026-12"))
        errado = corpo(fonte("vencimentosDoMes", mes="2026-11"))
        linhas = [registro("g1-01", certo), registro("g1-01", certo), registro("g1-01", errado),
                  registro("g1-02", certo), registro("g1-02", errado),
                  registro("g1-02", None, status=503),
                  registro("g1-03", None, status=0), registro("g1-03", None, status=503),
                  registro("aquecimento", corpo(), aquecimento=True)]
        r = pontuar.pontuar(self.conjunto, linhas)
        self.assertEqual(r["por_pergunta"]["g1-01"]["resultado"], "certa")
        self.assertEqual(r["por_pergunta"]["g1-02"]["resultado"], "errada")
        self.assertEqual(r["por_pergunta"]["g1-03"]["resultado"], "sem_medicao")
        self.assertTrue(r["aquecido"])
        frio = pontuar.pontuar(self.conjunto, [
            registro("aquecimento", None, status=503, aquecimento=True),
            registro("g1-01", certo, modelo_digest=None)])
        self.assertFalse(frio["aquecido"])
        self.assertEqual(r["commits"], ["a" * 40])
        self.assertEqual(r["datas_referencia"], ["2026-11-09"])

    def test_linha_sem_data_ou_fora_do_conjunto_quebra(self):
        with self.assertRaises(SystemExit):
            pontuar.pontuar(self.conjunto, [registro("zz-01", corpo())])
        linha = registro("g1-01", corpo())
        del linha["data_referencia"]
        with self.assertRaises(SystemExit):
            pontuar.pontuar(self.conjunto, [linha])


class Regra(unittest.TestCase):

    def base(self, **kw):
        grupos = {g: (1, 4) for g in pontuar.GRUPOS_CORRECAO}
        grupos.update(kw)
        return resumo(grupos, commit="a" * 40)

    def correcao(self, **kw):
        grupos = {g: (3, 4) for g in pontuar.GRUPOS_CORRECAO}
        grupos.update(kw)
        return resumo(grupos, commit="b" * 40)

    def test_entra_quando_todos_sobem_e_nenhum_controle_piora(self):
        decisao = pontuar.comparar(self.base(), self.correcao())
        self.assertEqual(decisao["decisao"], "ENTRA", decisao)

    def test_empate_num_grupo_nao_entra(self):
        decisao = pontuar.comparar(self.base(), self.correcao(trimestre=(1, 4)))
        self.assertEqual(decisao["decisao"], "NAO_ENTRA")
        self.assertFalse(decisao["grupos_da_correcao"]["trimestre"]["sobe"])

    def test_grupo_ja_no_teto_precisa_ficar_no_teto(self):
        no_teto = pontuar.comparar(self.base(aberto=(4, 4)), self.correcao(aberto=(4, 4)))
        self.assertEqual(no_teto["decisao"], "ENTRA")
        caiu = pontuar.comparar(self.base(aberto=(4, 4)), self.correcao(aberto=(3, 4)))
        self.assertEqual(caiu["decisao"], "NAO_ENTRA")

    def test_controle_que_piora_barra(self):
        decisao = pontuar.comparar(self.base(controle_lei=(2, 2)),
                                   self.correcao(controle_lei=(1, 2)))
        self.assertEqual(decisao["decisao"], "NAO_ENTRA")
        self.assertEqual(decisao["controles_que_pioraram"], ["controle_lei-1"])

    def test_inconclusiva(self):
        outro_dia = self.correcao()
        outro_dia["datas_referencia"] = ["2026-11-10"]
        self.assertEqual(pontuar.comparar(self.base(), outro_dia)["decisao"], "INCONCLUSIVA")
        mesmo_commit = self.correcao()
        mesmo_commit["commits"] = ["a" * 40]
        self.assertEqual(pontuar.comparar(self.base(), mesmo_commit)["decisao"], "INCONCLUSIVA")
        sem = resumo({g: (3, 4) for g in pontuar.GRUPOS_CORRECAO}, commit="b" * 40,
                     sem=("trimestre-3",))
        self.assertEqual(pontuar.comparar(self.base(), sem)["decisao"], "INCONCLUSIVA")
        outro_modelo = self.correcao()
        outro_modelo["modelos"] = [["qwen3:4b-instruct", "d2"]]
        self.assertEqual(pontuar.comparar(self.base(), outro_modelo)["decisao"], "INCONCLUSIVA")

    def test_latencia_so_com_os_dois_aquecidos(self):
        frio = self.correcao()
        frio["aquecido"] = False
        self.assertIsNone(pontuar.comparar(self.base(), frio)["latencia_mediana_segundos"])
        self.assertIsNotNone(
            pontuar.comparar(self.base(), self.correcao())["latencia_mediana_segundos"])


class ForaDaDecisao(unittest.TestCase):
    """Regra da v2f (regra-de-decisao-v2f.md): a v2-g4-06 roda, mas fica fora da decisão."""

    FORA = ("v2-g4-06",)

    @classmethod
    def setUpClass(cls):
        cls.conjunto = pontuar.carregar_conjunto(CONJUNTO_V2)

    def linhas(self) -> list[dict]:
        julho_sozinho = corpo(fonte("consultarRetencoes", dataInicio="2026-07-01",
                                    dataFim="2026-07-31"), rota="MISTO")
        desde_janeiro = corpo(fonte("consultarRetencoes", dataInicio="2026-01-01",
                                    dataFim="2026-07-31"), rota="MISTO")
        diesel = corpo(fonte("totalComprasPorInsumo", descricaoOuNcm="diesel",
                             dataInicio="2026-07-01", dataFim="2026-11-09"))
        estouro = {"resposta": "Sem o modelo.", "modoFallback": True, "fontesDados": []}
        return [
            registro("v2-g4-06", estouro, repeticao=1, tentativa=1, segundos=123.5,
                     falha_tecnica=True),
            registro("v2-g4-06", estouro, repeticao=1, tentativa=2, segundos=0.2,
                     falha_tecnica=True),
            registro("v2-g4-06", desde_janeiro, repeticao=2, segundos=92.7),
            registro("v2-g4-06", julho_sozinho, repeticao=3, segundos=18.4),
            registro("v2-g4-01", diesel, segundos=10.0),
        ]

    def test_sai_do_grupo_e_aparece_a_parte_com_o_tempo(self):
        r = pontuar.pontuar(self.conjunto, self.linhas(), self.FORA)
        self.assertNotIn("v2-g4-06", r["por_pergunta"])
        self.assertEqual(r["por_grupo"]["aberto"]["total"], 9)
        self.assertEqual(r["por_grupo"]["aberto"]["certas"], 1)
        # A mediana do grupo só tem a v2-g4-01: nem os 92,7 nem os 18,4 s da v2-g4-06.
        self.assertEqual(r["mediana_segundos"]["aberto"], 10.0)
        separada = r["fora_da_decisao"]["v2-g4-06"]
        self.assertEqual(separada["grupo"], "aberto")
        # 1 certa e 1 errada nas válidas: empate, errada, como na regra v2.
        self.assertEqual((separada["resultado"], separada["validas"], separada["tecnicas"]),
                         ("errada", 2, 2))
        self.assertEqual([(e["repeticao"], e["tentativa"], e["resultado"], e["segundos"])
                          for e in separada["execucoes"]],
                         [(1, 1, "tecnica", 123.5), (1, 2, "tecnica", 0.2),
                          (2, 1, "certa", 92.7), (3, 1, "errada", 18.4)])

    def test_sem_a_opcao_o_resumo_e_o_de_antes(self):
        r = pontuar.pontuar(self.conjunto, self.linhas())
        self.assertNotIn("fora_da_decisao", r)
        self.assertIn("v2-g4-06", r["por_pergunta"])
        self.assertEqual(r["por_grupo"]["aberto"]["total"], 10)

    def test_id_fora_do_conjunto_quebra(self):
        with self.assertRaises(SystemExit):
            pontuar.pontuar(self.conjunto, self.linhas(), ("v2-g4-99",))

    def test_so_tecnicas_na_pergunta_fora_nao_tornam_inconclusiva(self):
        so_estouro = [linha for linha in self.linhas() if linha["id"] != "v2-g4-06"
                      or linha["tentativa"] == 2 and linha["repeticao"] == 1]
        base = pontuar.pontuar(self.conjunto, self.linhas(), self.FORA)
        correcao = pontuar.pontuar(self.conjunto, [dict(linha, commit="b" * 40)
                                                   for linha in so_estouro], self.FORA)
        self.assertEqual(correcao["fora_da_decisao"]["v2-g4-06"]["resultado"], "sem_medicao")
        decisao = pontuar.comparar(base, correcao)
        self.assertFalse(any("v2-g4-06" in p for p in decisao["problemas"]), decisao["problemas"])
        self.assertEqual(decisao["fora_da_decisao"]["v2-g4-06"]["correcao"]["resultado"],
                         "sem_medicao")

    @staticmethod
    def resumos(aberto_base=(1, 4), aberto_correcao=(3, 4)) -> tuple[dict, dict]:
        """Base com 1 de 4 e correção com 3 de 4 em cada grupo da correção, salvo o aberto."""
        base = {g: (1, 4) for g in pontuar.GRUPOS_CORRECAO}
        correcao = {g: (3, 4) for g in pontuar.GRUPOS_CORRECAO}
        base["aberto"], correcao["aberto"] = aberto_base, aberto_correcao
        return resumo(base, commit="a" * 40), resumo(correcao, commit="b" * 40)

    def test_a_pergunta_fora_nao_faz_o_grupo_subir(self):
        base, correcao = self.resumos(aberto_base=(6, 9), aberto_correcao=(6, 9))
        base["fora_da_decisao"] = {"v2-g4-06": {"grupo": "aberto", "resultado": "errada"}}
        correcao["fora_da_decisao"] = {"v2-g4-06": {"grupo": "aberto", "resultado": "certa"}}
        decisao = pontuar.comparar(base, correcao)
        self.assertEqual(decisao["decisao"], "NAO_ENTRA")
        self.assertFalse(decisao["grupos_da_correcao"]["aberto"]["sobe"])
        self.assertNotIn("v2-g4-06", decisao["subiram"])
        self.assertEqual(decisao["problemas"], [])
        self.assertEqual(decisao["fora_da_decisao"]["v2-g4-06"]["correcao"]["resultado"],
                         "certa")

    def test_bracos_com_perguntas_fora_diferentes_inconclui(self):
        base, correcao = self.resumos()
        correcao["fora_da_decisao"] = {"v2-g4-06": {"grupo": "aberto", "resultado": "certa"}}
        decisao = pontuar.comparar(base, correcao)
        self.assertEqual(decisao["decisao"], "INCONCLUSIVA")
        self.assertIn("os dois resumos tiraram perguntas diferentes da decisão",
                      decisao["problemas"])

    def test_sem_pergunta_fora_a_decisao_nao_ganha_campo(self):
        decisao = pontuar.comparar(*self.resumos())
        self.assertEqual(decisao["decisao"], "ENTRA")
        self.assertNotIn("fora_da_decisao", decisao)
        self.assertEqual(list(decisao)[-2:], ["problemas", "decisao"])


if __name__ == "__main__":
    unittest.main()
