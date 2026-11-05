"""Adaptacao do gabarito e recortes do benchmark (issue #348)."""

import json
import sys
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parent))

from ceres_benchmark import gabarito as gab  # noqa: E402

FONTE = '''
public class FerramentasDadosProdutor {
  @Tool(name = "listarNotas", resultConverter = X.class,
      description = """
      Lista as notas (ex.: "quais notas (de saida) emiti?"), com virgula, e parenteses.""")
  public Notas listarNotas(
      @ToolParam(description = "ENTRADA, SAIDA (ou vazio)") String tipo,
      @ToolParam(description = "situacao, ex.: \\"DEVOLVIDA\\"") String situacao,
      Map<String, List<String>> extra,
      ToolContext toolContext) {
    return null;
  }

  @Tool(description = "Sem parametro")
  public Alertas listarAlertas(ToolContext toolContext) { return null; }

  public void auxiliar(String naoEFuncao) {}
}
'''


def pergunta(id_, **campos):
    base = {"id": id_, "categoria": "DADOS", "pergunta": f"pergunta {id_}", "rotaEsperada": "DADOS"}
    base.update(campos)
    return base


class AssinaturasTest(unittest.TestCase):
    def test_le_funcoes_e_parametros_com_text_block_e_generics(self):
        self.assertEqual(gab.assinaturas_ferramentas(FONTE), {
            "listarNotas": ["tipo", "situacao", "extra", "toolContext"],
            "listarAlertas": ["toolContext"],
        })

    def test_sem_tool_falha(self):
        with self.assertRaises(ValueError):
            gab.assinaturas_ferramentas("class X {}")


class AdaptarTest(unittest.TestCase):
    FUNCOES = {"listarNotas": ["tipo", "situacao", "toolContext"], "listarAlertas": ["toolContext"]}

    def gabarito(self):
        return {"versao": "v1", "perguntas": [
            pergunta("OK", funcoesEsperadas=["listarNotas"], parametrosEsperados={"tipo": ["SAIDA"]},
                     camposValor=["valorTotal"]),
            pergunta("SEM_FUNCAO", funcoesEsperadas=["guiasEmAtraso"], camposValor=["emAtraso.valorTotal"]),
            pergunta("ALTERNATIVA", funcoesEsperadas=["vencimentosDoMes", "listarAlertas"],
                     parametrosEsperados={"mes": ["2026-11"]}, camposValor=["valorTotal"]),
            pergunta("SEM_PARAMETRO", funcoesEsperadas=["listarNotas"],
                     parametrosEsperados={"numero": ["103"]}, camposValor=["valorTotal"]),
            pergunta("LISTA", funcoesEsperadas=["listarNotas"], camposValor=["notas.0.valorIbs"]),
            pergunta("EXTRA", funcoesEsperadas=["listarNotas"], parametrosEsperados={"situacao": ["DEVOLVIDA"]},
                     camposValor=["quantidadeNoTotal"]),
        ]}

    def test_regras_de_adaptacao(self):
        original = self.gabarito()
        adaptado, adaptacoes = gab.adaptar(original, self.FUNCOES, {"EXTRA": ["camposValor"]},
                                           indice_de_lista=False)
        por_id = {p["id"]: p for p in adaptado["perguntas"]}
        self.assertNotIn("OK", adaptacoes)
        self.assertEqual(por_id["OK"]["camposValor"], ["valorTotal"])
        # Funcao inexistente: sai, e com ela o valor esperado que ela calculava.
        self.assertEqual(por_id["SEM_FUNCAO"]["funcoesEsperadas"], [])
        self.assertNotIn("camposValor", por_id["SEM_FUNCAO"])
        # A alternativa que existe fica; parametro e valor da referencia que nao existe saem.
        self.assertEqual(por_id["ALTERNATIVA"]["funcoesEsperadas"], ["listarAlertas"])
        self.assertNotIn("parametrosEsperados", por_id["ALTERNATIVA"])
        self.assertNotIn("camposValor", por_id["ALTERNATIVA"])
        self.assertNotIn("parametrosEsperados", por_id["SEM_PARAMETRO"])
        self.assertNotIn("camposValor", por_id["SEM_PARAMETRO"])
        self.assertNotIn("camposValor", por_id["LISTA"])
        self.assertNotIn("camposValor", por_id["EXTRA"])
        self.assertEqual(por_id["EXTRA"]["parametrosEsperados"], {"situacao": ["DEVOLVIDA"]})
        self.assertEqual(set(adaptacoes), {"SEM_FUNCAO", "ALTERNATIVA", "SEM_PARAMETRO", "LISTA", "EXTRA"})
        self.assertEqual(adaptacoes["SEM_FUNCAO"]["removido"]["camposValor"], ["emAtraso.valorTotal"])
        self.assertEqual(adaptado["versao"], "v1-adaptado")
        # O original nao muda (o canonico vai para a Ceres 2.0 como esta).
        self.assertEqual(original, self.gabarito())

    def test_lista_e_aceita_quando_a_arquitetura_le_indice(self):
        _, adaptacoes = gab.adaptar(self.gabarito(), self.FUNCOES, indice_de_lista=True)
        self.assertNotIn("LISTA", adaptacoes)

    def test_sem_adaptacao_mantem_a_versao(self):
        g = {"versao": "v1", "perguntas": [self.gabarito()["perguntas"][0]]}
        adaptado, adaptacoes = gab.adaptar(g, self.FUNCOES)
        self.assertEqual(adaptacoes, {})
        self.assertEqual(adaptado["versao"], "v1")


class RecorteTest(unittest.TestCase):
    def test_recorte_comum_exige_mesmo_texto_e_sem_adaptacao(self):
        canonico = {"perguntas": [
            pergunta("A", pergunta="Quanto comprei?"), pergunta("B", pergunta="Pergunta nova"),
            pergunta("C", pergunta="Texto mudou"), pergunta("D", pergunta="Adaptada"),
            pergunta("E", pergunta="Só nova")]}
        antigo = {"perguntas": [
            pergunta("A", pergunta="quanto  COMPREI"), pergunta("C", pergunta="Texto antigo"),
            pergunta("D", pergunta="Adaptada")]}
        self.assertEqual(gab.recorte_comum(canonico, antigo, {"D": {}}), ["A"])

    def test_normalizar_igual_ao_java(self):
        self.assertEqual(gab.normalizar("  Óleo\\u00e9 DIESEL-S10? "), "oleoe diesel s10")

    def test_gabarito_real_do_repositorio_e_lido(self):
        real = json.loads((AQUI.parents[1] / "gabarito-ceres.json").read_text(encoding="utf-8"))
        criterios = {c for p in real["perguntas"] for c in ("rota", "numero") if gab.aplicavel(p, c)}
        self.assertEqual(criterios, {"rota", "numero"})


if __name__ == "__main__":
    unittest.main()
