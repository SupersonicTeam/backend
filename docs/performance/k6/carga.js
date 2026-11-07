// Benchmark de p95 da API (issue #153): dashboard, listagem de notas, apuracao e calendario
// fiscal. Roda contra a stack isolada de docs/performance/compose.yaml (README.md).
//
// Variaveis (todas por ambiente, nenhuma credencial no arquivo):
//   BASE_URL   endereco do backend (padrao http://backend:8080, dentro da rede do compose)
//   K6_EMAIL   e-mail do produtor semeado (DataLoader)      K6_SENHA   senha (PERF_SEED_SENHA)
//   VUS        usuarios virtuais simultaneos (padrao 10)    DURACAO    duracao do patamar (3m)
//
// Limite do plano: p95 < 2 s em CADA endpoint. 429 e erro tambem reprovam: o limite de taxa da
// stack isolada esta alto de proposito, entao um 429 aqui e problema de configuracao, e deixaria
// o p95 medindo o limitador.
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://backend:8080';
const VUS = parseInt(__ENV.VUS || '10', 10);
const DURACAO = __ENV.DURACAO || '3m';
const LIMITE_MS = 2000;
// O access token vale 15 min (#206): renova com folga.
const RENOVAR_APOS_MS = 10 * 60 * 1000;

const respostas429 = new Counter('limite_429');
const ENDPOINTS = ['dashboard', 'notas', 'apuracao', 'calendario'];

const limites = {
  http_req_failed: ['rate<0.01'],
  limite_429: ['count==0'],
  checks: ['rate>0.99'],
};
for (const e of ENDPOINTS) {
  limites[`http_req_duration{endpoint:${e}}`] = [`p(95)<${LIMITE_MS}`];
}

export const options = {
  scenarios: {
    carga: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: VUS },
        { duration: DURACAO, target: VUS },
        { duration: '15s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: limites,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
};

function login() {
  const r = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ email: __ENV.K6_EMAIL, senha: __ENV.K6_SENHA }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'login' } },
  );
  if (r.status !== 200) {
    throw new Error(`login falhou: HTTP ${r.status} (confira K6_EMAIL/K6_SENHA e o SEED_SENHA)`);
  }
  return r.json('accessToken');
}

// Estado por VU: o modulo e avaliado uma vez em cada VU e persiste entre as iteracoes.
let token = null;
let tokenEm = 0;

function cabecalho() {
  if (token === null || Date.now() - tokenEm > RENOVAR_APOS_MS) {
    token = login();
    tokenEm = Date.now();
  }
  return { headers: { Authorization: `Bearer ${token}` } };
}

export function setup() {
  const t = login();
  const eu = http.get(`${BASE}/api/auth/me`, { headers: { Authorization: `Bearer ${t}` } });
  if (eu.status !== 200) {
    throw new Error(`/api/auth/me falhou: HTTP ${eu.status}`);
  }
  // Prova de que ha volume: sem ele o p95 mediria uma base vazia.
  const lista = http.get(`${BASE}/api/notas?page=0&size=1`, {
    headers: { Authorization: `Bearer ${t}` },
  });
  const total = lista.status === 200 ? lista.json('totalElements') : 0;
  if (total < 1000) {
    throw new Error(`so ${total} notas: rode docs/performance/seed-volume.sql antes (README)`);
  }
  console.log(`produtor ${eu.json('usuarioId')} com ${total} notas`);
  return { produtorId: eu.json('usuarioId'), totalNotas: total };
}

function chamar(nome, caminho, parametros) {
  const r = http.get(`${BASE}${caminho}`, { ...parametros, tags: { endpoint: nome } });
  if (r.status === 429) {
    respostas429.add(1);
  }
  check(r, { [`${nome} 200`]: (x) => x.status === 200 });
  return r;
}

export default function (dados) {
  const p = cabecalho();
  // Pagina aleatoria dentro do que existe (20 por pagina), para nao medir sempre a pagina 0.
  const paginas = Math.max(1, Math.floor(dados.totalNotas / 20));
  const pagina = Math.floor(Math.random() * Math.min(paginas, 200));

  group('dashboard', () => chamar('dashboard', '/api/dashboard', p));
  group('notas', () => chamar('notas', `/api/notas?page=${pagina}&size=20`, p));
  group('apuracao', () => chamar('apuracao', '/api/apuracao?ultimos=12', p));
  group('calendario', () =>
    chamar('calendario', `/api/calendario-fiscal/${dados.produtorId}/vencimentos`, p));
  sleep(0.5);
}
