# Diagnóstico de rota no conjunto de desenvolvimento (#497)

Configuração: limiar 0.9, vizinhos 7, similaridade mínima 0.5, treino 262 exemplos.

**Decididas antes do modelo:** 130 de 130, com 123 certas; 0 vão ao modelo.

| camada | n | certas | erradas | ao modelo |
|---|---|---|---|---|
| ATALHO_DEFINICAO | 1 | 1 | 0 | 0 |
| CONVERSA_LEVE | 2 | 2 | 0 | 0 |
| EMBEDDING | 18 | 17 | 1 | 0 |
| LLM | 61 | 55 | 6 | 0 |
| REGRA | 40 | 40 | 0 | 0 |
| SINAL_RECUSA_FIRME | 8 | 8 | 0 | 0 |

| rota esperada | n | certas antes do modelo | erradas | ao modelo |
|---|---|---|---|---|
| DADOS | 47 | 44 | 3 | 0 |
| LEGISLACAO | 36 | 36 | 0 | 0 |
| MISTO | 19 | 17 | 2 | 0 |
| FORA_DE_ESCOPO | 28 | 26 | 2 | 0 |

Chamadas ao classificador por modelo no caminho de produção (desempate e pergunta de lei do MISTO): 65.

Latência da chamada de classificação ao modelo (158 chamadas, desta máquina até a API): p50 637 ms, p95 914 ms.

## Por pergunta

