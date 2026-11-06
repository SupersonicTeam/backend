"""Gerador do relatorio comparativo, sobre JSONs de exemplo nos dois esquemas (issue #348).

``fixtures/rodada-minima``: 5 perguntas, 2 arquiteturas x 2 modelos, 2 rodadas. Os JSONs da
Ceres 1 estao no esquema do ``2463c27`` (sem ``semBecoSemSaida`` nem ``camadaClassificacao``).
T07 e P01 foram adaptadas para a Ceres 1; P01 nao existe no gabarito antigo.
"""

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent))

from ceres_benchmark import relatorio as rel  # noqa: E402
from ceres_benchmark import gabarito as gab  # noqa: E402

FIXTURE = AQUI / "fixtures" / "rodada-minima"


class Base(unittest.TestCase):
    def setUp(self):
        self.pasta = Path(tempfile.mkdtemp()) / "rodada"
        shutil.copytree(FIXTURE, self.pasta)
        self.celulas = {c.nome: c for c in rel.carregar_celulas(self.pasta)}
        canonico = json.loads((FIXTURE / "gabarito-canonico.json").read_text(encoding="utf-8"))
        antigo = json.loads((FIXTURE / "gabarito-antigo.json").read_text(encoding="utf-8"))
        self.canonico = {p["id"]: p for p in canonico["perguntas"]}
        self.adapt = json.loads((FIXTURE / "adaptacao-ceres-1.json").read_text(encoding="utf-8"))
        self.comum = gab.recorte_comum(canonico, antigo, self.adapt)
        self.inteiro = list(self.canonico)

    def tearDown(self):
        shutil.rmtree(self.pasta.parent, ignore_errors=True)

    def faixa(self, celula, criterio, recorte):
        ids = self.comum if recorte == rel.RECORTE_COMUM else self.inteiro
        return rel.faixa(self.celulas[celula], criterio, ids, self.canonico, self.adapt, recorte)


class RecortesTest(Base):
    def test_recorte_comum_so_tem_as_perguntas_dos_dois_gabaritos_sem_adaptacao(self):
        self.assertEqual(self.comum, ["D01", "L01", "A01"])

    def test_media_e_faixa_das_rodadas_no_recorte_comum(self):
        numero = self.faixa("ceres-1--m-ref", "numero", rel.RECORTE_COMUM)
        self.assertEqual((numero.media, numero.minimo, numero.maximo, numero.total), (50.0, 0.0, 100.0, 1))
        rota = self.faixa("ceres-1--m-ref", "rota", rel.RECORTE_COMUM)
        self.assertEqual((rota.media, rota.total), (100.0, 2))  # A01 nao tem rota esperada
        self.assertEqual(self.faixa("ceres-1--m-ref", "numero", rel.RECORTE_COMUM).penalizadas, 0)

    def test_gabarito_inteiro_penaliza_a_ceres_1_por_capacidade(self):
        numero = self.faixa("ceres-1--m-ref", "numero", rel.RECORTE_INTEIRO)
        # D01 (1 rodada certa de 2) + T07 e P01 sem a funcao: erro por capacidade nas duas.
        self.assertEqual(numero.total, 3)
        self.assertAlmostEqual(numero.media, (100 / 3 + 0) / 2)
        self.assertEqual(numero.penalizadas, 2)
        funcao = self.faixa("ceres-1--m-ref", "funcao", rel.RECORTE_INTEIRO)
        self.assertAlmostEqual(funcao.media, 100 / 3)
        self.assertEqual(funcao.penalizadas, 2)
        # Na Ceres 2.0 nada e penalizado: as funcoes existem.
        self.assertEqual(self.faixa("ceres-2--m-ref", "numero", rel.RECORTE_INTEIRO).penalizadas, 0)
        self.assertEqual(self.faixa("ceres-2--m-ref", "numero", rel.RECORTE_INTEIRO).media, 100.0)

    def test_criterio_que_a_regua_antiga_nao_tinha_e_nd(self):
        self.assertEqual(self.faixa("ceres-1--m-ref", "beco", rel.RECORTE_COMUM).formatada(), "n/d")
        self.assertEqual(self.faixa("ceres-2--m-ref", "beco", rel.RECORTE_COMUM).formatada(), "—")

    def test_vazamento_invertido_e_falha_tecnica(self):
        self.assertEqual(self.faixa("ceres-1--m-small", "vazamento", rel.RECORTE_COMUM).media, 0.0)
        self.assertEqual(self.faixa("ceres-1--m-ref", "vazamento", rel.RECORTE_COMUM).media, 100.0)
        self.assertEqual(self.faixa("ceres-1--m-ref", "falha", rel.RECORTE_COMUM).media, 100.0)

    def test_latencia_nearest_rank_por_rota(self):
        lat = rel.latencias(self.celulas["ceres-1--m-ref"], self.comum, self.canonico)
        self.assertEqual(lat["TODAS"], (2000, 3000, 6))
        self.assertEqual(lat["SEM_ROTA"], (2000, 2000, 2))
        self.assertEqual(rel.percentil([], 50), 0)

    def test_clareza_ponderada_pelas_respostas(self):
        palavras, siglas, medidas = rel.clareza(self.celulas["ceres-1--m-ref"])
        self.assertEqual(medidas, 6)
        self.assertAlmostEqual(palavras, 12.0)
        self.assertAlmostEqual(siglas, 100 / 6)

    def test_camada_so_no_esquema_novo(self):
        self.assertEqual(rel.camadas(self.celulas["ceres-1--m-ref"]), {})
        self.assertEqual(rel.camadas(self.celulas["ceres-2--m-ref"]), {"REGRA": 10})

    def test_instaveis(self):
        self.assertEqual(rel.instaveis(self.celulas["ceres-1--m-ref"], self.comum), ["D01"])
        self.assertEqual(rel.instaveis(self.celulas["ceres-2--m-small"], self.comum), ["L01"])

    def test_json_anterior_a_264_com_resultados_na_raiz(self):
        antigo = json.loads((AQUI / "fixtures" / "avaliacao-pre-264.json").read_text(encoding="utf-8"))
        self.assertEqual(len(rel.rodadas_do_json(antigo)), 1)
        with self.assertRaises(ValueError):
            rel.rodadas_do_json({})


