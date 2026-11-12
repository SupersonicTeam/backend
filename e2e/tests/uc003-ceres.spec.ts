import { Api } from '../support/api';
import { entrar, expect, test } from '../support/tela';

/** Uma das tres perguntas de atalho da tela, que o backend responde sem modelo (#343). */
const ATALHO = 'Quanto eu tenho que pagar este mês?';

/**
 * UC003 — Conversar com a Ceres. A suite nao usa modelo nenhum (a stack sobe com
 * AI_CHAT_PROVIDER=none, sem chave de API nem Ollama): o atalho de dados chama a funcao direto e
 * responde pelo template, com a fonte dos numeros.
 */
test('UC003: o produtor pergunta à Ceres por um atalho e vê a resposta com a fonte', async ({
  page,
  request,
}) => {
  await test.step('o produtor abre o chat e clica no atalho', async () => {
    await entrar(page, 'carlos');
    await page.goto('/chat');
    await expect(page.getByRole('heading', { level: 1, name: 'Ceres' })).toBeVisible();

    // A tela pergunta pelo SSE (`/chat/stream`), ou pelo POST simples sem leitor de stream.
    const [resposta] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          /^\/api\/assistente\/chat(\/stream)?$/.test(new URL(r.url()).pathname),
      ),
      page.getByRole('button', { name: ATALHO }).click(),
    ]);
    expect(resposta.status()).toBe(200);
  });

  await test.step('a resposta aparece com a origem dos números', async () => {
    const fonte = page.getByRole('region', { name: 'Origem dos números da resposta' });
    await expect(fonte).toBeVisible({ timeout: 30_000 });
    await expect(fonte).toContainText('Calculado a partir das suas notas');
    await expect(fonte.getByRole('listitem').first()).toBeVisible();
    // Sem modelo na stack, a resposta e do backend, nunca o aviso de Ceres fora do ar.
    await expect(page.getByText('Não foi possível falar com a Ceres')).toHaveCount(0);
  });

  await test.step('o servidor respondeu pela rota de dados, sem modelo e sem fallback', async () => {
    const carlos = await Api.entrar(request, 'carlos');
    const corpo = await carlos.perguntarCeres(ATALHO);
    expect(corpo.rota).toBe('DADOS');
    expect(corpo.modoFallback).toBe(false);
    expect(corpo.resposta.trim()).not.toBe('');
    expect(corpo.fontesDados.length).toBeGreaterThan(0);
    expect(corpo.fontesDados.every((f) => f.origem === 'PRODUTOR')).toBe(true);
  });
});
