#!/usr/bin/env python3
"""Harness da Ceres contra o ambiente publicado (issue #264, item 7).

Roda as perguntas do gabarito pela API publica (login + POST /api/assistente/chat) e mede a
latencia real, de ponta a ponta, como o produtor a sente. Nao avalia numero nem citacao: isso e do
comando `dev,avaliacao`, que conhece o seed. Aqui o que conta e o tempo e a rota.

Nada de URL, conta ou senha no repositorio: tudo por variavel de ambiente.

    AGRO_API_URL        ex.: https://www.exemplo.com.br (sem barra no fim)          [obrigatoria]
    AGRO_EMAIL          e-mail de uma conta de PRODUTOR   [obrigatoria, salvo com AGRO_TOKEN]
    AGRO_SENHA          senha dessa conta                 [obrigatoria, salvo com AGRO_TOKEN]
    AGRO_TOKEN          access token ja emitido (JWT, 15 min): dispensa e-mail e senha; sem
                        novo login, a medicao para com "token expirado" no primeiro 401 (#497)
    AGRO_USO_MAXIMO     percentualUsado da cota semanal (GET /api/assistente/uso) que para a
                        medicao (padrao 50); o uso antes e depois entra no resultado (#497)
    AGRO_BASIC_USUARIO  usuario do Basic Auth do Caddy da demonstracao (opcional)
    AGRO_BASIC_SENHA    senha do Basic Auth (opcional; o login passa por ele)
    AGRO_REPETICOES     rodadas (padrao 3)
    AGRO_IDS            ids do gabarito separados por virgula (padrao: todos)
    AGRO_GABARITO       caminho do gabarito (padrao docs/avaliacao/gabarito-ceres.json)
    AGRO_SAIDA          diretorio de saida (padrao docs/avaliacao/resultados)

Uso (da raiz do repositorio):

    AGRO_API_URL=... AGRO_EMAIL=... AGRO_SENHA=... python3 -I docs/avaliacao/harness-api/harness.py

Cada pergunta gasta creditos da cota semanal da Ceres (#166). Com 429 LIMITE_USO_ASSISTENTE o
harness para (esperar a semana virar nao cabe numa medicao); com 429 MUITAS_TENTATIVAS ou 503
ASSISTENTE_OCUPADO, espera o Retry-After e tenta de novo.
"""

import base64
import datetime
import json
import socket
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
import zoneinfo

# Maior que o timeout da pergunta no servidor (app.assistente.timeout-pergunta, 5 min).
TIMEOUT_S = 330
TIMEOUT_AUXILIAR_S = 30  # GET de uso e de health: nunca seguram a medicao por minutos
MAX_FALHAS_USO = 2
FUSO = zoneinfo.ZoneInfo("America/Sao_Paulo")
MAX_ESPERAS = 5


def obrigatoria(nome):
    valor = os.environ.get(nome, "").strip()
    if not valor:
        sys.exit(f"Defina a variavel de ambiente {nome}")
    return valor


def requisitar(url, corpo, cabecalhos, metodo="POST", timeout=TIMEOUT_S):
    dados = json.dumps(corpo).encode("utf-8") if corpo is not None else None
    req = urllib.request.Request(url, data=dados, method=metodo, headers={
        "Content-Type": "application/json", "Accept": "application/json", **cabecalhos})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read() or b"{}"), resp.headers
    except urllib.error.HTTPError as erro:
        try:
            corpo_erro = json.loads(erro.read() or b"{}")
        except ValueError:
            corpo_erro = {}
        return erro.code, corpo_erro, erro.headers
    except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, ValueError) as erro:
        # Rede, tempo esgotado ou corpo que nao e JSON: vira resultado da pergunta, nunca derruba
        # a medicao inteira (o que ja foi medido seria perdido).
        return 0, {"errors": [{"code": "FALHA_" + type(erro).__name__}]}, {}


def codigo_erro(corpo):
    erros = corpo.get("errors") or [{}]
    return erros[0].get("code", "")


def login(base, email, senha):
    cabecalhos = {}
    usuario = os.environ.get("AGRO_BASIC_USUARIO")
    if usuario:
        par = f"{usuario}:{os.environ.get('AGRO_BASIC_SENHA', '')}".encode("utf-8")
        cabecalhos["Authorization"] = "Basic " + base64.b64encode(par).decode("ascii")
    status, corpo, _ = requisitar(base + "/api/auth/login", {"email": email, "senha": senha},
                                  cabecalhos)
    if status != 200 or "accessToken" not in corpo:
        # Nunca imprime a senha nem o corpo da resposta.
        sys.exit(f"Login falhou: HTTP {status} {codigo_erro(corpo)}")
    return corpo["accessToken"]


