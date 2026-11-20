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
