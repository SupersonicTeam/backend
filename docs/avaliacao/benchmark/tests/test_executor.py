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
                              hardware="maquina X", retomar=None, teste=False, embedding_cpu=False)
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


class RetomadaTest(unittest.TestCase):
    def executor(self, **extra):
        e = ex.Executor(config(**extra))
        e.resolver_sha = lambda ref: {"2463c27": "a" * 40, "origin/main": "b" * 40, "x": "c" * 40}[ref]
        return e

    def test_retomada_reusa_os_shas_da_rodada(self):
        e = self.executor()
        e.fixar_shas({"arquiteturas": [{"nome": "ceres-1", "sha": "a" * 40},
                                       {"nome": "ceres-2", "sha": "d" * 40}]})
        self.assertEqual([a["sha"] for a in e.cfg.todas_arquiteturas], ["a" * 40, "d" * 40])

    def test_retomada_com_outro_commit_explicito_e_recusada(self):
        e = self.executor(ref_ceres2="x")
        with self.assertRaises(RuntimeError):
            e.fixar_shas({"arquiteturas": [{"nome": "ceres-2", "sha": "d" * 40}]})

    def test_gabarito_canonico_e_da_matriz_mesmo_com_uma_arquitetura(self):
        e = self.executor(arquiteturas="ceres-1")
        e.fixar_shas(None)
        self.assertEqual([a["nome"] for a in e.cfg.arquiteturas], ["ceres-1"])
        self.assertEqual(e.cfg.todas_arquiteturas[-1]["nome"], "ceres-2")
        self.assertEqual(e.cfg.todas_arquiteturas[-1]["sha"], "b" * 40)

    def test_rodada_retomada_com_um_modelo_mantem_as_outras_celulas(self):
        e = self.executor(modelos="qwen3:1.7b")
        e.fixar_shas(None)
        for a in e.cfg.todas_arquiteturas:
            a["adaptacao"] = f"adaptacao-{a['nome']}.json"
        anterior = {"iniciadaEm": "2026-11-20T22:00:00-03:00",
                    "modelos": ["qwen3:0.6b", "qwen3:1.7b", "qwen3:4b-instruct"],
                    "celulas": [{"nome": ex.nome_celula(a, m), "arquitetura": a,
                                 "rotuloArquitetura": a, "modelo": m}
                                for a in ("ceres-1", "ceres-2")
                                for m in ("qwen3:0.6b", "qwen3:4b-instruct")]}
        rodada = e.manifesto_rodada(anterior, {"ollama": "0.12", "trechosLegislacao": 8304})
        self.assertEqual(rodada["iniciadaEm"], "2026-11-20T22:00:00-03:00")
        self.assertEqual([c["nome"] for c in rodada["celulas"]], [
            "ceres-1--qwen3-0.6b", "ceres-1--qwen3-1.7b", "ceres-1--qwen3-4b-instruct",
            "ceres-2--qwen3-0.6b", "ceres-2--qwen3-1.7b", "ceres-2--qwen3-4b-instruct"])
        self.assertEqual(len(rodada["arquiteturas"]), 2)


class PensamentoVazioTest(unittest.TestCase):
    def test_bloco_think_vazio_nao_conta_como_raciocinio(self):
        prova = ex.avaliar_prova({"message": {"content": "<think>\n\n</think>\n\npronto"}})
        self.assertFalse(prova["raciocinou"])
        self.assertEqual(prova["caracteresPensamento"], 0)


