# Diagnóstico de rota no conjunto de desenvolvimento

Conjunto: `perguntas-rota-dev-2.json`, versão 2026-11-25.2.

Configuração: limiar 0.9, vizinhos 7, similaridade mínima 0.5, treino 262 exemplos.

**Decididas antes do modelo:** 65 de 65, com 49 certas; 0 vão ao modelo.

| camada | n | certas | erradas | ao modelo |
|---|---|---|---|---|
| EMBEDDING | 7 | 4 | 3 | 0 |
| LLM | 33 | 29 | 4 | 0 |
| REGRA | 21 | 12 | 9 | 0 |
| SINAL_RECUSA_FIRME | 4 | 4 | 0 | 0 |

| rota esperada | n | certas antes do modelo | erradas | ao modelo |
|---|---|---|---|---|
| DADOS | 19 | 15 | 4 | 0 |
| LEGISLACAO | 12 | 11 | 1 | 0 |
| MISTO | 11 | 8 | 3 | 0 |
| FORA_DE_ESCOPO | 23 | 15 | 8 | 0 |

Chamadas ao classificador por modelo no caminho de produção (desempate e pergunta de lei do MISTO): 33.

Latência da chamada de classificação ao modelo (80 chamadas, desta máquina até a API): p50 754 ms, p95 1365 ms.

## Por pergunta

