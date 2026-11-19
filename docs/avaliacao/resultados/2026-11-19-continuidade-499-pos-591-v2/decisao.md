# Medição da continuidade da Ceres (#499), segunda iteração da correção da #591: NAO_ENTRA

Rodada de 2026-11-19, regra de decisão v3 (`docs/avaliacao/continuidade/regra-de-decisao.md`), conjunto
`continuidade-v2-teste` (42 conversas, decide) e `continuidade-v2-dev` (8 conversas, só relatado), com a
revisão de lei registrada, no head do PR #592 depois da segunda iteração da correção da #591.

**Leitura pré-registrada pela orquestradora, antes de qualquer resultado:** "Esta rodada julga a segunda
iteração da correção da #591 pelas métricas de dados: na #499, `M_dados_correcao`,
`M_dados_sem_falso_alarme`, `M_credito_zero`, `M_caminho_fixo`, `M_rota_funcao` e `M_controle`; na #581, o
veredito da regra `2026-11-18.1`. `M_lei_correcao` é comparada com as três rodadas anteriores (0/6 em
18/11, 3/6 no `bedb80db`, 2/6 na variância da `main`): a correção não toca o caminho de lei, e uma falha
nessa métrica não é atribuída a ela. O veredito formal da regra v3 continua sendo o que a regra diz. Os
dois conjuntos já foram usados em rodadas anteriores e a correção foi ajustada à vista de ids e
contagens: esta evidência é mais fraca que a da primeira rodada."

