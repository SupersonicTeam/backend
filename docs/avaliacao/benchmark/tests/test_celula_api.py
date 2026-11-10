"""Celula do modelo por API (``claude-haiku-5-5``, Ceres 2.0) no executor e no relatorio.

Nenhum teste chama a API da Anthropic: a prova do raciocinio e testada com ``Executor.anthropic``
trocado por um dublê e, para o protocolo HTTP, contra um servidor local de brinquedo. A chave dos testes
e fabricada (nao e uma chave de verdade).
"""

import argparse
import contextlib
import io
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest import mock

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent))

from ceres_benchmark import executor as ex  # noqa: E402
from ceres_benchmark import relatorio as rel  # noqa: E402

os.environ.pop("CERES_BENCHMARK_CEGO", None)

#: Fabricada para o teste: o formato nao importa, so que apareca e seja oculta.
CHAVE = "sk-ant-api03-CHAVE-FALSA-DE-TESTE-0123456789"
FIXTURE = AQUI / "fixtures" / "rodada-minima"
MODELO = "claude-haiku-5-5"


def config(**extra):
    args = argparse.Namespace(matriz=str(AQUI.parent / "matriz.json"), arquiteturas=None, modelos=None,
                              ref_ceres2=None, saida="saida", trabalho="trabalho", repeticoes=None,
                              hardware="maquina X", retomar=None, teste=False, embedding_cpu=False,
                              ensaio=False, cego=None)
    for k, v in extra.items():
        setattr(args, k, v)
    return ex.montar_config(args, AQUI.parents[3])


class ComChave(unittest.TestCase):
    """Base: a chave de teste no ambiente e no registro de segredos, limpos no fim."""

    def setUp(self):
        self.env = mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": CHAVE})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.addCleanup(ex._SEGREDOS.clear)
        self.e = ex.Executor(config())
        self.api = self.e.cfg.modelos_api[0]


class MatrizTest(unittest.TestCase):
    def test_o_haiku_esta_so_na_ceres_2_e_os_locais_nao_mudam(self):
        cfg = config()
        self.assertEqual(cfg.modelos, ["qwen3:0.6b", "qwen3:1.7b", "qwen3:4b-instruct"])
        self.assertEqual([m["modelo"] for m in cfg.modelos_api], [MODELO])
        api = cfg.modelos_api[0]
        self.assertEqual((api["provedor"], api["raciocinio"], api["arquiteturas"]),
                         ("anthropic", "disabled", ["ceres-2"]))
        ceres_1, ceres_2 = cfg.arquiteturas
        self.assertEqual([m for m, _ in cfg.celulas_da(ceres_1)], cfg.modelos)
        self.assertEqual([m for m, _ in cfg.celulas_da(ceres_2)], cfg.modelos + [MODELO])
        self.assertEqual([(a["nome"], m["modelo"]) for a, m in cfg.celulas_api()], [("ceres-2", MODELO)])

    def test_modelos_pede_o_haiku_e_os_locais_juntos_ou_separados(self):
        so_haiku = config(modelos=MODELO)
        self.assertEqual((so_haiku.modelos, [m["modelo"] for m in so_haiku.modelos_api]), ([], [MODELO]))
        so_local = config(modelos="qwen3:1.7b")
        self.assertEqual((so_local.modelos, so_local.modelos_api), (["qwen3:1.7b"], []))
        misto = config(modelos=f"qwen3:4b-instruct,{MODELO}")
        self.assertEqual((misto.modelos, [m["modelo"] for m in misto.modelos_api]),
                         (["qwen3:4b-instruct"], [MODELO]))

    def test_so_a_ceres_1_nao_tem_celula_por_api(self):
        self.assertEqual(config(arquiteturas="ceres-1").celulas_api(), [])

    def test_nome_da_celula(self):
        self.assertEqual(ex.nome_celula("ceres-2", MODELO), "ceres-2--claude-haiku-5-5")