| id | tipo | esperada | camada | rota | ok | modelo | voto | conf | sim1 | vizinhos |
|---|---|---|---|---|---|---|---|---|---|---|
| T01 | terceiro | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 0.55 | 0.66 | DADOS 0.66 «Quantas vendas eu fiz para a cooperativa este ano?» DADOS 0.65 «Quanto eu recebi de vendas no ano passado?» FORA 0.61 «Qual é a previsão de safra de soja do país?» |
| T02 | terceiro | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | FORA | 0.51 | 0.65 | FORA 0.65 «Me conta uma piada de fazendeiro» FORA 0.62 «Invente um nome para minha fazenda» DADOS 0.62 «Me mostra as notas que estão em rascunho» |
| T03 | terceiro | FORA | LLM | FORA | ok | FORA,FORA | DADOS | 0.83 | 0.57 | MISTO 0.57 «Tenho guia de Funrural vencida e qual a multa prevista na lei?» DADOS 0.57 «Tem alguma coisa pendente comigo no sistema?» DADOS 0.57 «Minhas guias de outubro já foram pagas?» |
| T04 | terceiro | FORA | REGRA | LEI | ERRO |  | LEI | 0.78 | 0.67 | LEI 0.67 «Doação de produtos agrícolas paga IBS?» LEI 0.65 «O que acontece com o crédito de IBS que sobra no fim do mês?» LEI 0.65 «Energia elétrica usada na fazenda gera crédito de CBS?» |
| T05 | terceiro | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | DADOS | 1.00 | 0.62 | DADOS 0.62 «Me da um panorama do que esta em aberto na minha conta» DADOS 0.62 «Me mostra minhas vendas de leite de setembro» DADOS 0.60 «Quanto eu recebi de vendas no ano passado?» |
| T06 | terceiro | FORA | LLM | FORA | ok | FORA,FORA | DADOS | 0.83 | 0.68 | DADOS 0.68 «Tem alguma pendência na minha conta?» DADOS 0.65 «O contador me pediu algum documento?» DADOS 0.64 «Tenho saldo credor de IBS?» |
| T07 | terceiro | FORA | LLM | FORA | ok | FORA,FORA | DADOS | 0.53 | 0.60 | DADOS 0.60 «Quanto rendeu a venda de milho da safrinha?» DADOS 0.57 «De quantos fornecedores diferentes eu comprei este ano?» MISTO 0.55 «Quanto comprei de sal mineral e sal mineral é insumo agropecuário?» |
| T08 | terceiro | FORA | SINAL_RECUSA_FIRME | FORA | ok |  | MISTO | 0.70 | 0.73 | MISTO 0.73 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» MISTO 0.65 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» MISTO 0.63 «Tenho guia de Funrural vencida e qual a multa prevista na lei?» |
| T09 | terceiro-legitimo | DADOS | REGRA | DADOS | ok |  | DADOS | 1.00 | 0.76 | DADOS 0.76 «Quanto eu recebi de vendas no ano passado?» DADOS 0.72 «Quantas vendas eu fiz para a cooperativa este ano?» DADOS 0.67 «Quanto vendi para fora do estado este ano?» |
| T10 | terceiro-legitimo | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.75 | 0.86 | MISTO 0.86 «Quanto vendi para a cooperativa e como fica o Funrural nessas vendas?» DADOS 0.78 «Quantas vendas eu fiz para a cooperativa este ano?» MISTO 0.74 «Quanto comprei da cooperativa e a compra de cooperativa tem crédito?» |
| T11 | terceiro-legitimo | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.78 | 0.64 | DADOS 0.64 «A nota 77 já foi autorizada?» DADOS 0.62 «Quais notas de compra eu importei ontem?» MISTO 0.59 «Minhas notas de venda de tomate saíram com alíquota zero como a lei prevê?» |
| T12 | terceiro-legitimo | DADOS | REGRA | DADOS | ok |  | DADOS | 0.69 | 0.76 | DADOS 0.76 «Quanto eu recebi de vendas no ano passado?» MISTO 0.71 «Qual o total das minhas compras de fertilizante orgânico e esse adubo tem benefício?» MISTO 0.71 «Quanto comprei de adubo foliar este ano e adubo foliar tem redução?» |
| T13 | terceiro-legitimo | LEI | REGRA | LEI | ok |  | LEI | 0.52 | 0.70 | MISTO 0.70 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» LEI 0.66 «O produtor pode optar por contribuir sobre a folha em vez do Funrural?» LEI 0.65 «O Funrural incide sobre a venda de sementes para plantio?» |
| T14 | terceiro-legitimo | LEI | REGRA | LEI | ok |  | LEI | 0.92 | 0.77 | LEI 0.77 «O split payment vale para pagamento em dinheiro?» LEI 0.75 «O split payment começa a valer em que ano?» LEI 0.75 «O produtor precisa ter conta em banco para o split payment?» |
| T15 | terceiro-legitimo | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.63 | 0.78 | DADOS 0.78 «Quantas vendas eu fiz para a cooperativa este ano?» MISTO 0.77 «Quanto comprei da cooperativa e a compra de cooperativa tem crédito?» MISTO 0.76 «Quanto vendi para a cooperativa e como fica o Funrural nessas vendas?» |
| T16 | terceiro-legitimo | LEI | REGRA | LEI | ok |  | MISTO | 0.48 | 0.90 | MISTO 0.90 «Quanto paguei de arrendamento e arrendamento tem IBS?» LEI 0.86 «Arrendamento de terra paga IBS?» LEI 0.71 «Cooperativa paga IBS no ato cooperativo?» |
| O01 | ordem | FORA | REGRA | DADOS | ERRO |  | DADOS | 0.75 | 0.63 | DADOS 0.63 «Tenho nota parada esperando o contador aprovar?» DADOS 0.60 «Alguma venda minha foi cancelada em outubro?» MISTO 0.59 «Minha nota 812 de venda de milho tem a redução certa da LC 214?» |
| O02 | ordem | FORA | EMBEDDING | DADOS | ERRO | FORA,FORA | DADOS | 0.95 | 0.77 | DADOS 0.77 «Quais guias eu já paguei em outubro?» DADOS 0.73 «Minhas guias de outubro já foram pagas?» DADOS 0.70 «Paguei a guia de setembro?» |
| O03 | ordem | FORA | LLM | FORA | ok | FORA,FORA | LEI | 0.63 | 0.60 | LEI 0.60 «Como fica o Simples Nacional depois da reforma?» LEI 0.56 «Quem está no regime regular emite nota com destaque de imposto?» LEI 0.54 «O produtor pode desistir do regime regular depois de optar?» |
| O04 | ordem | FORA | LLM | FORA | ok | FORA,FORA | MISTO | 0.78 | 0.54 | MISTO 0.54 «As vendas de frutas que eu fiz em setembro têm isenção? Quanto foram?» MISTO 0.51 «Minhas vendas de arroz em casca: quanto deu e arroz em casca tem alíquota zero?» MISTO 0.50 «Tenho crédito de IBS acumulado? A lei deixa pedir ressarcimento?» |
| O05 | ordem | FORA | LLM | FORA | ok | FORA,FORA | DADOS | 0.57 | 0.57 | DADOS 0.57 «Quanto rendeu a venda de milho da safrinha?» MISTO 0.56 «Minhas notas de outubro estão com o cClassTrib certo para a soja? Quanto vendi?» LEI 0.56 «Como funciona a nota fiscal de entrada emitida pelo comprador?» |
| O06 | ordem | FORA | REGRA | DADOS | ERRO |  | DADOS | 1.00 | 0.70 | DADOS 0.70 «O contador me pediu algum documento?» DADOS 0.69 «Tenho nota parada esperando o contador aprovar?» DADOS 0.68 «Me mostra o resumo das minhas notas do mês» |
| O07 | ordem | FORA | LLM | FORA | ok | FORA,FORA | DADOS | 0.56 | 0.53 | DADOS 0.53 «Tem alguma coisa pendente comigo no sistema?» MISTO 0.53 «Tenho crédito de IBS acumulado? A lei deixa pedir ressarcimento?» DADOS 0.53 «Me mostra as notas que estão em rascunho» |
| O08 | ordem | FORA | REGRA | DADOS | ERRO |  | DADOS | 0.58 | 0.70 | DADOS 0.70 «Alguma venda minha foi cancelada em outubro?» MISTO 0.68 «As vendas de frutas que eu fiz em setembro têm isenção? Quanto foram?» DADOS 0.64 «Me mostra minhas vendas de leite de setembro» |
| O09 | ordem | FORA | REGRA | DADOS | ERRO |  | DADOS | 0.93 | 0.71 | DADOS 0.71 «A nota 77 já foi autorizada?» LEI 0.54 «Quem está no regime regular emite nota com destaque de imposto?» DADOS 0.53 «Quantas notas eu tenho no total este ano?» |
| O10 | ordem | FORA | EMBEDDING | DADOS | ERRO | FORA,FORA | DADOS | 0.96 | 0.87 | DADOS 0.87 «Tenho nota parada esperando o contador aprovar?» DADOS 0.72 «O contador me pediu algum documento?» DADOS 0.66 «Me mostra as notas que estão em rascunho» |
| O11 | ordem-legitima | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 1.00 | 0.67 | DADOS 0.67 «Quantas notas eu tenho no total este ano?» DADOS 0.65 «Alguma venda minha foi cancelada em outubro?» DADOS 0.65 «Quais notas de compra eu importei ontem?» |
| O12 | ordem-legitima | LEI | EMBEDDING | DADOS | ERRO | LEI,LEI | DADOS | 0.91 | 0.64 | DADOS 0.64 «Tenho nota parada esperando o contador aprovar?» DADOS 0.63 «Alguma venda minha foi cancelada em outubro?» DADOS 0.60 «Quantos avisos de vencimento eu recebi no mês?» |
| O13 | ordem-legitima | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 1.00 | 0.73 | DADOS 0.73 «Qual foi o valor da última guia que eu paguei?» DADOS 0.72 «Quais guias eu já paguei em outubro?» DADOS 0.72 «Quantas guias eu paguei este ano?» |
| M01 | misto-curto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.48 | 0.72 | MISTO 0.72 «Vendi soja para outro estado; quanto foi e o IBS fica com qual estado?» LEI 0.69 «Energia elétrica usada na fazenda gera crédito de CBS?» MISTO 0.67 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» |
| M02 | misto-curto | MISTO | REGRA | DADOS | ERRO |  | MISTO | 0.81 | 0.84 | MISTO 0.84 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» MISTO 0.73 «Quanto paguei de energia elétrica e posso tomar crédito disso?» MISTO 0.73 «Quanto comprei de máquina este ano e a lei dá crédito na compra de máquina?» |
| M03 | misto-curto | MISTO | LLM | MISTO | ok | MISTO,MISTO | DADOS | 0.59 | 0.79 | DADOS 0.79 «O leite que eu vendo tem alíquota reduzida?» MISTO 0.63 «Minha nota 812 de venda de milho tem a redução certa da LC 214?» MISTO 0.63 «O valor de IBS das minhas vendas de milho respeita a redução do produto in natura?» |
| M04 | misto-curto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.66 | 0.75 | MISTO 0.75 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» DADOS 0.68 «Qual guia de Funrural eu paguei por último?» MISTO 0.68 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» |
| M05 | misto-curto | MISTO | REGRA | DADOS | ERRO |  | MISTO | 0.63 | 0.82 | MISTO 0.82 «Tenho crédito de IBS acumulado? A lei deixa pedir ressarcimento?» LEI 0.73 «Como pedir ressarcimento de saldo credor de CBS?» MISTO 0.69 «Quanto paguei de energia elétrica e posso tomar crédito disso?» |
| M06 | misto-curto | MISTO | LLM | DADOS | ERRO | DADOS,DADOS | MISTO | 0.50 | 0.77 | MISTO 0.77 «Tenho guia de IBS para pagar e qual o prazo legal de recolhimento?» LEI 0.69 «Qual é o prazo para recolher o IBS e a CBS?» MISTO 0.68 «Quanto ficou de saldo a pagar de IBS e quando vence pela lei?» |
| M07 | misto-curto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.77 | 0.69 | FORA 0.69 «Onde comprar semente barata?» MISTO 0.66 «Minhas vendas de arroz em casca: quanto deu e arroz em casca tem alíquota zero?» MISTO 0.65 «Minhas notas de venda de tomate saíram com alíquota zero como a lei prevê?» |
| M08 | misto-curto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.58 | 0.87 | MISTO 0.87 «Vendi soja para outro estado; quanto foi e o IBS fica com qual estado?» DADOS 0.75 «Quanto vendi para fora do estado este ano?» LEI 0.69 «Venda de imóvel rural tem IBS?» |
| M09 | misto-curto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.45 | 0.70 | LEI 0.70 «A exportação de soja paga Funrural?» MISTO 0.66 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» MISTO 0.63 «Quanto vendi para a cooperativa e como fica o Funrural nessas vendas?» |
| M10 | misto-curto | MISTO | LLM | MISTO | ok | MISTO,MISTO | MISTO | 0.58 | 0.75 | LEI 0.75 «Como pedir ressarcimento de saldo credor de CBS?» MISTO 0.73 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» MISTO 0.71 «Qual o total das minhas compras de peças e peça de máquina gera crédito de CBS?» |
| C01 | curta-dados | DADOS | REGRA | LEI | ERRO |  | LEI | 0.65 | 0.56 | LEI 0.56 «O que é o Funrural pela Lei 8.212?» MISTO 0.52 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» LEI 0.51 «O Funrural incide sobre a venda de sementes para plantio?» |
| C02 | curta-dados | DADOS | LLM | MISTO | ERRO | MISTO,MISTO | LEI | 0.53 | 0.80 | MISTO 0.80 «Qual o total das minhas compras de peças e peça de máquina gera crédito de CBS?» LEI 0.76 «Como pedir ressarcimento de saldo credor de CBS?» MISTO 0.75 «Quanto paguei de diesel no ano e diesel gera crédito de CBS?» |
| C03 | curta-dados | DADOS | EMBEDDING | DADOS | ok | DADOS,DADOS | DADOS | 0.93 | 0.75 | DADOS 0.75 «Alguma venda minha foi cancelada em outubro?» DADOS 0.74 «Quais compras de outubro geraram crédito?» DADOS 0.68 «Quais guias eu já paguei em outubro?» |
| C04 | curta-dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.39 | 0.81 | MISTO 0.81 «Quanto ficou de saldo a pagar de IBS e quando vence pela lei?» DADOS 0.79 «Tenho saldo credor de IBS?» MISTO 0.76 «Quanto paguei de arrendamento e arrendamento tem IBS?» |
| C05 | curta-dados | DADOS | REGRA | DADOS | ok |  | DADOS | 1.00 | 0.55 | DADOS 0.55 «Em que situação está a nota 512?» DADOS 0.53 «Quantas notas eu tenho no total este ano?» DADOS 0.53 «Qual foi a primeira nota que emiti este ano?» |
| C06 | curta-dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.42 | 0.59 | FORA 0.59 «Que dia cai o Carnaval do ano que vem?» LEI 0.58 «O que acontece com o crédito de IBS que sobra no fim do mês?» MISTO 0.54 «Quanto ficou de saldo a pagar de IBS e quando vence pela lei?» |
| C07 | curta-dados | DADOS | LLM | DADOS | ok | DADOS,DADOS | DADOS | 0.79 | 0.62 | DADOS 0.62 «De quantos fornecedores diferentes eu comprei este ano?» DADOS 0.61 «Quanto eu recebi de vendas no ano passado?» DADOS 0.60 «Qual foi a nota de compra de maior valor do ano?» |
| C08 | curta-lei | LEI | LLM | LEI | ok | LEI,LEI | MISTO | 0.61 | 0.76 | MISTO 0.76 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» LEI 0.68 «O que é o Funrural pela Lei 8.212?» MISTO 0.63 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» |
| C09 | curta-lei | LEI | REGRA | LEI | ok |  | MISTO | 0.85 | 0.69 | MISTO 0.69 «Vacina para o rebanho entra como insumo com redução? E quanto foi meu gasto com isso?» MISTO 0.67 «Quanto comprei de adubo foliar este ano e adubo foliar tem redução?» LEI 0.65 «Insumo veterinário tem redução de alíquota?» |
| C10 | curta-lei | LEI | REGRA | LEI | ok |  | LEI | 0.74 | 0.70 | LEI 0.70 «Serviço prestado ao exterior paga CBS?» LEI 0.69 «Quem administra a CBS?» MISTO 0.67 «Qual o total das minhas compras de peças e peça de máquina gera crédito de CBS?» |
| C11 | curta-lei | LEI | REGRA | LEI | ok |  | LEI | 0.79 | 0.78 | LEI 0.78 «Quando começa a cobrança integral do IBS?» LEI 0.68 «Qual é o prazo para recolher o IBS e a CBS?» DADOS 0.68 «Em qual mês do ano eu recolhi mais IBS?» |
| C12 | curta-lei | LEI | REGRA | LEI | ok |  | LEI | 0.95 | 0.88 | LEI 0.88 «O split payment vale para pagamento em dinheiro?» LEI 0.80 «O split payment começa a valer em que ano?» LEI 0.76 «O produtor precisa ter conta em banco para o split payment?» |
| C13 | curta-lei | LEI | REGRA | LEI | ok |  | LEI | 0.78 | 0.86 | LEI 0.86 «Como é calculado o valor do crédito presumido?» LEI 0.81 «O crédito presumido tem limite de valor?» MISTO 0.69 «As minhas compras de ureia de outubro geram crédito presumido?» |
| C14 | curta-fora | FORA | LLM | FORA | ok | FORA,FORA | - | - | - | - |
| C15 | curta-fora | FORA | LLM | FORA | ok | FORA,FORA | FORA | 0.64 | 0.64 | FORA 0.64 «Qual a cotação do dólar hoje?» FORA 0.58 «Qual o preço do diesel no posto hoje?» DADOS 0.53 «Quanto paguei em lona e arame no mês passado?» |
| C16 | curta-fora | FORA | LLM | FORA | ok | FORA,FORA | LEI | 0.41 | 0.61 | LEI 0.61 «Como funciona a nota fiscal de entrada emitida pelo comprador?» LEI 0.60 «Quem está no regime regular emite nota com destaque de imposto?» FORA 0.59 «Como abrir uma conta no Instagram?» |
| K01 | voto-knn | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.77 | 0.86 | MISTO 0.86 «Qual o débito de IBS das minhas vendas de café e qual alíquota foi usada?» DADOS 0.76 «Quanto faturei com café no ano passado?» MISTO 0.75 «Quanto de IBS municipal saiu nas minhas notas e quem fica com esse dinheiro?» |
| K02 | voto-knn | DADOS | LLM | MISTO | ERRO | MISTO,MISTO | MISTO | 0.65 | 0.72 | MISTO 0.72 «Quanto vendi de sorgo e o sorgo tem redução de 60%?» DADOS 0.64 «O leite que eu vendo tem alíquota reduzida?» MISTO 0.64 «Minhas notas de outubro estão com o cClassTrib certo para a soja? Quanto vendi?» |
| K03 | voto-knn | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.67 | 0.73 | MISTO 0.73 «Quanto vendi de sorgo e o sorgo tem redução de 60%?» DADOS 0.61 «O leite que eu vendo tem alíquota reduzida?» MISTO 0.59 «O valor de IBS das minhas vendas de milho respeita a redução do produto in natura?» |
| K04 | voto-knn | DADOS | LLM | MISTO | ERRO | MISTO,MISTO | MISTO | 0.75 | 0.63 | MISTO 0.63 «Quanto paguei de frete nas compras e frete gera crédito?» MISTO 0.63 «Quanto comprei da cooperativa e a compra de cooperativa tem crédito?» MISTO 0.62 «Qual o total das minhas compras de peças e peça de máquina gera crédito de CBS?» |
| K05 | voto-knn | LEI | EMBEDDING | LEI | ok | MISTO,MISTO | LEI | 0.91 | 0.72 | LEI 0.72 «Venda de imóvel rural tem IBS?» LEI 0.71 «Doação de produtos agrícolas paga IBS?» LEI 0.67 «Combustível dá direito a crédito de IBS?» |
| K06 | voto-knn | DADOS | LLM | DADOS | ok | DADOS,DADOS | MISTO | 0.62 | 0.65 | MISTO 0.65 «Minhas vendas ao exterior pagaram Funrural? A lei manda pagar?» LEI 0.62 «O Funrural incide sobre a venda de sementes para plantio?» MISTO 0.62 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» |
| K07 | voto-knn | LEI | LLM | LEI | ok | LEI,LEI | LEI | 0.52 | 0.66 | MISTO 0.66 «Quanto vendi para a cooperativa e como fica o Funrural nessas vendas?» LEI 0.65 «O Funrural incide sobre a venda de sementes para plantio?» MISTO 0.65 «Quanto recolhi de Funrural este ano e qual a alíquota da lei?» |
| K08 | voto-knn | FORA | REGRA | DADOS | ERRO |  | LEI | 0.60 | 0.69 | LEI 0.69 «Precisa destacar IBS e CBS na nota de venda de gado?» LEI 0.63 «Venda de imóvel rural tem IBS?» MISTO 0.61 «Quanto vendi de boi gordo e gado vivo tem redução de IBS?» |
| K09 | voto-knn | FORA | LLM | FORA | ok | FORA,FORA | FORA | 0.62 | 0.69 | LEI 0.69 «Como fica o crédito na compra de trator?» FORA 0.69 «Quem inventou o trator?» FORA 0.66 «Qual trator é melhor para uma fazenda pequena?» |
| K10 | voto-knn | DADOS | REGRA | DADOS | ok |  | DADOS | 0.71 | 0.65 | LEI 0.65 «Como fica o crédito na compra de trator?» DADOS 0.65 «Calcário: quanto eu desembolsei no ano passado?» DADOS 0.64 «Quanto paguei de guias no ano passado?» |

