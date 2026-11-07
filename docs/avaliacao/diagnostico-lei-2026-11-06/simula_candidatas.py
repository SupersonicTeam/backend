import json, os, re, subprocess, sys, unicodedata

S = os.path.dirname(os.path.abspath(__file__))
BASE = r'D:\Faculdade\Projetos\SuperSonicTeam - ShowRural2026\backend-tcc\.claude\worktrees\modest-volhard-79be7c'
rec = json.load(open(os.path.join(S, 'recall31.json'), encoding='utf-8'))['perguntas']
qs = {q['id']: q for q in json.load(open(os.path.join(
    BASE, 'docs', 'avaliacao', 'treino', 'perguntas-lei-diagnostico.json'), encoding='utf-8'))['perguntas']}


def sem(t):
    return ''.join(c for c in unicodedata.normalize('NFD', t.lower()) if not unicodedata.combining(c))


# 1) caputs dos artigos que aparecem nos top-20
artigos = sorted({e['artigo'] for q in rec for e in q['top20']})
caput = {}
for a in artigos:
    norma, _, art = a.partition(' art. ')
    art = 'art. ' + art
    sql = ("select left(content,260) from legislacao_trecho "
           "where norma='%s' and (dispositivo = '%s' or dispositivo like '%s,%%') "
           "order by length(dispositivo) limit 1" % (norma, art, art))
    out = subprocess.run([r'C:\Program Files\Git\usr\bin\bash.exe', os.path.join(S, 'q.sh'), sql], capture_output=True, text=True,
                         encoding='utf-8').stdout.strip()
    caput[a] = sem(out)

# 2) regra (b): regime específico/diferenciado, fixada ANTES de olhar o controle D23-D31
SETORES = {
    'combust': 'combust', 'biocombust': 'combust', 'imovel': 'imovel|imobil', 'imoveis': 'imovel|imobil',
    'imobiliar': 'imovel|imobil', 'financeir': 'financ', 'saude': 'saude', 'prognostic': 'prognost',
    'fundo de invest': 'fundo', 'futebol': 'futebol', 'saneamento': 'saneament', 'zona franca': 'zona franca',
    'aeronav': 'aeronav', 'naval': 'naval', 'turismo': 'turism|viagem', 'agencia de viagem': 'turism|viagem',
    'creditos presumidos': 'presum', 'credito presumido': 'presum', 'cooperativ': 'cooperativ',
    'regime especifico': 'especific', 'regimes especificos': 'especific',
}


def especifico(artigo, pergunta):
    c = caput.get(artigo, '')
    p = sem(pergunta)
    for chave, radical in SETORES.items():
        if chave in c and not re.search(radical, p):
            return True
    return False


def pos_primeiro(lista, aceitos):
    for i, a in enumerate(lista, 1):
        if a in aceitos:
            return i
    return 0


def cenarios(q):
    aceitos = set(qs[q['id']]['esperado']) | set(qs[q['id']]['aceito'])
    arts = [e['artigo'] for e in q['top20']]
    base5 = arts[:5]
    # (a) 5 artigos distintos
    distintos = []
    for a in arts:
        if a not in distintos:
            distintos.append(a)
    a5 = distintos[:5]
    # (b1) rebaixa especificos e pega 5 trechos
    boas = [a for a in arts if not especifico(a, q['pergunta'])]
    ruins = [a for a in arts if especifico(a, q['pergunta'])]
    b_lista = boas + ruins
    b1 = b_lista[:5]
    # (b2) rebaixa e 5 artigos distintos
    bd = []
    for a in b_lista:
        if a not in bd:
            bd.append(a)
    b2 = bd[:5]
    hit = lambda l: bool(set(l) & aceitos)
    return dict(base=hit(base5), a=hit(a5), b1=hit(b1), b2=hit(b2),
                demovidos=sorted(set(ruins) & set(base5)))


ausentes = {'D19', 'D20', 'D22'}
grupos = {'treino (D01-D18, D21)': [q for q in rec if q['id'] not in ausentes and int(q['id'][1:]) <= 22],
          'controle (D23-D31)': [q for q in rec if int(q['id'][1:]) >= 23]}
resumo = {}
for nome, lista in grupos.items():
    tot = len(lista)
    res = {k: 0 for k in ('base', 'a', 'b1', 'b2')}
    casos = []
    for q in lista:
        c = cenarios(q)
        for k in res:
            res[k] += c[k]
        casos.append((q['id'], c))
    resumo[nome] = (tot, res, casos)
    print(nome, tot, res)
    for i, c in casos:
        if c['base'] != c['a'] or c['base'] != c['b1'] or c['base'] != c['b2'] or c['demovidos']:
            print('   ', i, {k: (int(v) if isinstance(v, bool) else v) for k, v in c.items()})
json.dump({n: {'total': t, 'recall': r, 'casos': {i: c for i, c in cs}} for n, (t, r, cs) in resumo.items()},
          open(os.path.join(S, 'candidatas.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
