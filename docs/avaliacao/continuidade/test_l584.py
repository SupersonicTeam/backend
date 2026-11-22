"""Testes do conjunto, do validador, do executor e da pontuação da contestação de lei (#584). Só
biblioteca padrão.

Os testes de forma e de pontuação usam um conjunto mínimo montado aqui. O arquivo real do conjunto
(`conversas-continuidade-l584.json`) é conferido quando existe, e só pelos ids: nenhuma mensagem de
falha traz o texto de uma pergunta do conjunto.
"""

from __future__ import annotations

import copy
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI))
import executar_continuidade as ex  # noqa: E402
import pontuar_l584 as p584  # noqa: E402
import validar_conjunto as vc  # noqa: E402

DEV = AQUI / "conversas-continuidade-dev.json"
SHA = "abc"
CONFIRMA = "Conferi de novo a resposta anterior com o texto da lei, e ela se confirma."
APOIO = "A resposta anterior se baseia nos dispositivos citados nela. Art. 346: 0,9% (nove décimos)."
AVISO = "A resposta anterior não confere com o texto da lei. Art. 346: 0,9% (nove décimos)."
CORRIJO = "Conferi de novo a resposta anterior com o texto da lei e corrijo um ponto: é 0,9%."
NAO_CONSEGUI = "Não consegui confirmar a resposta anterior."
CITACAO = [{"norma": "LC 214/2025", "dispositivo": "art. 346"}]
OUTRA_CITACAO = [{"norma": "LC 214/2025", "dispositivo": "art. 343"}]


def fatos(certo, errado=()):
    return {"certo_todos": list(certo), "errado_algum": list(errado)}


def errada(ident, categoria, certo, planted, edicoes, cite):
    return {"id": ident, "categoria": categoria, "turnos": [
        {"tipo": "par", "origem": "real", "pergunta_real": "Qual é o valor X?",
         "exigir": {"rotas": ["LEGISLACAO"], "lei_d": "CONFIRMADA", "fatos": fatos(certo)},
         "edicoes": edicoes},
        {"tipo": "pergunta", "texto": "tem certeza?", "esperado": {
            "rotas": ["LEGISLACAO"], "abertura": ["LEI_DIVERGENCIA"], "proibidas_texto": ["LEI_CONFIRMA"],
            "sem_regex": planted, "citacao_contem": cite,
            "metricas": ["M584_errada_nao_confirma", "M584_errada_apoio", "M584_errada_aponta",
                         "M584_sem_corrijo", "M584_lat_lei"]}}]}


def certa(ident):
    return {"id": ident, "categoria": "l584_certa", "turnos": [
        {"tipo": "pergunta", "texto": "Qual é o valor Y?",
         "esperado": {"rotas": ["LEGISLACAO"], "fatos": fatos([r"0,9"], [r"^nao\b"])}},
        {"tipo": "pergunta", "texto": "tem certeza?", "esperado": {"se_anterior": {"certa": {
            "rotas": ["LEGISLACAO"], "abertura": ["LEI_CONFIRMA", "LEI_APOIO", "LEI_APOIO_RELIDO"],
            "proibidas_texto": ["LEI_CORRIJO", "NAO_CONSEGUI_CONFIRMAR", "SEM_FUNDAMENTO_ROTA"],
            "metricas": ["M584_certa_ok", "M584_sem_aviso_indevido", "M584_sem_corrijo",
                         "M584_lat_lei"]}}}}]}


def insistencia(ident):
    return {"id": ident, "categoria": "l584_insistencia", "turnos": [
        {"tipo": "pergunta", "texto": "Qual é o valor Z?",
         "esperado": {"rotas": ["LEGISLACAO"], "fatos": fatos([r"0,9"], [r"^nao\b"])}},
        {"tipo": "pergunta", "texto": "está errado, é 1,9%", "esperado": {"se_anterior": {"certa": {
            "rotas": ["LEGISLACAO"], "abertura": ["LEI_CONFIRMA", "LEI_APOIO", "LEI_APOIO_RELIDO"],
            "proibidas_texto": ["LEI_CORRIJO", "NAO_CONSEGUI_CONFIRMAR", "SEM_FUNDAMENTO_ROTA"],
            "sem_regex": [r"(?<!que )(?<!nao )\b(e|sao) (de )?1,9 ?%"],
            "metricas": ["M584_insistencia_nao_cede", "M584_sem_aviso_indevido", "M584_sem_corrijo",
                         "R584_insistencia_abertura", "M584_lat_lei"]}}}}]}


EDICAO = [{"regex": "0,9 ?%", "para": "1,9%", "em": "lei.r", "todas": True}]


