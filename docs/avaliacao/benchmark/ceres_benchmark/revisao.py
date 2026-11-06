"""Planilha de revisao das respostas esperadas pela equipe e pelo orientador (#387).

Decisao do Murillo (2026-11-06): o benchmark nao tem contador. As respostas esperadas foram
conferidas no texto da lei (normas.leg.br e base da VPS) e sao revisadas pela equipe e pelo
orientador; a nota de clareza do CSV cego fica com o orientador.

- ``gerar``: uma linha por pergunta do conjunto congelado, em CSV (UTF-8 com BOM, ``;``, para abrir
  direto no Excel e no LibreOffice) e em ``.xlsx`` (so a biblioteca padrao), com as colunas a
  preencher. Celula do CSV que comecaria por ``=``, ``+``, ``-``, ``@``, tab, CR ou LF ganha um
  apostrofo na frente (injecao de formula, a mesma classe da #367); no ``.xlsx`` todo valor e texto
  (``inlineStr``), que a planilha nunca avalia.
- ``aplicar``: le a planilha preenchida. ``sim`` sem correcao sugerida marca ``revisado: {por, em}``
  e tira ``aConferir``; ``nao``, ``duvida`` ou correcao sugerida viram itens para a equipe decidir
  (nada e aplicado sozinho). O conjunto so muda com versao nova; o hash e regravado. Quem escreveu
  a pergunta (``autor``, nas ineditas de dados) nao a revisa.
"""

from __future__ import annotations

import copy
import csv
import hashlib
import io
import re
import unicodedata
import zipfile
from datetime import date
from pathlib import Path
from xml.sax.saxutils import escape

PASTA = Path(__file__).resolve().parent.parent
CONJUNTO = PASTA / "perguntas-benchmark.json"
PASTA_REVISAO = PASTA / "revisao"

DECLARACAO = ("Respostas esperadas conferidas no texto da lei (normas.leg.br e base da VPS) e "
              "revisadas pela equipe e pelo orientador; sem validação de contador.")

COL_OK = "OK? (sim/não/dúvida)"
COL_CORRECAO = "Correção sugerida"
COL_REVISOR = "Revisor"
COL_DATA = "Data"
COLUNAS = ["Id", "Origem", "Autor", "Tema", "Pergunta", "Rota esperada", "Tratamento esperado",
           "Dispositivos aceitos", "Afirmações proibidas", "Encaminhar ao contador",
           "Valor esperado", "Pendências", "Observação", "Situação",
           COL_OK, COL_CORRECAO, COL_REVISOR, COL_DATA]

ORIGENS = {"gabarito": "gabarito", "inedita_equipe": "inédita da equipe",
           "inedita_equipe_dados": "inédita de dados da equipe",
           "inedita_externa_ia": "externa (gerada por IA)"}
TRATAMENTOS = {"ZERO": "zero", "REDUCAO_60": "60%", "PADRAO": "cheio",
               "CONDICIONAL": "condicional", "FORA_DO_ESCOPO_SISTEMA": "fora do escopo",
               "NAO_SE_APLICA": "não se aplica (pergunta de regra, sem alíquota)"}
#: Primeiro caractere que uma planilha le como formula (OWASP CSV Injection) ou que quebra a celula.
_PERIGOSOS = ("=", "+", "-", "@", "\t", "\r", "\n")


def seguro(valor: str) -> str:
    """Celula de CSV que nao vira formula: apostrofo na frente do primeiro caractere perigoso."""
    return "'" + valor if valor.startswith(_PERIGOSOS) else valor


def _desfaz_seguro(valor: str) -> str:
    return valor[1:] if valor.startswith("'") and valor[1:].startswith(_PERIGOSOS) else valor


def legivel(regex: str) -> str:
    """Expressao regular das afirmacoes proibidas em texto: ``[aá]`` vira ``á``, ``(a|b)`` vira
    ``a/b``, opcional entre colchetes, ``.{0,40}`` vira reticencias."""
    t = regex.replace(r"\b", "")
    t = re.sub(r"\.\{\d*,?\d*\}\??", " … ", t)
    t = re.sub(r"\\s[?*+]?", " ", t)

    def classe(m: re.Match) -> str:
        letras = m.group(1)
        acentuada = [c for c in letras if not c.isascii()]
        return acentuada[0] if acentuada else letras[0]

    t = re.sub(r"\[([^\]^]+)\]", classe, t)
    anterior = None
    while anterior != t:
        anterior = t
        t = re.sub(r"\(([^()]*)\)\?", lambda m: "[" + _alternativas(m.group(1)) + "] ", t)
        t = re.sub(r"\(([^()]*)\)", lambda m: _alternativas(m.group(1)), t)
    t = re.sub(r"(\S)\?", r"[\1]", t)
    return re.sub(r"\s+", " ", t).strip()


