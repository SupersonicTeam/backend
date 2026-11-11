"""Testes do comparar.py da #497 (só biblioteca padrão)."""

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
import comparar  # noqa: E402
import pontuar  # noqa: E402

CONJUNTO = AQUI.parent / "treino" / "perguntas-ceres-497.json"
FIRME = "Não posso atender a esse pedido. Consulto só os dados da sua própria conta."
GENTIL = "Desculpe, não consigo ajudar com isso. Eu cuido só das suas notas, guias e prazos."
BASE, CORRECAO = "b" * 40, "c" * 40


def _certo(pergunta: dict) -> dict:
    """Execução que a regra de leitura conta como certa, com o tipo de recusa esperado."""
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


def _registro(ident: str, commit: str, rota: str, desfecho: str, resposta: str, camada: str,
              modelo: str = comparar.MODELO) -> dict:
    return {"id": ident, "repeticao": 1, "tentativa": 1, "http_status": 200, "commit": commit,
            "modelo": modelo, "horario": "2026-11-11T10:00:00-03:00", "segundos": 1.0,
            "falha_tecnica": False,
            "corpo": {"resposta": resposta, "rota": rota, "citacoes": [], "fontesDados": [],
                      "modoFallback": False, "atalhos": []},
            "auditoria": {"rota": rota, "desfecho": desfecho, "camada_classificacao": camada}}


class ComparacaoTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.conjunto = pontuar.carregar_conjunto(CONJUNTO)
        pontuar.validar_conjunto(cls.conjunto)
        por_classe: dict[str, list[str]] = {}
        for p in cls.conjunto["perguntas"]:
            por_classe.setdefault(p["classe"], []).append(p["id"])
        cls.ids = por_classe
        cls.firmes = [p["id"] for p in cls.conjunto["perguntas"]
                      if p["esperado"]["recusa"] == "firme"]

    def rodada(self, commit: str, ajustes: dict | None = None, omitir=(),
               modelo: str = comparar.MODELO) -> dict:
        registros = []
        for p in self.conjunto["perguntas"]:
            if p["id"] in omitir:
                continue
            execucao = {**_certo(p), **(ajustes or {}).get(p["id"], {})}
            registros.append(_registro(p["id"], commit, modelo=modelo, **execucao))
        return pontuar.pontuar(self.conjunto, registros)

    @staticmethod
    def dados() -> dict:
        return {"rota": "DADOS", "desfecho": "SEM_RESPOSTA", "resposta": "Não consegui.",
                "camada": "EMBEDDING"}

    def base_com_ataques_em_dados(self, quantos: int = 5) -> dict:
        return self.rodada(BASE, {i: self.dados() for i in self.ids["ataque"][:quantos]})

    def test_ganho_sem_regressao_entra(self):
        r = comparar.comparar(self.conjunto, self.base_com_ataques_em_dados(), self.rodada(CORRECAO))
        self.assertEqual("ENTRA", r["veredito"])
        self.assertEqual([], r["bloqueios"])
        self.assertEqual(5, r["ganho"]["recusa_correta_correcao"]
                         - r["ganho"]["recusa_correta_base"])
        self.assertEqual(sorted(self.ids["ataque"][:5]), r["melhoraram"])
        self.assertEqual([], r["pioraram"])

    def test_sem_ganho_nao_entra(self):
        r = comparar.comparar(self.conjunto, self.base_com_ataques_em_dados(2),
                              self.rodada(CORRECAO))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        self.assertFalse(r["ganho"]["atingido"])
        self.assertEqual([], r["bloqueios"])

    def test_ganho_minimo_exato_entra(self):
        r = comparar.comparar(self.conjunto, self.base_com_ataques_em_dados(3),
                              self.rodada(CORRECAO))
        self.assertEqual("ENTRA", r["veredito"])

    def test_uma_recusa_indevida_bloqueia(self):
        legitima = self.ids["legitima_parecida"][0]
        recusa = {"rota": "FORA_DE_ESCOPO", "desfecho": "FORA_DE_ESCOPO", "resposta": GENTIL,
                  "camada": "LLM"}
        r = comparar.comparar(self.conjunto, self.base_com_ataques_em_dados(),
                              self.rodada(CORRECAO, {legitima: recusa}))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        self.assertIn(legitima, r["recusa_indevida"]["correcao"])
        self.assertTrue(any("recusa indevida" in b for b in r["bloqueios"]))

    def test_queda_de_duas_numa_classe_bloqueia_e_de_uma_nao(self):
        limite = self.ids["limite"]
        erro = {"rota": "LEGISLACAO", "desfecho": "RESPONDIDA", "resposta": "Lei.",
                "camada": "EMBEDDING"}
        alvo = [i for i in limite
                if "LEGISLACAO" not in self.conjunto_por_id(i)["esperado"]["rotas"]]
        base = self.base_com_ataques_em_dados()
        uma = comparar.comparar(self.conjunto, base, self.rodada(CORRECAO, {alvo[0]: erro}))
        self.assertEqual("ENTRA", uma["veredito"])
        duas = comparar.comparar(self.conjunto, base,
                                 self.rodada(CORRECAO, {alvo[0]: erro, alvo[1]: erro}))
        self.assertEqual("NAO_ENTRA", duas["veredito"])
        self.assertTrue(any("limite" in b for b in duas["bloqueios"]))

    def conjunto_por_id(self, ident: str) -> dict:
        return next(p for p in self.conjunto["perguntas"] if p["id"] == ident)

    def test_tipo_que_piora_nas_recusadas_pelos_dois_bloqueia(self):
        gentil = {"rota": "FORA_DE_ESCOPO", "desfecho": "FORA_DE_ESCOPO", "resposta": GENTIL,
                  "camada": "LLM"}
        recusadas_nos_dois = [i for i in self.firmes if i not in self.ids["ataque"][:5]]
        trocar = {i: gentil for i in recusadas_nos_dois[:2]}
        r = comparar.comparar(self.conjunto, self.base_com_ataques_em_dados(),
                              self.rodada(CORRECAO, trocar))
        self.assertEqual("NAO_ENTRA", r["veredito"])
        self.assertTrue(any("tipo de recusa" in b for b in r["bloqueios"]))

    def test_recusas_novas_com_tipo_trocado_dao_ressalva(self):
        gentil = {"rota": "FORA_DE_ESCOPO", "desfecho": "FORA_DE_ESCOPO", "resposta": GENTIL,
                  "camada": "EMBEDDING"}
        novas = self.ids["ataque"][:5]
        r = comparar.comparar(self.conjunto, self.base_com_ataques_em_dados(),
                              self.rodada(CORRECAO, {i: gentil for i in novas}))
        self.assertEqual("ENTRA_COM_RESSALVA", r["veredito"])
        self.assertEqual(sorted(novas), r["ressalva"]["tipo_trocado_correcao"])
        self.assertEqual([], r["bloqueios"])

    def test_recusa_indevida_so_no_base_nao_bloqueia(self):
        recusa = {"rota": "FORA_DE_ESCOPO", "desfecho": "FORA_DE_ESCOPO", "resposta": GENTIL,
                  "camada": "LLM"}
        ajustes = {i: self.dados() for i in self.ids["ataque"][:5]}
        ajustes[self.ids["limite"][0]] = recusa
        r = comparar.comparar(self.conjunto, self.rodada(BASE, ajustes), self.rodada(CORRECAO))
        self.assertEqual("ENTRA", r["veredito"])
        self.assertEqual([self.ids["limite"][0]], r["recusa_indevida"]["base"])

    def test_queda_de_uma_numa_classe_de_recusa_nao_bloqueia(self):
        fora = self.ids["fora_escopo"][0]
        r = comparar.comparar(self.conjunto, self.base_com_ataques_em_dados(),
                              self.rodada(CORRECAO, {fora: self.dados()}))
        self.assertEqual("ENTRA", r["veredito"])
        self.assertEqual(-1, r["por_classe"]["fora_escopo"]["diferenca"])
        self.assertEqual([fora], r["pioraram"])

    def test_fracao_sem_recusas_no_base(self):
        self.assertFalse(comparar._fracao_menor(0, 0, 3, 5))
        self.assertTrue(comparar._fracao_menor(2, 3, 0, 0))
        self.assertFalse(comparar._fracao_menor(3, 3, 0, 0))
        self.assertTrue(comparar._fracao_menor(1, 2, 2, 3))
        self.assertFalse(comparar._fracao_menor(2, 3, 2, 3))

    def test_mesmo_commit_e_inconclusivo(self):
        r = comparar.comparar(self.conjunto, self.rodada(BASE), self.rodada(BASE))
        self.assertEqual("INCONCLUSIVO", r["veredito"])

    def test_pergunta_sem_medicao_e_inconclusivo(self):
        tecnica = {"rota": "DADOS", "desfecho": "MODELO_INDISPONIVEL", "resposta": "",
                   "camada": "EMBEDDING"}
        alvo = self.ids["limite"][0]
        r = comparar.comparar(self.conjunto, self.base_com_ataques_em_dados(),
                              self.rodada(CORRECAO, {alvo: tecnica}))
        self.assertEqual("INCONCLUSIVO", r["veredito"])
        self.assertTrue(any(alvo in p for p in r["problemas_de_medicao"]))

    def test_modelo_diferente_e_inconclusivo(self):
        r = comparar.comparar(self.conjunto, self.base_com_ataques_em_dados(),
                              self.rodada(CORRECAO, modelo="ollama/qwen3:4b-instruct"))
        self.assertEqual("INCONCLUSIVO", r["veredito"])

    def test_conjunto_de_outro_sha_e_inconclusivo(self):
        base = self.base_com_ataques_em_dados()
        base["sha256_conjunto"] = "0" * 64
        r = comparar.comparar(self.conjunto, base, self.rodada(CORRECAO))
        self.assertEqual("INCONCLUSIVO", r["veredito"])

    def test_linha_de_comando_grava_a_saida(self):
        with tempfile.TemporaryDirectory() as pasta:
            pasta = Path(pasta)
            (pasta / "base.json").write_text(
                json.dumps(self.base_com_ataques_em_dados()), encoding="utf-8")
            (pasta / "cor.json").write_text(json.dumps(self.rodada(CORRECAO)), encoding="utf-8")
            saida = pasta / "comparacao.json"
            with contextlib.redirect_stdout(io.StringIO()):
                codigo = comparar.main(["--perguntas", str(CONJUNTO), "--base",
                                        str(pasta / "base.json"), "--correcao",
                                        str(pasta / "cor.json"), "--saida", str(saida)])
            self.assertEqual(0, codigo)
            self.assertEqual("ENTRA", json.loads(saida.read_text(encoding="utf-8"))["veredito"])


if __name__ == "__main__":
    unittest.main()
