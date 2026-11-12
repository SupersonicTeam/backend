import { readFile } from 'node:fs/promises';
import { entrar, expect, test } from '../support/tela';

/**
 * UC004 — Relatorio de conformidade tributaria. Na tela de impostos do produtor, o PDF do mes
 * escolhido (o corrente, por padrao) sai do `GET /api/relatorios/conformidade/pdf`. O seed da
 * banca da ao Carlos oito meses de notas ate o dia da semeadura, entao o mes tem movimento.
 */
test('UC004: o produtor baixa o relatório de conformidade em PDF', async ({ page }) => {
  await entrar(page, 'carlos');
  await page.goto('/tributario');
  await expect(page.getByRole('heading', { level: 1, name: 'Impostos' })).toBeVisible();

  const baixar = page.getByRole('button', { name: 'Baixar PDF' });
  // O botao so habilita depois de o relatorio do mes carregar.
  await expect(baixar).toBeEnabled();

  const [download] = await Promise.all([page.waitForEvent('download'), baixar.click()]);

  expect(download.suggestedFilename()).toMatch(/\.pdf$/i);
  const conteudo = await readFile(await download.path());
  expect(conteudo.subarray(0, 5).toString('latin1')).toBe('%PDF-');
  expect(conteudo.length).toBeGreaterThan(1_000);
  await expect(page.getByText('Não foi possível baixar o PDF.')).toHaveCount(0);
});
