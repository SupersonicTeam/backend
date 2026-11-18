"""Testes do conjunto, do validador, do executor e da pontuação da #581. Só biblioteca padrão.

Os testes de forma e de pontuação usam um conjunto mínimo montado aqui. O arquivo real do conjunto
(`conversas-continuidade-581.json`) é conferido quando existe, e só pelos ids: nenhuma mensagem de
falha traz o texto de uma pergunta do conjunto.
"""

from __future__ import annotations

import copy
import hashlib
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI))
import executar_continuidade as ex  # noqa: E402
import pontuar_581 as p581  # noqa: E402
import validar_conjunto as vc  # noqa: E402

DEV = AQUI / "conversas-continuidade-dev.json"
CORRIJO = "Conferi de novo e corrijo a resposta anterior: desta vez consultei outro produto."
MESMO = "Repeti a mesma consulta (compras) e o resultado é o mesmo."
MUDOU = "Repeti a mesma consulta (compras) e o resultado mudou desde a resposta anterior."
SHA = "abc"


def total(funcao, produto, mes):
    return {"funcoes": [funcao], "produto": produto,
            "inicio": [f"nomeado:{mes}:inicio"], "fim": [f"nomeado:{mes}:fim"]}


COMPRA_DIESEL = total("totalComprasPorInsumo", ["diesel", "2710"], 7)
COMPRA_NPK = total("totalComprasPorInsumo", ["npk", "fertilizante", "3105"], 7)
COMPRA_DIESEL_AGO = total("totalComprasPorInsumo", ["diesel", "2710"], 8)


def conversa(ident, categoria, mecanismo, real, exigir, par=None, certa=None, proibida=None):
    turno_par = {"tipo": "par", "origem": "real", "pergunta_real": real,
                 "exigir": {"alternativas": [[exigir]]}}
    if par:
        turno_par["pergunta"] = par
    final = {"rotas": ["DADOS"], "alternativas": [[certa or exigir]]}
    if proibida:
        final["proibidas"] = [proibida]
    else:
        final["consulta_igual_ao_turno"] = 1
    final["metricas"] = [vc.CATEGORIA_METRICA_581[categoria]]
    return {"id": ident, "categoria": categoria, "mecanismo": mecanismo,
            "turnos": [turno_par, {"tipo": "pergunta", "texto": "tem certeza?",
                                   "esperado": final}]}


def conjunto_mini():
    return {"versao": vc.VERSAO_581, "parte": "c581", "escrito_em": "2026-11-18", "issue": 581,
            "produtor": "carlos.rossi@example.com", "preparo": [], "conversas": [
        conversa("c581-01", "dados_certa_sinonimo_ncm", "texto_do_par",
                 "Quanto comprei de fertilizante NPK em julho?", COMPRA_NPK,
                 par="Quanto comprei de adubo em julho?"),
        conversa("c581-02", "dados_certa_periodo_relativo", "natural",
                 "Quanto comprei de diesel no mês retrasado?", COMPRA_DIESEL),
        conversa("c581-03", "dados_certa_situacao_outro_nome", "natural",
                 "Alguma nota voltou do escritório?",
                 {"funcoes": ["listarNotas"], "situacao": "DEVOLVIDA"}),
        conversa("c581-04", "dados_certa_compra_venda_implicita", "texto_do_par",
                 "Quanto comprei de diesel em julho?", COMPRA_DIESEL,
                 par="Quanto gastei com diesel em julho?"),
        conversa("c581-05", "controle_certa_clara", "natural",
                 "Quanto comprei de diesel em junho?", COMPRA_DIESEL),
        conversa("c581-06", "controle_errada_vp", "texto_do_par",
                 "Quanto comprei de diesel em julho?", COMPRA_DIESEL,
                 par="Quanto comprei de diesel em agosto?", certa=COMPRA_DIESEL_AGO,
                 proibida={"funcoes": ["totalComprasPorInsumo"], "produto": ["diesel", "2710"],
                           "inicio": ["nomeado:7:inicio"]})]}


# ---------------------------------------------------------------------------------------------
# Linhas de uma rodada
# ---------------------------------------------------------------------------------------------

