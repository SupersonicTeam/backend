#!/usr/bin/env python3
"""Gera uma rodada de **dados ficticios** e o relatorio de exemplo (issue #348).

Nada aqui foi medido: as taxas, latencias, memorias e textos sao sorteados (semente fixa) so para
mostrar o formato do relatorio e exercitar o gerador. Toda celula sai com ``"ficticio": true`` e o
relatorio ganha o aviso no topo e a marca d'agua nos graficos.

    python3 docs/avaliacao/benchmark/gerar_exemplo_ficticio.py [pasta]   (padrao: exemplo-ficticio/)
"""

from __future__ import annotations

import json
import random
import subprocess
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))

from ceres_benchmark import executor, gabarito as gab, relatorio  # noqa: E402

REPO = AQUI.parents[2]
SHA_ANTIGO = "2463c27"
#: Qualidade e latencia inventadas por (arquitetura, modelo): so para o exemplo ter contraste.
PERFIL = {
    ("ceres-1", "qwen3:0.6b"): (0.50, 3.5, 0.6), ("ceres-1", "qwen3:1.7b"): (0.66, 8.0, 1.5),
    ("ceres-1", "qwen3:4b-instruct"): (0.84, 19.0, 3.3), ("ceres-2", "qwen3:0.6b"): (0.74, 2.4, 0.6),
    ("ceres-2", "qwen3:1.7b"): (0.86, 4.0, 1.5), ("ceres-2", "qwen3:4b-instruct"): (0.92, 7.0, 3.3),
}
ESQUEMA_ANTIGO = "ceres-1"


def _git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=REPO, check=True, capture_output=True,
                          text=True).stdout


def _resultado(rng: random.Random, p: dict, q: float, arq: str, adaptada: dict | None,
               latencia_base: float) -> dict:
    def sorteio(aplica: bool):
        return (rng.random() < q) if aplica else None

    removido = (adaptada or {}).get("removido", {})
    funcoes = p.get("funcoesEsperadas") or []
    if "funcoesEsperadas" in removido:
        funcoes = [f for f in funcoes if f in PRESENTES_CERES1]
    tem_param = bool(p.get("parametrosEsperados")) and "parametrosEsperados" not in removido
    tem_num = bool(p.get("camposValor")) and "camposValor" not in removido
    rota_ok = sorteio(p.get("rotaEsperada") is not None)
    num_ok = sorteio(tem_num)
    vazou = None
    if p.get("valoresProibidos") or p.get("textosProibidos"):
        vazou = rng.random() > (q + (1 - q) * 0.7)
    desfecho = "RESPONDIDA"
    texto = "[FICTÍCIO] Resposta inventada para o exemplo do relatório."
    if p.get("rotaEsperada") == "FORA_DE_ESCOPO" or p.get("deveRecusar"):
        desfecho = "FORA_DE_ESCOPO" if rng.random() < q else "RESPONDIDA"
        if desfecho == "RESPONDIDA" and p["categoria"] in ("LEGISLACAO", "MISTO"):
            texto = "[FICTÍCIO] Sim, o art. 999 da LC 214/2025 isenta esse produto (regra inventada)."
    if num_ok is False:
        if rng.random() < 0.3:
            desfecho = "NUMERO_SEM_FONTE"
            texto = "[FICTÍCIO] Não consegui confirmar os números desta resposta."
        elif rng.random() < 0.4:
            texto = "[FICTÍCIO] Você não tem compras registradas nesse período."
        else:
            texto = "[FICTÍCIO] O total foi de R$ 12.345,67."
    if vazou:
        texto = ("[FICTÍCIO] " + p["textosProibidos"][0]) if p.get("textosProibidos") \
            else "[FICTÍCIO] O vizinho comprou R$ 987.654,32."
    if rng.random() < (1 - q) * 0.08:
        desfecho = "TEMPO_ESGOTADO"
    rota = p.get("rotaEsperada") if rota_ok else rng.choice(["DADOS", "LEGISLACAO", "MISTO", "FORA_DE_ESCOPO"])
    execucao = {
        "rota": rota, "texto": texto, "desfecho": desfecho, "citacoes": [], "fontes": [],
        "modoFallback": False, "latenciaMs": int(rng.lognormvariate(0, 0.35) * latencia_base * 1000),
        "erro": None, "desfechoDados": None, "desfechoLegislacao": None, "perguntaLeiReescrita": None,
    }
    resultado = {
        "id": p["id"], "categoria": p["categoria"], "execucao": execucao,
        "valoresEsperados": ["R$ 15.980,00"] if tem_num else [],
        "rotaCorreta": rota_ok,
        "funcaoCorreta": sorteio(bool(funcoes)),
        "parametrosCorretos": sorteio(tem_param),
        "numeroCorreto": num_ok,
        "citacaoValida": sorteio(bool(p.get("dispositivosEsperados"))),
        "recusaCorreta": (desfecho == "FORA_DE_ESCOPO") if p.get("deveRecusar") else None,
        "vazamento": vazou,
        "rotaEsperada": p.get("rotaEsperada"),
        "adversario": bool(p.get("adversario")),
    }
    if arq != ESQUEMA_ANTIGO:
        # Esquema da main: os dois campos que o 2463c27 nao grava.
        execucao["camadaClassificacao"] = rng.choice(["REGRA", "REGRA", "EMBEDDING", "EMBEDDING", "LLM"])
        resultado["semBecoSemSaida"] = (rng.random() < q) if p.get("semBecoSemSaida") else None
    return resultado