class PreRequisitosTest(ComChave):
    def test_sem_a_chave_a_celula_e_recusada_antes_de_tudo(self):
        with mock.patch.dict(os.environ):
            os.environ.pop("ANTHROPIC_API_KEY")
            with self.assertRaises(RuntimeError) as erro:
                ex.Executor(config()).conferir_celulas_api()
        self.assertIn("ANTHROPIC_API_KEY ausente", str(erro.exception))
        self.assertIn("--modelos", str(erro.exception))

    def test_chave_vazia_tambem_e_recusada(self):
        with mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": ""}):
            with self.assertRaises(RuntimeError):
                ex.Executor(config()).conferir_celulas_api()

    def test_so_os_locais_nao_pedem_a_chave(self):
        with mock.patch.dict(os.environ):
            os.environ.pop("ANTHROPIC_API_KEY")
            ex.Executor(config(modelos="qwen3:1.7b")).conferir_celulas_api()

    def test_com_a_chave_passa(self):
        self.e.conferir_celulas_api()

    def test_provedor_e_raciocinio_fora_do_suportado_sao_recusados(self):
        for campo, valor in (("provedor", "openai"), ("raciocinio", "enabled"), ("raciocinio", "")):
            with self.subTest(campo=campo, valor=valor):
                e = ex.Executor(config())
                e.cfg.modelos_api[0] = {**e.cfg.modelos_api[0], campo: valor}
                with self.assertRaises(RuntimeError):
                    e.conferir_celulas_api()


class AmbienteTest(ComChave):
    def arquitetura(self):
        return {"nome": "ceres-2", "configuracaoPublicada": {"AI_READ_TIMEOUT": "120s", "AI_MAX_TOKENS": "1024"}}

    def test_ambiente_da_celula_por_api(self):
        with mock.patch.dict(os.environ, {"AI_CHAT_MODEL": "esquecido", "AI_ANTHROPIC_THINKING": "x"}):
            env = self.e.ambiente_java("b", MODELO, self.arquitetura(), self.api)
        self.assertEqual(env["AI_CHAT_PROVIDER"], "anthropic")
        self.assertEqual(env["AI_ANTHROPIC_MODEL"], MODELO)
        self.assertEqual(env["AI_ANTHROPIC_THINKING"], "disabled")
        self.assertEqual(env["ANTHROPIC_API_KEY"], CHAVE)
        # Sem o AI_* do shell e sem o modelo do Ollama.
        self.assertNotIn("AI_CHAT_MODEL", env)
        self.assertNotIn("AI_OLLAMA_THINK", env)
        # O embedding segue local.
        self.assertEqual(env["AI_OLLAMA_PULL_STRATEGY"], "never")
        self.assertIn("AI_OLLAMA_BASE_URL", env)
        self.assertEqual(env["AI_MAX_TOKENS"], "1024")

    def test_a_celula_por_api_usa_o_read_timeout_publicado_nao_o_da_celula(self):
        self.assertEqual(self.e.cfg.read_timeout_celula, "240s")
        com_api = self.e.ambiente_java("b", MODELO, self.arquitetura(), self.api)
        local = self.e.ambiente_java("b", "qwen3:4b-instruct", self.arquitetura())
        self.assertEqual(com_api["AI_READ_TIMEOUT"], "120s")
        self.assertEqual(local["AI_READ_TIMEOUT"], "240s")
        self.assertIsNone(self.e.read_timeout(self.api))
        self.assertEqual(self.e.read_timeout(None), "240s")

    def test_as_celulas_locais_nunca_recebem_a_chave(self):
        env = self.e.ambiente_java("b", "qwen3:4b-instruct", self.arquitetura())
        self.assertNotIn("ANTHROPIC_API_KEY", env)
        self.assertNotIn(CHAVE, "".join(env.values()))
        self.assertEqual(env["AI_CHAT_PROVIDER"], "ollama")
        # Nem o ambiente do schema (arquitetura sem celula, modelo "nenhum").
        self.assertNotIn("ANTHROPIC_API_KEY", self.e.ambiente_java("b", "nenhum", self.arquitetura()))

    def test_o_comando_nao_leva_a_chave_e_o_hardware_diz_que_e_api(self):
        args = self.e.argumentos_avaliacao(Path("g.json"), Path("s"), self.api)
        self.assertNotIn(CHAVE, " ".join(args))
        hardware = next(a for a in args if a.startswith("--avaliacao.hardware="))
        self.assertIn("maquina X", hardware)
        self.assertIn("API da Anthropic", hardware)
        self.assertIn("latência de rede", hardware)
        local = self.e.argumentos_avaliacao(Path("g.json"), Path("s"))
        self.assertIn("--avaliacao.hardware=maquina X", local)


