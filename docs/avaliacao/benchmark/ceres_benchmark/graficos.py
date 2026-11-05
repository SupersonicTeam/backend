"""Graficos SVG para slide (issue #348): barras agrupadas modelo x arquitetura.

Legiveis em projetor (16:9, fonte grande) e sem cor como unica distincao: cada arquitetura tem
**hachura propria** e o rotulo do valor fica escrito em cima de cada barra. A faixa das 3 rodadas
aparece como barra de erro (minimo-maximo). So biblioteca padrao: SVG escrito a mao.
"""

from __future__ import annotations

from html import escape
from pathlib import Path

LARGURA, ALTURA = 1600, 900
MARGEM_ESQ, MARGEM_DIR, MARGEM_TOPO, MARGEM_BAIXO = 150, 60, 170, 150
#: Uma aparencia por arquitetura: cor + hachura (a hachura sozinha ja distingue).
ESTILOS = (
    {"cor": "#5b5b5b", "padrao": "hachura-diagonal", "nome": "hachurado"},
    {"cor": "#1f4e79", "padrao": None, "nome": "sólido"},
    {"cor": "#7a4b00", "padrao": "hachura-pontos", "nome": "pontilhado"},
)

_DEFS = """<defs>
  <pattern id="hachura-diagonal" width="16" height="16" patternUnits="userSpaceOnUse"
    patternTransform="rotate(45)">
    <rect width="16" height="16" fill="#ffffff"/>
    <rect width="7" height="16" fill="#5b5b5b"/>
  </pattern>
  <pattern id="hachura-pontos" width="14" height="14" patternUnits="userSpaceOnUse">
    <rect width="14" height="14" fill="#ffffff"/>
    <circle cx="7" cy="7" r="4" fill="#7a4b00"/>
  </pattern>
</defs>"""


def _preenchimento(estilo: dict) -> str:
    return f"url(#{estilo['padrao']})" if estilo["padrao"] else estilo["cor"]


def barras_agrupadas(
    titulo: str,
    subtitulo: str,
    grupos: list[str],
    series: list[str],
    valores: list[list[tuple[float | None, float | None, float | None]]],
    unidade: str,
    maximo: float | None = None,
    ficticio: bool = False,
) -> str:
    """SVG de barras agrupadas.

    :param valores: ``valores[serie][grupo] = (media, minimo, maximo)``; ``None`` = sem dado
    """
    teto = maximo
    if teto is None:
        picos = [v[2] if v[2] is not None else v[0] for s in valores for v in s if v[0] is not None]
        teto = max(picos) * 1.2 if picos else 1.0
        teto = teto or 1.0
    area_l = LARGURA - MARGEM_ESQ - MARGEM_DIR
    area_a = ALTURA - MARGEM_TOPO - MARGEM_BAIXO
    largura_grupo = area_l / max(1, len(grupos))
    largura_barra = largura_grupo * 0.7 / max(1, len(series))

    def y(valor: float) -> float:
        return MARGEM_TOPO + area_a * (1 - min(valor, teto) / teto)

    partes = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {LARGURA} {ALTURA}" '
        f'width="{LARGURA}" height="{ALTURA}" font-family="Arial, Helvetica, sans-serif" '
        f'role="img" aria-label="{escape(titulo)}">',
        _DEFS,
        f'<rect width="{LARGURA}" height="{ALTURA}" fill="#ffffff"/>',
        f'<text x="{MARGEM_ESQ}" y="64" font-size="44" font-weight="bold" fill="#111111">'
        f"{escape(titulo)}</text>",
        f'<text x="{MARGEM_ESQ}" y="110" font-size="28" fill="#333333">'
        f'{escape(("DADOS FICTÍCIOS — " if ficticio else "") + subtitulo)}</text>',
    ]
    # Eixo e grade
    for i in range(5):
        valor = teto * i / 4
        yy = y(valor)
        partes.append(f'<line x1="{MARGEM_ESQ}" y1="{yy:.1f}" x2="{LARGURA - MARGEM_DIR}" '
                      f'y2="{yy:.1f}" stroke="#cccccc" stroke-width="2"/>')
        partes.append(f'<text x="{MARGEM_ESQ - 16}" y="{yy + 10:.1f}" font-size="28" '
                      f'text-anchor="end" fill="#333333">{_rotulo(valor, unidade)}</text>')
    for gi, grupo in enumerate(grupos):
        centro = MARGEM_ESQ + largura_grupo * (gi + 0.5)
        partes.append(f'<text x="{centro:.1f}" y="{ALTURA - MARGEM_BAIXO + 50}" font-size="32" '
                      f'text-anchor="middle" fill="#111111">{escape(grupo)}</text>')
        inicio = centro - largura_barra * len(series) / 2
        for si in range(len(series)):
            media, minimo, maximo_v = valores[si][gi]
            x = inicio + si * largura_barra + 6
            w = largura_barra - 12
            estilo = ESTILOS[si % len(ESTILOS)]
            if media is None:
                partes.append(f'<text x="{x + w / 2:.1f}" y="{y(0) - 14:.1f}" font-size="26" '
                              f'text-anchor="middle" fill="#333333">sem dado</text>')
                continue
            topo = y(media)
            partes.append(f'<rect x="{x:.1f}" y="{topo:.1f}" width="{w:.1f}" '
                          f'height="{y(0) - topo:.1f}" fill="{_preenchimento(estilo)}" '
                          f'stroke="{estilo["cor"]}" stroke-width="3"/>')
            if minimo is not None and maximo_v is not None and minimo != maximo_v:
                cx = x + w / 2
                partes.append(f'<line x1="{cx:.1f}" y1="{y(minimo):.1f}" x2="{cx:.1f}" '
                              f'y2="{y(maximo_v):.1f}" stroke="#000000" stroke-width="4"/>')
                for extremo in (minimo, maximo_v):
                    partes.append(f'<line x1="{cx - 14:.1f}" y1="{y(extremo):.1f}" '
                                  f'x2="{cx + 14:.1f}" y2="{y(extremo):.1f}" stroke="#000000" '
                                  f'stroke-width="4"/>')
            topo_rotulo = min(topo, y(maximo_v) if maximo_v is not None else topo) - 14
            partes.append(f'<text x="{x + w / 2:.1f}" y="{topo_rotulo:.1f}" font-size="30" '
                          f'font-weight="bold" text-anchor="middle" fill="#111111">'
                          f"{_rotulo(media, unidade)}</text>")
    # Legenda: amostra com a hachura + nome da serie (e o nome do padrao, para quem nao ve a cor)
    lx = MARGEM_ESQ
    for si, serie in enumerate(series):
        estilo = ESTILOS[si % len(ESTILOS)]
        partes.append(f'<rect x="{lx}" y="{ALTURA - 70}" width="44" height="34" '
                      f'fill="{_preenchimento(estilo)}" stroke="{estilo["cor"]}" stroke-width="3"/>')
        texto = f"{serie} ({estilo['nome']})"
        partes.append(f'<text x="{lx + 56}" y="{ALTURA - 43}" font-size="30" fill="#111111">'
                      f"{escape(texto)}</text>")
        lx += 56 + 18 * len(texto) + 60
    partes.append(f'<text x="{LARGURA - MARGEM_DIR}" y="{ALTURA - 43}" font-size="24" '
                  f'text-anchor="end" fill="#333333">traço = faixa das rodadas</text>')
    if ficticio:
        partes.append(
            f'<text x="{LARGURA / 2}" y="{ALTURA / 2}" font-size="110" font-weight="bold" '
            f'fill="#cc0000" fill-opacity="0.30" text-anchor="middle" '
            f'transform="rotate(-20 {LARGURA / 2} {ALTURA / 2})">DADOS FICTÍCIOS</text>')
    partes.append("</svg>")
    return "\n".join(partes) + "\n"