## Varredura no conjunto de desenvolvimento (perguntas que chegam às embeddings)

Célula = decididas / erradas (as demais vão ao modelo).

| sim1 \ limiar | 0.60 | 0.70 | 0.80 | 0.85 | 0.90 | 0.95 | 0.99 |
|---|---|---|---|---|---|---|---|
| 0.50 | 24 / 13 | 15 / 8 | 8 / 4 | 7 / 3 | 7 / 3 | 4 / 2 | 2 / 0 |
| 0.60 | 22 / 11 | 14 / 7 | 8 / 4 | 7 / 3 | 7 / 3 | 4 / 2 | 2 / 0 |
| 0.65 | 17 / 9 | 10 / 5 | 7 / 3 | 6 / 2 | 6 / 2 | 4 / 2 | 2 / 0 |
| 0.70 | 12 / 7 | 7 / 4 | 5 / 2 | 5 / 2 | 5 / 2 | 3 / 2 | 1 / 0 |
| 0.75 | 7 / 5 | 4 / 4 | 2 / 2 | 2 / 2 | 2 / 2 | 2 / 2 | 0 / 0 |
| 0.80 | 3 / 3 | 3 / 3 | 1 / 1 | 1 / 1 | 1 / 1 | 1 / 1 | 0 / 0 |

