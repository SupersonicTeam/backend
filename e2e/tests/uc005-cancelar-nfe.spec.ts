import { Api, Nota, notaAutorizadaDoCarlos } from '../support/api';
import { NATUREZA_VENDA } from '../support/cenario';
import { abrirNota, entrar, esperarResposta, expect, test } from '../support/tela';

/**
 * UC005 — Cancelar NF-e. A nota autorizada e preparada pela API (o caminho da UC001, que tem teste
 * proprio), emitida hoje: dentro dos 30 dias da RN006. O cancelamento em si e pela tela.
 */
test('UC005: o produtor cancela uma NF-e autorizada dentro do prazo', async ({
  page,
  request,
}) => {
  let nota!: Nota;

  await test.step('uma nota do produtor autorizada hoje (preparação pela API)', async () => {
    nota = await notaAutorizadaDoCarlos(request);
    expect(nota.status).toBe('AUTORIZADA');
  });

  await test.step('o produtor cancela a nota com a justificativa', async () => {
    await entrar(page, 'carlos');
    const painel = await abrirNota(page, nota.id, NATUREZA_VENDA);
    await expect(painel.getByText(`Nota ${nota.numero} · Autorizada`)).toBeVisible();
    await painel.getByRole('button', { name: 'Cancelar', exact: true }).click();

    const confirmacao = page.getByRole('dialog', { name: `Cancelar a NF-e ${nota.numero}` });
    await confirmacao
      .getByLabel('Por que está cancelando?')
      .fill('Venda desfeita a pedido do comprador antes do embarque.');
    await esperarResposta(page, 'POST', /\/cancelar$/, () =>
      confirmacao.getByRole('button', { name: 'Confirmar cancelamento' }).click(),
    );

    await expect(confirmacao).toBeHidden();
    await expect(page.getByText(/Nota cancelada junto à SEFAZ/)).toBeVisible();
    await expect(painel.getByText('Cancelamento na SEFAZ')).toBeVisible();
    await expect(painel.getByText(`Nota ${nota.numero} · Cancelada`)).toBeVisible();
  });

  await test.step('o servidor guarda a nota como CANCELADA', async () => {
    const carlos = await Api.entrar(request, 'carlos');
    expect((await carlos.nota(nota.id)).status).toBe('CANCELADA');
  });
});