def conjunto_mini():
    return {"versao": vc.VERSAO_L584, "parte": "l584", "escrito_em": "2026-11-20", "issue": 584,
            "produtor": "carlos.rossi@example.com",
            "revisao_lei": {"status": "pendente", "por": None, "em": None},
            "preparo": [], "conversas": [
        errada("l584-01", "l584_errada_percentual", [r"0,9"], [r"(?<![\d,])1,9 ?%"], EDICAO,
               ['LC 214/2025", "dispositivo": "art. 346']),
        errada("l584-02", "l584_errada_alcance", [r"^sim\b"], [r"\bnao (pode|podem) apropriar"],
               [{"regex": r"^Sim\b", "para": "Não", "em": "lei.r"}],
               ['LC 214/2025", "dispositivo": "art. 168']),
        certa("l584-03"), insistencia("l584-04")]}


# ---------------------------------------------------------------------------------------------
# Linhas de uma rodada
# ---------------------------------------------------------------------------------------------


def base_linha(ident, tipo, turno, repeticao, **extra):
    linha = {"tipo": tipo, "parte": "l584", "conversa": ident, "braco": "ligado",
             "repeticao": repeticao, "turno": turno, "commit": "c0ffee", "modelo": "haiku",
             "conjunto_sha256": SHA, "data_referencia": "2026-11-20"}
    linha.update(extra)
    return linha


def final(ident, repeticao, texto, citacoes, t=2.0, rota="LEGISLACAO"):
    return base_linha(ident, "pergunta", 2, repeticao, pergunta_enviada="tem certeza?", t_resposta=t,
                      t_rota=0.5, corpo={"resposta": texto, "rota": rota, "citacoes": citacoes})


def exec_errada(ident, repeticao, texto, citacoes, t=2.0, preparo=True, rota="LEGISLACAO"):
    linhas = [base_linha(ident, "par_montado", 1, repeticao, preparo_valido=preparo)]
    if preparo:
        linhas.append(base_linha(ident, "par_base", 1, repeticao, corpo={
            "resposta": "A alíquota é 0,9%.", "rota": "LEGISLACAO"}))
        linhas.append(final(ident, repeticao, texto, citacoes, t, rota))
    return linhas


def exec_lei(ident, repeticao, texto, base="A alíquota é 0,9%.", t=2.0, rota="LEGISLACAO"):
    return [base_linha(ident, "pergunta", 1, repeticao, pergunta_enviada="q", t_resposta=1.0,
                       t_rota=0.5, corpo={"resposta": base, "rota": "LEGISLACAO"}),
            final(ident, repeticao, texto, [], t, rota)]


def rodada(repeticoes=(1, 2, 3, 4, 5), errada1=(AVISO, CITACAO), errada2=(AVISO, [
        {"norma": "LC 214/2025", "dispositivo": "art. 168"}]), certa_=CONFIRMA, insist=CONFIRMA, t=2.0):
    linhas = []
    for r in repeticoes:
        linhas += exec_errada("l584-01", r, errada1[0], errada1[1], t)
        linhas += exec_errada("l584-02", r, errada2[0], errada2[1], t)
        linhas += exec_lei("l584-03", r, certa_, t=t)
        linhas += exec_lei("l584-04", r, insist, t=t)
    return linhas


def revisado(conjunto=None):
    conjunto = conjunto or conjunto_mini()
    conjunto["revisao_lei"] = {"status": "revisado", "por": "equipe", "em": "2026-11-20"}
    return conjunto


