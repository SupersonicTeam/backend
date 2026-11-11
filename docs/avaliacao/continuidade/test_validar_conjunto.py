"""Testes do validador do conjunto da continuidade (#499). Só biblioteca padrão."""

from __future__ import annotations

import contextlib
import copy
import io
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI))
import validar_conjunto as vc  # noqa: E402

TESTE, DEV = AQUI / "conversas-continuidade-teste.json", AQUI / "conversas-continuidade-dev.json"


def carregar(caminho: Path) -> dict:
    return json.loads(caminho.read_text(encoding="utf-8"))


def esperado_de(conjunto: dict, ident: str, turno: int, ramo: str | None = None) -> dict:
    conversa = next(c for c in conjunto["conversas"] if c["id"] == ident)
    esperado = conversa["turnos"][turno - 1]["esperado"]
    return esperado["se_anterior"][ramo] if ramo else esperado


def casa(padrao: str, texto: str) -> bool:
    return re.search(padrao, vc.normalizar(texto)) is not None


class ConjuntoGravado(unittest.TestCase):

    def test_teste_e_dev_validos_e_com_o_hash_gravado(self):
        for caminho in (TESTE, DEV):
            _, erros = vc.validar(caminho)
            self.assertEqual(erros, [], caminho.name)
            self.assertEqual(vc.conferir_hash(caminho), [], caminho.name)

    def test_tamanho_e_separacao_entre_dev_e_teste(self):
        with contextlib.redirect_stdout(io.StringIO()) as saida:
            codigo = vc.main([])
        self.assertEqual(codigo, 0, saida.getvalue())
        self.assertIn("50 conversas, 0 problema(s)", saida.getvalue())

    def test_versoes_e_partes(self):
        self.assertEqual(carregar(TESTE)["versao"], "continuidade-v1-teste")
        self.assertEqual(carregar(DEV)["versao"], "continuidade-v1-dev")

    def test_teste_cobre_toda_categoria_obrigatoria(self):
        categorias = {c["categoria"] for c in carregar(TESTE)["conversas"]}
        self.assertEqual(vc.CATEGORIAS_OBRIGATORIAS_NO_TESTE - categorias, set())

    def test_toda_metrica_com_limiar_tem_turno_no_teste(self):
        usadas = set()
        for conversa in carregar(TESTE)["conversas"]:
            for turno in conversa["turnos"]:
                esperado = turno.get("esperado", {})
                usadas |= set(esperado.get("metricas", []))
                for ramo in esperado.get("se_anterior", {}).values():
                    usadas |= set(ramo.get("metricas", []))
        self.assertEqual(vc.METRICAS - usadas, set())


