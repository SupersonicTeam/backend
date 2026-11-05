"""Partes puras do executor da matriz (issue #348): nada aqui chama Ollama, banco nem git."""

import argparse
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent))

from ceres_benchmark import executor as ex  # noqa: E402


def config(**extra):
    args = argparse.Namespace(matriz=str(AQUI.parent / "matriz.json"), arquiteturas=None, modelos=None,
                              ref_ceres2=None, saida="saida", trabalho="trabalho", repeticoes=None,
                              hardware="maquina X", retomar=None)
    for k, v in extra.items():
        setattr(args, k, v)
    return ex.montar_config(args, AQUI.parents[3])


class ExecutorTest(unittest.TestCase):
    def test_nome_da_celula(self):
        self.assertEqual(ex.nome_celula("ceres-1", "qwen3:0.6b"), "ceres-1--qwen3-0.6b")
        self.assertEqual(ex.nome_celula("ceres-2", "qwen3:4b-instruct"), "ceres-2--qwen3-4b-instruct")

    def test_matriz_padrao(self):
        cfg = config()
        self.assertEqual(cfg.modelos, ["qwen3:0.6b", "qwen3:1.7b", "qwen3:4b-instruct"])
        self.assertEqual([a["ref"] for a in cfg.arquiteturas], ["2463c27", "origin/main"])
        self.assertEqual(cfg.repeticoes, 3)
        self.assertEqual(cfg.arquiteturas[0]["remocoesExtras"], {"T10": ["camposValor"]})

    def test_subconjunto_e_ref_da_ceres2(self):
        cfg = config(modelos="qwen3:1.7b", arquiteturas="ceres-2", ref_ceres2="abc123", repeticoes=1)
        self.assertEqual(cfg.modelos, ["qwen3:1.7b"])
        self.assertEqual([a["ref"] for a in cfg.arquiteturas], ["abc123"])
        self.assertEqual(cfg.repeticoes, 1)
        with self.assertRaises(SystemExit):
            config(arquiteturas="ceres-9")

    def test_ambiente_e_argumentos_sem_segredo(self):
        e = ex.Executor(config())
        env = e.ambiente_java("agro_bench_ceres_1", "qwen3:0.6b")
        self.assertEqual(env["AI_CHAT_MODEL"], "qwen3:0.6b")
        self.assertEqual(env["AI_OLLAMA_THINK"], "false")
        self.assertEqual(env["SPRING_PROFILES_ACTIVE"], "dev,avaliacao")
        self.assertEqual(env["DB_NAME"], "agro_bench_ceres_1")
        args = e.argumentos_avaliacao(Path("/g.json"), Path("/s"))
        self.assertIn("--avaliacao.repeticoes=3", args)
        self.assertIn("--avaliacao.gabarito=/g.json", args)
        self.assertIn("--avaliacao.hardware=maquina X", args)
        self.assertFalse(any("password" in a.lower() or "senha" in a.lower() for a in args))

    def test_memoria_do_docker_stats(self):
        self.assertEqual(ex.bytes_de_docker_stats("1.5GiB / 15.6GiB"), int(1.5 * 1024**3))
        self.assertEqual(ex.bytes_de_docker_stats("512MiB / 2GiB"), 512 * 1024**2)
        self.assertIsNone(ex.bytes_de_docker_stats("--"))

    def test_prova_de_pensamento(self):
        limpo = ex.avaliar_prova({"message": {"content": "pronto"}, "eval_count": 2})
        self.assertFalse(limpo["raciocinou"])
        self.assertIn("desligado", ex.resumo_pensamento(limpo))
        campo = ex.avaliar_prova({"message": {"content": "pronto", "thinking": "Vou pensar..."}})
        self.assertTrue(campo["raciocinou"])
        no_texto = ex.avaliar_prova({"message": {"content": "<think>hmm</think>pronto"}})
        self.assertTrue(no_texto["raciocinou"] and no_texto["thinkNoTexto"])
        self.assertIn("ATIVO apesar de think=false", ex.resumo_pensamento(no_texto))
        self.assertIn("prova falhou", ex.resumo_pensamento({"erro": "URLError"}))

    def test_renomeia_a_saida_do_comando_com_o_nome_da_celula(self):
        with tempfile.TemporaryDirectory() as d:
            temporario, destino = Path(d) / "tmp", Path(d) / "celula"
            temporario.mkdir()
            destino.mkdir()
            for nome in ("2026-11-05-1012-ollama-qwen3-0.6b.md", "2026-11-05-1012-ollama-qwen3-0.6b.json",
                         "2026-11-05-1012-ollama-qwen3-0.6b-clareza-cego.csv",
                         "2026-11-05-1012-ollama-qwen3-0.6b-clareza-chave.json"):
                (temporario / nome).write_text("x")
            gravados = ex.Executor._renomear_saida(temporario, destino, "ceres-1--qwen3-0.6b")
            self.assertEqual(sorted(gravados), [
                "ceres-1--qwen3-0.6b-clareza-cego.csv", "ceres-1--qwen3-0.6b-clareza-chave.json",
                "ceres-1--qwen3-0.6b.json", "ceres-1--qwen3-0.6b.md"])
            self.assertFalse(temporario.exists())


if __name__ == "__main__":
    unittest.main()