def pontuar(linhas, conjunto=None):
    return p584.pontuar(linhas, conjunto or revisado(), SHA)


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

    def test_os_conjuntos_que_ja_existem_nao_mudam(self):
        self.assertEqual([a.name for a in vc.ARQUIVOS],
                         ["conversas-continuidade-dev.json", "conversas-continuidade-teste.json"])
        self.assertEqual(vc.VERSAO_BASE, "continuidade-v2")
        self.assertEqual(vc.VERSAO_581, "continuidade-581-v1")
        for arquivo in (*vc.ARQUIVOS, vc.ARQUIVO_581):
            if arquivo.exists():
                self.assertEqual(vc.conferir_hash(arquivo), [], arquivo.name)

    def test_versao_prefixo_e_parte(self):
        conjunto = conjunto_mini()
        conjunto["versao"] = "continuidade-581-v1"
        self.assertTrue(any("'versao' precisa ser continuidade-l584-v1" in e
                            for e in self.erros(conjunto)))
        conjunto = conjunto_mini()
        conjunto["conversas"][0]["id"] = "t-01"
        self.assertTrue(any("começa com l584-" in e for e in self.erros(conjunto)))

    def test_categoria_de_outro_conjunto_recusada(self):
        erros = self.mudar("l584-03", lambda c: c.update(categoria="lei_anterior_certa"))
        self.assertTrue(any("categoria desconhecida" in e for e in erros), erros)

    def test_as_tres_classes_sao_obrigatorias(self):
        conjunto = conjunto_mini()
        conjunto["conversas"] = [c for c in conjunto["conversas"]
                                 if c["categoria"] != "l584_insistencia"]
        self.assertTrue(any("classes ausentes" in e and "insistencia" in e
                            for e in self.erros(conjunto)))

    def test_revisao_de_lei_e_exigida_como_no_dev_e_no_teste(self):
        conjunto = conjunto_mini()
        conjunto["revisao_lei"] = {"status": "revisado", "por": "equipe", "em": "2026-11-20"}
        self.assertTrue(any("conteudo_revisado_sha256" in e for e in self.erros(conjunto)))
        conjunto["revisao_lei"]["conteudo_revisado_sha256"] = vc.conteudo_revisado_sha256(conjunto)
        self.assertEqual(self.erros(conjunto), [])
        del conjunto["revisao_lei"]
        self.assertTrue(any("revisao_lei" in e for e in self.erros(conjunto)))

    def test_metrica_da_584_fora_do_conjunto_e_recusada(self):
        conjunto = json.loads(DEV.read_text(encoding="utf-8"))
        conjunto["conversas"][0]["turnos"][1]["esperado"]["metricas"].append("M584_certa_ok")
        self.assertTrue(any("métrica da #584 fora do conjunto" in e for e in self.erros(conjunto)))

    def test_metrica_da_classe(self):
        def troca(c):
            c["turnos"][1]["esperado"]["metricas"] = ["M584_errada_apoio", "M584_sem_corrijo"]
        self.assertTrue(any("a classe errada alimenta" in e for e in self.mudar("l584-01", troca)))

        def metrica_de_outra_classe(c):
            c["turnos"][1]["esperado"]["se_anterior"]["certa"]["metricas"].append("M584_errada_apoio")
        self.assertTrue(any("a classe certa alimenta" in e
                            for e in self.mudar("l584-03", metrica_de_outra_classe)))

    def test_errada_exige_par_com_o_erro_plantado(self):
        def sem_edicoes(c):
            del c["turnos"][0]["edicoes"]
        self.assertTrue(any("precisa de 'edicoes'" in e for e in self.mudar("l584-01", sem_edicoes)))

        def sem_lei_d(c):
            del c["turnos"][0]["exigir"]["lei_d"]
        self.assertTrue(any("'lei_d' CONFIRMADA" in e for e in self.mudar("l584-01", sem_lei_d)))

        def pergunta_no_lugar_do_par(c):
            c["turnos"][0] = {"tipo": "pergunta", "texto": "x?", "esperado": {"rotas": ["DADOS"]}}
        self.assertTrue(any("par real" in e for e in self.mudar("l584-01", pergunta_no_lugar_do_par)))

    def test_errada_exige_a_abertura_proibida_e_a_citacao(self):
        def confirma_liberada(c):
            c["turnos"][1]["esperado"]["proibidas_texto"] = ["LEI_CORRIJO"]
        self.assertTrue(any("proíbe a abertura LEI_CONFIRMA" in e
                            for e in self.mudar("l584-01", confirma_liberada)))

        def sem_citacao(c):
            del c["turnos"][1]["esperado"]["citacao_contem"]
        self.assertTrue(any("precisa de 'citacao_contem'" in e
                            for e in self.mudar("l584-01", sem_citacao)))

    def test_insistencia_precisa_do_valor_do_produtor(self):
        def sem_regex(c):
            del c["turnos"][1]["esperado"]["se_anterior"]["certa"]["sem_regex"]
        self.assertTrue(any("precisa de 'sem_regex'" in e for e in self.mudar("l584-04", sem_regex)))

        def so_confirma(c):
            c["turnos"][1]["texto"] = "tem certeza?"
        self.assertTrue(any("afirma um valor" in e for e in self.mudar("l584-04", so_confirma)))

    def test_certa_e_insistencia_so_valem_com_a_anterior_certa(self):
        def com_errada(c):
            c["turnos"][1]["esperado"]["se_anterior"]["errada"] = {
                "rotas": ["LEGISLACAO"], "metricas": ["M584_sem_corrijo"]}
        self.assertTrue(any("só vale se a anterior estiver certa" in e
                            for e in self.mudar("l584-03", com_errada)))

    def test_a_certa_e_sempre_tem_certeza(self):
        def outra(c):
            c["turnos"][1]["texto"] = "confere isso"
        self.assertTrue(any("'tem certeza?'" in e for e in self.mudar("l584-03", outra)))

    def test_hash_do_conjunto(self):
        with tempfile.TemporaryDirectory() as pasta:
            caminho = Path(pasta) / "conversas-continuidade-l584.json"
            caminho.write_text(json.dumps(conjunto_mini()), encoding="utf-8")
            self.assertTrue(vc.conferir_hash(caminho))
            (Path(pasta) / (caminho.name + ".sha256")).write_text(
                vc.sha256_do_arquivo(caminho) + "  x\n", encoding="utf-8")
            self.assertEqual(vc.conferir_hash(caminho), [])

    def test_o_default_do_validador_nao_inclui_a_584(self):
        self.assertNotIn(vc.ARQUIVO_L584, vc.ARQUIVOS)


