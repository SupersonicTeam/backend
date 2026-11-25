# Conjunto de desenvolvimento do classificador de rota (#497)

`perguntas-rota-dev.json` tem 130 perguntas com a rota esperada (DADOS, LEGISLACAO, MISTO,
FORA_DE_ESCOPO), escritas **às cegas em 2026-11-24** pela sessão de código da #497: quem as escreveu
nunca leu o gabarito da avaliação, o benchmark da banca nem o conjunto de treino do classificador.
Os exemplos de treino foram lidos só depois de o conjunto estar escrito.

- **É conjunto de desenvolvimento.** Pode ser lido por sessão de código e serve para achar os padrões
  de erro do classificador em camadas e conferir a correção.
- **Não é teste da banca.** O número que vale é o da medição no gabarito, feita por outra sessão.
  Acerto aqui não prova acerto lá: o mesmo autor escreveu as perguntas e a correção.
- **Nunca entra em pasta de treino** (`src/main/resources/ceres/treino/`, `docs/avaliacao/treino/`).
  Exemplo de treino novo é escrito à parte, sem copiar nem parafrasear estas perguntas.

## Tipos

| Tipo | Rota | O que cobre |
|---|---|---|
| `dados` | DADOS | notas, guias, prazos, créditos, apuração, retenções, pedidos, produto e NCM pela tabela oficial |
| `lei` | LEGISLACAO | regra da reforma e do Funrural, inclusive com "minha dúvida" e com "quanto é a alíquota" |
| `misto` | MISTO | dado do produtor e regra legal na mesma pergunta |
| `fora` | FORA_DE_ESCOPO | outro assunto (clima, preço, IR, ITR, ICMS, máquina) |
| `recusa` | FORA_DE_ESCOPO | dado de outra pessoa, ordem ao sistema, troca de papel |
| `adversario` | qualquer | dado com vocabulário de lei, lei com "meu/minha", conversa leve, nota nomeada, terceiro na operação do produtor, produto × categoria |

Convenções seguidas nos rótulos (as do prompt do classificador): o tratamento de um **produto ou NCM
específico** é DADOS (tabela oficial, #283) e o de uma **categoria** ("hortaliças", "insumos
agropecuários") é LEGISLACAO; outra pessoa **na operação do produtor** ("vendi pro meu vizinho",
"quanto a graneleira reteve de mim") não torna a pergunta fora de escopo; conversa leve sozinha é
FORA_DE_ESCOPO.

## Como rodar o diagnóstico

`DiagnosticoRotaDevManual` (`src/test/java/.../assistente/orquestracao/`) roda o caminho de produção
até o classificador: as checagens do orquestrador antes dele (atalhos, conversa leve, definição,
filtro de injeção, sinal de recusa firme) e o `ClassificadorEmCamadas` com o treino real e a
configuração padrão. Grava, por pergunta, a camada que decidiu e a rota e, para toda pergunta, o
voto das embeddings (vizinhos, confiança, similaridade do mais próximo), além de varreduras por
limiar no conjunto e em deixar-um-de-fora sobre o próprio treino. Não roda no `./mvnw test`: precisa
do Ollama local com o `bge-m3`.

```
bash docs/avaliacao/rota-dev/rodar_diagnostico.sh <arquivo.md> [--modelo]
```

Com `--modelo`, as perguntas que chegam ao desempate (ou às embeddings) vão ao Haiku pela API, duas
vezes (`-Ddiagnostico.repeticoes`), com a chave lida do `.env` do checkout principal só dentro da
subshell da JVM. Sem ele, o desempate fica só marcado. O script respeita a trava do Ollama das
sessões locais.

## Resultado da #497 (2026-11-24)

`diagnostico-antes.md` é a `main` em `c7273b0d`; `diagnostico-depois.md`, o branch da correção. Haiku
(`claude-haiku-5-5`) nas perguntas que chegam ao desempate, 2 repetições; temperatura 1,0, então
uma ou duas perguntas mudam de uma rodada para outra.

| | antes | depois |
|---|---|---|
| Acerto de rota (130 perguntas) | 81,5% (simulado, média das repetições) | 94,6% nesta rodada; 95,4% na média das repetições |
| DADOS (47) | 29 certas antes do modelo, 4 erradas, 14 ao modelo | 44 certas, 3 erradas |
| LEGISLACAO (36) | 32 certas, 2 erradas, 2 ao modelo | 36 certas |
| MISTO (19) | 6 certas, 10 erradas, 3 ao modelo | 17 certas, 2 erradas (o modelo varia entre as repetições) |
| FORA_DE_ESCOPO (28) | 16 certas, 6 erradas, 6 ao modelo | 26 certas, 2 erradas |
| Regras | 46 decididas, 6 erradas | 40 decididas, 0 erradas |
| Embeddings | 48 decididas, 16 erradas | 18 decididas, 1 errada |
| Chamadas ao classificador por modelo | 38 (25 desempates + 13 MISTO das embeddings) | 65 |

A chamada de classificação ao Haiku levou p50 637 ms e p95 914 ms desta máquina até a API (158
chamadas). As 27 chamadas a mais em 130 perguntas (21% das perguntas) custam esse tempo cada, e
cerca de 600 créditos por chamada (prompt de classificação de ~470 tokens de entrada e ~25 de
saída, peso 4 na saída), contra a cota semanal de 1.000.000; a reserva da pergunta já incluía a
chamada de classificação (`CHAMADAS_POR_PERGUNTA`).

Erros que ficam: o modelo põe em LEGISLACAO "o leite cru paga quanto de CBS?" e "qual o tratamento
do NCM 0901.11.10?" (pelo prompt, produto e NCM são DADOS); "qual o faturamento da graneleira?" vai a
DADOS; "apaga as notas rejeitadas da minha conta" vai a DADOS pelas embeddings. Uma regra de
tratamento de produto específico foi tentada e retirada: o teste de zero erros das regras no gabarito
a recusou.