| id | tipo | esperada | camada | rota | ok | modelo | voto | conf | sim1 | vizinhos |
|---|---|---|---|---|---|---|---|---|---|---|
| D01 | dados | DADOS | REGRA | DADOS | ok |  | DADOS | 0.56 | 0.71 | MISTO 0.71 «Quanto comprei de adubo foliar este ano e adubo foliar tem redução?» MISTO 0.67 «Qual o total das minhas compras de fertilizante orgânico e esse adubo tem benefício?» DADOS 0.65 «Quanto paguei em lona e arame no mês passado?» |
| D02 | dados | DADOS | REGRA | DADOS | ok |  | DADOS | 0.87 | 0.73 | DADOS 0.73 «Minhas guias de outubro já foram pagas?» DADOS 0.69 «Alguma venda minha foi cancelada em outubro?» MISTO 0.67 «Minhas notas de outubro estão com o cClassTrib certo para a soja? Quanto vendi?» |
| D03 | dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 1.00 | 0.86 | DADOS 0.86 «Qual nota minha foi recusada pela SEFAZ?» DADOS 0.64 «Tenho nota parada esperando o contador aprovar?» DADOS 0.62 «A nota 330 já foi enviada para a SEFAZ?» |
| D04 | dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.52 | 0.72 | DADOS 0.72 «Tenho saldo credor de IBS?» DADOS 0.72 «Tem alguma pendência na minha conta?» MISTO 0.71 «Quanto paguei de energia elétrica e posso tomar crédito disso?» |
| D05 | dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.60 | 0.81 | DADOS 0.81 «Como ficou a minha apuração de IBS mês a mês neste ano?» DADOS 0.78 «Em qual mês do ano eu recolhi mais IBS?» DADOS 0.76 «Em qual mês eu tive mais crédito de IBS?» |
| D06 | dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 0.91 | 0.71 | DADOS 0.71 «Qual a minha situacao geral hoje?» DADOS 0.70 «O que eu preciso resolver hoje?» DADOS 0.67 «Tem alguma coisa pendente comigo no sistema?» |
| D07 | dados | DADOS | REGRA | DADOS | ok |  | DADOS | 0.89 | 0.68 | DADOS 0.68 «Minhas guias de outubro já foram pagas?» DADOS 0.66 «Tem alguma coisa pendente comigo no sistema?» DADOS 0.66 «Paguei a guia de setembro?» |
| D08 | dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.78 | 0.86 | MISTO 0.86 «Quanto vendi para a cooperativa e como fica o Funrural nessas vendas?» DADOS 0.74 «Quantas vendas eu fiz para a cooperativa este ano?» MISTO 0.68 «Quanto comprei da cooperativa e a compra de cooperativa tem crédito?» |
| D09 | dados | DADOS | REGRA | DADOS | ok |  | DADOS | 1.00 | 0.66 | DADOS 0.66 «Me mostra as notas que estão em rascunho» DADOS 0.64 «Tenho nota parada esperando o contador aprovar?» DADOS 0.61 «Quantas notas eu tenho no total este ano?» |
| D10 | dados | DADOS | REGRA | DADOS | ok |  | DADOS | 0.81 | 0.60 | DADOS 0.60 «Tenho nota parada esperando o contador aprovar?» DADOS 0.60 «Em que situação está a nota 512?» MISTO 0.59 «Minha nota 812 de venda de milho tem a redução certa da LC 214?» |
| D11 | dados | DADOS | REGRA | DADOS | ok |  | DADOS | 0.65 | 0.71 | DADOS 0.71 «Quanto rendeu a venda de milho da safrinha?» MISTO 0.62 «Quanto vendi de sorgo e o sorgo tem redução de 60%?» MISTO 0.60 «Quanto vendi de mel este ano e mel tem redução de alíquota?» |
| D12 | dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 1.00 | 0.66 | DADOS 0.66 «Qual foi a minha maior venda deste mês?» DADOS 0.64 «De quantos fornecedores diferentes eu comprei este ano?» DADOS 0.61 «Quais notas de compra eu importei ontem?» |
| D13 | dados | DADOS | EMBEDDING | DADOS | ok | FORA,FORA | DADOS | 0.93 | 0.84 | DADOS 0.84 «O contador me pediu algum documento?» DADOS 0.69 «Quais pedidos do escritório estão pendentes?» DADOS 0.66 «Tem alguma coisa pendente comigo no sistema?» |
| D14 | dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 1.00 | 0.86 | DADOS 0.86 «Qual a minha situacao geral hoje?» DADOS 0.73 «Tem alguma coisa pendente comigo no sistema?» DADOS 0.66 «Tem alguma pendência na minha conta?» |
| D15 | dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 1.00 | 0.73 | DADOS 0.73 «Qual o histórico de avisos que o sistema me mandou?» DADOS 0.69 «Quantos avisos de vencimento eu recebi no mês?» DADOS 0.68 «Tem alguma pendência na minha conta?» |
| D16 | dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.69 | 0.74 | DADOS 0.74 «O feijão tem desconto no imposto?» DADOS 0.67 «O leite que eu vendo tem alíquota reduzida?» MISTO 0.61 «A redução de alíquota vale para o café que vendi neste trimestre? E quanto foi?» |
| D17 | dados | DADOS | LLM | LEI | ERRO | LEI,LEI | LEI | 0.52 | 0.67 | DADOS 0.67 «Quanto de CBS incide no tomate?» MISTO 0.66 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» LEI 0.66 «Serviço prestado ao exterior paga CBS?» |
| D18 | dados | DADOS | LLM | LEI | ERRO | LEI,LEI | DADOS | 0.64 | 0.55 | DADOS 0.55 «O NCM 0713.33.19 tem redução?» FORA 0.47 «Qual remédio tomar para dor de cabeça?» MISTO 0.44 «Quanto vendi de cana e cana-de-açúcar tem tratamento especial no IBS?» |
| D19 | dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.60 | 0.76 | DADOS 0.76 «Quanto saiu de combustível nas minhas notas de setembro?» MISTO 0.72 «As vendas de frutas que eu fiz em setembro têm isenção? Quanto foram?» DADOS 0.71 «Paguei a guia de setembro?» |
| D20 | dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 0.95 | 0.74 | DADOS 0.74 «A apuração de setembro já fechou?» DADOS 0.58 «A apuração de setembro foi reaberta pelo contador?» DADOS 0.51 «Minhas guias de outubro já foram pagas?» |
| D21 | dados | DADOS | REGRA | DADOS | ok |  | DADOS | 1.00 | 0.75 | DADOS 0.75 «Quantas notas eu tenho no total este ano?» DADOS 0.70 «Lista as notas que eu emiti na semana passada» DADOS 0.65 «Qual foi a primeira nota que emiti este ano?» |
| D22 | dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 1.00 | 0.94 | DADOS 0.94 «Tenho nota parada esperando o contador aprovar?» DADOS 0.75 «O contador me pediu algum documento?» DADOS 0.70 «Tem alguma pendência na minha conta?» |
| D23 | dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.53 | 0.70 | MISTO 0.70 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» FORA 0.67 «Qual o preço do diesel no posto hoje?» DADOS 0.64 «De quantos fornecedores diferentes eu comprei este ano?» |
| D24 | dados | DADOS | REGRA | DADOS | ok |  | DADOS | 1.00 | 0.79 | DADOS 0.79 «Qual foi o valor da última guia que eu paguei?» DADOS 0.73 «Quanto paguei de guias no ano passado?» DADOS 0.73 «Qual o maior valor de guia que já paguei?» |
| D25 | dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 1.00 | 0.75 | DADOS 0.75 «Tem alguma pendência na minha conta?» DADOS 0.74 «Tenho nota parada esperando o contador aprovar?» DADOS 0.71 «O contador me pediu algum documento?» |
| D26 | dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 0.94 | 0.84 | DADOS 0.84 «Quanto eu recebi de vendas no ano passado?» DADOS 0.70 «Qual foi a despesa com ração para o gado no último trimestre?» DADOS 0.70 «Quantas vendas eu fiz para a cooperativa este ano?» |
| D27 | dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.58 | 0.62 | MISTO 0.62 «Quanto vendi de sorgo e o sorgo tem redução de 60%?» DADOS 0.54 «O leite que eu vendo tem alíquota reduzida?» DADOS 0.53 «Somando as notas de ureia de março a maio, quanto deu?» |
| D28 | dados | DADOS | LLM | MISTO | ERRO | MISTO,MISTO | MISTO | 0.51 | 0.73 | MISTO 0.73 «Qual o total das minhas compras de peças e peça de máquina gera crédito de CBS?» LEI 0.71 «Energia elétrica usada na fazenda gera crédito de CBS?» MISTO 0.69 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» |
| L01 | lei | LEI | REGRA | LEI | ok |  | LEI | 1.00 | 0.78 | LEI 0.78 «Produtor rural pessoa física precisa se inscrever no cadastro do IBS?» LEI 0.75 «Venda de imóvel rural tem IBS?» LEI 0.75 «Doação de produtos agrícolas paga IBS?» |
| L02 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.89 | 0.67 | LEI 0.67 «Produtor rural pessoa física precisa se inscrever no cadastro do IBS?» LEI 0.65 «Doação de produtos agrícolas paga IBS?» LEI 0.65 «Precisa destacar IBS e CBS na nota de venda de gado?» |
| L03 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.97 | 0.84 | LEI 0.84 «O split payment vale para pagamento em dinheiro?» LEI 0.80 «O split payment começa a valer em que ano?» LEI 0.72 «O produtor precisa ter conta em banco para o split payment?» |
| L04 | lei | LEI | REGRA | LEI | ok |  | MISTO | 0.58 | 0.70 | MISTO 0.70 «Quanto foi o crédito presumido das minhas compras de produtor rural e qual a regra?» LEI 0.65 «O crédito presumido tem limite de valor?» LEI 0.65 «Como é calculado o valor do crédito presumido?» |
| L05 | lei | LEI | REGRA | LEI | ok |  | MISTO | 0.62 | 0.66 | MISTO 0.66 «Vacina para o rebanho entra como insumo com redução? E quanto foi meu gasto com isso?» MISTO 0.64 «Quanto comprei de medicamento veterinário e tem redução de alíquota?» LEI 0.63 «Insumo veterinário tem redução de alíquota?» |
| L06 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.80 | 0.82 | LEI 0.82 «Quando começa a cobrança integral do IBS?» DADOS 0.69 «Em qual mês do ano eu recolhi mais IBS?» LEI 0.68 «Qual é o prazo para recolher o IBS e a CBS?» |
| L07 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.82 | 0.90 | LEI 0.90 «Como será a cobrança da CBS em 2027?» LEI 0.71 «Como será definida a alíquota da CBS depois da transição?» MISTO 0.68 «Quanto de CBS paguei em outubro e como a CBS de 2026 é compensada?» |
| L08 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.61 | 0.63 | LEI 0.63 «O que é o Funrural pela Lei 8.212?» LEI 0.62 «A reforma acaba com o PIS e a Cofins?» MISTO 0.62 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» |
| L09 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.90 | 0.82 | LEI 0.82 «Serviço prestado ao exterior paga CBS?» LEI 0.72 «O IBS incide sobre exportação?» LEI 0.71 «Precisa destacar IBS e CBS na nota de venda de gado?» |
| L10 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.80 | 0.72 | LEI 0.72 «A cesta básica nacional foi criada por qual norma?» MISTO 0.63 «Quanto vendi de trigo e o trigo entra na cesta básica?» LEI 0.51 «Quais receitas entram no cálculo do faturamento do produtor rural?» |
| L11 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.69 | 0.74 | MISTO 0.74 «Quanto ficou de saldo a pagar de IBS e quando vence pela lei?» LEI 0.73 «Arrendamento de terra paga IBS?» LEI 0.73 «O que a Constituição fala sobre o IBS?» |
| L12 | lei | LEI | LLM | LEI | ok | LEI,LEI | LEI | 0.62 | 0.66 | LEI 0.66 «Como funciona o diferimento do IBS para insumos agropecuários?» DADOS 0.60 «O feijão tem desconto no imposto?» LEI 0.60 «Como a reforma trata a importação de máquinas agrícolas?» |
| L13 | lei | LEI | LLM | LEI | ok | LEI,LEI | LEI | 0.56 | 0.74 | LEI 0.74 «Cogumelo está entre os hortícolas da alíquota zero?» MISTO 0.60 «Minhas notas de venda de tomate saíram com alíquota zero como a lei prevê?» MISTO 0.59 «Quanto vendi de ovos no mês e ovos têm alíquota zero?» |
| L14 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.77 | 0.63 | LEI 0.63 «Qual a alíquota do SENAR?» LEI 0.53 «O que é o imposto seletivo e quem paga?» DADOS 0.51 «Tem alguma pendência na minha conta?» |
| L15 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.56 | 0.67 | LEI 0.67 «O produtor pode optar por contribuir sobre a folha em vez do Funrural?» MISTO 0.66 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» LEI 0.65 «O Funrural incide sobre a venda de sementes para plantio?» |
| L16 | lei | LEI | REGRA | LEI | ok |  | LEI | 1.00 | 0.74 | LEI 0.74 «Doação de produtos agrícolas paga IBS?» LEI 0.71 «Produtor rural pessoa física precisa se inscrever no cadastro do IBS?» LEI 0.67 «Venda de imóvel rural tem IBS?» |
| L17 | lei | LEI | LLM | LEI | ok | LEI,LEI | LEI | 0.70 | 0.67 | MISTO 0.67 «Quanto comprei da cooperativa e a compra de cooperativa tem crédito?» LEI 0.61 «Quais obrigações acessórias o produtor terá com a reforma?» LEI 0.60 «Como a reforma trata a importação de máquinas agrícolas?» |
| L18 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.80 | 0.77 | LEI 0.77 «O que é o imposto seletivo e quem paga?» DADOS 0.60 «O feijão tem desconto no imposto?» FORA 0.57 «Como se tornar um influenciador do agro?» |
| L19 | lei | LEI | REGRA | LEI | ok |  | LEI | 1.00 | 0.67 | LEI 0.67 «Como funciona a alíquota de referência do IBS?» LEI 0.61 «Como funciona o cashback do IBS para famílias de baixa renda?» LEI 0.60 «Como funciona o fato gerador do IBS na venda de mercadoria?» |
| L20 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.90 | 0.74 | LEI 0.74 «Venda de imóvel rural tem IBS?» LEI 0.74 «Doação de produtos agrícolas paga IBS?» LEI 0.72 «Combustível dá direito a crédito de IBS?» |
| L21 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.62 | 0.70 | LEI 0.70 «Arrendamento de terra paga IBS?» MISTO 0.70 «Quanto paguei de arrendamento e arrendamento tem IBS?» LEI 0.69 «Serviço prestado ao exterior paga CBS?» |
| L22 | lei | LEI | REGRA | LEI | ok |  | LEI | 0.90 | 0.78 | LEI 0.78 «O artigo 138 trata de quê?» LEI 0.59 «Quais itens estão listados no Anexo IX da LC 214?» MISTO 0.56 «A redução que aparece nas minhas notas de café está certa pela LC 214?» |
| L23 | lei | LEI | LLM | LEI | ok | LEI,LEI | MISTO | 0.72 | 0.70 | MISTO 0.70 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» LEI 0.60 «O Funrural incide sobre a venda de sementes para plantio?» MISTO 0.58 «Quanto vendi para a cooperativa e como fica o Funrural nessas vendas?» |
| L24 | lei | LEI | EMBEDDING | LEI | ok | LEI,LEI | LEI | 0.94 | 0.70 | LEI 0.70 «Quais obrigações acessórias o produtor terá com a reforma?» LEI 0.69 «Como a reforma trata a importação de máquinas agrícolas?» LEI 0.62 «A reforma acaba com o PIS e a Cofins?» |
| L25 | lei | LEI | LLM | LEI | ok | LEI,LEI | MISTO | 0.66 | 0.74 | MISTO 0.74 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» LEI 0.64 «O que é o Funrural pela Lei 8.212?» MISTO 0.64 «Tenho guia de Funrural vencida e qual a multa prevista na lei?» |
| L26 | lei | LEI | REGRA | LEI | ok |  | MISTO | 0.62 | 0.67 | LEI 0.67 «O que é o Funrural pela Lei 8.212?» MISTO 0.66 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» MISTO 0.66 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» |
| M01 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | LEI | 0.78 | 0.74 | LEI 0.74 «Doação de produtos agrícolas paga IBS?» LEI 0.74 «Combustível dá direito a crédito de IBS?» LEI 0.71 «Venda de imóvel rural tem IBS?» |
| M02 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.52 | 0.67 | MISTO 0.67 «Minhas notas de venda de tomate saíram com alíquota zero como a lei prevê?» MISTO 0.66 «Minhas notas de outubro estão com o cClassTrib certo para a soja? Quanto vendi?» DADOS 0.63 «O feijão tem desconto no imposto?» |
| M03 | misto | MISTO | EMBEDDING | MISTO | ok | MISTO,MISTO | MISTO | 0.93 | 0.70 | MISTO 0.70 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» MISTO 0.68 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» MISTO 0.66 «Tenho guia de Funrural vencida e qual a multa prevista na lei?» |
| M04 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.89 | 0.69 | MISTO 0.69 «O IBS da minha apuração de outubro considera a redução dos insumos? Quanto deu?» MISTO 0.63 «Quanto gastei com mudas de eucalipto e muda é insumo com redução?» MISTO 0.61 «Quanto vendi de sorgo e o sorgo tem redução de 60%?» |
| M05 | misto | MISTO | EMBEDDING | MISTO | ok | MISTO,MISTO | MISTO | 0.90 | 0.73 | MISTO 0.73 «Minhas compras de calcário deste ano dão direito a crédito?» MISTO 0.71 «Quanto comprei da cooperativa e a compra de cooperativa tem crédito?» MISTO 0.68 «Quanto comprei de máquina este ano e a lei dá crédito na compra de máquina?» |
| M06 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | LEI | 0.47 | 0.73 | LEI 0.73 «O IBS de 2026 é compensado com o PIS?» MISTO 0.69 «Quanto ficou de saldo a pagar de IBS e quando vence pela lei?» LEI 0.67 «Quando começa a cobrança integral do IBS?» |
| M07 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.52 | 0.75 | MISTO 0.75 «Qual o total das minhas compras de peças e peça de máquina gera crédito de CBS?» MISTO 0.72 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» LEI 0.72 «Como pedir ressarcimento de saldo credor de CBS?» |
| M08 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.56 | 0.71 | MISTO 0.71 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» DADOS 0.68 «Quanto vendi para fora do estado este ano?» MISTO 0.65 «Minhas notas de venda de tomate saíram com alíquota zero como a lei prevê?» |
| M09 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | DADOS | 0.46 | 0.75 | DADOS 0.75 «Tenho saldo credor de IBS?» MISTO 0.74 «Quanto ficou de saldo a pagar de IBS e quando vence pela lei?» MISTO 0.72 «Tenho guia de IBS para pagar e qual o prazo legal de recolhimento?» |
| M10 | misto | MISTO | LLM | LEI | ERRO | LEI,MISTO | MISTO | 0.63 | 0.80 | MISTO 0.80 «Tenho guia de Funrural vencida e qual a multa prevista na lei?» DADOS 0.67 «Qual guia de Funrural eu paguei por último?» MISTO 0.66 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» |
| M11 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | LEI | 1.00 | 0.72 | LEI 0.72 «Precisa destacar IBS e CBS na nota de venda de gado?» LEI 0.71 «Venda de imóvel rural tem IBS?» LEI 0.70 «Doação de produtos agrícolas paga IBS?» |
| M12 | misto | MISTO | EMBEDDING | MISTO | ok | MISTO,MISTO | MISTO | 1.00 | 0.67 | MISTO 0.67 «A redução que aparece nas minhas notas de café está certa pela LC 214?» MISTO 0.65 «Quanto comprei de máquina este ano e a lei dá crédito na compra de máquina?» MISTO 0.64 «Minha apuração do mês tem saldo credor; a lei permite transferir esse crédito?» |
| M13 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | LEI | 0.73 | 0.77 | LEI 0.77 «O split payment vale para pagamento em dinheiro?» LEI 0.75 «O split payment começa a valer em que ano?» LEI 0.71 «O produtor precisa ter conta em banco para o split payment?» |
| M14 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.73 | 0.80 | MISTO 0.80 «Quanto foi o crédito presumido das minhas compras de produtor rural e qual a regra?» LEI 0.72 «O crédito presumido tem limite de valor?» MISTO 0.72 «As minhas compras de ureia de outubro geram crédito presumido?» |
| M15 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.89 | 0.72 | MISTO 0.72 «Minha nota 812 de venda de milho tem a redução certa da LC 214?» MISTO 0.69 «O valor de IBS das minhas vendas de milho respeita a redução do produto in natura?» MISTO 0.68 «A redução que aparece nas minhas notas de café está certa pela LC 214?» |
| M16 | misto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.73 | 0.71 | MISTO 0.71 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» DADOS 0.65 «Quanto saiu de combustível nas minhas notas de setembro?» DADOS 0.65 «Quais notas de compra eu importei ontem?» |
| F01 | fora | FORA | LLM | FORA | ok | FORA,FORA | FORA | 0.78 | 0.51 | FORA 0.51 «Que dia cai o Carnaval do ano que vem?» FORA 0.49 «Como está o trânsito na rodovia?» FORA 0.48 «Qual é a previsão de safra de soja do país?» |
| F02 | fora | FORA | LLM | FORA | ok | FORA,FORA | FORA | 0.72 | 0.64 | FORA 0.64 «Qual é a previsão de safra de soja do país?» FORA 0.60 «Qual o preço do diesel no posto hoje?» MISTO 0.58 «Quanto vendi de sorgo e o sorgo tem redução de 60%?» |
| F03 | fora | FORA | LLM | FORA | ok | FORA,FORA | LEI | 0.65 | 0.62 | FORA 0.62 «Invente um nome para minha fazenda» LEI 0.60 «Quais receitas entram no cálculo do faturamento do produtor rural?» LEI 0.57 «Produtor rural pessoa física precisa se inscrever no cadastro do IBS?» |
| F04 | fora | FORA | LLM | FORA | ok | FORA,FORA | FORA | 0.80 | 0.56 | FORA 0.56 «Que horas são agora?» DADOS 0.56 «Qual foi meu gasto com herbicida 2,4-D na safrinha?» FORA 0.54 «Qual a melhor época para plantar feijão?» |
| F05 | fora | FORA | LLM | FORA | ok | FORA,FORA | FORA | 0.58 | 0.67 | FORA 0.67 «Me ajuda a escrever um currículo» MISTO 0.64 «Quanto paguei de arrendamento e arrendamento tem IBS?» DADOS 0.59 «O contador me pediu algum documento?» |
| F06 | fora | FORA | LLM | FORA | ok | FORA,FORA | MISTO | 0.44 | 0.66 | MISTO 0.66 «Quanto paguei de arrendamento e arrendamento tem IBS?» LEI 0.63 «Arrendamento de terra paga IBS?» LEI 0.63 «Venda de imóvel rural tem IBS?» |
| F07 | fora | FORA | LLM | FORA | ok | FORA,FORA | - | - | - | - |
| F08 | fora | FORA | LLM | FORA | ok | FORA,FORA | LEI | 0.37 | 0.57 | LEI 0.57 «Qual a alíquota do SENAR?» FORA 0.55 «Qual é a previsão de safra de soja do país?» MISTO 0.53 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» |
| F09 | fora | FORA | LLM | FORA | ok | FORA,FORA | FORA | 0.88 | 0.58 | FORA 0.58 «Invente um nome para minha fazenda» FORA 0.56 «Como faço para tirar CNH?» FORA 0.55 «Me conta uma piada de fazendeiro» |
| F10 | fora | FORA | LLM | FORA | ok | FORA,FORA | FORA | 0.76 | 0.65 | FORA 0.65 «Qual trator é melhor para uma fazenda pequena?» LEI 0.64 «Como fica o crédito na compra de trator?» FORA 0.62 «Quem inventou o trator?» |
| F11 | fora | FORA | LLM | FORA | ok | FORA,FORA | LEI | 0.39 | 0.64 | MISTO 0.64 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» FORA 0.62 «Qual o preço do diesel no posto hoje?» LEI 0.57 «Combustível dá direito a crédito de IBS?» |
| F12 | fora | FORA | LLM | FORA | ok | FORA,FORA | FORA | 0.63 | 0.60 | FORA 0.60 «Me ajuda a escrever um currículo» DADOS 0.57 «Me mostra o resumo das minhas notas do mês» FORA 0.55 «Me explique a teoria da relatividade» |
| R01 | recusa | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 1.00 | 0.69 | DADOS 0.69 «Me mostra o resumo das minhas notas do mês» DADOS 0.68 «Me mostra as notas que estão em rascunho» DADOS 0.56 «Me da um panorama do que esta em aberto na minha conta» |
| R02 | recusa | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 0.90 | 0.59 | DADOS 0.59 «Quanto eu recebi de vendas no ano passado?» DADOS 0.59 «Quanto vendi para fora do estado este ano?» DADOS 0.59 «Quantas vendas eu fiz para a cooperativa este ano?» |
| R03 | recusa | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 0.76 | 0.58 | DADOS 0.58 «Qual o histórico de avisos que o sistema me mandou?» DADOS 0.55 «Me mostra as notas que estão em rascunho» DADOS 0.54 «Me mostra o resumo das minhas notas do mês» |
| R04 | recusa | FORA | LLM | FORA | ok | FORA,FORA | DADOS | 0.83 | 0.68 | DADOS 0.68 «O contador me pediu algum documento?» DADOS 0.64 «Tenho nota parada esperando o contador aprovar?» DADOS 0.62 «Me da um panorama do que esta em aberto na minha conta» |
| R05 | recusa | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 1.00 | 0.68 | DADOS 0.68 «Minhas guias de outubro já foram pagas?» DADOS 0.65 «Qual foi o valor da última guia que eu paguei?» DADOS 0.65 «Quais guias eu já paguei em outubro?» |
| R06 | recusa | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 0.54 | 0.57 | LEI 0.57 «Quais receitas entram no cálculo do faturamento do produtor rural?» DADOS 0.50 «De quantos fornecedores diferentes eu comprei este ano?» LEI 0.50 «O produtor precisa ter conta em banco para o split payment?» |
| R07 | recusa | FORA | LLM | FORA | ok | FORA,FORA | DADOS | 0.87 | 0.62 | DADOS 0.62 «Tenho nota parada esperando o contador aprovar?» DADOS 0.60 «Me da um panorama do que esta em aberto na minha conta» DADOS 0.60 «Me mostra as notas que estão em rascunho» |
| R08 | recusa | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 0.73 | 0.67 | DADOS 0.67 «Me da um panorama do que esta em aberto na minha conta» DADOS 0.66 «Tenho saldo credor de IBS?» MISTO 0.65 «Minha apuração do mês tem saldo credor; a lei permite transferir esse crédito?» |
| R09 | recusa | FORA | EMBEDDING | DADOS | ERRO | FORA,FORA | DADOS | 1.00 | 0.66 | DADOS 0.66 «Tenho nota parada esperando o contador aprovar?» DADOS 0.64 «Me mostra o resumo das minhas notas do mês» DADOS 0.62 «Me mostra as notas que estão em rascunho» |
| R10 | recusa | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 1.00 | 0.69 | DADOS 0.69 «Tenho nota parada esperando o contador aprovar?» DADOS 0.63 «Qual nota minha foi recusada pela SEFAZ?» DADOS 0.58 «Alguma venda minha foi cancelada em outubro?» |
| R11 | recusa | FORA | LLM | DADOS | ERRO | DADOS,DADOS | DADOS | 0.68 | 0.59 | DADOS 0.59 «Qual foi o faturamento com algodão em pluma este ano?» DADOS 0.57 «Quanto eu recebi de vendas no ano passado?» DADOS 0.55 «Quantas vendas eu fiz para a cooperativa este ano?» |
| R12 | recusa | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 0.54 | 0.64 | DADOS 0.64 «Me da um panorama do que esta em aberto na minha conta» MISTO 0.59 «Minha apuração do mês tem saldo credor; a lei permite transferir esse crédito?» DADOS 0.57 «Tem alguma pendência na minha conta?» |
| A01 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.52 | 0.68 | DADOS 0.68 «Quanto eu recebi de vendas no ano passado?» MISTO 0.67 «Vendi soja para outro estado; quanto foi e o IBS fica com qual estado?» DADOS 0.66 «Quantas vendas eu fiz para a cooperativa este ano?» |
| A02 | adversario | LEI | LLM | LEI | ok | LEI,LEI | MISTO | 0.62 | 0.60 | MISTO 0.60 «Tenho guia de Funrural vencida e qual a multa prevista na lei?» MISTO 0.60 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» DADOS 0.58 «Qual guia de Funrural eu paguei por último?» |
| A03 | adversario | MISTO | LLM | LEI | ERRO | LEI,MISTO | LEI | 0.66 | 0.83 | LEI 0.83 «Como é calculado o valor do crédito presumido?» LEI 0.82 «O crédito presumido tem limite de valor?» MISTO 0.72 «As minhas compras de ureia de outubro geram crédito presumido?» |
| A04 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | LEI | 0.55 | 0.61 | LEI 0.61 «Precisa destacar IBS e CBS na nota de venda de gado?» LEI 0.58 «O IBS incide sobre exportação?» DADOS 0.57 «Como ficou a minha apuração de IBS mês a mês neste ano?» |
| A05 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.58 | 0.79 | MISTO 0.79 «O IBS da minha apuração de outubro considera a redução dos insumos? Quanto deu?» MISTO 0.75 «Qual o débito de IBS das minhas vendas de café e qual alíquota foi usada?» DADOS 0.71 «Em qual mês do ano eu recolhi mais IBS?» |
| A06 | adversario | DADOS | REGRA | DADOS | ok |  | DADOS | 1.00 | 0.67 | DADOS 0.67 «Minhas guias de outubro já foram pagas?» DADOS 0.66 «Qual foi o valor da última guia que eu paguei?» DADOS 0.65 «Quanto paguei de guias no ano passado?» |
| A07 | adversario | FORA | CONVERSA_LEVE | FORA | ok |  | FORA | 0.80 | 0.67 | FORA 0.67 «Como se diz obrigado em japonês?» FORA 0.57 «Me dá uma ideia de presente de aniversário» FORA 0.56 «Me ajuda a escrever um currículo» |
| A08 | adversario | FORA | CONVERSA_LEVE | FORA | ok |  | - | - | - | - |
| A09 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.71 | 0.77 | MISTO 0.77 «Minhas notas de venda de tomate saíram com alíquota zero como a lei prevê?» LEI 0.67 «Cogumelo está entre os hortícolas da alíquota zero?» MISTO 0.63 «Quanto vendi de ovos no mês e ovos têm alíquota zero?» |
| A10 | adversario | LEI | LLM | LEI | ok | LEI,LEI | LEI | 0.60 | 0.69 | LEI 0.69 «Cogumelo está entre os hortícolas da alíquota zero?» MISTO 0.60 «Minhas notas de venda de tomate saíram com alíquota zero como a lei prevê?» MISTO 0.57 «Minhas vendas de laranja do trimestre: quanto foram e laranja tem alíquota zero?» |
| A11 | adversario | LEI | ATALHO_DEFINICAO | LEI | ok |  | LEI | 1.00 | 0.84 | LEI 0.84 «Quem administra a CBS?» LEI 0.76 «O que é a base de cálculo da CBS?» LEI 0.72 «Qual a diferença entre IBS e CBS?» |
| A12 | adversario | DADOS | REGRA | DADOS | ok |  | DADOS | 0.42 | 0.61 | LEI 0.61 «Precisa destacar IBS e CBS na nota de venda de gado?» DADOS 0.59 «Qual o valor médio das minhas notas de venda?» DADOS 0.59 «Qual o total das minhas vendas de boi gordo no trimestre?» |
| A13 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.74 | 0.75 | MISTO 0.75 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» MISTO 0.73 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» MISTO 0.65 «Tenho guia de Funrural vencida e qual a multa prevista na lei?» |
| A14 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.56 | 0.78 | MISTO 0.78 «Quanto de IBS municipal saiu nas minhas notas e quem fica com esse dinheiro?» DADOS 0.76 «Como ficou a minha apuração de IBS mês a mês neste ano?» DADOS 0.75 «Tenho saldo credor de IBS?» |
| A15 | adversario | MISTO | LLM | MISTO | ok | MISTO,MISTO | LEI | 0.89 | 0.78 | LEI 0.78 «O split payment vale para pagamento em dinheiro?» LEI 0.74 «O split payment começa a valer em que ano?» LEI 0.73 «O produtor precisa ter conta em banco para o split payment?» |
| A16 | adversario | DADOS | REGRA | DADOS | ok |  | DADOS | 0.52 | 0.59 | MISTO 0.59 «Quanto ficou de saldo a pagar de IBS e quando vence pela lei?» LEI 0.56 «Quando começa a cobrança integral do IBS?» DADOS 0.56 «Quantos avisos de vencimento eu recebi no mês?» |
| A17 | adversario | LEI | EMBEDDING | LEI | ok | LEI,LEI | LEI | 1.00 | 0.81 | LEI 0.81 «Quais obrigações acessórias o produtor terá com a reforma?» LEI 0.71 «Como a reforma trata a importação de máquinas agrícolas?» LEI 0.70 «Quais receitas entram no cálculo do faturamento do produtor rural?» |
| A18 | adversario | DADOS | REGRA | DADOS | ok |  | DADOS | 1.00 | 0.68 | DADOS 0.68 «A nota 77 já foi autorizada?» DADOS 0.64 «Qual nota minha foi recusada pela SEFAZ?» DADOS 0.58 «O NCM 0713.33.19 tem redução?» |
| A19 | adversario | LEI | REGRA | LEI | ok |  | MISTO | 0.65 | 0.71 | MISTO 0.71 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» LEI 0.63 «O Funrural incide sobre a venda de sementes para plantio?» LEI 0.62 «O que é o Funrural pela Lei 8.212?» |
| A20 | adversario | FORA | LLM | FORA | ok | FORA,FORA | MISTO | 0.85 | 0.51 | MISTO 0.51 «Minhas vendas de laranja do trimestre: quanto foram e laranja tem alíquota zero?» DADOS 0.48 «Quanto eu recebi de vendas no ano passado?» MISTO 0.47 «As vendas de frutas que eu fiz em setembro têm isenção? Quanto foram?» |
| A21 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.53 | 0.62 | DADOS 0.62 «O NCM 0713.33.19 tem redução?» MISTO 0.54 «Quanto vendi de sorgo e o sorgo tem redução de 60%?» MISTO 0.53 «Quanto comprei de medicamento veterinário e tem redução de alíquota?» |
| A22 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.57 | 0.66 | MISTO 0.66 «A redução de alíquota vale para o café que vendi neste trimestre? E quanto foi?» MISTO 0.60 «A redução que aparece nas minhas notas de café está certa pela LC 214?» DADOS 0.57 «Quanto faturei com café no ano passado?» |
| A23 | adversario | LEI | REGRA | LEI | ok |  | LEI | 0.59 | 0.70 | LEI 0.70 «Como é calculado o valor do crédito presumido?» MISTO 0.69 «Quanto foi o crédito presumido das minhas compras de produtor rural e qual a regra?» LEI 0.68 «O crédito presumido tem limite de valor?» |
| A24 | adversario | DADOS | REGRA | DADOS | ok |  | MISTO | 0.62 | 0.72 | MISTO 0.72 «Quanto paguei de energia elétrica e posso tomar crédito disso?» MISTO 0.68 «Quanto paguei de frete nas compras e frete gera crédito?» MISTO 0.68 «Quanto comprei da cooperativa e a compra de cooperativa tem crédito?» |
| A25 | adversario | LEI | REGRA | LEI | ok |  | MISTO | 0.41 | 0.69 | DADOS 0.69 «Tenho saldo credor de IBS?» LEI 0.67 «Quando começa a cobrança integral do IBS?» MISTO 0.67 «Tenho guia de IBS para pagar e qual o prazo legal de recolhimento?» |
| A26 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.69 | 0.58 | MISTO 0.58 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» MISTO 0.54 «Quanto vendi para a cooperativa e como fica o Funrural nessas vendas?» LEI 0.52 «A exportação de soja paga Funrural?» |
| A27 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.72 | 0.75 | MISTO 0.75 «Tenho guia de Funrural vencida e qual a multa prevista na lei?» DADOS 0.72 «Minhas guias de outubro já foram pagas?» DADOS 0.69 «Qual guia de Funrural eu paguei por último?» |
| A28 | adversario | LEI | REGRA | LEI | ok |  | MISTO | 0.75 | 0.69 | MISTO 0.69 «Quanto vendi para a cooperativa e como fica o Funrural nessas vendas?» MISTO 0.68 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» LEI 0.66 «O produtor pode optar por contribuir sobre a folha em vez do Funrural?» |
| A29 | adversario | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 1.00 | 0.74 | DADOS 0.74 «Tem alguma coisa pendente comigo no sistema?» DADOS 0.70 «Quais pedidos do escritório estão pendentes?» DADOS 0.66 «O que eu preciso resolver hoje?» |
| A30 | adversario | LEI | REGRA | LEI | ok |  | MISTO | 0.51 | 0.67 | MISTO 0.67 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» LEI 0.66 «O Funrural incide sobre a venda de sementes para plantio?» LEI 0.66 «A exportação de soja paga Funrural?» |
| A31 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.87 | 0.61 | DADOS 0.61 «Me da um panorama do que esta em aberto na minha conta» DADOS 0.61 «Tem alguma pendência na minha conta?» DADOS 0.61 «Tem algo urgente para eu resolver?» |
| A32 | adversario | MISTO | EMBEDDING | MISTO | ok | MISTO,MISTO | MISTO | 1.00 | 0.73 | MISTO 0.73 «Quanto comprei da cooperativa e a compra de cooperativa tem crédito?» MISTO 0.72 «Quanto comprei de máquina este ano e a lei dá crédito na compra de máquina?» MISTO 0.70 «Quanto paguei de energia elétrica e posso tomar crédito disso?» |
| A33 | adversario | FORA | LLM | FORA | ok | FORA,FORA | DADOS | 0.46 | 0.56 | DADOS 0.56 «O contador me pediu algum documento?» FORA 0.56 «Qual o melhor seguro para a lavoura?» MISTO 0.53 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» |
| A34 | adversario | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.38 | 0.79 | DADOS 0.79 «Na venda de cana-de-açúcar o IBS é reduzido?» MISTO 0.72 «O valor de IBS das minhas vendas de milho respeita a redução do produto in natura?» MISTO 0.71 «Quanto vendi de boi gordo e gado vivo tem redução de IBS?» |
| A35 | adversario | LEI | REGRA | LEI | ok |  | LEI | 1.00 | 0.76 | LEI 0.76 «Produtor rural pessoa física precisa se inscrever no cadastro do IBS?» LEI 0.75 «Venda de imóvel rural tem IBS?» LEI 0.69 «Doação de produtos agrícolas paga IBS?» |
| A36 | adversario | DADOS | REGRA | DADOS | ok |  | DADOS | 0.85 | 0.71 | DADOS 0.71 «Tenho nota parada esperando o contador aprovar?» DADOS 0.64 «Alguma venda minha foi cancelada em outubro?» DADOS 0.61 «Quantas notas eu tenho no total este ano?» |

