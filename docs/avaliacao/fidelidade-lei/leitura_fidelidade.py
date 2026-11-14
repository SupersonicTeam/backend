#!/usr/bin/env python3
"""Organiza a leitura cega dos juízes da medição de fidelidade (#269).

Três passos, sobre a pasta de uma rodada do `executar_fidelidade.py`:

    python3 -I leitura_fidelidade.py lotes     RODADA   # cega e embaralha as respostas aceitas
    python3 -I leitura_fidelidade.py validar   RODADA   # confere a forma do que cada juiz entregou
    python3 -I leitura_fidelidade.py desacordos RODADA  # monta os lotes do terceiro juiz (J3)

`lotes` grava, em `RODADA/leitura/`: `chave.json` (id opaco -> pergunta, braço, repetição; **só para
quem aplica a regra**, nunca para o juiz), `lotes/lote-NN.json` (o que o juiz vê) e, para cada juiz,
`prompts/J1/lote-NN.md` e `prompts/J2/lote-NN.md`: o prompt completo (papel + lote + onde gravar o
JSON). Cada prompt vai a um subagente do modelo fixado na regra (J1 opus, J2 sonnet), em contexto
novo; o subagente grava o JSON em `juizes/<J>/lote-NN.json`. O juiz nunca vê o braço.

`desacordos` compara J1 e J2 por item (veredito calculado pelos rótulos) e põe só os itens em que
divergem em lotes para o J3 (opus), sem dizer o que J1 e J2 deram.
"""

import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fidelidade_comum as comum  # noqa: E402

PROMPT_JUIZ = comum.DIR / "prompts" / "juiz.md"
TAMANHO_LOTE = 12


def ultimas_execucoes(brutas: list) -> dict:
    """Vale a última tentativa de cada (pergunta, braço, repetição)."""
    ultimas = {}
    for r in brutas:
        ultimas[(r["id"], r["braco"], r["repeticao"])] = r
    return ultimas


def item_do_gabarito(pergunta: dict) -> dict:
    """O que o juiz vê do gabarito: nada que revele o braço, o executor ou a correção."""
    chaves = ("id", "classe", "temBaseLegal", "fatos", "armadilhas", "permitidos",
              "assuntoAusente", "naoPodeAfirmar", "comportamentoCorreto", "observacaoDoGabarito")
    return {k: pergunta[k] for k in chaves if k in pergunta}


def montar_lotes(brutas: list, conjunto: dict, semente: int, tamanho: int = TAMANHO_LOTE) -> tuple:
    """(itens por lote, chave). Só as execuções aceitas, as dos dois braços juntas e embaralhadas."""
    perguntas = comum.perguntas_por_id(conjunto)
    aceitas = []
    for (pid, braco, rep), r in sorted(ultimas_execucoes(brutas).items()):
        classe, _ = comum.classificar_execucao(r)
        if classe == "aceita":
            aceitas.append((pid, braco, rep, r))
    random.Random(semente).shuffle(aceitas)
    itens, chave = [], {}
    for n, (pid, braco, rep, r) in enumerate(aceitas, start=1):
        rid = f"R{n:03d}"
        chave[rid] = {"id": pid, "braco": braco, "repeticao": rep}
        p = perguntas[pid]
        itens.append({"id": rid, "pergunta": p["pergunta"], "resposta": r["resposta"],
                      "citacoes": r["citacoes"], "gabarito": item_do_gabarito(p)})
    lotes = [itens[i:i + tamanho] for i in range(0, len(itens), tamanho)]
    return lotes, chave


def prompt_do_lote(juiz: str, nome_lote: str, itens: list, destino: Path) -> str:
    papel = PROMPT_JUIZ.read_text(encoding="utf-8")
    return (f"{papel}\n\n---\n\n# Seu lote\n\nVocê é o juiz `{juiz}`. O lote chama-se `{nome_lote}`. "
            f"Use exatamente esses dois valores nos campos `juiz` e `lote` do JSON.\n\n"
            f"Grave o JSON no arquivo `{destino}` (com a ferramenta de escrita) e responda apenas "
            f"\"gravado\".\n\n## Itens\n\n```json\n"
            f"{json.dumps(itens, ensure_ascii=False, indent=2)}\n```\n")


def gravar_lotes(rodada: Path, lotes: list, chave: dict, juizes: tuple, prefixo: str = "lote") -> list:
    base = rodada / "leitura"
    (base / "lotes").mkdir(parents=True, exist_ok=True)
    nomes = []
    for i, itens in enumerate(lotes, start=1):
        nome = f"{prefixo}-{i:02d}"
        nomes.append(nome)
        (base / "lotes" / f"{nome}.json").write_text(
            json.dumps(itens, ensure_ascii=False, indent=2), encoding="utf-8")
        for juiz in juizes:
            (base / "prompts" / juiz).mkdir(parents=True, exist_ok=True)
            (base / "juizes" / juiz).mkdir(parents=True, exist_ok=True)
            destino = (base / "juizes" / juiz / f"{nome}.json").resolve()
            (base / "prompts" / juiz / f"{nome}.md").write_text(
                prompt_do_lote(juiz, nome, itens, destino), encoding="utf-8")
    return nomes


def carregar_json_do_juiz(caminho: Path) -> dict:
    """JSON entregue pelo juiz; tolera a cerca de código (```json) que modelos costumam pôr."""
    texto = caminho.read_text(encoding="utf-8").strip()
    if texto.startswith("```"):
        quebra = chr(10)
        texto = texto.split(quebra, 1)[1] if quebra in texto else ""
        texto = texto.rsplit("```", 1)[0].strip()
    return json.loads(texto)


