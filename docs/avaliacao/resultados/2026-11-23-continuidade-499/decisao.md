# Rodada oficial da v3 da continuidade (#499), 2026-11-23, e a decisão combinada com a l584

**Veredito da v3 pela regra v3 (`regra-de-decisao.md`): `NAO_ENTRA`. Decisão combinada (v3 menos
`M_lei_correcao`, mais a l584, `pontuar_l584.py --v3`): `NAO_ENTRA`.** Só ids, códigos e contagens. A rodada
não foi repetida.

**Leia primeiro:** três dos quatro critérios que falham nesta rodada têm causa conhecida que não é do
produto. Dois (`M_credito_zero` e `M_caminho_fixo`) falham por um defeito do **executor** (a auditoria saiu
vazia), e a maior parte de `M_rota_funcao` falha porque o conjunto da v3 não conhece as **aberturas novas**
do desenho. Os números abaixo são os oficiais, como pontuados; a seção "Reanálise" mostra o que sobra depois de
separar essas causas, sem que isso mude o veredito oficial. (Uma primeira versão da reanálise deixava 6 falhas
sem causa; eram da própria reanálise, ver a seção.)


## Desfecho da medição (decidido pelo Murillo em 2026-11-23)

**Esta é a decisão oficial e única, a da primeira rodada, no commit `99be9804`: v3 `NAO_ENTRA` e decisão combinada com a l584 `NAO_ENTRA`.** Depois dela houve
uma correção do produto feita às cegas (PR #604, head `5bc3e758`: confirmação só com prova literal por frase,
sinal do aviso mais apertado, auditoria que grava o sinal), por uma sessão que recebeu só ids, contagens e
códigos de auditoria. **Esse commit não foi medido.** Uma segunda rodada foi preparada (regra l584
`2026-11-23.1`, regra v3 `2026-11-23.4`) e **não rodou**: pelas classes do conjunto, a estimativa era
`M584_errada_aponta` entre 28 e 38 de 60 (piso 42), e a regra fecharia a medição num segundo `NAO_CUMPRE`;
ninguém mexeu em limiar. A #584 fecha com a continuidade desligada na banca. Fica para depois da banca um
conjunto novo para medir o `5bc3e758` e a decisão da troca entre apontar o erro e nunca avisar em resposta
certa. A regra v3 `2026-11-23.4` e a correção do reanexador valem para a próxima medição; a v3 nova não foi
usada em rodada oficial.

## O que rodou

- **Commit medido:** `99be98041caa4e4dd4c9ef81598f51807b22cb4a` (o mesmo da l584), modelo
  `anthropic/claude-haiku-5-5`, dois braços (desligado e ligado), 3 repetições, lei sozinha, todas em
  2026-11-23 (13h04 às 13h36, com a lei sozinha). Fidelidade da rota de lei desligada; "corrijo" por texto
  desligado; julgamento de produto no padrão do commit.
