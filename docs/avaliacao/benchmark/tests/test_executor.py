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
                              hardware="maquina X", retomar=None, teste=False, embedding_cpu=False,
                              ensaio=False)
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


class RevisaoSegundoPrTest(unittest.TestCase):
    REPO = AQUI.parents[3]

    def test_sessao_paralela_so_pelo_merge_da_issue(self):
        assuntos = ["fe83a01 feat(avaliacao): conjunto (#352)",
                    "aaa1111 feat(ceres): prompts por rota (#270) (#360)",
                    "bbb2222 fix: algo (#2701) (#361)",
                    "ccc3333 docs: cita a #270 no meio"]
        self.assertEqual(ex.commits_da_issue(assuntos, "#270"), ["aaa1111 feat(ceres): prompts por rota (#270) (#360)"])
        self.assertEqual(ex.commits_da_issue(assuntos[:1] + assuntos[2:], "#270"), [])

    def test_coluna_oficial_exige_a_sessao_7_e_a_8a(self):
        from unittest import mock
        e = ex.Executor(config())
        e.capturar = lambda comando, cwd=None: ""
        e.cfg.todas_arquiteturas[-1]["sha"] = "b" * 40
        sete = e.cfg.matriz["sessaoParalela"]["commit"]
        extras = {x["commit"] for x in e.cfg.matriz["sessaoParalela"]["tambemExige"]}
        oito = e.cfg.matriz["sessaoParalela"]["tambemExige"][0]["commit"]
        for presentes, confirmado, inteira in (({sete} | extras, True, True), ({sete}, False, True),
                                               (set(), False, False)):
            def run(comando, cwd=None, _p=presentes):
                return mock.Mock(returncode=0 if comando[3] in _p else 1)
            with mock.patch.object(ex.subprocess, "run", run):
                sessao = e.sessao_paralela()
            self.assertEqual((sessao["confirmado"], sessao["inteira"]), (confirmado, inteira))
            self.assertEqual(bool(sessao["faltando"]), not extras <= presentes)

    def test_retomada_recusa_rodada_antiga_conjunto_ou_embedding_diferente(self):
        e = ex.Executor(config())
        atual = ex.sha256_arquivo(self.REPO / e.cfg.matriz["perguntas"])
        e.conferir_retomada({"perguntas": {"sha256": atual}, "embedding": "bge-m3"})
        with self.assertRaises(RuntimeError):
            e.conferir_retomada({"embedding": "bge-m3"})  # rodada do #349, sem o conjunto
        with self.assertRaises(RuntimeError):
            e.conferir_retomada({"perguntas": {"sha256": "0" * 64}})
        with self.assertRaises(RuntimeError):
            ex.Executor(config(embedding_cpu=True)).conferir_retomada(
                {"perguntas": {"sha256": atual}, "embedding": "bge-m3"})

    def _executor_com_stub(self, **extra):
        from ceres_benchmark import gabarito as gab
        e = ex.Executor(config(**extra))
        antiga, nova = e.cfg.todas_arquiteturas
        antiga["sha"], nova["sha"] = "a" * 40, "b" * 40
        fonte = (self.REPO / ex.FERRAMENTAS_REPO).read_text(encoding="utf-8")
        gabarito = (self.REPO / ex.GABARITO_REPO).read_text(encoding="utf-8")
        assinaturas = gab.assinaturas_ferramentas(fonte)
        stub = "\n".join(
            f'@Tool(description = "x")\npublic X {f}(' + ", ".join(f"String {p}" for p in assinaturas[f]) + ") {}"
            for f in antiga["funcoesConhecidas"])
        e.capturar = lambda comando, cwd=None: (
            gabarito if comando[-1].endswith(ex.GABARITO_REPO)
            else stub if comando[-1].startswith("a" * 40) else fonte)
        return e

    def test_rodada_da_banca_espera_a_revisao_das_externas(self):
        # O conjunto versionado ainda tem as 40 externas sem revisao: so --ensaio (ou --teste) roda.
        e = self._executor_com_stub()
        self.assertEqual(e.cfg.tipo, "banca")
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(RuntimeError, "revisao humana.*--ensaio"):
                e.preparar_gabaritos(Path(d))
            self.assertFalse((Path(d) / "gabarito-canonico.json").exists())
        self.assertEqual(self._executor_com_stub(ensaio=True).cfg.tipo, "ensaio")
        self.assertEqual(self._executor_com_stub(teste=True, ensaio=True).cfg.tipo, "teste")

    def test_conjunto_de_outro_gabarito_que_o_do_commit_medido(self):
        # Checkout atrasado (#371): o conjunto local passa no hash, mas o commit medido ja tem outro
        # gabarito. Banca recusa; ensaio avisa e segue.
        import json
        for ensaio in (False, True):
            e = self._executor_com_stub(ensaio=ensaio)
            antigo = json.loads((self.REPO / ex.GABARITO_REPO).read_text(encoding="utf-8"))
            antigo["versao"] = "2099-01-01.1"
            original = e.capturar
            e.capturar = lambda comando, cwd=None, _o=original: (
                json.dumps(antigo) if comando[-1].endswith(ex.GABARITO_REPO) else _o(comando, cwd))
            with tempfile.TemporaryDirectory() as d:
                if ensaio:
                    e.preparar_gabaritos(Path(d))
                else:
                    with self.assertRaisesRegex(RuntimeError, "baseado no gabarito .* commit medido"):
                        e.preparar_gabaritos(Path(d))

    def test_preparo_recusado_nao_mexe_na_pasta_da_rodada(self):
        from ceres_benchmark import gabarito as gab
        e = ex.Executor(config(ensaio=True))
        antiga, nova = e.cfg.todas_arquiteturas
        antiga["sha"], nova["sha"] = "a" * 40, "b" * 40
        fonte = (self.REPO / ex.FERRAMENTAS_REPO).read_text(encoding="utf-8")
        gabarito = (self.REPO / ex.GABARITO_REPO).read_text(encoding="utf-8")
        assinaturas = gab.assinaturas_ferramentas(fonte)
        # O 2463c27 de mentira: so as funcoes da matriz, com os parametros de hoje.
        stub = "\n".join(
            f'@Tool(description = "x")\npublic X {f}(' + ", ".join(f"String {p}" for p in assinaturas[f]) + ") {}"
            for f in antiga["funcoesConhecidas"])
        e.capturar = lambda comando, cwd=None: (
            gabarito if comando[-1].endswith(ex.GABARITO_REPO)
            else stub if comando[-1].startswith("a" * 40) else fonte)
        with tempfile.TemporaryDirectory() as d:
            pasta = Path(d)
            (pasta / "gabarito-canonico.json").write_text("ANTIGO", encoding="utf-8")
            original = gab.conferir_capacidade
            gab.conferir_capacidade = lambda *a: ["X: errado"]
            try:
                with self.assertRaises(RuntimeError):
                    e.preparar_gabaritos(pasta)
            finally:
                gab.conferir_capacidade = original
            self.assertEqual((pasta / "gabarito-canonico.json").read_text(encoding="utf-8"), "ANTIGO")
            self.assertFalse((pasta / "adaptacao-ceres-1.json").exists())
            # Sem a recusa, entra tudo e a Ceres 2.0 le a copia congelada da pasta.
            e.preparar_gabaritos(pasta)
            self.assertEqual(e.cfg.todas_arquiteturas[-1]["gabarito"], pasta / "gabarito-canonico.json")
            self.assertEqual(ex.sha256_arquivo(pasta / "gabarito-canonico.json"),
                             ex.sha256_arquivo(self.REPO / e.cfg.matriz["perguntas"]))
            self.assertFalse((pasta / ".preparo").exists())

    def test_matriz_com_menos_de_3_repeticoes_e_teste(self):
        import json
        with tempfile.TemporaryDirectory() as d:
            matriz = json.loads((AQUI.parent / "matriz.json").read_text(encoding="utf-8"))
            matriz["repeticoes"] = 1
            arquivo = Path(d) / "m.json"
            arquivo.write_text(json.dumps(matriz), encoding="utf-8")
            cfg = config(matriz=str(arquivo))
            self.assertTrue(cfg.teste)
            self.assertEqual(cfg.repeticoes, 1)