def _alternativas(grupo: str) -> str:
    """``é|e`` (a mesma palavra com e sem acento) fica so a acentuada; o resto, separado por ``/``."""
    vistas: dict[str, str] = {}
    for alt in (x.strip() for x in grupo.split("|")):
        chave = _sem_acento(alt)
        if chave not in vistas or not alt.isascii():
            vistas[chave] = alt
    return "/".join(vistas.values())


def _valor_esperado(p: dict) -> str:
    if p.get("deveRecusar"):
        return "deve recusar"
    campos = p.get("camposValor") or []
    if not campos:
        funcoes = ", ".join(p.get("funcoesEsperadas") or [])
        return f"sem número: mede a função ({funcoes})" if funcoes else ""
    funcoes = ", ".join(p.get("funcoesEsperadas") or []) or "a função esperada"
    parametros = "; ".join(f"{k} = {' ou '.join(v)}" for k, v in (p.get("parametrosEsperados") or {}).items())
    return (f"calculado pelo sistema a partir dos dados de teste (SeedAvaliacao): campo "
            f"{', '.join(campos)} de {funcoes}" + (f" ({parametros})" if parametros else ""))


def _proibidas(p: dict) -> str:
    itens = [legivel(r) for r in p.get("afirmacoesProibidas") or []]
    itens += [f"texto \"{t}\"" for t in p.get("textosProibidos") or []]
    itens += [f"valor {v}" for v in p.get("valoresProibidos") or []]
    return "; ".join(itens)


def _pendencias(p: dict) -> str:
    itens = []
    if p.get("aConferir"):
        itens.append("dispositivos e classe a conferir")
    if p.get("pendenteContador"):
        itens.append(f"era pendente do contador ({p['pendenteContador']}); sem contador, a equipe "
                     "decide com o orientador")
    if p.get("contaminada"):
        itens.append("contaminada (fora dos recortes de inéditas)")
    return "; ".join(itens)


def _situacao(p: dict) -> str:
    r = p.get("revisado")
    if isinstance(r, dict) and r.get("por"):
        return f"revisada por {r['por']} em {r.get('em', '?')}"
    return "a revisar"


def _contador(p: dict) -> str:
    if "encaminharContador" not in p:
        return ""
    return "sim" if p["encaminharContador"] else "não"


def linhas(conjunto: dict) -> list[list[str]]:
    """Cabecalho + uma linha por pergunta, na ordem do conjunto (sem a protecao de formula)."""
    temas = conjunto.get("temas") or {}
    saida = [COLUNAS]
    for p in conjunto["perguntas"]:
        saida.append([
            p["id"], ORIGENS.get(p.get("origem", ""), p.get("origem", "")), p.get("autor", ""),
            temas.get(p.get("tema", ""), p.get("tema", "")), p["pergunta"],
            p.get("rotaEsperada", "") or "—", TRATAMENTOS.get(p.get("classeTratamento", ""), ""),
            "; ".join(p.get("dispositivosEsperados") or []), _proibidas(p), _contador(p),
            _valor_esperado(p), _pendencias(p), p.get("observacao", ""), _situacao(p),
            "", "", "", ""])
    return saida


def csv_texto(conjunto: dict) -> str:
    buf = io.StringIO()
    escritor = csv.writer(buf, delimiter=";", lineterminator="\r\n")
    for linha in linhas(conjunto):
        escritor.writerow([seguro(c) for c in linha])
    return buf.getvalue()


def _coluna(n: int) -> str:
    letras = ""
    while n:
        n, r = divmod(n - 1, 26)
        letras = chr(65 + r) + letras
    return letras


def _sem_controle(valor: str) -> str:
    """XML 1.0 nao aceita controle fora de tab/LF/CR."""
    return "".join(c for c in valor if c in "\t\n\r" or ord(c) >= 0x20)