def ler_leituras(rodada: Path, juiz: str) -> dict:
    """{id do item: leitura} de todos os lotes entregues pelo juiz."""
    leituras = {}
    pasta = rodada / "leitura" / "juizes" / juiz
    for arquivo in sorted(pasta.glob("*.json")) if pasta.exists() else []:
        dados = carregar_json_do_juiz(arquivo)
        for item in dados.get("itens", []):
            leituras[item["id"]] = item
    return leituras


def problemas_da_leitura(item: dict) -> list:
    problemas = []
    if item.get("tipoResposta") not in comum.TIPOS_RESPOSTA:
        problemas.append(f"{item.get('id')}: tipoResposta inválido")
    frases = item.get("frases") or []
    if item.get("tipoResposta") == "AFIRMA" and not frases:
        problemas.append(f"{item.get('id')}: resposta AFIRMA sem frases")
    for f in frases:
        if f.get("rotulo") not in comum.ROTULOS_FRASE:
            problemas.append(f"{item.get('id')}: rótulo inválido {f.get('rotulo')!r}")
    return problemas


def validar(rodada: Path) -> list:
    base = rodada / "leitura"
    problemas = []
    for arquivo in sorted((base / "lotes").glob("lote-*.json")):
        esperados = {i["id"] for i in json.loads(arquivo.read_text(encoding="utf-8"))}
        for juiz in ("J1", "J2"):
            caminho = base / "juizes" / juiz / arquivo.name
            if not caminho.exists():
                problemas.append(f"{juiz}: falta {arquivo.name}")
                continue
            try:
                dados = carregar_json_do_juiz(caminho)
            except ValueError as erro:
                problemas.append(f"{juiz}/{arquivo.name}: JSON inválido ({erro})")
                continue
            itens = dados.get("itens", [])
            ids = [i.get("id") for i in itens]
            if set(ids) != esperados or len(ids) != len(set(ids)):
                problemas.append(f"{juiz}/{arquivo.name}: ids diferentes do lote "
                                 f"(faltam {sorted(esperados - set(ids))}, "
                                 f"sobram {sorted(set(ids) - esperados)})")
            for item in itens:
                problemas += [f"{juiz}/{arquivo.name}: {p}" for p in problemas_da_leitura(item)]
    # J3, se existir
    for caminho in sorted((base / "juizes" / "J3").glob("*.json")) \
            if (base / "juizes" / "J3").exists() else []:
        for item in carregar_json_do_juiz(caminho).get("itens", []):
            problemas += [f"J3/{caminho.name}: {p}" for p in problemas_da_leitura(item)]
    return problemas


def itens_em_desacordo(j1: dict, j2: dict) -> list:
    ids = []
    for rid in sorted(set(j1) & set(j2)):
        if comum.veredito_de_leitura(j1[rid])[0] != comum.veredito_de_leitura(j2[rid])[0]:
            ids.append(rid)
    return ids


def comando_lotes(rodada: Path) -> None:
    brutas = comum.ler_jsonl(rodada / "respostas-brutas.jsonl")
    if not (rodada / "execucao.json").exists():
        sys.exit(f"falta execucao.json em {rodada}: o manifesto diz qual conjunto a rodada usou")
    manifesto = json.loads((rodada / "execucao.json").read_text(encoding="utf-8"))
    conjunto = comum.carregar_conjunto(comum.conjunto_do_manifesto(manifesto))
    lotes, chave = montar_lotes(brutas, conjunto, comum.REGRA["bootstrapSemente"])
    nomes = gravar_lotes(rodada, lotes, chave, ("J1", "J2"))
    (rodada / "leitura").mkdir(parents=True, exist_ok=True)
    (rodada / "leitura" / "chave.json").write_text(
        json.dumps(chave, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(chave)} respostas aceitas em {len(nomes)} lotes. Chave (não mostre ao juiz): "
          f"{rodada / 'leitura' / 'chave.json'}")
    print("Dispare um subagente por prompt: J1 = opus, J2 = sonnet (regra, seção 4.1).")


def comando_desacordos(rodada: Path) -> None:
    base = rodada / "leitura"
    j1, j2 = ler_leituras(rodada, "J1"), ler_leituras(rodada, "J2")
    ids = itens_em_desacordo(j1, j2)
    itens_por_id = {i["id"]: i for arquivo in sorted((base / "lotes").glob("lote-*.json"))
                    for i in json.loads(arquivo.read_text(encoding="utf-8"))}
    itens = [itens_por_id[i] for i in ids]
    lotes = [itens[i:i + TAMANHO_LOTE] for i in range(0, len(itens), TAMANHO_LOTE)]
    nomes = gravar_lotes(rodada, lotes, {}, ("J3",), prefixo="desempate")
    print(f"{len(ids)} itens em desacordo ({len(ids)}/{len(j1) or 1}) em {len(nomes)} lotes para o "
          "J3 (opus): " + ", ".join(nomes))


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) != 2 or argv[0] not in ("lotes", "validar", "desacordos"):
        print(__doc__)
        return 2
    rodada = Path(argv[1])
    if argv[0] == "lotes":
        comando_lotes(rodada)
    elif argv[0] == "desacordos":
        comando_desacordos(rodada)
    else:
        problemas = validar(rodada)
        print("\n".join(problemas) or "leituras de J1 e J2 válidas")
        return 1 if problemas else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