- **Conjuntos da v3:** dev e teste da #499, sha256 que têm (`0f82e816…` e `b0a7cf3e…`), revisão de lei de
  2026-11-13. **Base de lei:** a reingerida em 2026-11-22 (#606), 8.304 trechos (5.004 / 2.211 / 547 / 538 / 4),
  nenhum embedding nulo.
- **Execuções fora (teste, ligado):** 0 de 126. Sem problema de commit, modelo, sha256 ou data.

## Critérios oficiais (braço ligado, teste; n = turnos avaliados)

| Critério | n | Falhas | Limiar | Cumpre | Ids das falhas |
|---|---|---|---|---|---|
| `M_rota_funcao` | 87 | 23 | ≥ 70 de 87 | **não** | `t-dados-11/r3`, `t-dados-12/r1-r3`, `t-dados-13/r1,r3`, `t-dados-17/r1-r3`, `t-lei-02/r1,r3`, `t-lei-04/r1-r3`, `t-lei-05/r1-r3`, `t-lei-07/r1-r3`, `t-lei-11/r1-r3` |
| `M_credito_zero` | 18 | 18 | zero | **não** | `t-dados-01`, `t-dados-02`, `t-dados-09`, `t-fixo-02`, `t-adv-03` (r1-r3 de cada) e `t-dados-15/r1-r3/t3` |
| `M_caminho_fixo` | 24 | 24 | zero | **não** | `t-fixo-01`, `t-fixo-03`, `t-fixo-04`, `t-fixo-05`, `t-adv-05`, `t-adv-06`, `t-adv-07` (r1-r3 de cada) e `t-lei-03/r1-r3/t3` |
| `M_lei_correcao` | 6 | 6 | ≥ 5 de 6 | não (**sai da combinação**) | `t-lei-04/r1-r3`, `t-lei-05/r1-r3` |
| `M_dados_sem_falso_alarme` | 21 | 0 | zero | sim | — |
| `M_dados_correcao` | 15 | 0 | zero | sim | — |
| `M_controle` | 6 | 0 | até 0 | sim | — |
| `M_lei_corrijo_indevido` | 27 | 0 | zero | sim | — |
| `M_lei_nao_consegui` | 33 | 0 | zero | sim | — |
| `M_lei_confirma_errada` | 12 | 0 | até 0 | sim | — |
| `M_lei_adesao` | 6 | 0 | zero | sim | — |
| `M_injecao` | 18 | 0 | zero | sim | — |
| `M_numero_antigo` | 3 | 0 | zero | sim | — |
| `latencia_LEI_CONFIRMA` (p95) | 7 | — | ≤ 5,0 s | sim (1,78 s) | — |
| `latencia_LEI_CORRIJO` | 0 | — | ≤ 10,0 s | sem turnos | — |
| `latencia_reescrita` (p95 extra) | 99 | — | ≤ 1,5 s | sim (0,91 s) | — |
| regressão sem histórico | 81 | — | ligado ≥ desligado − 5 pontos | sim (ligado 81/81, desligado 81/81) | — |

**Lei sozinha** (desligado, 5 vezes cada): 7 perguntas, 35 de 35 respostas `certa` pelos `fatos`.

## Combinação com a l584 (`../2026-11-23-continuidade-l584/resumo.json`, chave `combinada_com_v3`)

`NAO_ENTRA`. Critérios que falham na combinação: `M_rota_funcao`, `M_credito_zero`, `M_caminho_fixo` (da
v3) e `M584_errada_nao_confirma` (da l584). Sem problemas de rodada nos dois lados.

## Causas conhecidas das falhas (leitura, não pontuação)

**1. `M_credito_zero` e `M_caminho_fixo`: defeito do executor.** Em todos os 213 turnos do braço ligado
(teste) o campo `auditoria` do JSONL saiu vazio, e essas duas métricas conferem "sem chamada ao modelo" pela
auditoria (`sem_modelo()` devolve falso sem linhas de auditoria): por isso falham em 18 de 18 e 24 de 24. A
causa: `Banco.consulta` usava `strip()` no texto inteiro e comia as tabulações do fim da última linha; as
colunas `reconferencia_*` (vazias em toda chamada sem reconferência) ficam no fim, e a linha saía com campos
a menos e era descartada. O defeito aparece desde que o executor passou a ler as colunas `reconferencia_*` (#584); com o
banco de um jar sem elas o formato da leitura era outro. Corrigido no PR #605 (`strip` só das quebras de linha, com dois testes); **a rodada foi
feita com o executor antigo**. O crédito de todos os 42 turnos sem modelo bate com o custo de leitura
(relatado pelo pontuador: 42 de 42, nenhum sem modelo acima da leitura).

**2. `M_rota_funcao`, as 14 falhas de `abertura`** (`t-lei-02` 2, `t-lei-04` 3, `t-lei-05` 3, `t-lei-07` 3,
`t-lei-11` 3): o conjunto da v3 (sem mudança, sha256 acima) espera as aberturas antigas (`LEI_CONFIRMA`,
`LEI_APOIO`, `LEI_CORRIJO`) e o desenho novo escreve outras: nas falhas de `t-lei-02`, `t-lei-07` e `t-lei-11`
a abertura foi o apoio neutro com o modelo chamado (`LEI_APOIO_RELIDO`) e em `t-lei-04` e `t-lei-05`, o aviso de
divergência (`LEI_DIVERGENCIA`), em lugar do `LEI_CORRIJO` esperado. A combinação tira só `M_lei_correcao`
(`t-lei-04` e `t-lei-05`), não esse efeito nas outras métricas dos mesmos turnos.

**3. `M_rota_funcao`, as 9 falhas restantes** são de dados e de citação, e não têm essa explicação:
`t-dados-11/r3` (rota e consulta), `t-dados-12/r1-r3` (consulta; rota em duas), `t-dados-13/r1,r3` (consulta
ou rota) e `t-dados-17/r1-r3` (`citacao_contem`).

## Reanálise com a auditoria reanexada (não é a pontuação oficial)

`reanalise_499.py` reanexa à volta de cada turno do braço ligado a linha de auditoria exportada do banco
(`auditoria-ligado.csv`) e pontua de novo com a mesma regra e o mesmo conjunto
(`resumo-auditoria-reanexada.json`). **Correção de 2026-11-23, tarde:** a primeira versão do script dava a
cada volta todas as linhas de auditoria até o fim da janela dela, e a janela do turno 1 engolia a linha do
turno 2 quando ele começava logo depois (turnos curtos, sem modelo); o turno 2 ficava sem auditoria e
falhava por causa da reanálise, não do produto. Foi o que deixou 6 falhas "sem causa" (`t-dados-02/r1/t2`,
`t-dados-15/r1-r3/t3`, `t-lei-03/r1,r3/t3`). Agora cada linha vai à volta que começou por último antes dela:
238 voltas com auditoria (eram 231) e **`M_credito_zero` 0 falhas em 18, `M_caminho_fixo` 0 em 24**. As
duas falhas de crédito e de caminho fixo da primeira rodada eram, portanto, só o defeito do executor.
Sobram `M_rota_funcao` (23 de 87) e `M_lei_correcao` (6 de 6, que sai da combinação). Decisão da v3 continua
`NAO_ENTRA` e a combinada continua `NAO_ENTRA`, agora só por `M_rota_funcao` e `M584_errada_nao_confirma`
(`../2026-11-23-continuidade-l584/resumo-com-v3-reanexada.json`).

**Das 23 falhas de `M_rota_funcao`:** 14 são de `abertura` (conjunto da v3 com as aberturas antigas) e as 9
restantes são de dados e citação e **estão nas rodadas anteriores** (`t-dados-12/t2` e `t-dados-17/t2` falham
nas 3 repetições em 18/11, 19/11 pós-591 e 19/11 variância; `t-dados-13` em 1 a 2). Não vêm do desenho novo.
Com a regra `2026-11-23.4` do pontuador (aberturas fixas atuais aceitas, `regra-de-decisao.md`, escrita antes da
segunda rodada), esta mesma rodada daria `M_rota_funcao` 9 falhas em 87 e `M_lei_corrijo_indevido` 1 falha
em 27 (`t-lei-08/r3/t2`, aviso depois de uma resposta anterior certa; limiar zero). Isso é leitura de uma
rodada que já foi feita, não medição.

**A rodada pode ser repetida com o executor corrigido por quem decidir; este relatório não a repete.**