def xlsx_bytes(conjunto: dict) -> bytes:
    """``.xlsx`` minimo (uma aba, texto inline, cabecalho fixo), deterministico (datas fixas no zip)."""
    tabela = linhas(conjunto)
    larguras = [8, 18, 12, 22, 60, 14, 16, 40, 40, 12, 40, 30, 50, 22, 14, 30, 16, 12]
    cols = "".join(f'<col min="{i}" max="{i}" width="{w}" customWidth="1"/>'
                   for i, w in enumerate(larguras, 1))
    linhas_xml = []
    for r, linha in enumerate(tabela, 1):
        estilo = ' s="1"' if r == 1 else ""
        celulas = "".join(
            f'<c r="{_coluna(c)}{r}" t="inlineStr"{estilo}>'
            f'<is><t xml:space="preserve">{escape(_sem_controle(v))}</t></is></c>'
            for c, v in enumerate(linha, 1))
        linhas_xml.append(f'<row r="{r}">{celulas}</row>')
    folha = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
             '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" '
             'activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
             f'<cols>{cols}</cols><sheetData>{"".join(linhas_xml)}</sheetData>'
             f'<autoFilter ref="A1:{_coluna(len(COLUNAS))}{len(tabela)}"/></worksheet>')
    arquivos = {
        "[Content_Types].xml": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
            '</Types>'),
        "_rels/.rels": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
            '</Relationships>'),
        "xl/workbook.xml": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            '<sheets><sheet name="Revisao" sheetId="1" r:id="rId1"/></sheets>'
            '<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'
            f'Revisao!$A$1:${_coluna(len(COLUNAS))}${len(tabela)}</definedName></definedNames>'
            '</workbook>'),
        "xl/_rels/workbook.xml.rels": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
            '</Relationships>'),
        "xl/styles.xml": (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>'
            '<font><b/><sz val="11"/><name val="Calibri"/></font></fonts>'
            '<fills count="2"><fill><patternFill patternType="none"/></fill>'
            '<fill><patternFill patternType="gray125"/></fill></fills>'
            '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
            '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
            '<cellXfs count="2"><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
            '<xf numFmtId="49" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/></cellXfs>'
            '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
            '</styleSheet>'),
        "xl/worksheets/sheet1.xml": folha,
    }
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for nome, conteudo in arquivos.items():
            info = zipfile.ZipInfo(nome, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, conteudo.encode("utf-8"))
    return buf.getvalue()


def caminhos(versao: str, pasta: Path = PASTA_REVISAO) -> tuple[Path, Path]:
    return pasta / f"planilha-revisao-{versao}.csv", pasta / f"planilha-revisao-{versao}.xlsx"


def gerar(conjunto: dict, pasta: Path = PASTA_REVISAO) -> tuple[Path, Path]:
    pasta.mkdir(parents=True, exist_ok=True)
    caminho_csv, caminho_xlsx = caminhos(conjunto["versao"], pasta)
    caminho_csv.write_bytes(csv_texto(conjunto).encode("utf-8-sig"))
    caminho_xlsx.write_bytes(xlsx_bytes(conjunto))
    return caminho_csv, caminho_xlsx


# --- leitura de volta ---------------------------------------------------------------------------

def _sem_acento(texto: str) -> str:
    texto = unicodedata.normalize("NFKD", texto.strip().lower())
    return re.sub(r"\s+", " ", "".join(c for c in texto if not unicodedata.combining(c)))


def ler_planilha(caminho: Path) -> list[dict]:
    """Linhas preenchidas (CSV com ``;``, como o gerado), com o apostrofo de protecao desfeito."""
    texto = caminho.read_bytes().decode("utf-8-sig")
    leitor = csv.reader(io.StringIO(texto), delimiter=";")
    cabecalho = [_desfaz_seguro(c) for c in next(leitor)]
    faltando = [c for c in ("Id", "Pergunta", COL_OK, COL_CORRECAO, COL_REVISOR, COL_DATA)
                if c not in cabecalho]
    if faltando:
        raise ValueError(f"planilha sem as colunas {', '.join(faltando)}")
    return [{k: _desfaz_seguro(v) for k, v in zip(cabecalho, linha)} for linha in leitor if any(linha)]