class ConfiguracaoRealTest(unittest.TestCase):
    REPO = AQUI.parents[3]

    def test_opcoes_da_anthropic_no_application_yml_real(self):
        yml = (self.REPO / "src/main/resources/application.yml").read_text(encoding="utf-8")
        opcoes = ex.opcoes_chat_anthropic(yml)
        self.assertEqual(opcoes["temperature"], "1.0")
        self.assertEqual(opcoes["thinking"], "${AI_ANTHROPIC_THINKING:disabled}")
        self.assertEqual(ex.resolver_placeholder(opcoes["thinking"], {}), "disabled")
        self.assertIn("max-tokens", opcoes)

    def test_commit_sem_o_provedor_nao_tem_opcoes_da_anthropic(self):
        self.assertEqual(ex.opcoes_chat_anthropic("spring:\n  ai:\n    ollama:\n      chat:\n"), {})

    def test_modelo_e_raciocinio_da_api_nao_entram_na_configuracao_publicada(self):
        publicada = ex.configuracao_publicada(
            (self.REPO / ".env.example").read_text(encoding="utf-8"))
        self.assertNotIn("AI_ANTHROPIC_MODEL", publicada)
        self.assertNotIn("AI_ANTHROPIC_THINKING", publicada)
        self.assertNotIn("ANTHROPIC_API_KEY", publicada)


class ProvaDoRaciocinioTest(ComChave):
    def resposta(self, *blocos, **extra):
        return {"model": "claude-haiku-5-5-20260101", "stop_reason": "end_turn",
                "usage": {"output_tokens": 4}, "content": list(blocos), **extra}

    def provar(self, resposta):
        corpos = []

        def falso(corpo, timeout=120):
            corpos.append(corpo)
            return resposta

        with mock.patch.object(self.e, "anthropic", side_effect=falso):
            return self.e.provar_pensamento_api(MODELO, self.api), corpos

    def test_sem_bloco_de_raciocinio_a_prova_diz_desligado(self):
        prova, corpos = self.provar(self.resposta({"type": "text", "text": "pronto"}))
        self.assertFalse(prova["raciocinou"])
        self.assertEqual(prova["resumo"], "desligado (thinking=disabled; nenhum bloco de raciocínio na prova)")
        self.assertEqual(prova["resposta"], "pronto")
        self.assertEqual(prova["modeloRetornado"], "claude-haiku-5-5-20260101")
        self.assertEqual(prova["tokensGerados"], 4)
        self.assertIsNone(prova["capacidades"])
        # O que se envia: o mesmo thinking da JVM, o modelo da celula e a pergunta curta da prova.
        self.assertEqual(corpos[0]["thinking"], {"type": "disabled"})
        self.assertEqual(corpos[0]["model"], MODELO)
        self.assertEqual(corpos[0]["messages"][0]["content"], ex.PERGUNTA_PROVA)

    def test_bloco_thinking_ou_redacted_thinking_e_raciocinio_ativo(self):
        for bloco in ({"type": "thinking", "thinking": "vou pensar", "signature": "s"},
                      {"type": "redacted_thinking", "data": "xxxxx"}):
            with self.subTest(tipo=bloco["type"]):
                prova, _ = self.provar(self.resposta(bloco, {"type": "text", "text": "pronto"}))
                self.assertTrue(prova["raciocinou"])
                self.assertGreater(prova["caracteresPensamento"], 0)
                self.assertIn("ATIVO apesar de thinking=disabled", prova["resumo"])

    def test_celula_com_prova_ativa_fica_invalida_no_relatorio(self):
        prova, _ = self.provar(self.resposta({"type": "thinking", "thinking": "hum"}))
        celula = rel.Celula("c", "ceres-2", "Ceres 2.0", MODELO,
                            {"pensamento": {"raciocinou": prova["raciocinou"]}, "provedor": "anthropic"}, {})
        self.assertTrue(celula.invalida)

    def test_erro_da_api_vira_prova_com_erro_sem_a_chave(self):
        with mock.patch.object(self.e, "anthropic",
                               side_effect=urllib.error.URLError(f"falhou com {CHAVE} no cabecalho")):
            prova = self.e.provar_pensamento_api(MODELO, self.api)
        self.assertNotIn(CHAVE, json.dumps(prova))
        self.assertIn(ex.OCULTO, prova["erro"])
        self.assertTrue(prova["resumo"].startswith("prova falhou"))

    def test_as_duas_provas_com_erro_deixam_a_celula_sem_prova(self):
        pensamento = {"erro": "URLError: x"}
        avisos = ex.avisos_das_provas(pensamento, {"erro": "URLError: y"})
        self.assertTrue(pensamento["semProva"])
        self.assertEqual(len(avisos), 1)


