"""Leitura cega e decisão: veredito pelos rótulos, consenso, lotes sem o braço e os três desfechos."""

import json
import tempfile
import unittest
from pathlib import Path

import decidir_fidelidade as dec
import fidelidade_comum as comum
import leitura_fidelidade as lei

COM_BASE = {f"Q{i:02d}": True for i in range(1, 31)}
COM_BASE.update({f"A{i}": False for i in range(1, 7)})


def e(pid, braco, rep, estado="aceita", veredito="FIEL", subtipo=None, lat=4000, tokens=3000):
    return {"id": pid, "braco": braco, "rep": rep, "estado": estado, "motivo": "",
            "veredito": veredito if estado == "aceita" else None,
            "subtipo": subtipo if estado == "aceita" else None, "latenciaMs": lat, "tokens": tokens}


def rodada(defeitos_off, defeitos_on, tipo_off="INFIEL", tipo_on="INFIEL"):
    """30 perguntas com base x 3 repetições; os defeitos são postos nas primeiras execuções."""
    execs = []
    for braco, defeitos, tipo in (("desligada", defeitos_off, tipo_off), ("ligada", defeitos_on, tipo_on)):
        n = 0
        for pid, com_base in COM_BASE.items():
            if not com_base:
                continue
            for rep in (1, 2, 3):
                if n < defeitos:
                    v, s = (("INFIEL", "ALEM_DO_TRECHO") if tipo == "INFIEL"
                            else ("COM_RESSALVA", "CITACAO"))
                    execs.append(e(pid, braco, rep, veredito=v, subtipo=s))
                else:
                    execs.append(e(pid, braco, rep))
                n += 1
        for pid, com_base in COM_BASE.items():
            if com_base:
                continue
            for rep in (1, 2, 3):
                execs.append(e(pid, braco, rep, estado="recusa"))
    return execs


LEITURA_BOA = {"kappa": 0.8, "desacordo": 0.1, "itensLidos": 100}


class VeredictoTest(unittest.TestCase):
    def frases(self, *rotulos):
        return {"tipoResposta": "AFIRMA", "frases": [{"rotulo": r} for r in rotulos]}

    def test_veredito_pela_pior_frase(self):
        v = comum.veredito_de_leitura
        self.assertEqual(v(self.frases("SUPORTADA", "NAO_JURIDICA")), ("FIEL", None))
        self.assertEqual(v(self.frases("SUPORTADA", "CITACAO_ERRADA")), ("COM_RESSALVA", "CITACAO"))
        self.assertEqual(v(self.frases("OUTRA_RESSALVA")), ("COM_RESSALVA", "OUTRA"))
        self.assertEqual(v(self.frases("CITACAO_ERRADA", "ALEM_DO_TRECHO")),
                         ("INFIEL", "ALEM_DO_TRECHO"))
        self.assertEqual(v(self.frases("ALEM_DO_TRECHO", "CONTRARIO")), ("INFIEL", "CONTRARIO"))
        self.assertEqual(v({"tipoResposta": "RECUSA", "frases": []}), ("RECUSA", None))

    def test_consenso(self):
        c = comum.consenso
        self.assertEqual(c([("FIEL", None), ("FIEL", None)])[:2], ("FIEL", None))
        self.assertEqual(c([("FIEL", None), ("INFIEL", "ALEM_DO_TRECHO"), ("INFIEL", "CONTRARIO")]),
                         ("INFIEL", "CONTRARIO", False))
        # os três divergem: o do meio
        self.assertEqual(
            c([("FIEL", None), ("INFIEL", "ALEM_DO_TRECHO"), ("COM_RESSALVA", "CITACAO")])[:2],
            ("COM_RESSALVA", "CITACAO"))
        self.assertEqual(c([("RECUSA", None), ("FIEL", None), ("INFIEL", "CONTRARIO")])[:2],
                         ("FIEL", None))
        self.assertTrue(c([("FIEL", None), ("FIEL", None)])[2])


