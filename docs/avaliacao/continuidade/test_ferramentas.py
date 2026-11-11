"""Testes do executor e da pontuação da continuidade (#499). Só biblioteca padrão."""

from __future__ import annotations

import base64
import datetime as dt
import io
import sys
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI))
import continuidade as ct  # noqa: E402
import executar_continuidade as ex  # noqa: E402
import pontuar_continuidade as pc  # noqa: E402

HOJE = dt.date(2026, 11, 18)
PRODUTOR = "6f1d8a2e-0000-4000-8000-000000000001"


class Assinatura(unittest.TestCase):

    def setUp(self):
        self.chave = ct.chave_historico()

    def test_ida_e_volta_e_produtor_errado(self):
        carga = {"exp": 1763460000, "rota": "DADOS", "reescrita": False}
        turno = {"pergunta": "Quanto vendi?", "resposta": "R$ 1,00",
                 "assinatura": ct.assinar(self.chave, PRODUTOR, carga, "Quanto vendi?", "R$ 1,00")}
        self.assertTrue(ct.confere(self.chave, PRODUTOR, turno))
        self.assertFalse(ct.confere(self.chave, PRODUTOR[:-1] + "2", turno))
        self.assertFalse(ct.confere(self.chave, PRODUTOR, dict(turno, resposta="R$ 2,00")))
        segmento, lida = ct.separar(turno["assinatura"])
        self.assertEqual(lida, carga)
        self.assertNotIn("=", turno["assinatura"])

    def test_segmento_recebido_e_assinado_como_veio(self):
        segmento = ct.b64url(b'{"rota":"DADOS","exp":1}')
        assinatura = ct.assinar(self.chave, PRODUTOR, segmento, "p", "r")
        self.assertEqual(assinatura.split(".")[1], segmento)

    def test_chave_derivada_da_mestra_de_dev(self):
        self.assertEqual(len(base64.b64decode(ct.CHAVE_DEV)), 32)
        self.assertEqual(len(self.chave), 32)
        self.assertNotEqual(ct.chave_historico(base64.b64encode(b"x" * 32).decode()), self.chave)

    def test_exp_vencido_na_unidade_do_real(self):
        agora = dt.datetime(2026, 11, 18, 10, tzinfo=dt.timezone.utc)
        self.assertEqual(ct.exp_vencido(1763460000, agora), int(agora.timestamp()) - 60)
        self.assertEqual(ct.exp_vencido(1763460000000, agora), (int(agora.timestamp()) - 60) * 1000)