PRESENTES_CERES1: set[str] = set()


def gerar(pasta: Path) -> Path:
    rng = random.Random(348)
    pasta.mkdir(parents=True, exist_ok=True)
    canonico = json.loads((REPO / "docs/avaliacao/gabarito-ceres.json").read_text(encoding="utf-8"))
    antigo = json.loads(_git("show", f"{SHA_ANTIGO}:docs/avaliacao/gabarito-ceres.json"))
    funcoes_antigas = gab.assinaturas_ferramentas(_git("show", f"{SHA_ANTIGO}:{executor.FERRAMENTAS_REPO}"))
    PRESENTES_CERES1.update(funcoes_antigas)
    _, adaptacoes = gab.adaptar(canonico, funcoes_antigas, indice_de_lista=False)
    (pasta / "gabarito-canonico.json").write_text(json.dumps(canonico, ensure_ascii=False), encoding="utf-8")
    (pasta / "gabarito-antigo.json").write_text(json.dumps(antigo, ensure_ascii=False), encoding="utf-8")
    (pasta / "adaptacao-ceres-1.json").write_text(json.dumps(adaptacoes, ensure_ascii=False, indent=2),
                                                  encoding="utf-8")
    (pasta / "adaptacao-ceres-2.json").write_text("{}\n", encoding="utf-8")
    matriz = json.loads((AQUI / "matriz.json").read_text(encoding="utf-8"))
    arquiteturas = [{"nome": a["nome"], "rotulo": a["rotulo"], "ref": a["ref"],
                     "sha": "0000000-ficticio-" + a["nome"], "descricao": a["descricao"],
                     "adaptacao": f"adaptacao-{a['nome']}.json"} for a in matriz["arquiteturas"]]
    celulas = []
    for a in arquiteturas:
        for modelo in matriz["modelos"]:
            nome = executor.nome_celula(a["nome"], modelo)
            celulas.append({"nome": nome, "arquitetura": a["nome"], "rotuloArquitetura": a["rotulo"],
                            "modelo": modelo})
            q, latencia, gb = PERFIL[(a["nome"], modelo)]
            rodadas = []
            for _ in range(3):
                rodadas.append({"resultados": [
                    _resultado(rng, p, q, a["nome"],
                               adaptacoes.get(p["id"]) if a["nome"] == "ceres-1" else None, latencia)
                    for p in canonico["perguntas"]]})
            avaliacao = {
                "ambiente": {"modelo": "ollama/" + modelo, "versaoGabarito": canonico["versao"]},
                "repeticoes": 3,
                "perguntas": {p["id"]: p["pergunta"] for p in canonico["perguntas"]},
                "porRotaAgregado": {"DADOS": {"respostasMedidas": 60, "palavrasPorFrase": 14 + 10 * (1 - q),
                                              "comSiglaSemExplicacao": int(30 * (1 - q))}},
                "rodadas": rodadas,
            }
            destino = pasta / nome
            destino.mkdir(exist_ok=True)
            (destino / f"{nome}.json").write_text(json.dumps(avaliacao, ensure_ascii=False), encoding="utf-8")
            manifesto = {
                "ficticio": True, "celula": nome, "arquitetura": a["nome"], "rotuloArquitetura": a["rotulo"],
                "sha": a["sha"], "modelo": modelo, "codigoSaida": 0,
                "duracaoSegundos": int(latencia * len(canonico["perguntas"]) * 3),
                "pensamento": {"resumo": "FICTÍCIO: desligado (think=false; nenhum raciocínio na prova)"},
                "memoria": {"modeloBytes": int(gb * 1_073_741_824),
                            "processoPicoBytes": int((gb + 1.4) * 1_073_741_824)},
            }
            (destino / "celula.json").write_text(json.dumps(manifesto, ensure_ascii=False, indent=2),
                                                 encoding="utf-8")
    rodada = {
        "ficticio": True, "iniciadaEm": "FICTÍCIO", "hardware": ["FICTÍCIO: máquina inventada"],
        "repeticoes": 3, "embedding": "bge-m3", "modeloReferencia": matriz["modeloReferencia"],
        "gabaritoCanonico": "gabarito-canonico.json", "gabaritoAntigo": "gabarito-antigo.json",
        "arquiteturas": arquiteturas, "modelos": matriz["modelos"], "celulas": celulas,
    }
    (pasta / "rodada.json").write_text(json.dumps(rodada, ensure_ascii=False, indent=2), encoding="utf-8")
    relatorio.gerar(pasta)
    return pasta


if __name__ == "__main__":
    destino = Path(sys.argv[1]) if len(sys.argv) > 1 else AQUI / "exemplo-ficticio"
    print(gerar(destino) / "relatorio.md")
