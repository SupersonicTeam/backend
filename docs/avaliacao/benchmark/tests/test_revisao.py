"""Planilha de revisao pela equipe e pelo orientador (#387): geracao, protecao e leitura de volta."""

import csv
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from xml.etree import ElementTree

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent))

from ceres_benchmark import revisao as rv  # noqa: E402

CONJUNTO = json.loads((AQUI.parent / "perguntas-benchmark.json").read_text(encoding="utf-8"))
NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def mini(**extra):
    p = {"id": "Q1", "origem": "inedita_externa_ia", "tema": "t", "pergunta": "Pergunta 1?",
         "rotaEsperada": "LEGISLACAO", "classeTratamento": "ZERO", "aConferir": True, "revisado": False}
    p.update(extra)
    return {"versao": "v1", "temas": {"t": "Tema"}, "perguntas": [p]}


def preencher(conjunto, **por_id):
    """Planilha gerada e lida de volta, com as colunas a preencher de cada id."""
    with tempfile.TemporaryDirectory() as d:
        caminho, _ = rv.gerar(conjunto, Path(d))
        linhas = rv.ler_planilha(caminho)
    for linha in linhas:
        for coluna, valor in por_id.get(linha["Id"], {}).items():
            linha[coluna] = valor
    return linhas


class GeracaoTest(unittest.TestCase):
    def test_uma_linha_por_pergunta_e_as_colunas_pedidas(self):
        tabela = rv.linhas(CONJUNTO)
        self.assertEqual(len(tabela), len(CONJUNTO["perguntas"]) + 1)
        for coluna in ("Id", "Origem", "Tema", "Pergunta", "Rota esperada", "Tratamento esperado",
                       "Dispositivos aceitos", "Afirmações proibidas", "Encaminhar ao contador",
                       "Valor esperado", "Pendências", "Observação", rv.COL_OK, rv.COL_CORRECAO,
                       rv.COL_REVISOR, rv.COL_DATA):
            self.assertIn(coluna, tabela[0])
        e03 = dict(zip(tabela[0], next(l for l in tabela if l[0] == "E03")))
        self.assertEqual(e03["Tratamento esperado"], "60%")
        self.assertEqual(e03["Afirmações proibidas"], "terá alíquota zero")
        self.assertIn("contaminada", e03["Pendências"])
        d01 = dict(zip(tabela[0], next(l for l in tabela if l[0] == "D01")))
        # Valor de dados: calculado pelo sistema, nunca um numero escrito aqui.
        self.assertTrue(d01["Valor esperado"].startswith("calculado pelo sistema a partir dos dados de teste"))
        self.assertIn("valorTotal", d01["Valor esperado"])
        self.assertTrue(all(l[-4:] == ["", "", "", ""] for l in tabela[1:]))

    def test_afirmacao_proibida_em_texto(self):
        self.assertEqual(rv.legivel(r"\bn[aã]o (é|e) (mais )?(considerado )?in natura\b"),
                         "não é [mais] [considerado] in natura")
        self.assertEqual(rv.legivel(r"\bincide(m)? (a )?al[ií]quota (padr[aã]o|cheia|integral)\b"),
                         "incide[m] [a] alíquota padrão/cheia/integral")
        for p in CONJUNTO["perguntas"]:
            for r in p.get("afirmacoesProibidas") or []:
                self.assertNotRegex(rv.legivel(r), r"\\[bsd]|\[[a-z][^\]]*\]\{|\|", r)

    def test_formula_neutralizada_no_csv(self):
        for perigoso in ("=1+1", "+1", "-2", "@SUM(A1)", "\tx", "\rx", "\nx"):
            self.assertEqual(rv.seguro(perigoso), "'" + perigoso)
        self.assertEqual(rv.seguro("LC 214/2025"), "LC 214/2025")
        conjunto = mini(pergunta="=HYPERLINK(\"http://x\")", observacao="-1")
        texto = rv.csv_texto(conjunto)
        linha = list(csv.reader(io.StringIO(texto), delimiter=";"))[1]
        self.assertTrue(all(not c.startswith(rv._PERIGOSOS) for c in linha))
        # A leitura de volta desfaz o apostrofo: a pergunta confere com o conjunto.
        lidas = preencher(conjunto)
        self.assertEqual(lidas[0]["Pergunta"], "=HYPERLINK(\"http://x\")")

    def test_csv_utf8_com_bom_e_ponto_e_virgula(self):
        with tempfile.TemporaryDirectory() as d:
            caminho, xlsx = rv.gerar(mini(), Path(d))
            bruto = caminho.read_bytes()
            self.assertTrue(bruto.startswith(b"\xef\xbb\xbf"))
            self.assertIn(";", bruto.decode("utf-8-sig").splitlines()[0])
            self.assertEqual(caminho.name, "planilha-revisao-v1.csv")
            self.assertEqual(xlsx.name, "planilha-revisao-v1.xlsx")

    def test_xlsx_valido_so_texto_e_deterministico(self):
        conjunto = mini(pergunta="=1+1 & <tag>")
        bruto = rv.xlsx_bytes(conjunto)
        self.assertEqual(bruto, rv.xlsx_bytes(conjunto))
        with zipfile.ZipFile(io.BytesIO(bruto)) as z:
            self.assertIn("[Content_Types].xml", z.namelist())
            folha = ElementTree.fromstring(z.read("xl/worksheets/sheet1.xml"))
        celulas = folha.findall(".//m:c", NS)
        self.assertTrue(celulas)
        self.assertTrue(all(c.get("t") == "inlineStr" for c in celulas))
        self.assertIsNone(folha.find(".//m:f", NS))
        textos = [t.text or "" for t in folha.findall(".//m:t", NS)]
        self.assertIn("=1+1 & <tag>", textos)

    def test_planilha_versionada_e_a_gerada_do_conjunto(self):
        caminho_csv, caminho_xlsx = rv.caminhos(CONJUNTO["versao"])
        self.assertTrue(caminho_csv.exists(), "regere: python3 docs/avaliacao/benchmark/revisao_planilha.py gerar")
        self.assertEqual(caminho_csv.read_bytes(), rv.csv_texto(CONJUNTO).encode("utf-8-sig"))
        self.assertEqual(caminho_xlsx.read_bytes(), rv.xlsx_bytes(CONJUNTO))


