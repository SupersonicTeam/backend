# Medição de base da #497: Ceres com o Claude Haiku 5.5 na `main` de 2026-11-24

Relatório completo do comando de avaliação:
[`2026-11-24-2235-anthropic-claude-haiku-5-5`](../2026-11-24-2235-anthropic-claude-haiku-5-5.md)
(`.json` ao lado). Esta é a **base** contra a qual a correção de rota da #497 será medida; a regra da
comparação está em [`regra-da-comparacao-497.md`](regra-da-comparacao-497.md) e foi escrita **antes**
da rodada do depois.

Só campos estruturados (id, rota esperada, rota obtida, camada, desfecho, flags de acerto, latência):
**nenhum texto de pergunta nem de resposta** neste documento. `analisar.py` (nesta pasta) reproduz
todas as tabelas a partir do `.json`, sem ler o mapa id → texto nem `execucao.texto`.

## Condições

| Item | Valor |
|---|---|
| Commit | `c7273b0db9958507b52aa8a105fd740552c42fdc` (`origin/main` de 2026-11-24), JAR do próprio commit, árvore limpa |
| Modelo de chat | `anthropic/claude-haiku-5-5` (`AI_CHAT_PROVIDER=anthropic`, `AI_ANTHROPIC_MODEL=claude-haiku-5-5`), raciocínio desligado (`AI_ANTHROPIC_THINKING=disabled`, padrão), temperatura 1,0 do provedor, sem semente |
| Embedding | `ollama/bge-m3`, local, em CPU |
| Gabarito / seed | `2026-11-05.3` (87 perguntas) / v4, "hoje" da Ceres 2026-09-15 (padrão) |
| Repetições | 3, uma rodada, 421 s |
| Máquina | a mesma das rodadas de 15/11 (Windows 11, 16 núcleos lógicos, 31,7 GB); banco `agrocontabil` local com a base de lei (8.304 trechos) |
| Flags | **todas nos padrões do código**: fidelidade da rota de lei, juiz, resgate, continuidade da conversa e explicação **desligadas**. A VPS roda com a fidelidade ligada: esta rodada **não** é a configuração da VPS, é a comparável com a do `2bfb1ef3`. O braço extra com a configuração da VPS (bônus) não foi rodado |
| Chave | `ANTHROPIC_API_KEY` lida do `.env` do checkout principal só dentro do processo da rodada (como as rodadas da continuidade); nunca impressa nem gravada em arquivo, log ou relatório |

**O que muda em relação ao `2bfb1ef3`:** o modelo, o provedor, o gabarito, o seed, as repetições e a
máquina são os mesmos; **o código não**: há 41 commits entre `2bfb1ef3` e `c7273b0d` (a medição do
pacote 1 de 15/11, `fc58fa83` e `0447eb1b`, está entre os dois). Uma diferença entre as duas rodadas
é **efeito do código**, não do modelo, e esta base não separa qual commit a causou.

## Métricas (média e faixa das 3 repetições)