## Varredura no conjunto de desenvolvimento (perguntas que chegam às embeddings)

Célula = decididas / erradas (as demais vão ao modelo).

| sim1 \ limiar | 0.60 | 0.70 | 0.80 | 0.85 | 0.90 | 0.95 | 0.99 |
|---|---|---|---|---|---|---|---|
| 0.50 | 52 / 17 | 41 / 12 | 28 / 6 | 26 / 5 | 19 / 2 | 13 / 2 | 12 / 2 |
| 0.60 | 45 / 14 | 37 / 11 | 25 / 5 | 24 / 4 | 19 / 2 | 13 / 2 | 12 / 2 |
| 0.65 | 39 / 12 | 33 / 10 | 23 / 4 | 22 / 3 | 19 / 2 | 13 / 2 | 12 / 2 |
| 0.70 | 29 / 10 | 25 / 8 | 16 / 2 | 16 / 2 | 14 / 1 | 10 / 1 | 9 / 1 |
| 0.75 | 14 / 6 | 12 / 5 | 7 / 1 | 7 / 1 | 6 / 0 | 4 / 0 | 4 / 0 |
| 0.80 | 8 / 2 | 7 / 1 | 6 / 0 | 6 / 0 | 6 / 0 | 4 / 0 | 4 / 0 |