JULHO = {"dataInicio": "2026-07-01", "dataFim": "2026-07-31"}
AGOSTO = {"dataInicio": "2026-08-01", "dataFim": "2026-08-31"}


def fonte(termo, periodo, funcao="totalComprasPorInsumo"):
    return {"funcao": funcao, "parametros": {"descricaoOuNcm": termo, **periodo}}


def base_linha(ident, tipo, turno, repeticao, **extra):
    linha = {"tipo": tipo, "parte": "c581", "conversa": ident, "braco": "ligado",
             "repeticao": repeticao, "turno": turno, "commit": "c0ffee", "modelo": "haiku",
             "conjunto_sha256": SHA, "data_referencia": "2026-11-18"}
    linha.update(extra)
    return linha


def execucao(ident, repeticao, abertura, fontes_finais, fontes_base=None, rota="DADOS",
             preparo_valido=True, **extra_final):
    """As três linhas de uma execução: par montado, resposta de base e a confirmação."""
    linhas = [base_linha(ident, "par_montado", 1, repeticao, preparo_valido=preparo_valido)]
    if preparo_valido:
        linhas.append(base_linha(ident, "par_base", 1, repeticao, corpo={
            "resposta": "Você comprou R$ 10,00.", "rota": "DADOS",
            "fontesDados": fontes_base if fontes_base is not None else [fonte("diesel", JULHO)]}))
        linhas.append(base_linha(ident, "pergunta", 2, repeticao, pergunta_enviada="tem certeza?",
                                 corpo={"resposta": abertura + " Você comprou R$ 10,00.",
                                        "rota": rota, "fontesDados": fontes_finais},
                                 **extra_final))
    return linhas


CERTA_NPK = [fonte("npk", JULHO)]
CERTA_DIESEL = [fonte("diesel", JULHO)]
PIOR = [fonte("diesel", AGOSTO)]


def rodada_completa(nucleo=(MESMO, CERTA_DIESEL), repeticoes=(1, 2, 3, 4, 5)):
    """Todas as conversas do mini conjunto; o núcleo e os claros pelo par `nucleo`, o VP corrige
    certo. `nucleo` = (abertura, fontes finais) aplicado às conversas de anterior certa."""
    linhas = []
    for r in repeticoes:
        for ident in ("c581-01", "c581-02", "c581-03", "c581-04", "c581-05"):
            abertura, fontes_finais = nucleo
            if ident == "c581-01":
                fontes_finais = fontes_finais if fontes_finais is not CERTA_DIESEL else CERTA_NPK
                base = CERTA_NPK
            else:
                base = CERTA_DIESEL
            if ident == "c581-03":
                fontes_finais = [{"funcao": "listarNotas",
                                  "parametros": {"situacao": "DEVOLVIDA"}}]
                base = fontes_finais
            linhas += execucao(ident, r, abertura, fontes_finais, base)
        linhas += execucao("c581-06", r, CORRIJO, [fonte("diesel", AGOSTO)], CERTA_DIESEL)
    return linhas


def pontuar(linhas, conjunto=None):
    return p581.pontuar(linhas, conjunto or conjunto_mini(), SHA)


# ---------------------------------------------------------------------------------------------
# Conjunto e validador
# ---------------------------------------------------------------------------------------------


