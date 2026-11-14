import { defineConfig, devices } from '@playwright/test';

/**
 * Suite E2E dos casos de uso UC001 a UC005 (issue #321), contra a stack isolada do compose.yaml,
 * que o rodar.sh sobe antes e derruba depois. Aqui nao ha `webServer`: a stack tem banco, backend
 * e frontend, e o seed da banca leva minutos.
 *
 * Um worker so, sem repeticao: os casos sao independentes da ordem, mas dividem as contas do seed
 * (a Ceres atende uma pergunta por vez por produtor, #157), e uma falha intermitente tem de
 * aparecer, nao ser escondida por uma segunda tentativa.
 */
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${process.env.E2E_PORTA ?? '18321'}`;

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],
  outputDir: 'test-results',
  use: {
    baseURL,
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
    // No CI o trace de todo caso vai no artefato; local, so o de quem falhou.
    trace: process.env.CI ? 'on' : 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
});