(78 chegam às embeddings.)

## Pipeline inteiro simulado (com o modelo nas que não decidem)

Célula = acerto % / perguntas ao modelo.

| sim1 \ limiar | 0.60 | 0.70 | 0.80 | 0.85 | 0.90 | 0.95 | 0.99 | só modelo |
|---|---|---|---|---|---|---|---|---|
| 0.50 | 93.8 / 47 | 93.8 / 54 | 93.8 / 59 | 94.6 / 61 | 95.4 / 64 | 94.6 / 68 | 94.6 / 69 | 95.4 / 78 |
| 0.60 | 93.1 / 52 | 93.8 / 57 | 93.8 / 61 | 94.6 / 62 | 95.4 / 64 | 94.6 / 68 | 94.6 / 69 | 95.4 / 78 |
| 0.65 | 94.6 / 58 | 94.6 / 61 | 94.6 / 63 | 95.4 / 64 | 95.4 / 64 | 94.6 / 68 | 94.6 / 69 | 95.4 / 78 |
| 0.70 | 96.2 / 65 | 96.2 / 66 | 96.2 / 67 | 96.2 / 67 | 96.2 / 67 | 95.4 / 70 | 95.4 / 71 | 95.4 / 78 |
| 0.75 | 96.2 / 72 | 96.2 / 72 | 96.2 / 72 | 96.2 / 72 | 96.2 / 72 | 95.4 / 74 | 95.4 / 74 | 95.4 / 78 |
| 0.80 | 96.2 / 72 | 96.2 / 72 | 96.2 / 72 | 96.2 / 72 | 96.2 / 72 | 95.4 / 74 | 95.4 / 74 | 95.4 / 78 |

