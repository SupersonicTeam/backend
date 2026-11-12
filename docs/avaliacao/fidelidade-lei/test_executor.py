"""Executor: ambiente da JVM, braços, plano de execução e classificação pela auditoria."""

import os
import unittest
from unittest import mock

import executar_fidelidade as ex
import fidelidade_comum as comum


class AmbienteTest(unittest.TestCase):
    def test_env_do_braco_e_validado(self):
        self.assertEqual(ex.analisar_env(["APP_FLAG=true", "OUTRA=1"], "ligada"),
                         {"APP_FLAG": "true", "OUTRA": "1"})
        with self.assertRaises(ValueError):
            ex.analisar_env(["minuscula=1"], "ligada")
        with self.assertRaises(ValueError):
            ex.analisar_env(["SEM_IGUAL"], "ligada")

    def test_variaveis_do_executor_nao_sao_sobrescritas(self):
        for chave in ("AI_CHAT_PROVIDER", "AI_ANTHROPIC_MODEL", "ANTHROPIC_API_KEY", "DB_NAME",
                      "SPRING_PROFILES_ACTIVE", "SERVER_PORT", "SEED_SENHA", "AI_COTA_CREDITOS_PRODUTOR",
                      "AI_OLLAMA_BASE_URL"):
            with self.assertRaises(ValueError, msg=chave):
                ex.analisar_env([f"{chave}=x"], "ligada")

    def test_bracos_precisam_diferir(self):
        with self.assertRaises(ValueError):
            ex.validar_bracos({}, {})
        with self.assertRaises(ValueError):
            ex.validar_bracos({"F": "1"}, {"F": "1"})
        ex.validar_bracos({}, {"F": "true"})
        ex.validar_bracos({"F": "false"}, {"F": "true"})

    def test_manifesto_esconde_o_que_parece_segredo(self):
        self.assertEqual(ex.mostrar_env({"FLAG": "true", "X_API_KEY": "abc", "SENHA_Y": "z"}),
                         {"FLAG": "true", "X_API_KEY": "***", "SENHA_Y": "***"})

    def test_jvm_nao_herda_ai_nem_chave_do_shell(self):
        with mock.patch.dict(os.environ, {"AI_MAX_TOKENS": "1", "AI_CHAT_MODEL": "outro",
                                          "ANTHROPIC_API_KEY": "chave-do-ambiente",
                                          "DB_PASSWORD": "s", "SEED_SENHA": "velha"}, clear=False):
            env = ex.ambiente_da_jvm("ligada", 18111, "fidelidade_lei", "senha-nova", {"FLAG": "true"},
                                     "http://localhost:11434")
        self.assertNotIn("AI_MAX_TOKENS", env)
        self.assertNotIn("AI_CHAT_MODEL", env)
        self.assertEqual(env["AI_CHAT_PROVIDER"], "anthropic")
        self.assertEqual(env["AI_ANTHROPIC_MODEL"], "claude-haiku-5-5")
        self.assertEqual(env["AI_ANTHROPIC_THINKING"], "disabled")
        self.assertEqual(env["SPRING_PROFILES_ACTIVE"], "dev")
        self.assertEqual(env["SEED_SENHA"], "senha-nova")
        self.assertEqual(env["FLAG"], "true")
        self.assertEqual(env["DB_NAME"], "fidelidade_lei")
        self.assertEqual(env["SERVER_PORT"], "18111")
        self.assertEqual(env["ANTHROPIC_API_KEY"], "chave-do-ambiente")
        self.assertEqual(env["AI_OLLAMA_PULL_STRATEGY"], "never")

    def test_flag_so_entra_no_braco_dela(self):
        a = ex.ambiente_da_jvm("desligada", 1, "b", "s", {}, "u")
        b = ex.ambiente_da_jvm("ligada", 2, "b", "s", {"FLAG": "true"}, "u")
        self.assertNotIn("FLAG", a)
        self.assertEqual(b["FLAG"], "true")