class ConfiguracaoPublicadaTest(unittest.TestCase):
    REPO = AQUI.parents[3]

    def test_le_a_configuracao_publicada_do_env_example_real(self):
        publicada = ex.configuracao_publicada((self.REPO / ".env.example").read_text(encoding="utf-8"))
        self.assertEqual(publicada["AI_MAX_TOKENS"], "1024")
        self.assertEqual(publicada["AI_OLLAMA_NUM_CTX"], "8192")
        for fora in ("AI_API_KEY", "AI_CHAT_MODEL", "AI_OLLAMA_BASE_URL", "AI_CHAT_PROVIDER"):
            self.assertNotIn(fora, publicada)

    def test_ambiente_nao_herda_ai_do_shell_e_aplica_a_publicada(self):
        import os
        e = ex.Executor(config())
        os.environ["AI_MAX_TOKENS"] = "64"
        try:
            env = e.ambiente_java("b", "qwen3:0.6b", {"configuracaoPublicada": {"AI_MAX_TOKENS": "512"}})
            self.assertEqual(env["AI_MAX_TOKENS"], "512")
            self.assertNotIn("AI_MAX_TOKENS", e.ambiente_java("b", "qwen3:0.6b"))
        finally:
            del os.environ["AI_MAX_TOKENS"]
        self.assertEqual(env["AI_CHAT_MODEL"], "qwen3:0.6b")
        self.assertEqual(env["AI_OLLAMA_THINK"], "false")

    def test_opcoes_diferentes_entre_commits_sao_registradas_sem_recusar(self):
        e = ex.Executor(config())
        antiga, nova = e.cfg.todas_arquiteturas
        antiga["sha"], nova["sha"] = "a" * 40, "b" * 40
        yml = (self.REPO / "src/main/resources/application.yml").read_text(encoding="utf-8")

        def capturar(comando, cwd=None):
            alvo = comando[-1]
            if alvo.endswith(":.env.example"):
                return "AI_MAX_TOKENS=1024\n" if alvo.startswith("a") else "AI_MAX_TOKENS=512\n"
            return yml
        e.capturar = capturar
        por_arq = e.conferir_opcoes_chat()
        self.assertEqual(por_arq["ceres-1"]["chat"]["num-predict"], "1024")
        self.assertEqual(por_arq["ceres-2"]["chat"]["num-predict"], "512")
        self.assertEqual(nova["configuracaoPublicada"], {"AI_MAX_TOKENS": "512"})