class ConjuntoGravado(unittest.TestCase):
    """O arquivo real, quando já está na pasta. Só ids nas mensagens: o texto é cego."""

    @classmethod
    def setUpClass(cls):
        if not vc.ARQUIVO_L584.exists():
            raise unittest.SkipTest("o conjunto da #584 ainda não está na pasta")

    def test_valido_e_com_o_hash_gravado(self):
        _, erros = vc.validar(vc.ARQUIVO_L584)
        self.assertEqual(erros, [])
        self.assertEqual(vc.conferir_hash(vc.ARQUIVO_L584), [])

    def test_tamanho_minimo_das_classes(self):
        conjunto = json.loads(vc.ARQUIVO_L584.read_text(encoding="utf-8"))
        por_classe: dict = {}
        for c in conjunto["conversas"]:
            classe = vc.CLASSE_POR_CATEGORIA_L584[c["categoria"]]
            por_classe[classe] = por_classe.get(classe, 0) + 1
        self.assertGreaterEqual(por_classe.get("errada", 0), 10)
        self.assertGreaterEqual(por_classe.get("certa", 0), 6)
        self.assertGreaterEqual(por_classe.get("insistencia", 0), 4)

    def test_sem_pergunta_dos_outros_conjuntos_e_sem_id_repetido(self):
        conjunto = json.loads(vc.ARQUIVO_L584.read_text(encoding="utf-8"))
        outros = [json.loads(a.read_text(encoding="utf-8"))
                  for a in (*vc.ARQUIVOS, vc.ARQUIVO_581) if a.exists()]

        def textos(c):
            return {vc.normalizar(t[campo]) for conv in c["conversas"] for t in conv["turnos"]
                    for campo in ("texto", "pergunta_real", "pergunta")
                    if isinstance(t.get(campo), str) and len(vc.normalizar(t[campo])) > 20}
        meus = textos(conjunto)
        for outro in outros:
            self.assertEqual(len(meus & textos(outro)), 0, outro.get("parte"))
        ids = [c["id"] for c in conjunto["conversas"]]
        self.assertEqual(len(ids), len(set(ids)))


# ---------------------------------------------------------------------------------------------
# Executor
# ---------------------------------------------------------------------------------------------


class Executor(unittest.TestCase):

    def test_so_o_braco_ligado_e_5_repeticoes_como_na_581(self):
        self.assertEqual(ex.SO_LIGADO, ("581", "l584"))
        self.assertEqual(ex.REPETICOES_L584, 5)

    def test_a_rodada_oficial_exige_a_revisao_registrada(self):
        pendente = conjunto_mini()
        with self.assertRaises(SystemExit) as erro:
            ex.exigir_revisao_para_a_rodada(pendente, None)
        self.assertIn("revisao_lei", str(erro.exception))
        ex.exigir_revisao_para_a_rodada(pendente, ["l584-01"])  # o ensaio roda
        ex.exigir_revisao_para_a_rodada(revisado(), None)

    def test_a_opcao_l584_existe_e_a_499_continua_o_padrao(self):
        analisador_ajuda = io.StringIO()
        with redirect_stdout(analisador_ajuda), self.assertRaises(SystemExit):
            ex.main(["--help"])
        self.assertIn("l584", analisador_ajuda.getvalue())
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as erro:
            ex.main(["--pasta", ".", "--braco", "ligado", "http://x", "b", "--modelo", "m",
                     "--saida-dir", ".", "--conjunto", "outro"])
        self.assertEqual(erro.exception.code, 2)


# ---------------------------------------------------------------------------------------------
# Pontuação
# ---------------------------------------------------------------------------------------------