def perguntar(base, token, pergunta, token_fixo=False):
    for _ in range(MAX_ESPERAS + 1):
        inicio = time.monotonic()
        status, corpo, cabecalhos = requisitar(base + "/api/assistente/chat",
                                               {"pergunta": pergunta},
                                               {"Authorization": "Bearer " + token})
        latencia_ms = round((time.monotonic() - inicio) * 1000)
        codigo = codigo_erro(corpo) if status >= 400 else ""
        if codigo == "LIMITE_USO_ASSISTENTE":
            raise CotaEsgotada()
        if status == 401 and token_fixo:
            raise TokenExpirado()
        if status in (429, 503) and codigo in ("MUITAS_TENTATIVAS", "ASSISTENTE_OCUPADO"):
            time.sleep(int(cabecalhos.get("Retry-After", "5")))
            continue
        return {"status": status, "codigo": codigo, "latenciaMs": latencia_ms,
                "rota": corpo.get("rota"), "modoFallback": corpo.get("modoFallback"),
                "citacoes": len(corpo.get("citacoes") or []),
                "fontesDados": len(corpo.get("fontesDados") or []),
                "resposta": corpo.get("resposta")}
    return {"status": status, "codigo": codigo, "latenciaMs": latencia_ms, "rota": None}


class TokenExpirado(Exception):
    """401 com AGRO_TOKEN: nao ha senha para um novo login; a medicao para (#497)."""


class UsoIndisponivel(Exception):
    """Nao foi possivel ler GET /api/assistente/uso: sem leitura, nao ha protecao da cota."""


class UsoAcimaDoLimite(Exception):
    """percentualUsado da cota passou do teto (AGRO_USO_MAXIMO): a medicao para (#497)."""


def percentual_usado(base, token):
    """Le GET /api/assistente/uso; None se nao conseguir (nunca derruba a medicao)."""
    status, corpo, _ = requisitar(base + "/api/assistente/uso", None,
                                  {"Authorization": "Bearer " + token}, "GET",
                                  TIMEOUT_AUXILIAR_S)
    if status != 200:
        return None
    return corpo.get("percentualUsado")


class GuardaDeCota:
    """Para a medicao quando o uso passa do teto, e tambem quando nao da para le-lo.

    Uma leitura que falha nunca vale como 0%: sem ler, a medicao nao esta protegida (#497).
    """

    def __init__(self, base, token, maximo):
        self.base, self.token, self.maximo = base, token, maximo
        self.antes = self.depois = None
        self.falhas = 0

    def inicio(self):
        self.antes = self.depois = percentual_usado(self.base, self.token)
        if self.antes is None:
            raise UsoIndisponivel()
        if self.antes > self.maximo:
            raise UsoAcimaDoLimite()

    def apos_pergunta(self):
        valor = percentual_usado(self.base, self.token)
        if valor is None:
            self.falhas += 1
            if self.falhas >= MAX_FALHAS_USO:
                raise UsoIndisponivel()
            return
        self.falhas = 0
        self.depois = valor  # so um valor lido; a falha nunca apaga o ultimo bom
        if valor > self.maximo:
            raise UsoAcimaDoLimite()

    def como_dict(self):
        return {"antes": self.antes, "depois": self.depois, "maximo": self.maximo}


def baseline_rede(base, amostras=7):
    """Tempo de GET /api/v1/health (publico, sem modelo): rede + TLS + Caddy + backend."""
    tempos = []
    for _ in range(amostras):
        inicio = time.monotonic()
        status, _, _ = requisitar(base + "/api/v1/health", None, {}, "GET",
                                  TIMEOUT_AUXILIAR_S)
        if status == 200:
            tempos.append(round((time.monotonic() - inicio) * 1000))
    if not tempos:
        return {"amostras": 0, "p50Ms": None, "maxMs": None}  # nao medido, nunca 0 ms
    return {"amostras": len(tempos), "p50Ms": percentil(tempos, 50), "maxMs": max(tempos)}


class CotaEsgotada(Exception):
    """429 LIMITE_USO_ASSISTENTE: a medicao para, gravando o que ja foi medido."""


