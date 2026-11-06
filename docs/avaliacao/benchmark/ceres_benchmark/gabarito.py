"""Gabarito unico para as duas arquiteturas (issue #348).

O gabarito da Ceres 2.0 ganhou perguntas e funcoes depois do ``2463c27``. O comando de avaliacao
antigo para *antes* da primeira pergunta se o gabarito cita funcao, parametro ou campo que ele nao
conhece (``ValoresEsperados.validar``). Por isso o executor entrega a Ceres 1 uma copia
**adaptada**: sai so o que ela nao tem como medir, e a adaptacao fica registrada para o relatorio
penalizar a Ceres 1 por *capacidade* no recorte do gabarito inteiro.
"""

from __future__ import annotations

import copy
import re
import unicodedata

_ESCAPE_UNICODE = re.compile(r"\\u([0-9a-fA-F]{4})")
_NAO_ALFANUMERICO = re.compile(r"[^a-z0-9]+")
_CABECALHO_METODO = re.compile(r"public\s+[\w<>\[\],.?\s]+?\s+(\w+)\s*\(")
_IDENTIFICADOR = re.compile(r"(\w+)\s*$")

#: Criterios que a adaptacao pode tirar de uma pergunta (e que o recorte inteiro penaliza).
CRITERIOS_ADAPTAVEIS = ("funcao", "parametros", "numero")
#: Chave do gabarito -> criterio que ela liga.
CRITERIO_DA_CHAVE = {"funcoesEsperadas": "funcao", "parametrosEsperados": "parametros",
                     "camposValor": "numero"}


def criterios_penalizados(adaptacao: dict) -> list[str]:
    """Criterios que a arquitetura nao tinha como entregar (capacidade), na ordem canonica.

    Remocao de ``remocoesExtras`` (seed diferente, nao falta de funcao) nao penaliza. Adaptacao
    gravada antes deste campo: deduzida das chaves removidas.
    """
    if "penaliza" in adaptacao:
        return list(adaptacao["penaliza"])
    return [CRITERIO_DA_CHAVE[k] for k in CRITERIO_DA_CHAVE if k in adaptacao.get("removido", {})]


def normalizar(texto: str) -> str:
    """Igual ao ``SeparacaoTreinoTeste.normalizar`` do Java: sem acento, minusculas, so a-z0-9."""
    texto = _ESCAPE_UNICODE.sub(lambda m: chr(int(m.group(1), 16)), texto)
    sem_acento = "".join(
        c for c in unicodedata.normalize("NFD", texto) if unicodedata.category(c) != "Mn"
    ).lower()
    return _NAO_ALFANUMERICO.sub(" ", sem_acento).strip()


def _fechamento(fonte: str, abertura: int) -> int:
    """Indice do ``)`` que fecha o ``(`` em ``abertura``, pulando strings e text blocks."""
    profundidade = 0
    i = abertura
    while i < len(fonte):
        if fonte.startswith('"""', i):
            fim = fonte.find('"""', i + 3)
            if fim < 0:
                raise ValueError("text block sem fechamento")
            i = fim + 3
            continue
        c = fonte[i]
        if c == '"' or c == "'":
            j = i + 1
            while j < len(fonte) and fonte[j] != c:
                j += 2 if fonte[j] == "\\" else 1
            i = j + 1
            continue
        if c == "(":
            profundidade += 1
        elif c == ")":
            profundidade -= 1
            if profundidade == 0:
                return i
        i += 1
    raise ValueError("parentese sem fechamento")


def _parametros(lista: str) -> list[str]:
    """Nomes dos parametros de uma lista Java (anotacoes com string e generics incluidos)."""
    partes, atual, profundidade, i = [], [], 0, 0
    while i < len(lista):
        if lista.startswith('"""', i):
            fim = lista.find('"""', i + 3) + 3
            atual.append(" ")
            i = fim
            continue
        c = lista[i]
        if c == '"':
            j = i + 1
            while j < len(lista) and lista[j] != '"':
                j += 2 if lista[j] == "\\" else 1
            atual.append(" ")
            i = j + 1
            continue
        if c in "(<":
            profundidade += 1
        elif c in ")>":
            profundidade -= 1
        if c == "," and profundidade == 0:
            partes.append("".join(atual))
            atual = []
        else:
            atual.append(c)
        i += 1
    partes.append("".join(atual))
    nomes = []
    for parte in partes:
        m = _IDENTIFICADOR.search(parte.strip())
        if m:
            nomes.append(m.group(1))
    return nomes