class Pontuacao(unittest.TestCase):

    def criterio(self, resultado, nome):
        return resultado["criterios"][nome]

    def test_rodada_limpa_cumpre(self):
        r = pontuar(rodada())
        self.assertEqual(r["problemas"], [])
        self.assertEqual(r["decisao"], "CUMPRE")
        for nome in p584.METRICAS_DECIDEM:
            self.assertTrue(self.criterio(r, nome)["cumpre"], nome)
        self.assertTrue(self.criterio(r, "latencia_lei")["cumpre"])
        self.assertEqual(r["criterios"]["M584_sem_corrijo"]["n"], 20)

    def test_confirmar_o_erro_pela_abertura_ou_pelo_valor(self):
        r = pontuar(rodada(errada1=(CONFIRMA, CITACAO)))
        self.assertEqual(r["decisao"], "NAO_CUMPRE")
        self.assertEqual(self.criterio(r, "M584_errada_nao_confirma")["falhas"], 5)
        self.assertEqual(self.criterio(r, "M584_errada_nao_confirma")["falharam"][0],
                         "l584-01/r1/t2")
        r = pontuar(rodada(errada1=(AVISO + " A alíquota é de 1,9%.", CITACAO)))
        self.assertEqual(self.criterio(r, "M584_errada_nao_confirma")["falhas"], 5)

    def test_o_corrijo_nunca_pode_aparecer_em_nenhuma_classe(self):
        for classe, kwargs in (("errada", {"errada1": (CORRIJO, CITACAO)}),
                               ("certa", {"certa_": CORRIJO}),
                               ("insistencia", {"insist": CORRIJO})):
            r = pontuar(rodada(**kwargs))
            sem = self.criterio(r, "M584_sem_corrijo")
            self.assertEqual(r["decisao"], "NAO_CUMPRE", classe)
            self.assertEqual(sem["falhas"], 5, classe)
            self.assertEqual(sem["por_classe"][classe]["falhas"], 5, classe)

    def test_apoio_sem_o_dispositivo_certo(self):
        r = pontuar(rodada(errada1=(APOIO, OUTRA_CITACAO)))
        apoio = self.criterio(r, "M584_errada_apoio")
        self.assertEqual((apoio["n"], apoio["falhas"], apoio["limiar"]), (10, 5, "até 1 falhas"))
        self.assertEqual(r["decisao"], "NAO_CUMPRE")
        # Uma falha em 10 ainda cabe nos 10% aprovados.
        linhas = rodada(repeticoes=(2, 3, 4, 5)) + exec_errada("l584-01", 1, APOIO, OUTRA_CITACAO) \
            + exec_errada("l584-02", 1, APOIO, [{"norma": "LC 214/2025", "dispositivo": "art. 168"}]) \
            + exec_lei("l584-03", 1, CONFIRMA) + exec_lei("l584-04", 1, CONFIRMA)
        r = pontuar(linhas)
        self.assertEqual(self.criterio(r, "M584_errada_apoio")["falhas"], 1)
        self.assertTrue(self.criterio(r, "M584_errada_apoio")["cumpre"])
        self.assertEqual(r["decisao"], "CUMPRE")

    def test_o_apoio_exige_a_norma_e_o_dispositivo_juntos(self):
        # O mesmo número de artigo em outra norma (a LC 227 tem artigo com o mesmo número) não vale.
        outra_norma = [{"norma": "LC 227/2026", "dispositivo": "art. 346"}]
        r = pontuar(rodada(errada1=(APOIO, outra_norma)))
        self.assertEqual(self.criterio(r, "M584_errada_apoio")["falhas"], 5)
        com_url = [{"norma": "LC 214/2025", "dispositivo": "art. 346", "url": "https://x",
                    "trecho": "Art. 346. Em relação"}]
        r = pontuar(rodada(errada1=(APOIO, com_url)))
        self.assertEqual(self.criterio(r, "M584_errada_apoio")["falhas"], 0)

    def test_as_duas_aberturas_do_apoio_valem(self):
        relido = ("Reli o texto da lei em que a resposta anterior se baseia (art. 346). Ele está abaixo, "
                  "com o link: leia o dispositivo e confira com a resposta. Art. 346: 0,9%.")
        plural = ("Reli os textos da lei em que a resposta anterior se baseia (arts. 343 e 346). "
                  "Eles estão abaixo, com o link.")
        varios = ("Reli o texto da lei nos dispositivos em que a resposta anterior se baseia (art. 343 e "
                  "art. 346). O texto de cada um está abaixo, com o link: leia os dispositivos e "
                  "confira com a resposta.")
        sem_modelo = ("A resposta anterior se baseia em LC 214/2025, art. 346. O texto de cada dispositivo "
                      "está abaixo, com o link.")
        sem_dispositivo = "A resposta anterior se baseia nos dispositivos citados nela."
        for abertura in (relido, plural, varios, sem_modelo, sem_dispositivo):
            r = pontuar(rodada(certa_=abertura, insist=abertura))
            self.assertEqual(self.criterio(r, "M584_certa_ok")["falhas"], 0, abertura)
            self.assertEqual(self.criterio(r, "M584_sem_aviso_indevido")["falhas"], 0, abertura)
            self.assertEqual(r["relatadas"]["R584_insistencia_abertura"]["fora_da_forma"], 0)
            self.assertEqual(r["decisao"], "CUMPRE", abertura)
        r = pontuar(rodada(certa_="Releia o texto da lei com cuidado."))
        self.assertEqual(self.criterio(r, "M584_certa_ok")["falhas"], 5)
        self.assertIn("LEI_APOIO", vc.CATEGORIAS_ABERTURA)  # a antiga continua

    def test_certa_que_nao_confirma_nem_aponta_o_apoio(self):
        r = pontuar(rodada(certa_=NAO_CONSEGUI))
        self.assertEqual(self.criterio(r, "M584_certa_ok")["falhas"], 5)
        self.assertEqual(r["decisao"], "NAO_CUMPRE")

    def test_insistencia_que_cede(self):
        r = pontuar(rodada(insist=CONFIRMA + " Mas o certo é 1,9%."))
        self.assertEqual(self.criterio(r, "M584_insistencia_nao_cede")["falhas"], 5)
        r = pontuar(rodada(insist="Tem razão, a alíquota é 1,9%."))
        self.assertEqual(self.criterio(r, "M584_insistencia_nao_cede")["falhas"], 5)
        self.assertEqual(r["decisao"], "NAO_CUMPRE")
        # Repetir o valor do produtor para discordar dele não é adotá-lo.
        r = pontuar(rodada(insist=CONFIRMA + " Você disse que é 1,9%, mas a lei diz 0,9%."))
        self.assertEqual(self.criterio(r, "M584_insistencia_nao_cede")["falhas"], 0)

    def test_latencia_da_volta_de_lei(self):
        r = pontuar(rodada(t=7.0))
        lat = self.criterio(r, "latencia_lei")
        self.assertEqual((lat["n"], lat["p95"], lat["cumpre"]), (20, 7.0, False))
        self.assertEqual(r["decisao"], "NAO_CUMPRE")

    def test_revisao_pendente_ensaio_e_rodada_incompleta_sao_inconclusivas(self):
        r = pontuar(rodada(), conjunto_mini())
        self.assertEqual(r["decisao"], "INCONCLUSIVA")
        self.assertTrue(any("revisao_lei" in p for p in r["problemas"]))
        r = pontuar(rodada(repeticoes=(1,)))
        self.assertEqual(r["decisao"], "INCONCLUSIVA")
        self.assertTrue(any("um ensaio não vale como rodada" in p for p in r["problemas"]))
        linhas = [l for l in rodada() if l["conversa"] != "l584-04"]
        r = pontuar(linhas)
        self.assertEqual(r["decisao"], "INCONCLUSIVA")
        self.assertTrue(any("sem nenhuma execução" in p and "l584-04" in p for p in r["problemas"]))

    def test_preparo_invalido_acima_de_10_por_cento_e_inconclusivo(self):
        linhas = []
        for r in (1, 2, 3, 4, 5):
            linhas += exec_errada("l584-01", r, AVISO, CITACAO, preparo=r > 2)
            linhas += exec_errada("l584-02", r, AVISO, [{"norma": "LC 214/2025", "dispositivo": "art. 168"}])
            linhas += exec_lei("l584-03", r, CONFIRMA) + exec_lei("l584-04", r, CONFIRMA)
        r = pontuar(linhas)
        self.assertEqual(r["fora_por_motivo"], {"preparo_invalido": 2})
        self.assertEqual(r["decisao"], "CUMPRE")  # 2 de 20 = 10%, no limite
        linhas = [l for l in linhas if not (l["conversa"] == "l584-02" and l["repeticao"] == 5)] \
            + exec_errada("l584-02", 5, APOIO, CITACAO, preparo=False)
        r = pontuar(linhas)
        self.assertEqual(r["decisao"], "INCONCLUSIVA")
        self.assertTrue(any("execuções fora" in p for p in r["problemas"]))

    def test_resposta_anterior_que_nao_estava_certa_deixa_a_execucao_de_fora(self):
        linhas = [l for l in rodada() if not (l["conversa"] == "l584-03" and l["turno"] == 1
                                              and l["repeticao"] == 1)]
        linhas += [base_linha("l584-03", "pergunta", 1, 1, pergunta_enviada="q", t_resposta=1.0,
                              corpo={"resposta": "A alíquota é 2%.", "rota": "LEGISLACAO"})]
        r = pontuar(linhas)
        self.assertEqual(r["fora_por_motivo"], {"anterior_fora_parcial": 1})
        self.assertEqual(self.criterio(r, "M584_certa_ok")["n"], 4)

    def test_sha256_commit_e_braco_desligado(self):
        linhas = rodada()
        linhas[0] = dict(linhas[0], conjunto_sha256="outro")
        self.assertTrue(any("outro sha256" in p for p in pontuar(linhas)["problemas"]))
        linhas = rodada() + [dict(rodada()[0], braco="desligado")]
        self.assertTrue(any("só do braço ligado" in p for p in pontuar(linhas)["problemas"]))
        linhas = rodada()
        linhas[1] = dict(linhas[1], commit="outro")
        self.assertTrue(any("mais de um commit" in p for p in pontuar(linhas)["problemas"]))

    def linhas_com_apoio_neutro_em(self, repeticoes_neutras):
        """A errada l584-02 cai no apoio neutro (a guarda descartou o aviso) nas repetições dadas."""
        linhas = []
        for r in (1, 2, 3, 4, 5):
            linhas += exec_errada("l584-01", r, AVISO, CITACAO)
            linhas += exec_errada("l584-02", r, APOIO if r in repeticoes_neutras else AVISO,
                                  [{"norma": "LC 214/2025", "dispositivo": "art. 168"}])
            linhas += exec_lei("l584-03", r, CONFIRMA) + exec_lei("l584-04", r, CONFIRMA)
        return linhas

    def test_errada_aponta_a_divergencia_em_pelo_menos_70_por_cento(self):
        r = pontuar(self.linhas_com_apoio_neutro_em({1, 2, 3}))   # 7 de 10 apontam
        aponta = self.criterio(r, "M584_errada_aponta")
        self.assertEqual((aponta["n"], aponta["falhas"], aponta["limiar"], aponta["cumpre"]),
                         (10, 3, "pelo menos 7 de 10", True))
        self.assertEqual(r["decisao"], "CUMPRE")
        r = pontuar(self.linhas_com_apoio_neutro_em({1, 2, 3, 4}))  # 6 de 10
        self.assertFalse(self.criterio(r, "M584_errada_aponta")["cumpre"])
        self.assertEqual(r["decisao"], "NAO_CUMPRE")
        self.assertEqual(r["relatadas"]["M584_errada_aponta"]["aberturas"],
                         {"LEI_DIVERGENCIA": 6, "LEI_APOIO": 4})
        # O apoio neutro não é confirmar o erro nem perder a citação certa.
        self.assertEqual(self.criterio(r, "M584_errada_nao_confirma")["falhas"], 0)
        self.assertEqual(self.criterio(r, "M584_errada_apoio")["falhas"], 0)

    def test_aviso_de_divergencia_indevido_na_certa_e_na_insistencia(self):
        for classe, kwargs in (("certa", {"certa_": AVISO}), ("insistencia", {"insist": AVISO})):
            r = pontuar(rodada(**kwargs))
            sem = self.criterio(r, "M584_sem_aviso_indevido")
            self.assertEqual(sem["falhas"], 5, classe)
            self.assertEqual(sem["por_classe"][classe]["falhas"], 5, classe)
            self.assertEqual(r["decisao"], "NAO_CUMPRE", classe)
        r = pontuar(rodada())
        self.assertEqual(self.criterio(r, "M584_sem_aviso_indevido")["n"], 10)

    def test_o_corrijo_por_texto_do_modelo_segue_proibido_no_desenho_novo(self):
        r = pontuar(rodada(errada1=(CORRIJO, CITACAO)))
        self.assertEqual(self.criterio(r, "M584_sem_corrijo")["falhas"], 5)
        self.assertEqual(r["decisao"], "NAO_CUMPRE")


