# Ceres — assistente fiscal (infraestrutura)

A Ceres responde perguntas do produtor combinando três camadas: **Tool Calling** (funções Java que
consultam os dados reais do produtor), **RAG** (trechos oficiais da EC 132/2023 e da LC 214/2025,
com citação obrigatória) e **orquestração** (roteador que decide a camada pela intenção). Esta
página cobre a base de infraestrutura (issue #141); o desenho completo está em
`.specs/features/sprint-6-ceres/spec.md`.

## Stack

| Peça | Escolha | Por quê |
|---|---|---|
| Integração | Spring AI **1.1.8** | Linha compatível com o Boot 3.5.14 (a 2.x exige Boot 4) |
| Modelo de chat | `qwen3:4b` no **Ollama** (local) | Orientação do prof. Luan: arquitetura completa com modelo simples, escalar depois |
| Provedor por API | qualquer API compatível com a da OpenAI (OpenAI, Maritaca) | Só configuração: `AI_CHAT_PROVIDER=openai` + `AI_API_KEY` |
| Embedding | `bge-m3` no Ollama, 1024 dimensões | **Sempre local.** Trocar o modelo obriga a reindexar a legislação |
| Vector store | pgvector no mesmo PostgreSQL 16 | Imagem `pgvector/pgvector:0.8.6-pg16`; schema pelo Liquibase (#156) |

## Subindo

```bash
# Só Postgres + Ollama (o backend roda pelo mvnw). Na primeira vez o ollama-init baixa ~4 GB.
docker compose --profile ia up -d
./mvnw spring-boot:run

# Tudo em containers, inclusive o backend
docker compose --profile full up -d --build
```

O Ollama **não** faz parte do compose padrão de dev: quem trabalha em NF-e não precisa baixar os
modelos. Sem Ollama o backend sobe normalmente; a Ceres responde **503**
`ASSISTENTE_INDISPONIVEL` e o `/actuator/health` fica **`DEGRADED`** (HTTP 200).

> **Volume antigo do Postgres:** a imagem mudou de `postgres:16-alpine` (musl) para a do pgvector
> (Debian/glibc), e a collation muda junto. Num volume de dev já existente, rode
> `docker compose down -v` (o `DataLoader` recria o seed) ou `REINDEX DATABASE agrocontabil;`.

### Trocar de modelo ou de provedor

| Quero | Variáveis |
|---|---|
| Modelo local maior | `AI_CHAT_MODEL=qwen3:8b` (o `ollama-init` baixa na próxima subida) |
| API da OpenAI | `AI_CHAT_PROVIDER=openai`, `AI_API_KEY=...`, `AI_CHAT_MODEL=gpt-4o-mini` |
| Outra API compatível (ex.: Maritaca) | `AI_CHAT_PROVIDER=openai`, `AI_API_BASE_URL`, `AI_API_COMPLETIONS_PATH` (a Maritaca usa `/chat/completions`, sem `/v1`; confira na documentação do provedor), `AI_API_KEY`, `AI_CHAT_MODEL` |

Com `AI_CHAT_PROVIDER=openai` e sem `AI_API_KEY`, a aplicação **não sobe** (falha na subida, não
na primeira pergunta). A chave vem só de variável de ambiente.

## Garantias desta camada

- **Falha do modelo é 503, nunca 500.** Todo acesso ao modelo passa por
  `assistente/llm/ModeloLinguagem`, que traduz qualquer falha do provedor em
  `AssistenteIndisponivelException`. O cliente recebe mensagem genérica; o log registra só o tipo
  da falha (a mensagem de erro de um provedor por API pode ecoar a requisição).
- **Timeout e retry:** `AI_CONNECT_TIMEOUT` (5 s), `AI_READ_TIMEOUT` (120 s — modelo local em CPU é
  lento), `AI_RETRY_MAX_ATTEMPTS` (2, sem retry em erro 4xx).
- **Prompt e resposta nunca vão para log** (`spring.ai.chat.observations.log-*: false`).
- **Ollama só em loopback** (`127.0.0.1:11434`): ele não tem autenticação.
- **Health com cliente próprio** (timeout de 2 s, cache de 10 s): o `/actuator/health` é público e
  não pode pendurar nem virar amplificador de chamadas ao Ollama.

## Testes

O profile `test` desliga os modelos reais (`spring.ai.model.chat/embedding: none`) e o
`support/ModelosSimuladosConfig` registra `ChatModelSimulado` (programável: texto fixo, sequência
de respostas, falha) e `EmbeddingModelSimulado` (determinístico, 1024 dimensões). A CI não depende
do Ollama nem de chave de API.

## Rota de dados — Tool Calling (issues #142 e #143)

`POST /api/assistente/chat` com `{"pergunta": "..."}` (1 a 1000 caracteres, só produtor).

| Função | Responde | Reaproveita |
|---|---|---|
| `totalComprasPorInsumo(descricaoOuNcm, dataInicio, dataFim)` | "quanto comprei de fertilizante no semestre?" | recorte do crédito: entradas `IMPORTADA` + `AUTORIZADA` |
| `consultarApuracao(mes)` | "quanto de IBS eu devo em julho?" | `ApuracaoService` |
| `consultarCreditos(dataInicio, dataFim)` | "quanto tenho de crédito?" | `CreditoService` |
| `listarNotas(tipo, situacao, dataInicio, dataFim, limite)` | "minhas últimas vendas" (máx. 10) | consulta fixa top-N |
| `proximosVencimentos(dias)` | "o que vence este mês?" (máx. 90 dias) | guias não pagas |

Garantias, todas em código:

- **Isolamento (RN007):** nenhuma função tem parâmetro de produtor. O produtor é resolvido do
  usuário autenticado **antes** de chamar o modelo e chega às funções pelo `ToolContext`
  (`ContextoAssistente`), que o modelo não vê. Sem contexto, a função falha fechado. Testado com
  prompt injection pedindo dados de outro produtor.
- **Nenhum número inventado:** o laço de funções é do backend e guarda os resultados; o
  `ValidadorNumeros` descarta a resposta que tiver número ou data que não veio das funções, da
  pergunta ou do prompt de sistema.
- **Sem text-to-SQL:** consultas fixas e parametrizadas; texto digitado vira `LIKE` escapado.
- **Minimização:** as funções devolvem totais, datas, contagens e listas curtas — nenhum nome,
  CPF/CNPJ, endereço, chave de acesso ou observação.
- **Custo limitado:** no máximo `AI_MAX_RODADAS_FERRAMENTAS` (3) rodadas de funções por pergunta e
  `AI_MAX_CONVERSAS_SIMULTANEAS` (4) perguntas ao mesmo tempo (acima disso, 429).
- **Erro de função:** o modelo recebe só a orientação de parâmetro inválido (escrita por nós) ou
  uma mensagem genérica — nunca a mensagem do banco.