class LotesTest(unittest.TestCase):
    def brutas(self):
        conjunto = comum.carregar_conjunto()
        ids = [p["id"] for p in conjunto["perguntas"][:5]]
        reg = []
        for pid in ids:
            for braco in comum.BRACOS:
                for rep in (1, 2):
                    reg.append({"id": pid, "braco": braco, "repeticao": rep, "status": 200,
                                "rota": "LEGISLACAO", "modoFallback": False,
                                "resposta": f"texto de {pid}", "citacoes": [],
                                "auditoria": {"desfecho": "RESPONDIDA"}})
        # a segunda tentativa de uma execução falhou: vale a última, e ela não é lida
        reg.append({"id": ids[0], "braco": "ligada", "repeticao": 1, "tentativa": 2, "status": 503,
                    "rota": None, "modoFallback": False, "auditoria": None})
        return conjunto, reg

    def test_lotes_so_com_aceitas_e_sem_o_braco(self):
        conjunto, brutas = self.brutas()
        lotes, chave = lei.montar_lotes(brutas, conjunto, 1, tamanho=4)
        itens = [i for lote in lotes for i in lote]
        self.assertEqual(len(itens), len(chave))
        self.assertEqual(len(chave), 19)  # 20 aceitas menos a que a segunda tentativa falhou
        self.assertTrue(all(len(lote) <= 4 for lote in lotes))
        for item in itens:
            texto = json.dumps(item, ensure_ascii=False).lower()
            self.assertEqual(set(item), {"id", "pergunta", "resposta", "citacoes", "gabarito"})
            self.assertNotIn("braco", texto)
            self.assertNotIn("desligada", texto)
            self.assertNotIn("repeticao", texto)
            self.assertRegex(item["id"], r"^R\d{3}$")

    def test_embaralhamento_e_deterministico_e_mistura_os_bracos(self):
        conjunto, brutas = self.brutas()
        _, c1 = lei.montar_lotes(brutas, conjunto, 1)
        l2, c2 = lei.montar_lotes(brutas, conjunto, 1)
        self.assertEqual(c1, c2)
        primeiros = [c2[i["id"]]["braco"] for i in l2[0]]
        self.assertEqual(set(primeiros), set(comum.BRACOS))

    def test_prompt_traz_o_papel_o_lote_e_o_destino_sem_o_braco(self):
        conjunto, brutas = self.brutas()
        lotes, _ = lei.montar_lotes(brutas, conjunto, 1)
        with tempfile.TemporaryDirectory() as pasta:
            texto = lei.prompt_do_lote("J1", "lote-01", lotes[0], Path(pasta) / "lote-01.json")
        self.assertIn("Papel: juiz de fidelidade", texto)
        self.assertIn("lote-01.json", texto)
        self.assertNotIn('"braco"', texto)

    def test_desacordo_so_quando_o_veredito_difere(self):
        def leitura(rotulo):
            return {"tipoResposta": "AFIRMA", "frases": [{"rotulo": rotulo}]}
        a = {"R001": leitura("SUPORTADA"), "R002": leitura("CONTRARIO"),
             "R003": leitura("CITACAO_ERRADA")}
        b = {"R001": leitura("NAO_JURIDICA"), "R002": leitura("ALEM_DO_TRECHO"),  # mesmo INFIEL
             "R003": leitura("SUPORTADA")}
        self.assertEqual(lei.itens_em_desacordo(a, b), ["R003"])

    def test_validacao_da_leitura(self):
        self.assertEqual(lei.problemas_da_leitura({"id": "R1", "tipoResposta": "AFIRMA",
                                                   "frases": [{"rotulo": "SUPORTADA"}]}), [])
        self.assertTrue(lei.problemas_da_leitura({"id": "R1", "tipoResposta": "AFIRMA", "frases": []}))
        self.assertTrue(lei.problemas_da_leitura({"id": "R1", "tipoResposta": "TALVEZ"}))
        self.assertTrue(lei.problemas_da_leitura({"id": "R1", "tipoResposta": "AFIRMA",
                                                  "frases": [{"rotulo": "BOM"}]}))
        self.assertEqual(lei.problemas_da_leitura({"id": "R1", "tipoResposta": "RECUSA"}), [])