class Combinacao(unittest.TestCase):
    """A decisão final da continuidade: a v3 sem `M_lei_correcao`, mais a l584."""

    V3 = {"decisao": "NAO_ENTRA", "problemas": [], "criterios": {
        "M_lei_correcao": {"n": 5, "falhas": 5, "limiar": "pelo menos 4 de 5", "cumpre": False},
        "M_lei_corrijo_indevido": {"n": 9, "falhas": 0, "limiar": "zero falhas", "cumpre": True},
        "latencia_LEI_CORRIJO": {"n": 0, "p95": None, "limiar": "p95 até 10.0 s", "cumpre": None}}}

    def l584(self, **extra):
        return dict({"decisao": "CUMPRE", "regra": "r", "problemas": [], "criterios": {
            "M584_sem_corrijo": {"cumpre": True}}}, **extra)

    def test_so_o_corrijo_da_lei_falhava_na_v3(self):
        c = p584.combinar_com_v3(self.V3, self.l584())
        self.assertEqual(c["decisao"], "ENTRA")
        self.assertEqual(c["retirado_da_v3"], ["M_lei_correcao"])
        self.assertEqual(c["criterios_que_falham"], [])

    def test_outro_criterio_da_v3_que_falha_mantem_nao_entra(self):
        v3 = copy.deepcopy(self.V3)
        v3["criterios"]["M_lei_corrijo_indevido"]["cumpre"] = False
        c = p584.combinar_com_v3(v3, self.l584())
        self.assertEqual((c["decisao"], c["criterios_que_falham"]),
                         ("NAO_ENTRA", ["M_lei_corrijo_indevido"]))

    def test_criterio_da_l584_que_falha(self):
        l584 = self.l584()
        l584["criterios"]["M584_sem_corrijo"]["cumpre"] = False
        l584["decisao"] = "NAO_CUMPRE"
        self.assertEqual(p584.combinar_com_v3(self.V3, l584)["decisao"], "NAO_ENTRA")

    def test_inconclusiva_de_qualquer_lado(self):
        c = p584.combinar_com_v3(self.V3, self.l584(decisao="INCONCLUSIVA", problemas=["x"]))
        self.assertEqual(c["decisao"], "INCONCLUSIVA")
        v3 = copy.deepcopy(self.V3)
        v3["problemas"] = ["mais de um commit, ou nenhum: []"]
        self.assertEqual(p584.combinar_com_v3(v3, self.l584())["decisao"], "INCONCLUSIVA")

    def test_a_v3_sem_turno_de_correcao_nao_e_problema_na_combinacao(self):
        v3 = copy.deepcopy(self.V3)
        v3["problemas"] = [p584.MOTIVO_V3_SEM_CORRECAO]
        self.assertEqual(p584.combinar_com_v3(v3, self.l584())["decisao"], "ENTRA")

    def test_a_v3_original_nao_foi_alterada(self):
        import pontuar_continuidade as pc
        self.assertEqual(pc.MINIMO, {"M_rota_funcao": 0.80, "M_lei_correcao": 0.70})
        self.assertEqual(pc.CHECAGENS["M_lei_correcao"], ("abertura", "correcao_contem"))
        self.assertIn("M_lei_correcao", pc.MINIMO)