(38 chegam às embeddings.)

## Pipeline inteiro simulado (com o modelo nas que não decidem)

Célula = acerto % / perguntas ao modelo.

| sim1 \ limiar | 0.60 | 0.70 | 0.80 | 0.85 | 0.90 | 0.95 | 0.99 | só modelo |
|---|---|---|---|---|---|---|---|---|
| 0.50 | 72.3 / 27 | 73.8 / 30 | 73.8 / 32 | 75.4 / 33 | 75.4 / 33 | 75.4 / 36 | 78.5 / 38 | 78.5 / 40 |
| 0.60 | 73.8 / 28 | 73.8 / 30 | 73.8 / 32 | 75.4 / 33 | 75.4 / 33 | 75.4 / 36 | 78.5 / 38 | 78.5 / 40 |
| 0.65 | 75.4 / 32 | 75.4 / 33 | 75.4 / 33 | 76.9 / 34 | 76.9 / 34 | 75.4 / 36 | 78.5 / 38 | 78.5 / 40 |
| 0.70 | 76.9 / 35 | 76.9 / 35 | 76.9 / 35 | 76.9 / 35 | 76.9 / 35 | 75.4 / 37 | 78.5 / 39 | 78.5 / 40 |
| 0.75 | 75.4 / 38 | 75.4 / 38 | 75.4 / 38 | 75.4 / 38 | 75.4 / 38 | 75.4 / 38 | 78.5 / 40 | 78.5 / 40 |
| 0.80 | 76.9 / 39 | 76.9 / 39 | 76.9 / 39 | 76.9 / 39 | 76.9 / 39 | 76.9 / 39 | 78.5 / 40 | 78.5 / 40 |

