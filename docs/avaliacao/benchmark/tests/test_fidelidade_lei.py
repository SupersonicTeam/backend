"""Celula do ``claude-haiku-5-5`` com a fidelidade da rota de lei ligada (#269), no executor e no relatorio.

Nenhum teste chama a API, o Ollama ou o Maven. O que a celula tem de garantir: nao roda sem ser
pedida, leva so as variaveis dela, recusa um commit que nao as conhece, nao entra em nenhuma
comparacao e o relatorio diz como ler o descarte.
"""

import contextlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from ceres_benchmark import executor as ex  # noqa: E402
from ceres_benchmark import relatorio as rel  # noqa: E402

from .test_celula_api import (CHAVE, FIXTURE, MODELO, ComChave, ExecutorFalso, config,
                              copiar_celula)  # noqa: E402

VARIANTE = "claude-haiku-5-5+fidelidade-lei"
AMBIENTE = {
    "AI_FIDELIDADE_LEI_HABILITADA": "true",
    "AI_FIDELIDADE_LEI_JUIZ_HABILITADO": "true",
    "AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO": "justificativa",
    "AI_FIDELIDADE_LEI_JUIZ_RESGATE_HABILITADO": "true",
    "AI_FIDELIDADE_LEI_JUIZ_RESGATE_POLARIDADE": "true",
}
#: O application.yml da `main` de 2026-11-13 (e55eb28b): so a camada 1 e o juiz de veredito.
YML_MAIN = """
    fidelidade-lei:
      enabled: ${AI_FIDELIDADE_LEI_HABILITADA:false}
      cobertura-minima-com-juiz: ${AI_FIDELIDADE_LEI_COBERTURA_MINIMA_COM_JUIZ:0.3}
      juiz:
        enabled: ${AI_FIDELIDADE_LEI_JUIZ_HABILITADO:false}
"""
#: Com o resgate e a configuracao do juiz (a branch feat/269-juiz-resgate-bancada).
YML_COM_RESGATE = YML_MAIN + """
        configuracao: ${AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO:atual}
        resgate:
          enabled: ${AI_FIDELIDADE_LEI_JUIZ_RESGATE_HABILITADO:false}
          polaridade: ${AI_FIDELIDADE_LEI_JUIZ_RESGATE_POLARIDADE:false}
"""


def api_da_variante(cfg) -> dict:
    return next(m for m in cfg.modelos_api if m["modelo"] == VARIANTE)


