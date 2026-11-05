"""Aviso de semelhanca entre as perguntas do benchmark e o treino do classificador (#352).

O gabarito e teste, nunca treino, nem por parafrase. A guarda do Java (``SeparacaoTreinoTeste``)
so pega copia literal; parafrase precisa de revisao humana. Este aviso e barato e **nao bloqueia**:
lista os pares com sobreposicao alta de palavras (Jaccard dos tokens normalizados) para a revisao
da equipe, que e obrigatoria no tema de linguagem do dia a dia.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from .gabarito import normalizar

#: Limiar do aviso: acima disso, o par vai para a lista da revisao humana.
LIMIAR = 0.5
#: Palavras que nao dizem nada sobre a pergunta (artigos, preposicoes, pronomes).
VAZIAS = frozenset(
    "a o as os um uma de do da dos das em no na nos nas e ou que eu me meu minha meus minhas "
    "para pra pro por com sem se ja tem ter esse essa este esta isso".split())


def tokens(texto: str) -> frozenset[str]:
    return frozenset(t for t in normalizar(texto).split() if t not in VAZIAS)


def jaccard(a: str, b: str) -> float:
    ta, tb = tokens(a), tokens(b)
    if not ta or not tb:
        return 0.0
    return len(ta & tb) / len(ta | tb)


#: Prompts que o modelo ve de verdade, com exemplos de pergunta (a mesma lista da guarda do Java).
PROMPTS = ("src/main/java/br/com/agrocontabil/assistente/PromptsCeres.java",
           "src/main/java/br/com/agrocontabil/assistente/ferramentas/FerramentasDadosProdutor.java")
_PERGUNTA_ENTRE_ASPAS = re.compile(r'"([^"\n]{8,}?\?)"')


def _ler(arquivo: Path) -> str:
    return arquivo.read_text(encoding="utf-8", errors="replace")


def _textos_json(dados) -> list[str]:
    """Todos os textos de um JSON (valores, nao chaves): estrutura nao dilui o Jaccard."""
    if isinstance(dados, str):
        return [dados]
    if isinstance(dados, dict):
        return [t for v in dados.values() for t in _textos_json(v)]
    if isinstance(dados, list):
        return [t for v in dados for t in _textos_json(v)]
    return []


def textos_de_treino(raiz: Path) -> list[tuple[str, str]]:
    """(arquivo, texto) do que o classificador e o modelo veem como exemplo: o JSON do
    classificador, ``docs/avaliacao/treino`` (exceto o README) e as perguntas entre aspas do prompt
    do classificador e das descricoes ``@Tool``."""
    textos = []
    embutido = raiz / "src/main/resources/ceres/treino"
    for arquivo in sorted(embutido.glob("*.json")):
        dados = json.loads(_ler(arquivo))
        for exemplo in dados.get("exemplos", []):
            if exemplo.get("pergunta"):
                textos.append((str(arquivo.relative_to(raiz)), exemplo["pergunta"]))
    docs = raiz / "docs/avaliacao/treino"
    for arquivo in sorted(p for p in docs.rglob("*") if p.is_file() and p.name != "README.md"):
        conteudo = _ler(arquivo)
        try:
            linhas = _textos_json(json.loads(conteudo)) if arquivo.suffix == ".json" else None
        except ValueError:
            linhas = None
        for linha in linhas if linhas is not None else conteudo.splitlines():
            if linha.strip():
                textos.append((str(arquivo.relative_to(raiz)), linha.strip()))
    for relativo in PROMPTS:
        arquivo = raiz / relativo
        if arquivo.exists():
            # Comentario e Javadoc nao vao ao modelo: so o codigo (strings, text blocks, @Tool).
            codigo = "\n".join(l for l in _ler(arquivo).splitlines()
                                if not l.lstrip().startswith(("*", "//", "/*")))
            for m in _PERGUNTA_ENTRE_ASPAS.finditer(codigo):
                textos.append((relativo, m.group(1)))
    return textos


def pares_semelhantes(perguntas: list[dict], treino: list[tuple[str, str]], temas: set[str] | None = None,
                      limiar: float = LIMIAR) -> list[dict]:
    """Pares (pergunta, exemplo de treino) com Jaccard >= ``limiar``, do mais parecido ao menos."""
    pares = []
    for p in perguntas:
        if temas and p.get("tema") not in temas:
            continue
        for arquivo, texto in treino:
            similaridade = jaccard(p["pergunta"], texto)
            if similaridade >= limiar:
                pares.append({"id": p["id"], "tema": p.get("tema"), "pergunta": p["pergunta"],
                              "treino": texto, "arquivo": arquivo,
                              "jaccard": round(similaridade, 2)})
    return sorted(pares, key=lambda x: (-x["jaccard"], x["id"]))


def markdown(pares: list[dict], limiar: float = LIMIAR) -> str:
    linhas = [
        "# Perguntas do benchmark parecidas com o treino (aviso, não bloqueia)\n",
        f"Jaccard das palavras normalizadas ≥ {limiar:.2f}. Cópia literal quebra o build "
        "(`SeparacaoTreinoTeste`); paráfrase só a revisão humana pega. **Revise cada par antes da "
        "rodada da banca** e reescreva a pergunta do benchmark se ela for a mesma pergunta dita de "
        "outro jeito.\n",
    ]
    if not pares:
        linhas.append("Nenhum par acima do limiar.\n")
        return "\n".join(linhas)
    linhas.append("| Id | Tema | Pergunta do benchmark | Exemplo de treino | Jaccard |\n|---|---|---|---|---|")
    for p in pares:
        linhas.append(f"| {p['id']} | {p['tema']} | {p['pergunta']} | {p['treino']} | {p['jaccard']:.2f} |")
    return "\n".join(linhas) + "\n"