class DecisaoTest(unittest.TestCase):
    def avaliar(self, execs, leitura=None, interrompida=None):
        return dec.avaliar(execs, COM_BASE, leitura or LEITURA_BOA, interrompida)

    def test_entra_quando_cai_o_defeito_sem_piorar_o_resto(self):
        r = self.avaliar(rodada(24, 6))
        self.assertEqual(r["desfecho"], "ENTRA", r["motivo"])
        self.assertEqual(r["m1"]["queda"], 18)

    def test_nao_entra_se_as_aceitas_caem_alem_da_margem(self):
        execs = rodada(24, 6)
        n = 0  # a correção descarta 20 respostas fiéis do ligado
        for x in execs:
            if x["braco"] == "ligada" and COM_BASE[x["id"]] and x["veredito"] == "FIEL" and n < 20:
                x["estado"], x["veredito"] = "descartada", None
                n += 1
        r = self.avaliar(execs)
        self.assertEqual(r["desfecho"], "NAO_ENTRA")
        self.assertIn("M2 aceitas", r["motivo"])

    def test_nao_entra_se_a_recusa_falsa_sobe(self):
        execs = rodada(24, 6)
        n = 0
        for x in execs:
            if x["braco"] == "ligada" and COM_BASE[x["id"]] and x["estado"] == "aceita" \
                    and x["veredito"] == "FIEL" and n < 6:
                x["estado"], x["veredito"] = "recusa", None
                n += 1
        r = self.avaliar(execs)
        self.assertEqual(r["desfecho"], "NAO_ENTRA")
        self.assertIn("M3 recusa falsa", r["motivo"])

    def test_nao_entra_se_a_lei_ausente_passa_a_afirmar(self):
        execs = rodada(24, 6)
        for x in execs:
            if x["braco"] == "ligada" and x["id"] == "A1":
                x["estado"], x["veredito"], x["subtipo"] = "aceita", "INFIEL", "ALEM_DO_TRECHO"
        r = self.avaliar(execs)
        self.assertEqual(r["desfecho"], "NAO_ENTRA")
        self.assertIn("lei ausente", r["motivo"])

    def test_nao_entra_se_ficou_mais_lento_demais(self):
        execs = rodada(24, 6)
        for x in execs:
            if x["braco"] == "ligada":
                x["latenciaMs"] = 9000
        r = self.avaliar(execs)
        self.assertEqual(r["desfecho"], "NAO_ENTRA")
        self.assertIn("M5", r["motivo"])

    def test_nao_entra_se_gasta_muito_mais_tokens(self):
        execs = rodada(24, 6)
        for x in execs:
            if x["braco"] == "ligada":
                x["tokens"] = 6000
        r = self.avaliar(execs)
        self.assertEqual(r["desfecho"], "NAO_ENTRA")
        self.assertIn("M6", r["motivo"])

    def test_nao_entra_sem_melhora(self):
        r = self.avaliar(rodada(12, 12))
        self.assertEqual(r["desfecho"], "NAO_ENTRA")
        self.assertIn("sem melhora", r["motivo"])

    def test_nao_entra_se_um_tipo_piora_mesmo_com_total_menor(self):
        execs = rodada(24, 0)  # o ligado zera os infiéis...
        n = 0  # ...mas ganha 3 ressalvas de citação que não existiam
        for x in execs:
            if x["braco"] == "ligada" and x["estado"] == "aceita" and COM_BASE[x["id"]] and n < 3:
                x["veredito"], x["subtipo"] = "COM_RESSALVA", "CITACAO"
                n += 1
        r = self.avaliar(execs)
        self.assertEqual(r["desfecho"], "NAO_ENTRA")
        self.assertIn("citação", r["motivo"])

    def test_inconclusiva_sem_defeito_suficiente_na_base(self):
        r = self.avaliar(rodada(5, 0))
        self.assertEqual(r["desfecho"], "INCONCLUSIVA")
        self.assertIn("não há o que medir", r["motivo"])

    def test_inconclusiva_com_melhora_pequena(self):
        r = self.avaliar(rodada(20, 17))  # queda de 3, abaixo de 4 e de 25%
        self.assertEqual(r["desfecho"], "INCONCLUSIVA")
        self.assertIn("melhora", r["motivo"])

    def test_inconclusiva_com_falha_tecnica_demais(self):
        execs = rodada(24, 6)
        n = 0
        for x in execs:
            if x["braco"] == "ligada" and n < 12:
                x["estado"] = "falha"
                x["veredito"] = None
                n += 1
        r = self.avaliar(execs)
        self.assertEqual(r["desfecho"], "INCONCLUSIVA")
        self.assertIn("falha técnica", r["motivo"])

    def test_inconclusiva_com_leitura_nao_confiavel(self):
        r = self.avaliar(rodada(24, 6), {"kappa": 0.2, "desacordo": 0.5})
        self.assertEqual(r["desfecho"], "INCONCLUSIVA")
        self.assertIn("leitura não confiável", r["motivo"])

    def test_inconclusiva_se_a_rodada_foi_interrompida(self):
        r = self.avaliar(rodada(24, 6), interrompida="429 LIMITE_USO_ASSISTENTE")
        self.assertEqual(r["desfecho"], "INCONCLUSIVA")

    def test_pergunta_com_poucas_execucoes_validas_sai_das_visoes_por_pergunta(self):
        execs = [x for x in rodada(24, 6)
                 if not (x["id"] == "Q01" and x["braco"] == "ligada" and x["rep"] > 1)]
        r = self.avaliar(execs)
        self.assertIn("Q01", r["porPergunta"]["excluidas"])
        self.assertNotIn("Q01", r["porPergunta"]["tabela"])

    def test_defeito_por_maioria_e_em_qualquer_repeticao_sao_diferentes(self):
        execs = rodada(0, 0)
        for x in execs:
            if x["id"] == "Q01" and x["braco"] == "desligada" and x["rep"] == 1:
                x["veredito"], x["subtipo"] = "INFIEL", "ALEM_DO_TRECHO"
        r = self.avaliar(execs)
        self.assertEqual(r["porPergunta"]["comDefeitoEmQualquerRepeticao"]["desligada"], 1)
        self.assertEqual(r["porPergunta"]["defeituosasPorMaioria"]["desligada"], 0)

    def test_ausente_recusada_e_acerto(self):
        r = self.avaliar(rodada(24, 6))
        self.assertEqual(r["braco"]["ligada"]["ausenteInfiel"], 0)
        self.assertEqual(r["braco"]["ligada"]["ausenteAcertos"], 18)

    def test_resultado_nao_traz_texto_de_resposta(self):
        resultado = self.avaliar(rodada(24, 6))
        self.assertNotIn("texto de", json.dumps(resultado, ensure_ascii=False))
        md = dec.relatorio_md({**resultado,
                               "rodada": {"commit": "abc", "modelo": "m", "repeticoes": 3}})
        self.assertIn("Desfecho: ENTRA", md)