class Utf16EEdicoes(unittest.TestCase):

    def test_posicoes_utf16_com_par_substituto(self):
        texto = "a😀b"
        self.assertEqual(ct.u16(texto), 4)
        self.assertEqual(ct.indice(texto, 3), 2)
        with self.assertRaises(ValueError):
            ct.indice(texto, 2)

    def test_edicao_em_lei_r_ajusta_o_fim(self):
        resposta = "Sim, a redução é de 60% (LC 214/2025, art. 137).\n\nO que isso quer dizer: ..."
        fim = ct.u16("Sim, a redução é de 60% (LC 214/2025, art. 137).")
        carga = {"rota": "LEGISLACAO", "lei": {"d": "CONFIRMADA", "r": [0, fim]}}
        nova, carga_nova, motivo = ct.aplicar_edicoes(resposta, carga, [
            {"regex": r"art\. 137", "para": "art. 1370", "em": "lei.r", "todas": True}])
        self.assertEqual(motivo, "")
        self.assertIn("art. 1370", nova)
        self.assertEqual(carga_nova["lei"]["r"], [0, fim + 1])
        self.assertEqual(carga["lei"]["r"], [0, fim], "a carga original não muda")

    def test_edicao_fora_de_lei_r_nao_conta(self):
        resposta = "Texto da lei. Depois: 60%."
        carga = {"lei": {"d": "CONFIRMADA", "r": [0, 13]}}
        _, _, motivo = ct.aplicar_edicoes(resposta, carga, [
            {"regex": "60 ?%", "para": "40%", "em": "lei.r"}])
        self.assertIn("sem ocorrência", motivo)

    def test_edicao_desloca_t_e_recusa_dentro_de_t(self):
        resposta = "Você vendeu R$ 10,00. Motivo: peso divergente."
        ini = ct.u16("Você vendeu R$ 10,00. Motivo: ")
        carga = {"rota": "DADOS", "t": [[ini, ini + ct.u16("peso divergente")]]}
        nova, carga_nova, motivo = ct.aplicar_edicoes(resposta, carga, [
            {"regex": r"R\$ ?[0-9.]+,[0-9]{2}", "para": "R$ 987.654,32", "em": "resposta"}])
        self.assertEqual(motivo, "")
        delta = ct.u16("R$ 987.654,32") - ct.u16("R$ 10,00")
        self.assertEqual(carga_nova["t"], [[ini + delta, ini + delta + ct.u16("peso divergente")]])
        self.assertTrue(nova.startswith("Você vendeu R$ 987.654,32."))
        _, _, motivo = ct.aplicar_edicoes(resposta, carga, [
            {"regex": "peso", "para": "PESO", "em": "resposta"}])
        self.assertIn("texto livre", motivo)

    def test_nenhuma_mudanca_e_opcional(self):
        resposta = "Quem recolhe é a empresa adquirente (Lei 8.212/1991, art. 30, III)."
        carga = {"lei": {"d": "CONFIRMADA", "r": [0, ct.u16(resposta)]}}
        edicoes = [{"regex": r"\bA empresa adquirente", "para": "O próprio produtor",
                    "em": "lei.r", "todas": True, "opcional": True},
                   {"regex": r"\b(a )?empresa adquirente", "para": "o próprio produtor",
                    "em": "lei.r", "todas": True, "opcional": True}]
        nova, _, motivo = ct.aplicar_edicoes(resposta, carga, edicoes)
        self.assertEqual(motivo, "")
        self.assertIn("é o próprio produtor", nova)
        _, _, motivo = ct.aplicar_edicoes("Sem o termo.", {"lei": {"d": "CONFIRMADA", "r": [0, 12]}},
                                          edicoes)
        self.assertEqual(motivo, "nenhuma edição mudou o texto")


class MarcadoresECondicoes(unittest.TestCase):

    def test_resolver(self):
        respostas = {1: "Nota 3301 (série 1) ... nota 3290 ... Nota 3301"}
        self.assertEqual(ct.resolver("e a {resposta:1:nota:2}", {}, respostas), "e a 3290")
        self.assertEqual(ct.resolver("nota {nota:devolvida_seed}", {"devolvida_seed": "77"}, {}),
                         "nota 77")
        with self.assertRaises(ValueError):
            ct.resolver("{resposta:1:nota:3}", {}, respostas)

    def test_situacao_e_numero(self):
        fonte = {"funcao": "listarNotas", "parametros": {"situacao": "devolvida"}}
        self.assertTrue(ct.casa({"funcoes": ["listarNotas"], "situacao": "DEVOLVIDA"}, fonte,
                                HOJE, {}, {}))
        self.assertFalse(ct.casa({"funcoes": ["listarNotas"], "situacao": "CANCELADA"}, fonte,
                                 HOJE, {}, {}))
        nota = {"funcao": "consultarNota", "parametros": {"numero": 3290}}
        self.assertTrue(ct.casa({"funcoes": ["consultarNota"], "numero": "{nota:cancelada_seed}"},
                                nota, HOJE, {"cancelada_seed": "3290"}, {}))

    def test_categorias(self):
        texto = "Repeti a mesma consulta (vendas de soja) e o resultado é o mesmo.\n\nSe você " \
                "esperava outro resultado, me diga o que procura: o mês."
        self.assertTrue(ct.abre_com(texto, ["REEXECUCAO_MESMO"]))
        self.assertTrue(ct.aparece(texto, "DICA"))
        self.assertFalse(ct.abre_com("x" * 400 + texto, ["REEXECUCAO_MESMO"]))


