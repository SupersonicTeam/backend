#!/usr/bin/env python3
"""Roda o conjunto da continuidade (#499) contra duas Ceres locais e grava um JSONL por linha de
chamada, para o pontuar_continuidade.py. Só biblioteca padrão. Segue regra-de-decisao.md.

- Dois braços no mesmo commit: `--braco desligado URL BANCO` e `--braco ligado URL BANCO`
  (AI_CONTINUIDADE_HABILITADA falsa e verdadeira). Repetições 1 e 3: desligado e depois ligado;
  repetição 2: ligado e depois desligado.
- No ligado, cada pergunta leva os pares anteriores da conversa (`turno` devolvidos e pares
  fabricados), até 3, e `ultimaRespostaSemTurno`. No desligado, cada pergunta vai sozinha e os
  pares fabricados não rodam.
- Pares fabricados só no ligado: a pergunta de base é feita ao vivo, sem histórico, conferida pelo
  `exigir`, editada e assinada com a CRIPTO_CHAVE pública de dev. Antes da rodada, a assinatura de
  um turno real é refeita aqui: se não bater, nada roda.
- Todas as perguntas vão pelo `/api/assistente/chat/stream`: tempo até o evento `rota` e até a
  `resposta`. O crédito é o `consumidos` do `/api/assistente/uso` antes e depois do turno.
- A senha das contas semeadas vem de CERES_SENHA; nunca vai para arquivo nem para a saída.

  CERES_SENHA=... python3 executar_continuidade.py --pasta <worktree> \\
      --braco desligado http://localhost:8501 agroconia_499_desligado \\
      --braco ligado http://localhost:8502 agroconia_499_ligado \\
      --modelo anthropic/claude-haiku-5-5 --saida-dir <pasta>
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(AQUI))
import continuidade as ct  # noqa: E402
import validar_conjunto as vc  # noqa: E402

BRASILIA = dt.timezone(dt.timedelta(hours=-3))
EMAIL_PRODUTOR = "carlos.rossi@example.com"
EMAIL_ADMIN = "patricia.moreira@example.com"
NOME_PRODUTOR = "Carlos Eduardo Rossi"
NOME_OUTRO_PRODUTOR = "Luiz Carlos Bertoldo"
AQUECIMENTO = ("Quanto eu vendi de feijão em março de 2026?", "O que é o Imposto Seletivo?")
AUTOTESTE = "Quanto vendi de soja em outubro?"
CAMPOS_AUDITORIA = ("rota", "desfecho", "camada_classificacao", "latencia_ms",
                    "tokens_entrada", "tokens_saida", "modelo")
ROTAS_TECNICAS = {0, 429, 500, 502, 503, 504}


def agora() -> dt.datetime:
    return dt.datetime.now(BRASILIA)


# ---------------------------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------------------------


def requisitar(metodo: str, url: str, corpo=None, token: str | None = None,
               timeout: float = 120) -> tuple[int, object, dict]:
    dados = None if corpo is None else json.dumps(corpo).encode("utf-8")
    pedido = urllib.request.Request(url, data=dados, method=metodo)
    pedido.add_header("Content-Type", "application/json")
    pedido.add_header("Accept", "application/json")
    if token:
        pedido.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(pedido, timeout=timeout) as resposta:
            texto = resposta.read().decode("utf-8")
            return resposta.status, (json.loads(texto) if texto else None), dict(resposta.headers)
    except urllib.error.HTTPError as erro:
        with erro:
            texto = erro.read().decode("utf-8", "replace")
        try:
            lido = json.loads(texto) if texto else None
        except ValueError:
            lido = None
        return erro.code, lido, dict(erro.headers)
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as erro:
        return 0, {"erro": type(erro).__name__}, {}


def ler_sse(resposta, inicio: float) -> tuple[dict, float | None, float | None]:
    """Lê o stream: {evento: dados}, tempo até `rota` e até `resposta` (segundos)."""
    eventos: dict = {}
    t_rota = t_resposta = None
    nome, dados = None, []
    for bruta in resposta:
        linha = bruta.decode("utf-8").rstrip("\r\n")
        if linha == "":
            if nome or dados:
                texto = "\n".join(dados)
                try:
                    valor = json.loads(texto) if texto else None
                except ValueError:
                    valor = texto
                evento = nome or "message"
                eventos[evento] = valor
                if evento == "rota" and t_rota is None:
                    t_rota = time.monotonic() - inicio
                if evento == "resposta" and t_resposta is None:
                    t_resposta = time.monotonic() - inicio
            nome, dados = None, []
            if "fim" in eventos or "erro" in eventos:
                break
            continue
        if linha.startswith(":"):
            continue
        campo, _, valor = linha.partition(":")
        valor = valor[1:] if valor.startswith(" ") else valor
        if campo == "event":
            nome = valor
        elif campo == "data":
            dados.append(valor)
    return eventos, t_rota, t_resposta


def perguntar_stream(url: str, token: str, corpo: dict, timeout: float = 330) -> dict:
    """POST no stream da Ceres. Devolve http, corpo (a resposta), tempos e o erro, se houver."""
    pedido = urllib.request.Request(url + "/api/assistente/chat/stream",
                                    data=json.dumps(corpo).encode("utf-8"), method="POST")
    pedido.add_header("Content-Type", "application/json")
    pedido.add_header("Accept", "text/event-stream")
    pedido.add_header("Authorization", "Bearer " + token)
    inicio = time.monotonic()
    try:
        with urllib.request.urlopen(pedido, timeout=timeout) as resposta:
            eventos, t_rota, t_resposta = ler_sse(resposta, inicio)
            http = resposta.status
            espera = None
    except urllib.error.HTTPError as erro:
        with erro:
            texto = erro.read().decode("utf-8", "replace")
        try:
            corpo_erro = json.loads(texto) if texto else None
        except ValueError:
            corpo_erro = None
        return {"http_status": erro.code, "corpo": None, "erro": corpo_erro,
                "retry_after": erro.headers.get("Retry-After"), "t_rota": None,
                "t_resposta": None, "segundos": round(time.monotonic() - inicio, 3)}
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as erro:
        return {"http_status": 0, "corpo": None, "erro": {"erro": type(erro).__name__},
                "retry_after": None, "t_rota": None, "t_resposta": None,
                "segundos": round(time.monotonic() - inicio, 3)}
    corpo_resposta = eventos.get("resposta") if isinstance(eventos.get("resposta"), dict) else None
    return {"http_status": http if corpo_resposta else 0, "corpo": corpo_resposta,
            "erro": eventos.get("erro"), "retry_after": espera,
            "t_rota": None if t_rota is None else round(t_rota, 3),
            "t_resposta": None if t_resposta is None else round(t_resposta, 3),
            "segundos": round(time.monotonic() - inicio, 3)}


def falha_tecnica(chamada: dict) -> bool:
    corpo = chamada.get("corpo") or {}
    return chamada["http_status"] != 200 or bool(corpo.get("modoFallback")) or not corpo


# ---------------------------------------------------------------------------------------------
# Banco (só leitura, pelo psql do container)
# ---------------------------------------------------------------------------------------------


class Banco:
    def __init__(self, nome: str, container: str = "agrocontabil-postgres",
                 usuario: str = "agrocontabil"):
        self.base = ["docker", "exec", container, "psql", "-U", usuario, "-d", nome, "-At",
                     "-F", "\t", "-c"]

    def consulta(self, sql: str) -> list[list[str]]:
        saida = subprocess.run(self.base + [sql], capture_output=True, text=True,
                               check=True, encoding="utf-8").stdout.strip()
        return [linha.split("\t") for linha in saida.splitlines() if linha]

    def produtor(self, nome: str) -> str:
        linhas = self.consulta(
            f"select id from produtores where nome_completo = '{nome}' order by id limit 2")
        if len(linhas) != 1:
            raise SystemExit(f"produtor do seed não achado ou repetido: {nome}")
        return linhas[0][0]

    def notas_seed(self, produtor: str) -> dict[str, tuple[str, str]]:
        """{marcador: (número, id)} das notas especiais do seed, antes do preparo."""
        saida = {}
        for marcador, status in (("devolvida_seed", "DEVOLVIDA"), ("cancelada_seed", "CANCELADA"),
                                 ("aguardando_seed", "AGUARDANDO_APROVACAO")):
            linhas = self.consulta(
                f"select numero::text, id from nota_fiscal where produtor_id = '{produtor}' "
                f"and status = '{status}' and origem = 'PROPRIA' order by data_emissao")
            if len(linhas) != 1:
                raise SystemExit(f"o seed precisa de exatamente uma nota {status}: {len(linhas)}")
            saida[marcador] = (linhas[0][0], linhas[0][1])
        return saida

    def contagem_auditoria(self) -> int:
        return int(self.consulta("select count(*) from assistente_interacao")[0][0])

    def ultima_auditoria(self) -> dict:
        colunas = ", ".join(f"coalesce({c}::text, '')" for c in CAMPOS_AUDITORIA)
        linhas = self.consulta(
            f"select {colunas} from assistente_interacao order by criado_em desc limit 1")
        if not linhas or len(linhas[0]) != len(CAMPOS_AUDITORIA):
            return {}
        return {c: (v or None) for c, v in zip(CAMPOS_AUDITORIA, linhas[0])}


# ---------------------------------------------------------------------------------------------
# Um braço
# ---------------------------------------------------------------------------------------------


class Braco:
    def __init__(self, nome: str, url: str, banco: str, senha: str, intervalo: float):
        self.nome, self.url, self.banco = nome, url, Banco(banco)
        self.senha, self.intervalo = senha, intervalo
        self.token: str | None = None
        self.ultima_chamada = 0.0

    def entrar(self, email: str = EMAIL_PRODUTOR) -> str:
        status, corpo, _ = requisitar("POST", self.url + "/api/auth/login",
                                      {"email": email, "senha": self.senha})
        if status != 200 or not isinstance(corpo, dict) or not corpo.get("accessToken"):
            raise SystemExit(f"login recusado no braço {self.nome} ({email}): HTTP {status}")
        return corpo["accessToken"]

    def renovar(self) -> None:
        self.token = self.entrar()

    def consumidos(self) -> int | None:
        status, corpo, _ = requisitar("GET", self.url + "/api/assistente/uso", token=self.token)
        if status == 401:
            self.renovar()
            status, corpo, _ = requisitar("GET", self.url + "/api/assistente/uso",
                                          token=self.token)
        return corpo.get("consumidos") if status == 200 and isinstance(corpo, dict) else None

    def esperar_ritmo(self) -> None:
        falta = self.intervalo - (time.monotonic() - self.ultima_chamada)
        if falta > 0:
            time.sleep(falta)
        self.ultima_chamada = time.monotonic()

    def perguntar(self, texto: str, historico: list[dict] | None, sem_turno: bool,
                  tentativas: int) -> list[dict]:
        """Até `tentativas` chamadas; devolve uma linha por tentativa."""
        corpo = {"pergunta": texto}
        if historico is not None:
            corpo["historico"] = historico[-3:]
            corpo["ultimaRespostaSemTurno"] = sem_turno
        linhas = []
        for tentativa in range(1, tentativas + 1):
            self.esperar_ritmo()
            antes = self.consumidos()
            contagem = self.banco.contagem_auditoria()
            horario = agora()
            chamada = perguntar_stream(self.url, self.token, corpo)
            if chamada["http_status"] == 401:
                self.renovar()
                chamada = perguntar_stream(self.url, self.token, corpo)
            depois = self.consumidos()
            auditoria = self.banco.ultima_auditoria() \
                if self.banco.contagem_auditoria() == contagem + 1 else {}
            linha = dict(chamada, tentativa=tentativa, horario=horario.isoformat(),
                         data_referencia=horario.date().isoformat(), credito_antes=antes,
                         credito_depois=depois, auditoria=auditoria,
                         historico_enviado=len(corpo.get("historico", [])),
                         ultima_sem_turno=corpo.get("ultimaRespostaSemTurno"),
                         pergunta_enviada=texto)
            linha["falha_tecnica"] = falha_tecnica(chamada)
            linhas.append(linha)
            if not linha["falha_tecnica"]:
                break
            espera = chamada.get("retry_after")
            time.sleep(float(espera) if espera and str(espera).isdigit() else 5)
        return linhas


# ---------------------------------------------------------------------------------------------
# Execução de uma conversa
# ---------------------------------------------------------------------------------------------


def exigir_casa(exigir: dict, corpo: dict, hoje: dt.date, notas: dict, respostas: dict) -> str:
    """Motivo pelo qual a resposta de base não serve (vazio = serve)."""
    if "rotas" in exigir and corpo.get("rota") not in exigir["rotas"]:
        return f"rota {corpo.get('rota')}"
    if "alternativas" in exigir and not any(
            all(any(ct.casa(c, f, hoje, notas, respostas) for f in ct.fontes(corpo)) for c in alt)
            for alt in exigir["alternativas"]):
        return "alternativas"
    if "fatos" in exigir and ct.classificar_fatos(exigir["fatos"], corpo.get("resposta", "")) \
            != "certa":
        return "fatos"
    turno = corpo.get("turno")
    if not turno:
        return "sem turno"
    if "lei_d" in exigir:
        _, carga = ct.separar(turno["assinatura"])
        if (carga.get("lei") or {}).get("d") != exigir["lei_d"]:
            return f"lei.d {(carga.get('lei') or {}).get('d')}"
    return ""


class Rodada:
    def __init__(self, args, bracos: dict[str, Braco], conjuntos: list[tuple[dict, str]],
                 commit: str, saida):
        self.args, self.bracos, self.conjuntos = args, bracos, conjuntos
        self.commit, self.saida = commit, saida
        self.chave = ct.chave_historico()
        self.produtor: dict[str, str] = {}
        self.outro: dict[str, str] = {}
        self.notas: dict[str, dict[str, str]] = {}
        self.exp_modelo = None

    def gravar(self, base: dict, linha: dict) -> None:
        registro = dict(base, **linha, commit=self.commit, modelo=self.args.modelo)
        self.saida.write(json.dumps(registro, ensure_ascii=False) + "\n")
        self.saida.flush()

    # -- preparo ------------------------------------------------------------------------------

    def preparar(self) -> None:
        for nome, braco in self.bracos.items():
            self.produtor[nome] = braco.banco.produtor(NOME_PRODUTOR)
            self.outro[nome] = braco.banco.produtor(NOME_OUTRO_PRODUTOR)
            notas = braco.banco.notas_seed(self.produtor[nome])
            self.notas[nome] = {m: numero for m, (numero, _) in notas.items()}
            self.gravar({"tipo": "notas_seed", "braco": nome}, {"notas": self.notas[nome]})
            admin = braco.entrar(EMAIL_ADMIN)
            for conjunto, _ in self.conjuntos:
                for passo in conjunto.get("preparo", []):
                    self.preparo(braco, admin, passo, notas)
            braco.renovar()
            print(f"[{nome}] preparo feito; notas do seed {self.notas[nome]}", flush=True)

    def preparo(self, braco: Braco, admin: str, passo: dict, notas: dict) -> None:
        base = f"{braco.url}/api/contador/produtores/{self.produtor[braco.nome]}"
        if passo["acao"] == "pedido_do_escritorio":
            limite = (agora().date() + dt.timedelta(days=passo.get("prazo_dias", 5))).isoformat()
            status, _, _ = requisitar("POST", base + "/pendencias", {
                "titulo": passo["titulo"], "descricao": passo["descricao"],
                "dataLimite": limite, "tiposDocumento": ["OUTRO"]}, token=admin)
        else:
            marcador = vc.MARCADOR_NOTA.fullmatch(passo["nota"].strip("{}")).group(1)
            _, nota_id = notas[marcador]
            status, _, _ = requisitar("POST", f"{base}/notas/{nota_id}/devolver",
                                      {"motivo": passo["motivo"]}, token=admin)
        if status not in (200, 201):
            raise SystemExit(f"preparo {passo['acao']} recusado no braço {braco.nome}: {status}")

    def aquecer(self, nome: str) -> None:
        braco = self.bracos[nome]
        for texto in AQUECIMENTO:
            for linha in braco.perguntar(texto, None, False, 1):
                self.gravar({"tipo": "aquecimento", "braco": nome}, linha)

    def autoteste(self) -> None:
        """Refaz a assinatura de um turno real do braço ligado; sem bater, a rodada não começa."""
        braco = self.bracos["ligado"]
        linhas = braco.perguntar(AUTOTESTE, [], False, 3)
        for linha in linhas:
            self.gravar({"tipo": "autoteste", "braco": "ligado"}, linha)
        turno = (linhas[-1].get("corpo") or {}).get("turno")
        if not turno:
            raise SystemExit("autoteste: a resposta do braço ligado não trouxe turno")
        if not ct.confere(self.chave, self.produtor["ligado"], turno):
            raise SystemExit("autoteste: a assinatura refeita aqui não bate com a do servidor")
        _, carga = ct.separar(turno["assinatura"])
        self.exp_modelo = carga.get("exp")
        print("autoteste: assinatura conferida", flush=True)

    # -- conversas ----------------------------------------------------------------------------

    def par_real(self, braco: Braco, turno: dict, numero: int, base: dict,
                 respostas: dict) -> tuple[dict | None, dict | None]:
        """Faz a pergunta de base e monta o par. Devolve (item do histórico, corpo de base)."""
        nome = braco.nome
        pergunta_real = ct.resolver(turno["pergunta_real"], self.notas[nome], respostas)
        pergunta = ct.resolver(turno.get("pergunta", turno["pergunta_real"]), self.notas[nome],
                               respostas)
        motivo = "sem resposta"
        for preparo in range(1, 4):
            linhas = braco.perguntar(pergunta_real, [], False, self.args.tentativas)
            for linha in linhas:
                self.gravar(dict(base, tipo="par_base", turno=numero, preparo_tentativa=preparo),
                            linha)
            final = linhas[-1]
            if final["falha_tecnica"]:
                motivo = "falha técnica"
                continue
            corpo = final["corpo"]
            hoje = dt.date.fromisoformat(final["data_referencia"])
            motivo = exigir_casa(turno["exigir"], corpo, hoje, self.notas[nome], respostas)
            if motivo:
                continue
            origem = corpo["turno"]
            segmento, carga = ct.separar(origem["assinatura"])
            resposta = origem["resposta"]
            if turno.get("edicoes"):
                resposta, carga, motivo = ct.aplicar_edicoes(resposta, carga, turno["edicoes"])
                if motivo:
                    continue
            if turno.get("exp") == "vencido":
                carga["exp"] = ct.exp_vencido(carga.get("exp"), agora())
            produtor = self.outro[nome] if turno.get("assinar_para") == "outro_produtor" \
                else self.produtor[nome]
            mudou_carga = turno.get("edicoes") or turno.get("exp") == "vencido"
            assinatura = ct.assinar(self.chave, produtor, carga if mudou_carga else segmento,
                                    pergunta, resposta)
            if turno.get("adulterar") == "resposta":
                resposta = adulterar(resposta)
            elif turno.get("adulterar") == "carga":
                cabeca, seg, fim = assinatura.split(".")
                assinatura = ".".join([cabeca, adulterar(seg), fim])
            self.gravar(dict(base, tipo="par_montado", turno=numero),
                        {"preparo_valido": True, "pergunta_assinada": pergunta,
                         "edicoes_aplicadas": bool(turno.get("edicoes"))})
            return {"pergunta": pergunta, "resposta": resposta,
                    "assinatura": assinatura}, corpo
        self.gravar(dict(base, tipo="par_montado", turno=numero),
                    {"preparo_valido": False, "motivo": motivo})
        return None, None

    def par_fixo(self, braco: Braco, turno: dict) -> dict:
        carga = json.loads(json.dumps(turno["carga"]))
        carga["exp"] = ct.exp_novo(self.exp_modelo, agora())
        return {"pergunta": turno["pergunta"], "resposta": turno["resposta"],
                "assinatura": ct.assinar(self.chave, self.produtor[braco.nome], carga,
                                         turno["pergunta"], turno["resposta"])}

    def conversa(self, nome: str, conjunto: dict, sha: str, conversa: dict,
                 repeticao: int) -> None:
        braco = self.bracos[nome]
        ligado = nome == "ligado"
        base = {"conjunto_versao": conjunto["versao"], "conjunto_sha256": sha,
                "parte": conjunto["parte"], "conversa": conversa["id"],
                "categoria": conversa["categoria"], "braco": nome, "repeticao": repeticao}
        historico: list[dict] = []
        respostas: dict[int, str] = {}
        sem_turno = False
        for numero, turno in enumerate(conversa["turnos"], start=1):
            if turno["tipo"] == "par":
                if not ligado:
                    continue
                if turno["origem"] == "real":
                    item, corpo = self.par_real(braco, turno, numero, base, respostas)
                    if item is None:
                        return
                    respostas[numero] = corpo.get("resposta", "")
                else:
                    item = self.par_fixo(braco, turno)
                historico.append(item)
                sem_turno = False
                continue
            try:
                texto = ct.resolver(turno["texto"], self.notas[nome], respostas)
            except ValueError as erro:
                self.gravar(dict(base, tipo="pergunta", turno=numero),
                            {"marcador_sem_valor": str(erro), "sem_medicao": True})
                return
            linhas = braco.perguntar(texto, historico if ligado else None, sem_turno,
                                     self.args.tentativas)
            for i, linha in enumerate(linhas):
                extra = {"sem_medicao": True} if i == len(linhas) - 1 and linha["falha_tecnica"] \
                    else {}
                self.gravar(dict(base, tipo="pergunta", turno=numero), dict(linha, **extra))
            final = linhas[-1]
            if final["falha_tecnica"]:
                return
            corpo = final["corpo"]
            respostas[numero] = corpo.get("resposta", "")
            if ligado:
                if corpo.get("turno"):
                    historico.append(corpo["turno"])
                    sem_turno = False
                else:
                    sem_turno = True

    def repeticao(self, numero: int) -> None:
        ordem = ("desligado", "ligado") if numero in (1, 3) else ("ligado", "desligado")
        for nome in ordem:
            self.bracos[nome].renovar()
            print(f"repetição {numero}, braço {nome}: {agora():%H:%M:%S}", flush=True)
            for conjunto, sha in self.conjuntos:
                for conversa in conjunto["conversas"]:
                    if self.args.ids and conversa["id"] not in self.args.ids:
                        continue
                    self.conversa(nome, conjunto, sha, conversa, numero)

    def lei_sozinha(self) -> None:
        """Cada pergunta de lei ao vivo do teste com `fatos`, N vezes, sozinha, no desligado."""
        braco = self.bracos["desligado"]
        for conjunto, sha in self.conjuntos:
            if conjunto["parte"] != "teste":
                continue
            vistas = []
            for conversa in conjunto["conversas"]:
                for turno in conversa["turnos"]:
                    if turno["tipo"] == "pergunta" and "fatos" in turno.get("esperado", {}) \
                            and turno["texto"] not in vistas:
                        vistas.append(turno["texto"])
                        for vez in range(1, self.args.lei_sozinha + 1):
                            for linha in braco.perguntar(turno["texto"], None, False,
                                                         self.args.tentativas):
                                self.gravar({"tipo": "lei_sozinha", "braco": "desligado",
                                             "conjunto_sha256": sha, "conversa": conversa["id"],
                                             "repeticao": vez, "fatos": turno["esperado"]["fatos"]},
                                            linha)


def adulterar(texto: str) -> str:
    """Troca o primeiro dígito (ou a primeira letra) por outro: o MAC deixa de bater."""
    for i, caractere in enumerate(texto):
        if caractere.isdigit():
            return texto[:i] + str((int(caractere) + 1) % 10) + texto[i + 1:]
    for i, caractere in enumerate(texto):
        if caractere.isalpha():
            outro = "x" if caractere != "x" else "y"
            return texto[:i] + outro + texto[i + 1:]
    raise ValueError("texto sem dígito nem letra para adulterar")


def commit_limpo(pasta: str) -> str:
    sujo = subprocess.run(["git", "-C", pasta, "status", "--porcelain"], capture_output=True,
                          text=True, check=True).stdout.strip()
    if sujo:
        raise SystemExit(f"{pasta} tem mudanças fora de commit: o commit não descreve a rodada")
    return subprocess.run(["git", "-C", pasta, "rev-parse", "HEAD"], capture_output=True,
                          text=True, check=True).stdout.strip()


def carregar(caminho: Path) -> tuple[dict, str]:
    conjunto, erros = vc.validar(caminho)
    erros += vc.conferir_hash(caminho)
    if erros:
        raise SystemExit("conjunto inválido: " + "; ".join(erros))
    return conjunto, hashlib.sha256(caminho.read_bytes()).hexdigest()


def main(argv=None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analisador.add_argument("--pasta", required=True, help="worktree limpo do commit medido")
    analisador.add_argument("--braco", nargs=3, action="append", required=True,
                            metavar=("NOME", "URL", "BANCO"))
    analisador.add_argument("--modelo", required=True)
    analisador.add_argument("--saida-dir", required=True)
    analisador.add_argument("--repeticoes", type=int, default=3)
    analisador.add_argument("--tentativas", type=int, default=3)
    analisador.add_argument("--lei-sozinha", type=int, default=5)
    analisador.add_argument("--intervalo", type=float, default=3.2)
    analisador.add_argument("--ids", nargs="*", help="só estas conversas (ensaio)")
    args = analisador.parse_args(argv)
    senha = os.environ.get("CERES_SENHA")
    if not senha:
        raise SystemExit("CERES_SENHA ausente")
    nomes = {b[0] for b in args.braco}
    if nomes != {"desligado", "ligado"}:
        raise SystemExit("os braços precisam ser 'desligado' e 'ligado'")
    commit = commit_limpo(args.pasta)
    conjuntos = [carregar(vc.ARQUIVOS[1]), carregar(vc.ARQUIVOS[0])]
    bracos = {nome: Braco(nome, url.rstrip("/"), banco, senha, args.intervalo)
              for nome, url, banco in args.braco}
    saida_dir = Path(args.saida_dir)
    saida_dir.mkdir(parents=True, exist_ok=True)
    with (saida_dir / "rodada.jsonl").open("a", encoding="utf-8") as saida:
        rodada = Rodada(args, bracos, conjuntos, commit, saida)
        rodada.preparar()
        for nome in ("desligado", "ligado"):
            bracos[nome].renovar()
            rodada.aquecer(nome)
        rodada.autoteste()
        for numero in range(1, args.repeticoes + 1):
            rodada.repeticao(numero)
        if args.lei_sozinha:
            bracos["desligado"].renovar()
            rodada.lei_sozinha()
    print(f"fim: {agora():%H:%M:%S}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