class RodadaEmDiscoTest(unittest.TestCase):
    """Ponta a ponta com arquivos: brutas -> lotes -> leituras fabricadas -> decisão."""

    def test_rodada_completa(self):
        conjunto = comum.carregar_conjunto()
        ids = ["FL01", "FL02", "FL25"]
        with tempfile.TemporaryDirectory() as pasta:
            raiz = Path(pasta)
            brutas = []
            for pid in ids:
                for braco in comum.BRACOS:
                    for rep in (1, 2, 3):
                        brutas.append({"id": pid, "braco": braco, "repeticao": rep, "tentativa": 1,
                                       "status": 200, "rota": "LEGISLACAO", "modoFallback": False,
                                       "latenciaMs": 4000, "resposta": "texto", "citacoes": [],
                                       "auditoria": {"desfecho": "RESPONDIDA", "tokens_entrada": 100,
                                                     "tokens_saida": 50}})
            comum.gravar_jsonl(raiz / "respostas-brutas.jsonl", brutas)
            (raiz / "execucao.json").write_text(json.dumps({
                "conjunto": {"sha256": comum.sha256_arquivo(comum.CONJUNTO)}, "repeticoes": 3,
                "commit": "abc", "modelo": "m", "interrompida": None}), encoding="utf-8")
            lotes, chave = lei.montar_lotes(brutas, conjunto, 1, tamanho=6)
            lei.gravar_lotes(raiz, lotes, chave, ("J1", "J2"))
            (raiz / "leitura" / "chave.json").write_text(json.dumps(chave), encoding="utf-8")
            for juiz in ("J1", "J2"):
                for n, lote in enumerate(lotes, start=1):
                    itens = [{"id": i["id"], "tipoResposta": "AFIRMA",
                              "frases": [{"rotulo": "SUPORTADA"}]} for i in lote]
                    (raiz / "leitura" / "juizes" / juiz / f"lote-{n:02d}.json").write_text(
                        json.dumps({"juiz": juiz, "lote": f"lote-{n:02d}", "itens": itens}),
                        encoding="utf-8")
            self.assertEqual(lei.validar(raiz), [])
            r = dec.decidir_rodada(raiz)
            # o manifesto planejou mais execuções do que as brutas cobrem: pergunta ausente da rodada
            manifesto = json.loads((raiz / "execucao.json").read_text(encoding="utf-8"))
            manifesto["execucoesPlanejadas"] = 99
            (raiz / "execucao.json").write_text(json.dumps(manifesto), encoding="utf-8")
            with self.assertRaises(dec.RodadaIncompleta):
                dec.decidir_rodada(raiz)
        # tudo fiel nos dois braços: não há o que melhorar
        self.assertEqual(r["desfecho"], "INCONCLUSIVA")
        self.assertEqual(r["braco"]["ligada"]["fiel"], 6)
        self.assertEqual(r["leitura"]["concordanciaJ1J2"], 1.0)

    def test_item_em_desacordo_sem_j3_deixa_a_rodada_incompleta(self):
        j1 = {"R001": {"tipoResposta": "AFIRMA", "frases": [{"rotulo": "SUPORTADA"}]}}
        j2 = {"R001": {"tipoResposta": "AFIRMA", "frases": [{"rotulo": "CONTRARIO"}]}}
        with self.assertRaises(dec.RodadaIncompleta):
            dec.vereditos_finais(j1, j2, {}, ["R001"])
        j3 = {"R001": {"tipoResposta": "AFIRMA", "frases": [{"rotulo": "CONTRARIO"}]}}
        self.assertEqual(dec.vereditos_finais(j1, j2, j3, ["R001"])["R001"][:2],
                         ("INFIEL", "CONTRARIO"))


if __name__ == "__main__":
    unittest.main()