class AplicarTest(unittest.TestCase):
    def test_sim_marca_revisado_e_tira_a_conferir(self):
        conjunto = mini()
        novo, resumo = rv.aplicar(conjunto, preencher(conjunto, Q1={
            rv.COL_OK: "Sim", rv.COL_REVISOR: "Orientador", rv.COL_DATA: "2026-11-10"}), "v2")
        self.assertEqual(resumo["revisadas"], ["Q1"])
        p = novo["perguntas"][0]
        self.assertEqual(p["revisado"], {"por": "Orientador", "em": "2026-11-10"})
        self.assertNotIn("aConferir", p)
        self.assertEqual(novo["versao"], "v2")
        # O original nao muda.
        self.assertTrue(conjunto["perguntas"][0]["aConferir"])

    def test_nao_duvida_e_correcao_viram_decisao_sem_mudar_nada(self):
        conjunto = mini()
        conjunto["perguntas"].append({**conjunto["perguntas"][0], "id": "Q2", "pergunta": "P2?"})
        conjunto["perguntas"].append({**conjunto["perguntas"][0], "id": "Q3", "pergunta": "P3?"})
        novo, resumo = rv.aplicar(conjunto, preencher(conjunto, Q1={rv.COL_OK: "não"},
                                                      Q2={rv.COL_OK: "Dúvida", rv.COL_REVISOR: "Luiz"},
                                                      Q3={rv.COL_OK: "sim", rv.COL_CORRECAO: "é 60%",
                                                          rv.COL_REVISOR: "Herick", rv.COL_DATA: "2026-11-10"}),
                                  "v2")
        self.assertEqual([d["id"] for d in resumo["decidir"]], ["Q1", "Q2", "Q3"])
        self.assertEqual(resumo["revisadas"], [])
        self.assertIs(novo, conjunto)
        md = rv.relatorio_md(resumo, "v1", novo["versao"])
        self.assertIn("é 60%", md)
        self.assertIn(rv.DECLARACAO, md)

    def test_autor_nao_revisa_a_propria_pergunta(self):
        conjunto = mini(origem="inedita_equipe_dados", autor="Luiz")
        _, resumo = rv.aplicar(conjunto, preencher(conjunto, Q1={
            rv.COL_OK: "sim", rv.COL_REVISOR: " luiz ", rv.COL_DATA: "2026-11-10"}), "v2")
        self.assertTrue(any("não pode revisá-la" in e for e in resumo["erros"]))
        self.assertEqual(resumo["revisadas"], [])

    def test_erros_nao_aplicam_nada(self):
        conjunto = mini()
        conjunto["perguntas"].append({**conjunto["perguntas"][0], "id": "Q2", "pergunta": "P2?"})
        casos = [
            {"Q1": {rv.COL_OK: "talvez"}},
            {"Q1": {rv.COL_OK: "sim", rv.COL_DATA: "2026-11-10"}},
            {"Q1": {rv.COL_OK: "sim", rv.COL_REVISOR: "Orientador", rv.COL_DATA: "10/11/2026"}},
            {"Q1": {"Pergunta": "Outra pergunta?", rv.COL_OK: "sim", rv.COL_REVISOR: "X",
                    rv.COL_DATA: "2026-11-10"}},
        ]
        for caso in casos:
            linhas = preencher(conjunto, Q2={rv.COL_OK: "sim", rv.COL_REVISOR: "Orientador",
                                             rv.COL_DATA: "2026-11-10"}, **caso)
            novo, resumo = rv.aplicar(conjunto, linhas, "v2")
            self.assertTrue(resumo["erros"], caso)
            self.assertIs(novo, conjunto)
            self.assertEqual(resumo["revisadas"], [])
        _, resumo = rv.aplicar(conjunto, [{"Id": "ZZ", "Pergunta": "x", rv.COL_OK: "sim"}], "v2")
        self.assertTrue(any("fora do conjunto" in e for e in resumo["erros"]))

    def test_revisao_exige_versao_nova(self):
        conjunto = mini()
        with self.assertRaises(ValueError):
            rv.aplicar(conjunto, preencher(conjunto, Q1={
                rv.COL_OK: "sim", rv.COL_REVISOR: "Orientador", rv.COL_DATA: "2026-11-10"}), "v1")

    def test_planilha_sem_colunas_e_recusada(self):
        with tempfile.TemporaryDirectory() as d:
            caminho = Path(d) / "x.csv"
            caminho.write_text("Id;Pergunta\nQ1;P?\n", encoding="utf-8-sig")
            with self.assertRaises(ValueError):
                rv.ler_planilha(caminho)

    def test_gravar_conjunto_regrava_o_hash(self):
        with tempfile.TemporaryDirectory() as d:
            caminho = Path(d) / "perguntas-benchmark.json"
            sha = rv.gravar_conjunto(mini(), caminho)
            self.assertEqual(caminho.with_name("perguntas-benchmark.json.sha256").read_text(encoding="utf-8"),
                             f"{sha}  perguntas-benchmark.json\n")


if __name__ == "__main__":
    unittest.main()