class PlanoTest(unittest.TestCase):
    def test_cada_pergunta_roda_nos_dois_bracos_em_cada_repeticao(self):
        ids = [f"FL{i:02d}" for i in range(1, 37)]
        plano = ex.plano_de_execucao(ids, 3, 20261112)
        self.assertEqual(len(plano), 108)
        for rep in (1, 2, 3):
            do_rep = [p for p in plano if p[0] == rep]
            self.assertEqual(sorted(p[1] for p in do_rep), ids)
            for _, _, bracos in do_rep:
                self.assertEqual(sorted(bracos), sorted(comum.BRACOS))

    def test_ordem_dos_bracos_alterna(self):
        plano = ex.plano_de_execucao([f"FL{i:02d}" for i in range(1, 37)], 3, 7)
        primeiros = [b[0] for _, _, b in plano]
        self.assertGreater(primeiros.count("desligada"), 30)
        self.assertGreater(primeiros.count("ligada"), 30)

    def test_plano_e_deterministico_e_a_ordem_muda_por_repeticao(self):
        ids = [f"FL{i:02d}" for i in range(1, 13)]
        self.assertEqual(ex.plano_de_execucao(ids, 3, 5), ex.plano_de_execucao(ids, 3, 5))
        ordem = lambda rep: [p[1] for p in ex.plano_de_execucao(ids, 3, 5) if p[0] == rep]  # noqa
        self.assertNotEqual(ordem(1), ordem(2))


class ClassificacaoTest(unittest.TestCase):
    def reg(self, desfecho="RESPONDIDA", rota="LEGISLACAO", status=200, fallback=False, auditoria=True):
        return {"status": status, "rota": rota, "modoFallback": fallback,
                "auditoria": {"desfecho": desfecho, "tokens_entrada": 10, "tokens_saida": 5}
                if auditoria else None}

    def test_aceita(self):
        self.assertEqual(comum.classificar_execucao(self.reg())[0], "aceita")

    def test_falhas_tecnicas(self):
        for r in (self.reg(status=503), self.reg(status=0), self.reg(fallback=True),
                  self.reg(auditoria=False), self.reg(desfecho="MODELO_INDISPONIVEL"),
                  self.reg(desfecho="TEMPO_ESGOTADO"), self.reg(desfecho="ERRO"),
                  self.reg(desfecho="CANCELADA"), self.reg(desfecho="MODO_FALLBACK")):
            self.assertEqual(comum.classificar_execucao(r)[0], "falha", r)

    def test_recusa(self):
        self.assertEqual(comum.classificar_execucao(self.reg("SEM_FUNDAMENTO_LEGAL"))[0], "recusa")
        self.assertEqual(comum.classificar_execucao(self.reg("FORA_DE_ESCOPO", "FORA_DE_ESCOPO"))[0],
                         "recusa")

    def test_descartada_inclui_desfecho_novo_da_correcao(self):
        for d in ("CITACAO_INVALIDA", "NUMERO_SEM_FONTE", "RESPOSTA_CORTADA",
                  "REGIME_ESPECIFICO_SEM_SETOR", "FIDELIDADE_REPROVADA"):
            self.assertEqual(comum.classificar_execucao(self.reg(d))[0], "descartada", d)
        self.assertEqual(comum.classificar_execucao(self.reg("RESPONDIDA", "DADOS"))[0], "descartada")

    def test_tokens_somam_todos_os_campos_de_tokens(self):
        aud = {"tokens_entrada": 100, "tokens_saida": 20, "tokens_entrada_verificacao": 50,
               "tokens_saida_verificacao": 5, "tokens_estimados": True, "latencia_ms": 3000}
        self.assertEqual(comum.tokens_da_auditoria(aud), 175)
        self.assertEqual(comum.tokens_da_auditoria(None), 0)


class EstatisticaTest(unittest.TestCase):
    def test_percentil_vizinho_mais_proximo(self):
        self.assertEqual(comum.percentil([10, 20, 30, 40], 50), 20)
        self.assertEqual(comum.percentil(list(range(1, 101)), 95), 95)
        self.assertIsNone(comum.percentil([], 50))

    def test_kappa(self):
        po, k = comum.kappa_de_cohen([("A", "A"), ("B", "B"), ("A", "A"), ("B", "B")])
        self.assertEqual((po, k), (1.0, 1.0))
        po, k = comum.kappa_de_cohen([("A", "B"), ("B", "A")])
        self.assertEqual(po, 0.0)
        self.assertLess(k, 0)
        self.assertEqual(comum.kappa_de_cohen([]), (None, None))


if __name__ == "__main__":
    unittest.main()
