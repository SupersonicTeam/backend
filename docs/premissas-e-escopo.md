# Premissas e escopo do cálculo

Decisão de 2026-10-29 (issue #280). Vale até a modelagem da situação do produtor no IBS/CBS
(issue [#281](https://github.com/SupersonicTeam/backend/issues/281), pós-banca). Este é o texto
de referência; o README e a CLAUDE.md só o resumem.

## 1. O cálculo de IBS/CBS vale para o produtor no regime regular

LC 214/2025, Capítulo do produtor rural não contribuinte:

| Situação | Base legal | O que o AgroContábil faz hoje |
|---|---|---|
| Produtor rural com receita **a partir do limite** (R$ 3,6 milhões no ano-calendário, atualizado anualmente pelo IPCA — art. 167), **exceto o integrado** | art. 164, *a contrario* | **Calcula** débito e crédito de IBS/CBS (regime regular) |
| Produtor abaixo do limite, ou produtor rural **integrado** (qualquer receita), que **optou** por se inscrever como contribuinte no regime regular | art. 165 | **Calcula** (mesmo tratamento do regime regular) |
| Produtor abaixo do limite, ou integrado, **sem** a opção: **não contribuinte** | art. 164 | **Fora do escopo** — o sistema calcularia débito que esse produtor não tem |
| Comprador contribuinte que adquire de produtor não contribuinte: **crédito presumido** | art. 168 | **Fora do escopo** — a compra entra como crédito normal |

**Premissa:** o AgroContábil atende o produtor **no regime regular do IBS/CBS** — acima do limite
ou optante (arts. 164 e 165). O produtor não contribuinte e o crédito presumido do comprador ficam
para a #281, que cria a situação do produtor por período e retira esta premissa.

O que a #281 precisa modelar e o sistema hoje **não** trata (todos no texto da LC 214):

- **Transição de limite** (art. 164, §§): ultrapassar o limite durante o ano, excesso pequeno com
  efeito só no ano seguinte e soma das receitas de partes relacionadas. Sem isso, o sistema não
  sugere a situação a partir das vendas.
- **Opção e renúncia** (arts. 165 e 166): a opção vale a partir do mês seguinte, é irretratável
  durante o ano-calendário e a renúncia produz efeito em 1º de janeiro do ano seguinte.
- **Diferimento dos insumos** (art. 138, § 2º): o IBS/CBS na venda de insumos agropecuários do
  Anexo IX (fertilizantes, defensivos, sementes etc.) a contribuinte do regime regular fica
  diferido. O `CreditoTributarioService` não modela o diferimento: numa compra de insumo do
  Anexo IX ele estima crédito que o produtor não teria.

Por que assim até a banca: modelar a situação exige histórico por ano-calendário, as regras de
opção e renúncia, nota sem destaque para o não contribuinte (CST/cClassTrib, relacionada à #278) e
o percentual do crédito presumido, fixado por ato anual. É trabalho de cálculo fiscal, e errar ali
é pior que declarar o escopo.

## 2. Dados da demonstração

- **O produtor semeado é optante pelo regime regular (art. 165).** O seed de dev/homolog
  (`config/DataLoader`) grava isso nas observações internas do vínculo do produtor A (João) com o
  escritório semeado, que o contador vê na ficha. A produtora B (Maria) não tem vínculo semeado.
  Em banco já semeado (a VPS de demonstração), o seed **não** reescreve vínculo existente: o texto
  entra pela ficha (`PATCH /api/contador/produtores/{produtorId}/ficha`, campo `observacoes`) para
  cada cliente da demonstração. Não há campo de modelo para a situação (é a #281).
- **Porte:** o seed declara faturamento anual de R$ 2,5 milhões (João) e R$ 1,8 milhão (Maria); as
  notas da VPS somam cerca de R$ 1,3 milhão por ano para o João. Os dois ficam abaixo do limite do
  art. 164, por isso a premissa de optante.

- **Demonstração da banca (#315):** os clientes do escritório "Contabilidade Rural Oeste" são de
  regime normal (`REAL`/`PRESUMIDO`, CRT 3), coerentes com esta premissa, e as observações de cada
  vínculo dizem se o cliente é optante (art. 165) ou contribuinte obrigatório (acima do limite do
  art. 164). Contas em [`demonstracao-banca.md`](demonstracao-banca.md).

### Regime do produtor pessoa física (resolvido na #298)

O seed cadastrava os dois produtores como **pessoa física** com `regimeTributario = SIMPLES`, o que
saía como **CRT 1 (Simples Nacional)** na NF-e e no DANFE. Mas produtor rural pessoa física **não
pode** estar no Simples Nacional nem no MEI, que são de microempresa e empresa de pequeno porte
(LC 123/2006, art. 3º). Desde a #298:

- o `NotaFiscalNfeMapper.crt` devolve **3 (regime normal)** para todo produtor pessoa física,
  qualquer que seja o regime gravado; pessoa jurídica segue o regime (1 Simples, 4 MEI, 3 normal);
- o calendário nunca mostra a pessoa física obrigação só do Simples ou do MEI (DAS, DEFIS, DAS-MEI,
  DASN-SIMEI), nem pela versão do escritório que tirou o tipo de produtor
  (`ObrigacaoFiscal.aplicaA`);
- o `Produtor` recusa gravar pessoa física em `SIMPLES`/`MEI` no cadastro e na troca de tipo ou de
  regime (422 `REGIME_INCOMPATIVEL_COM_TIPO_PRODUTOR`); cadastro antigo segue gravável em outra
  coluna até ser corrigido;
- o `DataLoader` semeia os dois em `REAL`. Na VPS, o regime já gravado é corrigido pelo passo
  manual de [`demonstracao-banca.md`](demonstracao-banca.md).

O campo `RegimeTributario` (MEI/SIMPLES/PRESUMIDO/REAL) é do imposto de renda e do Simples e não
representa a situação dos arts. 164 e 165 (a #281). A #284, pergunta 16, segue para o contador
confirmar o CRT 3.

## 3. ICMS e Funrural seguem por guia

O AgroContábil trata da **reforma** (IBS/CBS). Os tributos atuais ficam com o contador:

- **ICMS:** a NF-e gerada **não tem o grupo de ICMS por item** e o total de ICMS sai zerado. O
  tratamento de ICMS da operação (diferimento, isenção, CST por UF) é do contador, que lança a guia
  de ICMS no módulo de guias (`/api/contador/guias`, tipo `ICMS`).
- **Funrural:** a retenção pelo comprador e o recolhimento pelo produtor são **calculados para
  informar** (RN009, `RetencaoService`, função `consultarRetencoes` da Ceres), mas não são
  destacados na NF-e. O recolhimento segue por guia (tipo `FUNRURAL` ou `GPS`).

## 4. O que pode mudar

As validações de contador da issue #284 podem ajustar este texto:

- **pergunta 7:** plausibilidade de um produtor desse porte optar pelo regime regular, e outros
  motivos para optar ou não;
- **pergunta 9:** tratamento de ICMS de uma venda interna de soja em MT e se o ICMS zerado na nota
  é aceitável para a banca;
- **pergunta 1:** a redução de 60% da soja em grão (art. 137), hoje não aplicada pela tabela de
  NCM.

Roteiro de resposta para a banca: [`roteiro-banca-regime-regular.md`](roteiro-banca-regime-regular.md).