class ServidorFalso(BaseHTTPRequestHandler):
    visto: dict = {}

    def do_POST(self):  # noqa: N802 (nome do http.server)
        tamanho = int(self.headers.get("content-length") or 0)
        ServidorFalso.visto = {"caminho": self.path, "corpo": json.loads(self.rfile.read(tamanho)),
                               "chave": self.headers.get("x-api-key"),
                               "versao": self.headers.get("anthropic-version")}
        carga = json.dumps({"content": [{"type": "text", "text": "pronto"}]}).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(carga)))
        self.end_headers()
        self.wfile.write(carga)

    def log_message(self, *args):  # silencio
        pass


class ProtocoloHttpTest(ComChave):
    def test_a_chave_vai_so_no_cabecalho_do_endereco_configurado(self):
        servidor = HTTPServer(("127.0.0.1", 0), ServidorFalso)
        threading.Thread(target=servidor.serve_forever, daemon=True).start()
        self.addCleanup(servidor.server_close)
        self.addCleanup(servidor.shutdown)
        with mock.patch.dict(os.environ, {"BENCH_ANTHROPIC_URL": f"http://127.0.0.1:{servidor.server_port}"}):
            resposta = self.e.anthropic({"model": MODELO, "max_tokens": 5, "messages": []}, timeout=10)
        self.assertEqual(resposta["content"][0]["text"], "pronto")
        visto = ServidorFalso.visto
        self.assertEqual(visto["caminho"], "/v1/messages")
        self.assertEqual(visto["chave"], CHAVE)
        self.assertEqual(visto["versao"], ex.VERSAO_API_ANTHROPIC)
        self.assertNotIn(CHAVE, json.dumps(visto["corpo"]))

    def test_endereco_que_nao_e_https_nem_local_e_recusado_antes_de_enviar(self):
        for url in ("http://exemplo.com", "ftp://x", "http://localhost.evil.com"):
            with self.subTest(url=url), mock.patch.dict(os.environ, {"BENCH_ANTHROPIC_URL": url}):
                with mock.patch("urllib.request.urlopen") as urlopen:
                    with self.assertRaises(RuntimeError):
                        self.e.anthropic({})
                urlopen.assert_not_called()

    def test_endereco_padrao_e_o_da_anthropic(self):
        with mock.patch.dict(os.environ):
            os.environ.pop("BENCH_ANTHROPIC_URL", None)
            with mock.patch("urllib.request.urlopen", side_effect=urllib.error.URLError("sem rede")) as u:
                with self.assertRaises(urllib.error.URLError):
                    self.e.anthropic({})
        self.assertEqual(u.call_args.args[0].full_url, "https://api.anthropic.com/v1/messages")