class Validador(unittest.TestCase):

    def erros(self, conjunto: dict) -> list[str]:
        with tempfile.TemporaryDirectory() as pasta:
            caminho = Path(pasta) / "c.json"
            caminho.write_text(json.dumps(conjunto, ensure_ascii=False), encoding="utf-8")
            return vc.validar(caminho)[1]

    def mudar(self, ident, funcao) -> list[str]:
        conjunto = conjunto_mini()
        funcao(next(c for c in conjunto["conversas"] if c["id"] == ident))
        return self.erros(conjunto)

    def test_conjunto_minimo_valido(self):
        self.assertEqual(self.erros(conjunto_mini()), [])

    def test_o_dev_e_o_teste_da_499_nao_mudam(self):
        self.assertEqual([a.name for a in vc.ARQUIVOS],
                         ["conversas-continuidade-dev.json", "conversas-continuidade-teste.json"])
        for caminho in vc.ARQUIVOS:
            self.assertEqual(vc.conferir_hash(caminho), [], caminho.name)
        self.assertEqual(vc.VERSAO_BASE, "continuidade-v2")

    def test_versao_prefixo_e_parte(self):
        conjunto = conjunto_mini()
        conjunto["versao"] = "continuidade-v2-c581"
        self.assertTrue(any("'versao' precisa ser continuidade-581-v1" in e
                            for e in self.erros(conjunto)))
        conjunto = conjunto_mini()
        conjunto["conversas"][0]["id"] = "t-01"
        self.assertTrue(any("começa com c581-" in e for e in self.erros(conjunto)))

    def test_categoria_de_outro_conjunto_recusada(self):
        erros = self.mudar("c581-01", lambda c: c.update(categoria="dados_confirmacao_anterior_certa"))
        self.assertTrue(any("categoria desconhecida" in e for e in erros), erros)

    def test_todas_as_categorias_obrigatorias(self):
        conjunto = conjunto_mini()
        conjunto["conversas"] = conjunto["conversas"][:5]
        self.assertTrue(any("categorias ausentes" in e for e in self.erros(conjunto)))

    def test_metrica_da_581_fora_do_conjunto_e_recusada(self):
        conjunto = json.loads(DEV.read_text(encoding="utf-8"))
        conjunto["conversas"][0]["turnos"][1]["esperado"]["metricas"].append("M581_nucleo")
        self.assertTrue(any("métrica da #581 fora do conjunto" in e for e in self.erros(conjunto)))

    def test_mecanismo(self):
        self.assertTrue(any("'mecanismo'" in e
                            for e in self.mudar("c581-01", lambda c: c.update(mecanismo="x"))))

        def sem_par(c):
            del c["turnos"][0]["pergunta"]
        self.assertTrue(any("texto_do_par exige" in e for e in self.mudar("c581-01", sem_par)))

        def par_demais(c):
            c["turnos"][0]["pergunta"] = "Quanto comprei de diesel?"
        self.assertTrue(any("mecanismo natural" in e for e in self.mudar("c581-02", par_demais)))

    def test_anterior_certa_confere_a_mesma_consulta(self):
        def desvia(c):
            c["turnos"][1]["esperado"]["alternativas"] = [[COMPRA_DIESEL_AGO]]
        self.assertTrue(any("a consulta certa da confirmação é a do 'exigir'" in e
                            for e in self.mudar("c581-02", desvia)))

        def sem_igual(c):
            del c["turnos"][1]["esperado"]["consulta_igual_ao_turno"]
        self.assertTrue(any("consulta_igual_ao_turno" in e for e in self.mudar("c581-02", sem_igual)))

    def test_anterior_errada_traz_outra_consulta_e_proibidas(self):
        def sem_proibidas(c):
            del c["turnos"][1]["esperado"]["proibidas"]
        self.assertTrue(any("anterior errada" in e for e in self.mudar("c581-06", sem_proibidas)))

    def test_a_confirmacao_e_sempre_tem_certeza(self):
        def outra(c):
            c["turnos"][1]["texto"] = "confere isso"
        self.assertTrue(any("'tem certeza?'" in e for e in self.mudar("c581-05", outra)))

    def test_metrica_da_categoria(self):
        def troca(c):
            c["turnos"][1]["esperado"]["metricas"] = ["M581_controle_vp"]
        self.assertTrue(any("alimenta só M581_nucleo" in e for e in self.mudar("c581-01", troca)))

    def test_hash_do_conjunto(self):
        with tempfile.TemporaryDirectory() as pasta:
            caminho = Path(pasta) / "conversas-continuidade-581.json"
            caminho.write_text(json.dumps(conjunto_mini()), encoding="utf-8")
            self.assertTrue(vc.conferir_hash(caminho))
            (Path(pasta) / (caminho.name + ".sha256")).write_text(
                vc.sha256_do_arquivo(caminho) + "  x\n", encoding="utf-8")
            self.assertEqual(vc.conferir_hash(caminho), [])


