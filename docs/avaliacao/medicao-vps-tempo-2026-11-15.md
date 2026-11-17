# Tempo da Ceres no ambiente publicado (VPS + Haiku por API), 2026-11-15

Item 3 da issue #497. Até aqui o p50 de 1,4 s e o p95 de 4,2 s do #483 eram só locais. Esta
medição roda a Ceres **como a banca a verá**: pela API pública do `www.agroconia.com.br`, com o
`claude-haiku-5-5` pela API da Anthropic, ponta a ponta.

## Resumo

**O ambiente publicado aguenta a demonstração ao vivo no que diz respeito a tempo.** As 54
chamadas (mais a prova) responderam 200, sem fallback, sem 429 nem 503. O pior caso foi 6,1 s
(MISTO). Os p50 de dados (1,5 s) e de lei (1,9 s) ficam bem abaixo das metas da milestone (10 s e
25 s). O custo real é outro: **o p50 de MISTO é 4,2 s, e a cota semanal da conta do Carlos subiu
de 7% para 40% com as 54 perguntas da série** (ver "Cota").

Três coisas **não foram medidas** (sem SSH na VPS): o p95 das camadas de classificação (regras e
embeddings, meta < 300 ms), a memória da VM e o `/healthz` da VM. Ver "Não medido".

## Como foi medido

- **Harness:** `docs/avaliacao/harness-api/harness.py`, com o ajuste desta tarefa (access token
  fornecido por `AGRO_TOKEN`, parada pela cota e linha de base de rede; README da pasta).
- **Conta:** produtor Carlos Eduardo Rossi (seed da banca, dados fictícios), em `homolog`.
- **Gabarito:** `docs/avaliacao/gabarito-ceres.json` (2026-11-05.3), só os ids abaixo. O conjunto do
  benchmark e o cego não foram lidos.
- **Perguntas (18 ids × 3 repetições = 54 chamadas, mais 1 de prova, D12):**
  - dados (8): D01, D05, D07, D10, D12, D13, D15, R01;
  - lei (6): L03, L04, L07, L08, L09, L11;
  - MISTO (4): M01, M02, M04, M05.
- **Tempo** = do envio do `POST /api/assistente/chat` à resposta completa, do cliente (esta
  máquina externa à VPS). Inclui rede e TLS; a espera por `Retry-After` não
  entra (não houve nenhuma).
- **Resultado bruto:** `docs/avaliacao/resultados/2026-11-15-1644-harness-api.json` (sem URL, sem
  e-mail, sem token).
- **Escolha dos ids:** feita antes de rodar, para cobrir as funções de dados (compras, apuração,
  vendas, guias, alertas, Funrural), seis perguntas de lei e quatro MISTO; não por resultado.
- Rodada única, em uma tarde de domingo, com a VPS sem outra carga conhecida. Uma rodada num único
  horário não mede variação entre horários.

## Resultados

Percentil por *nearest-rank*, igual ao relatório Java. n pequeno: o p95 de 12 a 24 respostas é
praticamente o pior ou o segundo pior valor.

| Rota esperada | n | p50 | p95 | máximo | Meta da milestone |
|---|---|---|---|---|---|
| DADOS | 24 | 1,46 s | 3,79 s | 3,79 s | p50 ≤ 10 s |
| LEGISLACAO | 18 | 1,90 s | 3,92 s | 3,92 s | p50 ≤ 25 s |
| MISTO | 12 | 4,16 s | 6,11 s | 6,11 s | sem meta própria (teto da lei, 25 s) |
| todas | 54 | 2,06 s | 4,90 s | 6,11 s | |

- **Metas atendidas com folga:** o p50 de dados é 14% da meta; o de lei, 8%.
- **Por rota obtida** (a que a Ceres de fato seguiu): DADOS 15 respostas, p50 1,18 s, p95 1,72 s;
  LEGISLACAO 18, p50 1,90 s, p95 3,92 s; MISTO 21, p50 3,98 s, p95 5,15 s. Dados que ficam em
  DADOS levam 1,0 a 1,7 s.
- **Rede:** `GET /api/v1/health` (sem modelo) p50 de 78 ms (máx. 102 ms, 7 amostras; 74 ms na
  prova). A distância até a VPS explica menos de 0,1 s de cada resposta; o resto é a Ceres.
