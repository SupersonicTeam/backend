# Rodada de DIAGNÓSTICO da correção de lei da continuidade (#584), com a auditoria instrumentada

**Não é medição oficial e não tem veredito.** Responde à pergunta de mecanismo deixada pelo `[DIAGNÓSTICO]` da #584:
qual guarda descarta a correção de lei em `t-lei-04` e em `t-lei-05`. Só ids, códigos e contagens: nenhum texto de
pergunta, de resposta, de lei ou do modelo neste documento.

| Item | Valor |
|---|---|
| Commit | `833183a40671bd7c7d3708b2fd9e0fbfa2eef446` (`main` com o PR #602: a auditoria grava `reconferencia_*`) |
| Modelo | `anthropic/claude-haiku-5-5` |
| Braço | só o ligado (`AI_CONTINUIDADE_HABILITADA=true`, porta 8502), banco próprio recriado (`agroconia_diag_lei_ligado`), seed da demonstração (158 notas), base de lei copiada (8.304 trechos) |
| Chave do julgamento de produto | no padrão do commit (não exportada) |
| Fidelidade da rota de lei | desligada (padrão do código) |
| Conversas | `t-lei-04` e `t-lei-05` (teste) e `d-lei-02` (dev), as que alimentam o `M_lei_correcao`; 12 execuções de cada, 36 ao todo |
| Dia e relógio | 2026-11-20, das 01:51 às 03:33 (de madrugada) |

## Como foi rodada

- **Driver novo, executor intacto.** O `executar_continuidade.py` não roda a #499 só com o braço ligado e não controla pausas
  entre repetições (e chamá-lo em laço repetiria o preparo do conjunto de teste, que devolve uma nota: pelo desenho, não testado). Por isso
  `driver_diagnostico.py` (nesta pasta) importa as classes `Braco` e `Rodada` do executor **sem alterá-lo** e chama
  `Rodada.conversa` para cada execução: par real, histórico e confirmação ao vivo, a mesma chamada da medição. O que muda é só o
  agendamento. `rodar-diagnostico.sh` é o `rodar_medicao_581.sh` com o driver no lugar do executor; `pontuar_diagnostico.py`
  gera as tabelas, com a mesma conferência do `pontuar_continuidade.py` para dizer se a execução passou no `M_lei_correcao`.
- **Agenda.** 18 pares. Em cada par, a primeira execução vem **depois de uma pausa** de 360 s (medido: 361,1 a 361,6 s desde a
  execução anterior), sem chamar a Ceres; a segunda vem **logo depois** (0,6 a 0,8 s entre o fim da anterior e o registro da agenda; a primeira chamada ainda espera o ritmo mínimo de 3,2 s do executor). Cada conversa fica com
  6 execuções de cada tipo, 12 ao todo. Hora de cada execução na tabela 2.
- **`bge-m3`.** `ollama ps` antes de começar, antes e depois de cada uma das 18 pausas e no fim: **carregado em todas as
  verificações** (18 de 18 antes e depois da pausa; início e fim também). Só leitura; nada foi recarregado nas execuções depois
  da pausa (o aquecimento único é o do `rodar-diagnostico.sh`, antes de começar, com `keep_alive` de 2 h).
- **Campos novos vêm do banco.** O executor lê a auditoria logo depois da resposta; nas 4 execuções que falharam (1,3 a 1,9 s) ele não achou linha nova
  e o `auditoria` do JSONL ficou vazio, embora a linha exista no banco (hipótese: foi gravada depois da leitura; não verificada). As colunas sem texto
  de `assistente_interacao` foram exportadas do banco (`auditoria-reconferencia.csv`, 36 linhas) e casadas com as execuções pela
  ordem cronológica: em 36 de 36 a hora gravada cai de 0 a 40 s depois do pedido do turno 2. O banco tem 36 linhas de
  reconferência, uma por execução, e as contagens por código batem com as das 36 linhas `Ceres: RECONFERENCIA_*` do log da aplicação (que fica
  fora do repositório): `CORRIGIU` 32, `CORRIJO_SEM_ANCORA` 3, `CONFIRMO_NUMERO_CITACAO` 1. O `pontuar_diagnostico.py` recusa o
  casamento se o número de linhas ou a janela de hora não bater.

## Resultado em uma linha

`t-lei-04` **0 falhas em 12**; `t-lei-05` **4 falhas em 12** (`CORRIJO_SEM_ANCORA` 3, `CONFIRMO_NUMERO_CITACAO` 1); `d-lei-02` 0 em 12.
A pausa não mudou nada (2 falhas depois da pausa e 2 logo depois, todas em `t-lei-05`).

## Leitura (por código, sem texto)

- **`t-lei-05`: 3 das 4 falhas são a guarda (a), `CORRIJO_SEM_ANCORA`; a outra é `CONFIRMO_NUMERO_CITACAO`.** Nas quatro o descarte
  veio **antes** da concordância (guarda h): `reconferencia_tokens_concordancia` e `reconferencia_concordancia` ficam vazios, e os
  tokens de entrada da pergunta são 1676 a 1754, contra 2781 a 2870 nas que passaram, cuja concordância soma 1099 tokens. Isso
  confirma, nos registros, a leitura de código do `[INSTRUMENTAÇÃO]`: a diferença de cerca de 1.100 tokens é a execução que não
  chegou à concordância. A busca vetorial deu `OK` em todas e o prompt levou 5 trechos em todas.
- **A saída do modelo nas 4 falhas é menor** (83 a 262 tokens, contra 339 a 487 nas que passaram), e a de `CONFIRMO_NUMERO_CITACAO`
  é a menor (83): nessa o modelo respondeu confirmando, e a confirmação foi descartada. Nas 3 de `CORRIJO_SEM_ANCORA` o modelo propôs
  correção e a guarda (a) a descartou.
- **`t-lei-04` não falhou nenhuma vez**, nem depois de pausa nem logo depois: 12 de 12 com `CORRIGIU`, mesma chamada em todas
  (3258 a 3259 tokens de entrada, concordância 1326, 1 dispositivo, 1 trecho citado). Nas três rodadas de 19/11 ela tinha falhado
  4 vezes em 9 (e 0 em 3 em 18/11). **Nesta rodada, com a auditoria instrumentada, a falha de `t-lei-04` não apareceu**, então o
  código da guarda dela não foi observado.
- **O que separa passou de falhou em `t-lei-05`:** o desfecho (`CORRIGIU` × `CORRIJO_SEM_ANCORA`/`CONFIRMO_NUMERO_CITACAO`) e o que
  vem dele (tokens da pergunta, tokens de saída, ausência da concordância). **Não separam**: `reconferencia_busca` (`OK` em 12 de
  12), `reconferencia_trechos_busca` e `_trechos_prompt` (5 em todas), `_caracteres_trechos` (1567 em todas) e a ordem ou a hora.
  `reconferencia_dispositivos` e `_trechos_citados` ficam em 1 nas 4 falhas e em 1 (5 execuções) ou 2 (3 execuções) nas 8 que
  passaram: com 1 dispositivo há 4 falhas em 9; com 2, 0 em 3. Com n tão pequeno, é só uma observação.
- **A pausa não mudou nada:** `t-lei-05` 4 de 6 passaram depois da pausa e 4 de 6 logo depois; `t-lei-04` e `d-lei-02` passaram 6 de 6 em
  cada tipo. As falhas: `CORRIJO_SEM_ANCORA` 2 depois da pausa e 1 logo depois; `CONFIRMO_NUMERO_CITACAO` 1 logo depois.

## O que esta rodada não permite dizer

- Qual guarda derruba `t-lei-04`: não falhou aqui (0 de 12). Falhou 4 de 9 nas três rodadas de 19/11; pode ser outro dia, outro
  horário (esta rodada foi de madrugada) ou acaso. O desfecho `CORRIJO_SEM_ANCORA` de `t-lei-05` não vale para ela.
- Por que o modelo propõe uma correção sem âncora ou confirma com número fora da citação: o texto bruto do modelo não é gravado.
- Se a repetição 2 concentrar falhas, como na rodada anterior: esta rodada tem um braço só e não reproduz a ordem dos braços.
- n = 12 por conversa; 4 falhas em `t-lei-05` (33%, intervalo de 95% de Wilson de 14% a 61%) são pouco para separar hipóteses.

## Tabelas

### 1. Por conversa

| Conversa | Execuções | Passaram | Falharam | Fora |
|---|---|---|---|---|
| `t-lei-04` | 12 | 12 | 0 | 0 |
| `t-lei-05` | 12 | 8 | 4 | 0 |
| `d-lei-02` | 12 | 12 | 0 | 0 |

### 2. As execuções

Tipo: `pausa` = veio depois de uma pausa; `logo` = veio logo depois de outra execução. bge antes/depois = bge-m3 carregado antes e depois da pausa (`ollama ps`).

| # | Execução | Tipo | bge antes/depois | Hora | Resultado | Abertura | Desfecho | reconferencia_lei | busca | dispositivos | trechos citados | trechos prompt | tokens concordância | concordância | Tokens ent/saí | Latência ms | Citações |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `t-lei-04/r1` | pausa | sim/sim | 01:51:13 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3258/437 | 3197 | 1 |
| 2 | `t-lei-05/r1` | logo |  | 01:51:21 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1099 | RESPONDIDA | 2851/385 | 3020 | 1 |
| 3 | `t-lei-05/r2` | pausa | sim/sim | 01:57:35 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1099 | RESPONDIDA | 2794/355 | 2705 | 1 |
| 4 | `d-lei-02/r1` | logo |  | 01:57:47 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3415/405 | 2935 | 2 |
| 5 | `d-lei-02/r2` | pausa | sim/sim | 02:03:55 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3446/468 | 3248 | 2 |
| 6 | `t-lei-04/r2` | logo |  | 02:04:03 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3259/410 | 3233 | 1 |
| 7 | `t-lei-04/r3` | pausa | sim/sim | 02:10:12 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3258/438 | 3432 | 1 |
| 8 | `t-lei-05/r3` | logo |  | 02:10:20 | **falhou** | LEI_APOIO | RECONFERENCIA_DESCARTADA | CORRIJO_SEM_ANCORA | OK | 1 | 1 | 5 | None | None | 1709/195 | 1497 | 1 |
| 9 | `t-lei-05/r4` | pausa | sim/sim | 02:16:27 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1099 | RESPONDIDA | 2793/448 | 3766 | 1 |
| 10 | `d-lei-02/r3` | logo |  | 02:16:36 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3373/457 | 3175 | 2 |
| 11 | `d-lei-02/r4` | pausa | sim/sim | 02:22:45 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3444/420 | 3242 | 2 |
| 12 | `t-lei-04/r4` | logo |  | 02:22:53 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3259/435 | 3842 | 1 |
| 13 | `t-lei-04/r5` | pausa | sim/sim | 02:29:02 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3259/434 | 3716 | 1 |
| 14 | `t-lei-05/r5` | logo |  | 02:29:11 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1099 | RESPONDIDA | 2841/414 | 4139 | 2 |
| 15 | `t-lei-05/r6` | pausa | sim/sim | 02:35:25 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1099 | RESPONDIDA | 2854/458 | 3711 | 2 |
| 16 | `d-lei-02/r5` | logo |  | 02:35:45 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3366/374 | 3285 | 2 |
| 17 | `d-lei-02/r6` | pausa | sim/sim | 02:41:53 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3458/471 | 3302 | 2 |
| 18 | `t-lei-04/r6` | logo |  | 02:42:01 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3258/438 | 3386 | 1 |
| 19 | `t-lei-04/r7` | pausa | sim/sim | 02:48:10 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3259/405 | 3208 | 1 |
| 20 | `t-lei-05/r7` | logo |  | 02:48:18 | **falhou** | LEI_APOIO | RECONFERENCIA_DESCARTADA | CONFIRMO_NUMERO_CITACAO | OK | 1 | 1 | 5 | None | None | 1676/83 | 1237 | 1 |
| 21 | `t-lei-05/r8` | pausa | sim/sim | 02:54:25 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1099 | RESPONDIDA | 2781/487 | 3364 | 1 |
| 22 | `d-lei-02/r7` | logo |  | 02:54:33 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3448/449 | 3180 | 2 |
| 23 | `d-lei-02/r8` | pausa | sim/sim | 03:00:42 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3379/450 | 3306 | 2 |
| 24 | `t-lei-04/r8` | logo |  | 03:00:50 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3259/434 | 3184 | 1 |
| 25 | `t-lei-04/r9` | pausa | sim/sim | 03:06:58 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3259/440 | 3388 | 1 |
| 26 | `t-lei-05/r9` | logo |  | 03:07:07 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1099 | RESPONDIDA | 2794/339 | 3294 | 1 |
| 27 | `t-lei-05/r10` | pausa | sim/sim | 03:13:20 | **falhou** | LEI_APOIO | RECONFERENCIA_DESCARTADA | CORRIJO_SEM_ANCORA | OK | 1 | 1 | 5 | None | None | 1754/262 | 1872 | 1 |
| 28 | `d-lei-02/r9` | logo |  | 03:13:28 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3424/405 | 3346 | 2 |
| 29 | `d-lei-02/r10` | pausa | sim/sim | 03:19:36 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3460/469 | 3725 | 2 |
| 30 | `t-lei-04/r10` | logo |  | 03:19:45 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3259/410 | 3526 | 1 |
| 31 | `t-lei-04/r11` | pausa | sim/sim | 03:25:54 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3258/436 | 3185 | 1 |
| 32 | `t-lei-05/r11` | logo |  | 03:26:02 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1099 | RESPONDIDA | 2870/374 | 3067 | 2 |
| 33 | `t-lei-05/r12` | pausa | sim/sim | 03:32:11 | **falhou** | LEI_APOIO | RECONFERENCIA_DESCARTADA | CORRIJO_SEM_ANCORA | OK | 1 | 1 | 5 | None | None | 1733/176 | 1496 | 1 |
| 34 | `d-lei-02/r11` | logo |  | 03:32:18 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3454/433 | 4520 | 2 |
| 35 | `d-lei-02/r12` | pausa | sim/sim | 03:38:27 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 2 | 2 | 5 | 1391 | RESPONDIDA | 3448/403 | 3271 | 2 |
| 36 | `t-lei-04/r12` | logo |  | 03:38:36 | passou | LEI_CORRIJO | RESPONDIDA | CORRIGIU | OK | 1 | 1 | 5 | 1326 | RESPONDIDA | 3259/435 | 3323 | 1 |

### 3. Falhas por código de `reconferencia_lei`, por conversa

| Conversa | `CONFIRMO_NUMERO_CITACAO` | `CORRIJO_SEM_ANCORA` | Total de falhas |
|---|---|---|---|
| `t-lei-04` | 0 | 0 | 0 |
| `t-lei-05` | 1 | 3 | 4 |
| `d-lei-02` | 0 | 0 | 0 |

Código de `reconferencia_lei` em TODAS as execuções (passaram e falharam), por conversa:

| Conversa | Resultado | `CONFIRMO_NUMERO_CITACAO` | `CORRIGIU` | `CORRIJO_SEM_ANCORA` |
|---|---|---|---|---|
| `t-lei-04` | passou | 0 | 12 | 0 |
| `t-lei-04` | falhou | 0 | 0 | 0 |
| `t-lei-05` | passou | 0 | 8 | 0 |
| `t-lei-05` | falhou | 1 | 0 | 3 |
| `d-lei-02` | passou | 0 | 12 | 0 |
| `d-lei-02` | falhou | 0 | 0 | 0 |

### 4. Passou × falhou na mesma conversa (campos novos)

| Conversa | Resultado | n | Tokens ent | Tokens saí | Latência ms | dispositivos | trechos citados | trechos prompt | tokens concordância | concordância (códigos) | busca (códigos) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `t-lei-04` | passou | 12 | 3258 a 3259 (mediana 3259) | 405 a 440 (mediana 435) | 3184 a 3842 (mediana 3354) | {'1': 12} | {'1': 12} | 5 | 1326 | {'RESPONDIDA': 12} | {'OK': 12} |
| `t-lei-05` | passou | 8 | 2781 a 2870 (mediana 2817) | 339 a 487 (mediana 399) | 2705 a 4139 (mediana 3329) | {'1': 5, '2': 3} | {'1': 5, '2': 3} | 5 | 1099 | {'RESPONDIDA': 8} | {'OK': 8} |
| `t-lei-05` | falhou | 4 | 1676 a 1754 (mediana 1721) | 83 a 262 (mediana 185) | 1237 a 1872 (mediana 1496) | {'1': 4} | {'1': 4} | 5 | - | {'None': 4} | {'OK': 4} |
| `d-lei-02` | passou | 12 | 3366 a 3460 (mediana 3445) | 374 a 471 (mediana 441) | 2935 a 4520 (mediana 3278) | {'2': 12} | {'2': 12} | 5 | 1391 | {'RESPONDIDA': 12} | {'OK': 12} |

### 5. A pausa mudou alguma coisa?

| Conversa | Tipo | n | Passaram | Falharam | Códigos das falhas |
|---|---|---|---|---|---|
| `t-lei-04` | pausa | 6 | 6 | 0 | {} |
| `t-lei-04` | logo | 6 | 6 | 0 | {} |
| `t-lei-05` | pausa | 6 | 4 | 2 | {'CORRIJO_SEM_ANCORA': 2} |
| `t-lei-05` | logo | 6 | 4 | 2 | {'CORRIJO_SEM_ANCORA': 1, 'CONFIRMO_NUMERO_CITACAO': 1} |
| `d-lei-02` | pausa | 6 | 6 | 0 | {} |
| `d-lei-02` | logo | 6 | 6 | 0 | {} |
| `todas` | pausa | 18 | 16 | 2 | {'CORRIJO_SEM_ANCORA': 2} |
| `todas` | logo | 18 | 16 | 2 | {'CORRIJO_SEM_ANCORA': 1, 'CONFIRMO_NUMERO_CITACAO': 1} |

bge-m3 carregado: no início {'bge_m3_carregado': True, 'modelos_carregados': 1}, no fim {'bge_m3_carregado': True, 'modelos_carregados': 1}; antes e depois de cada pausa, na coluna 'bge antes/depois' da tabela 2.