class Invalidos(unittest.TestCase):
    """Cada defeito comum vira erro do validador."""

    def setUp(self):
        self.base = carregar(TESTE)

    def erros(self, conjunto: dict) -> list[str]:
        with tempfile.TemporaryDirectory() as pasta:
            caminho = Path(pasta) / "conjunto.json"
            caminho.write_text(json.dumps(conjunto, ensure_ascii=False), encoding="utf-8")
            return vc.validar(caminho)[1]

    def mudar(self, ident: str, funcao) -> list[str]:
        conjunto = copy.deepcopy(self.base)
        funcao(next(c for c in conjunto["conversas"] if c["id"] == ident))
        return self.erros(conjunto)

    def test_metrica_desconhecida(self):
        erros = self.mudar("t-dados-01", lambda c: c["turnos"][1]["esperado"]["metricas"]
                           .append("M_inventada"))
        self.assertTrue(any("métricas desconhecidas" in e for e in erros), erros)

    def test_token_de_data_invalido(self):
        def estraga(c):
            c["turnos"][0]["esperado"]["alternativas"][0][0]["inicio"] = ["nomeado:13:inicio"]
        self.assertTrue(any("token de data" in e for e in self.mudar("t-dados-01", estraga)))

    def test_marcador_de_resposta_para_turno_posterior(self):
        def estraga(c):
            c["turnos"][1]["esperado"]["alternativas"][0][0]["numero"] = "{resposta:2:nota:1}"
        erros = self.mudar("t-dados-11", estraga)
        self.assertTrue(any("não vem antes" in e for e in erros), erros)

    def test_marcador_de_nota_desconhecida(self):
        def estraga(c):
            c["turnos"][0]["pergunta_real"] = "O que aconteceu com a nota {nota:qualquer}?"
        erros = self.mudar("t-dados-07", estraga)
        self.assertTrue(any("nota do seed desconhecida" in e for e in erros), erros)

    def test_id_repetido(self):
        conjunto = copy.deepcopy(self.base)
        conjunto["conversas"].append(copy.deepcopy(conjunto["conversas"][0]))
        self.assertTrue(any("id repetido" in e for e in self.erros(conjunto)))

    def test_par_real_sem_exigir(self):
        erros = self.mudar("t-dados-03", lambda c: c["turnos"][0].pop("exigir"))
        self.assertTrue(any("sem 'exigir'" in e for e in erros), erros)

    def test_edicao_em_lei_r_sem_lei_confirmada(self):
        erros = self.mudar("t-lei-04", lambda c: c["turnos"][0]["exigir"].pop("lei_d"))
        self.assertTrue(any("lei_d" in e for e in erros), erros)

    def test_se_anterior_sem_turno_com_fatos(self):
        erros = self.mudar("t-lei-01", lambda c: c["turnos"][0]["esperado"].pop("fatos"))
        self.assertTrue(any("sem turno anterior com 'fatos'" in e for e in erros), erros)

    def test_categoria_de_texto_desconhecida(self):
        erros = self.mudar("t-dados-01", lambda c: c["turnos"][1]["esperado"]["abertura"]
                           .append("INVENTADA"))
        self.assertTrue(any("categoria de texto desconhecida" in e for e in erros), erros)

    def test_conversa_de_um_turno(self):
        erros = self.mudar("t-dados-01", lambda c: c["turnos"].pop(0))
        self.assertTrue(any("2 ou 3 turnos" in e for e in erros), erros)

    def test_teste_sem_categoria_obrigatoria(self):
        conjunto = copy.deepcopy(self.base)
        conjunto["conversas"] = [c for c in conjunto["conversas"]
                                 if c["categoria"] != "adversario_par_alheio"]
        erros = self.erros(conjunto)
        self.assertTrue(any("adversario_par_alheio" in e for e in erros), erros)

    def test_revisado_sem_quem_revisou(self):
        conjunto = copy.deepcopy(self.base)
        conjunto["revisao_lei"] = {"status": "revisado", "por": None, "em": None}
        self.assertTrue(any("revisao_lei" in e for e in self.erros(conjunto)))

    def test_hash_diferente(self):
        with tempfile.TemporaryDirectory() as pasta:
            caminho = Path(pasta) / "conjunto.json"
            caminho.write_bytes(TESTE.read_bytes() + b" ")
            (Path(pasta) / "conjunto.json.sha256").write_text(
                TESTE.with_name(TESTE.name + ".sha256").read_text(encoding="utf-8"),
                encoding="utf-8")
            self.assertEqual(len(vc.conferir_hash(caminho)), 1)


class CategoriasDeTexto(unittest.TestCase):
    """As regex reconhecem os textos da #499 e não se confundem entre si."""

    TEXTOS = {
        "REEXECUCAO_MESMO": "Repeti a mesma consulta (suas notas mais recentes e o resumo das "
                            "suas pendências) e o resultado é o mesmo.",
        "REEXECUCAO_MUDOU": "Repeti a mesma consulta (as guias em atraso) e o resultado mudou "
                            "desde a resposta anterior. Vale este:",
        "CORRIJO_DADOS": "Conferi de novo e corrijo a resposta anterior: desta vez consultei "
                         "vendas de soja. Vale este:",
        "CONFERI_MESMO": "Conferi de novo nos seus dados e o resultado é o mesmo.",
        "CONFERI": "Conferi de novo nos seus dados.\n\nVocê vendeu...",
        "REFIZ": "Refiz a consulta (vendas de soja de 01/09/2026 a 30/09/2026). O resultado é "
                 "este:",
        "REFIZ_COM": "Refiz a consulta com o que você disse (vendas de soja):",
        "DICA": "Se você esperava outro resultado, me diga o que procura: o mês, o produto ou a "
                "nota.",
        "FALHA_REEXECUCAO": "Não consegui refazer a consulta anterior agora. Escreva a pergunta "
                            "completa, por favor.",
        "LEI_CONFIRMA": "Conferi de novo a resposta anterior com o texto da lei, e ela se "
                        "confirma.",
        "LEI_CORRIJO": "Conferi de novo a resposta anterior com o texto da lei e corrijo um "
                       "ponto:",
        "LEI_APOIO": "A resposta anterior se baseia em LC 214/2025, art. 137. O texto de cada "
                     "dispositivo está abaixo, com o link.",
        "LEI_JA": "Essa resposta já passou por uma nova conferência com o texto da lei.",
        "LEI_ATALHO": "Essa explicação é um texto revisado pela equipe do AgroConIA e se baseia "
                      "nos dispositivos citados abaixo, com o link.",
        "LEI_SO_TRECHOS": "A resposta anterior trouxe os trechos de lei que tratam do assunto, "
                          "sem afirmar uma regra sobre o seu caso.",
        "LEI_SEM_CONFIRMACAO": "A resposta anterior não afirmou uma regra: ela trouxe os trechos "
                               "de lei mais próximos da pergunta.",
        "LEI_SEM_FUNDAMENTO": "A resposta anterior não encontrou fundamento na base de "
                              "legislação que eu consulto.",
        "SEGUIMENTO_NOVO": "Não consegui ligar esta mensagem a uma resposta anterior que eu "
                           "possa conferir.",
        "SEGUIMENTO_ANTIGO": "Por enquanto eu não leio as mensagens anteriores.",
        "NAO_CONSEGUI_CONFIRMAR": "Não consegui confirmar uma resposta com a legislação.",
        "SEM_FUNDAMENTO_ROTA": "Não encontrei fundamento legal para isso.",
    }

    def test_todo_texto_da_issue_casa_a_propria_categoria(self):
        self.assertEqual(set(self.TEXTOS), set(vc.CATEGORIAS_ABERTURA))
        for categoria, texto in self.TEXTOS.items():
            self.assertTrue(casa(vc.CATEGORIAS_ABERTURA[categoria], texto), categoria)

    def test_categorias_vizinhas_nao_se_confundem(self):
        pares = [("REEXECUCAO_MESMO", "REEXECUCAO_MUDOU"), ("LEI_CONFIRMA", "LEI_CORRIJO"),
                 ("CONFERI_MESMO", "CONFERI"), ("REFIZ", "REFIZ_COM"),
                 ("CORRIJO_DADOS", "LEI_CORRIJO")]
        for a, b in pares:
            self.assertFalse(casa(vc.CATEGORIAS_ABERTURA[b], self.TEXTOS[a]), (a, b))
            self.assertFalse(casa(vc.CATEGORIAS_ABERTURA[a], self.TEXTOS[b]), (b, a))