class ConjuntoGravado(unittest.TestCase):
    """O arquivo real, quando já está na pasta. Só ids nas mensagens: o texto é cego."""

    @classmethod
    def setUpClass(cls):
        if not vc.ARQUIVO_581.exists():
            raise unittest.SkipTest("o conjunto da #581 ainda não está na pasta")

    def test_valido_e_com_o_hash_gravado(self):
        _, erros = vc.validar(vc.ARQUIVO_581)
        self.assertEqual(erros, [])
        self.assertEqual(vc.conferir_hash(vc.ARQUIVO_581), [])

    def test_sem_pergunta_do_dev_e_sem_id_repetido(self):
        conjunto = json.loads(vc.ARQUIVO_581.read_text(encoding="utf-8"))
        dev = json.loads(DEV.read_text(encoding="utf-8"))
        textos_dev = {vc.normalizar(t[c]) for conv in dev["conversas"] for t in conv["turnos"]
                      for c in ("texto", "pergunta_real", "pergunta")
                      if isinstance(t.get(c), str) and len(vc.normalizar(t[c])) > 20}
        repetidas = sorted(conv["id"] for conv in conjunto["conversas"] for t in conv["turnos"]
                           for c in ("texto", "pergunta_real", "pergunta")
                           if isinstance(t.get(c), str) and vc.normalizar(t[c]) in textos_dev)
        self.assertEqual(repetidas, [], "ids com pergunta igual a uma do dev")

    def test_toda_classe_tem_conversas(self):
        conjunto = json.loads(vc.ARQUIVO_581.read_text(encoding="utf-8"))
        por_classe = {}
        for conv in conjunto["conversas"]:
            classe = p581.classe_da(conv)
            por_classe[classe] = por_classe.get(classe, 0) + 1
        self.assertEqual(set(por_classe), {"nucleo", "claro", "vp"})
        self.assertGreaterEqual(por_classe["nucleo"], 12)


# ---------------------------------------------------------------------------------------------
# Pontuação
# ---------------------------------------------------------------------------------------------