class SegredoTest(ComChave):
    def test_log_oculta_a_chave(self):
        saida = io.StringIO()
        with contextlib.redirect_stdout(saida):
            ex.log(f"comando com {CHAVE} no meio")
        self.assertNotIn(CHAVE, saida.getvalue())
        self.assertIn(ex.OCULTO, saida.getvalue())

    def test_valor_curto_nao_e_registrado_como_segredo(self):
        ex._SEGREDOS.clear()
        ex.registrar_segredo("curto")
        ex.registrar_segredo(None)
        ex.registrar_segredo("")
        self.assertEqual(ex.ocultar("curto"), "curto")

    def test_ocultar_em_arquivos_tira_a_chave_e_diz_onde_estava(self):
        pasta = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, pasta, True)
        (pasta / "sub").mkdir()
        (pasta / "comando.log").write_text(f"x {CHAVE} y", encoding="utf-8")
        (pasta / "sub" / "a.json").write_text(json.dumps({"k": CHAVE}), encoding="utf-8")
        (pasta / "limpo.md").write_text("nada", encoding="utf-8")
        achados = ex.ocultar_em_arquivos(pasta)
        self.assertEqual(sorted(achados), ["comando.log", str(Path("sub") / "a.json")])
        for arquivo in pasta.rglob("*"):
            if arquivo.is_file():
                self.assertNotIn(CHAVE.encode(), arquivo.read_bytes())
        self.assertEqual(ex.ocultar_em_arquivos(pasta), [])


AVALIACAO = {"rodadas": [{"resultados": [
    {"id": "D01", "execucao": {"rota": "DADOS", "desfecho": "RESPONDIDA", "modoFallback": False,
                               "erro": None, "latenciaMs": 1400}},
    {"id": "L01", "execucao": {"rota": "LEGISLACAO", "desfecho": "RESPONDIDA", "modoFallback": False,
                               "erro": None, "latenciaMs": 2000}}]}]}


class ExecutorFalso(ex.Executor):
    """Executor sem Ollama, JVM, banco nem API: o que a celula por API nao pode tocar levanta."""

    def __init__(self, cfg):
        super().__init__(cfg)
        self.comandos, self.ambientes, self.provas = [], [], 0
        self.amostrou = False
        self.config_rodada = {}

    def ollama(self, *a, **k):
        raise AssertionError("a celula por API nao fala com o Ollama")

    def descarregar_outros(self, manter):
        raise AssertionError("a celula por API nao descarrega modelos do Ollama")

    def amostrar_memoria(self, *a, **k):
        # Roda numa thread: levantar la nao falharia o teste; o sinal e conferido depois.
        self.amostrou = True

    def provar_pensamento(self, *a, **k):
        raise AssertionError("a prova do Ollama nao vale para a API")

    def conferir_configuracao(self, gravada):
        return gravada or {}

    def provar_pensamento_api(self, modelo, api):
        self.provas += 1
        prova = {"provedor": "anthropic", "thinking": "disabled", "raciocinou": False,
                 "caracteresPensamento": 0, "capacidades": None}
        prova["resumo"] = ex.resumo_pensamento(prova)
        return prova

    def rodar(self, comando, cwd=None, entrada=None, env=None, saida=None, checar=True):
        self.comandos.append(comando)
        self.ambientes.append(env)
        pasta = Path(next(c.split("=", 1)[1] for c in comando if c.startswith("--avaliacao.saida=")))
        (pasta / f"2026-11-10-1200-{MODELO}.json").write_text(json.dumps(AVALIACAO), encoding="utf-8")
        (pasta / f"2026-11-10-1200-{MODELO}.md").write_text("relatorio do comando", encoding="utf-8")
        if saida is not None:
            # A JVM nao imprime a chave, mas o executor nao confia nisso: o log e varrido.
            saida.write_text(f"subiu com {CHAVE}\n", encoding="utf-8")
        return 0


