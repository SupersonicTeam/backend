import { test as base, expect, Locator, Page } from '@playwright/test';
import { CONTAS, Conta, senhaDeTeste } from './contas';

/**
 * Passos de tela comuns. Seletores so por papel, rotulo e texto (getByRole/getByLabel/getByText),
 * que aguentam as mudancas de layout do frontend (frontend#186); nada de classe CSS.
 */

/** Entra pela tela de login, como a pessoa, e espera sair dela. */
export async function entrar(page: Page, conta: Conta): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(CONTAS[conta]);
  await page.getByLabel('Senha').fill(senhaDeTeste());
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

/**
 * `test` com a fixture `outraPessoa`: abre outro contexto do navegador (outro cookie de sessao) e
 * entra com a conta pedida. E como duas pessoas em dois computadores: o produtor e o escritorio.
 */
export const test = base.extend<{ outraPessoa: (conta: Conta) => Promise<Page> }>({
  outraPessoa: async ({ browser, baseURL, locale, timezoneId, viewport }, use) => {
    const contextos: Awaited<ReturnType<typeof browser.newContext>>[] = [];
    await use(async (conta) => {
      const contexto = await browser.newContext({ baseURL, locale, timezoneId, viewport });
      contextos.push(contexto);
      const page = await contexto.newPage();
      await entrar(page, conta);
      return page;
    });
    for (const contexto of contextos) {
      await contexto.close();
    }
  },
});

export { expect };

/**
 * Abre a nota na tela de notas pelo link direto (`/documentos?nota=<id>`, o mesmo que o alerta do
 * painel usa) e devolve o painel da nota, um dialogo com o titulo da natureza da operacao.
 */
export async function abrirNota(page: Page, notaId: string, natureza: string): Promise<Locator> {
  await page.goto(`/documentos?nota=${notaId}`);
  const painel = page.getByRole('dialog', { name: natureza });
  await expect(painel).toBeVisible();
  return painel;
}

/**
 * Espera a resposta da API que a acao na tela dispara e exige sucesso (2xx).
 *
 * So o status: o corpo de uma resposta lida pelo `fetch` da tela nem sempre continua disponivel
 * ao Playwright (`Network.getResponseBody` falhou na primeira rodada). O que o teste precisa saber
 * do servidor ele pergunta pela `Api`.
 */
export async function esperarResposta(
  page: Page,
  metodo: string,
  caminho: RegExp,
  acao: () => Promise<void>,
): Promise<void> {
  const [resposta] = await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === metodo && caminho.test(new URL(r.url()).pathname),
    ),
    acao(),
  ]);
  expect(resposta.status(), `${metodo} ${new URL(resposta.url()).pathname}`).toBeLessThan(300);
}