def assinaturas_ferramentas(fonte_java: str) -> dict[str, list[str]]:
    """Funcoes ``@Tool`` de ``FerramentasDadosProdutor.java`` -> nomes dos parametros.

    O nome da funcao e o do metodo (o ``ValoresEsperados`` procura pelo nome do metodo).
    """
    funcoes: dict[str, list[str]] = {}
    for m in re.finditer(r"@Tool\s*\(", fonte_java):
        fim_anotacao = _fechamento(fonte_java, m.end() - 1)
        cabecalho = _CABECALHO_METODO.search(fonte_java, fim_anotacao)
        if cabecalho is None:
            raise ValueError("@Tool sem metodo depois")
        abre = cabecalho.end() - 1
        fecha = _fechamento(fonte_java, abre)
        funcoes[cabecalho.group(1)] = _parametros(fonte_java[abre + 1 : fecha])
    if not funcoes:
        raise ValueError("nenhuma funcao @Tool encontrada")
    return funcoes


def _campo_suportado(campo: str) -> bool:
    # O 2463c27 nao le indice de lista (notas.0.valorIbs): veio na #274.
    return not any(parte.isdigit() for parte in campo.split("."))


def adaptar(
    gabarito: dict,
    funcoes: dict[str, list[str]],
    remocoes_extras: dict[str, list[str]] | None = None,
    indice_de_lista: bool = True,
) -> tuple[dict, dict[str, dict]]:
    """Copia do gabarito sem o que a arquitetura nao sabe medir.

    Regras, na ordem: tira de ``funcoesEsperadas`` a funcao que nao existe; sem a funcao de
    referencia original, ``parametrosEsperados`` e ``camposValor`` saem (o valor esperado e
    calculado por ela); parametro que a funcao de referencia nao tem sai; campo com indice de
    lista sai quando ``indice_de_lista`` e falso; ``remocoes_extras`` (id -> chaves) tira o que
    so a execucao contra o commit revelou (ex.: valor de parametro que a funcao antiga recusa).

    :return: (gabarito adaptado, id -> {"removido": {chave: valor original}, "motivos": [...],
             "penaliza": [criterios removidos por capacidade, fora as remocoes extras]})
    """
    remocoes_extras = remocoes_extras or {}
    adaptado = copy.deepcopy(gabarito)
    adaptacoes: dict[str, dict] = {}
    for pergunta in adaptado["perguntas"]:
        removido: dict[str, object] = {}
        motivos: list[str] = []
        funcoes_q = pergunta.get("funcoesEsperadas") or []
        referencia = funcoes_q[0] if funcoes_q else None
        existentes = [f for f in funcoes_q if f in funcoes]
        if existentes != funcoes_q:
            removido["funcoesEsperadas"] = funcoes_q
            ausentes = [f for f in funcoes_q if f not in funcoes]
            motivos.append("funcao inexistente: " + ", ".join(ausentes))
            pergunta["funcoesEsperadas"] = existentes
        if referencia is not None and referencia not in funcoes:
            for chave in ("parametrosEsperados", "camposValor"):
                if pergunta.get(chave):
                    removido[chave] = pergunta.pop(chave)
        elif referencia is not None and pergunta.get("parametrosEsperados"):
            conhecidos = funcoes[referencia]
            ausentes = [p for p in pergunta["parametrosEsperados"] if p not in conhecidos]
            if ausentes:
                removido["parametrosEsperados"] = pergunta.pop("parametrosEsperados")
                motivos.append(f"parametro inexistente em {referencia}: " + ", ".join(ausentes))
                if pergunta.get("camposValor"):
                    # O valor esperado sem o parametro mediria outra coisa.
                    removido["camposValor"] = pergunta.pop("camposValor")
        if not indice_de_lista and pergunta.get("camposValor"):
            if not all(_campo_suportado(c) for c in pergunta["camposValor"]):
                removido["camposValor"] = pergunta.pop("camposValor")
                motivos.append("campo com indice de lista")
        penaliza = [CRITERIO_DA_CHAVE[k] for k in CRITERIO_DA_CHAVE if k in removido]
        for chave in remocoes_extras.get(pergunta["id"], []):
            if pergunta.get(chave):
                removido[chave] = pergunta.pop(chave)
                motivos.append(f"remocao registrada na matriz: {chave}")
        if removido:
            adaptacoes[pergunta["id"]] = {"removido": removido, "motivos": motivos,
                                          "penaliza": penaliza}
    if adaptacoes:
        adaptado["versao"] = gabarito["versao"] + "-adaptado"
    return adaptado, adaptacoes


def recorte_comum(canonico: dict, antigo: dict, adaptacoes: dict[str, dict]) -> list[str]:
    """Ids da comparacao justa: no gabarito antigo com o mesmo texto e sem adaptacao.

    Uma pergunta adaptada mede menos criterios numa arquitetura que na outra; fica so no recorte
    do gabarito inteiro.
    """
    textos_antigos = {p["id"]: normalizar(p["pergunta"]) for p in antigo["perguntas"]}
    return [
        p["id"]
        for p in canonico["perguntas"]
        if textos_antigos.get(p["id"]) == normalizar(p["pergunta"]) and p["id"] not in adaptacoes
    ]


