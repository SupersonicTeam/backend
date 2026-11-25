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
| Acerto de rota (130 perguntas) | 81,5% (simulado, média das repetições) | 96,2% nesta rodada; 95,8% na média das repetições |
| DADOS (47) | 29 certas antes do modelo, 4 erradas, 14 ao modelo | 44 certas, 3 erradas |
| LEGISLACAO (36) | 32 certas, 2 erradas, 2 ao modelo | 36 certas |
| MISTO (19) | 6 certas, 10 erradas, 3 ao modelo | 19 certas |
| FORA_DE_ESCOPO (28) | 16 certas, 6 erradas, 6 ao modelo | 26 certas, 2 erradas |
| Regras | 46 decididas, 6 erradas | 40 decididas, 0 erradas |
| Embeddings | 48 decididas, 16 erradas | 18 decididas, 1 errada |
| Chamadas ao classificador por modelo | 38 (25 desempates + 13 MISTO das embeddings) | 65 |

A chamada de classificação ao Haiku levou p50 626 ms e p95 933 ms desta máquina até a API (158
chamadas). As 27 chamadas a mais em 130 perguntas (21% das perguntas) custam esse tempo cada, e
cerca de 600 créditos por chamada (prompt de classificação de ~470 tokens de entrada e ~25 de
saída, peso 4 na saída), contra a cota semanal de 1.000.000; a reserva da pergunta já incluía a
chamada de classificação (`CHAMADAS_POR_PERGUNTA`).

Erros que ficam: o modelo põe em LEGISLACAO "o leite cru paga quanto de CBS?" e "qual o tratamento
do NCM 0901.11.10?" (pelo prompt, produto e NCM são DADOS); "qual o faturamento da graneleira?" vai a
DADOS; "apaga as notas rejeitadas da minha conta" vai a DADOS pelas embeddings. Uma regra de
tratamento de produto específico foi tentada e retirada: o teste de zero erros das regras no gabarito
a recusou.

## Segundo conjunto e resultado da #624 (2026-11-25)

`perguntas-rota-dev-2.json` (versão `2026-11-25.1`) tem 65 perguntas escritas às cegas pela sessão de
código da #624, que nunca leu o gabarito nem o benchmark, nos padrões de erro que sobraram da #497:
terceiro com e sem leitura legítima (`terceiro`, `terceiro-legitimo`), ordem ao sistema (`ordem`,
`ordem-legitima`), MISTO curto (`misto-curto`), pergunta curta (`curta-dados`, `curta-lei`,
`curta-fora`) e votos do kNN entre dados e lei (`voto-knn`). As mesmas regras do primeiro: é
desenvolvimento, não teste da banca, e nunca entra em pasta de treino. Para rodar:

```
CONJUNTO=docs/avaliacao/rota-dev/perguntas-rota-dev-2.json \
  bash docs/avaliacao/rota-dev/rodar_diagnostico.sh <arquivo.md> --modelo
```

Correção (as duas só mandam a pergunta adiante; nenhuma decide rota nova):

- **Ordem de ação no sistema vai ao desempate** (`SinalRecusaFirme.pedeDesempate`): "cancela a nota
  418", "marca a guia como paga", "exclui as compras", "altera o valor da nota", "aprova a nota",
  "manda um e-mail pro contador", no começo da mensagem. Regra e embeddings os punham em DADOS pelas
  palavras "nota" e "guia"; o modelo os lê como fora de escopo.
- **Pergunta de direito ao crédito impede a regra de decidir DADOS** (`RegrasClassificacao`,
  `LEI_GENERICA`): "isso dá crédito?", "gera crédito", "ressarcimento" junto do dado do produtor são
  candidatos a MISTO.

Haiku nas perguntas que chegam ao desempate, 2 repetições, temperatura 1,0:

| | antes (`main` em `df122843`) | depois |
|---|---|---|
| Conjunto 2 (65) | 48 certas (73,8%) | 56 certas (86,2%) |
| Conjunto 1 (130) | 125 certas (96,2%, rodada da #497) | 127 certas (97,7%) |
| Regras, erros no conjunto 2 | 9 | 3 |
| Chamadas ao classificador por modelo, conjunto 2 | 33 | 41 |
| Chamadas ao classificador por modelo, conjunto 1 | 65 | 66 |

Diagnósticos: `diagnostico-624-conjunto2-antes.md`, `diagnostico-624-conjunto2-depois.md` e
`diagnostico-624-conjunto1-depois.md`. O R09 do conjunto 1 ("apaga as notas rejeitadas da minha
conta") deixou de errar. Erros que ficam no conjunto 2: o modelo põe "meu crédito de CBS?" e "quanto
de crédito eu tive nas compras de defensivo?" em MISTO e "minha guia de IBS vence quando e o que
acontece se eu atrasar?" em DADOS; o kNN vota DADOS com 0,91 em "até quantos dias depois da emissão
eu posso cancelar uma nota?"; a regra põe "a fazenda do lado" em lei, "e o funrural do mês?" em lei
e "como calculo o ICMS da minha venda de gado?" em dados. Dois rótulos são discutíveis (K02 e K03:
produto específico com redução é DADOS pela convenção do prompt).

**Isto muda o que a banca mede:** entra só depois da medição pela regra da comparação da #497 e com o
OK do Murillo.