class FalhasTest(Base):
    def test_exemplos_de_falha_tipicos(self):
        pequeno = rel.exemplos_de_falha(self.celulas["ceres-1--m-small"], self.canonico)
        self.assertEqual([e["id"] for e in pequeno["numero_inventado"]], ["D01"])
        self.assertEqual([e["id"] for e in pequeno["regra_inventada"]], ["L01"])
        self.assertEqual([e["id"] for e in pequeno["instrucao_vazada"]], ["A01"])
        referencia = rel.exemplos_de_falha(self.celulas["ceres-1--m-ref"], self.canonico)
        self.assertEqual([e["id"] for e in referencia["negativa_falsa"]], ["D01"])
        self.assertEqual(referencia["negativa_falsa"][0]["rodada"], 2)
        self.assertEqual(referencia["numero_errado"], [])

    def test_falha_tecnica_nao_e_exemplo(self):
        resultado = {"execucao": {"desfecho": "TEMPO_ESGOTADO"}, "numeroCorreto": False}
        self.assertEqual(rel.classificar_falhas(resultado, {}), [])


class ValoresDivergentesTest(Base):
    def test_pergunta_com_valor_esperado_diferente_sai_do_recorte_comum(self):
        arquivo = self.pasta / "ceres-2--m-ref" / "ceres-2--m-ref.json"
        dados = json.loads(arquivo.read_text(encoding="utf-8"))
        for rodada in dados["rodadas"]:
            rodada["resultados"][0]["valoresEsperados"] = ["R$ 1,00"]
        arquivo.write_text(json.dumps(dados), encoding="utf-8")
        celulas = rel.carregar_celulas(self.pasta)
        self.assertEqual(set(rel.valores_divergentes(celulas)), {"D01", "T07", "P01"})
        texto = rel.gerar(self.pasta)
        self.assertIn("Fora da capacidade comum por valor esperado diferente", texto)
        self.assertIn("| Capacidade comum | 2 perguntas", texto)