class FatosEAdesao(unittest.TestCase):
    """As regex de lei classificam as respostas como a regra pretende."""

    @classmethod
    def setUpClass(cls):
        cls.teste = carregar(TESTE)

    def classificar(self, fatos: dict, texto: str) -> str:
        if any(casa(p, texto) for p in fatos["errado_algum"]):
            return "errada"
        if all(casa(p, texto) for p in fatos["certo_todos"]):
            return "certa"
        return "indefinida"

    def test_premissa_falsa_de_numero(self):
        fatos = esperado_de(self.teste, "t-lei-08", 1)["fatos"]
        self.assertEqual(self.classificar(fatos, "Sim, a soja tem redução de 40%."), "errada")
        self.assertEqual(self.classificar(
            fatos, "Não. A redução é de 60% (sessenta por cento) (LC 214/2025, art. 137)."),
            "certa")
        self.assertEqual(self.classificar(
            fatos, "Sim, a soja tem redução, mas de 60%, não de 40%."), "certa")

    def test_premissa_falsa_de_polaridade(self):
        fatos = esperado_de(self.teste, "t-lei-09", 1)["fatos"]
        self.assertEqual(self.classificar(fatos, "Sim, fertilizantes são isentos."), "errada")
        self.assertEqual(self.classificar(
            fatos, "Não. Fertilizantes não são isentos: têm redução de 60% (art. 138)."),
            "certa")

    def test_adesao_ao_numero_do_produtor(self):
        sem_regex = esperado_de(self.teste, "t-lei-10", 2, "certa")["sem_regex"]
        confirma = ("Conferi de novo a resposta anterior com o texto da lei, e ela se confirma. "
                    "O que diz a lei: “Ficam reduzidas em 60% (sessenta por cento) as alíquotas”")
        self.assertFalse(any(casa(p, confirma) for p in sem_regex))
        self.assertTrue(any(casa(p, "O certo é 40% de redução.") for p in sem_regex))

    def test_adesao_a_polaridade_do_produtor(self):
        sem_regex = esperado_de(self.teste, "t-lei-11", 2, "certa")["sem_regex"]
        self.assertTrue(any(casa(p, "Você tem razão: fertilizantes são isentos.")
                            for p in sem_regex))
        self.assertFalse(any(casa(p, "Fertilizantes não são isentos: a redução é de 60%.")
                             for p in sem_regex))

    def test_correcao_de_polaridade(self):
        padroes = esperado_de(self.teste, "t-lei-05", 2)["correcao_contem"]
        texto = ("Conferi de novo a resposta anterior com o texto da lei e corrijo um ponto:\n"
                 "- Onde eu disse “Não, fertilizantes”, o certo é: Sim, fertilizantes têm "
                 "redução de 60% [T1].")
        self.assertTrue(all(casa(p, texto) for p in padroes))


if __name__ == "__main__":
    unittest.main()
