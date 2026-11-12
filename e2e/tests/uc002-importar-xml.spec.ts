import { writeFile } from 'node:fs/promises';
import { Api, Nota } from '../support/api';
import { CARLOS } from '../support/cenario';
import { entrar, esperarResposta, expect, test } from '../support/tela';

/**
 * UC002 — Importar XML de nota de compra. A fixture e gerada pela suite a cada rodada: a Maria
 * (fornecedora, sem escritorio) emite pela API uma NF-e de venda ao Carlos, assinada com o
 * certificado de teste e autorizada pela SEFAZ simulada. O Carlos sobe o XML pela tela, e o
 * backend confere o destinatario, a assinatura e a autorizacao pela chave com o digVal (#223).
 * Chave nova a cada rodada: a importacao nunca cai na regra de duplicada.
 */
test('UC002: o produtor importa o XML de uma nota de compra', async ({ page, request }) => {
  let venda!: Nota;
  const arquivo = test.info().outputPath('nfe-compra.xml');

  await test.step('a fornecedora emite a NF-e ao produtor (preparação pela API)', async () => {
    const maria = await Api.entrar(request, 'maria');
    const rascunho = await maria.criarVendaDeSoja(CARLOS, 800);
    await maria.gerarXml(rascunho.id);
    const envio = await maria.enviarSefaz(rascunho.id);
    expect(envio.status).toBe('AUTORIZADA');
    venda = await maria.nota(rascunho.id);
    await writeFile(arquivo, await maria.xml(rascunho.id), 'utf8');
  });

  await test.step('o produtor carrega o XML na tela de notas', async () => {
    await entrar(page, 'carlos');
    await page.goto('/documentos');
    await page.getByRole('button', { name: 'Nova nota' }).click();
    const janela = page.getByRole('dialog', { name: 'Nova nota fiscal' });
    await janela.getByRole('tab', { name: 'Carregar XML' }).click();

    const [seletor] = await Promise.all([
      page.waitForEvent('filechooser'),
      janela.getByText('Arraste o XML da NF-e aqui').click(),
    ]);
    await esperarResposta(page, 'POST', /^\/api\/notas\/importar$/, () =>
      seletor.setFiles(arquivo),
    );
    // A tela diz o resultado de cada arquivo, com o motivo do servidor (frontend#117).
    await expect(page.getByText('nfe-compra.xml: Nota importada com sucesso.')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(janela).toBeHidden();
  });

  await test.step('a compra aparece na lista do produtor, autorizada', async () => {
    await page.getByLabel('Buscar notas').fill(String(venda.numero));
    const cartao = page
      .getByRole('button')
      .filter({ hasText: new RegExp(`Nº ${venda.numero}\\b`) });
    await expect(cartao).toHaveCount(1);
    await expect(cartao).toContainText('compra');
    await expect(cartao).toContainText('Maria Agricultora');
    await expect(cartao).toContainText('Autorizada');
  });

  await test.step('o servidor guarda a compra autorizada, com a chave da NF-e', async () => {
    const carlos = await Api.entrar(request, 'carlos');
    const compras = (await carlos.notasPorNumero(venda.numero)).filter(
      (n) => n.origem === 'IMPORTADA',
    );
    expect(compras).toHaveLength(1);
    expect(compras[0].status).toBe('AUTORIZADA');
    expect(compras[0].chaveAcesso).toBe(venda.chaveAcesso);
  });
});
