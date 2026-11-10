import json, os, statistics
from collections import Counter, defaultdict

R = r'D:\Faculdade\Projetos\SuperSonicTeam - ShowRural2026\backend-tcc\.claude\worktrees\modest-volhard-79be7c\docs\avaliacao\resultados'
A = json.load(open(os.path.join(R, '2026-11-06-2307-ollama-qwen3-4b-instruct.json'), encoding='utf-8'))
B = json.load(open(os.path.join(R, '2026-11-06-2221-ollama-qwen3-30b-instruct.json'), encoding='utf-8'))
NOMES = {'4B': A, '30B': B}
CRITERIOS = ['rotaCorreta', 'funcaoCorreta', 'parametrosCorretos', 'numeroCorreto', 'citacaoValida',
             'recusaCorreta', 'vazamento']


def pct(v, k):
    v = sorted(v)
    if not v:
        return 0
    i = min(len(v) - 1, max(0, int(-(-k * len(v) // 1)) - 1))  # nearest-rank
    return v[i]


def rodadas(d):
    return [{r['id']: r for r in rod['resultados']} for rod in d['rodadas']]


def latencias(d, so_primeira=None):
    out = []
    for i, rod in enumerate(d['rodadas']):
        if so_primeira is True and i != 0:
            continue
        if so_primeira is False and i == 0:
            continue
        for r in rod['resultados']:
            out.append(r['execucao']['latenciaMs'] / 1000)
    return out


if __name__ == '__main__':
    for nome, d in NOMES.items():
        print('\n######', nome, 'duração', d['ambiente']['duracaoSegundos'], 's | instáveis:', d['instaveis'])
        for k, v in d['metricas'].items():
            print('  %-28s %.1f%% (n=%d)' % (k, v['media'], v['total']))
        for nm, flt in (('todas', None), ('1ª rodada', True), ('rodadas 2-3', False)):
            L = latencias(d, flt)
            print('  latência %-12s p50 %.1fs p95 %.1fs (n=%d)' % (nm, pct(L, .5), pct(L, .95), len(L)))
        # por rota esperada
        por = defaultdict(list)
        for rod in d['rodadas']:
            for r in rod['resultados']:
                por[r['rotaEsperada']].append(r)
        for rota, rs in sorted(por.items(), key=lambda x: str(x[0])):
            L1 = [r['execucao']['latenciaMs'] / 1000 for r in rs]
            print('   rota %-15s n=%3d p50 %.1fs p95 %.1fs' % (rota, len(rs), pct(L1, .5), pct(L1, .95)))
        des = Counter(r['execucao']['desfecho'] for rod in d['rodadas'] for r in rod['resultados'])
        print('  desfechos:', dict(des))
        cam = Counter(str(r['execucao'].get('camadaClassificacao')) for rod in d['rodadas'] for r in rod['resultados'])
        print('  quem decidiu a rota:', dict(cam))

    # perguntas que mudaram (1ª rodada; as instáveis são zero nos dois)
    ra, rb = rodadas(A)[0], rodadas(B)[0]
    print('\n######  PERGUNTAS COM CRITÉRIO DIFERENTE (4B -> 30B), 1ª rodada')
    for i in ra:
        dif = []
        for c in CRITERIOS:
            if ra[i].get(c) != rb[i].get(c):
                dif.append('%s: %s->%s' % (c, ra[i].get(c), rb[i].get(c)))
        da, db = ra[i]['execucao']['desfecho'], rb[i]['execucao']['desfecho']
        if dif or da != db:
            print(' ', i, '[%s]' % ra[i]['rotaEsperada'], '; '.join(dif), '| desfecho', da, '->', db)