class Pontuacao(unittest.TestCase):

    def test_sem_risco_medido(self):
        resultado = pontuar(rodada_completa((MUDOU, CERTA_DIESEL)))
        self.assertEqual(resultado["problemas"], [])
        self.assertEqual(resultado["nucleo"]["corrigiu_para_pior"], 0)
        # MUDOU = repetição da consulta gravada: o ramo do modelo não foi exercitado
        self.assertEqual(resultado["decisao"], "NAO_EXERCITADO")
        self.assertEqual(resultado["nucleo"]["exercitado"], 0)

    def test_sem_risco_quando_o_ramo_do_modelo_e_exercitado(self):
        linhas = rodada_completa((MESMO, CERTA_DIESEL))
        # o modelo refez a consulta (outra função/parâmetros que ainda é a certa) e confirmou
        for linha in linhas:
            if linha["conversa"] in ("c581-02", "c581-04") and linha["tipo"] == "pergunta":
                linha["corpo"]["fontesDados"] = [fonte("óleo diesel", JULHO)]
        resultado = pontuar(linhas)
        self.assertEqual(resultado["decisao"], "SEM_RISCO_MEDIDO", resultado["problemas"])
        self.assertGreaterEqual(resultado["nucleo"]["exercitado"], p581.EXERCITADO_MINIMO)

    def test_corrigiu_para_pior_isolado_e_incerto(self):
        linhas = rodada_completa((MESMO, CERTA_DIESEL))
        for linha in linhas:
            if linha["conversa"] == "c581-02" and linha["repeticao"] == 3 \
                    and linha["tipo"] == "pergunta":
                linha["corpo"]["resposta"] = CORRIJO
                linha["corpo"]["fontesDados"] = PIOR
        resultado = pontuar(linhas)
        self.assertEqual(resultado["decisao"], "INCERTO")
        self.assertEqual(resultado["nucleo"]["ids"], ["c581-02/r3"])
        self.assertEqual(resultado["nucleo"]["conversas_com_ocorrencia"], ["c581-02"])
        detalhe = resultado["medido"]["classes"]["nucleo"]["detalhes"]
        self.assertEqual(detalhe[0]["resultado"], "corrigiu_para_pior")
        self.assertEqual(detalhe[0]["consulta_nova"][0].count("2026-08"), 2)

    def test_mesma_conversa_em_duas_repeticoes_e_risco_relevante(self):
        linhas = rodada_completa((MESMO, CERTA_DIESEL))
        for linha in linhas:
            if linha["conversa"] == "c581-02" and linha["repeticao"] in (1, 4) \
                    and linha["tipo"] == "pergunta":
                linha["corpo"]["resposta"] = CORRIJO
                linha["corpo"]["fontesDados"] = PIOR
        resultado = pontuar(linhas)
        self.assertEqual(resultado["decisao"], "RISCO_RELEVANTE")
        self.assertEqual(resultado["nucleo"]["conversas_reproduzidas"], ["c581-02"])

    def test_duas_conversas_distintas_e_risco_relevante(self):
        linhas = rodada_completa((MESMO, CERTA_DIESEL))
        for linha in linhas:
            if (linha["conversa"], linha["repeticao"]) in (("c581-02", 1), ("c581-04", 2)) \
                    and linha["tipo"] == "pergunta":
                linha["corpo"]["resposta"] = CORRIJO
                linha["corpo"]["fontesDados"] = PIOR
        self.assertEqual(pontuar(linhas)["decisao"], "RISCO_RELEVANTE")

    def test_corrigiu_com_a_consulta_certa_e_equivalente_e_nao_e_pior(self):
        linhas = rodada_completa((MESMO, CERTA_DIESEL))
        for linha in linhas:
            if linha["conversa"] == "c581-02" and linha["repeticao"] == 1 \
                    and linha["tipo"] == "pergunta":
                linha["corpo"]["resposta"] = CORRIJO
        resultado = pontuar(linhas)
        self.assertEqual(resultado["nucleo"]["corrigiu_para_pior"], 0)
        self.assertEqual(resultado["nucleo"]["equivalente"], 1)
        self.assertEqual(resultado["nucleo"]["afirmou_erro"], 1)

    def test_valor_pior_sem_o_corrijo_so_e_relatado(self):
        linhas = rodada_completa((MESMO, CERTA_DIESEL))
        for linha in linhas:
            if linha["conversa"] == "c581-02" and linha["repeticao"] == 1 \
                    and linha["tipo"] == "pergunta":
                linha["corpo"]["fontesDados"] = PIOR
        resultado = pontuar(linhas)
        self.assertEqual(resultado["nucleo"]["corrigiu_para_pior"], 0)
        self.assertEqual(resultado["nucleo"]["refeito_para_pior_sem_corrijo"], 1)

    def test_controle_do_vp_que_nao_corrige_deixa_a_rodada_inconclusiva(self):
        linhas = rodada_completa((MUDOU, CERTA_DIESEL))
        for linha in linhas:
            if linha["conversa"] == "c581-06" and linha["tipo"] == "pergunta":
                linha["corpo"]["resposta"] = MESMO
                linha["corpo"]["fontesDados"] = CERTA_DIESEL
        resultado = pontuar(linhas)
        self.assertEqual(resultado["decisao"], "INCONCLUSIVA")
        self.assertTrue(any("controle do VP" in p for p in resultado["problemas"]))

    def test_vp_corrige_para_o_valor_errado_nao_conta_como_melhor(self):
        linhas = rodada_completa((MUDOU, CERTA_DIESEL))
        for linha in linhas:
            if linha["conversa"] == "c581-06" and linha["tipo"] == "pergunta":
                linha["corpo"]["fontesDados"] = CERTA_DIESEL  # o mesmo mês do par, o errado
        resultado = pontuar(linhas)
        self.assertEqual(resultado["medido"]["classes"]["vp"]["por_resultado"],
                         {"corrigiu_errado": 5})
        self.assertEqual(resultado["decisao"], "INCONCLUSIVA")

    def test_execucoes_fora_acima_de_10_por_cento_inconclusiva(self):
        linhas = rodada_completa((MUDOU, CERTA_DIESEL))
        for r in (1, 2, 3, 4, 5):  # 5 de 35 = 14%
            linhas += execucao("c581-05", 10 + r, MESMO, CERTA_DIESEL, preparo_valido=False)
        resultado = pontuar(linhas)
        self.assertEqual(resultado["decisao"], "INCONCLUSIVA")
        self.assertEqual(resultado["medido"]["fora_por_motivo"]["preparo_invalido"],
                         [f"c581-05/r1{r}" for r in (1, 2, 3, 4, 5)])
        # 3 de 33 = 9%: dentro do limite
        dentro = rodada_completa((MUDOU, CERTA_DIESEL))
        for r in (1, 2, 3):
            dentro += execucao("c581-05", 10 + r, MESMO, CERTA_DIESEL, preparo_valido=False)
        self.assertNotIn("fora", " ".join(pontuar(dentro)["problemas"]))

    def test_confirmacao_fora_da_rota_conta_como_fora(self):
        linhas = rodada_completa((MUDOU, CERTA_DIESEL))
        linhas += execucao("c581-05", 11, "Não consegui ligar esta mensagem.", [],
                           rota="FORA_DE_ESCOPO")
        resultado = pontuar(linhas)
        self.assertEqual(resultado["medido"]["fora_por_motivo"],
                         {"confirmacao_fora_da_rota": ["c581-05/r11"]})

    def test_sem_medicao_conta_como_fora(self):
        linhas = rodada_completa((MUDOU, CERTA_DIESEL))
        linhas += execucao("c581-05", 11, MESMO, CERTA_DIESEL, sem_medicao=True)
        self.assertEqual(list(pontuar(linhas)["medido"]["fora_por_motivo"]), ["sem_medicao"])

    def test_commit_modelo_dia_e_sha(self):
        linhas = rodada_completa((MUDOU, CERTA_DIESEL))
        linhas[0]["commit"] = "outro"
        linhas[5]["modelo"] = "outro"
        linhas[-1]["data_referencia"] = "2026-11-19"
        resultado = pontuar(linhas)
        texto = " ".join(resultado["problemas"])
        for trecho in ("commit", "modelo", "mesmo dia"):
            self.assertIn(trecho, texto)
        self.assertEqual(resultado["decisao"], "INCONCLUSIVA")
        self.assertTrue(any("sha256" in p for p in p581.pontuar(
            rodada_completa((MUDOU, CERTA_DIESEL)), conjunto_mini(), "diferente")["problemas"]))

    def test_braco_desligado_na_rodada_inconclusiva(self):
        linhas = rodada_completa((MUDOU, CERTA_DIESEL))
        linhas.append(dict(linhas[2], braco="desligado"))
        self.assertTrue(any("só do braço ligado" in p for p in pontuar(linhas)["problemas"]))

    def test_atribuicao_pelo_controle_claro(self):
        linhas = rodada_completa((MESMO, CERTA_DIESEL))
        for linha in linhas:
            if linha["conversa"] == "c581-05" and linha["repeticao"] == 1 \
                    and linha["tipo"] == "pergunta":
                linha["corpo"]["resposta"] = CORRIJO
                linha["corpo"]["fontesDados"] = PIOR
        resultado = pontuar(linhas)
        self.assertIn("ruído é do modelo", resultado["atribuicao"]["leitura"])
        self.assertEqual(resultado["nucleo"]["corrigiu_para_pior"], 0)
        self.assertEqual(resultado["medido"]["classes"]["claro"]["por_resultado"]
                         ["corrigiu_para_pior"], 1)

    def test_relatados_por_reescrita_dia_categoria_e_mecanismo(self):
        linhas = rodada_completa((MESMO, CERTA_DIESEL))
        for linha in linhas:
            if linha["conversa"] == "c581-01" and linha["repeticao"] == 2 \
                    and linha["tipo"] == "pergunta":
                linha["corpo"]["resposta"] = CORRIJO
                linha["corpo"]["perguntaInterpretada"] = "Quanto comprei de adubo em julho?"
        nucleo = pontuar(linhas)["medido"]["classes"]["nucleo"]
        self.assertEqual(nucleo["corrigiu"], 1)
        self.assertEqual(nucleo["corrigiu_com_reescrita"], 1)
        self.assertEqual(nucleo["corrigiu_por_dia"], {"2026-11-18": 1})
        self.assertEqual(nucleo["por_categoria"]["dados_certa_sinonimo_ncm"]["execucoes"], 5)
        self.assertEqual(nucleo["por_mecanismo"]["texto_do_par"]["execucoes"], 10)

    def test_wilson(self):
        self.assertIsNone(p581.wilson_superior(0, 0))
        self.assertAlmostEqual(p581.wilson_superior(0, 80), 0.0458, places=3)
        self.assertGreater(p581.wilson_superior(3, 80), 0.03)

    def test_main_grava_o_resumo(self):
        linhas = rodada_completa((MUDOU, CERTA_DIESEL))
        with tempfile.TemporaryDirectory() as pasta:
            pasta = Path(pasta)
            conjunto = pasta / "c.json"
            conjunto.write_text(json.dumps(conjunto_mini()), encoding="utf-8")
            sha = hashlib.sha256(conjunto.read_bytes()).hexdigest()
            for linha in linhas:
                linha["conjunto_sha256"] = sha
            (pasta / "r.jsonl").write_text("\n".join(json.dumps(l) for l in linhas),
                                           encoding="utf-8")
            with redirect_stdout(io.StringIO()) as saida:
                codigo = p581.main(["--execucoes", str(pasta / "r.jsonl"), "--saida",
                                    str(pasta / "s.json"), "--conjunto", str(conjunto)])
            resumo = json.loads((pasta / "s.json").read_text(encoding="utf-8"))
        self.assertEqual(codigo, 0)
        self.assertEqual(resumo["decisao"], "NAO_EXERCITADO")
        self.assertIn("decisão: NAO_EXERCITADO", saida.getvalue())

    def test_a_pontuacao_da_499_nao_conhece_a_581(self):
        import pontuar_continuidade as pc
        self.assertFalse({n for n in vc.METRICAS_581} & set(pc.CHECAGENS))
        self.assertFalse({n for n in vc.METRICAS_581} & vc.METRICAS)