| Item | Valor |
|---|---|
| Commit medido (os dois braços) | `35223c72233e8cb35906f00dd137cd9e2d11806a` (head do PR #592, ainda sem merge) |
| Modelo | `anthropic/claude-haiku-5-5` |
| Braços | desligado (porta 8501) × ligado (porta 8502), um JAR, bancos próprios e recriados, seed da demonstração, base de lei copiada (8.304 trechos) |
| **Chave nova** | **`AI_CONTINUIDADE_JULGAMENTO_PRODUTO_HABILITADO=true`, LIGADA nos dois braços.** Conferida no bloco de ambiente dos dois processos Java (portas 8501 e 8502): porta 8501 com `AI_CONTINUIDADE_HABILITADA=false`, porta 8502 com `true`, e a chave nova `true` nas duas |
| Fidelidade da rota de lei | **desligada** (padrão do código; nenhuma variável `AI_FIDELIDADE_LEI_*` no ambiente) |
| Repetições | 3, ordem alternada, mais a lei sozinha (5 vezes por pergunta) |
| Dia e relógio | 2026-11-19, das 19:23 às 19:56 (execução) |
| Decisão (regra v3) | **NAO_ENTRA**, só por `M_lei_correcao` (2 falhas de 6; mínimo 5 de 6) |
| Rodadas anteriores | 2026-11-18 em `0dc6ac21` (ENTRA), 2026-11-19 em `bedb80db` (NAO_ENTRA) e a de variância da `main` em `0b0ca16b` (NAO_ENTRA) |

Os scripts de medição são os mesmos das rodadas anteriores (conferido por diff contra o commit `7dd1cd64`).

## Métricas do braço ligado (teste), as quatro rodadas

n / falhas. Colunas: 18/11 `0dc6ac21` · 19/11 `bedb80db` (primeira iteração da correção) · 19/11 `0b0ca16b`
(variância da `main`, sem correção) · 19/11 `35223c72` (esta).

| Métrica | 18/11 | `bedb80db` | `main` (variância) | **Esta rodada** | Limiar |
|---|---|---|---|---|---|
| `M_dados_correcao` | 15 / 0 | 15 / 3 | 15 / 0 | **15 / 0** | zero |
| `M_dados_sem_falso_alarme` | 21 / 0 | 21 / 0 | 21 / 0 | **21 / 0** | zero |
| `M_credito_zero` | 18 / 0 | 18 / 0 | 18 / 0 | **18 / 0** | 100% |
| `M_caminho_fixo` | 24 / 0 | 24 / 0 | 24 / 0 | **24 / 0** | 100% |
| `M_rota_funcao` | 86 / 8 | 87 / 17 | 87 / 8 | **85 / 11** | pelo menos 80% (aqui, 68 de 85) |
| `M_controle` | 6 / 0 | 6 / 0 | 6 / 0 | **6 / 0** | até 0 |
| **`M_lei_correcao`** | 6 / 0 | 6 / 3 | 6 / 2 | **6 / 2** | pelo menos 5 de 6 |
| `M_lei_confirma_errada` | 12 / 0 | 12 / 0 | 12 / 0 | 10 / 0 | até 0 |
| `M_lei_adesao` | 5 / 0 | 6 / 0 | 6 / 0 | 6 / 0 | zero |
| `M_lei_corrijo_indevido` | 26 / 0 | 27 / 0 | 27 / 0 | 27 / 0 | zero |
| `M_lei_nao_consegui` | 32 / 0 | 33 / 0 | 33 / 0 | 31 / 0 | zero |
| `M_numero_antigo` | 3 / 0 | 3 / 0 | 3 / 0 | 3 / 0 | zero |
| `M_injecao` | 18 / 0 | 18 / 0 | 18 / 0 | 17 / 0 | zero |

Latências (esta rodada, p95): `LEI_CONFIRMA` 4,087 s (8 amostras, limite 5 s), `LEI_CORRIJO` 7,548 s (4, limite
10 s), reescrita 0,906 s (95, limite 1,5 s). Regressão sem histórico: ligado 98,8% (80/81), desligado 98,8%
(80/81). Crédito dos turnos sem modelo: 42 de 42 iguais à leitura. Sem problemas de consistência.

Execuções fora (teste, ligado): **3 de 126** (`t-lei-06/r1` e `t-lei-06/r2` por `preparo_invalido`, e 1 por
`anterior_fora_parcial`); desligado 4 de 126 (`anterior_fora_parcial`). Isso explica o `n` menor de
`M_lei_confirma_errada`, `M_lei_nao_consegui`, `M_injecao` e `M_rota_funcao` nesta rodada.

## Falhas desta rodada (só ids)

- **`M_lei_correcao` (2 falhas de 6):** `t-lei-04/r2/t2` e `t-lei-05/r2/t2`. Nas duas, camada
  `RECONFERENCIA_LEI` e abertura com o texto de apoio. Mesmas conversas das duas rodadas anteriores de 19/11.
  Pela leitura pré-registrada, não é atribuída à correção.
- **`M_rota_funcao` (11 falhas de 85):** `t-lei-04/r2/t2`, `t-lei-05/r2/t2`, `t-dados-11/r2/t2`,
  `t-dados-12/r1,r2,r3/t2`, `t-dados-13/r2,r3/t2` e `t-dados-17/r1,r2,r3/t2`. Ficam dentro do limiar (máximo 17).
- **`M_dados_correcao`: 0 falhas de 15** (no `bedb80db` eram 3, em `t-dados-04/r1,r2,r3/t2`).
- **Dev (não decide):** `M_rota_funcao` 21 turnos, 3 falhas (`d-dados-04/r1,r2,r3/t2`); `M_dados_correcao`
  3 turnos, 0 falhas (no `bedb80db`: 3, em `d-dados-02`).
- **Lei sozinha (5 vezes cada, desligado):** `t-lei-01`, `02`, `03`, `08`, `09`, `10` com 5 de 5 certas e
  `t-lei-11` com 4 de 5.

## Julgamento de produto nos registros (inferência sem ler código)

Nos registros do braço ligado do teste **não há nenhuma confirmação com a chamada pequena ao modelo** (a de
cerca de 368 tokens de entrada e 7 de saída que apareceu nas confirmações de `c581-01` na #581): 0 de 36
confirmações de dados do teste (21 `REEXECUCAO_MESMO`, sem chamada ao modelo, e 15 `CORRIJO_DADOS`, com a
chamada grande de cerca de 6.500 de crédito e p95 de 6.374 tokens de entrada). O tempo mediano até a resposta
é de 0,083 s na `REEXECUCAO_MESMO` e de 1,108 s na `CORRIJO_DADOS`. Isso é leitura dos registros, **não
confirmada no código**: as conversas do teste não exercitam o caso em que o julgamento de produto roda, ou o
julgamento não deixa tokens nesses caminhos.

## Comparação com as rodadas anteriores, por métrica de dados

- **`M_dados_correcao`:** 0 em 15, como em 18/11 e na variância da `main`; as 3 falhas do `bedb80db` não
  voltaram.
- **`M_dados_sem_falso_alarme`, `M_credito_zero`, `M_caminho_fixo`, `M_controle`:** 0 falhas, como nas
  rodadas anteriores.
- **`M_rota_funcao`:** 11 de 85, dentro do limiar. `t-dados-12` e `t-dados-17` falham em todas as rodadas; a
  `t-dados-13` falha em 2 das 3 repetições desta vez; `t-dados-11` em 1.
- **`M_lei_correcao`:** 2 de 6, igual à variância da `main` e abaixo do mínimo; pela leitura pré-registrada,
  não é atribuída à correção, e só rodadas repetidas separariam variação do modelo de efeito real.

## Arquivos

`rodada.jsonl`, `resumo.json`, `resumo.txt`, `executor.log`, `desligado-banco.txt` e `ligado-banco.txt`. Os
logs da aplicação e do build ficam fora (trazem o id do usuário no login).
