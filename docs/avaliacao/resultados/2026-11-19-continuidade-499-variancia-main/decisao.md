# Rodada de variância da medição da continuidade da Ceres (#499), na `main`, sem a correção da #591

**Esta NÃO é uma rodada oficial.** Não substitui o veredito de 2026-11-18 (`ENTRA`, commit `0dc6ac21`)
nem o de 2026-11-19 (`NAO_ENTRA`, commit `bedb80db`, head do PR #592). Serve para responder se a linha
de base varia sozinha de uma rodada para outra, depois de `M_lei_correcao` ter dado 3 falhas em 6 em
19/11, contra 0 em 6 em 18/11, num commit cuja correção não toca o caminho de lei (conferido pela
orquestradora; quem mede não leu o diff).

Regra de decisão v3 (`docs/avaliacao/continuidade/regra-de-decisao.md`), conjunto
`continuidade-v2-teste` (42 conversas, decide) e `continuidade-v2-dev` (8 conversas, só relatado), com a
revisão de lei registrada.

| Item | Valor |
|---|---|
| Commit medido (os dois braços) | `0b0ca16bb24a0608d21e47cbd2102edd10df01b1` (`origin/main` de 2026-11-19; o código de `assistente/continuidade/` é o mesmo do `0dc6ac21`, conferido por `git diff --stat` entre os dois commits, sem diferença) |
| Modelo | `anthropic/claude-haiku-5-5` |
| Braços | desligado (porta 8501) × ligado (porta 8502), um JAR, bancos próprios e recriados, seed da demonstração, base de lei copiada (8.304 trechos) |
| Fidelidade da rota de lei | **desligada** (padrão do código; nenhuma variável `AI_FIDELIDADE_LEI_*` no ambiente da rodada) |
| Repetições | 3, ordem alternada, mais a lei sozinha (5 vezes por pergunta) |
| Dia e relógio | 2026-11-19, das 16:43 às 17:15 (execução) |
| Decisão pontuada | **NAO_ENTRA** (só por `M_lei_correcao`; rótulo da pontuação, não é veredito oficial) |
| Seed | 158 notas (157 na rodada da #581 de 18/11 e na do `bedb80db`): o último commit da `main` (#299, `0b0ca16b`) mexeu no seed da demonstração, e a contagem subiu em uma nota |

## Métricas do braço ligado (teste), as três rodadas

n / falhas. 18/11 no `0dc6ac21`, 19/11 no `bedb80db` (com a correção da #591), esta rodada na `main`
`0b0ca16b`.

| Métrica | 18/11 `0dc6ac21` | 19/11 `bedb80db` | Esta rodada `0b0ca16b` | Limiar |
|---|---|---|---|---|
| **`M_lei_correcao`** | 6 / 0 | 6 / 3 | **6 / 2** | pelo menos 5 de 6 |
| `M_lei_confirma_errada` | 12 / 0 | 12 / 0 | 12 / 0 | até 0 |
| `M_lei_adesao` | 5 / 0 | 6 / 0 | 6 / 0 | zero |
| `M_dados_correcao` | 15 / 0 | 15 / 3 | 15 / 0 | zero |
| `M_rota_funcao` | 86 / 8 | 87 / 17 | 87 / 8 | pelo menos 70 de 87 |
| `M_controle` | 6 / 0 | 6 / 0 | 6 / 0 | até 0 |

Demais métricas desta rodada, todas cumprindo o limiar: `M_lei_corrijo_indevido` 27 / 0,
`M_lei_nao_consegui` 33 / 0, `M_dados_sem_falso_alarme` 21 / 0, `M_numero_antigo` 3 / 0,
`M_injecao` 18 / 0, `M_credito_zero` 18 / 0, `M_caminho_fixo` 24 / 0. Latências: `LEI_CONFIRMA` p95 1,774 s
(7 amostras), `LEI_CORRIJO` p95 3,597 s (4), reescrita p95 0,912 s (99). Regressão sem histórico: ligado
100% (81/81), desligado 98,8% (80/81). Execuções fora (teste): ligado 0 de 126, desligado 4 de 126.
Crédito dos turnos sem modelo: 42 de 42 iguais à leitura. Sem problemas de consistência.

## Falhas desta rodada (só ids)

- **`M_lei_correcao` (2 falhas de 6):** `t-lei-04/r3/t2` e `t-lei-05/r2/t2`. Nas duas, camada
  `RECONFERENCIA_LEI` e abertura com o texto de apoio ("a resposta anterior se baseia em…"), em vez de
  "corrijo". Também contam em `M_rota_funcao`.
- **`M_rota_funcao` (8 falhas de 87, o máximo permitido é 17):** `t-dados-12/r1,r2,r3/t2`,
  `t-dados-17/r1,r2,r3/t2`, `t-lei-04/r3/t2` e `t-lei-05/r2/t2`.
- **Dev (não decide):** `M_rota_funcao` 21 turnos, 3 falhas (`d-dados-04/r1,r2,r3/t2`); as demais
  métricas do dev sem falha.
- **Lei sozinha (5 vezes cada, desligado):** `t-lei-01`, `02`, `03`, `08`, `09`, `10` com 5 de 5 certas;
  **`t-lei-11` com 3 de 5** (na rodada do `bedb80db`, 5 de 5).

## Quais conversas falham em mais de uma rodada (`M_rota_funcao`)

Repetições com falha em cada rodada (– = nenhuma).

| Conversa | 18/11 `0dc6ac21` | 19/11 `bedb80db` | Esta rodada `0b0ca16b` | Rodadas com falha |
|---|---|---|---|---|
| `t-dados-12` | r1, r2, r3 | r1, r2, r3 | r1, r2, r3 | 3 |
| `t-dados-17` | r1, r2, r3 | r1, r2, r3 | r1, r2, r3 | 3 |
| `t-dados-13` | r2, r3 | r1, r2, r3 | – | 2 |
| `t-lei-04` | – | r1, r2 | r3 | 2 |
| `t-lei-05` | – | r2 | r2 | 2 |
| `t-dados-04` | – | r1, r2, r3 | – | 1 |
| `t-dados-11` | – | r2, r3 | – | 1 |
| `d-dados-04` (dev) | r2 | r1, r2, r3 | r1, r2, r3 | 3 |
| `d-dados-02` (dev) | – | r1, r2, r3 | – | 1 |

- **Falham nas três rodadas:** `t-dados-12`, `t-dados-17` e, no dev, `d-dados-04`.
- **Falham em duas:** `t-dados-13` (18/11 e `bedb80db`), `t-lei-04` e `t-lei-05` (`bedb80db` e esta
  rodada).
- **Falham só em uma, a do `bedb80db`:** `t-dados-04`, `t-dados-11` e, no dev, `d-dados-02`.

## Leitura (não é fato provado; n é pequeno)

- **`M_dados_correcao`:** na `main` deu 0 falhas em 15 nas duas rodadas (18/11 e esta). As 3 falhas de
  `t-dados-04` só aparecem no `bedb80db`, com a correção. Isso é compatível com a correção ter mudado o
  caminho de confirmação de dados, e esta rodada não mostra variação da `main` nessa métrica.
- **`M_lei_correcao`:** a `main` sem a correção também falhou (2 de 6), contra 0 de 6 em 18/11. Então a
  métrica **varia sem a correção**, o que sustenta a leitura de que as 3 falhas do `bedb80db` não
  são efeito dela. Com n = 6, 2 falhas já passam do limiar (mínimo 5 de 6).
- **Ressalva:** as duas falhas desta rodada e as três do `bedb80db` estão nas mesmas duas conversas
  (`t-lei-04`, `t-lei-05`), e as duas rodadas de 19/11 falham nelas, ao contrário da de 18/11. Isso pode
  ser variação do modelo, ou um efeito do dia, ou algo da `main` fora de `assistente/continuidade/`: a
  seed passou de 157 para 158 notas entre 18/11 e hoje. Três rodadas não separam essas hipóteses.
- **`M_rota_funcao`:** `t-dados-12` e `t-dados-17` falham sempre, nas três rodadas, e já falhavam em 18/11,
  então não são da correção.

## Arquivos

`rodada.jsonl`, `resumo.json`, `resumo.txt`, `executor.log`, `desligado-banco.txt` e
`ligado-banco.txt`. Os logs da aplicação e do build ficam fora (trazem o id do usuário no login).
