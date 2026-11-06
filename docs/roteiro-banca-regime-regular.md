# Roteiro da banca: "e o pequeno produtor?"

Pergunta provável: *"O art. 164 da LC 214 diz que o produtor com receita abaixo de R$ 3,6 milhões
não é contribuinte do IBS/CBS. Por que o sistema calcula débito para ele?"*

Premissa completa em [`premissas-e-escopo.md`](premissas-e-escopo.md). Issues: #280 (premissa) e
#281 (modelagem, pós-banca).

## Resposta curta (30 segundos)

> O sistema atende o produtor **no regime regular** do IBS/CBS: quem fatura acima do limite e quem,
> abaixo dele, **optou** por se inscrever como contribuinte (art. 165). Na demonstração, o Carlos
> está acima do limite (contribuinte obrigatório) e o Rogério, o Luiz e a Helena são optantes. O produtor não contribuinte e o crédito presumido de quem compra dele (art. 168) estão
> fora do escopo desta versão e já têm issue de modelagem (#281).

## Por que um produtor abaixo do limite optaria

Os dispositivos **desta seção** foram conferidos na LC 214/2025 compilada (normas.leg.br, versão de
2026-08-27). Cite só esses e com as ressalvas de cada um.

**A favor da opção:**

- **A opção existe e é do produtor** (art. 165, *caput*): "O produtor rural ou o produtor rural
  integrado poderão optar, a qualquer tempo, por se inscrever como contribuinte do IBS e da CBS no
  regime regular."
- **O crédito do comprador muda de natureza** (o argumento central). Quem compra de produtor **não
  contribuinte** só se apropria de **crédito presumido** (art. 168, *caput*).
  - Os percentuais são "definidos e divulgados anualmente até o mês de setembro" por ato conjunto
    do Ministro da Fazenda e do Comitê Gestor (art. 168, **§ 4º**).
  - Eles podem ser diferenciados por produto, receita e tipo de produtor (§ 6º).
  - Quem compra de produtor **optante** se credita do IBS/CBS **destacado no documento fiscal e
    extinto** (art. 47, *caput* e § 2º, I).
  - Para quem vende a trading, cerealista ou agroindústria, a opção põe na nota o crédito integral
    do comprador, em vez do percentual presumido do ano.
  - **Ressalva:** só vale para produto **tributado**, como a soja e o milho do Rogério (com a
    redução de 60%). No produto com alíquota zero (Anexo I, como o leite em embalagem de 1 litro do
    Luiz e o tomate da Helena), nada é destacado, e o comprador não ganha crédito com a opção. Para
    esse produtor, o motivo de optar tem de ser outro (o saldo credor, por exemplo), ou a opção não
    compensa. Se a banca perguntar por que os dois optaram, é essa a resposta.
- **Saldo credor ressarcível** (art. 39): quem "apurar saldo a recuperar [...] poderá solicitar seu
  ressarcimento integral ou parcial", e o remanescente vira crédito para depois (§ 1º).
  - **Ressalva:** pedir ressarcimento prende a opção. A renúncia (art. 166) fica vedada a quem
    recebeu ressarcimento no ano-calendário corrente ou no anterior (art. 41, § 5º, aplicado à
    opção do produtor pelo § 6º).
  - Quem deixa de ser contribuinte tem os prazos de ressarcimento suspensos por até 5 anos
    (art. 39, § 10, II).
- **Exportação imune com crédito mantido** (art. 79): as exportações são imunes, "asseguradas ao
  exportador a apropriação e a utilização dos créditos" das aquisições.
  - **Ressalva:** vale para quem exporta direto.
  - A venda a trading com fim específico de exportação tem regra própria (art. 82), que o sistema
    não modela.
  - Os créditos aqui são os das compras tributadas, não os dos insumos do Anexo IX (veja "o que
    não usar" abaixo).

**Bens de capital (art. 108): não usar para máquinas agrícolas.** O art. 108 assegura o "crédito
integral e imediato de IBS e CBS [...] na aquisição de bens de capital". Só que o produtor **não
contribuinte** já compra tratores, máquinas e implementos agrícolas com **alíquota zero**
(art. 110, I, para os bens listados no regulamento). Nesses bens não há IBS/CBS a creditar, e o
argumento não favorece a opção. Ele só vale para bens de capital fora do art. 110.

**Contra a opção:**

- **Apuração mensal** (art. 43): "O período de apuração do IBS e da CBS será mensal." Optar traz
  essa rotina, com o custo do contador.
- **O documento fiscal vira declaração do imposto** (art. 60, § 1º): as informações do documento
  "possuem caráter declaratório e constituem confissão do valor devido de IBS e de CBS". O produtor
  já emite NF-e ou NFA hoje (por causa do ICMS); o que muda é o peso do que vai nela.
- **Venda a consumidor final:** quem vende a pessoa física ou a não contribuinte não ganha nada com
  o crédito do comprador, e o IBS/CBS destacado só encarece o preço. É argumento econômico, sem
  dispositivo próprio.

**Conclusão para a banca:** se a opção vale preço melhor depende do produto (tributado ou não), do
comprador e do percentual presumido do ano (art. 168, § 4º). É argumento de negociação, não ganho
garantido, e a decisão é do produtor com o contador.

**O que não usar como argumento: "o optante recupera o IBS/CBS dos insumos".** A compra de insumo
agropecuário do Anexo IX (fertilizantes, defensivos, sementes) entre contribuintes do regime regular
tem o recolhimento do IBS/CBS **diferido** (art. 138, § 2º, inciso I, alínea a).

- Quando o diferimento se encerra (§ 5º), recolhe quem promove a operação que o encerra (§ 6º). O
  recolhimento é dispensado quando a apropriação do crédito é permitida (§ 8º). O efeito sobre o
  crédito do produtor está com o contador (#284, pergunta C).
- Por isso o argumento fica fora. O crédito do art. 47 nas demais compras tributadas (serviços,
  bens fora dos Anexos e do art. 110) continua valendo.
- O sistema ainda não modela o diferimento (premissas, seção 1).

**Citações a não usar** (estão erradas no material da #284):

| Citação errada | Por quê | Correto |
|---|---|---|
| "art. 353" para o teste de 2026 | O art. 353 trata da fixação da alíquota de referência da CBS de 2027 | arts. 343 (IBS de 0,1%), 346 (CBS de 0,9%) e 348 (compensação e dispensa) |
| "arts. 43 a 45" como obrigações acessórias | Eles tratam da **apuração** (o art. 43 fixa o período mensal) | obrigação de emitir documento fiscal: art. 60 |
| "art. 15" para a CBS | O art. 15 trata só da alíquota do **IBS** (estado e município de destino) | a fixação das alíquotas da CBS e do IBS está no art. 14 |
| "art. 168, § 3º" para a divulgação do percentual | O § 3º diz como o crédito presumido é calculado | a divulgação anual até setembro está no **§ 4º** |

## Contrapontos que a banca pode levantar

| Contraponto | Resposta |
|---|---|
| "Optar tem custo: emitir nota com destaque, apurar, recolher." | Sim (veja "Contra a opção" acima). É o trabalho que o sistema automatiza (emissão, apuração, relatório). A decisão de optar continua do produtor com o contador; o sistema não decide. |
| "Quem vende para pessoa física ou consumidor final não ganha nada com o crédito do comprador." | Correto: para esse produtor a opção faz menos sentido. É o caso do não contribuinte, fora do escopo atual (#281). |
| "A opção é para sempre? O limite muda?" | A opção vale a partir do mês seguinte e é irretratável durante o ano-calendário (art. 165, §§ 1º e 2º). A renúncia produz efeito em 1º de janeiro do ano seguinte (art. 166), **mas fica vedada** a quem recebeu ressarcimento de crédito no ano corrente ou no anterior (art. 41, §§ 5º e 6º). O limite é atualizado todo ano pelo IPCA (art. 167). O sistema ainda não modela esse histórico (#281). |
| "E o produtor integrado?" | Pelo art. 164 o integrado não é contribuinte qualquer que seja a receita, salvo opção (art. 165). Mesmo tratamento do pequeno produtor: fora do escopo atual. |
| "Os produtos *in natura* têm redução de 60% (art. 137)." | Sim. Desde a tabela oficial (#272) a soja, o milho, o trigo e o algodão em grão saem com 60% pela marca *in natura* do NCM, e a demonstração mostra isso; o arroz em casca não parboilizado (1006.10.92) entrou na lista pela resposta do escritório (#284, pergunta 3; o parboilizado segue fora). O enquadramento segue em validação com o contador (#284, pergunta 1): não afirmar que ele é definitivo. |
| "A nota diz que o emitente é do Simples Nacional (CRT 1)." | Não diz mais (#298): produtor rural pessoa física não entra no Simples Nacional nem no MEI (LC 123/2006, art. 3º), então a NF-e e o DANFE dele saem com **CRT 3 (regime normal)** e o cadastro com `SIMPLES` é recusado. Nota autorizada antes da correção guarda o CRT da emissão: não usar uma delas na banca. |
| "E o ICMS da nota?" | A NF-e leva o grupo de ICMS por item, mas o sistema **não calcula** o ICMS (premissas, seção 3). Sem o grupo, a SEFAZ rejeitaria a NF-e (rejeição 1002, NT 2025.002, regra B25-90). **Padrão:** na venda interna de produção própria (CFOP 5101) a destinatário **com IE**, o XML sai com o **ICMS51, diferido** (CST 51, ICMS próprio zero). O escritório confirmou isso para a soja em MT (#284, pergunta 9); nos demais produtos e UFs o padrão é aplicado, e o sistema avisa para conferir com o contador. **Sem padrão:** venda interestadual, exportação, destinatário sem IE ou outro CFOP exigem o CST informado pelo contador. Sem ele, a emissão é barrada (422 `ICMS_A_DEFINIR`), e o sistema nunca escolhe o CST. O emitente do Simples ou MEI é barrado, porque informa CSOSN, que o sistema não emite. O cálculo do ICMS devido e o do ST seguem com o contador, por guia. O Funrural/Senar retido pelo comprador sai destacado nas informações complementares (#332). **Na VPS:** as notas já emitidas antes do #377 guardam o XML sem o grupo até o reset dos dados da demonstração. Não use uma delas para mostrar o ICMS. |

## Pendências que podem mudar este texto

- **#284, pergunta 7:** um contador confirmar se o argumento do crédito do comprador se sustenta e
  se há outro motivo comum para optar ou não.
- **#284, pergunta 1:** redução da soja em grão.
- **#284, pergunta 9 (respondida):** CST 51, diferido, na venda interna de soja **em MT**. Só esse
  par (UF; NCM) está confirmado (`icms/diferimento-confirmado.csv`). Os outros pares do seed da
  banca (café em MG, leite no PR, milho, algodão e os demais) seguem a confirmar com o contador.
- **Dados da demonstração:** o regime gravado do João e da Maria na VPS, corrigido pelo passo manual
  de [`demonstracao-banca.md`](demonstracao-banca.md) (#298). A #284, pergunta 16, confirma o CRT 3.
- **Ceres:** a pergunta "pequeno produtor paga IBS?" deve citar os arts. 164 e 165 (base já
  ingerida); pedida para o gabarito ampliado na #264.