def _rotulo(valor: float, unidade: str) -> str:
    if unidade == "%":
        return f"{valor:.0f}%"
    return f"{valor:.1f} {unidade}".replace(".", ",")


def gerar_todos(destino: Path, celulas, modelos, arquiteturas, ids_comum, canonico, adaptacoes,
                ficticio: bool, repeticoes: int | None = None) -> list[Path]:
    """Os graficos do relatorio (recorte comum); devolve os arquivos gravados."""
    from . import relatorio as rel  # import tardio: relatorio importa este modulo

    destino.mkdir(parents=True, exist_ok=True)
    series = [rotulo for _, rotulo in arquiteturas]
    arquivos = []

    def celula(modelo, arq):
        return next((c for c in celulas if c.modelo == modelo and c.arquitetura == arq), None)

    for criterio in ("rota", "numero", "citacao", "recusa", "vazamento", "falha"):
        valores = []
        n = 0
        for arq, _ in arquiteturas:
            linha = []
            for modelo in modelos:
                c = celula(modelo, arq)
                f = rel.faixa(c, criterio, ids_comum, canonico, adaptacoes, rel.RECORTE_COMUM) if c else None
                linha.append((f.media, f.minimo, f.maximo) if f and f.media is not None
                             else (None, None, None))
                n = max(n, f.total if f else 0)
            valores.append(linha)
        nome = rel.CRITERIOS[criterio][0]
        rodadas = f"média de {repeticoes} rodadas" if repeticoes else "média das rodadas"
        svg = barras_agrupadas(nome, f"Recorte comum, n={n} por rodada, {rodadas}",
                               modelos, series, valores, "%", 100.0, ficticio)
        arquivo = destino / f"{criterio}.svg"
        arquivo.write_text(svg, encoding="utf-8")
        arquivos.append(arquivo)

    lat = []
    for arq, _ in arquiteturas:
        linha = []
        for modelo in modelos:
            c = celula(modelo, arq)
            todas = rel.latencias(c, ids_comum, canonico).get("TODAS") if c else None
            linha.append((todas[0] / 1000, None, None) if todas else (None, None, None))
        lat.append(linha)
    arquivo = destino / "latencia-p50.svg"
    arquivo.write_text(barras_agrupadas("Latência p50 por pergunta",
                                        "Recorte comum, todas as rodadas, nesta máquina (não a VPS)",
                                        modelos, series, lat, "s", None, ficticio), encoding="utf-8")
    arquivos.append(arquivo)

    mem = []
    for arq, _ in arquiteturas:
        linha = []
        for modelo in modelos:
            c = celula(modelo, arq)
            bytes_ = ((c.manifesto.get("memoria") or {}).get("modeloBytes")) if c else None
            linha.append((bytes_ / 1_073_741_824, None, None) if bytes_ else (None, None, None))
        mem.append(linha)
    arquivo = destino / "memoria.svg"
    arquivo.write_text(barras_agrupadas("Memória do modelo carregado no Ollama",
                                        "GB do /api/ps com o num_ctx da Ceres (VPS: 24 GB no total)",
                                        modelos, series, mem, "GB", None, ficticio), encoding="utf-8")
    arquivos.append(arquivo)
    return arquivos
