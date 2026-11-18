# Medição da continuidade da Ceres (#499): decisão ENTRA

Rodada oficial de 2026-11-18, regra de decisão v3 (`docs/avaliacao/continuidade/regra-de-decisao.md`), conjunto `continuidade-v2-teste` (42 conversas, decide) e `continuidade-v2-dev` (8 conversas, só relatado), revisão de lei registrada.

| Item | Valor |
|---|---|
| Commit medido (os dois braços) | `0dc6ac213fb000cfcdb879df2adbaa9cb7fe94fc` |
| Modelo | `anthropic/claude-haiku-5-5` |
| Braços | desligado (`AI_CONTINUIDADE_HABILITADA=false`, porta 8501) × ligado (`true`, porta 8502), um JAR, bancos próprios e recriados, seed da demonstração, base de lei copiada (8.304 trechos) |
| Repetições | 3, ordem alternada (desligado primeiro nas 1 e 3), mais a lei sozinha (5 vezes por pergunta) |
| Dia | 2026-11-18, todas as repetições |
| Relógio | 13:30 a 14:06 (build e subida incluídos; 13:34 a 14:04 de execução) |
| Decisão | **ENTRA** |

## Métricas do braço ligado (teste)

| Métrica | n | Falhas | Limiar | Cumpre |
|---|---|---|---|---|
| `M_lei_corrijo_indevido` | 26 | 0 | zero | sim |
| `M_lei_adesao` | 5 | 0 | zero | sim |
| `M_lei_nao_consegui` | 32 | 0 | zero | sim |
| `M_dados_sem_falso_alarme` | 21 | 0 | zero | sim |
| `M_numero_antigo` | 3 | 0 | zero | sim |
| `M_injecao` | 18 | 0 | zero | sim |
| `M_dados_correcao` | 15 | 0 | 100% | sim |
| `M_credito_zero` | 18 | 0 | 100% | sim |
| `M_caminho_fixo` | 24 | 0 | 100% | sim |
| `M_rota_funcao` | 86 | 8 | até 17 (≥ 80%) | sim |
| `M_lei_correcao` | 6 | 0 | até 1 (≥ 70%) | sim |
| `M_lei_confirma_errada` | 12 | 0 | até 0 (5%) | sim |
| `M_controle` | 6 | 0 | até 0 (5%) | sim |
| Latência `LEI_CONFIRMA` (p95) | 6 | – | ≤ 5 s | 2,125 s |
| Latência `LEI_CORRIJO` (p95) | 6 | – | ≤ 10 s | 3,594 s |
| Latência da reescrita (p95 extra) | 99 | – | ≤ 1,5 s | 1,122 s |
| Regressão sem histórico | 81 | – | ligado ≥ desligado − 5 pontos | ligado 98,8% (80/81), desligado 98,8% (80/81) |

- **Execuções fora (ligado, teste):** 1 de 126 (0,8%; limite 10%), por `anterior_fora_parcial`.
  O braço desligado teve 4 (todas `anterior_fora_parcial`). Nenhum `sem_medicao` nem
  `preparo_invalido`. Sem problemas de consistência (`problemas: []`).
- **Falhas de `M_rota_funcao`:** `t-dados-12/r1,r2,r3/t2`, `t-dados-13/r2,r3/t2`, `t-dados-17/r1,r2,r3/t2`.
  As oito estão no 2º turno de três conversas de dados, e nenhuma é de lei.
- **Tamanho de amostra:** `M_lei_correcao` (n=6), `M_lei_adesao` (n=5), `M_numero_antigo` (n=3) e
  `M_controle` (n=6) têm poucos turnos válidos, porque dependem de poucas conversas × 3 repetições.
  "Zero falhas" nelas é evidência fraca; a regra v3 não pede mais, e os limiares estão cumpridos.
- **Dev (não decide):** `M_rota_funcao` 21 turnos, 1 falha (`d-dados-04/r2/t2`); as demais métricas
  do dev sem falha.

## O que só se relata (regra, seção "Relatório")

- **`R_funcao` (erro de escolha de função):** n=3, 0 falhas.
- **Crédito dos turnos sem modelo (v3):** 42 de 42 com o crédito igual à leitura da #511
  (⌈caracteres ÷ 4⌉); nenhum turno sem modelo acima da leitura.
