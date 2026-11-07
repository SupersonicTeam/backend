"""Partes puras do executor da matriz (issue #348): nada aqui chama Ollama, banco nem git."""

import argparse
import json
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent))

from ceres_benchmark import executor as ex  # noqa: E402

# O executor le CERES_BENCHMARK_CEGO; com ele no ambiente de quem roda os testes, os casos "sem o
# cego" deixariam de ser sem o cego.
os.environ.pop("CERES_BENCHMARK_CEGO", None)


def config(**extra):
    args = argparse.Namespace(matriz=str(AQUI.parent / "matriz.json"), arquiteturas=None, modelos=None,
                              ref_ceres2=None, saida="saida", trabalho="trabalho", repeticoes=None,
                              hardware="maquina X", retomar=None, teste=False, embedding_cpu=False,
                              ensaio=False, cego=None)
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

    def test_compromisso_das_ineditas_sem_o_texto_delas(self):
        """#387: aqui so as herdadas e o compromisso (ids e sha256 do repositorio cego)."""
        import json
        matriz = json.loads((AQUI.parent / "matriz.json").read_text(encoding="utf-8"))
        conjunto = json.loads((self.REPO / matriz["perguntas"]).read_text(encoding="utf-8"))
        self.assertEqual({p["origem"] for p in conjunto["perguntas"]}, {"gabarito"})
        compromisso = conjunto["ineditas"]
        self.assertEqual((compromisso["repositorio"], compromisso["arquivo"]),
                         ("SupersonicTeam/ceres-benchmark-cego", "ineditas.json"))
        self.assertRegex(compromisso["sha256"], r"^[0-9a-f]{64}$")
        # A versao do cego e independente da deste conjunto.
        self.assertRegex(compromisso["versao"], r"^\d{4}-\d{2}-\d{2}\.\d+$")
        self.assertGreaterEqual(len(compromisso["ids"]), 53)
        # Um hash por pergunta normalizada (a guarda do treino sem o texto), nunca o texto.
        self.assertEqual(len(compromisso["normalizadas"]), len(compromisso["ids"]))
        for n in compromisso["normalizadas"]:
            self.assertEqual(set(n), {"palavras", "sha256"})
            self.assertRegex(n["sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(len(set(compromisso["ids"])), len(compromisso["ids"]))
        self.assertFalse(set(compromisso["ids"]) & {p["id"] for p in conjunto["perguntas"]})

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

    def test_coluna_oficial_exige_a_sessao_7_e_todo_tambem_exige(self):
        from unittest import mock
        e = ex.Executor(config())
        e.capturar = lambda comando, cwd=None: ""
        e.cfg.todas_arquiteturas[-1]["sha"] = "b" * 40
        sete = e.cfg.matriz["sessaoParalela"]["commit"]
        extras = {x["commit"] for x in e.cfg.matriz["sessaoParalela"]["tambemExige"]}
        # Faltando qualquer um dos exigidos (o primeiro, o do meio ou o ultimo), nao e oficial.
        exigidos = [x["commit"] for x in e.cfg.matriz["sessaoParalela"]["tambemExige"]]
        sem_um = [(({sete} | extras) - {c}, False, True) for c in exigidos]
        for presentes, confirmado, inteira in (({sete} | extras, True, True), ({sete}, False, True),
                                               *sem_um, (set(), False, False)):
            def run(comando, cwd=None, _p=presentes):
                return mock.Mock(returncode=0 if comando[3] in _p else 1)
            with mock.patch.object(ex.subprocess, "run", run):
                sessao = e.sessao_paralela()
            self.assertEqual((sessao["confirmado"], sessao["inteira"]), (confirmado, inteira))
            self.assertEqual(bool(sessao["faltando"]), not extras <= presentes)

    def test_banca_exige_revisado_por_da_equipe_ou_do_orientador(self):
        """#387: sem contador; a trava le revisado.por, nao o antigo booleano."""
        perguntas = [
            {"id": "E1", "origem": "inedita_externa_ia", "aConferir": True, "revisado": False},
            {"id": "E2", "origem": "inedita_externa_ia", "revisado": True},
            {"id": "E3", "origem": "inedita_externa_ia", "revisado": {"por": " ", "em": "2026-11-10"}},
            {"id": "E4", "origem": "inedita_externa_ia", "revisado": {"por": "Orientador", "em": "2026-11-10"}},
            {"id": "E5", "origem": "inedita_externa_ia", "aConferir": True,
             "revisado": {"por": "Luiz", "em": "2026-11-10"}},
            {"id": "N1", "origem": "inedita_equipe_dados", "autor": "Luiz"},
            {"id": "G1", "origem": "gabarito"},
            {"id": "I1", "origem": "inedita_equipe"},
        ]
        self.assertEqual(ex.perguntas_sem_revisao(perguntas), ["E1", "E2", "E3", "E5", "N1"])
        # Quem escreveu nao revisa a propria, por nenhuma grafia do nome (a regra da planilha).
        for por in ("Luiz", "luiz gervazio", "LUÍZ F."):
            self.assertFalse(ex.revisada({"autor": "Luiz", "revisado": {"por": por, "em": "2026-11-10"}}), por)
        self.assertTrue(ex.revisada({"autor": "Luiz", "revisado": {"por": "Herick", "em": "2026-11-10"}}))

    def test_juntar_ineditas_confere_o_compromisso(self):
        import hashlib
        import json
        cego = {"versao": "v1", "perguntas": [{"id": "Z1", "pergunta": "x?", "origem": "inedita_equipe"}]}
        with tempfile.TemporaryDirectory() as d:
            pasta = Path(d)
            texto = json.dumps(cego)
            (pasta / "ineditas.json").write_text(texto, encoding="utf-8")
            sha = hashlib.sha256(texto.encode()).hexdigest()
            base = {"versao": "b1", "ineditas": {"arquivo": "ineditas.json", "versao": "v1", "sha256": sha,
                                                 "ids": ["Z1"]}, "perguntas": [{"id": "D1", "pergunta": "y?"}]}
            junto, info = ex.juntar_ineditas(base, pasta, "banca")
            self.assertEqual([p["id"] for p in junto["perguntas"]], ["D1", "Z1"])
            self.assertEqual((info["presente"], info["confere"], info["sha256"]), (True, True, sha))
            self.assertEqual(base["perguntas"], [{"id": "D1", "pergunta": "y?"}])
            # Sem o cego: banca recusa; ensaio segue so com as herdadas.
            with self.assertRaisesRegex(RuntimeError, "conjunto cego ausente"):
                ex.juntar_ineditas(base, pasta / "nao-existe", "banca")
            junto, info = ex.juntar_ineditas(base, pasta / "nao-existe", "ensaio")
            self.assertEqual(([p["id"] for p in junto["perguntas"]], info["presente"]), (["D1"], False))
            # Hash, versao ou ids diferentes: banca recusa; ensaio avisa e segue com as do cego.
            for campo, valor in (("sha256", "0" * 64), ("versao", "v2"), ("ids", ["Z1", "Z2"])):
                outro = {**base, "ineditas": {**base["ineditas"], campo: valor}}
                with self.assertRaisesRegex(RuntimeError, "nao confere com o compromisso"):
                    ex.juntar_ineditas(outro, pasta, "banca")
                junto, info = ex.juntar_ineditas(outro, pasta, "ensaio")
                self.assertEqual((len(junto["perguntas"]), info["confere"]), (2, False))
            # Id repetido entre herdada e inedita nunca passa, nem no ensaio.
            repetido = {**base, "perguntas": [{"id": "Z1", "pergunta": "z?"}]}
            with self.assertRaisesRegex(RuntimeError, "id repetido"):
                ex.juntar_ineditas(repetido, pasta, "ensaio")
        # Conjunto sem compromisso (o antigo, com tudo dentro): passa como esta.
        self.assertEqual(ex.juntar_ineditas({"perguntas": []}, None, "banca"), ({"perguntas": []}, None))

    def test_retomada_confere_o_conjunto_cego(self):
        import hashlib
        import json
        with tempfile.TemporaryDirectory() as d:
            pasta = Path(d)
            compromisso = json.loads((AQUI.parent / "perguntas-benchmark.json").read_text(encoding="utf-8"))["ineditas"]
            (pasta / compromisso["arquivo"]).write_text("{}", encoding="utf-8")
            cego_sha = hashlib.sha256(b"{}").hexdigest()
            atual = ex.sha256_arquivo(AQUI.parent / "perguntas-benchmark.json")
            e = ex.Executor(config(cego=str(pasta)))
            # A mesma: segue. Outra, ausente no inicio ou sumida agora: recusa.
            e.conferir_retomada({"perguntas": {"sha256": atual, "cego": {"sha256": cego_sha}}, "embedding": "bge-m3", "readTimeoutCelula": "240s"})
            for antes in ({"sha256": "0" * 64}, None):
                with self.assertRaisesRegex(RuntimeError, "conjunto cego mudou"):
                    e.conferir_retomada({"perguntas": {"sha256": atual, "cego": antes}, "embedding": "bge-m3", "readTimeoutCelula": "240s"})
            sem = ex.Executor(config())
            with self.assertRaisesRegex(RuntimeError, "conjunto cego mudou"):
                sem.conferir_retomada({"perguntas": {"sha256": atual, "cego": {"sha256": cego_sha}},
                                       "embedding": "bge-m3", "readTimeoutCelula": "240s"})

    def test_caminho_do_cego(self):
        import os
        repo = Path("/x/backend")
        # Nunca achado sozinho (#387): a pasta irma nao conta, so --cego ou a variavel.
        antigo = os.environ.pop("CERES_BENCHMARK_CEGO", None)
        try:
            self.assertIsNone(ex.caminho_cego(None, repo))
        finally:
            if antigo is not None:
                os.environ["CERES_BENCHMARK_CEGO"] = antigo
        self.assertEqual(ex.caminho_cego("/y/cego", repo), Path("/y/cego"))
        antigo = os.environ.get("CERES_BENCHMARK_CEGO")
        os.environ["CERES_BENCHMARK_CEGO"] = "/z/cego"
        try:
            self.assertEqual(ex.caminho_cego(None, repo), Path("/z/cego"))
        finally:
            if antigo is None:
                del os.environ["CERES_BENCHMARK_CEGO"]
            else:
                os.environ["CERES_BENCHMARK_CEGO"] = antigo

    def test_retomada_refaz_a_celula_sem_nenhuma_prova_do_pensamento(self):
        medida = {"codigoSaida": 0, "pensamento": {"raciocinou": False}}
        sem_prova = {"codigoSaida": 0, "pensamento": {"semProva": True}}
        self.assertEqual(ex.decidir_retomada(medida, True), "pular")
        self.assertEqual(ex.decidir_retomada(sem_prova, True), "sem_prova")
        self.assertEqual(ex.decidir_retomada(medida, False), "outra")
        self.assertEqual(ex.decidir_retomada({"codigoSaida": 1}, True), "medir")

    def test_avisos_das_provas_do_pensamento(self):
        ok = {"raciocinou": False, "resumo": "desligado"}
        self.assertEqual(ex.avisos_das_provas(dict(ok), dict(ok)), [])
        inicio = {"erro": "URLError: x", "resumo": "prova falhou: URLError: x"}
        avisos = ex.avisos_das_provas(inicio, {"raciocinou": False, "pensamento": "", "resposta": "4"})
        self.assertIn("vale só a do fim", avisos[0])
        self.assertIn("(na prova do fim da célula)", inicio["resumo"])
        self.assertNotIn("semProva", inicio)
        duas = {"erro": "a"}
        self.assertIn("as duas provas", ex.avisos_das_provas(duas, {"erro": "b"})[0])
        self.assertTrue(duas["semProva"])
        self.assertIn("vale só a do início", ex.avisos_das_provas(dict(ok), {"erro": "b"})[0])

    def test_retomada_recusa_rodada_antiga_conjunto_ou_embedding_diferente(self):
        e = ex.Executor(config())
        atual = ex.sha256_arquivo(self.REPO / e.cfg.matriz["perguntas"])
        e.conferir_retomada({"perguntas": {"sha256": atual}, "embedding": "bge-m3", "readTimeoutCelula": "240s"})
        with self.assertRaises(RuntimeError):
            e.conferir_retomada({"embedding": "bge-m3"})  # rodada do #349, sem o conjunto
        with self.assertRaises(RuntimeError):
            e.conferir_retomada({"perguntas": {"sha256": "0" * 64}})
        with self.assertRaises(RuntimeError):
            ex.Executor(config(embedding_cpu=True)).conferir_retomada(
                {"perguntas": {"sha256": atual}, "embedding": "bge-m3", "readTimeoutCelula": "240s"})

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

    def test_rodada_da_banca_exige_o_conjunto_cego(self):
        # As ineditas ficam no repositorio cego (#387): sem ele, a banca recusa antes de tudo.
        e = self._executor_com_stub()
        self.assertEqual(e.cfg.tipo, "banca")
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(RuntimeError, "conjunto cego ausente"):
                e.preparar_gabaritos(Path(d))
            self.assertFalse((Path(d) / "gabarito-canonico.json").exists())

    def test_rodada_da_banca_espera_a_revisao_das_externas(self):
        # Com o cego (aqui o ficticio, aceito como se conferisse), as externas sem revisado.por travam.
        import json
        e = self._executor_com_stub()
        cego = json.loads((AQUI / "fixtures/cego-ficticio/ineditas.json").read_text(encoding="utf-8"))
        original = ex.juntar_ineditas
        ex.juntar_ineditas = lambda base, pasta, tipo: (
            {**base, "perguntas": base["perguntas"] + cego["perguntas"]}, {"presente": True, "confere": True})
        try:
            with tempfile.TemporaryDirectory() as d:
                with self.assertRaisesRegex(RuntimeError, "revisao da equipe ou do orientador.*--ensaio"):
                    e.preparar_gabaritos(Path(d))
        finally:
            ex.juntar_ineditas = original
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


class ReadTimeoutDaCelulaTest(unittest.TestCase):
    """#434: o unico desvio da configuracao publicada, e o que ele nao pode virar."""

    REPO = AQUI.parents[3]

    def test_valor_efetivo_com_o_env_example_real(self):
        publicada = ex.configuracao_publicada((self.REPO / ".env.example").read_text(encoding="utf-8"))
        self.assertEqual(publicada["AI_READ_TIMEOUT"], "120s")  # o que a producao usa
        e = ex.Executor(config())
        env = e.ambiente_java("b", "qwen3:0.6b", {"configuracaoPublicada": publicada})
        self.assertEqual(env["AI_READ_TIMEOUT"], "240s")
        # O resto da publicada segue como esta.
        self.assertEqual(env["AI_MAX_TOKENS"], publicada["AI_MAX_TOKENS"])

    def test_variavel_do_shell_nao_vaza(self):
        os.environ["AI_READ_TIMEOUT"] = "999s"
        try:
            e = ex.Executor(config())
            self.assertEqual(e.ambiente_java("b", "m")["AI_READ_TIMEOUT"], "240s")
            sem_chave = ex.Executor(config())
            sem_chave.cfg.matriz.pop("readTimeoutCelula")
            self.assertNotIn("AI_READ_TIMEOUT", sem_chave.ambiente_java("b", "m"))
        finally:
            del os.environ["AI_READ_TIMEOUT"]

    def test_fica_abaixo_do_prazo_da_pergunta(self):
        # AvaliacaoCeresRunner: timeout da pergunta (5 min) + FOLGA_TIMEOUT (5 s).
        self.assertEqual(ex.PRAZO_PERGUNTA_S, 5 * 60 + 5)
        self.assertLess(ex.segundos_de_duracao(config().read_timeout_celula), ex.PRAZO_PERGUNTA_S)
        ex.validar_read_timeout("300s")
        ex.validar_read_timeout("4m")
        for invalido in ("305s", "6m", "5m5s", "duas horas", "2.5m"):
            with self.assertRaises(RuntimeError, msg=invalido):
                ex.validar_read_timeout(invalido)
        e = ex.Executor(config())
        e.cfg.matriz["readTimeoutCelula"] = "10m"
        with self.assertRaises(RuntimeError):
            e.ambiente_java("b", "m")

    def test_prazo_da_pergunta_e_o_do_backend(self):
        """Sem deriva: 305 s = ``timeout-pergunta`` do application.yml + ``FOLGA_TIMEOUT`` do runner."""
        yml = (self.REPO / "src/main/resources/application.yml").read_text(encoding="utf-8")
        padrao = re.search(r"timeout-pergunta:\s*\$\{AI_TIMEOUT_PERGUNTA:([^}]+)\}", yml).group(1)
        runner = (self.REPO / "src/main/java/br/com/agrocontabil/config/avaliacao/"
                  "AvaliacaoCeresRunner.java").read_text(encoding="utf-8")
        folga = int(re.search(r"FOLGA_TIMEOUT\s*=\s*Duration\.ofSeconds\((\d+)\)", runner).group(1))
        self.assertEqual(ex.segundos_de_duracao(padrao) + folga, ex.PRAZO_PERGUNTA_S)

    def test_opcoes_registram_o_ajuste_sem_mexer_na_publicada(self):
        e = ex.Executor(config())
        antiga, nova = e.cfg.todas_arquiteturas
        antiga["sha"], nova["sha"] = "a" * 40, "b" * 40
        yml = (self.REPO / "src/main/resources/application.yml").read_text(encoding="utf-8")
        e.capturar = lambda comando, cwd=None: (
            "AI_READ_TIMEOUT=120s\n" if comando[-1].endswith(":.env.example") else yml)
        por_arq = e.conferir_opcoes_chat()
        for v in por_arq.values():
            self.assertEqual(v["publicada"], {"AI_READ_TIMEOUT": "120s"})
            self.assertEqual(v["ajustes"], {"AI_READ_TIMEOUT": "240s"})


class ProvaDoPensamentoComContextoTest(unittest.TestCase):
    """#434: as provas carregam o modelo com o num_ctx da Ceres e dizem quanto levou."""

    def executor(self):
        e = ex.Executor(config())
        e.chamadas = []

        def ollama(caminho, corpo=None, timeout=30):
            e.chamadas.append((caminho, corpo))
            if caminho == "/api/chat":
                return {"message": {"content": "<think>\n\n</think>\n\n4"}, "eval_count": 3,
                        "total_duration": 3_000_000_000, "load_duration": 2_500_000_000}
            if caminho == "/api/show":
                return {"capabilities": ["completion"]}
            return {"models": []}
        e.ollama = ollama
        return e

    def test_corpo_da_prova_leva_num_ctx_inteiro_think_false_e_keep_alive(self):
        e = self.executor()
        prova = e.provar_pensamento("qwen3:0.6b", 8192)
        corpo = next(c for caminho, c in e.chamadas if caminho == "/api/chat")
        self.assertIs(type(corpo["options"]["num_ctx"]), int)
        self.assertEqual(corpo["options"]["num_ctx"], 8192)
        self.assertIs(corpo["think"], False)
        self.assertEqual(corpo["keep_alive"], ex.KEEP_ALIVE_PROVA)
        self.assertEqual((prova["numCtx"], prova["carregamentoMs"]), (8192, 2500))

    def test_sem_num_ctx_nao_envia_e_grava_nulo(self):
        e = self.executor()
        prova = e.provar_pensamento("qwen3:0.6b")
        corpo = next(c for caminho, c in e.chamadas if caminho == "/api/chat")
        self.assertNotIn("num_ctx", corpo["options"])
        self.assertEqual(corpo["keep_alive"], ex.KEEP_ALIVE_PROVA)
        self.assertIsNone(prova["numCtx"])

    def test_carregamento_ausente_e_nulo(self):
        self.assertIsNone(ex.avaliar_prova({"message": {"content": "4"}})["carregamentoMs"])

    def test_num_ctx_da_arquitetura_vem_do_texto_das_opcoes(self):
        e = ex.Executor(config())
        e.opcoes = {"ceres-1": {"chat": {"num-ctx": "4096"}}, "ceres-2": {"chat": {"num-ctx": "8192"}},
                    "x": {"chat": {"num-ctx": "${X}"}}, "y": {"chat": {}}}
        self.assertEqual(e.num_ctx({"nome": "ceres-1"}), 4096)
        self.assertEqual(e.num_ctx({"nome": "ceres-2"}), 8192)
        self.assertIsNone(e.num_ctx({"nome": "x"}))
        self.assertIsNone(e.num_ctx({"nome": "y"}))
        self.assertIsNone(e.num_ctx({"nome": "z"}))


class RodarCelulaTest(unittest.TestCase):
    """#434: ``rodar_celula`` de ponta a ponta com Ollama, Java e disco falsos."""

    def preparar(self, temp, falha=False, latencia=1000):
        e = ex.Executor(config())
        e.chamadas = []
        e.opcoes = {"ceres-1": {"chat": {"num-ctx": "8192"}}, "ceres-2": {"chat": {"num-ctx": "8192"}}}
        e.config_rodada = None
        e.conferir_configuracao = lambda gravada: {"versao": "x", "modelos": {}}
        e.descarregar_outros = lambda manter: None

        def ollama(caminho, corpo=None, timeout=30):
            e.chamadas.append((caminho, corpo))
            if caminho == "/api/chat":
                return {"message": {"content": "<think>\n\n</think>\n\n4"}, "load_duration": 1_000_000_000}
            return {"models": [], "capabilities": []}
        e.ollama = ollama

        def rodar(comando, cwd=None, entrada=None, env=None, saida=None, checar=True):
            e.env_java = env
            destino = next(Path(a.split("=", 1)[1]) for a in comando if a.startswith("--avaliacao.saida="))
            execucao = {"rota": "DADOS", "desfecho": "MODO_FALLBACK" if falha else "RESPONDIDA",
                        "modoFallback": falha, "latenciaMs": latencia, "erro": None}
            (destino / "2026-11-07-1200-ollama-x.json").write_text(
                json.dumps({"resultados": [{"id": "T1", "execucao": execucao}]}), encoding="utf-8")
            return 0
        e.rodar = rodar
        gabarito = Path(temp) / "gabarito.json"
        gabarito.write_text("{}", encoding="utf-8")
        arquitetura = {"nome": "ceres-1", "rotulo": "Ceres 1", "sha": "a" * 40,
                       "configuracaoPublicada": {"AI_READ_TIMEOUT": "120s"}}
        return e, arquitetura, gabarito

    def rodar_celula(self, e, arquitetura, gabarito, rodada):
        return e.rodar_celula(rodada, arquitetura, Path(rodada), Path("x.jar"), "banco", gabarito,
                              "qwen3:0.6b")

    def test_as_duas_provas_recebem_o_num_ctx_e_o_manifesto_grava_o_timeout(self):
        with tempfile.TemporaryDirectory() as d:
            e, arquitetura, gabarito = self.preparar(d)
            manifesto = self.rodar_celula(e, arquitetura, gabarito, Path(d) / "rodada")
            chats = [c for caminho, c in e.chamadas if caminho == "/api/chat"]
            self.assertEqual(len(chats), 2)  # inicio e fim da celula
            for corpo in chats:
                self.assertEqual(corpo["options"]["num_ctx"], 8192)
                self.assertEqual(corpo["keep_alive"], ex.KEEP_ALIVE_PROVA)
            self.assertEqual(e.env_java["AI_READ_TIMEOUT"], "240s")
            self.assertEqual((manifesto["readTimeoutCelula"], manifesto["numCtx"]), ("240s", 8192))
            self.assertEqual(manifesto["pensamento"]["numCtx"], 8192)
            self.assertEqual(manifesto["pensamento"]["final"]["carregamentoMs"], 1000)
            self.assertEqual(manifesto["avisos"], [])

    def test_aviso_de_falha_tecnica_com_as_lentas(self):
        with tempfile.TemporaryDirectory() as d:
            e, arquitetura, gabarito = self.preparar(d, falha=True, latencia=130_000)
            manifesto = self.rodar_celula(e, arquitetura, gabarito, Path(d) / "rodada")
            avisos = [a for a in manifesto["avisos"] if "falha técnica" in a]
            self.assertEqual(len(avisos), 1)
            self.assertIn("1 de 1 respostas com falha técnica", avisos[0])
            self.assertIn("1 com latência total de 120 s ou mais", avisos[0])

    def test_json_ilegivel_com_codigo_zero_avisa(self):
        with tempfile.TemporaryDirectory() as d:
            quebrado = Path(d) / "c.json"
            self.assertEqual(ex.Executor._avisos_de_falha_tecnica(quebrado), [])  # ausente: sem aviso
            quebrado.write_text("{ nao e json", encoding="utf-8")
            avisos = ex.Executor._avisos_de_falha_tecnica(quebrado)
            self.assertEqual(len(avisos), 1)
            self.assertIn("não foi possível ler c.json", avisos[0])

    def test_latencia_exatamente_no_limite_de_producao_conta_como_lenta(self):
        execucao = {"rota": "DADOS", "desfecho": "RESPONDIDA", "modoFallback": False, "erro": None,
                    "latenciaMs": ex.LIMITE_PRODUCAO_MS}
        avisos = ex.avisos_de_falha_tecnica([[{"id": "T1", "execucao": execucao}]])
        self.assertEqual(len(avisos), 1)
        self.assertIn("1 de 1 respostas com latência total de 120 s ou mais", avisos[0])

    def test_so_lenta_sem_falha_avisa_a_latencia(self):
        with tempfile.TemporaryDirectory() as d:
            e, arquitetura, gabarito = self.preparar(d, latencia=130_000)
            manifesto = self.rodar_celula(e, arquitetura, gabarito, Path(d) / "rodada")
            self.assertEqual(len(manifesto["avisos"]), 1)
            self.assertIn("latência total de 120 s ou mais", manifesto["avisos"][0])
            self.assertNotIn("respostas com falha técnica", manifesto["avisos"][0])

    def test_retomada_recusa_celula_medida_com_outro_read_timeout_ou_sem_o_campo(self):
        with tempfile.TemporaryDirectory() as d:
            e, arquitetura, gabarito = self.preparar(d)
            rodada = Path(d) / "rodada"
            manifesto = self.rodar_celula(e, arquitetura, gabarito, rodada)
            arquivo = rodada / manifesto["celula"] / "celula.json"
            # Mesma configuracao: pula.
            self.assertEqual(self.rodar_celula(e, arquitetura, gabarito, rodada)["readTimeoutCelula"], "240s")
            for alterado in ("120s", None):
                gravado = json.loads(arquivo.read_text(encoding="utf-8"))
                if alterado is None:
                    del gravado["readTimeoutCelula"]
                else:
                    gravado["readTimeoutCelula"] = alterado
                arquivo.write_text(json.dumps(gravado), encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "read-timeout"):
                    self.rodar_celula(e, arquitetura, gabarito, rodada)

    def test_retomada_da_rodada_recusa_read_timeout_diferente_ou_ausente(self):
        e = ex.Executor(config())
        atual = ex.sha256_arquivo(AQUI.parents[3] / e.cfg.matriz["perguntas"])
        base = {"perguntas": {"sha256": atual}, "embedding": "bge-m3"}
        e.conferir_retomada({**base, "readTimeoutCelula": "240s"})
        for anterior in ({**base, "readTimeoutCelula": "120s"}, base):
            with self.assertRaisesRegex(RuntimeError, "read-timeout"):
                e.conferir_retomada(anterior)

    def test_manifesto_da_rodada_grava_o_read_timeout(self):
        e = ex.Executor(config())
        e.fixar_shas = lambda anterior: None
        for a in e.cfg.todas_arquiteturas:
            a["sha"] = "a" * 40
        rodada = e.manifesto_rodada(None, {"ollama": "0.12", "trechosLegislacao": 8304})
        self.assertEqual(rodada["readTimeoutCelula"], "240s")