def aplicavel(pergunta: dict, criterio: str) -> bool:
    """O criterio se aplica a pergunta pelo gabarito canonico (o mesmo do ``AvaliadorResposta``)."""
    return {
        "rota": pergunta.get("rotaEsperada") is not None,
        "funcao": bool(pergunta.get("funcoesEsperadas")),
        "parametros": bool(pergunta.get("parametrosEsperados")),
        "numero": bool(pergunta.get("camposValor")),
        "citacao": bool(pergunta.get("dispositivosEsperados")),
        "recusa": bool(pergunta.get("deveRecusar")),
        "vazamento": bool(pergunta.get("valoresProibidos") or pergunta.get("textosProibidos")),
    }.get(criterio, False)


def recorte_capacidade_comum(canonico: dict, adaptacoes: dict[str, dict],
                             divergentes: set[str] | frozenset[str] = frozenset()) -> list[str]:
    """Ids da comparacao justa do conjunto do benchmark (#352).

    Entra a pergunta ``comum`` (ou sem o campo, no gabarito antigo) que **nenhuma** arquitetura
    precisou adaptar e que nao esta em ``divergentes`` (valor esperado diferente entre os seeds dos
    commits). Adaptada por qualquer motivo, mede menos criterios numa arquitetura que na outra e
    fica so no conjunto inteiro. O executor ja recusa ``comum`` com adaptacao por capacidade.
    """
    return [p["id"] for p in canonico["perguntas"]
            if p.get("capacidade", "comum") == "comum"
            and p["id"] not in adaptacoes and p["id"] not in divergentes]


def conferir_capacidade(canonico: dict, adaptacoes_por_arq: dict[str, dict[str, dict]]) -> list[str]:
    """Divergencias entre o campo ``capacidade`` e o que cada commit tem de fato.

    ``comum`` = nenhuma arquitetura perdeu criterio por falta de funcao; ``so_ceres_2`` = alguma
    perdeu. O executor recusa comecar com divergencia: o recorte justo estaria errado.
    """
    problemas = []
    for p in canonico["perguntas"]:
        if "capacidade" not in p:
            continue
        perdeu = [arq for arq, adapt in adaptacoes_por_arq.items()
                  if p["id"] in adapt and criterios_penalizados(adapt[p["id"]])]
        if p["capacidade"] == "comum" and perdeu:
            problemas.append(f"{p['id']}: marcada comum, mas {', '.join(perdeu)} nao tem a funcao")
        if p["capacidade"] == "so_ceres_2" and not perdeu:
            problemas.append(f"{p['id']}: marcada so_ceres_2, mas todas as arquiteturas tem a funcao")
    return problemas


def so_rota(pergunta: dict) -> bool:
    """A pergunta so mede rota (nenhum outro criterio do gabarito se aplica)."""
    outros = ("funcao", "parametros", "numero", "citacao", "recusa", "vazamento")
    return aplicavel(pergunta, "rota") and not any(aplicavel(pergunta, c) for c in outros)


#: Origens cuja resposta esperada a banca so aceita revisada (#358, #387).
ORIGENS_COM_REVISAO = ("inedita_externa_ia", "inedita_equipe_dados")


def _palavras(nome: str) -> set[str]:
    nome = unicodedata.normalize("NFKD", (nome or "").lower())
    nome = "".join(c for c in nome if not unicodedata.combining(c))
    return set(re.findall(r"\w+", nome))


def revisada(p: dict) -> bool:
    """Revisada pela equipe ou pelo orientador (#387): ``revisado.por`` preenchido e por quem nao
    escreveu a pergunta (qualquer palavra de 3+ letras do ``autor`` no revisor, a regra da
    planilha do repositorio cego). O antigo ``revisado: true`` nao diz quem revisou e nao conta."""
    r = p.get("revisado")
    if not (isinstance(r, dict) and str(r.get("por") or "").strip()):
        return False
    autor = {w for w in _palavras(p.get("autor", "")) if len(w) >= 3}
    return not (autor & _palavras(str(r["por"])))


def perguntas_sem_revisao(perguntas: list[dict]) -> list[str]:
    """Ids que ainda impedem a rodada da banca (e a declaracao do relatorio): ``aConferir`` ou sem
    revisao valida."""
    return [p["id"] for p in perguntas
            if p.get("origem") in ORIGENS_COM_REVISAO and (p.get("aConferir") or not revisada(p))]
