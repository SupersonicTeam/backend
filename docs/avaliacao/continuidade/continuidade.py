"""Peças comuns do executor e da pontuação da continuidade (#499). Só biblioteca padrão.

- Assinatura dos pares, pela seção "Assinatura" da #499: `v1.<carga>.<mac>`, Base64url sem
  preenchimento; MAC = HMAC-SHA256(k, "v1|" + produtor + "|" + segmento da carga + "|" +
  b64url(sha256(pergunta)) + "|" + b64url(sha256(resposta))); k = HMAC-SHA256(chave mestra,
  "agrocontabil:ceres:historico:v1"). O executor confere essa conta contra um turno real antes da
  rodada: se não bater, a rodada não começa.
- Posições da carga (`lei.r` e `t`) em unidades UTF-16 da resposta, como na issue.
- Edições dos pares fabricados, no texto original.
- Condições sobre `fontesDados` (gramática do pontuar.py da #452, mais `situacao` e `numero`),
  marcadores e categorias de texto.
"""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import hmac
import json
import re
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI))
sys.path.insert(0, str(AQUI.parent / "ceres-452"))
import pontuar  # noqa: E402  (condições e tokens da #452)
import validar_conjunto as vc  # noqa: E402  (catálogos e normalização)

ROTULO_CHAVE = b"agrocontabil:ceres:historico:v1"
# A chave pública de desenvolvimento (security/CifraDeterministica.CHAVE_DE_DESENVOLVIMENTO):
# só assina pares locais, contra um banco de dev. A #499 manda assinar com ela.
CHAVE_DEV = "ZGV2LWFncm9jb250YWJpbC1jaGF2ZS1wdWJsaWNhLTM="
NOTA_NA_RESPOSTA = re.compile(r"\bnota (\d+)")
JANELA_ABERTURA = 300

normalizar = vc.normalizar


# ---------------------------------------------------------------------------------------------
# Base64url, UTF-16 e assinatura
# ---------------------------------------------------------------------------------------------


def b64url(dados: bytes) -> str:
    return base64.urlsafe_b64encode(dados).decode("ascii").rstrip("=")


def de_b64url(texto: str) -> bytes:
    return base64.urlsafe_b64decode(texto + "=" * (-len(texto) % 4))


def u16(texto: str) -> int:
    """Tamanho em unidades UTF-16."""
    return len(texto.encode("utf-16-le")) // 2


def indice(texto: str, posicao16: int) -> int:
    """Índice Python do caractere que começa na unidade UTF-16 `posicao16`."""
    if posicao16 < 0:
        raise ValueError("posição negativa")
    contado = 0
    for i, caractere in enumerate(texto):
        if contado == posicao16:
            return i
        contado += 2 if ord(caractere) > 0xFFFF else 1
        if contado > posicao16:
            raise ValueError("posição no meio de um par substituto")
    if contado == posicao16:
        return len(texto)
    raise ValueError("posição além do texto")


def chave_historico(mestra_b64: str = CHAVE_DEV) -> bytes:
    mestra = base64.b64decode(mestra_b64)
    return hmac.new(mestra, ROTULO_CHAVE, hashlib.sha256).digest()


def mac(chave: bytes, produtor: str, segmento: str, pergunta: str, resposta: str) -> str:
    mensagem = "|".join([
        "v1", produtor, segmento,
        b64url(hashlib.sha256(pergunta.encode("utf-8")).digest()),
        b64url(hashlib.sha256(resposta.encode("utf-8")).digest())])
    return b64url(hmac.new(chave, mensagem.encode("utf-8"), hashlib.sha256).digest())


def separar(assinatura: str) -> tuple[str, dict]:
    """Segmento da carga e a carga lida."""
    partes = assinatura.split(".")
    if len(partes) != 3 or partes[0] != "v1":
        raise ValueError("assinatura fora do formato v1.<carga>.<mac>")
    return partes[1], json.loads(de_b64url(partes[1]).decode("utf-8"))