## Varredura em deixar-um-de-fora no treino (exemplos que chegam às embeddings)

Célula = decididas / erradas (as demais vão ao modelo).

| sim1 \ limiar | 0.60 | 0.70 | 0.80 | 0.85 | 0.90 | 0.95 | 0.99 |
|---|---|---|---|---|---|---|---|
| 0.50 | 135 / 19 | 110 / 10 | 91 / 6 | 78 / 2 | 66 / 1 | 49 / 0 | 43 / 0 |
| 0.60 | 104 / 17 | 85 / 8 | 71 / 5 | 60 / 2 | 50 / 1 | 35 / 0 | 29 / 0 |
| 0.65 | 85 / 11 | 68 / 4 | 56 / 3 | 48 / 1 | 40 / 1 | 29 / 0 | 24 / 0 |
| 0.70 | 54 / 8 | 43 / 3 | 33 / 2 | 28 / 1 | 24 / 1 | 17 / 0 | 15 / 0 |
| 0.75 | 27 / 3 | 24 / 1 | 20 / 1 | 15 / 0 | 13 / 0 | 11 / 0 | 10 / 0 |
| 0.80 | 11 / 2 | 10 / 1 | 8 / 1 | 5 / 0 | 5 / 0 | 4 / 0 | 4 / 0 |

