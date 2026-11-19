# Medição da continuidade da Ceres (#499) depois da correção da #591: NAO_ENTRA

Rodada de 2026-11-19, regra de decisão v3 (`docs/avaliacao/continuidade/regra-de-decisao.md`), conjunto
`continuidade-v2-teste` (42 conversas, decide) e `continuidade-v2-dev` (8 conversas, só relatado), com a
revisão de lei registrada, no head do PR #592 (a correção da #591).

| Item | Valor |
|---|---|
| Commit medido (os dois braços) | `bedb80dbfd2d4fe119ac65f0303ed0c86a4bf930` (head do PR #592, ainda sem merge) |
| Modelo | `anthropic/claude-haiku-5-5` |
| Braços | desligado (porta 8501) × ligado (porta 8502), um JAR, bancos próprios e recriados, seed da demonstração, base de lei copiada (8.304 trechos) |
| Fidelidade da rota de lei | **desligada** (padrão do código; nenhuma variável `AI_FIDELIDADE_LEI_*` no ambiente da rodada) |
| Repetições | 3, ordem alternada, mais a lei sozinha (5 vezes por pergunta) |
| Dia e relógio | 2026-11-19, das 12:31 às 13:03 (execução) |
| Decisão | **NAO_ENTRA** (esperado: `ENTRA`) |
| Rodada anterior | 2026-11-18, commit `0dc6ac21` (decisão ENTRA), pasta `2026-11-18-continuidade-499` |

## Métricas do braço ligado (teste), antes e depois

| Métrica | Antes (0dc6ac21): n / falhas | Depois (bedb80db): n / falhas | Limiar | Cumpre agora |
|---|---|---|---|---|
| `M_lei_corrijo_indevido` | 26 / 0 | 27 / 0 | zero | sim |
| `M_lei_adesao` | 5 / 0 | 6 / 0 | zero | sim |
| `M_lei_nao_consegui` | 32 / 0 | 33 / 0 | zero | sim |
| `M_dados_sem_falso_alarme` | 21 / 0 | 21 / 0 | zero | sim |
| `M_numero_antigo` | 3 / 0 | 3 / 0 | zero | sim |
| `M_injecao` | 18 / 0 | 18 / 0 | zero | sim |
| **`M_dados_correcao`** | 15 / 0 | **15 / 3** | **zero (100%)** | **não** |
| `M_credito_zero` | 18 / 0 | 18 / 0 | 100% | sim |
| `M_caminho_fixo` | 24 / 0 | 24 / 0 | 100% | sim |
| `M_rota_funcao` | 86 / 8 | 87 / 17 | pelo menos 70 de 87 (≥ 80%) | sim (17 falhas, o máximo permitido) |
| **`M_lei_correcao`** | 6 / 0 | **6 / 3** | **pelo menos 5 de 6 (≥ 70%)** | **não** |
| `M_lei_confirma_errada` | 12 / 0 | 12 / 0 | até 0 (5%) | sim |
| `M_controle` | 6 / 0 | 6 / 0 | até 0 (5%) | sim |
| Latência `LEI_CONFIRMA` (p95) | 6 amostras, 2,125 s | 9 amostras, 2,266 s | ≤ 5 s | sim |
| Latência `LEI_CORRIJO` (p95) | 6 amostras, 3,594 s | 3 amostras, 3,087 s | ≤ 10 s | sim |
| Latência da reescrita (p95 extra) | 99 amostras, 1,122 s | 97 amostras, 0,891 s | ≤ 1,5 s | sim |
| Regressão sem histórico | ligado 98,8% (80/81), desligado 98,8% (80/81) | ligado 100% (81/81), desligado 97,5% (79/81) | ligado ≥ desligado − 5 pontos | sim |

## Falhas e ids (sem conteúdo de conversa)

- **`M_dados_correcao` (3 falhas):** `t-dados-04/r1/t2`, `t-dados-04/r2/t2`, `t-dados-04/r3/t2` (as 3
  repetições de uma conversa). Todas também contam em `M_rota_funcao`. Nas 3 a camada é
  `CONFIRMACAO` e a resposta abriu com a repetição da consulta ("o resultado é o mesmo") seguida da
  dica de reformular, em vez de "corrijo".
- **`M_lei_correcao` (3 falhas de 6):** `t-lei-04/r1/t2`, `t-lei-04/r2/t2`, `t-lei-05/r2/t2`. Também
  contam em `M_rota_funcao`. Nas 3 a camada é `RECONFERENCIA_LEI` e a resposta abriu com o texto de
  apoio ("a resposta anterior se baseia em…"), em vez de "corrijo".
- **`M_rota_funcao` (17 falhas):** as três de `M_dados_correcao`, as três de `M_lei_correcao`, mais
  `t-dados-11/r2,r3/t2`, `t-dados-12/r1,r2,r3/t2`, `t-dados-13/r1,r2,r3/t2` e `t-dados-17/r1,r2,r3/t2`.
  Na rodada anterior as 8 falhas eram `t-dados-12/r1,r2,r3`, `t-dados-13/r2,r3` e `t-dados-17/r1,r2,r3`:
  as três conversas seguem falhando, `t-dados-13` agora também na repetição 1, e entraram `t-dados-04`,
  `t-dados-11`, `t-lei-04` e `t-lei-05`.
- **Dev (não decide):** `M_rota_funcao` 21 turnos, 6 falhas (`d-dados-02/r1,r2,r3/t2` e
  `d-dados-04/r1,r2,r3/t2`); `M_dados_correcao` 3 turnos, 3 falhas (`d-dados-02/r1,r2,r3/t2`). A
  `d-dados-02` é a conversa de dev da categoria "anterior errada por produto". Na rodada anterior o
  dev tinha 1 falha (`d-dados-04/r2/t2`) e `M_dados_correcao` sem falha.

## O que mudou em relação à rodada anterior, sem interpretar a causa

- O caminho `CONFIRMACAO | CORRIJO_DADOS` caiu de 15 para 12 turnos no braço ligado (teste).
- `M_dados_correcao` passou de 0 para 3 falhas, e `M_lei_correcao` de 0 para 3 falhas de 6
  (a reconferência de lei tem n pequeno; o `M_lei_corrijo_indevido` segue em 0).
- A lei sozinha (5 vezes por pergunta, desligado) segue certa em todas: `t-lei-01`, `02`, `03`, `08`,
  `09`, `10`, `11` com 5 de 5 cada.

## Demais relatados

- **Execuções fora (teste):** ligado 0 de 126; desligado 5 de 126 (`anterior_fora_parcial`).
- **Crédito dos turnos sem modelo (v3):** 42 de 42 com o crédito igual à leitura da #511; nenhum turno
  sem modelo acima da leitura.
- Sem problemas de consistência (`problemas: []`).

## Leitura

Duas métricas de limiar não cumpridas (`M_dados_correcao` e `M_lei_correcao`), logo `NAO_ENTRA` pela
regra v3. Esta rodada não é repetida por conta própria: a decisão sobre o PR #592 é da orquestradora.
O resultado da medição da #581 no mesmo commit está em `2026-11-19-continuidade-581-pos-591`.

**As 3 falhas de `M_lei_correcao` (leitura, não fato provado).** A orquestradora conferiu no diff do
PR #592 que o commit medido não altera o caminho de lei da continuidade: a correção só mexe na
confirmação de dados (quem mede não leu o diff). Se for assim, as 3 falhas em 6 execuções, contra 0
em 6 na rodada de 2026-11-18, apontam para **variação entre rodadas do modelo** numa métrica com
n = 6, e não para efeito da correção. Esta rodada não prova isso: a mesma conversa de lei falhou em
2 das 3 repetições (`t-lei-04`) e outra em 1 (`t-lei-05`), e só rodadas repetidas no mesmo commit
separariam a variação do modelo de um efeito real. As 3 falhas de `M_dados_correcao` estão no caminho
de confirmação de dados, que, segundo a orquestradora, é o único que o PR #592 altera; esta rodada
também não prova que a correção as causou.

## Arquivos

`rodada.jsonl`, `resumo.json`, `resumo.txt`, `executor.log`, `desligado-banco.txt` e
`ligado-banco.txt`. Os logs da aplicação e do build ficam fora (trazem o id do usuário no login).
