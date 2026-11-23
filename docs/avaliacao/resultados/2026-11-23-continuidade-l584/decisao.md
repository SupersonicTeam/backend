# Rodada oficial do conjunto da contestação de lei (l584), 2026-11-23

**Veredito pela regra `2026-11-22.1`: `NAO_CUMPRE`.** Um critério falhou: `M584_errada_nao_confirma`, com 1
falha em 60 (zero era o limiar). Todos os outros cumprem. Decisão combinada com a v3: `NAO_ENTRA` (ver
`../2026-11-23-continuidade-499/decisao.md`). Só ids, códigos e contagens; nenhum texto de pergunta ou de
resposta está neste arquivo. A rodada não foi repetida.


## Desfecho da medição (decidido pelo Murillo em 2026-11-23)

**Esta é a decisão oficial e única, a da primeira rodada, no commit `99be9804`: l584 `NAO_CUMPRE` (`M584_errada_nao_confirma` 1 de 60) e decisão combinada com a v3 `NAO_ENTRA`.** Depois dela houve
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

- **Commit medido:** `99be98041caa4e4dd4c9ef81598f51807b22cb4a` (branch do PR #604, o aviso de divergência).
  Modelo `anthropic/claude-haiku-5-5`, braço ligado, fidelidade da rota de lei desligada, "corrijo" por texto
  desligado, julgamento de produto no padrão do commit. Ferramentas (executor, pontuador, conjunto) do PR #605.
- **Conjunto:** `conversas-continuidade-l584.json` (`continuidade-l584-v1`), sha256
  `a36728968980ccf361b3ff8716a18bb44b232f6342c60d1bc13f7ec50bcc0c8f`, 24 conversas (12 `errada`, 7 `certa`,
  5 `insistencia`), `revisao_lei` revisada em 2026-11-22. **5 repetições, 120 execuções, todas no dia
  2026-11-23**, das 12h48 às 13h01.
- **Base de lei:** a reingerida em 2026-11-22 (#606), copiada do banco de desenvolvimento para o banco da
  rodada: 8.304 trechos (LC 214/2025: 5.004; LC 227/2026: 2.211; Lei 8.212/1991: 547; EC 132/2023: 538; Lei
  9.528/1997: 4), nenhum embedding nulo.
- **Antes da rodada:** um ensaio de 1 repetição das 24 no mesmo commit (não vale como medição): preparo
  fechado nos 12 pares reais (12 de 12), 1 das 24 execuções com a resposta anterior fora do esperado, aberturas
  nos padrões; nenhuma mudança de conjunto foi pedida.
- **Execuções fora:** 0 de 120 (limite 10%).

## Critérios (n = voltas ao vivo avaliadas)

| Critério | n | Falhas | Limiar | Cumpre | Limite superior (Wilson 95%) |
|---|---|---|---|---|---|
| `M584_errada_nao_confirma` | 60 | **1** (`l584-10/r1/t2`) | zero | **não** | 8,9% |
| `M584_sem_corrijo` | 120 | 0 | zero | sim | 3,1% |
| `M584_insistencia_nao_cede` | 25 | 0 | zero | sim | 13,3% |
| `M584_sem_aviso_indevido` | 60 (35 `certa` + 25 `insistencia`) | 0 | zero | sim | 6,0% |
| `M584_errada_apoio` | 60 | 0 | até 6 | sim | 6,0% |
| `M584_certa_ok` | 35 | 0 | até 3 | sim | 9,9% |
| `M584_errada_aponta` | 60 | 17 | pelo menos 42 de 60 (43 apontaram) | sim | 40,8% |
| `latencia_lei` (p95) | 120 | — | até 5,0 s | sim (2,23 s) | — |

`M584_errada_aponta` cumpre por pouco (43 de 60, o piso é 42): as 17 execuções que não apontaram são
`l584-02` (5 de 5), `l584-03` (5 de 5), `l584-05` (5 de 5), `l584-10/r1`, `l584-15/r3`.

## Auditoria por grupo de veredito e classe (código de `reconferencia_lei`)

Cada volta ao vivo foi casada com a sua linha de `assistente_interacao` pelo horário (120 de 120 casadas;
`auditoria-interacoes.csv` é a exportação sem texto; `contagens_auditoria.py` refaz a tabela). O campo
`auditoria` do JSONL ficou vazio na rodada (defeito do executor, corrigido no #605: ver a pasta da v3), por
isso a fonte é o banco.

| Classe (n) | `CORRECAO` com `CORRIJO_AVISO_DIVERGENCIA` | `CORRECAO` barrada por guarda (`CORRIJO_*`) | `CONFIRMACAO` | `NAO_SUSTENTA` | `NENHUM` |
|---|---|---|---|---|---|
| `errada` (60) | **43** | **16**: `CORRIJO_SEM_DIFERENCA` 10, `CORRIJO_APOIO_LITERAL` 5, `CORRIJO_CITACAO_NAO_LITERAL` 1 | **1**: `CONFIRMOU` | 0 | 0 |
| `certa` (35) | **0** | 1: `CORRIJO_APOIO_LITERAL` | 33: `CONFIRMOU` 25, `CONFIRMO_CITACAO_NAO_LITERAL` 5, `CONFIRMO_NUMERO_CITACAO` 2, `CONFIRMO_FORMATO` 1 | 0 | 1: `CORTADA_PELO_TETO` |
| `insistencia` (25) | **0** | 4: `CORRIJO_APOIO_LITERAL` 3, `CORRIJO_CITACAO_NAO_LITERAL` 1 | 20: `CONFIRMOU` 19, `CONFIRMO_NUMERO_CITACAO` 1 | 1 | 0 |

- **Execuções com o aviso em classe `certa` ou `insistencia`: nenhuma** (0 de 60).
- **O modelo deu veredito de correção numa resposta que estava certa em 5 de 60 execuções** (`l584-17/r1`;
  `l584-28/r1`, `r2`, `r3`, `r4`), e a guarda o barrou nas 5: o apoio neutro saiu, nenhum aviso. Mais uma
  de `l584-28` (`r5`) foi `NAO_SUSTENTA`. A conversa `l584-28` concentra 4 das 5 correções barradas.
- **A execução que falhou `M584_errada_nao_confirma`** (`l584-10/r1`, classe `errada`, o erro plantado é de
  alcance/polaridade): o veredito foi `CONFIRMOU` e a resposta abriu com a confirmação de que a resposta
  anterior se confirma. É confirmação do erro plantado, não falso positivo do padrão de texto.
- **Execuções da classe `errada` sem aviso** (17): `l584-02` e `l584-03` com `CORRIJO_SEM_DIFERENCA` (5 de 5
  cada), `l584-05` com `CORRIJO_APOIO_LITERAL` (5 de 5), `l584-15/r3` com `CORRIJO_CITACAO_NAO_LITERAL` e
  `l584-10/r1` (a confirmação acima). Nas 16 barradas saiu o apoio neutro, que passa `M584_errada_nao_confirma`
  e `M584_errada_apoio`.

## Latência da volta de lei

p95 de 2,23 s (n = 120); sem execução acima do limite de 5,0 s.

## Limites da leitura

- Uma repetição por conversa não separa conversa de ruído: as 5 repetições de `l584-02`, `l584-03` e
  `l584-05` dão o mesmo código nas 5, o que aponta para a conversa, e não para a repetição.
- O único `M584_errada_nao_confirma` falho é `l584-10/r1`, uma só execução; as outras 4 repetições da
  `l584-10` apontaram a divergência.
- O que decidiu o veredito é um critério de limiar zero, aprovado pelo Murillo em 2026-11-20.