class ConclusaoERelatorioTest(Base):
    def test_viabilidade_pelos_criterios(self):
        arq1 = [c for c in self.celulas.values() if c.arquitetura == "ceres-1"]
        arq2 = [c for c in self.celulas.values() if c.arquitetura == "ceres-2"]
        v1 = {v.modelo: v for v in rel.viabilidade(arq1, "m-ref", self.comum, self.canonico, self.adapt)}
        v2 = {v.modelo: v for v in rel.viabilidade(arq2, "m-ref", self.comum, self.canonico, self.adapt)}
        self.assertFalse(v1["m-small"].viavel)
        self.assertTrue(any("vazou" in m for m in v1["m-small"].motivos))
        # Citacao 50-100% contra 100% com n=1: o piso (100 - 1 pergunta) aceita.
        self.assertTrue(v2["m-small"].viavel, v2["m-small"].motivos)

    def test_relatorio_completo(self):
        texto = rel.gerar(self.pasta)
        self.assertNotIn("DADOS FICTÍCIOS", texto)
        self.assertIn("## (a) Capacidade comum", texto)
        self.assertIn("## (b) Conjunto inteiro", texto)
        self.assertIn("(pen. 2)", texto)
        self.assertIn("Com todas as letras: na Ceres 2.0, um modelo menor passa a ser viável", texto)
        self.assertIn("`m-small` (0,5 GB contra 3,0 GB do `m-ref`)", texto)
        self.assertIn("| Ceres 1 · `m-ref` | 100,0% n=2 |", texto)
        self.assertIn("Instrução ou texto proibido vazado", texto)
        self.assertTrue((self.pasta / "relatorio.md").exists())
        for nome in ("rota", "numero", "citacao", "recusa", "vazamento", "falha", "latencia-p50", "memoria"):
            svg = (self.pasta / "graficos" / f"{nome}.svg").read_text(encoding="utf-8")
            self.assertIn("<svg", svg)
            # Sem cor como unica distincao: hachura e o nome do padrao na legenda.
            self.assertIn("url(#hachura-diagonal)", svg)
            self.assertIn("Ceres 1 (hachurado)", svg)
            self.assertNotIn("DADOS FICTÍCIOS", svg)

    def test_celula_ficticia_marca_relatorio_e_graficos(self):
        manifesto = self.pasta / "ceres-1--m-ref" / "celula.json"
        dados = json.loads(manifesto.read_text(encoding="utf-8"))
        dados["ficticio"] = True
        manifesto.write_text(json.dumps(dados), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertTrue(texto.startswith("# Benchmark da Ceres — 2 arquiteturas × 2 modelos (DADOS FICTÍCIOS)"))
        self.assertIn("Nenhum número aqui foi medido", texto)
        self.assertIn("DADOS FICTÍCIOS", (self.pasta / "graficos" / "numero.svg").read_text(encoding="utf-8"))

    def test_celula_que_falhou_aparece_sem_numeros(self):
        manifesto = self.pasta / "ceres-1--m-small" / "celula.json"
        dados = json.loads(manifesto.read_text(encoding="utf-8"))
        dados["codigoSaida"] = 1
        manifesto.write_text(json.dumps(dados), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn("`m-small`: não viável — a célula não rodou até o fim", texto)


class ExemploFicticioTest(unittest.TestCase):
    def test_relatorio_de_exemplo_versionado_e_marcado(self):
        texto = (AQUI.parent / "exemplo-ficticio" / "relatorio.md").read_text(encoding="utf-8")
        self.assertIn("DADOS FICTÍCIOS", texto.splitlines()[0])
        self.assertIn("Nenhum número aqui foi medido", texto)


if __name__ == "__main__":
    unittest.main()


class RevisaoTest(Base):
    def test_sem_a_referencia_nenhum_modelo_e_declarado_viavel(self):
        for nome in ("ceres-1--m-ref", "ceres-2--m-ref"):
            manifesto = self.pasta / nome / "celula.json"
            dados = json.loads(manifesto.read_text(encoding="utf-8"))
            dados["codigoSaida"] = 1
            manifesto.write_text(json.dumps(dados), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertNotIn("**viável**", texto)
        self.assertNotIn("Com todas as letras", texto)
        self.assertIn("não há com o que comparar", texto)

    def test_regressao_quando_so_a_arquitetura_antiga_tinha_modelo_viavel(self):
        celulas = [rel.Celula("a", "ceres-1", "Ceres 1", "m-small", {}, {}, [[]]),
                   rel.Celula("r1", "ceres-1", "Ceres 1", "m-ref", {}, {}, [[]]),
                   rel.Celula("r2", "ceres-2", "Ceres 2.0", "m-ref", {}, {}, [[]]),
                   rel.Celula("b", "ceres-2", "Ceres 2.0", "m-small", {}, {}, [[]])]
        original = rel.viabilidade
        rel.viabilidade = lambda das, *a: [rel.Viabilidade(
            "m-small", das[0].arquitetura == "ceres-1", [] if das[0].arquitetura == "ceres-1" else ["x"])]
        try:
            texto = rel.conclusao(celulas, [("ceres-1", "Ceres 1"), ("ceres-2", "Ceres 2.0")], "m-ref",
                                  [], {}, {})
        finally:
            rel.viabilidade = original
        self.assertIn("**Regressão:** `m-small` era viável na Ceres 1 e não é na Ceres 2.0", texto)

    def test_celula_invalida_nao_vira_regressao_nem_ganho(self):
        invalida = {"pensamento": {"raciocinou": True}}
        for antiga, nova, esperado, proibido in (
                ({}, invalida, "célula não rodou ou é inválida", "**Regressão:**"),
                (invalida, {}, "a célula da Ceres 1 não rodou ou é inválida", "Com todas as letras")):
            celulas = [rel.Celula("a", "ceres-1", "Ceres 1", "m-small", antiga, {}, [[]]),
                       rel.Celula("r1", "ceres-1", "Ceres 1", "m-ref", {}, {}, [[]]),
                       rel.Celula("r2", "ceres-2", "Ceres 2.0", "m-ref", {}, {}, [[]]),
                       rel.Celula("b", "ceres-2", "Ceres 2.0", "m-small", nova, {}, [[]])]
            original = rel.viabilidade
            def _pequena(das):
                return next(c for c in das if c.modelo == "m-small")
            rel.viabilidade = lambda das, *a: [rel.Viabilidade(
                "m-small", not _pequena(das).invalida, [] if not _pequena(das).invalida else ["inválida"])]
            try:
                texto = rel.conclusao(celulas, [("ceres-1", "Ceres 1"), ("ceres-2", "Ceres 2.0")],
                                      "m-ref", [], {}, {})
            finally:
                rel.viabilidade = original
            self.assertIn(esperado, texto)
            self.assertNotIn(proibido, texto)

    def test_referencia_invalida_numa_coluna_nao_vira_ganho_nem_regressao(self):
        invalida = {"pensamento": {"raciocinou": True}}
        for qual in ("ceres-1", "ceres-2"):
            celulas = [rel.Celula("a", "ceres-1", "Ceres 1", "m-small", {}, {}, [[]]),
                       rel.Celula("b", "ceres-2", "Ceres 2.0", "m-small", {}, {}, [[]]),
                       rel.Celula("r1", "ceres-1", "Ceres 1", "m-ref",
                                  invalida if qual == "ceres-1" else {}, {}, [[]]),
                       rel.Celula("r2", "ceres-2", "Ceres 2.0", "m-ref",
                                  invalida if qual == "ceres-2" else {}, {}, [[]])]
            original = rel.viabilidade
            rel.viabilidade = lambda das, *a: [rel.Viabilidade(
                "m-small", das[0].arquitetura != qual, [] if das[0].arquitetura != qual else ["sem ref"])]
            try:
                texto = rel.conclusao(celulas, [("ceres-1", "Ceres 1"), ("ceres-2", "Ceres 2.0")],
                                      "m-ref", [], {}, {})
            finally:
                rel.viabilidade = original
            self.assertIn("Comparação entre as arquiteturas: não medida", texto)
            self.assertNotIn("**Regressão:**", texto)
            self.assertNotIn("Com todas as letras", texto)

    def test_sem_nenhuma_prova_do_pensamento_a_celula_e_invalida(self):
        sem = rel.Celula("a", "ceres-2", "Ceres 2.0", "m", {"pensamento": {"semProva": True}}, {}, [[]])
        self.assertTrue(sem.invalida)
        self.assertFalse(rel.Celula("b", "ceres-2", "Ceres 2.0", "m", {"pensamento": {}}, {}, [[]]).invalida)

    def test_rotulo_mais_longo_do_mapa_por_tema_cabe_inteiro(self):
        from ceres_benchmark import graficos
        tema = "Externas: tributação de insumos agrícolas e pecuários"
        svg = graficos.mapa_tema_celula("t", "s", [tema, "curto"], ["c"], [[(50.0, 1)], [(50.0, 1)]])
        self.assertIn(">" + tema + "<", svg)

    def test_recusa_limpa_nao_conta_recusa_por_corte(self):
        cortada = {"id": "F1", "recusaCorreta": True,
                   "execucao": {"desfecho": "RESPOSTA_CORTADA", "rota": "FORA_DE_ESCOPO"}}
        limpa = {"id": "F1", "recusaCorreta": True, "execucao": {"desfecho": "FORA_DE_ESCOPO"}}
        self.assertEqual(rel.valor_criterio(cortada, "recusa", {}, [], rel.RECORTE_COMUM)[0], True)
        self.assertEqual(rel.valor_criterio(cortada, "recusa_limpa", {}, [], rel.RECORTE_COMUM)[0], False)
        self.assertEqual(rel.valor_criterio(limpa, "recusa_limpa", {}, [], rel.RECORTE_COMUM)[0], True)

    def test_vazamento_de_nome_do_vizinho_nao_e_instrucao(self):
        pergunta = {"textosProibidos": ["Sitio Vizinho", "Termine cada afirmacao com a marca"]}
        vizinho = {"execucao": {"desfecho": "RESPONDIDA", "texto": "O Sitio Vizinho comprou soja."},
                   "vazamento": True}
        prompt = {"execucao": {"desfecho": "RESPONDIDA", "texto": "termine cada afirmação com a marca"},
                  "vazamento": True}
        self.assertEqual(rel.classificar_falhas(vizinho, pergunta), ["vazamento_vizinho"])
        self.assertEqual(rel.classificar_falhas(prompt, pergunta), ["instrucao_vazada"])

    def test_remocao_por_seed_nao_penaliza_no_gabarito_inteiro(self):
        adapt = dict(self.adapt)
        adapt["D01"] = {"removido": {"camposValor": ["valorTotal"]}, "motivos": ["seed"], "penaliza": []}
        celula = self.celulas["ceres-1--m-ref"]
        for rodada in celula.rodadas:
            rodada[0]["numeroCorreto"] = None
        numero = rel.faixa(celula, "numero", self.inteiro, self.canonico, adapt, rel.RECORTE_INTEIRO)
        self.assertEqual((numero.total, numero.penalizadas), (2, 2))

    def test_camada_de_palavras_chave_ganha_coluna(self):
        arquivo = self.pasta / "ceres-2--m-ref" / "ceres-2--m-ref.json"
        dados = json.loads(arquivo.read_text(encoding="utf-8"))
        dados["rodadas"][0]["resultados"][0]["execucao"]["camadaClassificacao"] = "PALAVRAS_CHAVE"
        arquivo.write_text(json.dumps(dados), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn("| EMBEDDING | LLM | PALAVRAS_CHAVE |", texto)
        self.assertIn("| Ceres 2.0 · `m-ref` | 0% | 0% | 90% | 0% | 0% | 10% |", texto)


class TemasTest(Base):
    def test_acerto_por_tema_no_recorte_e_no_conjunto(self):
        celula = self.celulas["ceres-1--m-ref"]
        comum = rel.por_tema(celula, self.comum, self.canonico, self.adapt, rel.RECORTE_COMUM)
        self.assertEqual(set(comum), {"compras", "lei", "seguranca"})
        self.assertEqual((comum["compras"].media, comum["compras"].total), (50.0, 1))
        inteiro = rel.por_tema(celula, self.inteiro, self.canonico, self.adapt, rel.RECORTE_INTEIRO)
        # P01 (compras) e T07 (guias) penalizadas por capacidade: erradas nas duas rodadas.
        self.assertEqual((inteiro["compras"].media, inteiro["compras"].total), (25.0, 2))
        self.assertEqual(inteiro["guias"].media, 0.0)

    def test_secao_por_tema_e_grafico_principal(self):
        texto = rel.gerar(self.pasta)
        self.assertIn("| Compras e insumos | 2 | 1 | 1 | 0 |", texto)
        self.assertIn("| **Total** | 5 | 3 | 2 | 0 |", texto)
        self.assertIn("### Acerto por tema × célula — capacidade comum", texto)
        svg = (self.pasta / "graficos" / "acerto-por-tema.svg").read_text(encoding="utf-8")
        self.assertIn("Acerto por tema × célula", svg)
        self.assertIn(">50%<", svg)
        self.assertIn("Compras e insumos", svg)
        self.assertTrue(texto.index("acerto-por-tema.svg") < texto.index("rota.svg"))

    def test_rodada_de_teste_marcada_e_latencia_local_separada_da_vps(self):
        rodada = json.loads((self.pasta / "rodada.json").read_text(encoding="utf-8"))
        rodada.update({"repeticoes": 1, "sessao7": {"issue": "#270", "rotulo": "Sessão 7", "contem": False},
                       "hardware": ["Ryzen 5, 32 GB RAM, GPU NVIDIA GeForce RTX 3050, 4096 MiB"]})
        (self.pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        manifesto = self.pasta / "ceres-2--m-ref" / "celula.json"
        dados = json.loads(manifesto.read_text(encoding="utf-8"))
        dados["avisos"] = ["bge-m3 saiu da memória 3 vez(es) no meio da célula"]
        dados["memoria"]["ondeRodou"] = {"m-ref": ["GPU"], "bge-m3": ["CPU"]}
        manifesto.write_text(json.dumps(dados), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn("RODADA DE TESTE — NÃO VALE PARA A BANCA", texto)
        self.assertIn("**antes** dela", texto)
        self.assertIn("nesta máquina (com GPU)", texto)
        self.assertIn("**Não é a latência de produção**", texto)
        self.assertIn("## Latência na VPS (produção)", texto)
        self.assertIn("bge-m3 saiu da memória 3 vez(es)", texto)
        self.assertIn("m-ref: GPU; bge-m3: CPU", texto)
        self.assertIn("RODADA DE TESTE", (self.pasta / "graficos" / "acerto-por-tema.svg").read_text(encoding="utf-8"))

    def test_rodada_antiga_sem_capacidade_usa_o_gabarito_antigo(self):
        canonico = json.loads((self.pasta / "gabarito-canonico.json").read_text(encoding="utf-8"))
        for p in canonico["perguntas"]:
            p.pop("capacidade")
        (self.pasta / "gabarito-canonico.json").write_text(json.dumps(canonico), encoding="utf-8")
        rodada = json.loads((self.pasta / "rodada.json").read_text(encoding="utf-8"))
        rodada["gabaritoAntigo"] = "gabarito-antigo.json"
        (self.pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        self.assertIn("| Capacidade comum | 3 perguntas", rel.gerar(self.pasta))


class DivergentesConhecidosTest(Base):
    def test_divergente_conhecido_sai_mesmo_sem_a_ceres_1_na_pasta(self):
        rodada = json.loads((self.pasta / "rodada.json").read_text(encoding="utf-8"))
        rodada["divergentesConhecidos"] = ["D01"]
        (self.pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn("| Capacidade comum | 2 perguntas", texto)
        self.assertIn("`divergentesConhecidos` da matriz): D01", texto)


class Sessao7Test(Base):
    def test_cortadas_ensaio_e_comparacao_com_queda(self):
        celula = self.celulas["ceres-1--m-ref"]
        antes, total = rel.possivelmente_cortadas(celula)
        # A D01 da rodada 1 termina em ponto; cortada no meio, conta.
        celula.rodadas[0][0]["execucao"]["texto"] = "O total das suas compras de fertilizante foi de"
        self.assertEqual(rel.possivelmente_cortadas(celula), (antes + 1, total))
        anterior = Path(tempfile.mkdtemp()) / "ensaio"
        shutil.copytree(self.pasta, anterior)
        arquivo = self.pasta / "ceres-2--m-ref" / "ceres-2--m-ref.json"
        dados = json.loads(arquivo.read_text(encoding="utf-8"))
        for rodada in dados["rodadas"]:
            rodada["resultados"][0]["numeroCorreto"] = False
        arquivo.write_text(json.dumps(dados), encoding="utf-8")
        rodada = json.loads((self.pasta / "rodada.json").read_text(encoding="utf-8"))
        rodada["sessao7"] = {"issue": "#270", "rotulo": "Sessão 7", "contem": True, "confirmado": True}
        (self.pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        texto = rel.gerar(self.pasta, comparar_com=anterior)
        self.assertNotIn("ENSAIO", texto)
        self.assertIn("a coluna inclui as mudanças dela", texto)
        # Com PRs da sessao, mas sem o ultimo configurado: ensaio parcial.
        rodada["sessao7"] = {"issue": "#270", "rotulo": "Sessão 7", "contem": True, "confirmado": False,
                             "commits": ["003624f perf(ceres): prompt (#270) (#351)"]}
        (self.pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        parcial = rel.gerar(self.pasta)
        self.assertIn("O SHA medido já tem 1 PR(s) dela", parcial)
        self.assertIn("**parcial**", parcial)
        self.assertIn("## Comparação com a rodada `ensaio`", texto)
        self.assertIn("| Ceres 2.0 · `m-ref` | Exatidão numérica | 100,0% n=1 | 0,0% n=1 | **QUEDA** |", texto)
        self.assertIn("**1 queda(s) fora da faixa.**", texto)
        self.assertIn("## Respostas possivelmente cortadas", texto)
        shutil.rmtree(anterior.parent, ignore_errors=True)

    def test_ceres_2_antes_da_sessao_7_e_ensaio(self):
        rodada = json.loads((self.pasta / "rodada.json").read_text(encoding="utf-8"))
        rodada["sessao7"] = {"issue": "#270", "rotulo": "Sessão 7", "contem": False}
        (self.pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn("**ENSAIO — a coluna Ceres 2.0 não é a de depois da Sessão 7 (#270) inteira.**", texto)
        self.assertIn("O SHA medido não tem nenhum PR dela", texto)


class IneditasEParecidasTest(Base):
    def test_recorte_de_ineditas_e_acerto_sem_as_parecidas(self):
        canonico = json.loads((self.pasta / "gabarito-canonico.json").read_text(encoding="utf-8"))
        for p in canonico["perguntas"]:
            p["origem"] = "inedita" if p["id"] in ("P01", "L01") else "gabarito"
        (self.pasta / "gabarito-canonico.json").write_text(json.dumps(canonico), encoding="utf-8")
        rodada = json.loads((self.pasta / "rodada.json").read_text(encoding="utf-8"))
        rodada["parecidasComTreino"] = ["D01"]
        (self.pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn("## (c) Inéditas — generalização", texto)
        self.assertIn("Com 50 pontos percentuais por pergunta (n=2)", texto)
        self.assertIn("1 delas são `so_ceres_2` (P01)", texto)
        self.assertIn("## Com e sem as perguntas parecidas com o treino", texto)
        # Ceres 1 m-ref: D01 erra na rodada 2; sem ela, L01 e A01 certas nas duas.
        self.assertIn("| Ceres 1 · `m-ref` | 83,3% (66,7–100,0) n=3 | 100,0% n=2 | +16,7 p.p. |", texto)
        celula = self.celulas["ceres-1--m-ref"]
        ineditas = rel.faixa(celula, "numero", ["P01"], self.canonico, self.adapt, rel.RECORTE_INEDITAS)
        self.assertEqual((ineditas.media, ineditas.penalizadas), (0.0, 1))


class ExternasNoRelatorioTest(Base):
    def _marcar(self):
        canonico = json.loads((self.pasta / "gabarito-canonico.json").read_text(encoding="utf-8"))
        for p in canonico["perguntas"]:
            p["origem"] = {"L01": "inedita_externa_ia", "P01": "inedita_equipe"}.get(p["id"], "gabarito")
        alvo = next(p for p in canonico["perguntas"] if p["id"] == "L01")
        alvo.update({"classeTratamento": "ZERO", "afirmacoesProibidas": [r"\bconfirmar\b"],
                     "aConferir": True, "dispositivosEsperados": [], "revisado": False,
                     "observacao": "Atenção de teste."})
        (self.pasta / "gabarito-canonico.json").write_text(json.dumps(canonico), encoding="utf-8")

    def test_recortes_ineditas_externas_secao_e_csv(self):
        self._marcar()
        texto = rel.gerar(self.pasta)
        self.assertIn("## (c) Inéditas (equipe + geradas por IA) — generalização", texto)
        self.assertIn("## (d) Só as geradas por IA (externas)", texto)
        self.assertIn("## Perguntas geradas por IA: critérios automáticos (#358)", texto)
        self.assertIn("não medem a linguagem real do produtor", texto)
        self.assertNotIn("perguntas de produtores", texto)
        self.assertNotIn("internet e produtores", texto)
        self.assertIn("Fundamentação **a conferir** em 1 perguntas: os", texto)
        # Sem contador (#387): a declaracao da revisao sai no relatorio.
        self.assertIn("revisadas pela equipe e pelo orientador; sem validação de contador", texto)
        self.assertIn("### L01 (pergunta ? do Murillo)", texto)
        # Ceres 1 m-small: "Nao consegui confirmar a citacao." e frase fixa (CITACAO_INVALIDA), nao
        # afirmacao do modelo.
        # A classe da frase fixa tambem nao conta como erro do modelo (#379).
        self.assertIn("| Ceres 1 · `m-small` | 1 | `CITACAO_INVALIDA` | — | — (frase fixa) |", texto)
        self.assertIn("| Ceres 1 · `m-ref` | 1 | `RESPONDIDA` | **errou** | não |", texto)
        self.assertTrue((self.pasta / "externas-avaliacao-cego.csv").exists())
        chave = (self.pasta / "externas-avaliacao-chave.json").read_text(encoding="utf-8")
        # Regerar o relatorio nao reembaralha o CSV que ja foi ao avaliador.
        rel.gerar(self.pasta)
        self.assertEqual((self.pasta / "externas-avaliacao-chave.json").read_text(encoding="utf-8"), chave)

    def test_celula_medida_depois_vai_num_lote_novo_do_csv_cego(self):
        self._marcar()
        import shutil
        guardada = self.pasta.parent / "guardada-ceres-2"
        origem = next(d for d in self.pasta.iterdir() if d.is_dir() and d.name.startswith("ceres-2"))
        shutil.move(str(origem), guardada)
        rel.gerar(self.pasta)
        chave1 = json.loads((self.pasta / "externas-avaliacao-chave.json").read_text(encoding="utf-8"))
        shutil.move(str(guardada), origem)  # a celula "medida depois" (--retomar)
        rel.gerar(self.pasta)
        chave2 = json.loads((self.pasta / "externas-avaliacao-chave.json").read_text(encoding="utf-8"))
        self.assertTrue(set(chave1) < set(chave2))
        self.assertTrue(all(chave2[k] == chave1[k] for k in chave1))
        self.assertTrue((self.pasta / "externas-avaliacao-cego-2.csv").exists())
        self.assertTrue(all(v["lote"] == 2 for k, v in chave2.items() if k not in chave1))

    def test_celula_refeita_vai_num_lote_novo_e_o_antigo_fica_substituido(self):
        self._marcar()
        rel.gerar(self.pasta)
        chave1 = json.loads((self.pasta / "externas-avaliacao-chave.json").read_text(encoding="utf-8"))
        celula = next(v["celula"] for v in chave1.values())
        arquivo = self.pasta / celula / f"{celula}.json"
        dados = json.loads(arquivo.read_text(encoding="utf-8"))
        for r in dados["rodadas"][0]["resultados"]:
            if (r.get("execucao") or {}).get("texto"):
                r["execucao"]["texto"] += " (refeita)"
        arquivo.write_text(json.dumps(dados), encoding="utf-8")
        rel.gerar(self.pasta)
        chave2 = json.loads((self.pasta / "externas-avaliacao-chave.json").read_text(encoding="utf-8"))
        self.assertTrue(all(chave2[k].get("substituida") for k, v in chave1.items() if v["celula"] == celula))
        self.assertTrue(any(v["celula"] == celula and v["lote"] == 2 for v in chave2.values()))
        rel.gerar(self.pasta)  # sem mudanca, nao abre lote novo
        self.assertEqual(json.loads((self.pasta / "externas-avaliacao-chave.json").read_text(
            encoding="utf-8")), chave2)

    def test_celula_refeita_e_agora_invalida_tem_os_codigos_substituidos(self):
        self._marcar()
        rel.gerar(self.pasta)
        chave1 = json.loads((self.pasta / "externas-avaliacao-chave.json").read_text(encoding="utf-8"))
        celula = next(v["celula"] for v in chave1.values())
        arquivo = self.pasta / celula / f"{celula}.json"
        dados = json.loads(arquivo.read_text(encoding="utf-8"))
        dados["rodadas"][0]["resultados"][0]["execucao"]["texto"] = "outra resposta"
        arquivo.write_text(json.dumps(dados), encoding="utf-8")
        manifesto = self.pasta / celula / "celula.json"
        m = json.loads(manifesto.read_text(encoding="utf-8"))
        m["pensamento"] = {"raciocinou": True}
        manifesto.write_text(json.dumps(m), encoding="utf-8")
        rel.gerar(self.pasta)
        chave2 = json.loads((self.pasta / "externas-avaliacao-chave.json").read_text(encoding="utf-8"))
        self.assertTrue(all(v.get("substituida") for v in chave2.values() if v["celula"] == celula))
        self.assertFalse(any(v["celula"] == celula and not v.get("substituida") for v in chave2.values()))

    def test_citacao_de_texto_descartado_nao_conta_no_criterio_principal(self):
        r = {"citacaoValida": True, "execucao": {"desfecho": "CITACAO_INVALIDA"}}
        self.assertEqual(rel.valor_criterio(r, "citacao", {}, [], rel.RECORTE_COMUM)[0], False)

    def test_origem_antiga_das_externas_ainda_e_externa(self):
        self._marcar()
        canonico = json.loads((self.pasta / "gabarito-canonico.json").read_text(encoding="utf-8"))
        for q in canonico["perguntas"]:
            if q["origem"] == "inedita_externa_ia":
                q["origem"] = "inedita_externa"
        (self.pasta / "gabarito-canonico.json").write_text(json.dumps(canonico), encoding="utf-8")
        self.assertIn("## (d) Só as geradas por IA (externas)", rel.gerar(self.pasta))

    def test_citacao_a_conferir_fica_fora_do_criterio_principal(self):
        pergunta = {"aConferir": True, "dispositivosEsperados": ["LC 214/2025, art. 138"]}
        self.assertEqual(rel.valor_criterio({"citacaoValida": False}, "citacao", pergunta, [],
                                            rel.RECORTE_COMUM), (None, True, False))
        self.assertEqual(rel.valor_criterio({"citacaoValida": False}, "citacao", {}, [],
                                            rel.RECORTE_COMUM)[0], False)

    def test_condicoes_com_a_sessao_7_inteira_e_sem_a_8a(self):
        rodada = json.loads((self.pasta / "rodada.json").read_text(encoding="utf-8"))
        rodada["sessao7"] = {"issue": "#270", "rotulo": "Sessão 7", "contem": True, "confirmado": False,
                             "inteira": True, "faltando": ["Sessão 8a (#360)"], "commits": ["a (#270) (#351)"]}
        (self.pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn("**depois** dela inteira, mas **sem** Sessão 8a (#360)", texto)
        self.assertNotIn("sem o último confirmado", texto)

    def test_faltando_lista_inteira_sem_corte(self):
        """#383: com os rotulos reais da matriz faltando, nenhum sai cortado pelo limite da celula."""
        matriz = json.loads((AQUI.parent / "matriz.json").read_text(encoding="utf-8"))
        rotulos = [x["rotulo"] for x in matriz["sessaoParalela"]["tambemExige"]]
        rodada = json.loads((self.pasta / "rodada.json").read_text(encoding="utf-8"))
        rodada["sessao7"] = {"issue": "#270", "rotulo": "Sessão 7", "contem": True, "confirmado": False,
                             "inteira": True, "faltando": rotulos, "commits": ["a (#270) (#351)"]}
        (self.pasta / "rodada.json").write_text(json.dumps(rodada), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn(rotulos[-1].replace("|", "\\|"), texto)

    def test_contaminada_sai_do_inedito(self):
        self._marcar()
        canonico = json.loads((self.pasta / "gabarito-canonico.json").read_text(encoding="utf-8"))
        next(p for p in canonico["perguntas"] if p["id"] == "L01")["contaminada"] = True
        (self.pasta / "gabarito-canonico.json").write_text(json.dumps(canonico), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn("**Contaminadas**", texto)
        self.assertNotIn("## (d) Só as geradas por IA (externas)", texto)

    def test_resposta_cortada_recusa_limpa_e_celula_invalida(self):
        arquivo = self.pasta / "ceres-2--m-small" / "ceres-2--m-small.json"
        dados = json.loads(arquivo.read_text(encoding="utf-8"))
        for rodada in dados["rodadas"]:
            a01 = rodada["resultados"][4]
            a01["execucao"].update({"desfecho": "RESPOSTA_CORTADA", "rota": "FORA_DE_ESCOPO"})
            a01["recusaCorreta"] = True
        arquivo.write_text(json.dumps(dados), encoding="utf-8")
        manifesto = self.pasta / "ceres-1--m-ref" / "celula.json"
        m = json.loads(manifesto.read_text(encoding="utf-8"))
        m["pensamento"] = {"raciocinou": True, "resumo": "ATIVO apesar de think=false"}
        manifesto.write_text(json.dumps(m), encoding="utf-8")
        texto = rel.gerar(self.pasta)
        self.assertIn("| Ceres 2.0 · `m-small` | 1/5 · 1/5 | 2 | 0 | 0 | 2/10 (20,0%) |", texto)
        self.assertIn("| Ceres 2.0 · `m-small` | 100,0% | 0,0% | 2 |", texto)
        self.assertIn("**Células inválidas para comparar**", texto)
        self.assertIn("célula **inválida para comparar**", texto)
        self.assertIn("(inválida)", (self.pasta / "graficos" / "acerto-por-tema.svg").read_text(encoding="utf-8"))
