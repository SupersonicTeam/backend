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

**O que não usar como argumento:** "o optante recupera o IBS/CBS dos insumos". Os insumos
agropecuários do Anexo IX (fertilizantes, defensivos, sementes) têm o IBS/CBS **diferido** na venda
a contribuinte do regime regular (art. 138, § 2º): não há imposto pago a recuperar. O argumento só
vale para compras fora do diferimento (máquinas, serviços), e o sistema ainda não modela o
diferimento (premissas, seção 1).

## Contrapontos que a banca pode levantar

| Contraponto | Resposta |
|---|---|
| "Optar tem custo: emitir nota com destaque, apurar, recolher." | Sim. É o trabalho que o sistema automatiza (emissão, apuração, relatório). A decisão de optar continua do produtor com o contador; o sistema não decide. |
| "Quem vende para pessoa física ou consumidor final não ganha nada com o crédito do comprador." | Correto: para esse produtor a opção faz menos sentido. É o caso do não contribuinte, fora do escopo atual (#281). |
| "A opção é para sempre? O limite muda?" | A opção vale a partir do mês seguinte e é irretratável durante o ano-calendário (art. 165); a renúncia produz efeito em 1º de janeiro do ano seguinte (art. 166). O limite é atualizado todo ano pelo IPCA (art. 167). O sistema ainda não modela esse histórico (#281). |
| "E o produtor integrado?" | Pelo art. 164 o integrado não é contribuinte qualquer que seja a receita, salvo opção (art. 165). Mesmo tratamento do pequeno produtor: fora do escopo atual. |
| "Os produtos *in natura* têm redução de 60% (art. 137)." | Sim. Hoje a redução vem da tabela de NCM, e a da soja em grão ainda não está aplicada: o enquadramento está em validação com o contador (#284, pergunta 1). Não afirmar que a demonstração já aplica a redução. |
| "A nota diz que o emitente é do Simples Nacional (CRT 1)." | **Ponto fraco real**: o cadastro da demonstração está com regime `SIMPLES`, que vai para o CRT da NF-e. É inconsistência dos dados de teste, pendente de correção (premissas, seção 2). **Corrigir antes da banca.** |
| "E o ICMS da nota, zerado?" | O sistema trata da reforma (IBS/CBS). ICMS e Funrural seguem por guia, com o contador (premissas, seção 3). |

## Pendências que podem mudar este texto

- **#284, pergunta 7:** um contador confirmar se o argumento do crédito do comprador se sustenta e
  se há outro motivo comum para optar ou não.
- **#284, perguntas 1 e 9:** redução da soja em grão e tratamento de ICMS na venda interna de soja
  em MT.
- **Dados da demonstração:** regime `SIMPLES` no cadastro (CRT 1 na NF-e).
- **Ceres:** a pergunta "pequeno produtor paga IBS?" deve citar os arts. 164 e 165 (base já
  ingerida); pedida para o gabarito ampliado na #264.
