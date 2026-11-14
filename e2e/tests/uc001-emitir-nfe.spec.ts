import { Api, Nota } from '../support/api';
import { GRANELEIRA_TELES_PIRES as COMPRADOR, NATUREZA_VENDA, SOJA } from '../support/cenario';
import { abrirNota, entrar, esperarResposta, expect, test } from '../support/tela';

/**
 * UC001 — Emitir NF-e. O Carlos tem escritorio com vinculo ativo, entao o caminho e o da revisao
 * (#178): rascunho → enviada para revisao → aprovada pela contadora → XML gerado e assinado →
 * transmitida a SEFAZ (simulada) → AUTORIZADA.
 */
test('UC001: o produtor emite a NF-e, o escritório aprova e a SEFAZ autoriza', async ({
  page,
  outraPessoa,
  request,
}) => {
  const carlos = await Api.entrar(request, 'carlos');
  let numero!: number;
  let nota!: Nota;

  await test.step('o produtor preenche e cria a nota de venda', async () => {
    await entrar(page, 'carlos');
    await page.goto('/documentos');
    await page.getByRole('button', { name: 'Nova nota' }).click();

    const formulario = page.getByRole('dialog', { name: 'Nova nota fiscal' });
    // A tela sugere o proximo numero (o maior + 1) depois de abrir, por uma chamada a API, e a
    // sugestao sobrescreve o que ja foi digitado se chegar depois (na stack recem-semeada, chega).
    // O produtor fica com a sugestao: espera-se por ela e o numero da nota e o dela.
    const campoNumero = formulario.getByLabel('Número *', { exact: true });
    await expect(campoNumero).toHaveValue(/^\d+$/);
    numero = Number(await campoNumero.inputValue());

    await formulario.getByLabel('Natureza da Operação *').fill(NATUREZA_VENDA);
    await formulario.getByLabel('Nome/Razão Social do Destinatário *').fill(COMPRADOR.nome);
    await formulario.getByLabel('CPF/CNPJ *').fill(COMPRADOR.documento);
    await formulario.getByLabel('Inscrição Estadual').fill(COMPRADOR.ie);
    await formulario.getByLabel('Rua').fill(COMPRADOR.endereco.logradouro);
    await formulario.getByLabel('Número', { exact: true }).fill(COMPRADOR.endereco.numero);
    await formulario.getByLabel('Município').fill(COMPRADOR.endereco.municipio);
    await formulario.getByLabel('Cód. IBGE').fill(COMPRADOR.endereco.codigoMunicipioIbge);
    await formulario.getByLabel('UF', { exact: true }).selectOption(COMPRADOR.endereco.uf);
    await formulario.getByLabel('CEP').fill(COMPRADOR.endereco.cep);

    await formulario.getByLabel('Descrição do produto 1').fill(SOJA.descricao);
    await formulario.getByLabel('Código do produto 1').fill(SOJA.codigo);
    await formulario.getByLabel('NCM do produto 1').fill(SOJA.ncm);
    await formulario.getByLabel('CFOP do produto 1').fill(SOJA.cfop);
    await formulario.getByLabel('Unidade do produto 1').fill(SOJA.unidade);
    await formulario.getByLabel('Quantidade do produto 1').fill('1500');
    await formulario.getByLabel('Valor unitário do produto 1').fill(SOJA.valorUnitario);

    await esperarResposta(page, 'POST', /^\/api\/notas$/, () =>
      formulario.getByRole('button', { name: 'Criar nota' }).click(),
    );
    await expect(page.getByText('Nota criada em rascunho!')).toBeVisible();
    await expect(formulario).toBeHidden();

    // Uma compra do seed pode ter o mesmo numero (e do fornecedor): so a propria interessa.
    const criadas = (await carlos.notasPorNumero(numero)).filter((n) => n.origem === 'PROPRIA');
    expect(criadas).toHaveLength(1);
    nota = criadas[0];
    expect(nota.status).toBe('RASCUNHO');
  });

  await test.step('o produtor envia a nota para a revisão do escritório', async () => {
    const painel = await abrirNota(page, nota.id, NATUREZA_VENDA);
    await esperarResposta(page, 'POST', /\/enviar-para-revisao$/, () =>
      painel.getByRole('button', { name: 'Enviar para revisão' }).click(),
    );
    await expect(page.getByText('Nota enviada para o seu contador.')).toBeVisible();
    await expect(painel.getByText(`Nota ${numero} · Aguardando aprovação`)).toBeVisible();
  });

  await test.step('a contadora aprova a nota na ficha do cliente', async () => {
    const escritorio = await outraPessoa('patricia');
    await escritorio.goto('/contador');
    await escritorio.getByRole('link', { name: /Carlos Eduardo Rossi/ }).click();
    await expect(
      escritorio.getByRole('heading', { level: 1, name: 'Carlos Eduardo Rossi' }),
    ).toBeVisible();

    const naMesa = escritorio
      .getByRole('listitem')
      .filter({ hasText: new RegExp(`Nota ${numero}\\b`) });
    await esperarResposta(escritorio, 'POST', /\/aprovar$/, () =>
      naMesa.getByRole('button', { name: 'Aprovar' }).click(),
    );
    // Aprovada, ela sai da mesa do escritorio.
    await expect(naMesa).toHaveCount(0);
    expect((await carlos.nota(nota.id)).status).toBe('APROVADA');
  });

  await test.step('o produtor gera o XML e transmite à SEFAZ', async () => {
    const painel = await abrirNota(page, nota.id, NATUREZA_VENDA);
    await expect(painel.getByText(`Nota ${numero} · Aprovada`)).toBeVisible();

    await esperarResposta(page, 'POST', /\/gerar-xml$/, () =>
      painel.getByRole('button', { name: 'Gerar XML' }).click(),
    );
    await expect(page.getByText('XML gerado e assinado!')).toBeVisible();

    await esperarResposta(page, 'POST', /\/enviar-sefaz$/, () =>
      painel.getByRole('button', { name: 'Enviar à SEFAZ' }).click(),
    );
    await expect(page.getByText(/Autorizada pela SEFAZ/)).toBeVisible();
    await expect(painel.getByText('Retorno da SEFAZ')).toBeVisible();
    await expect(painel.getByText(`Nota ${numero} · Autorizada`)).toBeVisible();
  });

  await test.step('o servidor guarda a nota como AUTORIZADA', async () => {
    const final = await carlos.nota(nota.id);
    expect(final.status).toBe('AUTORIZADA');
    expect(final.chaveAcesso).toMatch(/^\d{44}$/);
  });
});