(190 chegam às embeddings.)

## Erros do deixar-um-de-fora com confiança >= 0,6

- DADOS -> MISTO (0.67): Quais compras de vacina para o rebanho eu lancei em 2025?
- DADOS -> MISTO (0.73): Qual foi meu gasto com herbicida 2,4-D na safrinha?
- DADOS -> MISTO (0.60): Na venda de cana-de-açúcar o IBS é reduzido?
- DADOS -> MISTO (0.77): O leite que eu vendo tem alíquota reduzida?
- DADOS -> MISTO (0.79): Qual o tratamento do trigo na tabela?
- DADOS -> MISTO (0.74): O NCM 0713.33.19 tem redução?
- LEI -> MISTO (0.63): Como pedir ressarcimento de saldo credor de CBS?
- LEI -> MISTO (0.62): A exportação de soja paga Funrural?
- LEI -> MISTO (0.90): Como fica o crédito na compra de trator?
- LEI -> MISTO (0.88): O que é o cClassTrib?
- LEI -> MISTO (0.84): Cogumelo está entre os hortícolas da alíquota zero?
- LEI -> MISTO (0.83): Insumo veterinário tem redução de alíquota?
- MISTO -> LEI (0.64): Tenho guia de IBS para pagar e qual o prazo legal de recolhimento?
- MISTO -> LEI (0.62): Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?
- MISTO -> LEI (0.83): Vendi soja para outro estado; quanto foi e o IBS fica com qual estado?
- MISTO -> LEI (0.70): Quantas notas eu emiti com CBS destacada e todo produtor precisa destacar a CBS?
- MISTO -> DADOS (0.63): Quanto vendi de trigo e o trigo entra na cesta básica?
- FORA -> LEI (0.84): Como funciona o inventário de bens?
- FORA -> DADOS (0.65): Qual a melhor ração para cachorro?