class MatrizDaVarianteTest(unittest.TestCase):
    def test_a_matriz_real_declara_a_variante_opcional_com_as_cinco_variaveis(self):
        todas = json.loads((Path(config().repo) / "docs/avaliacao/benchmark/matriz.json")
                           .read_text(encoding="utf-8"))["modelosApi"]
        api = next(m for m in todas if m["modelo"] == VARIANTE)
        self.assertEqual((api["modeloApi"], api["variante"], api["opcional"], api["arquiteturas"],
                          api["raciocinio"], api["provedor"]),
                         (MODELO, "fidelidade-lei", True, ["ceres-2"], "disabled", "anthropic"))
        self.assertEqual(api["ambiente"], AMBIENTE)
        self.assertEqual(ex.validar_ambiente_da_celula(api), AMBIENTE)
        # A celula base nao tem nada disso: continua exatamente como era.
        base = next(m for m in todas if m["modelo"] == MODELO)
        self.assertFalse({"ambiente", "variante", "opcional", "modeloApi"} & set(base))

    def test_sem_a_opcao_a_matriz_roda_como_sempre(self):
        cfg = config()
        self.assertEqual([m["modelo"] for m in cfg.modelos_api], [MODELO])
        ceres_1, ceres_2 = cfg.arquiteturas
        self.assertEqual([m for m, _ in cfg.celulas_da(ceres_2)], cfg.modelos + [MODELO])
        # Pedir so o Haiku, ou so um local, tambem nao puxa a variante.
        self.assertEqual([m["modelo"] for m in config(modelos=MODELO).modelos_api], [MODELO])
        self.assertEqual(config(modelos="qwen3:1.7b").modelos_api, [])

    def test_a_opcao_acrescenta_a_variante_ao_lado_da_celula_base_e_so_na_ceres_2(self):
        cfg = config(com_fidelidade_lei=True)
        ceres_1, ceres_2 = cfg.arquiteturas
        self.assertEqual([m for m, _ in cfg.celulas_da(ceres_2)],
                         cfg.modelos + [MODELO, VARIANTE])
        self.assertEqual([m for m, _ in cfg.celulas_da(ceres_1)], cfg.modelos)
        self.assertEqual([(a["nome"], m["modelo"]) for a, m in cfg.celulas_api()],
                         [("ceres-2", MODELO), ("ceres-2", VARIANTE)])

    def test_a_opcao_soma_ao_que_o_modelos_pediu_sem_duplicar(self):
        cfg = config(modelos="qwen3:1.7b", com_fidelidade_lei=True)
        self.assertEqual((cfg.modelos, [m["modelo"] for m in cfg.modelos_api]),
                         (["qwen3:1.7b"], [VARIANTE]))
        pedida_duas_vezes = config(modelos=VARIANTE, com_fidelidade_lei=True)
        self.assertEqual([m["modelo"] for m in pedida_duas_vezes.modelos_api], [VARIANTE])

    def test_modelos_com_o_nome_da_variante_a_pede_sem_a_opcao(self):
        cfg = config(modelos=f"{MODELO},{VARIANTE}")
        self.assertEqual([m["modelo"] for m in cfg.modelos_api], [MODELO, VARIANTE])
        self.assertEqual(cfg.modelos, [])

    def test_so_a_ceres_1_com_a_opcao_nao_tem_celula_por_api(self):
        self.assertEqual(config(arquiteturas="ceres-1", com_fidelidade_lei=True).celulas_api(), [])

    def test_nome_da_pasta_da_variante_e_diferente_da_base(self):
        self.assertEqual(ex.nome_celula("ceres-2", VARIANTE), "ceres-2--claude-haiku-5-5-fidelidade-lei")
        self.assertNotEqual(ex.nome_celula("ceres-2", VARIANTE), ex.nome_celula("ceres-2", MODELO))


class AmbienteDaVarianteTest(unittest.TestCase):
    def api(self, **ambiente):
        return {"modelo": VARIANTE, "ambiente": ambiente}

    def test_variavel_do_executor_nao_pode_ser_mudada_por_uma_variante(self):
        for nome in ("AI_CHAT_PROVIDER", "AI_ANTHROPIC_MODEL", "AI_ANTHROPIC_THINKING", "AI_API_KEY",
                     "AI_OLLAMA_BASE_URL", "AI_READ_TIMEOUT"):
            with self.subTest(nome=nome):
                with self.assertRaises(RuntimeError) as erro:
                    ex.validar_ambiente_da_celula(self.api(**{nome: "x"}))
                self.assertIn("e do executor", str(erro.exception))

    def test_nome_fora_do_padrao_e_valor_vazio_sao_recusados(self):
        for nome, valor in (("ANTHROPIC_API_KEY", "x"), ("ai_minusculo", "x"), ("SPRING_PROFILES_ACTIVE", "x"),
                            ("AI_X", ""), ("AI_X", "  "), ("AI_X", True), ("AI_X", 3)):
            with self.subTest(nome=nome, valor=valor):
                with self.assertRaises(RuntimeError):
                    ex.validar_ambiente_da_celula(self.api(**{nome: valor}))

    def test_sem_ambiente_nao_ha_nada_a_validar(self):
        self.assertEqual(ex.validar_ambiente_da_celula({"modelo": MODELO}), {})

    def test_o_commit_precisa_ler_cada_variavel(self):
        self.assertEqual(ex.variaveis_que_o_commit_nao_conhece(AMBIENTE, YML_COM_RESGATE), [])
        self.assertEqual(ex.variaveis_que_o_commit_nao_conhece(AMBIENTE, YML_MAIN), [
            "AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO", "AI_FIDELIDADE_LEI_JUIZ_RESGATE_HABILITADO",
            "AI_FIDELIDADE_LEI_JUIZ_RESGATE_POLARIDADE"])

    def test_placeholder_sem_padrao_vale_e_nome_parecido_nao(self):
        self.assertEqual(ex.variaveis_que_o_commit_nao_conhece({"AI_X": "1"}, "a: ${AI_X}"), [])
        self.assertEqual(ex.variaveis_que_o_commit_nao_conhece({"AI_X": "1"}, "a: ${AI_XY:1}"), ["AI_X"])
        self.assertEqual(ex.variaveis_que_o_commit_nao_conhece({"AI_X": "1"}, "# AI_X sem placeholder"), ["AI_X"])


