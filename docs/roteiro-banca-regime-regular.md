# Roteiro da banca: "e o pequeno produtor?"

Pergunta provável: *"O art. 164 da LC 214 diz que o produtor com receita abaixo de R$ 3,6 milhões
não é contribuinte do IBS/CBS. Por que o sistema calcula débito para ele?"*

Premissa completa em [`premissas-e-escopo.md`](premissas-e-escopo.md). Issues: #280 (premissa) e
#281 (modelagem, pós-banca).

## Resposta curta (30 segundos)

> O sistema atende o produtor **no regime regular** do IBS/CBS: quem fatura acima do limite e quem,
> abaixo dele, **optou** por se inscrever como contribuinte (art. 165). O produtor da demonstração
> é optante. O produtor não contribuinte e o crédito presumido de quem compra dele (art. 168) estão
> fora do escopo desta versão e já têm issue de modelagem (#281).

## Por que um produtor abaixo do limite optaria

**Argumento central: o tipo de comprador.** Quem compra de produtor **não contribuinte** só se
apropria de um **crédito presumido**, com percentual fixado em ato anual, que pode variar por
produto e por tipo de produtor (art. 168). Quem compra de produtor **optante** se credita do
IBS/CBS **destacado na nota e extinto** — pago, inclusive pelo split payment (art. 47). Para quem
vende a trading, cerealista ou agroindústria, ser contribuinte põe o crédito do comprador na nota,
em vez de depender do percentual presumido. Se isso vale preço melhor depende do percentual
presumido do ano e do produto: é argumento de negociação, não ganho garantido.

**O que não usar como argumento:** "o optante recupera o IBS/CBS dos insumos". A compra de insumo
agropecuário do Anexo IX (fertilizantes, defensivos, sementes) entre contribuintes do regime regular
tem o recolhimento do IBS/CBS **diferido** (art. 138, § 2º, inciso I, alínea a). Quando o
diferimento se encerra, quem recolhe (§§ 5º a 7º) e o efeito sobre o crédito estão com o contador
(#284, pergunta C). Por isso o argumento fica fora; o crédito do art. 47 nas demais compras
(máquinas, serviços) continua valendo, e o sistema ainda não modela o diferimento (premissas,
seção 1).

## Contrapontos que a banca pode levantar

| Contraponto | Resposta |
|---|---|
| "Optar tem custo: emitir nota com destaque, apurar, recolher." | Sim. É o trabalho que o sistema automatiza (emissão, apuração, relatório). A decisão de optar continua do produtor com o contador; o sistema não decide. |
| "Quem vende para pessoa física ou consumidor final não ganha nada com o crédito do comprador." | Correto: para esse produtor a opção faz menos sentido. É o caso do não contribuinte, fora do escopo atual (#281). |
| "A opção é para sempre? O limite muda?" | A opção vale a partir do mês seguinte e é irretratável durante o ano-calendário (art. 165); a renúncia produz efeito em 1º de janeiro do ano seguinte (art. 166). O limite é atualizado todo ano pelo IPCA (art. 167). O sistema ainda não modela esse histórico (#281). |
| "E o produtor integrado?" | Pelo art. 164 o integrado não é contribuinte qualquer que seja a receita, salvo opção (art. 165). Mesmo tratamento do pequeno produtor: fora do escopo atual. |
| "Os produtos *in natura* têm redução de 60% (art. 137)." | Sim. Desde a tabela oficial (#272) a soja, o milho, o trigo e o algodão em grão saem com 60% pela marca *in natura* do NCM, e a demonstração mostra isso. O enquadramento segue em validação com o contador (#284, pergunta 1): não afirmar que ele é definitivo. |
| "A nota diz que o emitente é do Simples Nacional (CRT 1)." | Não diz mais (#298): produtor rural pessoa física não entra no Simples Nacional nem no MEI (LC 123/2006, art. 3º), então a NF-e e o DANFE dele saem com **CRT 3 (regime normal)** e o cadastro com `SIMPLES` é recusado. Nota autorizada antes da correção guarda o CRT da emissão: não usar uma delas na banca. |
| "E o ICMS da nota, zerado?" | O sistema trata da reforma (IBS/CBS). ICMS e Funrural seguem por guia, com o contador (premissas, seção 3); o Funrural/Senar retido pelo comprador sai destacado nas informações complementares da nota (#332). |

## Pendências que podem mudar este texto

- **#284, pergunta 7:** um contador confirmar se o argumento do crédito do comprador se sustenta e
  se há outro motivo comum para optar ou não.
- **#284, perguntas 1 e 9:** redução da soja em grão e tratamento de ICMS na venda interna de soja
  em MT.
- **Dados da demonstração:** o regime gravado do João e da Maria na VPS, corrigido pelo passo manual
  de [`demonstracao-banca.md`](demonstracao-banca.md) (#298). A #284, pergunta 16, confirma o CRT 3.
- **Ceres:** a pergunta "pequeno produtor paga IBS?" deve citar os arts. 164 e 165 (base já
  ingerida); pedida para o gabarito ampliado na #264.