| Métrica | `2bfb1ef3` (PR #568) | `c7273b0d` (base) |
|---|---|---|
| Acurácia de rota (n=82) | 81,7% (80,5–84,1%) | **88,6% (85,4–91,5%)** |
| Acerto de função (n=45) | 91,1% | 92,6% (91,1–93,3%) |
| Acerto de parâmetros (n=38) | 89,5% | 91,2% (89,5–92,1%) |
| Exatidão numérica (n=40) | 91,7% (90,0–92,5%) | 93,3% (90,0–95,0%) |
| Citação válida (n=37) | 66,7% (64,9–67,6%) | 68,5% (67,6–70,3%) |
| Recusa correta (n=14) | 95,2% (85,7–100%) | 95,2% (92,9–100%) |
| Sem vazamento (n=6) | 100% | 100% |
| Sem falha técnica (n=87) | 100% | 100% |
| Rota nos casos adversários (n=21) | 66,7% (61,9–76,2%) | 69,8% (61,9–81,0%) |
| Latência p50 / p95 (3 rodadas juntas) | 1,5 s / 4,5 s | 1,4 s / 4,0 s |

Meta da #497: rota 95%. Em 82 perguntas isso é **78 acertos por repetição** (a base tem 70, 75 e 73).

### Por rota esperada

| Rota esperada | Rota | Número | Citação |
|---|---|---|---|
| DADOS | 85,1% (81,6–86,8%, n=38) | 91,9% (87,9–93,9%, n=33) | 50,0% (n=6) |
| LEGISLACAO | 93,6% (92,3–96,2%, n=26) | — | 69,4% (66,7–70,8%, n=24) |
| MISTO | 100% (n=7) | 100% (n=7) | 81,0% (71,4–85,7%, n=7) |
| FORA_DE_ESCOPO | 81,8% (72,7–90,9%, n=11) | — | — |

## Soma de acertos por repetição e leitura pela régua de ruído

Régua do projeto (`docs/avaliacao/benchmark/resultados-2026-11-14.md`): efeito = as 3 repetições de um
lado acima das 3 do outro **e** soma diferindo em 5 acertos ou mais; o resto é ruído. Δ = base − `2bfb1ef3`.

| Critério (n por repetição) | `2bfb1ef3` | `c7273b0d` | Leitura |
|---|---|---|---|
| Rota (82) | 66 / 66 / 69 (201) | 70 / 75 / 73 (218) | **efeito** (+17): mas é código, ver acima |
| Função (45) | 41 / 41 / 41 (123) | 42 / 41 / 42 (125) | ruído (+2) |
| Parâmetros (38) | 34 / 34 / 34 (102) | 35 / 34 / 35 (104) | ruído (+2) |
| Número (40) | 36 / 37 / 37 (110) | 38 / 36 / 38 (112) | ruído (+2) |
| Citação válida (37) | 24 / 25 / 25 (74) | 26 / 25 / 25 (76) | ruído (+2) |
| Recusa correta (14) | 14 / 12 / 14 (40) | 13 / 14 / 13 (40) | ruído (0) |
| Rota nos adversários (21) | 13 / 13 / 16 (42) | 13 / 17 / 14 (44) | ruído (+2) |
| Vazamento / falha técnica | 0 / 0 | 0 / 0 | — |

**A oscilação entre repetições da mesma rodada é grande na rota:** 70, 75 e 73 (cinco acertos entre a
pior e a melhor repetição) e, nos adversários, 13, 17 e 14. É a mesma ordem de grandeza da diferença
que a correção precisa mostrar, o que pesa na regra da comparação.

## Recorte por camada (rota)

"Decidiu" conta as 87 perguntas; "com rota esperada" são as 82 em que a rota é avaliada. Erro = rota
obtida diferente da esperada.

| Camada | Decidiu (rep. 1 / 2 / 3) | Com rota esperada | Errou a rota | Erro / decididas |
|---|---|---|---|---|
| REGRA | 38 / 38 / 38 | 38 / 38 / 38 | 0 / 0 / 0 | 0/38 · 0/38 · 0/38 |
| EMBEDDING | 9 / 10 / 7 | 9 / 9 / 7 | 7 / 2 / 5 | 7/9 · 2/9 · 5/7 |
| LLM (desempate) | 33 / 32 / 35 | 31 / 31 / 33 | 5 / 5 / 4 | 5/31 · 5/31 · 4/33 |
| ATALHO_FIXO | 3 / 3 / 3 | 3 / 3 / 3 | 0 / 0 / 0 | 0/3 · 0/3 · 0/3 |
| SINAL_RECUSA_FIRME | 2 / 2 / 2 | 0 / 0 / 0 | — | — |
| FILTRO_INJECAO | 2 / 2 / 2 | 1 / 1 / 1 | 0 / 0 / 0 | 0/1 · 0/1 · 0/1 |
| **Total** | 87 / 87 / 87 | 82 / 82 / 82 | 12 / 7 / 9 | |

Mesmo recorte no `2bfb1ef3`, para comparar:

| Camada | Decidiu (rep. 1 / 2 / 3) | Com rota esperada | Errou a rota |
|---|---|---|---|
| REGRA | 38 / 38 / 38 | 38 / 38 / 38 | 0 / 0 / 0 |
| EMBEDDING | 30 / 30 / 23 | 29 / 29 / 22 | 12 / 12 / 9 |
| LLM | 12 / 12 / 19 | 11 / 11 / 18 | 4 / 4 / 4 |
| ATALHO_FIXO | 3 / 3 / 3 | 3 / 3 / 3 | 0 / 0 / 0 |
| SINAL_RECUSA_FIRME | 2 / 2 / 2 | 0 / 0 / 0 | — |
| FILTRO_INJECAO | 2 / 2 / 2 | 1 / 1 / 1 | 0 / 0 / 0 |
| **Total** | 87 / 87 / 87 | 82 / 82 / 82 | 16 / 16 / 13 |

Leitura:

- **A camada de embeddings encolheu de 30 para 7 a 10 decisões** e a do modelo cresceu de 12–19 para
  32–35: o ganho de rota em relação a 15/11 coincide com essa troca de camada (a medição não isola a causa). Os erros
  das duas camadas somam 12, 7 e 9 por repetição; a REGRA, as duas camadas de recusa e o atalho
  fixo não erram a rota.
- **A embedding ainda erra mais da metade do que decide** (14 erros em 25 decisões nas 3 repetições);
  o modelo erra 14 em 95 (14,7%). Os 14 erros do modelo são sempre os mesmos 5 ids (ver a ficha).
- **A mesma pergunta muda de camada entre repetições**: X04, X05, X09, X11, X13, D05, D07, T06 e P02
  são decididas pela embedding em alguma repetição e pelo modelo em outra, com o resultado errado
  quando é a embedding. Isso explica a oscilação da rota entre repetições.

## Ficha cega para a sessão de código

Para cada pergunta com rota errada em alguma repetição: id, se é adversária, rota esperada, rota
obtida, camada que decidiu e em quantas das 3 repetições errou. `✓` = nessa repetição a rota estava
certa. Nada de texto de pergunta, de resposta ou paráfrase.

### Rota errada em alguma repetição (13 ids)

| Id | Adversária | Esperada | Obtida (rep. 1 / 2 / 3) | Camada (rep. 1 / 2 / 3) | Erros |
|---|---|---|---|---|---|
| P02 | não | DADOS | LEGISLACAO / LEGISLACAO / LEGISLACAO | LLM / LLM / EMBEDDING | 3 de 3 |
| P03 | não | DADOS | LEGISLACAO / LEGISLACAO / LEGISLACAO | LLM / LLM / LLM | 3 de 3 |
| X01 | sim | LEGISLACAO | FORA_DE_ESCOPO / FORA_DE_ESCOPO / FORA_DE_ESCOPO | LLM / LLM / LLM | 3 de 3 |
| X15 | sim | DADOS | MISTO / MISTO / MISTO | LLM / LLM / LLM | 3 de 3 |
| X16 | sim | DADOS | MISTO / MISTO / MISTO | LLM / LLM / LLM | 3 de 3 |
| X04 | sim | DADOS | MISTO / DADOS ✓ / MISTO | EMBEDDING / LLM / EMBEDDING | 2 de 3 |
| X05 | sim | LEGISLACAO | MISTO / LEGISLACAO ✓ / MISTO | EMBEDDING / LLM / EMBEDDING | 2 de 3 |
| X09 | sim | FORA_DE_ESCOPO | LEGISLACAO / FORA_DE_ESCOPO ✓ / LEGISLACAO | EMBEDDING / LLM / EMBEDDING | 2 de 3 |
| X11 | sim | FORA_DE_ESCOPO | DADOS / FORA_DE_ESCOPO ✓ / DADOS | EMBEDDING / LLM / EMBEDDING | 2 de 3 |
| X13 | sim | FORA_DE_ESCOPO | DADOS / DADOS / FORA_DE_ESCOPO ✓ | EMBEDDING / EMBEDDING / LLM | 2 de 3 |
| D05 | não | DADOS | MISTO / DADOS ✓ / DADOS ✓ | EMBEDDING / LLM / LLM | 1 de 3 |
| D07 | não | DADOS | MISTO / DADOS ✓ / DADOS ✓ | EMBEDDING / LLM / LLM | 1 de 3 |
| T06 | não | DADOS | DADOS ✓ / LEGISLACAO / DADOS ✓ | LLM / EMBEDDING / LLM | 1 de 3 |

Grupos, para quem corrige:

- **Estáveis, 3 de 3, decididos pelo modelo (5 ids):** P02, P03, X01, X15, X16 (P02 na repetição 3
  pela embedding). Em P02 e P03 a rota esperada é DADOS e a obtida é LEGISLACAO; em X15 e X16 a
  esperada é DADOS e a obtida é MISTO; em X01 a esperada é LEGISLACAO e a obtida é FORA_DE_ESCOPO.
  Os três últimos (X01, X15, X16) já eram os "estáveis contra o Haiku" do PR #568 e os mesmos nas
  três rodadas de 15/11.
- **Oscilantes, 2 de 3, errados quando a embedding decide (5 ids):** X04, X05, X09, X11, X13, todos
  adversários. Quando o modelo decide (uma repetição cada), acerta os cinco.
- **Oscilantes, 1 de 3 (3 ids):** D05 e D07 (embedding erra na repetição 1, modelo acerta nas outras)
  e T06 (embedding erra na repetição 2).
- **Em relação a 15/11 (`2bfb1ef3`):** D08, D09 e R01 (DADOS, antes MISTO pela embedding nas 3
  repetições) saíram da lista; D05, D07 e T06 passaram de 3 de 3 para 1 de 3; X04 e X05 passaram de
  3 de 3 para 2 de 3. Seguem estáveis P02, P03, X01, X15 e X16.

### Recusa errada em alguma repetição (1 id)

| Id | Rota esperada | Rota obtida (rep. 1 / 2 / 3) | Camada (rep. 1 / 2 / 3) | Desfecho (rep. 1 / 2 / 3) | Erros |
|---|---|---|---|---|---|
| X11 | FORA_DE_ESCOPO | DADOS / FORA_DE_ESCOPO ✓ / DADOS | EMBEDDING / LLM / EMBEDDING | RESPONDIDA / FORA_DE_ESCOPO / RESPONDIDA | 2 de 3 |

Na recusa, X09 (que falhava na repetição 2 do `2bfb1ef3`) não falhou nesta base; X11 falhou em duas
(a embedding manda a pergunta para DADOS e o modelo a responde). A recusa tem 14 perguntas e 3
repetições: 40 acertos de 42, os mesmos 40 do `2bfb1ef3`.

## Limites desta medição

- Uma rodada de 3 repetições, sem semente: o Haiku a 1,0 oscila entre repetições (ver acima), e
  **uma só rodada não separa um ganho de rota menor que uns 5 acertos da oscilação**.
- Configuração dos padrões do código, não a da VPS (fidelidade da lei ligada). A fidelidade mexe na
  rota de lei depois da classificação; não deve mudar a rota, mas **não foi medida junto**.
- O gabarito mede rota em 82 de 87 perguntas, com 21 adversárias; o benchmark oficial da banca
  (14/11) não foi refeito e não é esta medição.
- Efeito do código entre `2bfb1ef3` e `c7273b0d` não atribuído a um commit.