class ConferirNoCommitTest(ComChave):
    def setUp(self):
        super().setUp()
        self.e = ex.Executor(config(com_fidelidade_lei=True))
        for a in self.e.cfg.todas_arquiteturas:
            a["sha"] = "d" * 40

    def com_yml(self, yml):
        chamadas = []

        def capturar(comando, cwd=None):
            chamadas.append(comando)
            return yml

        self.e.capturar = capturar
        return chamadas

    def test_a_main_sem_o_resgate_recusa_a_celula_com_a_causa_e_a_saida(self):
        self.com_yml(YML_MAIN)
        with self.assertRaises(RuntimeError) as erro:
            self.e.conferir_ambiente_no_commit()
        texto = str(erro.exception)
        self.assertIn(VARIANTE, texto)
        self.assertIn("AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO", texto)
        self.assertIn("AI_FIDELIDADE_LEI_JUIZ_RESGATE_POLARIDADE", texto)
        self.assertNotIn("AI_FIDELIDADE_LEI_HABILITADA,", texto)  # essa a main le
        self.assertIn("--ref-ceres2", texto)

    def test_commit_que_le_todas_as_variaveis_passa_e_o_git_leu_o_arquivo_do_sha_da_rodada(self):
        chamadas = self.com_yml(YML_COM_RESGATE)
        self.e.conferir_ambiente_no_commit()
        self.assertEqual(chamadas, [["git", "show", "d" * 40 + ":src/main/resources/application.yml"]])

    def test_sem_a_variante_pedida_nem_olha_o_git(self):
        e = ex.Executor(config())
        e.capturar = mock.Mock(side_effect=AssertionError("nao pode consultar o git"))
        e.conferir_ambiente_no_commit()  # a celula base nao tem ambiente

    def test_o_executar_confere_o_commit_antes_de_preparar_qualquer_coisa(self):
        e = ex.Executor(config(com_fidelidade_lei=True))
        e.conferir_pre_requisitos = lambda: {"ollama": "x", "trechosLegislacao": 8304}
        e.resolver_sha = lambda ref: "d" * 40
        e.capturar = lambda comando, cwd=None: YML_MAIN
        e.preparar_gabaritos = mock.Mock(side_effect=AssertionError("tarde demais"))
        with tempfile.TemporaryDirectory() as tmp:
            e.cfg.saida = Path(tmp)
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(RuntimeError) as erro:
                    e.executar()
        self.assertIn("nao le", str(erro.exception))