class CelulaPorApiTest(ComChave):
    def setUp(self):
        super().setUp()
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.gabarito = self.dir / "gabarito.json"
        self.gabarito.write_text("{}", encoding="utf-8")
        self.rodada = self.dir / "rodada"
        self.rodada.mkdir()
        self.f = ExecutorFalso(config())
        self.arquitetura = {"nome": "ceres-2", "rotulo": "Ceres 2.0", "sha": "d" * 40,
                            "configuracaoPublicada": {"AI_READ_TIMEOUT": "120s"}}

    def rodar(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return self.f.rodar_celula(self.rodada, self.arquitetura, self.dir, self.dir / "x.jar",
                                       "banco", self.gabarito, MODELO, self.f.cfg.modelos_api[0])

    def test_manifesto_da_celula_por_api(self):
        m = self.rodar()
        self.assertEqual((m["provedor"], m["modelo"], m["codigoSaida"]), ("anthropic", MODELO, 0))
        self.assertEqual(m["inferenciaEm"], "API da Anthropic (rede)")
        self.assertEqual(m["memoria"], {})
        self.assertIsNone(m["numCtx"])
        self.assertIsNone(m["readTimeoutCelula"])
        self.assertEqual(m["readTimeoutEfetivo"], "120s")
        self.assertEqual(m["pensamento"]["resumo"],
                         "desligado (thinking=disabled; nenhum bloco de raciocínio na prova)")
        self.assertEqual(self.f.provas, 2)  # no inicio e no fim, como nas celulas locais
        self.assertFalse(self.f.amostrou, "a celula por API nao amostra a memoria do Ollama")
        self.assertTrue(any("latência é de API pela rede" in a for a in m["avisos"]))
        self.assertIn(f"ceres-2--{MODELO}.json", m["arquivos"])
        self.assertTrue((self.rodada / f"ceres-2--{MODELO}" / "celula.json").exists())

    def test_so_a_jvm_da_celula_recebe_a_chave_e_nenhum_arquivo_a_guarda(self):
        self.rodar()
        env = self.f.ambientes[0]
        self.assertEqual((env["AI_CHAT_PROVIDER"], env["ANTHROPIC_API_KEY"]), ("anthropic", CHAVE))
        self.assertNotIn(CHAVE, " ".join(self.f.comandos[0]))
        for arquivo in (self.rodada).rglob("*"):
            if arquivo.is_file():
                self.assertNotIn(CHAVE.encode(), arquivo.read_bytes(), arquivo.name)

    def test_chave_achada_no_log_vira_aviso_e_e_redigida(self):
        m = self.rodar()
        log = (self.rodada / f"ceres-2--{MODELO}" / "comando.log").read_text(encoding="utf-8")
        self.assertIn(ex.OCULTO, log)
        self.assertTrue(any("apareceu em comando.log" in a for a in m["avisos"]))

    def test_retomada_pula_a_celula_ja_medida_sem_chamar_a_jvm(self):
        self.rodar()
        antes = len(self.f.comandos)
        with contextlib.redirect_stdout(io.StringIO()):
            m = self.f.rodar_celula(self.rodada, self.arquitetura, self.dir, self.dir / "x.jar",
                                    "banco", self.gabarito, MODELO, self.f.cfg.modelos_api[0])
        self.assertEqual(len(self.f.comandos), antes)
        self.assertEqual(m["provedor"], "anthropic")

    def test_manifesto_da_rodada_traz_a_celula_e_o_modelo_por_api(self):
        self.f.opcoes = {"ceres-2": {"anthropic": {"temperature": "1.0", "max-tokens": "1024",
                                                    "thinking": "disabled"}}}
        for a in self.f.cfg.todas_arquiteturas:
            a["sha"] = "d" * 40
        rodada = self.f.manifesto_rodada(None, {"ollama": "0.1", "trechosLegislacao": 8304})
        nomes = [c["nome"] for c in rodada["celulas"]]
        self.assertEqual(nomes[-1], f"ceres-2--{MODELO}")
        self.assertNotIn(f"ceres-1--{MODELO}", nomes)
        self.assertIn(MODELO, rodada["modelos"])
        self.assertEqual(len(rodada["modelosApi"]), 1)
        api = rodada["modelosApi"][0]
        self.assertEqual((api["modelo"], api["provedor"], api["raciocinio"], api["arquiteturas"]),
                         (MODELO, "anthropic", "disabled", ["ceres-2"]))
        self.assertEqual(api["opcoes"]["thinking"], "disabled")
        # Retomar so com os locais mantem o Haiku da rodada anterior.
        so_locais = ExecutorFalso(config(modelos="qwen3:1.7b"))
        so_locais.opcoes = self.f.opcoes
        for a in so_locais.cfg.todas_arquiteturas:
            a["sha"] = "d" * 40
        de_novo = so_locais.manifesto_rodada(rodada, {"ollama": "0.1", "trechosLegislacao": 8304})
        self.assertEqual([m["modelo"] for m in de_novo["modelosApi"]], [MODELO])
        self.assertIn(f"ceres-2--{MODELO}", [c["nome"] for c in de_novo["celulas"]])


# --------------------------------------------------------------------------- relatorio


def copiar_celula(pasta: Path, pensamento: dict | None = None, avisos: list | None = None) -> None:
    """Acrescenta a rodada minima uma celula ``ceres-2--claude-haiku-5-5`` (copia da ``m-ref``)."""
    origem = pasta / "ceres-2--m-ref"
    destino = pasta / f"ceres-2--{MODELO}"
    shutil.copytree(origem, destino)
    (destino / "ceres-2--m-ref.json").rename(destino / f"ceres-2--{MODELO}.json")
    manifesto = json.loads((destino / "celula.json").read_text(encoding="utf-8"))
    manifesto.update({
        "celula": f"ceres-2--{MODELO}", "modelo": MODELO, "provedor": "anthropic",
        "inferenciaEm": "API da Anthropic (rede)", "memoria": {},
        "pensamento": pensamento or {
            "resumo": "desligado (thinking=disabled; nenhum bloco de raciocínio na prova)"},
        "avisos": avisos or [],
    })
    (destino / "celula.json").write_text(json.dumps(manifesto), encoding="utf-8")
    rodada = json.loads((pasta / "rodada.json").read_text(encoding="utf-8"))
    rodada["celulas"].append({"nome": f"ceres-2--{MODELO}", "arquitetura": "ceres-2",
                              "rotuloArquitetura": "Ceres 2.0", "modelo": MODELO})
    rodada["modelos"].append(MODELO)
    rodada["modelosApi"] = [{"modelo": MODELO, "provedor": "anthropic", "raciocinio": "disabled",
                             "arquiteturas": ["ceres-2"],
                             "opcoes": {"temperature": "1.0", "max-tokens": "1024", "thinking": "disabled"}}]
    (pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")


class RelatorioComHaikuTest(unittest.TestCase):
    def setUp(self):
        self.pasta = Path(tempfile.mkdtemp()) / "rodada"
        shutil.copytree(FIXTURE, self.pasta)
        self.addCleanup(shutil.rmtree, self.pasta.parent, True)

    def gerar(self) -> str:
        return rel.gerar(self.pasta)

    def test_celula_por_api_aparece_marcada_e_sem_memoria(self):
        copiar_celula(self.pasta)
        texto = self.gerar()
        self.assertIn(f"Ceres 2.0 · `{MODELO}` (API)", texto)
        linha = next(l for l in texto.splitlines()
                     if l.startswith(f"| Ceres 2.0 · `{MODELO}` (API) | desligado"))
        self.assertIn("n/a (API)", linha)
        self.assertIn("API da Anthropic (rede)", linha)
        # As celulas locais seguem com os numeros de memoria de antes.
        local = next(l for l in texto.splitlines() if l.startswith("| Ceres 2.0 · `m-ref` | desligado"))
        self.assertNotIn("n/a (API)", local)
        self.assertIn("3,0 GB", local)

    def test_latencia_da_api_e_dita_de_rede_nao_comparavel_com_a_local(self):
        copiar_celula(self.pasta)
        texto = self.gerar()
        self.assertIn("A latência delas é de API pela rede", texto)
        self.assertIn("não a compare com a das células do qwen3", texto)
        self.assertIn("Latência p50 (rede)", texto)

    def test_sem_celula_por_api_o_relatorio_nao_fala_em_api(self):
        texto = self.gerar()
        self.assertNotIn("(API)", texto)
        self.assertNotIn("de API pela rede", texto)

    def test_condicoes_trazem_o_modelo_por_api_sem_a_chave(self):
        copiar_celula(self.pasta)
        texto = self.gerar()
        linha = next(l for l in texto.splitlines() if l.startswith(f"| `{MODELO}` (API) |"))
        self.assertIn("provedor anthropic", linha)
        self.assertIn("raciocínio disabled", linha)
        self.assertIn("thinking=disabled", linha)
        self.assertIn("só do ambiente", linha)
        self.assertNotIn("sk-ant", texto)

    def test_tabela_modelo_x_arquitetura_nao_diz_nao_rodou_na_ceres_1(self):
        copiar_celula(self.pasta)
        texto = self.gerar()
        linhas = [l for l in texto.splitlines() if l.startswith(f"| `{MODELO}` (API) |")
                  and "Acurácia de rota" in l]
        self.assertTrue(linhas)
        self.assertTrue(all("n/a (sem provedor)" in l and "não rodou" not in l for l in linhas))

    def test_conclusao_tem_paragrafo_proprio_e_o_haiku_fica_fora_da_viabilidade(self):
        copiar_celula(self.pasta)
        texto = self.gerar()
        conclusao = texto[texto.index("Conclusão para a banca"):]
        self.assertIn(f"**Modelo por API, Ceres 2.0 · `{MODELO}` (API)**", conclusao)
        self.assertIn("a diferença para o `m-ref` local", conclusao)
        self.assertIn("A latência da API é de rede e de fila do provedor", conclusao)
        # Nem "viavel" nem "nao viavel": o criterio e dos modelos locais menores.
        self.assertNotIn(f"- `{MODELO}`:", conclusao)
        self.assertNotIn(f"`{MODELO}` é viável", conclusao)

    def test_celula_por_api_com_raciocinio_ativo_e_invalida(self):
        copiar_celula(self.pasta, pensamento={"raciocinou": True,
                                              "resumo": "ATIVO apesar de thinking=disabled (3 caracteres)"})
        texto = self.gerar()
        self.assertIn("**Células inválidas para comparar**", texto)
        self.assertIn(f"Ceres 2.0 · `{MODELO}` (API)", texto[texto.index("Células inválidas"):])
        self.assertIn("a célula não rodou até o fim ou é inválida", texto)

    def test_graficos_marcam_a_celula_por_api(self):
        copiar_celula(self.pasta)
        self.gerar()
        svg = (self.pasta / "graficos" / "acerto-por-tema.svg").read_text(encoding="utf-8")
        self.assertIn(f"{MODELO} (API)", svg)

    def test_aviso_da_celula_por_api_entra_nos_avisos_do_relatorio(self):
        copiar_celula(self.pasta, avisos=["o chat desta célula foi pela API da Anthropic"])
        self.assertIn(f"Ceres 2.0 · `{MODELO}` (API): o chat desta célula foi pela API da Anthropic",
                      self.gerar())


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
