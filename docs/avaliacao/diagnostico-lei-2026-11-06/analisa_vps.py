import json, os, re, statistics, sys, unicodedata
from collections import Counter

from tabelas_diag import perguntas, aceitos, carrega, art

CONJUNTOS = {
    'treino (D01-D18, D21)': [f'D{i:02d}' for i in range(1, 19)] + ['D21'],
    'controle 1 (D23-D31)': [f'D{i}' for i in range(23, 32)],
    'controle 2 (D32-D43)': [f'D{i}' for i in range(32, 44)],
    'lei ausente (D19, D20, D22)': ['D19', 'D20', 'D22'],
}
PERG = {q['id']: q['pergunta'] for q in perguntas}


def classe(q, r):
    if r['desfecho'] == 'ERRO_MODELO':
        return 'erro'
    if r['desfecho'] != 'RESPONDIDA':
        return 'descartada'
    cit = {art(x) for x in r['citacoes']}
    return 'certa' if cit & aceitos[q['id']] else 'outro'


def sem(t):
    return ''.join(c for c in unicodedata.normalize('NFD', t.lower()) if not unicodedata.combining(c))


# ---- guarda do regime específico: LC 214/2025, arts. 172 a 307
SETORES = [  # (primeiro, último, nome, palavras que mostram que a pergunta cita o setor)
    (172, 180, 'combustíveis', r'combust|gasolina|diesel|etanol|petroleo|gas natural'),
    (181, 233, 'serviços financeiros', r'financ|banc|segur|consorcio|previdencia|capitaliz|cambio|fundo'),
    (234, 243, 'planos de saúde', r'saude|plano|medic|hospital'),
    (244, 250, 'concursos de prognósticos', r'prognost|loteria|aposta|sorteio|bingo'),
    (251, 270, 'bens imóveis', r'imovel|imoveis|imobiliar|terreno|incorporac|locac|aluguel|constru|loteamento'),
    (271, 272, 'cooperativas', r'cooperativ'),
    (273, 291, 'bares, hotelaria, transporte, turismo', r'\bbar\b|restaurante|hotel|turism|viagem|parque|transporte'),
    (292, 296, 'SAF', r'futebol|\bsaf\b|clube'),
    (297, 299, 'missões diplomáticas', r'diplomat|consular|embaixada|tratado'),
    (300, 307, 'comuns aos regimes específicos', r'regime especifico|regimes especificos'),
]


def setor_do_artigo(c):
    m = re.match(r'LC 214/2025 art\. (\d+)', c)
    if not m:
        return None
    n = int(m.group(1))
    for a, b, nome, kw in SETORES:
        if a <= n <= b:
            return nome, kw
    return None


def guarda_barra(q, r):
    """True se a guarda descartaria esta resposta aceita."""
    if r['desfecho'] != 'RESPONDIDA' or not r['citacoes']:
        return False
    cits = {art(x) for x in r['citacoes']}
    setores = [setor_do_artigo(c) for c in cits]
    if any(s is None for s in setores):
        return False
    p = sem(PERG[q['id']])
    return not any(re.search(kw, p) for _, kw in setores)


def p(v, k):
    v = sorted(v)
    if not v:
        return 0
    i = min(len(v) - 1, int(round(k * (len(v) - 1))))
    return v[i]


def resumo(nome, dados):
    print('\n######', nome)
    todos_ms = []
    for conj, ids in CONJUNTOS.items():
        c = Counter()
        ms = []
        for q in dados:
            if q['id'] in ids:
                for r in q['rodadas']:
                    c[classe(q, r)] += 1
                    if r['desfecho'] != 'ERRO_MODELO' and 'ms' in r:
                        ms.append(r['ms'] / 1000)
        todos_ms += ms
        print('%-30s certa %2d | outro artigo %2d | descartada %2d | erro %d | tempo p50 %.1fs p95 %.1fs'
              % (conj, c['certa'], c['outro'], c['descartada'], c['erro'], p(ms, .5), p(ms, .95)))
    print('TODAS as chamadas: p50 %.1fs  p95 %.1fs  (n=%d)' % (p(todos_ms, .5), p(todos_ms, .95), len(todos_ms)))
    cru_raciocinio = sum(1 for q in dados for r in q['rodadas']
                         if re.search(r'<think>|</think>|^okay,|^let me|^primeiro, vou|^hmm', r.get('cru', '').lower().strip()))
    print('texto cru com raciocínio:', cru_raciocinio, 'de', sum(len(q['rodadas']) for q in dados))
    # guarda
    barradas = Counter()
    for q in dados:
        for r in q['rodadas']:
            if guarda_barra(q, r):
                barradas[classe(q, r)] += 1
    print('GUARDA (citações só da LC 214 arts. 172-307 sem o setor na pergunta) barraria:', dict(barradas))


if __name__ == '__main__':
    for nome, arq in (('4B na VPS', 'vps_4b.json'), ('30B na VPS', 'vps_30b.json')):
        if os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), arq)):
            resumo(nome, carrega(arq))