def percentil(valores, p):
    if not valores:
        return 0
    ordenados = sorted(valores)
    posicao = max(1, -(-p * len(ordenados) // 100))  # nearest-rank, igual ao relatorio Java
    return ordenados[posicao - 1]


def main():
    base = obrigatoria("AGRO_API_URL").rstrip("/")
    token_fixo = os.environ.get("AGRO_TOKEN", "").strip()
    email = "" if token_fixo else obrigatoria("AGRO_EMAIL")
    senha = "" if token_fixo else obrigatoria("AGRO_SENHA")
    uso_maximo = int(os.environ.get("AGRO_USO_MAXIMO", "50"))
    repeticoes = int(os.environ.get("AGRO_REPETICOES", "3"))
    gabarito_arquivo = os.environ.get("AGRO_GABARITO", "docs/avaliacao/gabarito-ceres.json")
    saida = os.environ.get("AGRO_SAIDA", "docs/avaliacao/resultados")
    ids = {i.strip() for i in os.environ.get("AGRO_IDS", "").split(",") if i.strip()}

    with open(gabarito_arquivo, encoding="utf-8") as arquivo:
        gabarito = json.load(arquivo)
    perguntas = [p for p in gabarito["perguntas"] if not ids or p["id"] in ids]

    agora = datetime.datetime.now(FUSO).strftime("%Y-%m-%d-%H%M")
    os.makedirs(saida, exist_ok=True)
    destino = os.path.join(saida, f"{agora}-harness-api.json")
    resultados = []
    interrompida = None
    token = token_fixo or login(base, email, senha)
    inicio_token = time.monotonic()
    rede = baseline_rede(base)
    guarda = GuardaDeCota(base, token, uso_maximo)
    try:
        guarda.inicio()
        print(f"rede (GET health): p50 {rede['p50Ms']} ms; "
              f"uso da cota antes: {guarda.antes}%", flush=True)
        for rodada in range(1, repeticoes + 1):
            for p in perguntas:
                if not token_fixo and time.monotonic() - inicio_token > 12 * 60:  # token de 15 min (#206)
                    token = login(base, email, senha)
                    inicio_token = time.monotonic()
                r = perguntar(base, token, p["pergunta"], bool(token_fixo))
                r.update({"id": p["id"], "rodada": rodada, "rotaEsperada": p.get("rotaEsperada")})
                resultados.append(r)
                print(f"rodada {rodada}/{repeticoes} {p['id']} HTTP {r['status']} "
                      f"rota={r['rota']} {r['latenciaMs']} ms", flush=True)
                guarda.apos_pergunta()
    except CotaEsgotada:
        interrompida = "Cota semanal da Ceres esgotada (429 LIMITE_USO_ASSISTENTE)"
    except TokenExpirado:
        interrompida = "Token expirado (401 com AGRO_TOKEN): gere outro e rode de novo"
    except UsoAcimaDoLimite:
        interrompida = f"Uso da cota acima de {uso_maximo}% (AGRO_USO_MAXIMO)"
    except UsoIndisponivel:
        interrompida = ("Nao foi possivel ler GET /api/assistente/uso: "
                        "sem leitura da cota a medicao nao e protegida")
    except (KeyboardInterrupt, SystemExit) as erro:
        interrompida = f"Interrompida ({type(erro).__name__})"
    finally:
        gravar(destino, agora, gabarito["versao"], repeticoes, resultados, interrompida, rede,
               guarda.como_dict())
    if interrompida:
        sys.exit(interrompida + f": o que foi medido esta em {destino}")


def gravar(destino, agora, versao, repeticoes, resultados, interrompida, rede=None, uso=None):
    por_rota = {}
    for r in resultados:
        chave = r.get("rotaEsperada") or "SEM_ROTA"
        grupo = por_rota.setdefault(chave, {"latencias": [], "acertos": 0, "total": 0})
        grupo["latencias"].append(r["latenciaMs"])
        if r.get("rotaEsperada"):
            grupo["total"] += 1
            grupo["acertos"] += int(r.get("rota") == r["rotaEsperada"])
    resumo = {rota: {"respostas": len(g["latencias"]),
                     "p50Ms": percentil(g["latencias"], 50),
                     "p95Ms": percentil(g["latencias"], 95),
                     "mediaMs": round(statistics.mean(g["latencias"])),
                     "acertoRota": f"{g['acertos']}/{g['total']}" if g["total"] else "-"}
              for rota, g in por_rota.items()}
    with open(destino, "w", encoding="utf-8") as arquivo:
        # Sem URL nem e-mail no arquivo: ele pode ser versionado.
        json.dump({"executadoEm": agora, "fuso": "America/Sao_Paulo", "versaoGabarito": versao,
                   "repeticoes": repeticoes, "interrompida": interrompida, "redeBaseline": rede,
                   "usoCotaPercentual": uso, "porRota": resumo,
                   "resultados": resultados}, arquivo, ensure_ascii=False, indent=2)
    print(json.dumps(resumo, ensure_ascii=False, indent=2))
    print(f"Gravado em {destino}")


if __name__ == "__main__":
    main()