class ConfiguracaoTest(unittest.TestCase):
    REPO = AQUI.parents[3]

    def test_opcoes_do_chat_lidas_do_application_yml_real(self):
        yml = (self.REPO / "src/main/resources/application.yml").read_text(encoding="utf-8")
        opcoes = ex.opcoes_chat_ollama(yml)
        self.assertEqual(opcoes["temperature"], "0.2")
        self.assertEqual(opcoes["num-ctx"], "${AI_OLLAMA_NUM_CTX:8192}")
        self.assertEqual(opcoes["think-option"], "${AI_OLLAMA_THINK:false}")
        self.assertIn("num-predict", opcoes)
        # O bloco do openai tambem tem chat/options: nao pode entrar.
        self.assertNotIn("max-tokens", opcoes)
        self.assertEqual(ex.resolver_placeholder(opcoes["num-ctx"], {}), "8192")
        self.assertEqual(ex.resolver_placeholder(opcoes["num-ctx"], {"AI_OLLAMA_NUM_CTX": "4096"}), "4096")
        self.assertEqual(ex.resolver_placeholder("0.2", {}), "0.2")

    def test_onde_rodou(self):
        self.assertEqual(ex.onde_rodou({"size": 100, "size_vram": 100}), "GPU")
        self.assertEqual(ex.onde_rodou({"size": 100, "size_vram": 0}), "CPU")
        self.assertEqual(ex.onde_rodou({"size": 100, "size_vram": 40}), "dividido (40% na GPU)")
        self.assertEqual(ex.onde_rodou({}), "?")

    def test_avisos_de_recarga_e_divisao(self):
        avisos = ex.avisos_da_amostra({"descargas": {"qwen3:4b-instruct": 2, "bge-m3": 0},
                                       "ondeRodou": {"qwen3:4b-instruct": ["GPU", "dividido (60% na GPU)"],
                                                     "bge-m3": ["CPU"]},
                                       "falhasAmostra": 1})
        self.assertEqual(len(avisos), 3)
        self.assertIn("qwen3:4b-instruct saiu da memória 2 vez(es)", avisos[0])
        self.assertEqual(ex.avisos_da_amostra({"descargas": {"m": 0}, "ondeRodou": {"m": ["GPU"]}}), [])

    def test_configuracao_do_ollama_que_muda_e_recusada(self):
        e = ex.Executor(config())
        atual = {"versao": "0.12.6", "modelos": {"qwen3:0.6b": {"quantizacao": "Q4_K_M", "digest": "a"}}}
        e.configuracao_ollama = lambda: atual
        self.assertEqual(e.conferir_configuracao(None), atual)
        self.assertEqual(e.conferir_configuracao(atual), atual)
        with self.assertRaises(RuntimeError) as erro:
            e.conferir_configuracao({"versao": "0.12.5", "modelos": atual["modelos"]})
        self.assertIn("0.12.5 -> 0.12.6", str(erro.exception))
        with self.assertRaises(RuntimeError):
            e.conferir_configuracao({"versao": "0.12.6", "modelos": {
                "qwen3:0.6b": {"quantizacao": "Q8_0", "digest": "b"}}})

    def test_teste_e_embedding_na_cpu(self):
        cfg = config(teste=True, embedding_cpu=True)
        self.assertTrue(cfg.teste)
        self.assertEqual(cfg.repeticoes, 1)
        self.assertEqual(cfg.embedding, "bge-m3-cpu")
        args = ex.Executor(cfg).argumentos_avaliacao(Path("/g.json"), Path("/s"))
        self.assertIn("--spring.ai.ollama.embedding.options.model=bge-m3-cpu", args)
        self.assertTrue(config(repeticoes=2).teste)
        self.assertFalse(config().teste)
        self.assertEqual(config().embedding, "bge-m3")


class ConjuntoRealTest(unittest.TestCase):
    """O conjunto congelado (#352) contra as funcoes reais: capacidade e hash."""
    REPO = AQUI.parents[3]

    def test_capacidade_do_conjunto_confere_com_as_funcoes_de_cada_commit(self):
        import json
        from ceres_benchmark import gabarito as gab
        matriz = json.loads((AQUI.parent / "matriz.json").read_text(encoding="utf-8"))
        conjunto = json.loads((self.REPO / matriz["perguntas"]).read_text(encoding="utf-8"))
        atuais = gab.assinaturas_ferramentas((self.REPO / ex.FERRAMENTAS_REPO).read_text(encoding="utf-8"))
        # As funcoes do 2463c27 tem os mesmos parametros na main; o executor confere a lista.
        antigas = {f: atuais[f] for f in matriz["arquiteturas"][0]["funcoesConhecidas"]}
        primeira = matriz["arquiteturas"][0]
        _, adapt1 = gab.adaptar(conjunto, antigas, primeira.get("remocoesExtras"),
                                primeira.get("indiceDeLista", True))
        _, adapt2 = gab.adaptar(conjunto, atuais)
        self.assertEqual(adapt2, {}, "a Ceres 2.0 tem de saber medir o conjunto inteiro")
        self.assertEqual(gab.conferir_capacidade(conjunto, {"ceres-1": adapt1, "ceres-2": adapt2}), [])
        comum = gab.recorte_capacidade_comum(conjunto, adapt1)
        self.assertGreaterEqual(len(comum), 70)

    def test_capacidade_errada_e_apontada(self):
        from ceres_benchmark import gabarito as gab
        canonico = {"perguntas": [{"id": "A", "capacidade": "comum"}, {"id": "B", "capacidade": "so_ceres_2"}]}
        adapt = {"ceres-1": {"A": {"removido": {"funcoesEsperadas": ["x"]}, "penaliza": ["funcao"]}}}
        problemas = gab.conferir_capacidade(canonico, adapt)
        self.assertEqual(len(problemas), 2)

    def test_hash_ao_lado_confere(self):
        import json
        matriz = json.loads((AQUI.parent / "matriz.json").read_text(encoding="utf-8"))
        arquivo = self.REPO / matriz["perguntas"]
        lado = arquivo.with_name(arquivo.name + ".sha256").read_text(encoding="utf-8").split()[0]
        self.assertEqual(ex.sha256_arquivo(arquivo), lado)
