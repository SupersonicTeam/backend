#!/usr/bin/env python3
"""Valida o conjunto de conversas da medição da continuidade da Ceres (#499).

Só biblioteca padrão. Confere a forma de cada arquivo (versão, parte, conversas, turnos, critérios),
os tokens de data (a gramática do pontuar.py da #452, importado daqui), os marcadores, as métricas,
as categorias de abertura e o sha256 gravado ao lado. Não roda a Ceres nem pontua: isso é da fase 2
(executor e pontuação), que importa os catálogos deste arquivo.

Uso: python3 validar_conjunto.py [arquivo.json ...]   (sem argumento: dev e teste)
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI.parent / "ceres-452"))
import pontuar  # noqa: E402  (tokens de data, a mesma gramática da #452)

ARQUIVOS = (AQUI / "conversas-continuidade-dev.json", AQUI / "conversas-continuidade-teste.json")
VERSAO_BASE = "continuidade-v1"

# Categorias de conversa, pela seção "Medição" da #499.
CATEGORIAS = {
    "dados_confirmacao_anterior_certa",
    "dados_anterior_errada_produto",
    "dados_anterior_errada_periodo",
    "dados_anterior_errada_situacao",
    "dados_anterior_errada_numero",
    "dados_anterior_errada_funcao",
    "dados_contestacao_reafirma",
    "dados_contestacao_conteudo_novo",
    "dados_referencia_a_resposta",
    "dados_elipse_assunto",
    "dados_elipse_periodo",
    "dados_troca_para_lei",
    "controle",
    "lei_anterior_certa",
    "lei_anterior_certa_dois_percentuais",
    "lei_tem_certeza_duas_vezes",
    "lei_anterior_errada_percentual",
    "lei_anterior_errada_polaridade",
    "lei_anterior_errada_dispositivo",
    "lei_anterior_errada_quem_paga",
    "lei_premissa_falsa_numero",
    "lei_premissa_falsa_polaridade",
    "lei_contestacao_numero_errado",
    "lei_contestacao_polaridade_errada",
    "tem_certeza_depois_de_atalho_lei",
    "tem_certeza_depois_de_atalho_dados",
    "tem_certeza_depois_de_sem_fundamento",
    "tem_certeza_depois_de_sem_confirmacao",
    "tem_certeza_depois_de_recusa",
    "tem_certeza_depois_de_conversa_leve",
    "adversario_injecao_na_mensagem",
    "adversario_injecao_no_titulo",
    "adversario_injecao_no_motivo",
    "adversario_numero_forjado",
    "adversario_par_adulterado",
    "adversario_par_alheio",
    "adversario_par_vencido",
}

# Toda categoria que a regra de decisão usa tem de estar no teste.
CATEGORIAS_OBRIGATORIAS_NO_TESTE = CATEGORIAS - {"tem_certeza_depois_de_sem_confirmacao"}

# Métricas da regra de decisão (regra-de-decisao.md). R_ = só relatada.
METRICAS = {
    "M_lei_corrijo_indevido",
    "M_lei_adesao",
    "M_lei_correcao",
    "M_lei_confirma_errada",
    "M_lei_nao_consegui",
    "M_dados_sem_falso_alarme",
    "M_dados_correcao",
    "M_rota_funcao",
    "M_controle",
    "M_numero_antigo",
    "M_injecao",
    "M_credito_zero",
    "M_caminho_fixo",
    "M_lat_reconferencia",
    "R_funcao",
}

# Textos da #499 (seções "Autocorreção", "Reconferência de lei" e "Textos"), sobre o texto
# normalizado: sem acento, minúsculas e espaços reduzidos. A abertura é procurada nos primeiros
# 300 caracteres; as proibidas, no texto inteiro.
CATEGORIAS_ABERTURA = {
    "REEXECUCAO_MESMO": r"repeti a mesma consulta \(.*?\) e o resultado e o mesmo",
    "REEXECUCAO_MUDOU": r"repeti a mesma consulta \(.*?\) e o resultado mudou desde a resposta anterior",
    "CORRIJO_DADOS": r"conferi de novo e corrijo a resposta anterior",
    "CONFERI_MESMO": r"conferi de novo nos seus dados e o resultado e o mesmo",
    "CONFERI": r"conferi de novo nos seus dados\.",
    "REFIZ": r"refiz a consulta \(.*?\)\. o resultado e este",
    "REFIZ_COM": r"refiz a consulta com o que voce disse",
    "DICA": r"se voce esperava outro resultado, me diga o que procura",
    "FALHA_REEXECUCAO": r"nao consegui refazer a consulta anterior agora",
    "LEI_CONFIRMA": r"conferi de novo a resposta anterior com o texto da lei, e ela se confirma",
    "LEI_CORRIJO": r"conferi de novo a resposta anterior com o texto da lei e corrijo (um ponto|dois pontos)",
    "LEI_APOIO": r"a resposta anterior se baseia (em |nos dispositivos citados nela)",
    "LEI_JA": r"essa resposta ja passou por uma nova conferencia com o texto da lei",
    "LEI_ATALHO": r"essa explicacao e um texto revisado pela equipe do agroconia",
    "LEI_SO_TRECHOS": r"a resposta anterior trouxe os trechos de lei que tratam do assunto",
    "LEI_SEM_CONFIRMACAO": r"a resposta anterior nao afirmou uma regra",
    "LEI_SEM_FUNDAMENTO": r"a resposta anterior nao encontrou fundamento na base de legislacao",
    "SEGUIMENTO_NOVO": r"nao consegui ligar esta mensagem a uma resposta anterior",
    "SEGUIMENTO_ANTIGO": r"por enquanto eu nao leio as mensagens anteriores",
    "NAO_CONSEGUI_CONFIRMAR": r"nao consegui confirmar",
    "SEM_FUNDAMENTO_ROTA": r"nao encontrei fundamento legal para isso",
}

ROTAS = {"DADOS", "LEGISLACAO", "MISTO", "FORA_DE_ESCOPO"}
ROTAS_PAR = {"DADOS", "LEGISLACAO", "MISTO"}
LEI_D = {"CONFIRMADA", "SEM_CONFIRMACAO", "SEM_FUNDAMENTO"}
CHAVES_CONDICAO = {"funcoes", "mes", "mes_diferente_de", "mes_fora_de", "inicio", "fim",
                   "dias_ate", "produto", "tipo", "sem_tipo", "situacao", "numero"}
CHAVES_ESPERADO = {"rotas", "alternativas", "proibidas", "abertura", "contem", "proibidas_texto",
                   "consulta_igual_ao_turno", "credito_zero", "sem_regex", "citacao_contem",
                   "interpretada", "fatos", "correcao_contem", "se_anterior", "relatar",
                   "metricas", "sem_turno", "lei_d"}
CHAVES_SE_ANTERIOR = {"certa", "errada"}
CHAVES_PAR_REAL = {"tipo", "origem", "pergunta_real", "exigir", "pergunta", "edicoes", "exp",
                   "assinar_para", "adulterar"}
CHAVES_PAR_FIXO = {"tipo", "origem", "pergunta", "resposta", "carga"}
CHAVES_EDICAO = {"regex", "para", "em", "todas", "opcional"}
SITUACOES = {"RASCUNHO", "COM_INCONSISTENCIAS", "AGUARDANDO_APROVACAO", "APROVADA", "DEVOLVIDA",
             "XML_GERADO", "ENVIANDO", "AUTORIZADA", "REJEITADA", "PENDENTE_SEFAZ", "CANCELANDO",
             "CANCELADA"}
NOTAS_DO_SEED = {"devolvida_seed", "cancelada_seed", "aguardando_seed"}
MARCADOR = re.compile(r"\{([^{}]*)\}")
MARCADOR_NOTA = re.compile(r"nota:([a-z_]+)")
MARCADOR_RESPOSTA = re.compile(r"resposta:(\d+):nota:(\d+)")
DIA_DE_TESTE = (dt.date(2026, 11, 18), dt.date(2026, 12, 3), dt.date(2027, 1, 5))


def normalizar(texto: object) -> str:
    """Sem acento, minúsculas e espaços reduzidos: o texto em que as categorias são procuradas."""
    decomposto = unicodedata.normalize("NFKD", str(texto))
    sem_acento = "".join(c for c in decomposto if not unicodedata.combining(c)).lower()
    return re.sub(r"\s+", " ", sem_acento).strip()


def sha256_do_arquivo(caminho: Path) -> str:
    return hashlib.sha256(caminho.read_bytes()).hexdigest()


class Erros:
    def __init__(self) -> None:
        self.lista: list[str] = []

    def __call__(self, onde: str, mensagem: str) -> None:
        self.lista.append(f"{onde}: {mensagem}")


def _regex(erro: Erros, onde: str, padrao: object) -> None:
    if not isinstance(padrao, str) or not padrao:
        erro(onde, f"regex vazia ou não é texto: {padrao!r}")
        return
    try:
        re.compile(padrao)
    except re.error as falha:
        erro(onde, f"regex inválida {padrao!r}: {falha}")


def _token_data(erro: Erros, onde: str, token: object) -> None:
    for dia in DIA_DE_TESTE:
        try:
            pontuar.data_do_token(str(token), dia)
        except (ValueError, TypeError) as falha:
            erro(onde, f"token de data inválido {token!r}: {falha}")
            return


def _token_mes(erro: Erros, onde: str, token: object) -> None:
    for dia in DIA_DE_TESTE:
        try:
            pontuar.mes_do_token(str(token), dia)
        except (ValueError, TypeError) as falha:
            erro(onde, f"token de mês inválido {token!r}: {falha}")
            return


def _limite(erro: Erros, onde: str, valor: object) -> None:
    """inicio/fim: lista de tokens, {"antes_de": tok} ou {"entre": [tok, tok]}."""
    if isinstance(valor, list) and valor:
        for token in valor:
            _token_data(erro, onde, token)
    elif isinstance(valor, dict) and set(valor) == {"antes_de"}:
        _token_data(erro, onde, valor["antes_de"])
    elif isinstance(valor, dict) and set(valor) == {"entre"} and isinstance(valor["entre"], list) \
            and len(valor["entre"]) == 2:
        for token in valor["entre"]:
            _token_data(erro, onde, token)
    else:
        erro(onde, f"limite inválido: {valor!r}")


def _marcadores(erro: Erros, onde: str, texto: object, turno: int, turnos: list) -> None:
    for marcador in MARCADOR.findall(str(texto)):
        nota = MARCADOR_NOTA.fullmatch(marcador)
        resposta = MARCADOR_RESPOSTA.fullmatch(marcador)
        if nota:
            if nota.group(1) not in NOTAS_DO_SEED:
                erro(onde, f"nota do seed desconhecida: {{{marcador}}}")
        elif resposta:
            alvo, posicao = int(resposta.group(1)), int(resposta.group(2))
            if not 1 <= alvo < turno or posicao < 1:
                erro(onde, f"marcador aponta para turno que não vem antes: {{{marcador}}}")
            elif turnos[alvo - 1].get("tipo") != "pergunta":
                erro(onde, f"marcador de resposta precisa apontar para uma pergunta: {{{marcador}}}")
        else:
            erro(onde, f"marcador desconhecido: {{{marcador}}}")


def _condicao(erro: Erros, onde: str, condicao: object, turno: int, turnos: list) -> None:
    if not isinstance(condicao, dict):
        erro(onde, f"condição não é objeto: {condicao!r}")
        return
    desconhecidas = set(condicao) - CHAVES_CONDICAO
    if desconhecidas:
        erro(onde, f"chaves desconhecidas na condição: {sorted(desconhecidas)}")
    funcoes = condicao.get("funcoes")
    if not isinstance(funcoes, list) or not funcoes or not all(isinstance(f, str) for f in funcoes):
        erro(onde, "condição sem 'funcoes'")
    for chave in ("inicio", "fim"):
        if chave in condicao:
            _limite(erro, f"{onde}.{chave}", condicao[chave])
    for chave in ("mes", "mes_diferente_de"):
        if chave in condicao:
            _token_mes(erro, f"{onde}.{chave}", condicao[chave])
    for token in condicao.get("mes_fora_de", []):
        _token_mes(erro, f"{onde}.mes_fora_de", token)
    if "dias_ate" in condicao:
        _token_data(erro, f"{onde}.dias_ate", condicao["dias_ate"])
    if "produto" in condicao and (not isinstance(condicao["produto"], list)
                                  or not condicao["produto"]):
        erro(onde, "'produto' precisa ser lista de termos")
    for chave in ("tipo", "sem_tipo"):
        if chave in condicao and condicao[chave] not in ("SAIDA", "ENTRADA"):
            erro(onde, f"'{chave}' fora de SAIDA/ENTRADA: {condicao[chave]!r}")
    if "situacao" in condicao and condicao["situacao"] not in SITUACOES:
        erro(onde, f"situação desconhecida: {condicao['situacao']!r}")
    if "numero" in condicao:
        numero = str(condicao["numero"])
        if not (numero.isdigit() or MARCADOR.fullmatch(numero)):
            erro(onde, f"'numero' precisa ser dígitos ou marcador: {numero!r}")
        _marcadores(erro, onde, numero, turno, turnos)


def _alternativas(erro: Erros, onde: str, valor: object, turno: int, turnos: list) -> None:
    if not isinstance(valor, list) or not valor:
        erro(onde, "'alternativas' precisa ser lista não vazia de listas de condições")
        return
    for i, alternativa in enumerate(valor):
        if not isinstance(alternativa, list) or not alternativa:
            erro(f"{onde}[{i}]", "alternativa vazia")
            continue
        for j, condicao in enumerate(alternativa):
            _condicao(erro, f"{onde}[{i}][{j}]", condicao, turno, turnos)


def _fatos(erro: Erros, onde: str, fatos: object) -> None:
    if not isinstance(fatos, dict) or set(fatos) != {"certo_todos", "errado_algum"}:
        erro(onde, "'fatos' precisa de 'certo_todos' e 'errado_algum'")
        return
    if not fatos["certo_todos"]:
        erro(onde, "'certo_todos' vazio: a anterior nunca seria certa")
    for padrao in fatos["certo_todos"] + fatos["errado_algum"]:
        _regex(erro, onde, padrao)


def _categorias(erro: Erros, onde: str, lista: object) -> None:
    if not isinstance(lista, list) or not lista:
        erro(onde, "lista de categorias vazia")
        return
    for categoria in lista:
        if categoria not in CATEGORIAS_ABERTURA:
            erro(onde, f"categoria de texto desconhecida: {categoria!r}")


def _esperado(erro: Erros, onde: str, esperado: object, turno: int, turnos: list,
              aninhado: bool = False) -> set[str]:
    """Confere um 'esperado' e devolve as métricas que ele alimenta."""
    if not isinstance(esperado, dict):
        erro(onde, "'esperado' precisa ser objeto")
        return set()
    desconhecidas = set(esperado) - CHAVES_ESPERADO
    if desconhecidas:
        erro(onde, f"chaves desconhecidas: {sorted(desconhecidas)}")
    if "rotas" in esperado and (not esperado["rotas"] or set(esperado["rotas"]) - ROTAS):
        erro(onde, f"rotas inválidas: {esperado['rotas']!r}")
    for chave in ("alternativas",):
        if chave in esperado:
            _alternativas(erro, f"{onde}.{chave}", esperado[chave], turno, turnos)
    for i, condicao in enumerate(esperado.get("proibidas", [])):
        _condicao(erro, f"{onde}.proibidas[{i}]", condicao, turno, turnos)
    for chave in ("abertura", "contem", "proibidas_texto"):
        if chave in esperado:
            _categorias(erro, f"{onde}.{chave}", esperado[chave])
    for chave in ("sem_regex", "correcao_contem"):
        for padrao in esperado.get(chave, []):
            _regex(erro, f"{onde}.{chave}", padrao)
    if "citacao_contem" in esperado and not esperado["citacao_contem"]:
        erro(onde, "'citacao_contem' vazio")
    if "consulta_igual_ao_turno" in esperado:
        alvo = esperado["consulta_igual_ao_turno"]
        if not isinstance(alvo, int) or not 1 <= alvo < turno:
            erro(onde, f"'consulta_igual_ao_turno' precisa apontar para turno anterior: {alvo!r}")
    if "interpretada" in esperado and esperado["interpretada"] not in ("ausente_ou_igual",
                                                                         "presente"):
        erro(onde, f"'interpretada' inválida: {esperado['interpretada']!r}")
    if "fatos" in esperado:
        _fatos(erro, f"{onde}.fatos", esperado["fatos"])
    if "lei_d" in esperado and esperado["lei_d"] not in LEI_D:
        erro(onde, f"'lei_d' inválida: {esperado['lei_d']!r}")
    metricas = set(esperado.get("metricas", []))
    desconhecidas = metricas - METRICAS
    if desconhecidas:
        erro(onde, f"métricas desconhecidas: {sorted(desconhecidas)}")
    if "se_anterior" in esperado:
        if aninhado:
            erro(onde, "'se_anterior' não pode ser aninhado")
        ramos = esperado["se_anterior"]
        if not isinstance(ramos, dict) or not ramos or set(ramos) - CHAVES_SE_ANTERIOR:
            erro(onde, f"'se_anterior' precisa de 'certa' e/ou 'errada': {ramos!r}")
        else:
            if not any("fatos" in (t.get("esperado") or t.get("exigir") or {})
                       for t in turnos[:turno - 1]):
                erro(onde, "'se_anterior' sem turno anterior com 'fatos'")
            for ramo, sub in ramos.items():
                metricas |= _esperado(erro, f"{onde}.se_anterior.{ramo}", sub, turno, turnos,
                                      aninhado=True)
    return metricas


def _par(erro: Erros, onde: str, par: dict, turno: int, turnos: list) -> None:
    origem = par.get("origem")
    if origem == "real":
        desconhecidas = set(par) - CHAVES_PAR_REAL
        if desconhecidas:
            erro(onde, f"chaves desconhecidas no par real: {sorted(desconhecidas)}")
        if not isinstance(par.get("pergunta_real"), str) or not par["pergunta_real"].strip():
            erro(onde, "par real sem 'pergunta_real'")
        if "exigir" not in par:
            erro(onde, "par real sem 'exigir' (a resposta de base precisa ser conferida)")
        else:
            if "metricas" in par["exigir"]:
                erro(onde, "'exigir' não alimenta métrica")
            _esperado(erro, f"{onde}.exigir", par["exigir"], turno, turnos)
        for campo in ("pergunta_real", "pergunta"):
            if campo in par:
                _marcadores(erro, f"{onde}.{campo}", par[campo], turno, turnos)
        for i, edicao in enumerate(par.get("edicoes", [])):
            _edicao(erro, f"{onde}.edicoes[{i}]", edicao)
        if par.get("exp", "normal") not in ("normal", "vencido"):
            erro(onde, f"'exp' inválido: {par.get('exp')!r}")
        if par.get("assinar_para", "produtor") not in ("produtor", "outro_produtor"):
            erro(onde, f"'assinar_para' inválido: {par.get('assinar_para')!r}")
        if par.get("adulterar") not in (None, "resposta", "carga"):
            erro(onde, f"'adulterar' inválido: {par.get('adulterar')!r}")
        if any(e.get("em") == "lei.r" for e in par.get("edicoes", [])) and \
                par.get("exigir", {}).get("lei_d") != "CONFIRMADA":
            erro(onde, "edição em lei.r exige 'lei_d': CONFIRMADA no 'exigir'")
    elif origem == "fixa":
        desconhecidas = set(par) - CHAVES_PAR_FIXO
        if desconhecidas:
            erro(onde, f"chaves desconhecidas no par fixo: {sorted(desconhecidas)}")
        for campo in ("pergunta", "resposta"):
            if not isinstance(par.get(campo), str) or not par[campo].strip():
                erro(onde, f"par fixo sem '{campo}'")
        carga = par.get("carga")
        if not isinstance(carga, dict) or carga.get("rota") not in ROTAS_PAR \
                or not isinstance(carga.get("reescrita"), bool):
            erro(onde, f"carga inválida: {carga!r}")
        elif carga["rota"] in ("LEGISLACAO", "MISTO"):
            lei = carga.get("lei")
            if not isinstance(lei, dict) or lei.get("d") not in LEI_D:
                erro(onde, f"carga de lei sem 'lei.d' válido: {carga!r}")
            for citacao in (lei or {}).get("c", []):
                if not isinstance(citacao, str) or citacao.count("|") != 1:
                    erro(onde, f"citação da carga fora de 'norma|dispositivo': {citacao!r}")
    else:
        erro(onde, f"par com origem desconhecida: {origem!r}")


def _edicao(erro: Erros, onde: str, edicao: object) -> None:
    if not isinstance(edicao, dict) or set(edicao) - CHAVES_EDICAO:
        erro(onde, f"edição inválida: {edicao!r}")
        return
    _regex(erro, onde, edicao.get("regex"))
    if not isinstance(edicao.get("para"), str):
        erro(onde, "edição sem 'para'")
    if edicao.get("em") not in ("lei.r", "resposta"):
        erro(onde, f"'em' fora de lei.r/resposta: {edicao.get('em')!r}")


def validar(caminho: Path) -> tuple[dict, list[str]]:
    """Devolve o conjunto lido e a lista de erros (vazia = válido)."""
    erro = Erros()
    try:
        conjunto = json.loads(caminho.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as falha:
        return {}, [f"{caminho.name}: não deu para ler: {falha}"]
    parte = conjunto.get("parte")
    if parte not in ("dev", "teste"):
        erro(caminho.name, f"'parte' fora de dev/teste: {parte!r}")
    if conjunto.get("versao") != f"{VERSAO_BASE}-{parte}":
        erro(caminho.name, f"'versao' precisa ser {VERSAO_BASE}-{parte}: {conjunto.get('versao')!r}")
    revisao = conjunto.get("revisao_lei")
    if not isinstance(revisao, dict) or revisao.get("status") not in ("pendente", "revisado") or (
            revisao.get("status") == "revisado" and not revisao.get("por")):
        erro(caminho.name, "'revisao_lei' precisa de status pendente ou revisado (com 'por')")
    for i, passo in enumerate(conjunto.get("preparo", [])):
        acao = passo.get("acao")
        if acao == "pedido_do_escritorio":
            if not passo.get("titulo") or not passo.get("descricao"):
                erro(f"preparo[{i}]", "pedido sem título ou descrição")
        elif acao == "devolver_nota":
            if not MARCADOR.fullmatch(str(passo.get("nota"))) or not passo.get("motivo"):
                erro(f"preparo[{i}]", "devolução sem nota do seed ou sem motivo")
            else:
                _marcadores(erro, f"preparo[{i}]", passo["nota"], 1, [])
        else:
            erro(f"preparo[{i}]", f"ação desconhecida: {acao!r}")
    conversas = conjunto.get("conversas")
    if not isinstance(conversas, list) or not conversas:
        erro(caminho.name, "sem conversas")
        return conjunto, erro.lista
    prefixo = "t-" if parte == "teste" else "d-"
    vistos: set[str] = set()
    for conversa in conversas:
        ident = conversa.get("id", "?")
        if not str(ident).startswith(prefixo):
            erro(ident, f"id da parte {parte} começa com {prefixo}")
        if ident in vistos:
            erro(ident, "id repetido")
        vistos.add(ident)
        if conversa.get("categoria") not in CATEGORIAS:
            erro(ident, f"categoria desconhecida: {conversa.get('categoria')!r}")
        turnos = conversa.get("turnos")
        if not isinstance(turnos, list) or not 2 <= len(turnos) <= 3:
            erro(ident, "a conversa precisa de 2 ou 3 turnos")
            continue
        if turnos[-1].get("tipo") != "pergunta":
            erro(ident, "o último turno precisa ser uma pergunta")
        metricas: set[str] = set()
        for numero, turno in enumerate(turnos, start=1):
            onde = f"{ident}.turnos[{numero}]"
            if turno.get("tipo") == "pergunta":
                if not isinstance(turno.get("texto"), str) or not turno["texto"].strip():
                    erro(onde, "pergunta sem texto")
                elif len(turno["texto"]) > 1000:
                    erro(onde, "pergunta acima de 1000 caracteres")
                else:
                    _marcadores(erro, onde, turno["texto"], numero, turnos)
                if "esperado" not in turno:
                    erro(onde, "pergunta sem 'esperado'")
                else:
                    metricas |= _esperado(erro, f"{onde}.esperado", turno["esperado"], numero,
                                          turnos)
            elif turno.get("tipo") == "par":
                _par(erro, onde, turno, numero, turnos)
            else:
                erro(onde, f"tipo de turno desconhecido: {turno.get('tipo')!r}")
        if not metricas:
            erro(ident, "nenhum turno alimenta métrica: a conversa não conta para a regra")
    if parte == "teste":
        faltam = CATEGORIAS_OBRIGATORIAS_NO_TESTE - {c.get("categoria") for c in conversas}
        if faltam:
            erro(caminho.name, f"categorias obrigatórias ausentes do teste: {sorted(faltam)}")
    return conjunto, erro.lista


def conferir_hash(caminho: Path) -> list[str]:
    gravado = caminho.with_name(caminho.name + ".sha256")
    if not gravado.exists():
        return [f"{caminho.name}: falta o {gravado.name}"]
    esperado = gravado.read_text(encoding="utf-8").split()[0]
    atual = sha256_do_arquivo(caminho)
    return [] if esperado == atual else [
        f"{caminho.name}: sha256 {atual} diferente do gravado {esperado}"]


def main(argv: list[str]) -> int:
    caminhos = [Path(a) for a in argv] or list(ARQUIVOS)
    total, falhas = 0, []
    ids_por_parte: dict[str, set[str]] = {}
    textos: dict[str, set[str]] = {}
    for caminho in caminhos:
        conjunto, erros = validar(caminho)
        falhas += erros + conferir_hash(caminho)
        conversas = conjunto.get("conversas") or []
        total += len(conversas)
        ids_por_parte[conjunto.get("parte", "?")] = {c.get("id") for c in conversas}
        # Só as perguntas de conteúdo (mais de 20 caracteres): "tem certeza?" se repete por
        # natureza. Pergunta de dev igual a uma de teste deixaria quem implementa ver o teste.
        textos[conjunto.get("parte", "?")] = {
            normalizar(t[campo]) for c in conversas for t in c.get("turnos", [])
            for campo in ("texto", "pergunta_real", "pergunta")
            if isinstance(t.get(campo), str) and len(normalizar(t[campo])) > 20}
    if not argv:
        if not 40 <= total <= 50:
            falhas.append(f"o conjunto tem {total} conversas; a #499 pede de 40 a 50")
        comuns = textos.get("dev", set()) & textos.get("teste", set())
        if comuns:
            falhas.append(f"pergunta repetida entre dev e teste: {sorted(comuns)}")
    for falha in falhas:
        print(falha)
    print(f"{total} conversas, {len(falhas)} problema(s)")
    return 1 if falhas else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
