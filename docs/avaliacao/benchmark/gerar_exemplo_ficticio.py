#!/usr/bin/env python3
"""Gera uma rodada de **dados ficticios** e o relatorio de exemplo (issue #348).

Nada aqui foi medido: as taxas, latencias, memorias e textos sao sorteados (semente fixa) so para
mostrar o formato do relatorio e exercitar o gerador. Toda celula sai com ``"ficticio": true`` e o
relatorio ganha o aviso no topo e a marca d'agua nos graficos.

    python3 docs/avaliacao/benchmark/gerar_exemplo_ficticio.py [pasta]   (padrao: exemplo-ficticio/)
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))

from ceres_benchmark import executor, gabarito as gab, relatorio  # noqa: E402

REPO = AQUI.parents[2]
#: Qualidade e latencia inventadas por (arquitetura, modelo): so para o exemplo ter contraste.
PERFIL = {
    ("ceres-1", "qwen3:0.6b"): (0.50, 3.5, 0.6), ("ceres-1", "qwen3:1.7b"): (0.66, 8.0, 1.5),
    ("ceres-1", "qwen3:4b-instruct"): (0.84, 19.0, 3.3), ("ceres-2", "qwen3:0.6b"): (0.74, 2.4, 0.6),
    ("ceres-2", "qwen3:1.7b"): (0.86, 4.0, 1.5), ("ceres-2", "qwen3:4b-instruct"): (0.92, 7.0, 3.3),
    # Modelo por API: latencia de rede (a memoria, 0, nao existe na celula).
    ("ceres-2", "claude-haiku-5-5"): (0.95, 1.4, 0.0),
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
    """Monta a rodada com as mesmas pecas do executor (SHAs, gabaritos adaptados, rodada.json)."""
    rng = random.Random(348)
    pasta.mkdir(parents=True, exist_ok=True)
    argumentos = argparse.Namespace(
        matriz=str(AQUI / "matriz.json"), arquiteturas=None, modelos=None, ref_ceres2=None,
        saida=str(pasta), trabalho=str(pasta), repeticoes=None,
        hardware="FICTÍCIO: máquina inventada", retomar=str(pasta),
        ensaio=True,
        # Ineditas de mentira (#387): as de verdade ficam no repositorio cego e nunca entram aqui.
        cego=str(AQUI / "tests" / "fixtures" / "cego-ficticio"))
    cfg = executor.montar_config(argumentos, REPO)
    ex = executor.Executor(cfg)
    ex.fixar_shas(None)
    ex.preparar_gabaritos(pasta)
    ex.gravar_similaridade(pasta)
    por_api = {m["modelo"] for m in cfg.modelos_api}
    ex.config_rodada = {"versao": "FICTÍCIO", "modelos": {
        m: {"quantizacao": "FICTÍCIO", "parametros": "?", "digest": "0" * 12} for m in cfg.modelos}}
    ex.opcoes = {"temperature": "0.2", "num-ctx": "8192", "num-predict": "1024", "think-option": "false"}
    canonico = json.loads((pasta / "gabarito-canonico.json").read_text(encoding="utf-8"))
    funcoes_antigas = gab.assinaturas_ferramentas(
        _git("show", f"{cfg.todas_arquiteturas[0]['sha']}:{executor.FERRAMENTAS_REPO}"))
    PRESENTES_CERES1.update(funcoes_antigas)
    rodada = ex.manifesto_rodada(None, {"ollama": "FICTÍCIO", "trechosLegislacao": 0})
    rodada.update({"ficticio": True, "iniciadaEm": "FICTÍCIO"})
    adaptacoes = {a["nome"]: json.loads((pasta / a["adaptacao"]).read_text(encoding="utf-8"))
                  for a in rodada["arquiteturas"]}
    for item in rodada["celulas"]:
        nome, arq, modelo = item["nome"], item["arquitetura"], item["modelo"]
        q, latencia, gb = PERFIL[(arq, modelo)]
        rodadas = [{"resultados": [
            _resultado(rng, p, q, arq, adaptacoes[arq].get(p["id"]), latencia)
            for p in canonico["perguntas"]]} for _ in range(cfg.repeticoes)]
        avaliacao = {
            "ambiente": {"modelo": ("anthropic/" if modelo in por_api else "ollama/") + modelo,
                         "versaoGabarito": canonico["versao"]},
            "repeticoes": cfg.repeticoes,
            "perguntas": {p["id"]: p["pergunta"] for p in canonico["perguntas"]},
            "porRotaAgregado": {"DADOS": {"respostasMedidas": 60, "palavrasPorFrase": 14 + 10 * (1 - q),
                                          "comSiglaSemExplicacao": int(30 * (1 - q))}},
            "rodadas": rodadas,
        }
        destino = pasta / nome
        destino.mkdir(exist_ok=True)
        (destino / f"{nome}.json").write_text(json.dumps(avaliacao, ensure_ascii=False), encoding="utf-8")
        manifesto = {
            "ficticio": True, "celula": nome, "arquitetura": arq, "rotuloArquitetura": item["rotuloArquitetura"],
            "sha": next(a["sha"] for a in rodada["arquiteturas"] if a["nome"] == arq), "modelo": modelo,
            "repeticoes": cfg.repeticoes, "codigoSaida": 0,
            "duracaoSegundos": int(latencia * len(canonico["perguntas"]) * cfg.repeticoes),
            "pensamento": {"resumo": "FICTÍCIO: desligado (think=false; nenhum raciocínio na prova)"},
            "memoria": {"modeloBytes": int(gb * 1_073_741_824),
                        "processoPicoBytes": int((gb + 1.4) * 1_073_741_824),
                        "ondeRodou": {modelo: ["GPU"], "bge-m3": ["CPU"]}},
        }
        if modelo in por_api:
            # Celula por API: sem Ollama para o chat, logo sem memoria nem "onde rodou".
            manifesto.update({
                "provedor": "anthropic", "inferenciaEm": "API da Anthropic (rede)", "memoria": {},
                "pensamento": {"resumo": "FICTÍCIO: desligado (thinking=disabled; nenhum bloco de "
                                         "raciocínio na prova)"},
                "avisos": ["o chat desta célula foi pela API da Anthropic: a latência é de API pela rede "
                           "(com a fila do provedor), não de inferência local; não a compare com a das "
                           "células do qwen3"]})
        (destino / "celula.json").write_text(json.dumps(manifesto, ensure_ascii=False, indent=2),
                                             encoding="utf-8")
    (pasta / "rodada.json").write_text(json.dumps(rodada, ensure_ascii=False, indent=2), encoding="utf-8")
    relatorio.gerar(pasta)
    return pasta


if __name__ == "__main__":
    destino = Path(sys.argv[1]) if len(sys.argv) > 1 else AQUI / "exemplo-ficticio"
    print(gerar(destino) / "relatorio.md")
