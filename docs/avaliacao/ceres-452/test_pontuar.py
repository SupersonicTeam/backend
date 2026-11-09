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


if __name__ == "__main__":
    unittest.main()