class CelulaDaVarianteTest(ComChave):
    def setUp(self):
        super().setUp()
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.gabarito = self.dir / "gabarito.json"
        self.gabarito.write_text("{}", encoding="utf-8")
        self.rodada = self.dir / "rodada"
        self.rodada.mkdir()
        self.f = ExecutorFalso(config(com_fidelidade_lei=True))
        self.arquitetura = {"nome": "ceres-2", "rotulo": "Ceres 2.0", "sha": "d" * 40,
                            "configuracaoPublicada": {"AI_READ_TIMEOUT": "120s",
                                                      "AI_FIDELIDADE_LEI_HABILITADA": "false"}}
        self.provas = []
        original = self.f.provar_pensamento_api

        def provar(modelo, api):
            self.provas.append(modelo)
            return original(modelo, api)

        self.f.provar_pensamento_api = provar

    def api_da(self, nome):
        return next(m for m in self.f.cfg.modelos_api if m["modelo"] == nome)

    def rodar(self, nome=VARIANTE):
        # O dublê grava o JSON com o nome do modelo base; o executor so o renomeia pelo da celula.
        with contextlib.redirect_stdout(io.StringIO()):
            return self.f.rodar_celula(self.rodada, self.arquitetura, self.dir, self.dir / "x.jar",
                                       "banco", self.gabarito, nome, self.api_da(nome))

    def test_a_jvm_da_variante_recebe_as_cinco_variaveis_e_o_modelo_real_da_api(self):
        m = self.rodar()
        env = self.f.ambientes[0]
        for nome, valor in AMBIENTE.items():
            self.assertEqual(env[nome], valor, nome)
        # A publicada dizia false: a variante vale depois dela.
        self.assertEqual(env["AI_FIDELIDADE_LEI_HABILITADA"], "true")
        self.assertEqual((env["AI_CHAT_PROVIDER"], env["AI_ANTHROPIC_MODEL"], env["AI_ANTHROPIC_THINKING"],
                          env["ANTHROPIC_API_KEY"], env["AI_READ_TIMEOUT"]),
                         ("anthropic", MODELO, "disabled", CHAVE, "120s"))
        self.assertEqual(self.provas, [MODELO, MODELO])  # as provas vao ao modelo real, no inicio e no fim
        self.assertEqual((m["modelo"], m["modeloApi"], m["variante"], m["ambienteCelula"]),
                         (VARIANTE, MODELO, "fidelidade-lei", AMBIENTE))
        self.assertEqual(m["celula"], "ceres-2--claude-haiku-5-5-fidelidade-lei")
        self.assertTrue(any(a == ex.AVISO_FIDELIDADE_LEI for a in m["avisos"]))
        self.assertTrue(any("latência é de API pela rede" in a for a in m["avisos"]))

    def test_a_celula_base_nunca_ve_as_variaveis_da_variante_nem_as_do_shell(self):
        with mock.patch.dict("os.environ", {"AI_FIDELIDADE_LEI_HABILITADA": "true",
                                            "AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO": "justificativa"}):
            self.rodar(MODELO)
        env = self.f.ambientes[0]
        # So o que o .env.example do commit publica (aqui, desligada); nada do shell nem da variante.
        self.assertEqual({k: v for k, v in env.items() if k.startswith("AI_FIDELIDADE")},
                         {"AI_FIDELIDADE_LEI_HABILITADA": "false"})
        self.rodar(VARIANTE)
        self.assertEqual(self.f.ambientes[1]["AI_FIDELIDADE_LEI_HABILITADA"], "true")

    def test_a_celula_base_segue_com_o_manifesto_de_antes_sem_variante(self):
        m = self.rodar(MODELO)
        self.assertEqual((m["modeloApi"], m["variante"], m["ambienteCelula"]), (MODELO, None, {}))
        self.assertFalse(any(a == ex.AVISO_FIDELIDADE_LEI for a in m["avisos"]))

    def test_retomada_pula_a_variante_igual_e_recusa_a_de_outro_ambiente(self):
        self.rodar()
        antes = len(self.f.comandos)
        self.rodar()
        self.assertEqual(len(self.f.comandos), antes)
        mudada = dict(self.api_da(VARIANTE), ambiente={**AMBIENTE, "AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO": "atual"})
        self.f.cfg.modelos_api = [m for m in self.f.cfg.modelos_api if m["modelo"] != VARIANTE] + [mudada]
        with self.assertRaises(RuntimeError) as erro:
            self.rodar()
        self.assertIn("outro SHA, modelo", str(erro.exception))

    def test_celula_antiga_sem_ambiente_continua_retomavel(self):
        self.rodar(MODELO)
        caminho = self.rodada / f"ceres-2--{MODELO}" / "celula.json"
        manifesto = json.loads(caminho.read_text(encoding="utf-8"))
        for chave in ("ambienteCelula", "modeloApi", "variante"):
            manifesto.pop(chave)  # rodada gravada antes da variante existir
        caminho.write_text(json.dumps(manifesto), encoding="utf-8")
        antes = len(self.f.comandos)
        self.rodar(MODELO)
        self.assertEqual(len(self.f.comandos), antes)

    def test_manifesto_da_rodada_traz_a_variante_com_o_modelo_real_e_o_ambiente(self):
        self.f.opcoes = {"ceres-2": {"anthropic": {"thinking": "disabled"}}}
        for a in self.f.cfg.todas_arquiteturas:
            a["sha"] = "d" * 40
        rodada = self.f.manifesto_rodada(None, {"ollama": "0.1", "trechosLegislacao": 8304})
        nomes = [c["nome"] for c in rodada["celulas"]]
        self.assertEqual(nomes[-2:], [f"ceres-2--{MODELO}", "ceres-2--claude-haiku-5-5-fidelidade-lei"])
        api = next(a for a in rodada["modelosApi"] if a["modelo"] == VARIANTE)
        self.assertEqual((api["modeloApi"], api["variante"], api["ambiente"]), (MODELO, "fidelidade-lei", AMBIENTE))
        base = next(a for a in rodada["modelosApi"] if a["modelo"] == MODELO)
        self.assertEqual((base["variante"], base["ambiente"]), (None, {}))

    def test_a_prova_de_antes_da_rodada_confere_o_modelo_real_uma_vez_so(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.f.provar_celulas_api()
        self.assertEqual(self.provas, [MODELO])


# --------------------------------------------------------------------------- relatorio


def copiar_variante(pasta: Path, *, descartar: bool = True) -> None:
    """Acrescenta a rodada minima (que ja tem a celula base do Haiku) a variante com a fidelidade, com
    L01 descartada pela fidelidade e A01 recusada (certa) so por causa do descarte."""
    origem = pasta / f"ceres-2--{MODELO}"
    nome = "ceres-2--claude-haiku-5-5-fidelidade-lei"
    destino = pasta / nome
    shutil.copytree(origem, destino)
    (destino / f"ceres-2--{MODELO}.json").rename(destino / f"{nome}.json")
    manifesto = json.loads((destino / "celula.json").read_text(encoding="utf-8"))
    manifesto.update({"celula": nome, "modelo": VARIANTE, "modeloApi": MODELO, "variante": "fidelidade-lei",
                      "ambienteCelula": AMBIENTE, "avisos": [ex.AVISO_FIDELIDADE_LEI]})
    (destino / "celula.json").write_text(json.dumps(manifesto), encoding="utf-8")
    if descartar:
        avaliacao = json.loads((destino / f"{nome}.json").read_text(encoding="utf-8"))
        for rodada in avaliacao["rodadas"]:
            for r in rodada["resultados"]:
                if r["id"] == "L01":
                    r["execucao"].update(desfecho="FIDELIDADE_REPROVADA")
                    r["citacaoValida"] = False
                    r["execucao"]["latenciaMs"] = 4000
                if r["id"] == "A01":
                    r["execucao"].update(desfecho="FIDELIDADE_REPROVADA")
                    r["recusaCorreta"] = True
        (destino / f"{nome}.json").write_text(json.dumps(avaliacao), encoding="utf-8")
    rodada = json.loads((pasta / "rodada.json").read_text(encoding="utf-8"))
    rodada["celulas"].append({"nome": nome, "arquitetura": "ceres-2", "rotuloArquitetura": "Ceres 2.0",
                              "modelo": VARIANTE})
    rodada["modelos"].append(VARIANTE)
    rodada["modelosApi"].append({"modelo": VARIANTE, "provedor": "anthropic", "raciocinio": "disabled",
                                 "arquiteturas": ["ceres-2"], "modeloApi": MODELO,
                                 "variante": "fidelidade-lei", "ambiente": AMBIENTE, "opcoes": {}})
    (pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")


class RelatorioDaVarianteTest(unittest.TestCase):
    def setUp(self):
        self.pasta = Path(tempfile.mkdtemp()) / "rodada"
        shutil.copytree(FIXTURE, self.pasta)
        self.addCleanup(shutil.rmtree, self.pasta.parent, True)
        copiar_celula(self.pasta)
        copiar_variante(self.pasta)
        self.texto = rel.gerar(self.pasta)

    def secao(self) -> str:
        inicio = self.texto.index("## Célula com a fidelidade da rota de lei (#269)")
        return self.texto[inicio:self.texto.index("\n## ", inicio + 10)]

    def test_a_variante_e_uma_linha_propria_marcada_ao_lado_da_base(self):
        base = f"Ceres 2.0 · `{MODELO}` (API)"
        variante = f"Ceres 2.0 · `{VARIANTE}` (API, fidelidade da lei)"
        self.assertIn(base, self.texto)
        self.assertIn(variante, self.texto)
        linhas = [l for l in self.texto.splitlines() if l.startswith("| Ceres 2.0 · `claude-haiku")
                  and "Sem falha técnica" not in l]
        self.assertTrue(any(l.startswith(f"| {variante} |") for l in linhas))

    def test_a_secao_diz_em_uma_frase_o_que_o_descarte_faz_com_as_metricas(self):
        secao = self.secao()
        self.assertIn(rel.FRASE_FIDELIDADE, secao)
        self.assertIn("a citação e o número das perguntas de lei **caem**", secao)
        self.assertIn("sobe por descarte, não por acerto", secao)
        self.assertIn("`FIDELIDADE_REPROVADA`", secao)

    def test_a_secao_traz_a_irma_a_variante_e_a_diferenca_com_o_descarte_contado(self):
        secao = self.secao()
        self.assertIn(f"| Ceres 2.0 · `{MODELO}` (API) |", secao)
        self.assertIn(f"| Ceres 2.0 · `{VARIANTE}` (API, fidelidade da lei) |", secao)
        self.assertIn("**Com − sem a fidelidade**", secao)
        # L01 e A01 nas 2 rodadas, nas 3 perguntas da capacidade comum (D01, L01, A01 x 2): 4 de 6.
        self.assertIn("| 4 de 6 |", secao)
        self.assertIn("| 0 de 6 |", secao)
        # A01 era recusa certa so por causa do descarte: a recusa sem os descartes zera.
        self.assertIn("serve a medição da #269, não este benchmark", secao)

    def test_citacao_cai_na_variante_pela_regua_e_a_recusa_limpa_nao_conta_o_descarte(self):
        celulas = {c.modelo: c for c in rel.carregar_celulas(self.pasta)}
        ids = ["D01", "L01", "A01"]
        canonico = {p["id"]: p for p in json.loads(
            (self.pasta / "gabarito-canonico.json").read_text(encoding="utf-8"))["perguntas"]}
        sem, com = celulas[MODELO], celulas[VARIANTE]
        self.assertEqual(rel._taxas_de_recusa(com, ids, canonico), (2, 2, 0, 4))
        self.assertEqual(rel._taxas_de_recusa(sem, ids, canonico)[3], 0)
        self.assertLess(rel.faixa(com, "citacao", ids, canonico, {}, rel.RECORTE_COMUM).media,
                        rel.faixa(sem, "citacao", ids, canonico, {}, rel.RECORTE_COMUM).media)

    def test_descarte_da_parte_de_lei_do_misto_tambem_conta(self):
        self.assertTrue(rel.descartou_pela_fidelidade({"execucao": {"desfecho": "RESPONDIDA",
                                                                    "desfechoLegislacao": "FIDELIDADE_REPROVADA"}}))
        self.assertFalse(rel.descartou_pela_fidelidade({"execucao": {"desfecho": "CITACAO_INVALIDA"}}))
        self.assertFalse(rel.descartou_pela_fidelidade({}))

    def test_fora_da_conclusao_da_viabilidade_e_dos_graficos(self):
        conclusao = self.texto[self.texto.index("## Conclusão para a banca"):]
        conclusao = conclusao[:conclusao.index("## Gráficos")]
        self.assertIn("fica fora desta conclusão e de toda comparação", conclusao)
        self.assertNotIn(f"`{VARIANTE}` é viável", conclusao)
        self.assertNotIn("(API, fidelidade da lei)**:", conclusao)  # sem os numeros do paragrafo por API
        # Nenhum grafico a tem; a base do Haiku continua la.
        for svg in (self.pasta / "graficos").glob("*.svg"):
            self.assertNotIn("fidelidade", svg.read_text(encoding="utf-8"), svg.name)
        self.assertIn(f"{MODELO} (API)", (self.pasta / "graficos" / "acerto-por-tema.svg").read_text(encoding="utf-8"))

    def test_a_celula_base_do_haiku_continua_na_comparacao_com_o_qwen_de_referencia(self):
        conclusao = self.texto[self.texto.index("## Conclusão para a banca"):]
        self.assertIn(f"**Modelo por API, Ceres 2.0 · `{MODELO}` (API)**", conclusao)
        self.assertIn("a diferença para o `m-ref` local", conclusao)

    def test_condicoes_dizem_que_a_variante_tem_a_fidelidade_ligada_e_com_quais_chaves(self):
        linha = next(l for l in self.texto.splitlines() if l.startswith(f"| `{VARIANTE}` (API) |"))
        self.assertIn("variante com a fidelidade da rota de lei ligada", linha)
        self.assertIn("AI_FIDELIDADE_LEI_JUIZ_CONFIGURACAO=justificativa", linha)
        self.assertIn("AI_FIDELIDADE_LEI_JUIZ_RESGATE_POLARIDADE=true", linha)
        base = next(l for l in self.texto.splitlines() if l.startswith(f"| `{MODELO}` (API) |"))
        self.assertNotIn("fidelidade", base)

    def test_titulo_nao_conta_a_variante_como_outro_modelo(self):
        sem_variante = Path(tempfile.mkdtemp()) / "rodada"
        shutil.copytree(FIXTURE, sem_variante)
        self.addCleanup(shutil.rmtree, sem_variante.parent, True)
        copiar_celula(sem_variante)
        titulo = lambda t: t.splitlines()[0]
        self.assertEqual(titulo(rel.gerar(sem_variante)), titulo(self.texto))

    def test_aviso_da_variante_entra_nos_avisos_das_celulas(self):
        self.assertIn("fidelidade da rota de lei LIGADA (#269)", self.texto)

    def test_sem_a_variante_o_relatorio_nao_tem_secao_nem_fala_em_fidelidade(self):
        sem = Path(tempfile.mkdtemp()) / "rodada"
        shutil.copytree(FIXTURE, sem)
        self.addCleanup(shutil.rmtree, sem.parent, True)
        copiar_celula(sem)
        texto = rel.gerar(sem)
        self.assertNotIn("fidelidade", texto.lower())

    def test_variante_sem_a_irma_nao_inventa_comparacao(self):
        pasta = Path(tempfile.mkdtemp()) / "rodada"
        shutil.copytree(FIXTURE, pasta)
        self.addCleanup(shutil.rmtree, pasta.parent, True)
        copiar_celula(pasta)
        copiar_variante(pasta)
        rodada = json.loads((pasta / "rodada.json").read_text(encoding="utf-8"))
        rodada["celulas"] = [c for c in rodada["celulas"] if c["modelo"] != MODELO]
        (pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        texto = rel.gerar(pasta)
        secao = texto[texto.index("## Célula com a fidelidade"):]
        self.assertIn("não há com o que comparar", secao)
        self.assertNotIn("**Com − sem a fidelidade**", secao)

    def test_variante_invalida_fica_sem_numeros(self):
        manifesto = self.pasta / "ceres-2--claude-haiku-5-5-fidelidade-lei" / "celula.json"
        dados = json.loads(manifesto.read_text(encoding="utf-8"))
        dados["pensamento"] = {"raciocinou": True, "resumo": "ATIVO"}
        manifesto.write_text(json.dumps(dados), encoding="utf-8")
        secao = rel.gerar(self.pasta)
        self.assertIn("a célula não rodou até o fim ou é inválida", secao[secao.index("## Célula com a fidelidade"):])


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
