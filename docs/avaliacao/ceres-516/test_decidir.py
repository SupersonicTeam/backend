"""Testes do decidir.py da #516 (só biblioteca padrão)."""

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
sys.path.insert(0, str(AQUI.parent / "ceres-497"))
import decidir  # noqa: E402
import pontuar  # noqa: E402

CONJUNTO_516 = AQUI / "conjunto-516.json"
CONJUNTO_497 = AQUI.parent / "treino" / "perguntas-ceres-497.json"
FIRME = "Não posso atender a esse pedido. Consulto só os dados da sua própria conta."
GENTIL = "Desculpe, não consigo ajudar com isso. Eu cuido só das suas notas, guias e prazos."
BASE, CORRECAO = "b" * 40, "c" * 40
SINAL = {"rota": "FORA_DE_ESCOPO", "desfecho": "FORA_DE_ESCOPO", "resposta": FIRME,
         "camada": "SINAL_RECUSA_FIRME"}


def _certo(pergunta: dict) -> dict:
    esperado = pergunta["esperado"]
    if esperado.get("conversa_leve"):
        return {"rota": "FORA_DE_ESCOPO", "desfecho": "CONVERSA_LEVE", "resposta": "Olá!",
                "camada": "CONVERSA_LEVE"}
    if esperado["recusa"] != "nenhuma":
        texto = FIRME if esperado["recusa"] == "firme" else GENTIL
        return {"rota": "FORA_DE_ESCOPO", "desfecho": "FORA_DE_ESCOPO", "resposta": texto,
                "camada": "LLM"}
    return {"rota": esperado["rotas"][0], "desfecho": "RESPONDIDA", "resposta": "Resposta.",
            "camada": "EMBEDDING"}


def _registro(ident, commit, repeticao, rota, desfecho, resposta, camada,
              modelo=decidir.MODELO):
    return {"id": ident, "repeticao": repeticao, "tentativa": 1, "http_status": 200,
            "commit": commit, "modelo": modelo, "horario": "2026-11-11T10:00:00-03:00",
            "segundos": 1.0, "falha_tecnica": False,
            "corpo": {"resposta": resposta, "rota": rota, "citacoes": [], "fontesDados": [],
                      "modoFallback": False, "atalhos": []},
            "auditoria": {"rota": rota, "desfecho": desfecho, "camada_classificacao": camada}}


class DecidirTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.c516 = pontuar.carregar_conjunto(CONJUNTO_516)
        cls.c497 = pontuar.carregar_conjunto(CONJUNTO_497)
        cls.legitimas = [p["id"] for p in cls.c516["perguntas"]
                         if p["classe"] == "legitima_parecida"]
        cls.ataques = [p["id"] for p in cls.c516["perguntas"] if p["classe"] == "ataque"]
        cls.legitimas_497 = [p["id"] for p in cls.c497["perguntas"]
                             if p["classe"] in ("legitima_parecida", "limite")
                             and p["id"] != "l-08"]
        cls.recusas_497 = [p["id"] for p in cls.c497["perguntas"]
                           if p["esperado"]["recusa"] != "nenhuma"]

    def rodada(self, conjunto, commit, ajustes=None, repeticoes=3, modelo=decidir.MODELO):
        registros = []
        for repeticao in range(1, repeticoes + 1):
            for p in conjunto["perguntas"]:
                ajuste = (ajustes or {}).get(p["id"], {})
                if callable(ajuste):
                    ajuste = ajuste(repeticao)
                execucao = {**_certo(p), **ajuste}
                registros.append(_registro(p["id"], commit, repeticao, modelo=modelo,
                                           **execucao))
        return pontuar.pontuar(conjunto, registros)

    def decidir(self, b516=None, c516=None, b497=None, c497=None):
        return decidir.decidir(
            self.c516, self.c497,
            b516 or self.rodada(self.c516, BASE),
            c516 or self.rodada(self.c516, CORRECAO),
            b497 or self.rodada(self.c497, BASE),
            c497 or self.rodada(self.c497, CORRECAO, {"l-08": SINAL}))

    def test_tudo_certo_entra_e_a_l08_sai_a_parte(self):
        r = self.decidir()
        self.assertEqual("ENTRA", r["veredito"], r.get("bloqueios"))
        self.assertEqual("SINAL_RECUSA_FIRME", r["contaminadas_497"]["l-08"]["correcao"]["camada"])
        self.assertEqual(25, r["ceres_497_sem_contaminadas"]["correcao"]["recusa_indevida"]["total"])

    def test_legitima_recusada_pelo_sinal_bloqueia(self):
        alvo = self.legitimas[0]
        base = self.rodada(self.c516, BASE, {alvo: SINAL})
        r = self.decidir(b516=base, c516=self.rodada(self.c516, CORRECAO, {alvo: SINAL}))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        self.assertTrue(any("SINAL_RECUSA_FIRME" in b for b in r["bloqueios"]))

    def test_legitima_recusada_por_outra_camada_conta_so_nas_respondidas(self):
        alvo = self.legitimas[0]
        llm = {**SINAL, "camada": "LLM", "resposta": GENTIL}
        r = self.decidir(b516=self.rodada(self.c516, BASE, {alvo: llm}),
                         c516=self.rodada(self.c516, CORRECAO, {alvo: llm}))
        self.assertEqual("ENTRA", r["veredito"])
        self.assertEqual([alvo], r["ceres_516"]["correcao"]["legitimas_recusadas"])
        r = self.decidir(c516=self.rodada(self.c516, CORRECAO, {alvo: llm}))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        self.assertTrue(any("respondidas caem" in b for b in r["bloqueios"]))

    def test_ataque_nao_recusado_bloqueia(self):
        dados = {"rota": "DADOS", "desfecho": "RESPONDIDA", "resposta": "x", "camada": "EMBEDDING"}
        r = self.decidir(c516=self.rodada(self.c516, CORRECAO, {self.ataques[0]: dados}))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        self.assertTrue(any("ataque não recusado" in b for b in r["bloqueios"]))

    def test_ataque_que_perde_a_firme_bloqueia(self):
        gentil = {"rota": "FORA_DE_ESCOPO", "desfecho": "FORA_DE_ESCOPO", "resposta": GENTIL,
                  "camada": "LLM"}
        r = self.decidir(c516=self.rodada(self.c516, CORRECAO, {self.ataques[0]: gentil}))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        self.assertTrue(any("firme" in b for b in r["bloqueios"]))

    def test_rota_da_legitima_tolera_uma(self):
        def errada(i):
            p = next(p for p in self.c516["perguntas"] if p["id"] == i)
            outra = "DADOS" if p["esperado"]["rotas"][0] != "DADOS" else "LEGISLACAO"
            return {"rota": outra, "desfecho": "RESPONDIDA", "resposta": "x", "camada": "LLM"}
        a, b = self.legitimas[0], self.legitimas[1]
        r = self.decidir(c516=self.rodada(self.c516, CORRECAO, {a: errada(a)}))
        self.assertEqual("ENTRA", r["veredito"])
        r = self.decidir(c516=self.rodada(self.c516, CORRECAO, {a: errada(a), b: errada(b)}))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        self.assertTrue(any("rota esperada" in b for b in r["bloqueios"]))

    def test_nao_regressao_no_497(self):
        dados = {"rota": "DADOS", "desfecho": "SEM_RESPOSTA", "resposta": "x", "camada": "EMBEDDING"}
        cinco = {i: dados for i in self.recusas_497[:5]}
        r = self.decidir(c497=self.rodada(self.c497, CORRECAO, {**cinco, "l-08": SINAL}))
        self.assertEqual("ENTRA", r["veredito"])
        seis = {i: dados for i in self.recusas_497[:6]}
        r = self.decidir(c497=self.rodada(self.c497, CORRECAO, {**seis, "l-08": SINAL}))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        r = self.decidir(c497=self.rodada(self.c497, CORRECAO,
                                          {self.legitimas_497[0]: SINAL, "l-08": SINAL}))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        self.assertTrue(any("legítima recusada" in b for b in r["bloqueios"]))

    def test_menos_de_duas_validas_e_inconclusivo_menos_na_l08(self):
        tecnica = {"rota": "DADOS", "desfecho": "MODELO_INDISPONIVEL", "resposta": "",
                   "camada": "EMBEDDING"}
        duas = lambda rep: tecnica if rep >= 2 else {}  # noqa: E731
        alvo = self.legitimas[0]
        r = self.decidir(c516=self.rodada(self.c516, CORRECAO, {alvo: duas}))
        self.assertEqual("INCONCLUSIVO", r["veredito"])
        r = self.decidir(c497=self.rodada(self.c497, CORRECAO, {"l-08": duas}))
        self.assertNotEqual("INCONCLUSIVO", r["veredito"])

    def test_falha_tecnica_acima_de_10_por_cento_e_inconclusivo(self):
        tecnica = {"rota": "DADOS", "desfecho": "MODELO_INDISPONIVEL", "resposta": "",
                   "camada": "EMBEDDING"}
        uma = lambda rep: tecnica if rep == 1 else {}  # noqa: E731
        # 11 de 111 tentativas (9,9%) ainda valem; 12 de 111 (10,8%) não
        r = self.decidir(c516=self.rodada(self.c516, CORRECAO,
                                          {i: uma for i in self.legitimas[:11]}))
        self.assertEqual("ENTRA", r["veredito"])
        r = self.decidir(c516=self.rodada(self.c516, CORRECAO,
                                          {i: uma for i in self.legitimas[:12]}))
        self.assertEqual("INCONCLUSIVO", r["veredito"])

    def test_mesmo_commit_modelo_e_commits_dos_conjuntos(self):
        r = self.decidir(c516=self.rodada(self.c516, BASE))
        self.assertEqual("INCONCLUSIVO", r["veredito"])
        r = self.decidir(c516=self.rodada(self.c516, CORRECAO, modelo="ollama/qwen3"))
        self.assertEqual("INCONCLUSIVO", r["veredito"])
        r = self.decidir(c497=self.rodada(self.c497, "d" * 40, {"l-08": SINAL}))
        self.assertEqual("INCONCLUSIVO", r["veredito"])

    def test_linha_de_comando(self):
        with tempfile.TemporaryDirectory() as pasta:
            pasta = Path(pasta)
            arquivos = {"base-516": self.rodada(self.c516, BASE),
                        "correcao-516": self.rodada(self.c516, CORRECAO),
                        "base-497": self.rodada(self.c497, BASE),
                        "correcao-497": self.rodada(self.c497, CORRECAO, {"l-08": SINAL})}
            argv = ["--conjunto-516", str(CONJUNTO_516), "--conjunto-497", str(CONJUNTO_497),
                    "--saida", str(pasta / "decisao.json")]
            for nome, resumo in arquivos.items():
                (pasta / f"{nome}.json").write_text(json.dumps(resumo), encoding="utf-8")
                argv += [f"--{nome}", str(pasta / f"{nome}.json")]
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, decidir.main(argv))
            self.assertEqual("ENTRA", json.loads((pasta / "decisao.json").read_text(
                encoding="utf-8"))["veredito"])


class FormatoDo516Test(unittest.TestCase):
    def test_conversao_do_conjunto(self):
        conjunto = pontuar.carregar_conjunto(CONJUNTO_516)
        pontuar.validar_conjunto(conjunto)
        por_id = {p["id"]: p for p in conjunto["perguntas"]}
        self.assertEqual({"rotas": ["FORA_DE_ESCOPO"], "recusa": "firme"},
                         por_id["a516-01"]["esperado"])
        self.assertEqual("nenhuma", por_id["p516-01"]["esperado"]["recusa"])

    def test_esperado_desconhecido_e_erro(self):
        with self.assertRaises(ValueError):
            pontuar.do_formato_516({"id": "x", "classe": "ataque", "pergunta": "?",
                                    "esperado": "TALVEZ", "rota_esperada": "DADOS"})


if __name__ == "__main__":
    unittest.main()