- **Crédito médio por caminho (braço ligado, teste):**

| Caminho (camada \| abertura) | n | Crédito médio | p95 de tokens de entrada |
|---|---|---|---|
| `ATALHO_FIXO` | 6 | 9,5 | – |
| `CONFIRMACAO \| REEXECUCAO_MESMO` | 21 | 3,86 | – |
| `CONFIRMACAO \| CORRIJO_DADOS` | 15 | 6.505 | 6.009 |
| `CONVERSA_LEVE` (nenhuma e seguimento novo) | 3 e 15 | 4 e 3 | – |
| `FILTRO_INJECAO` | 3 | 19 | – |
| `EMBEDDING` | 12 | 4.776 | 6.012 |
| `LLM` | 15 | 5.672 | 8.248 |
| `REGRA` (nenhuma) | 75 | 5.618 | 6.897 |
| `REGRA \| NAO_CONSEGUI_CONFIRMAR` | 3 | 4.066 | 2.481 |
| `RECONFERENCIA_LEI \| LEI_CONFIRMA` | 11 | 2.515 | 2.094 |
| `RECONFERENCIA_LEI \| LEI_CORRIJO` | 6 | 4.747 | 3.258 |
| `RECONFERENCIA_LEI \| LEI_APOIO` | 15 | 2.565 | 2.477 |
| `RECONFERENCIA_LEI` (atalho, sem fundamento, só trechos) | 3, 3 e 3 | 3 | – |

  A tabela completa, com medianas e p95 de tempo, está em `resumo.json`
  (`partes.teste.ligado.caminhos`).
- **Reconferências que caem no apoio (`LEI_APOIO`):** 15 turnos, de 42 reconferências de lei do braço ligado.
- **p95 de tokens de entrada (auditoria do banco do braço ligado, todas as chamadas com modelo da
  rodada, teste e dev):** 6.920 (237 chamadas; máximo 8.250).
- **Respostas acima de 6.000 caracteres:** só `t-fixo-01` (texto fixo de 6.128 caracteres), nas 3
  repetições de cada braço. As respostas dos modelos não passaram de 6.000.
- **Lei sozinha (braço desligado, 5 vezes cada):** `t-lei-01`, `t-lei-02`, `t-lei-03`, `t-lei-08`,
  `t-lei-09`, `t-lei-10` e `t-lei-11`: 35 de 35 classificadas como certas, nenhuma errada nem
  indefinida.
- **Braço desligado (hoje):** 81 turnos de seguimento caíram em `CONVERSA_LEVE|SEGUIMENTO_ANTIGO`,
  o texto de seguimento antigo; a regressão dos primeiros turnos é a mesma nos dois braços (80/81).

## Ressalvas

- **Ramo "corrijo" com a consulta anterior certa:** **não** é medido por este conjunto; é a #581.
- **Fidelidade da rota de lei:** a rodada mede a continuidade com a fidelidade da rota de lei
  **desligada** (`AI_FIDELIDADE_LEI_HABILITADA` não definida, padrão desligado). Ligar as duas flags juntas
  não foi medido aqui.
- **Provedor por API:** as perguntas do conjunto saem para a API da Anthropic (`AI_CHAT_PROVIDER=anthropic`);
  a rodada foi local, sem tocar na VPS.
- **Embedding:** `bge-m3` aquecido pelo script logo antes do executor; a rodada foi contínua (36
  minutos), sem pausa longa, e a camada `EMBEDDING` apareceu nos dois braços.
- **Ensaio de fumaça (não oficial, 1 repetição, mesmo SHA):** 0 de 42 execuções fora no braço
  ligado. Não vale como medição e não está nesta pasta.

## Arquivos

| Arquivo | O que é |
|---|---|
| `rodada.jsonl` | uma linha por chamada (pergunta, par montado, lei sozinha, aquecimento), com commit, modelo, crédito e auditoria |
| `resumo.json` / `resumo.txt` | pontuação por métrica, por conversa e por caminho, e a decisão |
| `executor.log` | log do executor (horários das repetições) |
| `desligado-banco.txt` / `ligado-banco.txt` | contagem do banco de cada braço (iguais) |

Os logs das aplicações ficam fora do PR, porque trazem o id do usuário no login. Nenhuma chave
aparece nos arquivos.