## Varredura em deixar-um-de-fora no treino (exemplos que chegam às embeddings)

Célula = decididas / erradas (as demais vão ao modelo).

| sim1 \ limiar | 0.60 | 0.70 | 0.80 | 0.85 | 0.90 | 0.95 | 0.99 |
|---|---|---|---|---|---|---|---|
| 0.50 | 134 / 19 | 109 / 10 | 90 / 6 | 78 / 2 | 66 / 1 | 49 / 0 | 43 / 0 |
| 0.60 | 103 / 17 | 84 / 8 | 70 / 5 | 60 / 2 | 50 / 1 | 35 / 0 | 29 / 0 |
| 0.65 | 84 / 11 | 67 / 4 | 55 / 3 | 48 / 1 | 40 / 1 | 29 / 0 | 24 / 0 |
| 0.70 | 53 / 8 | 42 / 3 | 32 / 2 | 28 / 1 | 24 / 1 | 17 / 0 | 15 / 0 |
| 0.75 | 26 / 3 | 23 / 1 | 19 / 1 | 15 / 0 | 13 / 0 | 11 / 0 | 10 / 0 |
| 0.80 | 10 / 2 | 9 / 1 | 7 / 1 | 5 / 0 | 5 / 0 | 4 / 0 | 4 / 0 |

(189 chegam às embeddings.)

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