def codificar(carga: dict) -> str:
    return b64url(json.dumps(carga, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def assinar(chave: bytes, produtor: str, carga_ou_segmento, pergunta: str, resposta: str) -> str:
    segmento = carga_ou_segmento if isinstance(carga_ou_segmento, str) \
        else codificar(carga_ou_segmento)
    return f"v1.{segmento}.{mac(chave, produtor, segmento, pergunta, resposta)}"


def confere(chave: bytes, produtor: str, turno: dict) -> bool:
    """A assinatura de um turno real bate com a conta deste arquivo."""
    segmento, _ = separar(turno["assinatura"])
    esperada = assinar(chave, produtor, segmento, turno["pergunta"], turno["resposta"])
    return hmac.compare_digest(esperada, turno["assinatura"])


def exp_vencido(exp_real, agora: dt.datetime):
    """Um `exp` um minuto no passado, na mesma unidade do `exp` real (segundos ou milissegundos)."""
    segundos = int(agora.timestamp()) - 60
    return segundos * 1000 if isinstance(exp_real, (int, float)) and exp_real > 10**12 else segundos


def exp_novo(exp_modelo, agora: dt.datetime):
    """`exp` de um par fixo: 29 minutos à frente, na unidade de um `exp` real."""
    segundos = int(agora.timestamp()) + 29 * 60
    if isinstance(exp_modelo, (int, float)) and exp_modelo > 10**12:
        return segundos * 1000
    if isinstance(exp_modelo, str):
        return (agora + dt.timedelta(minutes=29)).astimezone(dt.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ")
    return segundos


# ---------------------------------------------------------------------------------------------
# Edições dos pares fabricados
# ---------------------------------------------------------------------------------------------


def _intervalo(valor) -> tuple[int, int] | None:
    if isinstance(valor, list) and len(valor) == 2 and all(isinstance(v, int) for v in valor):
        return valor[0], valor[1]
    return None


def aplicar_edicoes(resposta: str, carga: dict, edicoes: list[dict]) -> tuple[str, dict, str]:
    """Aplica as edições no texto original e ajusta `lei.r` e `t`.

    Devolve (resposta nova, carga nova, motivo); motivo vazio = válido.
    """
    carga = json.loads(json.dumps(carga))
    lei = carga.get("lei") if isinstance(carga.get("lei"), dict) else None
    r16 = _intervalo(lei.get("r")) if lei else None
    t = carga.get("t")
    livres = [] if t in (None, "*") else [list(par) for par in t]
    mudou = False
    for edicao in edicoes:
        padrao = re.compile(edicao["regex"])
        if edicao["em"] == "lei.r":
            if r16 is None:
                return resposta, carga, "edição em lei.r sem lei.r na carga"
            ini, fim = indice(resposta, r16[0]), indice(resposta, r16[1])
        else:
            ini, fim = 0, len(resposta)
        trecho = resposta[ini:fim]
        achados = list(padrao.finditer(trecho))
        if not edicao.get("todas"):
            achados = achados[:1]
        if not achados:
            if edicao.get("opcional"):
                continue
            return resposta, carga, f"edição sem ocorrência: {edicao['regex']}"
        # Da última ocorrência para a primeira: as posições anteriores não se mexem.
        for achado in reversed(achados):
            a, b = ini + achado.start(), ini + achado.end()
            novo = achado.expand(edicao["para"])
            a16, b16 = u16(resposta[:a]), u16(resposta[:b])
            delta = u16(novo) - (b16 - a16)
            for par in livres:
                if par[0] < b16 and a16 < par[1]:
                    return resposta, carga, "edição dentro de um texto livre (t)"
                if par[0] >= b16:
                    par[0] += delta
                    par[1] += delta
            if r16 is not None:
                if r16[0] <= a16 and b16 <= r16[1]:
                    r16 = (r16[0], r16[1] + delta)
                elif r16[0] >= b16:
                    r16 = (r16[0] + delta, r16[1] + delta)
                elif not (b16 <= r16[0] or a16 >= r16[1]):
                    return resposta, carga, "edição atravessa a borda de lei.r"
            if novo != resposta[a:b]:
                mudou = True
            resposta = resposta[:a] + novo + resposta[b:]
            if edicao["em"] == "lei.r":
                fim = indice(resposta, r16[1])
    if not mudou:
        return resposta, carga, "nenhuma edição mudou o texto"
    if lei is not None and r16 is not None:
        lei["r"] = [r16[0], r16[1]]
    if livres:
        carga["t"] = livres
    return resposta, carga, ""


# ---------------------------------------------------------------------------------------------
# Marcadores e condições
# ---------------------------------------------------------------------------------------------


def notas_da_resposta(texto: str) -> list[str]:
    """Os números distintos depois de “nota”, na ordem em que aparecem."""
    vistos: list[str] = []
    for numero in NOTA_NA_RESPOSTA.findall(normalizar(texto)):
        if numero not in vistos:
            vistos.append(numero)
    return vistos


def resolver(texto: str, notas_seed: dict[str, str], respostas: dict[int, str]) -> str:
    """Troca os marcadores; levanta ValueError se algum não resolver."""
    def trocar(achado: re.Match) -> str:
        marcador = achado.group(1)
        nota = vc.MARCADOR_NOTA.fullmatch(marcador)
        if nota:
            if nota.group(1) not in notas_seed:
                raise ValueError(f"nota do seed sem número: {marcador}")
            return notas_seed[nota.group(1)]
        resposta = vc.MARCADOR_RESPOSTA.fullmatch(marcador)
        if resposta:
            turno, posicao = int(resposta.group(1)), int(resposta.group(2))
            numeros = notas_da_resposta(respostas.get(turno, ""))
            if len(numeros) < posicao:
                raise ValueError(f"a resposta do turno {turno} não tem {posicao} notas")
            return numeros[posicao - 1]
        raise ValueError(f"marcador desconhecido: {marcador}")
    return vc.MARCADOR.sub(trocar, texto)


def casa(condicao: dict, fonte: dict, hoje: dt.date, notas_seed: dict, respostas: dict) -> bool:
    """A condição da #452, mais `situacao` e `numero`."""
    params = pontuar.parametros(fonte)
    if "situacao" in condicao and str(params.get("situacao", "")).upper() != condicao["situacao"]:
        return False
    if "numero" in condicao:
        try:
            alvo = resolver(str(condicao["numero"]), notas_seed, respostas)
        except ValueError:
            return False
        if str(params.get("numero", "")).strip() != alvo:
            return False
    resto = {k: v for k, v in condicao.items() if k not in ("situacao", "numero")}
    return pontuar.casa(resto, fonte, hoje)


def fontes(corpo: dict | None) -> list[dict]:
    return [f for f in (corpo or {}).get("fontesDados") or [] if isinstance(f, dict)]


def consulta(corpo: dict | None) -> list[str]:
    """Multiconjunto (função, parâmetros em JSON canônico), ordenado.

    Os valores entram como texto: 3290 e "3290" são o mesmo argumento.
    """
    return sorted(json.dumps([f.get("funcao"), {k: str(v) for k, v in
                                                 pontuar.parametros(f).items()}],
                             sort_keys=True, ensure_ascii=False) for f in fontes(corpo))


# ---------------------------------------------------------------------------------------------
# Categorias de texto
# ---------------------------------------------------------------------------------------------


def aparece(texto: str, categoria: str) -> bool:
    return re.search(vc.CATEGORIAS_ABERTURA[categoria], normalizar(texto)) is not None


def abre_com(texto: str, categorias: list[str]) -> bool:
    inicio = normalizar(texto)[:JANELA_ABERTURA]
    return any(re.search(vc.CATEGORIAS_ABERTURA[c], inicio) for c in categorias)


def depois_de(texto: str, categoria: str) -> str:
    """O texto normalizado depois da categoria (vazio se ela não aparece)."""
    normal = normalizar(texto)
    achado = re.search(vc.CATEGORIAS_ABERTURA[categoria], normal)
    return normal[achado.end():] if achado else ""


def classificar_fatos(fatos: dict, texto: str) -> str:
    normal = normalizar(texto)
    if any(re.search(p, normal) for p in fatos["errado_algum"]):
        return "errada"
    if all(re.search(p, normal) for p in fatos["certo_todos"]):
        return "certa"
    return "indefinida"