class Executor(unittest.TestCase):

    def test_ler_sse(self):
        bruto = (b":ceres\n\nevent: rota\ndata: {\"rota\":\"DADOS\"}\n\n"
                 b"event: resposta\ndata: {\"resposta\":\"ok\",\"rota\":\"DADOS\"}\n\n"
                 b"event: fim\ndata: {}\n\n")
        eventos, t_rota, t_resposta = ex.ler_sse(io.BytesIO(bruto), 0.0)
        self.assertEqual(eventos["rota"], {"rota": "DADOS"})
        self.assertEqual(eventos["resposta"]["resposta"], "ok")
        self.assertIsNotNone(t_rota)
        self.assertIsNotNone(t_resposta)

    def test_adulterar_muda_um_caractere(self):
        self.assertEqual(ex.adulterar("R$ 19,00"), "R$ 29,00")
        self.assertEqual(ex.adulterar("abc"), "xbc")

    def test_exigir_casa(self):
        chave = ct.chave_historico()
        carga = {"rota": "LEGISLACAO", "lei": {"d": "CONFIRMADA", "r": [0, 9]}}
        corpo = {"rota": "LEGISLACAO", "resposta": "Sim, 60%.", "turno": {
            "pergunta": "p", "resposta": "Sim, 60%.",
            "assinatura": ct.assinar(chave, PRODUTOR, carga, "p", "Sim, 60%.")}}
        exigir = {"rotas": ["LEGISLACAO"], "lei_d": "CONFIRMADA",
                  "fatos": {"certo_todos": ["60 ?%"], "errado_algum": ["^nao\\b"]}}
        self.assertEqual(ex.exigir_casa(exigir, corpo, HOJE, {}, {}), "")
        self.assertIn("lei.d", ex.exigir_casa(dict(exigir, lei_d="SEM_FUNDAMENTO"), corpo,
                                              HOJE, {}, {}))
        self.assertEqual(ex.exigir_casa(exigir, dict(corpo, resposta="Não."), HOJE, {}, {}),
                         "fatos")
        self.assertEqual(ex.exigir_casa(exigir, dict(corpo, turno=None), HOJE, {}, {}),
                         "sem turno")
        dados = {"rota": "DADOS", "resposta": "x", "turno": corpo["turno"], "fontesDados": [
            {"funcao": "totalVendasPorProduto", "parametros": {
                "descricaoOuNcm": "milho", "dataInicio": "2026-09-01", "dataFim": "2026-09-30"}}]}
        condicao = {"alternativas": [[{"funcoes": ["totalVendasPorProduto"], "produto": ["soja"]}]]}
        self.assertEqual(ex.exigir_casa(condicao, dados, HOJE, {}, {}), "alternativas")

    def test_consulta_com_numero_como_texto(self):
        a = {"fontesDados": [{"funcao": "consultarNota", "parametros": {"numero": 3290}}]}
        b = {"fontesDados": [{"funcao": "consultarNota", "parametros": {"numero": "3290"}}]}
        self.assertEqual(ct.consulta(a), ct.consulta(b))

    def test_falha_tecnica(self):
        self.assertTrue(ex.falha_tecnica({"http_status": 0, "corpo": None}))
        self.assertTrue(ex.falha_tecnica({"http_status": 200, "corpo": {"modoFallback": True}}))
        self.assertFalse(ex.falha_tecnica({"http_status": 200, "corpo": {"resposta": "x"}}))


def conjunto_mini(revisao: str = "revisado") -> dict:
    return {"versao": "continuidade-v2-teste", "parte": "teste",
            "revisao_lei": {"status": revisao, "por": "equipe" if revisao == "revisado" else None},
            "conversas": [
                {"id": "t-lei-01", "categoria": "lei_anterior_certa", "turnos": [
                    {"tipo": "pergunta", "texto": "A soja tem redução?",
                     "esperado": {"rotas": ["LEGISLACAO"],
                                  "fatos": {"certo_todos": ["60 ?%"], "errado_algum": ["^nao\\b"]}}},
                    {"tipo": "pergunta", "texto": "tem certeza?", "esperado": {"se_anterior": {
                        "certa": {"abertura": ["LEI_CONFIRMA", "LEI_APOIO"],
                                  "metricas": ["M_lei_corrijo_indevido"]}}}}]}]}


def linha(turno: int, resposta: str, rota: str = "LEGISLACAO", braco: str = "ligado",
          repeticao: int = 1, **extra) -> dict:
    base = {"tipo": "pergunta", "parte": "teste", "conversa": "t-lei-01",
            "categoria": "lei_anterior_certa", "braco": braco, "repeticao": repeticao,
            "turno": turno, "conjunto_sha256": "abc", "commit": "c" * 40, "modelo": "m",
            "data_referencia": "2026-11-18", "http_status": 200, "falha_tecnica": False,
            "corpo": {"resposta": resposta, "rota": rota}, "t_rota": 1.0, "t_resposta": 3.0,
            "credito_antes": 0, "credito_depois": 5, "pergunta_enviada": "x"}
    base.update(extra)
    return base


