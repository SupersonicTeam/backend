# Medição do falso positivo do "corrijo" nos dados da continuidade (#581): RISCO_RELEVANTE

Rodada de 2026-11-18, regra de decisão `2026-11-18.1`
(`docs/avaliacao/continuidade/regra-de-decisao-581.md`, limiares aprovados pelo Murillo em
2026-11-18), conjunto `continuidade-581-v1` (24 conversas, sha256
`f46e81ab2048d71832efeed9f8c3063dd00960ae9363b742dca89873474722c7`).

| Item | Valor |
|---|---|
| Commit medido | `b8722d1654d5568facdfba711edfd23b9a89d0d2` (a `main` logo depois do merge do #589) |
| Modelo | `anthropic/claude-haiku-5-5` |
| Braço | só o ligado (`AI_CONTINUIDADE_HABILITADA=true`, porta 8502), banco próprio recriado, seed da demonstração, base de lei copiada (8.304 trechos) |
| Fidelidade da rota de lei | **desligada** (padrão do código; nenhuma variável `AI_FIDELIDADE_LEI_*` no ambiente da rodada) |
| Repetições | 5 |
| Dia e relógio | 2026-11-18, das 18:36 às 18:50 (execução) |
| Execuções | 120 (24 conversas × 5), 9 fora (7,5%; limite 10%), 111 medidas |
| Problemas de consistência | nenhum (`problemas: []`) |
| **Veredito** | **`RISCO_RELEVANTE`** |

## Núcleo (decide)

| Item | Valor |
|---|---|
| Execuções válidas | 74 |
| `corrigiu_para_pior` | **5** (c581-01/r1, r2, r3, r4, r5) |
| Taxa de "corrigiu para pior" | **6,8%** (5 de 74) |
| Limite superior de 95% (Wilson) | **14,9%** |
| `manteve_certo` | 69 |
| `corrigiu_equivalente`, `refeito_para_pior`, `sem_consulta` | 0, 0, 0 |
| Passou pelo modelo (ramo exercitado, proxy) | **24%** (18 de 74); o mínimo de 30% não cumpre, mas o veredito de risco vem antes na ordem da regra |
| Conversas com ocorrência | 1 (c581-01) |
| Reproduzida (2+ repetições da mesma conversa) | sim: c581-01 em 5 de 5 |

**Por que `RISCO_RELEVANTE`:** a taxa passa de 5% e a mesma conversa corrige para pior em 5 das 5
repetições (reproduz). A terceira condição da regra (2 conversas distintas) não se cumpre.

**O que aconteceu em c581-01 (categoria sinônimo/NCM, mecanismo `texto_do_par`):** a consulta anterior
era certa para a pergunta. A pergunta gravada no par usava um termo geral para o produto. A régua de
texto leu a consulta antiga como incoerente, o modelo refez a consulta com o termo geral no lugar do
nome do produto, a nova consulta não achou nada, e a resposta saiu com a frase "corrijo a resposta
anterior" seguida de "não encontrei compras". As 5 repetições fizeram o mesmo. Ou seja, o valor
certo foi trocado por um vazio, com a frase de correção na frente. O arquivo `resumo.json` traz a
consulta anterior e a nova de cada ocorrência (`medido.classes.nucleo.detalhes`).

### Por categoria e por mecanismo (núcleo)

| Categoria | Execuções | Corrigiu para pior |
|---|---|---|
| sinônimo/NCM | 18 | 5 (todas c581-01) |
| período relativo | 16 | 0 |
| situação com outro nome | 20 | 0 |
| compra/venda implícita | 20 | 0 |

| Mecanismo | Execuções | Corrigiu para pior |
|---|---|---|
| `natural` | 19 | 0 |
| `texto_do_par` | 55 | 5 |

### Por conversa

c581-03, 08, 09, 10, 15, 19, 20, 22, 23, 38, 41, 42 e 44: 5 de 5 `manteve_certo` cada.
c581-06: 3 `manteve_certo` (2 execuções fora, `preparo_invalido`: r1, r5). c581-40: 1
`manteve_certo` (4 fora, ver abaixo).

## Controles

| Classe | n | Resultado |
|---|---|---|
| Controle claro (anterior certa, formulação clara) | 20 | 20 `manteve_certo`; nenhum "corrijo"; 0% pelo modelo |
| Controle do verdadeiro positivo (anterior errada) | 17 | 17 `corrigiu_para_melhor` (100%; mínimo 14 de 17); 100% pelo modelo |

- **O controle do verdadeiro positivo valida a rodada:** a Ceres corrige quando deve (c581-32, 33,
  34 e 49), então o resultado do núcleo não é por a rodada não enxergar o "corrijo".
- **Atribuição:** o "corrijo" no controle claro é 0%; no núcleo, 6,8% (5 de 74). Leitura da regra:
  a formulação pesa, e não é ruído do modelo.

## Execuções fora (9 de 120)

| Motivo | Execuções |
|---|---|
| `preparo_invalido` | c581-06/r1, c581-06/r5 |
| `confirmacao_fora_da_rota` | c581-40/r2, r3, r4, r5 e c581-49/r2, r3, r5 (a confirmação saiu pela rota `MISTO`, não por `DADOS`) |

As duas conversas de retenções (c581-40 e c581-49) perderam 7 de 10 execuções: a pergunta sobre
Funrural retido é lida como mista (dados e lei). Sobram só 1 de c581-40 no núcleo e 2 de c581-49 no
controle do verdadeiro positivo. É limitação do conjunto, e não do veredito: o `RISCO_RELEVANTE`
vem de c581-01.

## Relatados (item 4 da #581)

- **"Corrijo" por dia:** 22 em 2026-11-18 (5 do núcleo, 17 do controle do verdadeiro positivo).
- **Por reescrita (`perguntaInterpretada` presente e diferente da mensagem):** 22 de 22. A
  confirmação "tem certeza?" é sempre reescrita pelo backend; não separa os casos.
- **Por par distinto:** o único par com "corrijo" indevido é c581-01.

## O que a regra manda fazer (aprovado pelo Murillo em 2026-11-18)

`RISCO_RELEVANTE`: o falso positivo existe e é mensurável. Abre issue de conserto (guarda de apoio
nos dados, análoga à guarda g da lei, **ou** "Refiz" no lugar de "Corrijo" quando não há como provar
que o valor antigo estava errado), e **não ligar `AI_CONTINUIDADE_HABILITADA` na VPS antes da
correção**. Quem corrige não lê o conjunto: mensagem a quem corrige cita só ids. A decisão da #499
(ENTRA, o mesmo `claude-haiku-5-5`, com a flag desligada por padrão) não muda por esta medição.

## Limites

- Uma conversa concentra todas as ocorrências (c581-01). Com 5 repetições, 5 de 5 mostra que o caso
  reproduz, mas não diz quantas formulações do mesmo tipo (sinônimo do produto na pergunta gravada)
  têm o problema. As outras 3 conversas de sinônimo/NCM (c581-03, 06 e 38) não corrigiram para pior.
- O ramo exercitado é só 24% do núcleo: nas outras execuções a régua de texto leu a consulta como
  coerente e o backend repetiu a mesma consulta.
- O gabarito é a função e os parâmetros da consulta, e não o número final. Um "corrijo" sem consulta
  nova também contaria como "para pior"; nesta rodada não houve esse caso (`sem_consulta`: 0).
- 9 execuções fora, 7 delas por a rota ser `MISTO` nas conversas de retenções.

## Arquivos

`rodada.jsonl` (todas as chamadas), `resumo.json` (métricas, ids e detalhe de cada ocorrência),
`resumo.txt`, `executor.log` e `ligado-banco.txt`. Os logs da aplicação e do build ficam fora (trazem
o id do usuário no login).
