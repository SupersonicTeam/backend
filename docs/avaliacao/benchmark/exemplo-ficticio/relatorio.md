# Benchmark da Ceres — 2 arquiteturas × 3 modelos (DADOS FICTÍCIOS)

> ⚠️ **DADOS FICTÍCIOS DE TESTE.** Este relatório foi gerado a partir de JSONs inventados pelo `gerar_exemplo_ficticio.py`, só para mostrar o formato. **Nenhum número aqui foi medido.** Não use em slide, artigo nem banca.

Gerado por `docs/avaliacao/benchmark/gerar_relatorio.py` (issue #348). Não editar à mão: gere de novo. Cada taxa é a média das rodadas, com a faixa (mínimo–máximo) entre parênteses; n = perguntas avaliadas no critério em cada rodada.

## Condições

| Item | Valor |
|---|---|
| Rodada | FICTÍCIO |
| Máquina | FICTÍCIO: máquina inventada |
| Ceres 1 | `2463c27630c6397106b6addea74e2424c139b60b` (merge da regua (#264, PR #302): o modelo classifica a rota e redige a resposta; sem #274, #265 e #266) |
| Ceres 2.0 | `5b8b1e1e48c0c312ff10f25885fbbba987f41086` (main: regras + embeddings na classificacao, resposta de dados por template, o modelo so escolhe a funcao) |
| Conjunto de perguntas | 2026-11-05.5 (140 perguntas, sha256 `64d7855c6bca…`), o mesmo nas células (a Ceres 1 lê a cópia adaptada) |
| Capacidade comum | 118 perguntas (a comparação justa; por tema abaixo) |
| Ceres 2.0 e a Sessão 7 (#270: só as funções da rota, prefixo fixo do prompt, teto de tokens por chamada, num_ctx por propriedade) (#270) | **depois** dela inteira (o SHA medido contém o merge do último PR) — a coluna inclui as mudanças dela |
| Repetições | 3 |
| Embedding | bge-m3 |
| Ollama | FICTÍCIO (igual nas células: o executor recusa começar se mudar) |
| `qwen3:0.6b` | quantização FICTÍCIO, ? parâmetros, digest `000000000000` |
| `qwen3:1.7b` | quantização FICTÍCIO, ? parâmetros, digest `000000000000` |
| `qwen3:4b-instruct` | quantização FICTÍCIO, ? parâmetros, digest `000000000000` |
| Opções do chat | temperature=0.2, num-ctx=8192, num-predict=1024, think-option=false |

Todas as células rodaram **na mesma máquina**, uma por vez. **A latência é local e relativa**: serve para comparar modelos e arquiteturas entre si nesta máquina. **Não é a latência de produção**: a da VPS (CPU ARM, sem GPU) fica na seção "Latência na VPS", medida pelo harness da API.

O **valor esperado** de cada pergunta é calculado na hora por cada arquitetura, sobre o próprio backend e o próprio seed (padrão da #158): o acerto de número mede se a Ceres repete o número **do próprio sistema**. O cálculo fiscal mudou depois do `2463c27` (#272, #277, #298); pergunta cujo valor esperado difere entre as arquiteturas sai do recorte de capacidade comum.

Fora da capacidade comum por valor esperado diferente entre os seeds dos commits, conferido na preparação (`divergentesConhecidos` da matriz): D13, D14.

### Pensamento, memória e onde cada modelo rodou

| Célula | Pensamento | Memória do modelo (Ollama `/api/ps`) | Pico do processo do Ollama | Onde rodou | Duração | Saída |
|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | FICTÍCIO: desligado (think=false; nenhum raciocínio na prova) | 0,6 GB | 2,0 GB | qwen3:0.6b: GPU; bge-m3: CPU | 1470 s | 0 |
| Ceres 1 · `qwen3:1.7b` | FICTÍCIO: desligado (think=false; nenhum raciocínio na prova) | 1,5 GB | 2,9 GB | qwen3:1.7b: GPU; bge-m3: CPU | 3360 s | 0 |
| Ceres 1 · `qwen3:4b-instruct` | FICTÍCIO: desligado (think=false; nenhum raciocínio na prova) | 3,3 GB | 4,7 GB | qwen3:4b-instruct: GPU; bge-m3: CPU | 7980 s | 0 |
| Ceres 2.0 · `qwen3:0.6b` | FICTÍCIO: desligado (think=false; nenhum raciocínio na prova) | 0,6 GB | 2,0 GB | qwen3:0.6b: GPU; bge-m3: CPU | 1008 s | 0 |
| Ceres 2.0 · `qwen3:1.7b` | FICTÍCIO: desligado (think=false; nenhum raciocínio na prova) | 1,5 GB | 2,9 GB | qwen3:1.7b: GPU; bge-m3: CPU | 1680 s | 0 |
| Ceres 2.0 · `qwen3:4b-instruct` | FICTÍCIO: desligado (think=false; nenhum raciocínio na prova) | 3,3 GB | 4,7 GB | qwen3:4b-instruct: GPU; bge-m3: CPU | 2940 s | 0 |

A VPS de produção tem 24 GB divididos com Postgres, backend, ClamAV e o BGE-M3: a coluna do pico inclui o BGE-M3 carregado junto.

## Por tema

Quantas perguntas cada tema tem no conjunto e quantas ficaram na capacidade comum. **Leia a comparação antiga pela coluna da capacidade comum**, não pelo total. "Só rota" = a pergunta só mede a rota (o resto do gabarito não se aplica).

| Tema | Conjunto | Capacidade comum | `so_ceres_2` | Só rota |
|---|---|---|---|---|
| Guias e vencimentos | 10 | 2 | 6 | 0 |
| Notas fiscais | 8 | 4 | 3 | 0 |
| Compras e insumos | 7 | 7 | 0 | 0 |
| Vendas e produtos | 7 | 3 | 4 | 0 |
| Apuração e créditos de IBS/CBS | 7 | 7 | 0 | 0 |
| Retenções (Funrural/Senar, split payment) | 6 | 6 | 0 | 0 |
| Legislação geral da reforma | 10 | 10 | 0 | 0 |
| Legislação do agro | 10 | 10 | 0 | 0 |
| MISTO (dado + lei) | 7 | 7 | 0 | 0 |
| Fora de escopo e recusa | 10 | 10 | 0 | 0 |
| Segurança (injeção, dado de outro produtor) | 8 | 8 | 0 | 0 |
| Linguagem do dia a dia | 10 | 4 | 6 | 0 |
| Externas: alíquotas de produtos agropecuários | 10 | 10 | 0 | 10 |
| Externas: tributação de insumos agrícolas e pecuários | 10 | 10 | 0 | 10 |
| Externas: situações práticas do produtor rural | 10 | 10 | 0 | 10 |
| Externas: planejamento, notas fiscais e transição | 10 | 10 | 0 | 10 |
| **Total** | 140 | 118 | 19 | 40 |

### Acerto por tema × célula — capacidade comum

Pergunta certa = todo critério que se aplica a ela acertou (rota, função, parâmetros, número, citação, recusa, sem vazamento e sem falha técnica). Média (mínimo–máximo) das rodadas.

| Tema | Ceres 1 · `qwen3:0.6b` | Ceres 1 · `qwen3:1.7b` | Ceres 1 · `qwen3:4b-instruct` | Ceres 2.0 · `qwen3:0.6b` | Ceres 2.0 · `qwen3:1.7b` | Ceres 2.0 · `qwen3:4b-instruct` |
|---|---|---|---|---|---|---|
| Guias e vencimentos | 16,7% (0,0–50,0) n=2 | 33,3% (0,0–50,0) n=2 | 66,7% (0,0–100,0) n=2 | 83,3% (50,0–100,0) n=2 | 83,3% (50,0–100,0) n=2 | 83,3% (50,0–100,0) n=2 |
| Notas fiscais | 8,3% (0,0–25,0) n=4 | 16,7% (0,0–25,0) n=4 | 41,7% (25,0–50,0) n=4 | 41,7% (25,0–75,0) n=4 | 58,3% (50,0–75,0) n=4 | 83,3% (75,0–100,0) n=4 |
| Compras e insumos | 4,8% (0,0–14,3) n=7 | 4,8% (0,0–14,3) n=7 | 42,9% (28,6–57,1) n=7 | 28,6% (0,0–57,1) n=7 | 42,9% (28,6–57,1) n=7 | 76,2% (71,4–85,7) n=7 |
| Vendas e produtos | 22,2% (0,0–66,7) n=3 | 0,0% n=3 | 55,6% (33,3–66,7) n=3 | 22,2% (0,0–33,3) n=3 | 66,7% (33,3–100,0) n=3 | 55,6% (33,3–100,0) n=3 |
| Apuração e créditos de IBS/CBS | 9,5% (0,0–14,3) n=7 | 14,3% (0,0–28,6) n=7 | 42,9% n=7 | 38,1% (28,6–57,1) n=7 | 61,9% (57,1–71,4) n=7 | 61,9% (28,6–85,7) n=7 |
| Retenções (Funrural/Senar, split payment) | 16,7% (0,0–33,3) n=6 | 22,2% (16,7–33,3) n=6 | 61,1% (50,0–66,7) n=6 | 27,8% (16,7–33,3) n=6 | 72,2% (50,0–83,3) n=6 | 94,4% (83,3–100,0) n=6 |
| Legislação geral da reforma | 30,0% (10,0–50,0) n=10 | 50,0% (40,0–60,0) n=10 | 63,3% (50,0–70,0) n=10 | 53,3% (30,0–70,0) n=10 | 73,3% (70,0–80,0) n=10 | 86,7% (80,0–100,0) n=10 |
| Legislação do agro | 30,0% (20,0–50,0) n=10 | 50,0% (40,0–70,0) n=10 | 56,7% (30,0–70,0) n=10 | 56,7% (40,0–80,0) n=10 | 63,3% (40,0–80,0) n=10 | 80,0% (60,0–90,0) n=10 |
| MISTO (dado + lei) | 0,0% n=7 | 14,3% n=7 | 28,6% (0,0–57,1) n=7 | 19,0% (14,3–28,6) n=7 | 52,4% (42,9–71,4) n=7 | 57,1% (42,9–71,4) n=7 |
| Fora de escopo e recusa | 33,3% (30,0–40,0) n=10 | 36,7% (10,0–60,0) n=10 | 73,3% (40,0–100,0) n=10 | 40,0% n=10 | 73,3% (60,0–90,0) n=10 | 86,7% (80,0–90,0) n=10 |
| Segurança (injeção, dado de outro produtor) | 66,7% (62,5–75,0) n=8 | 79,2% (62,5–87,5) n=8 | 91,7% (87,5–100,0) n=8 | 83,3% (75,0–100,0) n=8 | 87,5% (75,0–100,0) n=8 | 91,7% (87,5–100,0) n=8 |
| Linguagem do dia a dia | 25,0% (0,0–50,0) n=4 | 33,3% (0,0–75,0) n=4 | 50,0% (0,0–100,0) n=4 | 50,0% (25,0–75,0) n=4 | 66,7% (50,0–75,0) n=4 | 58,3% (25,0–100,0) n=4 |
| Externas: alíquotas de produtos agropecuários | 50,0% n=10 | 56,7% (50,0–60,0) n=10 | 63,3% (40,0–90,0) n=10 | 80,0% n=10 | 83,3% (60,0–100,0) n=10 | 83,3% (70,0–90,0) n=10 |
| Externas: tributação de insumos agrícolas e pecuários | 46,7% (40,0–50,0) n=10 | 70,0% (50,0–80,0) n=10 | 93,3% (90,0–100,0) n=10 | 63,3% (60,0–70,0) n=10 | 86,7% (80,0–90,0) n=10 | 90,0% (80,0–100,0) n=10 |
| Externas: situações práticas do produtor rural | 60,0% (50,0–70,0) n=10 | 46,7% (40,0–60,0) n=10 | 80,0% (60,0–100,0) n=10 | 73,3% (70,0–80,0) n=10 | 93,3% (90,0–100,0) n=10 | 96,7% (90,0–100,0) n=10 |
| Externas: planejamento, notas fiscais e transição | 53,3% (40,0–70,0) n=10 | 66,7% (60,0–80,0) n=10 | 70,0% (60,0–80,0) n=10 | 60,0% (40,0–70,0) n=10 | 90,0% (80,0–100,0) n=10 | 83,3% (80,0–90,0) n=10 |

### Acerto por tema × célula — conjunto inteiro

Pergunta certa = todo critério que se aplica a ela acertou (rota, função, parâmetros, número, citação, recusa, sem vazamento e sem falha técnica). Média (mínimo–máximo) das rodadas.

| Tema | Ceres 1 · `qwen3:0.6b` | Ceres 1 · `qwen3:1.7b` | Ceres 1 · `qwen3:4b-instruct` | Ceres 2.0 · `qwen3:0.6b` | Ceres 2.0 · `qwen3:1.7b` | Ceres 2.0 · `qwen3:4b-instruct` |
|---|---|---|---|---|---|---|
| Guias e vencimentos | 3,3% (0,0–10,0) n=10 | 6,7% (0,0–10,0) n=10 | 23,3% (10,0–30,0) n=10 | 50,0% (40,0–60,0) n=10 | 66,7% (60,0–70,0) n=10 | 83,3% (70,0–100,0) n=10 |
| Notas fiscais | 8,3% (0,0–12,5) n=8 | 16,7% (0,0–25,0) n=8 | 29,2% (25,0–37,5) n=8 | 41,7% (25,0–50,0) n=8 | 66,7% (62,5–75,0) n=8 | 70,8% (62,5–87,5) n=8 |
| Compras e insumos | 4,8% (0,0–14,3) n=7 | 4,8% (0,0–14,3) n=7 | 42,9% (28,6–57,1) n=7 | 28,6% (0,0–57,1) n=7 | 42,9% (28,6–57,1) n=7 | 76,2% (71,4–85,7) n=7 |
| Vendas e produtos | 9,5% (0,0–28,6) n=7 | 0,0% n=7 | 23,8% (14,3–28,6) n=7 | 28,6% (14,3–42,9) n=7 | 71,4% (57,1–85,7) n=7 | 66,7% (42,9–85,7) n=7 |
| Apuração e créditos de IBS/CBS | 9,5% (0,0–14,3) n=7 | 14,3% (0,0–28,6) n=7 | 42,9% n=7 | 38,1% (28,6–57,1) n=7 | 61,9% (57,1–71,4) n=7 | 61,9% (28,6–85,7) n=7 |
| Retenções (Funrural/Senar, split payment) | 16,7% (0,0–33,3) n=6 | 22,2% (16,7–33,3) n=6 | 61,1% (50,0–66,7) n=6 | 27,8% (16,7–33,3) n=6 | 72,2% (50,0–83,3) n=6 | 94,4% (83,3–100,0) n=6 |
| Legislação geral da reforma | 30,0% (10,0–50,0) n=10 | 50,0% (40,0–60,0) n=10 | 63,3% (50,0–70,0) n=10 | 53,3% (30,0–70,0) n=10 | 73,3% (70,0–80,0) n=10 | 86,7% (80,0–100,0) n=10 |
| Legislação do agro | 30,0% (20,0–50,0) n=10 | 50,0% (40,0–70,0) n=10 | 56,7% (30,0–70,0) n=10 | 56,7% (40,0–80,0) n=10 | 63,3% (40,0–80,0) n=10 | 80,0% (60,0–90,0) n=10 |
| MISTO (dado + lei) | 0,0% n=7 | 14,3% n=7 | 28,6% (0,0–57,1) n=7 | 19,0% (14,3–28,6) n=7 | 52,4% (42,9–71,4) n=7 | 57,1% (42,9–71,4) n=7 |
| Fora de escopo e recusa | 33,3% (30,0–40,0) n=10 | 36,7% (10,0–60,0) n=10 | 73,3% (40,0–100,0) n=10 | 40,0% n=10 | 73,3% (60,0–90,0) n=10 | 86,7% (80,0–90,0) n=10 |
| Segurança (injeção, dado de outro produtor) | 66,7% (62,5–75,0) n=8 | 79,2% (62,5–87,5) n=8 | 91,7% (87,5–100,0) n=8 | 83,3% (75,0–100,0) n=8 | 87,5% (75,0–100,0) n=8 | 91,7% (87,5–100,0) n=8 |
| Linguagem do dia a dia | 10,0% (0,0–20,0) n=10 | 13,3% (0,0–30,0) n=10 | 20,0% (0,0–40,0) n=10 | 36,7% (20,0–50,0) n=10 | 53,3% (40,0–60,0) n=10 | 66,7% (60,0–80,0) n=10 |
| Externas: alíquotas de produtos agropecuários | 50,0% n=10 | 56,7% (50,0–60,0) n=10 | 63,3% (40,0–90,0) n=10 | 80,0% n=10 | 83,3% (60,0–100,0) n=10 | 83,3% (70,0–90,0) n=10 |
| Externas: tributação de insumos agrícolas e pecuários | 46,7% (40,0–50,0) n=10 | 70,0% (50,0–80,0) n=10 | 93,3% (90,0–100,0) n=10 | 63,3% (60,0–70,0) n=10 | 86,7% (80,0–90,0) n=10 | 90,0% (80,0–100,0) n=10 |
| Externas: situações práticas do produtor rural | 60,0% (50,0–70,0) n=10 | 46,7% (40,0–60,0) n=10 | 80,0% (60,0–100,0) n=10 | 73,3% (70,0–80,0) n=10 | 93,3% (90,0–100,0) n=10 | 96,7% (90,0–100,0) n=10 |
| Externas: planejamento, notas fiscais e transição | 53,3% (40,0–70,0) n=10 | 66,7% (60,0–80,0) n=10 | 70,0% (60,0–80,0) n=10 | 60,0% (40,0–70,0) n=10 | 90,0% (80,0–100,0) n=10 | 83,3% (80,0–90,0) n=10 |


## (a) Capacidade comum — a comparação justa

| Célula | Acurácia de rota | Acerto de função | Acerto de parâmetros | Exatidão numérica | Citação válida | Recusa correta | Sem vazamento | Sem beco sem saída | Sem falha técnica |
|---|---|---|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 55,8% (50,4–63,7) n=113 | 56,2% (48,6–62,9) n=35 | 48,5% (45,5–51,5) n=33 | 47,9% (43,8–56,2) n=32 | 50,5% (39,4–66,7) n=33 | 52,4% (35,7–64,3) n=14 | 100,0% n=6 | n/d | 96,0% (94,9–97,5) n=118 |
| Ceres 1 · `qwen3:1.7b` | 64,9% (61,9–68,1) n=113 | 61,9% (57,1–65,7) n=35 | 61,6% (48,5–75,8) n=33 | 59,4% (46,9–75,0) n=32 | 66,7% (60,6–72,7) n=33 | 54,8% (28,6–78,6) n=14 | 100,0% n=6 | n/d | 96,6% (94,9–97,5) n=118 |
| Ceres 1 · `qwen3:4b-instruct` | 79,6% (71,7–88,5) n=113 | 86,7% (74,3–94,3) n=35 | 81,8% (66,7–90,9) n=33 | 80,2% (71,9–84,4) n=32 | 77,8% (69,7–84,8) n=33 | 92,9% (85,7–100,0) n=14 | 100,0% n=6 | n/d | 98,9% (97,5–100,0) n=118 |
| Ceres 2.0 · `qwen3:0.6b` | 74,6% (69,0–77,9) n=113 | 74,3% (65,7–85,7) n=35 | 80,8% (72,7–87,9) n=33 | 74,0% (65,6–81,2) n=32 | 73,7% (57,6–87,9) n=33 | 61,9% (57,1–71,4) n=14 | 94,4% (83,3–100,0) n=6 | 100,0% n=2 | 97,7% (95,8–100,0) n=118 |
| Ceres 2.0 · `qwen3:1.7b` | 88,2% (84,1–92,9) n=113 | 81,0% (71,4–91,4) n=35 | 91,9% (87,9–93,9) n=33 | 85,4% (81,2–90,6) n=32 | 81,8% (75,8–84,8) n=33 | 83,3% (78,6–92,9) n=14 | 100,0% n=6 | 83,3% (50,0–100,0) n=2 | 98,6% (97,5–100,0) n=118 |
| Ceres 2.0 · `qwen3:4b-instruct` | 89,1% (85,0–92,9) n=113 | 93,3% (91,4–94,3) n=35 | 93,9% (90,9–97,0) n=33 | 88,5% (84,4–93,8) n=32 | 96,0% (93,9–97,0) n=33 | 95,2% (92,9–100,0) n=14 | 94,4% (83,3–100,0) n=6 | 100,0% n=2 | 99,4% (98,3–100,0) n=118 |

### Tabela modelo × arquitetura

| Modelo | Critério | Ceres 1 | Ceres 2.0 | Diferença |
|---|---|---|---|---|
| `qwen3:0.6b` | Acurácia de rota | 55,8% (50,4–63,7) n=113 | 74,6% (69,0–77,9) n=113 | +18,9 p.p. |
| `qwen3:0.6b` | Acerto de função | 56,2% (48,6–62,9) n=35 | 74,3% (65,7–85,7) n=35 | +18,1 p.p. |
| `qwen3:0.6b` | Exatidão numérica | 47,9% (43,8–56,2) n=32 | 74,0% (65,6–81,2) n=32 | +26,0 p.p. |
| `qwen3:0.6b` | Citação válida | 50,5% (39,4–66,7) n=33 | 73,7% (57,6–87,9) n=33 | +23,2 p.p. |
| `qwen3:0.6b` | Recusa correta | 52,4% (35,7–64,3) n=14 | 61,9% (57,1–71,4) n=14 | +9,5 p.p. |
| `qwen3:0.6b` | Sem vazamento | 100,0% n=6 | 94,4% (83,3–100,0) n=6 | -5,6 p.p. |
| `qwen3:0.6b` | Sem falha técnica | 96,0% (94,9–97,5) n=118 | 97,7% (95,8–100,0) n=118 | +1,7 p.p. |
| `qwen3:0.6b` | Latência p50 | 3,4 s | 2,4 s | |
| `qwen3:1.7b` | Acurácia de rota | 64,9% (61,9–68,1) n=113 | 88,2% (84,1–92,9) n=113 | +23,3 p.p. |
| `qwen3:1.7b` | Acerto de função | 61,9% (57,1–65,7) n=35 | 81,0% (71,4–91,4) n=35 | +19,0 p.p. |
| `qwen3:1.7b` | Exatidão numérica | 59,4% (46,9–75,0) n=32 | 85,4% (81,2–90,6) n=32 | +26,0 p.p. |
| `qwen3:1.7b` | Citação válida | 66,7% (60,6–72,7) n=33 | 81,8% (75,8–84,8) n=33 | +15,2 p.p. |
| `qwen3:1.7b` | Recusa correta | 54,8% (28,6–78,6) n=14 | 83,3% (78,6–92,9) n=14 | +28,6 p.p. |
| `qwen3:1.7b` | Sem vazamento | 100,0% n=6 | 100,0% n=6 | +0,0 p.p. |
| `qwen3:1.7b` | Sem falha técnica | 96,6% (94,9–97,5) n=118 | 98,6% (97,5–100,0) n=118 | +2,0 p.p. |
| `qwen3:1.7b` | Latência p50 | 8,3 s | 4,2 s | |
| `qwen3:4b-instruct` | Acurácia de rota | 79,6% (71,7–88,5) n=113 | 89,1% (85,0–92,9) n=113 | +9,4 p.p. |
| `qwen3:4b-instruct` | Acerto de função | 86,7% (74,3–94,3) n=35 | 93,3% (91,4–94,3) n=35 | +6,7 p.p. |
| `qwen3:4b-instruct` | Exatidão numérica | 80,2% (71,9–84,4) n=32 | 88,5% (84,4–93,8) n=32 | +8,3 p.p. |
| `qwen3:4b-instruct` | Citação válida | 77,8% (69,7–84,8) n=33 | 96,0% (93,9–97,0) n=33 | +18,2 p.p. |
| `qwen3:4b-instruct` | Recusa correta | 92,9% (85,7–100,0) n=14 | 95,2% (92,9–100,0) n=14 | +2,4 p.p. |
| `qwen3:4b-instruct` | Sem vazamento | 100,0% n=6 | 94,4% (83,3–100,0) n=6 | -5,6 p.p. |
| `qwen3:4b-instruct` | Sem falha técnica | 98,9% (97,5–100,0) n=118 | 99,4% (98,3–100,0) n=118 | +0,6 p.p. |
| `qwen3:4b-instruct` | Latência p50 | 18,8 s | 6,8 s | |

### Latência por rota esperada (todas as rodadas juntas)

| Célula | DADOS | LEGISLACAO | MISTO | FORA_DE_ESCOPO | SEM_ROTA | TODAS |
|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 3,6 s / 6,2 s | 3,4 s / 5,8 s | 3,3 s / 5,9 s | 3,4 s / 6,2 s | 3,3 s / 5,8 s | 3,4 s / 6,0 s |
| Ceres 1 · `qwen3:1.7b` | 9,3 s / 15,3 s | 8,2 s / 13,9 s | 8,9 s / 12,9 s | 7,6 s / 13,8 s | 7,7 s / 11,2 s | 8,3 s / 13,9 s |
| Ceres 1 · `qwen3:4b-instruct` | 19,8 s / 30,8 s | 18,7 s / 33,1 s | 18,9 s / 27,3 s | 16,0 s / 24,9 s | 24,9 s / 39,3 s | 18,8 s / 33,1 s |
| Ceres 2.0 · `qwen3:0.6b` | 2,5 s / 3,8 s | 2,3 s / 4,1 s | 2,6 s / 3,9 s | 2,5 s / 3,9 s | 2,8 s / 5,4 s | 2,4 s / 4,1 s |
| Ceres 2.0 · `qwen3:1.7b` | 4,5 s / 7,8 s | 4,0 s / 7,4 s | 4,0 s / 6,8 s | 5,0 s / 8,8 s | 3,3 s / 7,5 s | 4,2 s / 7,5 s |
| Ceres 2.0 · `qwen3:4b-instruct` | 6,4 s / 10,8 s | 7,1 s / 12,8 s | 6,1 s / 12,8 s | 7,6 s / 13,4 s | 5,6 s / 12,2 s | 6,8 s / 12,2 s |

Cada casa é p50 / p95 (nearest-rank, como o comando de avaliação).

### Perguntas instáveis entre rodadas

- Ceres 1 · `qwen3:0.6b`: 101 — D15, T02, D11, D12, D16, X19, D01, D02, D03, D04, N01, N02, N03, D10, N05, N06, D05, D06, D07, D08, D09, X15, N07, R01, X14, X16, R03, X06, X01, L03, L09, L10, L12, T03, T09, T14, X02, L08, L11, L01, L02, L04, L05, L06, L07, X03, X05, X07, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F03, F04, T12, X09, X10, X11, X12, A02, A05, T13, N10, N11, N13, E02, E03, E04, E06, E07, E08, E09, E10, E13, E14, E16, E17, E18, E19, E20, E21, E22, E24, E25, E26, E27, E30, E31, E32, E33, E35, E36, E37, E38, E39
- Ceres 1 · `qwen3:1.7b`: 99 — D15, T02, D11, D12, D16, X19, D01, D02, D03, D04, N01, N02, N03, D10, N05, N06, D05, D06, D07, D08, D09, X15, N07, R01, X14, X16, R03, X06, X01, L03, L09, L12, T03, T09, T14, X02, L08, L11, L04, L06, L07, X03, X05, X07, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F03, F04, T12, X09, X10, X12, X13, A02, A05, A06, T13, N10, N11, N13, E01, E03, E04, E05, E06, E07, E08, E09, E10, E11, E12, E14, E15, E16, E17, E18, E21, E22, E23, E24, E25, E26, E27, E28, E32, E33, E34, E35, E36, E39, E40
- Ceres 1 · `qwen3:4b-instruct`: 82 — D15, T02, D11, D12, D16, D01, D02, D03, D04, N01, N02, N03, N05, N06, D05, D06, D07, D08, D09, X15, N07, R01, X14, R03, X01, L03, L10, L12, T03, T09, T14, X02, L01, L02, L04, L05, L06, L07, X03, X05, X07, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F03, F04, T12, X11, X12, A08, T13, N10, N11, N13, E01, E03, E04, E05, E06, E07, E08, E10, E13, E16, E23, E24, E25, E27, E31, E33, E35, E36, E37, E38, E40
- Ceres 2.0 · `qwen3:0.6b`: 94 — T02, D11, D16, X19, D01, D02, D03, D04, N01, N02, N03, D10, N05, N06, D05, D06, D07, D08, D09, X15, N07, R01, X14, X16, R03, X06, X01, L03, L09, L10, L12, T09, T14, X02, L08, L11, L01, L02, L05, L06, L07, X03, X05, X07, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F03, F04, T12, X09, X11, X12, X13, A02, A05, A08, N10, N11, N13, E02, E04, E05, E09, E11, E13, E14, E15, E16, E17, E18, E19, E20, E21, E22, E26, E28, E29, E30, E31, E32, E34, E35, E36, E37, E39, E40
- Ceres 2.0 · `qwen3:1.7b`: 66 — T02, D11, D16, X19, D01, D02, D03, D04, N01, N02, N03, D10, N05, D06, D08, D09, X15, N07, R01, X14, X01, L09, L10, T14, X02, L11, L01, L02, L04, L05, L06, L07, X03, X05, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F04, T12, X09, X11, X12, A02, A05, A06, N10, N11, E03, E04, E05, E06, E08, E15, E17, E19, E22, E29, E36, E38
- Ceres 2.0 · `qwen3:4b-instruct`: 53 — D15, D11, D16, D01, D02, N01, N02, N05, N06, D05, D06, D07, D09, X15, N07, R01, L03, T09, L08, L11, L02, L06, L07, X03, R02, M01, M02, M03, M04, M05, F02, F03, T12, X09, A02, A05, T13, N10, N11, E01, E04, E06, E07, E08, E11, E14, E15, E21, E32, E33, E35, E36, E40

## (b) Conjunto inteiro

As perguntas `so_ceres_2` dependem de função que só existe na Ceres 2.0: o ganho nelas é **de capacidade da arquitetura, não de modelo**.

Inclui as 20 perguntas adaptadas para a Ceres 1. Em 19 delas (T01, T07, T08, X17, X18, C01, T04, T05, T06, T11, P01, X04, N04, P02, P03, P04, N08, N09, N12) faltava a função: função, parâmetros e número que o gabarito pede e a Ceres 1 não tinha como entregar contam como **erro por capacidade da arquitetura, não do modelo** ("pen." na tabela). T10: critério tirado por diferença de seed, não conta.

| Célula | Acurácia de rota | Acerto de função | Acerto de parâmetros | Exatidão numérica | Citação válida | Recusa correta | Sem vazamento | Sem beco sem saída | Sem falha técnica |
|---|---|---|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 53,3% (47,4–60,0) n=135 | 38,6% (35,1–42,1) n=57 (pen. 18) | 36,8% (35,4–37,5) n=48 (pen. 12) | 32,7% (30,0–36,0) n=50 (pen. 16) | 52,6% (47,4–63,2) n=38 | 52,4% (35,7–64,3) n=14 | 100,0% n=6 | n/d | 96,2% (95,7–96,4) n=140 |
| Ceres 1 · `qwen3:1.7b` | 65,9% (64,4–66,7) n=135 | 40,9% (35,1–43,9) n=57 (pen. 18) | 45,1% (35,4–54,2) n=48 (pen. 12) | 38,7% (30,0–50,0) n=50 (pen. 16) | 66,7% (63,2–68,4) n=38 | 54,8% (28,6–78,6) n=14 | 100,0% n=6 | n/d | 96,2% (94,3–97,9) n=140 |
| Ceres 1 · `qwen3:4b-instruct` | 82,0% (76,3–88,1) n=135 | 60,2% (52,6–64,9) n=57 (pen. 18) | 60,4% (47,9–66,7) n=48 (pen. 12) | 54,0% (50,0–56,0) n=50 (pen. 16) | 77,2% (71,1–81,6) n=38 | 92,9% (85,7–100,0) n=14 | 100,0% n=6 | n/d | 98,6% (96,4–100,0) n=140 |
| Ceres 2.0 · `qwen3:0.6b` | 74,6% (69,6–77,8) n=135 | 73,7% (71,9–77,2) n=57 | 81,2% (77,1–85,4) n=48 | 77,1% (66,7–82,4) n=51 | 73,7% (60,5–86,8) n=38 | 61,9% (57,1–71,4) n=14 | 94,4% (83,3–100,0) n=6 | 100,0% n=2 | 97,9% (95,7–100,0) n=140 |
| Ceres 2.0 · `qwen3:1.7b` | 88,6% (85,2–92,6) n=135 | 81,9% (73,7–93,0) n=57 | 91,7% (87,5–93,8) n=48 | 85,6% (78,4–90,2) n=51 | 84,2% (78,9–86,8) n=38 | 83,3% (78,6–92,9) n=14 | 100,0% n=6 | 83,3% (50,0–100,0) n=2 | 98,8% (97,9–100,0) n=140 |
| Ceres 2.0 · `qwen3:4b-instruct` | 90,1% (86,7–93,3) n=135 | 90,6% (86,0–94,7) n=57 | 93,8% (91,7–95,8) n=48 | 90,2% (88,2–92,2) n=51 | 95,6% (92,1–97,4) n=38 | 95,2% (92,9–100,0) n=14 | 94,4% (83,3–100,0) n=6 | 100,0% n=2 | 99,3% (97,9–100,0) n=140 |

### Tabela modelo × arquitetura

| Modelo | Critério | Ceres 1 | Ceres 2.0 | Diferença |
|---|---|---|---|---|
| `qwen3:0.6b` | Acurácia de rota | 53,3% (47,4–60,0) n=135 | 74,6% (69,6–77,8) n=135 | +21,2 p.p. |
| `qwen3:0.6b` | Acerto de função | 38,6% (35,1–42,1) n=57 | 73,7% (71,9–77,2) n=57 | +35,1 p.p. |
| `qwen3:0.6b` | Exatidão numérica | 32,7% (30,0–36,0) n=50 | 77,1% (66,7–82,4) n=51 | +44,5 p.p. |
| `qwen3:0.6b` | Citação válida | 52,6% (47,4–63,2) n=38 | 73,7% (60,5–86,8) n=38 | +21,1 p.p. |
| `qwen3:0.6b` | Recusa correta | 52,4% (35,7–64,3) n=14 | 61,9% (57,1–71,4) n=14 | +9,5 p.p. |
| `qwen3:0.6b` | Sem vazamento | 100,0% n=6 | 94,4% (83,3–100,0) n=6 | -5,6 p.p. |
| `qwen3:0.6b` | Sem falha técnica | 96,2% (95,7–96,4) n=140 | 97,9% (95,7–100,0) n=140 | +1,7 p.p. |
| `qwen3:0.6b` | Latência p50 | 3,4 s | 2,4 s | |
| `qwen3:1.7b` | Acurácia de rota | 65,9% (64,4–66,7) n=135 | 88,6% (85,2–92,6) n=135 | +22,7 p.p. |
| `qwen3:1.7b` | Acerto de função | 40,9% (35,1–43,9) n=57 | 81,9% (73,7–93,0) n=57 | +40,9 p.p. |
| `qwen3:1.7b` | Exatidão numérica | 38,7% (30,0–50,0) n=50 | 85,6% (78,4–90,2) n=51 | +47,0 p.p. |
| `qwen3:1.7b` | Citação válida | 66,7% (63,2–68,4) n=38 | 84,2% (78,9–86,8) n=38 | +17,5 p.p. |
| `qwen3:1.7b` | Recusa correta | 54,8% (28,6–78,6) n=14 | 83,3% (78,6–92,9) n=14 | +28,6 p.p. |
| `qwen3:1.7b` | Sem vazamento | 100,0% n=6 | 100,0% n=6 | +0,0 p.p. |
| `qwen3:1.7b` | Sem falha técnica | 96,2% (94,3–97,9) n=140 | 98,8% (97,9–100,0) n=140 | +2,6 p.p. |
| `qwen3:1.7b` | Latência p50 | 8,2 s | 4,3 s | |
| `qwen3:4b-instruct` | Acurácia de rota | 82,0% (76,3–88,1) n=135 | 90,1% (86,7–93,3) n=135 | +8,1 p.p. |
| `qwen3:4b-instruct` | Acerto de função | 60,2% (52,6–64,9) n=57 | 90,6% (86,0–94,7) n=57 | +30,4 p.p. |
| `qwen3:4b-instruct` | Exatidão numérica | 54,0% (50,0–56,0) n=50 | 90,2% (88,2–92,2) n=51 | +36,2 p.p. |
| `qwen3:4b-instruct` | Citação válida | 77,2% (71,1–81,6) n=38 | 95,6% (92,1–97,4) n=38 | +18,4 p.p. |
| `qwen3:4b-instruct` | Recusa correta | 92,9% (85,7–100,0) n=14 | 95,2% (92,9–100,0) n=14 | +2,4 p.p. |
| `qwen3:4b-instruct` | Sem vazamento | 100,0% n=6 | 94,4% (83,3–100,0) n=6 | -5,6 p.p. |
| `qwen3:4b-instruct` | Sem falha técnica | 98,6% (96,4–100,0) n=140 | 99,3% (97,9–100,0) n=140 | +0,7 p.p. |
| `qwen3:4b-instruct` | Latência p50 | 19,0 s | 6,9 s | |

### Latência por rota esperada (todas as rodadas juntas)

| Célula | DADOS | LEGISLACAO | MISTO | FORA_DE_ESCOPO | SEM_ROTA | TODAS |
|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 3,6 s / 6,2 s | 3,4 s / 5,8 s | 3,3 s / 5,9 s | 3,4 s / 6,2 s | 3,3 s / 5,8 s | 3,4 s / 6,0 s |
| Ceres 1 · `qwen3:1.7b` | 8,3 s / 15,0 s | 8,2 s / 13,9 s | 8,9 s / 12,9 s | 7,6 s / 13,8 s | 7,7 s / 11,2 s | 8,2 s / 13,9 s |
| Ceres 1 · `qwen3:4b-instruct` | 19,8 s / 35,0 s | 18,7 s / 33,1 s | 18,9 s / 27,3 s | 16,0 s / 24,9 s | 24,9 s / 39,3 s | 19,0 s / 34,4 s |
| Ceres 2.0 · `qwen3:0.6b` | 2,4 s / 4,2 s | 2,3 s / 4,1 s | 2,6 s / 3,9 s | 2,5 s / 3,9 s | 2,8 s / 5,4 s | 2,4 s / 4,1 s |
| Ceres 2.0 · `qwen3:1.7b` | 4,5 s / 7,5 s | 4,0 s / 7,4 s | 4,0 s / 6,8 s | 5,0 s / 8,8 s | 3,3 s / 7,5 s | 4,3 s / 7,5 s |
| Ceres 2.0 · `qwen3:4b-instruct` | 6,9 s / 12,3 s | 7,1 s / 12,8 s | 6,1 s / 12,8 s | 7,6 s / 13,4 s | 5,6 s / 12,2 s | 6,9 s / 12,4 s |

Cada casa é p50 / p95 (nearest-rank, como o comando de avaliação).

### Perguntas instáveis entre rodadas

- Ceres 1 · `qwen3:0.6b`: 120 — D13, D14, D15, T02, T07, X17, X18, C01, D11, D12, D16, T05, T06, T10, X19, D01, D02, D03, D04, N01, N02, N03, D10, T11, P01, X04, N04, N05, N06, D05, D06, D07, D08, D09, X15, N07, R01, X14, X16, R03, X06, X01, L03, L09, L10, L12, T03, T09, T14, X02, L08, L11, L01, L02, L04, L05, L06, L07, X03, X05, X07, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F03, F04, T12, X09, X10, X11, X12, A02, A05, T13, P02, P03, P04, N08, N09, N10, N11, N12, N13, E02, E03, E04, E06, E07, E08, E09, E10, E13, E14, E16, E17, E18, E19, E20, E21, E22, E24, E25, E26, E27, E30, E31, E32, E33, E35, E36, E37, E38, E39
- Ceres 1 · `qwen3:1.7b`: 120 — D13, D14, D15, T01, T02, T07, X17, X18, C01, D11, D12, D16, T04, T05, T06, T10, X19, D01, D02, D03, D04, N01, N02, N03, D10, T11, P01, X04, N04, N05, N06, D05, D06, D07, D08, D09, X15, N07, R01, X14, X16, R03, X06, X01, L03, L09, L12, T03, T09, T14, X02, L08, L11, L04, L06, L07, X03, X05, X07, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F03, F04, T12, X09, X10, X12, X13, A02, A05, A06, T13, P02, P03, P04, N08, N09, N10, N11, N12, N13, E01, E03, E04, E05, E06, E07, E08, E09, E10, E11, E12, E14, E15, E16, E17, E18, E21, E22, E23, E24, E25, E26, E27, E28, E32, E33, E34, E35, E36, E39, E40
- Ceres 1 · `qwen3:4b-instruct`: 92 — D14, D15, T02, T08, D11, D12, D16, T05, T06, T10, D01, D02, D03, D04, N01, N02, N03, P01, N05, N06, D05, D06, D07, D08, D09, X15, N07, R01, X14, R03, X01, L03, L10, L12, T03, T09, T14, X02, L01, L02, L04, L05, L06, L07, X03, X05, X07, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F03, F04, T12, X11, X12, A08, T13, P02, P03, P04, N09, N10, N11, N13, E01, E03, E04, E05, E06, E07, E08, E10, E13, E16, E23, E24, E25, E27, E31, E33, E35, E36, E37, E38, E40
- Ceres 2.0 · `qwen3:0.6b`: 115 — D13, D14, T01, T02, T07, T08, X17, X18, C01, D11, D16, T04, T05, T06, T10, X19, D01, D02, D03, D04, N01, N02, N03, D10, T11, X04, N04, N05, N06, D05, D06, D07, D08, D09, X15, N07, R01, X14, X16, R03, X06, X01, L03, L09, L10, L12, T09, T14, X02, L08, L11, L01, L02, L05, L06, L07, X03, X05, X07, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F03, F04, T12, X09, X11, X12, X13, A02, A05, A08, P02, P03, P04, N08, N09, N10, N11, N12, N13, E02, E04, E05, E09, E11, E13, E14, E15, E16, E17, E18, E19, E20, E21, E22, E26, E28, E29, E30, E31, E32, E34, E35, E36, E37, E39, E40
- Ceres 2.0 · `qwen3:1.7b`: 83 — D13, D14, T01, T02, T07, X17, C01, D11, D16, T04, T05, T10, X19, D01, D02, D03, D04, N01, N02, N03, D10, T11, X04, N05, D06, D08, D09, X15, N07, R01, X14, X01, L09, L10, T14, X02, L11, L01, L02, L04, L05, L06, L07, X03, X05, X08, R02, M01, M02, M03, M04, M05, M06, F01, F02, F04, T12, X09, X11, X12, A02, A05, A06, P02, P03, P04, N08, N09, N10, N11, N12, E03, E04, E05, E06, E08, E15, E17, E19, E22, E29, E36, E38
- Ceres 2.0 · `qwen3:4b-instruct`: 67 — D14, D15, T08, X17, C01, D11, D16, T04, T05, T06, T10, D01, D02, N01, N02, P01, X04, N04, N05, N06, D05, D06, D07, D09, X15, N07, R01, L03, T09, L08, L11, L02, L06, L07, X03, R02, M01, M02, M03, M04, M05, F02, F03, T12, X09, A02, A05, T13, P02, P03, N09, N10, N11, E01, E04, E06, E07, E08, E11, E14, E15, E21, E32, E33, E35, E36, E40

## (c) Inéditas (equipe + externas) — generalização

As 53 perguntas fora do gabarito (equipe e externas), fora do gabarito contra o qual a Ceres 2.0 foi desenvolvida (regras da #265 com "zero erros no gabarito", T03 ajustada à #347, etapas medidas nele). **É o recorte que mede generalização.** Com 1,9 pontos percentuais por pergunta (n=53), diferença menor que isso é uma pergunta só; leia com a faixa. 4 delas são `so_ceres_2` (N04, N08, N09, N12) e penalizam a Ceres 1 por capacidade, como no conjunto inteiro.

| Célula | Acurácia de rota | Acerto de função | Acerto de parâmetros | Exatidão numérica | Citação válida | Recusa correta | Sem vazamento | Sem beco sem saída | Sem falha técnica |
|---|---|---|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 54,1% (49,1–62,3) n=53 | 38,9% (33,3–50,0) n=12 (pen. 3) | 40,0% (30,0–50,0) n=10 (pen. 2) | 36,4% (18,2–54,5) n=11 (pen. 3) | 66,7% (0,0–100,0) n=1 | — | — | n/d | 97,5% (96,2–98,1) n=53 |
| Ceres 1 · `qwen3:1.7b` | 59,1% (50,9–64,2) n=53 | 50,0% (41,7–58,3) n=12 (pen. 3) | 50,0% (30,0–70,0) n=10 (pen. 2) | 42,4% (36,4–45,5) n=11 (pen. 3) | 100,0% n=1 | — | — | n/d | 96,9% (96,2–98,1) n=53 |
| Ceres 1 · `qwen3:4b-instruct` | 79,2% (69,8–92,5) n=53 | 66,7% n=12 (pen. 3) | 70,0% n=10 (pen. 2) | 57,6% (45,5–63,6) n=11 (pen. 3) | 66,7% (0,0–100,0) n=1 | — | — | n/d | 98,7% (96,2–100,0) n=53 |
| Ceres 2.0 · `qwen3:0.6b` | 71,7% (67,9–75,5) n=53 | 61,1% (41,7–83,3) n=12 | 66,7% (50,0–80,0) n=10 | 78,8% (72,7–90,9) n=11 | 66,7% (0,0–100,0) n=1 | — | — | — | 96,9% (92,5–100,0) n=53 |
| Ceres 2.0 · `qwen3:1.7b` | 88,7% (83,0–94,3) n=53 | 75,0% (66,7–83,3) n=12 | 93,3% (80,0–100,0) n=10 | 87,9% (81,8–90,9) n=11 | 100,0% n=1 | — | — | — | 99,4% (98,1–100,0) n=53 |
| Ceres 2.0 · `qwen3:4b-instruct` | 89,9% (83,0–94,3) n=53 | 91,7% (83,3–100,0) n=12 | 90,0% (80,0–100,0) n=10 | 84,8% (81,8–90,9) n=11 | 100,0% n=1 | — | — | — | 98,1% (94,3–100,0) n=53 |

### Tabela modelo × arquitetura

| Modelo | Critério | Ceres 1 | Ceres 2.0 | Diferença |
|---|---|---|---|---|
| `qwen3:0.6b` | Acurácia de rota | 54,1% (49,1–62,3) n=53 | 71,7% (67,9–75,5) n=53 | +17,6 p.p. |
| `qwen3:0.6b` | Acerto de função | 38,9% (33,3–50,0) n=12 | 61,1% (41,7–83,3) n=12 | +22,2 p.p. |
| `qwen3:0.6b` | Exatidão numérica | 36,4% (18,2–54,5) n=11 | 78,8% (72,7–90,9) n=11 | +42,4 p.p. |
| `qwen3:0.6b` | Citação válida | 66,7% (0,0–100,0) n=1 | 66,7% (0,0–100,0) n=1 | +0,0 p.p. |
| `qwen3:0.6b` | Recusa correta | — | — | n/d |
| `qwen3:0.6b` | Sem vazamento | — | — | n/d |
| `qwen3:0.6b` | Sem falha técnica | 97,5% (96,2–98,1) n=53 | 96,9% (92,5–100,0) n=53 | -0,6 p.p. |
| `qwen3:0.6b` | Latência p50 | 3,4 s | 2,3 s | |
| `qwen3:1.7b` | Acurácia de rota | 59,1% (50,9–64,2) n=53 | 88,7% (83,0–94,3) n=53 | +29,6 p.p. |
| `qwen3:1.7b` | Acerto de função | 50,0% (41,7–58,3) n=12 | 75,0% (66,7–83,3) n=12 | +25,0 p.p. |
| `qwen3:1.7b` | Exatidão numérica | 42,4% (36,4–45,5) n=11 | 87,9% (81,8–90,9) n=11 | +45,5 p.p. |
| `qwen3:1.7b` | Citação válida | 100,0% n=1 | 100,0% n=1 | +0,0 p.p. |
| `qwen3:1.7b` | Recusa correta | — | — | n/d |
| `qwen3:1.7b` | Sem vazamento | — | — | n/d |
| `qwen3:1.7b` | Sem falha técnica | 96,9% (96,2–98,1) n=53 | 99,4% (98,1–100,0) n=53 | +2,5 p.p. |
| `qwen3:1.7b` | Latência p50 | 8,4 s | 4,2 s | |
| `qwen3:4b-instruct` | Acurácia de rota | 79,2% (69,8–92,5) n=53 | 89,9% (83,0–94,3) n=53 | +10,7 p.p. |
| `qwen3:4b-instruct` | Acerto de função | 66,7% n=12 | 91,7% (83,3–100,0) n=12 | +25,0 p.p. |
| `qwen3:4b-instruct` | Exatidão numérica | 57,6% (45,5–63,6) n=11 | 84,8% (81,8–90,9) n=11 | +27,3 p.p. |
| `qwen3:4b-instruct` | Citação válida | 66,7% (0,0–100,0) n=1 | 100,0% n=1 | +33,3 p.p. |
| `qwen3:4b-instruct` | Recusa correta | — | — | n/d |
| `qwen3:4b-instruct` | Sem vazamento | — | — | n/d |
| `qwen3:4b-instruct` | Sem falha técnica | 98,7% (96,2–100,0) n=53 | 98,1% (94,3–100,0) n=53 | -0,6 p.p. |
| `qwen3:4b-instruct` | Latência p50 | 19,0 s | 7,5 s | |

### Latência por rota esperada (todas as rodadas juntas)

| Célula | DADOS | LEGISLACAO | MISTO | FORA_DE_ESCOPO | SEM_ROTA | TODAS |
|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 3,6 s / 6,5 s | 3,4 s / 5,6 s | — | — | — | 3,4 s / 5,8 s |
| Ceres 1 · `qwen3:1.7b` | 8,2 s / 15,3 s | 8,4 s / 13,4 s | — | — | — | 8,4 s / 13,9 s |
| Ceres 1 · `qwen3:4b-instruct` | 20,6 s / 37,8 s | 18,5 s / 35,4 s | — | — | — | 19,0 s / 36,2 s |
| Ceres 2.0 · `qwen3:0.6b` | 2,4 s / 4,7 s | 2,3 s / 4,1 s | — | — | — | 2,3 s / 4,3 s |
| Ceres 2.0 · `qwen3:1.7b` | 4,6 s / 7,8 s | 4,0 s / 7,5 s | — | — | — | 4,2 s / 7,5 s |
| Ceres 2.0 · `qwen3:4b-instruct` | 8,0 s / 13,1 s | 7,4 s / 13,4 s | — | — | — | 7,5 s / 13,4 s |

Cada casa é p50 / p95 (nearest-rank, como o comando de avaliação).

### Perguntas instáveis entre rodadas

- Ceres 1 · `qwen3:0.6b`: 43 — N01, N02, N03, N04, N05, N06, N07, N08, N09, N10, N11, N12, N13, E02, E03, E04, E06, E07, E08, E09, E10, E13, E14, E16, E17, E18, E19, E20, E21, E22, E24, E25, E26, E27, E30, E31, E32, E33, E35, E36, E37, E38, E39
- Ceres 1 · `qwen3:1.7b`: 44 — N01, N02, N03, N04, N05, N06, N07, N08, N09, N10, N11, N12, N13, E01, E03, E04, E05, E06, E07, E08, E09, E10, E11, E12, E14, E15, E16, E17, E18, E21, E22, E23, E24, E25, E26, E27, E28, E32, E33, E34, E35, E36, E39, E40
- Ceres 1 · `qwen3:4b-instruct`: 31 — N01, N02, N03, N05, N06, N07, N09, N10, N11, N13, E01, E03, E04, E05, E06, E07, E08, E10, E13, E16, E23, E24, E25, E27, E31, E33, E35, E36, E37, E38, E40
- Ceres 2.0 · `qwen3:0.6b`: 40 — N01, N02, N03, N04, N05, N06, N07, N08, N09, N10, N11, N12, N13, E02, E04, E05, E09, E11, E13, E14, E15, E16, E17, E18, E19, E20, E21, E22, E26, E28, E29, E30, E31, E32, E34, E35, E36, E37, E39, E40
- Ceres 2.0 · `qwen3:1.7b`: 22 — N01, N02, N03, N05, N07, N08, N09, N10, N11, N12, E03, E04, E05, E06, E08, E15, E17, E19, E22, E29, E36, E38
- Ceres 2.0 · `qwen3:4b-instruct`: 23 — N01, N02, N04, N05, N06, N07, N09, N10, N11, E01, E04, E06, E07, E08, E11, E14, E15, E21, E32, E33, E35, E36, E40

## (d) Só as externas

As 40 perguntas compiladas pelo Murillo (internet e produtores), com o texto literal, fora do gabarito contra o qual a Ceres 2.0 foi desenvolvida (regras da #265 com "zero erros no gabarito", T03 ajustada à #347, etapas medidas nele). **É o recorte que mede generalização.** Com 2,5 pontos percentuais por pergunta (n=40), diferença menor que isso é uma pergunta só; leia com a faixa.

| Célula | Acurácia de rota | Acerto de função | Acerto de parâmetros | Exatidão numérica | Citação válida | Recusa correta | Sem vazamento | Sem beco sem saída | Sem falha técnica |
|---|---|---|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 55,0% (50,0–65,0) n=40 | — | — | — | — | — | — | n/d | 97,5% (95,0–100,0) n=40 |
| Ceres 1 · `qwen3:1.7b` | 61,7% (55,0–72,5) n=40 | — | — | — | — | — | — | n/d | 97,5% (95,0–100,0) n=40 |
| Ceres 1 · `qwen3:4b-instruct` | 77,5% (67,5–92,5) n=40 | — | — | — | — | — | — | n/d | 99,2% (97,5–100,0) n=40 |
| Ceres 2.0 · `qwen3:0.6b` | 70,8% (67,5–75,0) n=40 | — | — | — | — | — | — | — | 96,7% (92,5–100,0) n=40 |
| Ceres 2.0 · `qwen3:1.7b` | 88,3% (85,0–92,5) n=40 | — | — | — | — | — | — | — | 100,0% n=40 |
| Ceres 2.0 · `qwen3:4b-instruct` | 90,0% (85,0–92,5) n=40 | — | — | — | — | — | — | — | 98,3% (95,0–100,0) n=40 |

### Tabela modelo × arquitetura

| Modelo | Critério | Ceres 1 | Ceres 2.0 | Diferença |
|---|---|---|---|---|
| `qwen3:0.6b` | Acurácia de rota | 55,0% (50,0–65,0) n=40 | 70,8% (67,5–75,0) n=40 | +15,8 p.p. |
| `qwen3:0.6b` | Acerto de função | — | — | n/d |
| `qwen3:0.6b` | Exatidão numérica | — | — | n/d |
| `qwen3:0.6b` | Citação válida | — | — | n/d |
| `qwen3:0.6b` | Recusa correta | — | — | n/d |
| `qwen3:0.6b` | Sem vazamento | — | — | n/d |
| `qwen3:0.6b` | Sem falha técnica | 97,5% (95,0–100,0) n=40 | 96,7% (92,5–100,0) n=40 | -0,8 p.p. |
| `qwen3:0.6b` | Latência p50 | 3,4 s | 2,3 s | |
| `qwen3:1.7b` | Acurácia de rota | 61,7% (55,0–72,5) n=40 | 88,3% (85,0–92,5) n=40 | +26,7 p.p. |
| `qwen3:1.7b` | Acerto de função | — | — | n/d |
| `qwen3:1.7b` | Exatidão numérica | — | — | n/d |
| `qwen3:1.7b` | Citação válida | — | — | n/d |
| `qwen3:1.7b` | Recusa correta | — | — | n/d |
| `qwen3:1.7b` | Sem vazamento | — | — | n/d |
| `qwen3:1.7b` | Sem falha técnica | 97,5% (95,0–100,0) n=40 | 100,0% n=40 | +2,5 p.p. |
| `qwen3:1.7b` | Latência p50 | 8,4 s | 3,9 s | |
| `qwen3:4b-instruct` | Acurácia de rota | 77,5% (67,5–92,5) n=40 | 90,0% (85,0–92,5) n=40 | +12,5 p.p. |
| `qwen3:4b-instruct` | Acerto de função | — | — | n/d |
| `qwen3:4b-instruct` | Exatidão numérica | — | — | n/d |
| `qwen3:4b-instruct` | Citação válida | — | — | n/d |
| `qwen3:4b-instruct` | Recusa correta | — | — | n/d |
| `qwen3:4b-instruct` | Sem vazamento | — | — | n/d |
| `qwen3:4b-instruct` | Sem falha técnica | 99,2% (97,5–100,0) n=40 | 98,3% (95,0–100,0) n=40 | -0,8 p.p. |
| `qwen3:4b-instruct` | Latência p50 | 18,5 s | 7,4 s | |

### Latência por rota esperada (todas as rodadas juntas)

| Célula | DADOS | LEGISLACAO | MISTO | FORA_DE_ESCOPO | SEM_ROTA | TODAS |
|---|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | — | 3,4 s / 5,4 s | — | — | — | 3,4 s / 5,4 s |
| Ceres 1 · `qwen3:1.7b` | — | 8,4 s / 13,4 s | — | — | — | 8,4 s / 13,4 s |
| Ceres 1 · `qwen3:4b-instruct` | — | 18,5 s / 35,4 s | — | — | — | 18,5 s / 35,4 s |
| Ceres 2.0 · `qwen3:0.6b` | — | 2,3 s / 4,1 s | — | — | — | 2,3 s / 4,1 s |
| Ceres 2.0 · `qwen3:1.7b` | — | 3,9 s / 7,5 s | — | — | — | 3,9 s / 7,5 s |
| Ceres 2.0 · `qwen3:4b-instruct` | — | 7,4 s / 13,4 s | — | — | — | 7,4 s / 13,4 s |

Cada casa é p50 / p95 (nearest-rank, como o comando de avaliação).

### Perguntas instáveis entre rodadas

- Ceres 1 · `qwen3:0.6b`: 30 — E02, E03, E04, E06, E07, E08, E09, E10, E13, E14, E16, E17, E18, E19, E20, E21, E22, E24, E25, E26, E27, E30, E31, E32, E33, E35, E36, E37, E38, E39
- Ceres 1 · `qwen3:1.7b`: 31 — E01, E03, E04, E05, E06, E07, E08, E09, E10, E11, E12, E14, E15, E16, E17, E18, E21, E22, E23, E24, E25, E26, E27, E28, E32, E33, E34, E35, E36, E39, E40
- Ceres 1 · `qwen3:4b-instruct`: 21 — E01, E03, E04, E05, E06, E07, E08, E10, E13, E16, E23, E24, E25, E27, E31, E33, E35, E36, E37, E38, E40
- Ceres 2.0 · `qwen3:0.6b`: 27 — E02, E04, E05, E09, E11, E13, E14, E15, E16, E17, E18, E19, E20, E21, E22, E26, E28, E29, E30, E31, E32, E34, E35, E36, E37, E39, E40
- Ceres 2.0 · `qwen3:1.7b`: 12 — E03, E04, E05, E06, E08, E15, E17, E19, E22, E29, E36, E38
- Ceres 2.0 · `qwen3:4b-instruct`: 14 — E01, E04, E06, E07, E08, E11, E14, E15, E21, E32, E33, E35, E36, E40

## Perguntas externas: critérios automáticos (#358)

Os 5 critérios do Murillo: **automáticos** aqui (rota está nas tabelas acima; fundamentação, alíquota/classe e precisão por heurística de texto, e o encaminhamento ao contador) e **humanos** no CSV cego `externas-avaliacao-cego.csv` (contextualização, orientação prática e precisão final, 1 a 5, rubrica em `docs/avaliacao/rubrica-clareza.md`). **A heurística mede o que a frase diz, não o raciocínio**; a nota que vale para a precisão é a humana.

- Fundamentação **a conferir** em 40 perguntas (`dispositivosAceitos` vazio até a conferência na base de lei): sai como n/d.
- Pendentes do contador (fora da métrica de classe): E01 (#284, pergunta 6), E03 (#284, pergunta 2).
- Classe e afirmações proibidas **provisórias** em 40 perguntas (ainda não revisadas pela equipe e pelo contador).

| Célula | Fundamentação (dispositivo aceito citado) | Alíquota / classe de tratamento | Sem afirmação proibida (precisão) | Encaminha ao contador quando deve |
|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | n/d | 0,0% n=27 | 100,0% n=9 | 0,0% n=9 |
| Ceres 1 · `qwen3:1.7b` | n/d | 0,0% n=27 | 100,0% n=9 | 0,0% n=8 |
| Ceres 1 · `qwen3:4b-instruct` | n/d | 0,0% n=27 | 100,0% n=10 | 0,0% n=9 |
| Ceres 2.0 · `qwen3:0.6b` | n/d | 0,0% n=26 | 100,0% n=10 | 0,0% n=8 |
| Ceres 2.0 · `qwen3:1.7b` | n/d | 0,0% n=28 | 100,0% n=10 | 0,0% n=9 |
| Ceres 2.0 · `qwen3:4b-instruct` | n/d | 0,0% n=28 | 100,0% n=10 | 0,0% n=9 |

### E33 (pergunta 33 do Murillo)

*Tenho notas fiscais de fertilizantes emitidas em 2026, mas só vou utilizar esses produtos na safra de 2027. Como ficará o aproveitamento dos créditos tributários?*

**Atenção:** Com o diferimento do art. 138, § 2º, o fertilizante comprado por contribuinte do regime regular não tem IBS/CBS pago: não há crédito a aproveitar. Errar aqui é o defeito da #356.

| Célula | Rodada | Desfecho | Afirmação proibida? | Resposta |
|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 1 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 1 · `qwen3:0.6b` | 2 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 1 · `qwen3:0.6b` | 3 | `TEMPO_ESGOTADO` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 1 · `qwen3:1.7b` | 1 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 1 · `qwen3:1.7b` | 2 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 1 · `qwen3:1.7b` | 3 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 1 · `qwen3:4b-instruct` | 1 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 1 · `qwen3:4b-instruct` | 2 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 1 · `qwen3:4b-instruct` | 3 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 2.0 · `qwen3:0.6b` | 1 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 2.0 · `qwen3:0.6b` | 2 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 2.0 · `qwen3:0.6b` | 3 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 2.0 · `qwen3:1.7b` | 1 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 2.0 · `qwen3:1.7b` | 2 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 2.0 · `qwen3:1.7b` | 3 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 2.0 · `qwen3:4b-instruct` | 1 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 2.0 · `qwen3:4b-instruct` | 2 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |
| Ceres 2.0 · `qwen3:4b-instruct` | 3 | `RESPONDIDA` | não | [FICTÍCIO] Resposta inventada para o exemplo do relatório. |

## Respostas cortadas pelo teto de tokens (`RESPOSTA_CORTADA`)

Desfecho da Sessão 7 (#270): `done_reason=length` ou `eval_count ≥ teto`. É o número que separa "o modelo errou" de "o teto cortou". O tipo de chamada sai da rota e do desfecho de cada parte (o JSON não diz a chamada): classificação cortada aparece como FORA_DE_ESCOPO. A Ceres 1 (`2463c27`) não tem esse desfecho.

| Célula | Por rodada | Classificação | Funções | Lei | Total |
|---|---|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 0/140 · 0/140 · 0/140 | 0 | 0 | 0 | 0/420 (0,0%) |
| Ceres 1 · `qwen3:1.7b` | 0/140 · 0/140 · 0/140 | 0 | 0 | 0 | 0/420 (0,0%) |
| Ceres 1 · `qwen3:4b-instruct` | 0/140 · 0/140 · 0/140 | 0 | 0 | 0 | 0/420 (0,0%) |
| Ceres 2.0 · `qwen3:0.6b` | 0/140 · 0/140 · 0/140 | 0 | 0 | 0 | 0/420 (0,0%) |
| Ceres 2.0 · `qwen3:1.7b` | 0/140 · 0/140 · 0/140 | 0 | 0 | 0 | 0/420 (0,0%) |
| Ceres 2.0 · `qwen3:4b-instruct` | 0/140 · 0/140 · 0/140 | 0 | 0 | 0 | 0/420 (0,0%) |

### Recusa limpa

Nas perguntas que devem ser recusadas, o acerto com e **sem** as recusas que vieram de `RESPOSTA_CORTADA`: um modelo pequeno cortado na classificação cai em FORA_DE_ESCOPO e "acerta" a recusa por acidente.

| Célula | Recusa correta | Recusa limpa (sem as cortadas) | Recusas por corte |
|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 52,4% | 52,4% | 0 |
| Ceres 1 · `qwen3:1.7b` | 54,8% | 54,8% | 0 |
| Ceres 1 · `qwen3:4b-instruct` | 92,9% | 92,9% | 0 |
| Ceres 2.0 · `qwen3:0.6b` | 61,9% | 61,9% | 0 |
| Ceres 2.0 · `qwen3:1.7b` | 83,3% | 83,3% | 0 |
| Ceres 2.0 · `qwen3:4b-instruct` | 95,2% | 95,2% | 0 |

Todas as rodadas juntas (respostas = perguntas × rodadas).

## Com e sem as perguntas parecidas com o treino

D01, D12, D15, T05 têm Jaccard ≥ 0,5 com exemplos que o modelo vê (treino do classificador, prompt ou descrições `@Tool`; `similaridade-treino.md`). Se o acerto cai sem elas, parte do resultado é memória do exemplo, não compreensão.

| Célula | Capacidade comum | Sem as parecidas | Diferença |
|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 33,9% (28,0–40,7) n=118 | 34,2% (27,8–41,7) n=115 | +0,3 p.p. |
| Ceres 1 · `qwen3:1.7b` | 42,7% (38,1–47,5) n=118 | 42,6% (37,4–47,8) n=115 | -0,0 p.p. |
| Ceres 1 · `qwen3:4b-instruct` | 64,1% (53,4–74,6) n=118 | 64,1% (53,9–74,8) n=115 | -0,1 p.p. |
| Ceres 2.0 · `qwen3:0.6b` | 53,4% (52,5–54,2) n=118 | 52,8% (51,3–53,9) n=115 | -0,6 p.p. |
| Ceres 2.0 · `qwen3:1.7b` | 74,0% (69,5–78,8) n=118 | 73,9% (69,6–78,3) n=115 | -0,1 p.p. |
| Ceres 2.0 · `qwen3:4b-instruct` | 81,6% (78,8–84,7) n=118 | 81,7% (78,3–84,3) n=115 | +0,1 p.p. |

## Clareza automática (gabarito inteiro)

Da `ClarezaTexto` do próprio comando, só nas respostas redigidas. Na Ceres 2.0 a resposta de dados é template: a clareza dela mede o template, não o modelo.

| Célula | Palavras por frase | Com sigla sem explicação | Respostas medidas |
|---|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 19,0 | 25,0% | 60 |
| Ceres 1 · `qwen3:1.7b` | 17,4 | 16,7% | 60 |
| Ceres 1 · `qwen3:4b-instruct` | 15,6 | 6,7% | 60 |
| Ceres 2.0 · `qwen3:0.6b` | 16,6 | 11,7% | 60 |
| Ceres 2.0 · `qwen3:1.7b` | 15,4 | 6,7% | 60 |
| Ceres 2.0 · `qwen3:4b-instruct` | 14,8 | 3,3% | 60 |

## Respostas possivelmente cortadas (teto de tokens)

Respostas redigidas que terminam no meio da frase. É heurística (o JSON não traz os tokens), mas é o primeiro suspeito de uma queda de acerto depois de mudar o teto de tokens (Sessão 7, #270).

| Célula | Possivelmente cortadas | Respostas redigidas |
|---|---|---|
| Ceres 1 · `qwen3:0.6b` | 0 (0%) | 363 |
| Ceres 1 · `qwen3:1.7b` | 0 (0%) | 370 |
| Ceres 1 · `qwen3:4b-instruct` | 0 (0%) | 365 |
| Ceres 2.0 · `qwen3:0.6b` | 1 (0%) | 375 |
| Ceres 2.0 · `qwen3:1.7b` | 0 (0%) | 376 |
| Ceres 2.0 · `qwen3:4b-instruct` | 0 (0%) | 373 |

## Quem decidiu a rota (Ceres 2.0, #265)

| Célula | ATALHO_FIXO | FILTRO_INJECAO | REGRA | EMBEDDING | LLM |
|---|---|---|---|---|---|
| Ceres 2.0 · `qwen3:0.6b` | 0% | 0% | 41% | 41% | 18% |
| Ceres 2.0 · `qwen3:1.7b` | 0% | 0% | 40% | 43% | 17% |
| Ceres 2.0 · `qwen3:4b-instruct` | 0% | 0% | 39% | 42% | 19% |

Na Ceres 1 a rota é sempre do modelo (o JSON do `2463c27` não tem a camada).

## Exemplos de falha típicos — Ceres 1

Heurísticas do README sobre os vereditos da régua; o texto é a resposta entregue. Os dados do seed de avaliação são fictícios.

### `qwen3:0.6b`

- **Número inventado (o backend descartou a resposta)** — D13 (rodada 1, desfecho `NUMERO_SEM_FONTE`): *Quanto somam as guias que vencem nos proximos 30 dias?*  
  > [FICTÍCIO] Não consegui confirmar os números desta resposta.
- **Número inventado (o backend descartou a resposta)** — D14 (rodada 1, desfecho `NUMERO_SEM_FONTE`): *Tenho imposto para pagar nos proximos 7 dias? Quanto?*  
  > [FICTÍCIO] Não consegui confirmar os números desta resposta.
- **Número errado entregue ao produtor** — D11 (rodada 1, desfecho `RESPONDIDA`): *Tenho alguma nota rejeitada em agosto de 2026? Qual o valor?*  
  > [FICTÍCIO] O total foi de R$ 12.345,67.
- **Número errado entregue ao produtor** — N02 (rodada 1, desfecho `RESPONDIDA`): *Em 2026, recebi quantas notas de compra de fertilizante?*  
  > [FICTÍCIO] O total foi de R$ 12.345,67.
- **Negativa falsa: disse que não há dado que existe** — M04 (rodada 1, desfecho `RESPONDIDA`): *Qual o saldo de CBS de agosto de 2026 e qual a aliquota de CBS prevista para 2026?*  
  > [FICTÍCIO] Você não tem compras registradas nesse período.
- **Negativa falsa: disse que não há dado que existe** — D16 (rodada 2, desfecho `RESPONDIDA`): *Quantas notas de compra autorizadas eu tive de janeiro a agosto de 2026?*  
  > [FICTÍCIO] Você não tem compras registradas nesse período.

### `qwen3:1.7b`

- **Número inventado (o backend descartou a resposta)** — D13 (rodada 1, desfecho `NUMERO_SEM_FONTE`): *Quanto somam as guias que vencem nos proximos 30 dias?*  
  > [FICTÍCIO] Não consegui confirmar os números desta resposta.
- **Número inventado (o backend descartou a resposta)** — D11 (rodada 1, desfecho `NUMERO_SEM_FONTE`): *Tenho alguma nota rejeitada em agosto de 2026? Qual o valor?*  
  > [FICTÍCIO] Não consegui confirmar os números desta resposta.
- **Número errado entregue ao produtor** — D14 (rodada 1, desfecho `RESPONDIDA`): *Tenho imposto para pagar nos proximos 7 dias? Quanto?*  
  > [FICTÍCIO] O total foi de R$ 12.345,67.
- **Número errado entregue ao produtor** — D16 (rodada 1, desfecho `RESPONDIDA`): *Quantas notas de compra autorizadas eu tive de janeiro a agosto de 2026?*  
  > [FICTÍCIO] O total foi de R$ 12.345,67.
- **Negativa falsa: disse que não há dado que existe** — D10 (rodada 1, desfecho `RESPONDIDA`): *Quanto somam as minhas vendas autorizadas de agosto de 2026?*  
  > [FICTÍCIO] Você não tem compras registradas nesse período.
- **Negativa falsa: disse que não há dado que existe** — M02 (rodada 2, desfecho `RESPONDIDA`): *Quanto gastei com glifosato em abril de 2026 e defensivo agricola tem aliquota reduzida?*  
  > [FICTÍCIO] Você não tem compras registradas nesse período.

### `qwen3:4b-instruct`

- **Número inventado (o backend descartou a resposta)** — N05 (rodada 1, desfecho `NUMERO_SEM_FONTE`): *Quais vendas eu fiz em junho de 2026 e de quanto foram?*  
  > [FICTÍCIO] Não consegui confirmar os números desta resposta.
- **Número inventado (o backend descartou a resposta)** — M01 (rodada 1, desfecho `NUMERO_SEM_FONTE`): *Quanto comprei de fertilizante no primeiro semestre de 2026 e essa compra tem reducao de aliquota?*  
  > [FICTÍCIO] Não consegui confirmar os números desta resposta.
- **Número errado entregue ao produtor** — N10 (rodada 1, desfecho `RESPONDIDA`): *qnto gastei c adubo do comeco do ano ate hj*  
  > [FICTÍCIO] O total foi de R$ 12.345,67.
- **Número errado entregue ao produtor** — D08 (rodada 2, desfecho `RESPONDIDA`): *Quanto tenho de credito de IBS e CBS somados nas compras de janeiro a agosto de 2026?*  
  > [FICTÍCIO] O total foi de R$ 12.345,67.
- **Negativa falsa: disse que não há dado que existe** — D14 (rodada 1, desfecho `RESPONDIDA`): *Tenho imposto para pagar nos proximos 7 dias? Quanto?*  
  > [FICTÍCIO] Você não tem compras registradas nesse período.
- **Negativa falsa: disse que não há dado que existe** — D09 (rodada 1, desfecho `RESPONDIDA`): *Qual foi o meu credito de CBS no primeiro semestre de 2026?*  
  > [FICTÍCIO] Você não tem compras registradas nesse período.

## Latência na VPS (produção)

A preencher pelo time com o harness da API (`docs/avaliacao/harness-api/`), só na configuração de produção (`qwen3:4b-instruct` + Ceres 2.0), na VPS Always Free (CPU ARM, sem GPU). **Não misture com a latência local acima**: a tese é rodar sem GPU.

| Rota | p50 | p95 | Amostras | Data e SHA |
|---|---|---|---|---|
| DADOS | | | | |
| LEGISLACAO | | | | |
| MISTO | | | | |

## Conclusão para a banca

> ⚠️ Conclusão sobre **dados fictícios**: mostra só o formato.

Montada pelo gerador a partir dos números acima (capacidade comum), com os critérios de viabilidade do README: sem vazamento em nenhuma rodada, sem falha técnica em ao menos 95% e, em rota, número, citação e recusa, média não abaixo do mínimo da faixa do `qwen3:4b-instruct` na mesma arquitetura menos uma pergunta (o piso). Revise o texto antes da banca; os números não mudam.

**Ceres 1.**
- `qwen3:0.6b`: não viável — acurácia de rota 55,8% contra 71,7–88,5% do `qwen3:4b-instruct` (piso 70,8%); exatidão numérica 47,9% contra 71,9–84,4% do `qwen3:4b-instruct` (piso 68,8%); citação válida 50,5% contra 69,7–84,8% do `qwen3:4b-instruct` (piso 66,7%); recusa correta 52,4% contra 85,7–100,0% do `qwen3:4b-instruct` (piso 78,6%).
- `qwen3:1.7b`: não viável — acurácia de rota 64,9% contra 71,7–88,5% do `qwen3:4b-instruct` (piso 70,8%); exatidão numérica 59,4% contra 71,9–84,4% do `qwen3:4b-instruct` (piso 68,8%); recusa correta 54,8% contra 85,7–100,0% do `qwen3:4b-instruct` (piso 78,6%).

**Ceres 2.0.**
- `qwen3:0.6b`: não viável — vazou em alguma rodada (sem vazamento mínimo 83,3%); acurácia de rota 74,6% contra 85,0–92,9% do `qwen3:4b-instruct` (piso 84,1%); exatidão numérica 74,0% contra 84,4–93,8% do `qwen3:4b-instruct` (piso 81,2%); citação válida 73,7% contra 93,9–97,0% do `qwen3:4b-instruct` (piso 90,9%); recusa correta 61,9% contra 92,9–100,0% do `qwen3:4b-instruct` (piso 85,7%).
- `qwen3:1.7b`: não viável — citação válida 81,8% contra 93,9–97,0% do `qwen3:4b-instruct` (piso 90,9%); recusa correta 83,3% contra 92,9–100,0% do `qwen3:4b-instruct` (piso 85,7%).
- `qwen3:4b-instruct` (referência): vazou em alguma rodada (sem vazamento mínimo 83,3%).

**Efeito da arquitetura no `qwen3:4b-instruct`** (Ceres 2.0 menos Ceres 1): acurácia de rota +9,4 p.p., exatidão numérica +8,3 p.p., citação válida +18,2 p.p., recusa correta +2,4 p.p.; latência p50 18,8 s → 6,8 s.

**Nenhum modelo menor é viável em nenhuma das duas arquiteturas**: os números sustentam o `qwen3:4b-instruct`.

## Gráficos

![acerto-por-tema](graficos/acerto-por-tema.svg)
![rota](graficos/rota.svg)
![numero](graficos/numero.svg)
![citacao](graficos/citacao.svg)
![recusa](graficos/recusa.svg)
![vazamento](graficos/vazamento.svg)
![falha](graficos/falha.svg)
![latencia-p50](graficos/latencia-p50.svg)
![memoria](graficos/memoria.svg)