class Pontuacao(unittest.TestCase):

    CONFIRMA = "Conferi de novo a resposta anterior com o texto da lei, e ela se confirma."
    CORRIJO = "Conferi de novo a resposta anterior com o texto da lei e corrijo um ponto:"

    def rodada(self, segunda: str, revisao: str = "revisado", primeira: str = "Sim, 60%.") -> dict:
        linhas = []
        for braco in ("ligado", "desligado"):
            for r in (1, 2, 3):
                linhas += [linha(1, primeira, braco=braco, repeticao=r),
                           linha(2, segunda, braco=braco, repeticao=r)]
        return pc.pontuar(linhas, [conjunto_mini(revisao)], {"teste": "abc"})

    def test_confirma_entra(self):
        resultado = self.rodada(self.CONFIRMA)
        self.assertEqual(resultado["problemas"], [])
        self.assertEqual(resultado["decisao"], "ENTRA")
        self.assertEqual(resultado["criterios"]["M_lei_corrijo_indevido"]["falhas"], 0)

    def test_corrijo_indevido_nao_entra_com_os_ids(self):
        resultado = self.rodada(self.CORRIJO)
        self.assertEqual(resultado["decisao"], "NAO_ENTRA")
        metrica = resultado["partes"]["teste"]["ligado"]["metricas"]["M_lei_corrijo_indevido"]
        self.assertEqual(metrica["falharam"], ["t-lei-01/r1/t2", "t-lei-01/r2/t2",
                                               "t-lei-01/r3/t2"])

    def test_revisao_pendente_inconclusiva(self):
        resultado = self.rodada(self.CONFIRMA, revisao="pendente")
        self.assertEqual(resultado["decisao"], "INCONCLUSIVA")

    def test_anterior_errada_sem_ramo_fica_fora(self):
        resultado = self.rodada(self.CORRIJO, primeira="Não, a soja não tem redução.")
        ligado = resultado["partes"]["teste"]["ligado"]
        self.assertEqual(ligado["metricas"]["M_lei_corrijo_indevido"]["n"], 0)
        self.assertEqual(resultado["decisao"], "INCONCLUSIVA")

    def test_execucao_com_turno_pulado_conta_no_limite_dos_10(self):
        conjunto = conjunto_mini()
        turnos = conjunto["conversas"][0]["turnos"]
        turnos[1]["esperado"]["se_anterior"]["certa"]["rotas"] = ["LEGISLACAO"]
        turnos.append({"tipo": "pergunta", "texto": "tem certeza?", "esperado": {"se_anterior": {
            "certa": {"metricas": ["M_lei_corrijo_indevido"]}}}})
        linhas = []
        for braco in ("ligado", "desligado"):
            for r in (1, 2, 3):
                linhas += [linha(1, "Sim, 60%.", braco=braco, repeticao=r),
                           linha(2, self.CONFIRMA, rota="DADOS", braco=braco, repeticao=r),
                           linha(3, self.CONFIRMA, braco=braco, repeticao=r)]
        resultado = pc.pontuar(linhas, [conjunto], {"teste": "abc"})
        ligado = resultado["partes"]["teste"]["ligado"]
        self.assertEqual(ligado["fora_por_motivo"], {"anterior_fora_parcial": 3})
        self.assertEqual(ligado["fora"], 3)
        self.assertEqual(ligado["metricas"]["M_lei_corrijo_indevido"]["n"], 3)
        self.assertEqual(resultado["decisao"], "INCONCLUSIVA")
        self.assertTrue(any("execuções do teste fora" in p for p in resultado["problemas"]))

    def test_latencia_sem_amostra_nao_se_aplica(self):
        resultado = self.rodada(self.CONFIRMA)
        self.assertIsNone(resultado["criterios"]["latencia_LEI_CORRIJO"]["cumpre"])

    def test_p95(self):
        self.assertEqual(pc.p95(list(range(1, 21))), 19)
        self.assertIsNone(pc.p95([]))


if __name__ == "__main__":
    unittest.main()