- **Por repetição:** p50 1,90 s, 2,14 s e 1,92 s (rodadas 1 a 3); a rodada 2 teve o p95 maior
  (6,11 s) por causa de um único valor (M04). Não há tendência de aquecimento nem de degradação
  nas 54 chamadas, e a primeira pergunta (D01, 1,2 s) não mostra partida a frio.
- **Estabilidade por pergunta:** a maior dispersão é M04 (3,5 s; 4,6 s; 6,1 s), seguida de L04
  (2,8 a 3,9 s) e M05 (4,2 a 5,1 s). As demais variam menos de 1 s.
- **Prova (D12, 1 repetição):** 2,12 s, rota DADOS.

Por pergunta (ms, repetições 1/2/3):

| Id | Esperada | Rota obtida | Tempos |
|---|---|---|---|
| D01 | DADOS | DADOS | 1207 / 1696 / 1228 |
| D05 | DADOS | **MISTO** | 3785 / 3729 / 3315 |
| D07 | DADOS | **MISTO** | 3793 / 3492 / 3438 |
| D10 | DADOS | DADOS | 1457 / 1265 / 1625 |
| D12 | DADOS | DADOS | 1161 / 1724 / 1178 |
| D13 | DADOS | DADOS | 1125 / 1029 / 1005 |
| D15 | DADOS | DADOS | 1164 / 1071 / 1003 |
| R01 | DADOS | **MISTO** | 2507 / 2226 / 2037 |
| L03 | LEGISLACAO | LEGISLACAO | 2439 / 2271 / 2702 |
| L04 | LEGISLACAO | LEGISLACAO | 3020 / 3915 / 2781 |
| L07 | LEGISLACAO | LEGISLACAO | 1539 / 1653 / 1299 |
| L08 | LEGISLACAO | LEGISLACAO | 1898 / 1858 / 1915 |
| L09 | LEGISLACAO | LEGISLACAO | 1434 / 2059 / 1305 |
| L11 | LEGISLACAO | LEGISLACAO | 1394 / 2142 / 1441 |
| M01 | MISTO | MISTO | 4099 / 4896 / 4756 |
| M02 | MISTO | MISTO | 4047 / 3983 / 4067 |
| M04 | MISTO | MISTO | 3471 / 6114 / 4647 |
| M05 | MISTO | MISTO | 4155 / 5149 / 4460 |

### Rota (observação, não é o objeto desta tarefa)