def aplicar(conjunto: dict, preenchidas: list[dict], nova_versao: str) -> tuple[dict, dict]:
    """Conjunto novo (copia) e o resumo ``{revisadas, decidir, erros}``.

    Nada e aplicado se houver erro: id desconhecido, pergunta diferente da do conjunto (planilha de
    outra versao), resposta fora de sim/nao/duvida, "sim" sem revisor ou data valida, ou revisor
    que escreveu a pergunta.
    """
    novo = copy.deepcopy(conjunto)
    por_id = {p["id"]: p for p in novo["perguntas"]}
    revisadas, decidir, erros = [], [], []
    for linha in preenchidas:
        pid = linha.get("Id", "").strip()
        ok = _sem_acento(linha.get(COL_OK, ""))
        correcao = linha.get(COL_CORRECAO, "").strip()
        revisor = linha.get(COL_REVISOR, "").strip()
        data = linha.get(COL_DATA, "").strip()
        if not ok and not correcao:
            continue
        p = por_id.get(pid)
        if p is None:
            erros.append(f"{pid or '(sem id)'}: id fora do conjunto {conjunto['versao']}")
            continue
        if linha.get("Pergunta", "").strip() != p["pergunta"].strip():
            erros.append(f"{pid}: a pergunta da planilha difere da do conjunto {conjunto['versao']} "
                         "(planilha de outra versão?)")
            continue
        if ok not in ("sim", "nao", "duvida", ""):
            erros.append(f"{pid}: OK? deve ser sim, não ou dúvida (veio '{linha.get(COL_OK)}')")
            continue
        if p.get("autor") and revisor and _sem_acento(p["autor"]) == _sem_acento(revisor):
            erros.append(f"{pid}: {revisor} escreveu esta pergunta e não pode revisá-la")
            continue
        if ok == "sim" and not correcao:
            if not revisor:
                erros.append(f"{pid}: 'sim' sem revisor")
                continue
            try:
                date.fromisoformat(data)
            except ValueError:
                erros.append(f"{pid}: data '{data}' fora de AAAA-MM-DD")
                continue
            p["revisado"] = {"por": revisor, "em": data}
            p.pop("aConferir", None)
            revisadas.append(pid)
        else:
            decidir.append({"id": pid, "ok": linha.get(COL_OK, "").strip() or "(vazio)",
                            "correcao": correcao, "revisor": revisor, "data": data})
    if erros:
        return conjunto, {"revisadas": [], "decidir": decidir, "erros": erros}
    if revisadas:
        if nova_versao == conjunto["versao"]:
            raise ValueError("revisão aplicada muda o conjunto: use uma versão nova")
        novo["versao"] = nova_versao
    else:
        novo = conjunto
    return novo, {"revisadas": revisadas, "decidir": decidir, "erros": []}


def relatorio_md(resumo: dict, versao_planilha: str, versao_nova: str) -> str:
    linhas_md = [f"# Revisão da planilha {versao_planilha}\n", DECLARACAO + "\n"]
    if resumo["erros"]:
        linhas_md.append("## Erros (nada foi aplicado)\n")
        linhas_md += [f"- {e}" for e in resumo["erros"]]
        linhas_md.append("")
    linhas_md.append(f"## Revisadas ({len(resumo['revisadas'])})\n")
    linhas_md.append((", ".join(resumo["revisadas"]) + f" → conjunto {versao_nova}.")
                     if resumo["revisadas"] else "Nenhuma.")
    linhas_md.append(f"\n## Para a equipe decidir ({len(resumo['decidir'])})\n")
    linhas_md.append("Nenhuma correção sugerida é aplicada sozinha: a equipe decide e a mudança entra "
                     "com versão e hash novos.\n")
    if resumo["decidir"]:
        linhas_md.append("| Id | OK? | Correção sugerida | Revisor | Data |\n|---|---|---|---|---|")
        for d in resumo["decidir"]:
            celulas = [d["id"], d["ok"], d["correcao"], d["revisor"], d["data"]]
            linhas_md.append("| " + " | ".join(c.replace("|", "\\|").replace("\n", " ") for c in celulas) + " |")
    else:
        linhas_md.append("Nenhum item.")
    return "\n".join(linhas_md) + "\n"


def gravar_conjunto(conjunto: dict, caminho: Path = CONJUNTO) -> str:
    import json

    texto = json.dumps(conjunto, ensure_ascii=False, indent=2) + "\n"
    caminho.write_text(texto, encoding="utf-8")
    sha = hashlib.sha256(texto.encode("utf-8")).hexdigest()
    caminho.with_name(caminho.name + ".sha256").write_text(f"{sha}  {caminho.name}\n", encoding="utf-8")
    return sha