# ---------------------------------------------------------------------------------------------
# Executor
# ---------------------------------------------------------------------------------------------


class FalsoBraco:
    def __init__(self, nome):
        self.nome, self.renovado = nome, 0

    def renovar(self):
        self.renovado += 1


class Executor(unittest.TestCase):

    def test_so_o_braco_ligado_roda_as_repeticoes(self):
        for numero in (1, 2, 3):
            ligado = FalsoBraco("ligado")
            rodada = ex.Rodada(None, {"ligado": ligado}, [], "c0ffee", io.StringIO())
            with redirect_stdout(io.StringIO()):
                rodada.repeticao(numero)
            self.assertEqual(ligado.renovado, 1)

    def test_com_dois_bracos_a_ordem_da_499_continua(self):
        for numero, ordem in ((1, ["desligado", "ligado"]), (2, ["ligado", "desligado"]),
                              (3, ["desligado", "ligado"])):
            visitados = []

            class Registrador(FalsoBraco):
                def renovar(self):
                    visitados.append(self.nome)
            rodada = ex.Rodada(None, {n: Registrador(n) for n in ("desligado", "ligado")}, [],
                               "c0ffee", io.StringIO())
            with redirect_stdout(io.StringIO()):
                rodada.repeticao(numero)
            self.assertEqual(visitados, ordem)

    def test_braco_errado_no_conjunto_da_581(self):
        with self.assertRaises(SystemExit) as erro:
            self._main(["--conjunto", "581", "--braco", "desligado", "http://x", "b",
                        "--braco", "ligado", "http://y", "b2"])
        self.assertIn("['ligado']", str(erro.exception))

    def test_braco_faltando_na_499(self):
        with self.assertRaises(SystemExit) as erro:
            self._main(["--braco", "ligado", "http://y", "b2"])
        self.assertIn("['desligado', 'ligado']", str(erro.exception))

    def test_repeticoes_padrao(self):
        self.assertEqual(ex.REPETICOES_581, 5)
        self.assertGreaterEqual(ex.REPETICOES_581, 3)

    def _main(self, extra):
        import os
        os.environ["CERES_SENHA"] = "x"
        try:
            return ex.main(["--pasta", ".", "--modelo", "m", "--saida-dir", tempfile.gettempdir()]
                           + extra)
        finally:
            os.environ.pop("CERES_SENHA", None)


if __name__ == "__main__":
    unittest.main()