O harness também compara a rota. Em 54 respostas: lei 18/18, MISTO 12/12 e **dados 15/24**. As
nove que erram são as mesmas três perguntas nas 3 repetições (D05, D07 e R01 → MISTO), o que
indica decisão determinística (regras ou embeddings), não ruído do modelo. É o item 1 da #497
(acerto de rota); não investiguei. Para a banca, o efeito é de tempo: essas três respondem em
2,0 a 3,8 s em vez de ~1,2 s, e o texto sai pela rota MISTO. Com 54 chamadas de uma conta só, isso
não prova a taxa do gabarito inteiro (82,9% no #483).

## Cota

`GET /api/assistente/uso` (`percentualUsado`, conta do Carlos, semana de segunda a domingo):

| Momento | `percentualUsado` |
|---|---|
| antes da prova | 7% |
| antes da série (depois da prova; o inteiro não mudou) | 7% |
| depois da série (54 perguntas) | **40%** |

- **33 pontos percentuais de cota por 54 perguntas**, cerca de 0,6 ponto (≈ 6 mil tokens
  ponderados) por pergunta em média. O teto de parada do harness era 50%; não foi atingido.
- Não registrei o uso depois de cada pergunta, então **não há custo por rota** nesta rodada; o
  harness só guarda o primeiro e o último valor. Se importar, um ajuste pequeno (gravar o uso por
  resposta) resolve numa rodada futura.
- O percentual vem do mesmo número que a tela mostra ao produtor. Esta medição foi feita num
  domingo; a semana da cota termina nesse mesmo dia e renova na segunda-feira, então ela não
  chega a 03/12. **Qualquer ensaio feito na semana da banca gasta a cota da conta da
  demonstração:** 54 perguntas consomem 33 pontos, e a banca precisa de uma folga nessa conta.
- Custo em dinheiro da API da Anthropic: não medido (não há leitura de fatura aqui). Ordem de
  grandeza pela cota: ~330 mil tokens ponderados.

## Comparação com a medição local do #483

| | Local, #483 (gabarito 2026-11-05.3, 87 × 3) | VPS, esta medição (54 chamadas) |
|---|---|---|
| p50 | 1,4 s | 2,06 s (todas as rotas medidas) |
| p95 | 4,2 s | 4,90 s |
| por rota | não consta no registro do #483 | dados 1,46 / 3,79 s; lei 1,90 / 3,92 s; MISTO 4,16 / 6,11 s |

- **Não é comparação igual.** O #483 mede o gabarito de 87 perguntas, onde 11 são fora de escopo e
  5 não têm rota; parte delas não passa pelo modelo de dados nem de lei, e a mistura de rotas é
  outra. Aqui só entram rotas de dados, lei e MISTO, e **22% das chamadas são MISTO** (mais de uma
  etapa com o modelo), contra 8% do gabarito. Não tenho o p50 local por rota, então **não dá para
  separar quanto da diferença vem da VPS e quanto vem da mistura**.
- **O que a comparação mostra:** o p50 sobe de 1,4 s para 2,06 s (+47%) e o p95 de 4,2 s para
  4,9 s (+17%), com uma mistura mais pesada. A diferença é pequena em segundos, mas **não posso
  afirmar que a VPS tem o mesmo desempenho do local**; o que os dados sustentam é que ela não está
  em outra ordem de grandeza. A rede custa ~0,08 s. Em dados corretamente roteados, a resposta
  completa (classificação com `bge-m3` em CPU ARM, funções de dados e a chamada ao Haiku) termina
  em no máximo 1,72 s.
- **Onde o ambiente publicado difere do local:** (1) a rede até a VPS e o TLS do Caddy (~0,08 s);
  (2) o embedding `bge-m3` roda em CPU ARM na VPS (não medido isoladamente, ver abaixo);
  (3) a cota e o bulkhead da produção estão ativos (nenhum 429/503 apareceu); (4) o provedor é o
  mesmo (Haiku por API), então a latência do modelo é a mesma que a local, mais a distância
  entre a VPS e a API. Para separar com rigor o efeito de cada diferença, falta a medição das
  camadas na VPS e uma rodada local com o mesmo subconjunto de perguntas.

## Não medido (e por quê)

| Item da #497 | Situação | Motivo |
|---|---|---|
| p95 das regras e das embeddings (meta < 300 ms), `bge-m3` em CPU ARM | **não medido** | A latência por camada só existe dentro da VPS (auditoria `assistente_interacao` e logs); o acesso é por SSH, que não foi liberado. Pela API só dá o tempo total. Limite superior indireto: dados corretamente roteados terminam em ≤ 1,72 s, então a classificação não pode ter passado disso; não prova a meta de 300 ms. |
| Memória da VM com a configuração adotada | **não medido** | Idem (`free`, `docker stats`). A medição de 2026-11-07 (15,3 GB com o 4B e o `bge-m3` carregados) é de antes da troca do chat para o Haiku. |
| `/healthz` da VM | **só o público** | `GET https://www.agroconia.com.br/healthz` devolve 200 `UP` (é o endpoint do Caddy) e `GET /api/v1/health` devolve 200 `UP` do backend, ambos de fora. O `/actuator/health` do domínio público cai no frontend (200 com HTML) e não prova nada. O health detalhado (inclui `ollama` e `clamav`) não foi lido. |

## Ressalvas

- Uma rodada, um horário, uma conta. Amostra pequena para p95.
- Rodada medida de uma máquina fora da VPS, numa única rede; um cliente em outra rede pode ver diferença de ~0,1 s.
- O harness mede o `POST /chat` (resposta completa). O `POST /chat/stream` (SSE), usado pela tela
  da banca, **não foi medido**; ele devolve só a resposta já validada (sem tokens parciais), então o
  tempo até a primeira informação deve ser parecido, mas não foi confirmado.
- Atalhos da tela (as três perguntas fixas), conversa leve e recusas não entraram: não usam o
  modelo e respondem pela leitura da mensagem.

## Reproduzir

```bash
export AGRO_API_URL='https://www.agroconia.com.br'
export AGRO_TOKEN='<access token de 15 min do produtor; só no ambiente do processo>'
export AGRO_REPETICOES=3
export AGRO_IDS='D01,D05,D07,D10,D12,D13,D15,R01,L03,L04,L07,L08,L09,L11,M01,M02,M04,M05'
python3 -I docs/avaliacao/harness-api/harness.py
```