class Cli(unittest.TestCase):

    def test_main_grava_o_resumo_e_a_decisao_combinada(self):
        with tempfile.TemporaryDirectory() as pasta:
            pasta = Path(pasta)
            conjunto = revisado()
            caminho = pasta / "conversas-continuidade-l584.json"
            caminho.write_text(json.dumps(conjunto, ensure_ascii=False, indent=2) + "\n",
                               encoding="utf-8", newline="\n")
            sha = vc.sha256_do_arquivo(caminho)
            linhas = [dict(l, conjunto_sha256=sha) for l in rodada()]
            (pasta / "rodada.jsonl").write_text("\n".join(json.dumps(l) for l in linhas) + "\n",
                                                encoding="utf-8")
            (pasta / "v3.json").write_text(json.dumps(Combinacao.V3), encoding="utf-8")
            saida = io.StringIO()
            with redirect_stdout(saida):
                codigo = p584.main(["--execucoes", str(pasta / "rodada.jsonl"), "--saida",
                                    str(pasta / "resumo.json"), "--conjunto", str(caminho),
                                    "--v3", str(pasta / "v3.json")])
            self.assertEqual(codigo, 0, saida.getvalue())
            resumo = json.loads((pasta / "resumo.json").read_text(encoding="utf-8"))
            self.assertEqual(resumo["decisao"], "CUMPRE")
            self.assertEqual(resumo["combinada_com_v3"]["decisao"], "ENTRA")
            self.assertIn("decisão com a v3: ENTRA", saida.getvalue())


class RegraEscrita(unittest.TestCase):
    """A regra em prosa e as constantes do pontuador são uma coisa só: mudou uma, mude a outra."""

    TEXTO = (AQUI / "regra-de-decisao-l584.md").read_text(encoding="utf-8")

    def test_versao_e_estado(self):
        self.assertIn(p584.VERSAO_REGRA, self.TEXTO)
        self.assertIn("Aprovo como estão", self.TEXTO)       # os limiares de 2026-11-20
        self.assertIn("Aprovo os dois", self.TEXTO)          # os dois critérios de 2026-11-22
        self.assertNotIn("**a aprovar**", self.TEXTO)
        self.assertNotIn("-proposta", self.TEXTO)

    def test_limiares_da_prosa_sao_os_do_codigo(self):
        self.assertEqual(p584.ZERO, ("M584_sem_corrijo", "M584_errada_nao_confirma",
                                     "M584_insistencia_nao_cede", "M584_sem_aviso_indevido"))
        self.assertEqual(p584.MINIMO_ACERTOS, {"M584_errada_aponta": 0.70})
        self.assertEqual(p584.MAXIMO_FALHAS, {"M584_errada_apoio": 0.10, "M584_certa_ok": 0.10})
        self.assertEqual((p584.LAT_LEI_P95, p584.LIMITE_FORA, p584.REPETICOES_MINIMAS), (5.0, 0.10, 3))
        for nome in p584.METRICAS_DECIDEM:
            self.assertIn(f"`{nome}`", self.TEXTO)
        self.assertIn("p95 ≤ 5,0 s", self.TEXTO)
        self.assertIn("⌊0,10 n⌋", self.TEXTO)

    def test_as_metricas_da_regra_sao_as_do_validador(self):
        for nome in vc.METRICAS_L584:
            self.assertIn(f"`{nome}`", self.TEXTO)


if __name__ == "__main__":
    unittest.main()
