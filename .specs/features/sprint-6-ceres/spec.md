# Spec — Sprint 6: Ceres, Fase 1 (assistente fiscal)

- **Data:** 2026-09-29
- **Issues:** #141 (infra Spring AI/Ollama) → #142 + #143 (Tool Calling com isolamento) → #144
  (sanitização + auditoria) → #156 (RAG com citação) → #157 (orquestrador + SSE)
- **Baseline:** 684 testes verdes em `c5096bd` (main).
- **Fonte de verdade da arquitetura:** Projeto Empreendedor + nota do vault
  `projects/agrocontabil/IA - Arquitetura RAG + Tool Calling.md` (decisões de 2026-09-28).
- **Autonomia:** Murillo pediu em 2026-09-29 execução autônoma (planejar → PR incremental → code
  review avançado de segurança/lógica após cada PR). Os PRs ficam **empilhados** (cada um sobre o
  anterior) porque a ordem da Fase 1 é de dependência; o merge fica com o Murillo.

## 1. Princípio que guia todas as decisões

> **Tudo que precisa ser garantido é garantido em código, nunca em prompt.**

| Garantia | Onde mora | Por que não no prompt |
|---|---|---|
| Só dados do produtor da sessão (RN007) | `produtorId` no `ToolContext`; nenhuma função tem parâmetro de produtor | prompt injection contorna instrução, não contorna parâmetro inexistente |
| Número nunca gerado pelo modelo | funções Java com consulta fixa; validador de números na resposta (#157) | o modelo "arredonda", soma e inventa com confiança |
| Citação legal real | modelo só marca `[Tn]`; `ValidadorCitacao` monta a referência pelos metadados | o modelo inventa artigo com facilidade |
| Nada de PII ao provedor | `DataSanitizer` nos DTOs das funções | provedor por API é trocável por configuração |
| Custo limitado por pergunta | laço de tool calling **controlado pelo backend**, com teto de rodadas | o laço interno do Spring AI não tem teto |

## 2. Decisões de desenho

### D-1 — Spring AI 1.1.8 (BOM), sem ChatClient no caminho de dados
- 1.1.8 é a última da linha 1.1 (compatível com Boot 3.5.14; a 2.x exige Boot 4).
- Starters: `spring-ai-starter-model-ollama` e `spring-ai-starter-model-openai` no PR 1;
  `spring-ai-starter-vector-store-pgvector` entra **no PR do RAG (#156)**, junto com o schema que
  ele consome (divergência deliberada da #141: sem a tabela o bean não tem o que fazer, e o
  pgvector no H2 dos testes só traria exclusões de autoconfiguração sem ganho).
- Execução das ferramentas **controlada pelo backend** (`internalToolExecutionEnabled(false)` +
  `ToolCallingManager`), com teto `app.assistente.max-rodadas-ferramentas` (padrão 3). Dá três
  coisas que o laço interno não dá: teto de custo, registro exato de `fontesDados[]` e controle do
  que volta ao modelo em caso de erro.

### D-2 — Provedor selecionável sem código
- `spring.ai.model.chat=${AI_CHAT_PROVIDER:ollama}` (`ollama` | `openai`). "openai" é qualquer
  API compatível (OpenAI, Maritaca) via `AI_API_BASE_URL`. Chave **só** por `AI_API_KEY`.
- Embedding **fixo** em `ollama` (`bge-m3`, 1024 dim): trocar o modelo exige reindexar, então não
  vira env var casual.
- Os demais modelos do starter OpenAI (imagem, áudio, moderação) ficam `none`: sem isso a
  aplicação exigiria chave de API na subida mesmo com Ollama.

### D-3 — Falha do modelo nunca derruba o backend
- Timeout: `spring.http.client.*` e `spring.http.reactiveclient.*` (o projeto não tinha nenhum
  outro `RestClient`/`WebClient`; verificado). Retry curto do Spring AI (`spring.ai.retry.*`),
  sem retry em erro 4xx.
- `ModeloLinguagem` (gateway) traduz qualquer falha do provedor em `AssistenteIndisponivelException`
  (**503** `ASSISTENTE_INDISPONIVEL`, mensagem genérica; detalhe só no log).
- **Ollama fora do compose padrão de dev** (profile `ia`/`full`): quem trabalha em NF-e não baixa
  ~4 GB de modelos. Sem Ollama o backend sobe normal; a Ceres responde 503 e o health fica
  `DEGRADED`.

### D-4 — Download dos modelos por serviço de init do compose (divergência da #141)
- A #141 sugere `pull-model-strategy: when_missing` do Spring AI. Isso **bloqueia a subida do
  backend** por minutos na primeira vez e **impede a subida** se o Ollama estiver fora. Em vez
  disso, o serviço one-shot `ollama-init` do compose faz `ollama pull` dos modelos (idempotente).
  A estratégia do Spring AI continua configurável (`AI_OLLAMA_PULL_STRATEGY`, padrão `never`).

### D-5 — Health `DEGRADED`, não `DOWN`
- `OllamaHealthIndicator` com cliente próprio de timeout curto (2 s) — o health não pode herdar o
  read-timeout de 120 s da geração. Ollama inacessível ou modelo ausente → status `DEGRADED`
  (HTTP 200), porque o restante do sistema funciona: `DOWN` faria o healthcheck do compose
  reiniciar um backend saudável.

### D-6 — Isolamento (RN007) por construção
- `ContextoAssistente` (record: `produtorId`, coletor de `fontesDados`) resolvido **antes** da
  chamada ao modelo, na thread da requisição, e entregue às funções via `ToolContext`.
- Funções **não** leem `SecurityContext`, **não** recebem `produtorId`, e toda consulta filtra por
  `...ProdutorId`. `ApuracaoService`/`CreditoService` ganham variantes com produtor explícito
  (a variante antiga delega para a nova — nenhuma regra duplicada).
- Erro dentro de função: `ToolExecutionExceptionProcessor` próprio devolve ao modelo mensagem
  genérica (ou a mensagem de validação de parâmetro, que é segura); exceção técnica nunca chega ao
  provedor.

### D-7 — Auditoria sem conteúdo (#144)
- `assistente_interacao`: produtor, rota, funções chamadas (nome + parâmetros já sanitizados),
  ids dos trechos citados, modelo, latência, tokens, desfecho. **Sem texto de pergunta/resposta.**
- Gravada em transação curta própria, depois da chamada ao modelo (nenhuma conexão do pool presa
  durante a geração). Auditoria é requisito (RF020): se a gravação falhar, a resposta **não** é
  entregue e o cliente recebe 503 genérico.

### D-8 — Citação validada em código (#156)
- Base vetorial só com legislação pública (nunca notas).
- Trecho = dispositivo (artigo, parágrafo, inciso, alínea). Nova redação = nova vigência (insert).
- Marca `[Tn]` inexistente ou ausência de marca → resposta descartada, texto fixo
  "não encontrei fundamento legal para isso".

## 3. Plano de PRs (empilhados)

| PR | Issue | Entrega | Depende |
|---|---|---|---|
| 1 | #141 | BOM + starters, provedor por env, compose (pgvector, ollama, ollama-init), health `DEGRADED`, timeout/retry, `ModeloLinguagem` + 503, modelos simulados no profile `test` | — |
| 2 | #142 + #143 | `assistente/ferramentas` (5 funções), `ContextoAssistente` via `ToolContext`, laço controlado, `POST /api/assistente/chat` síncrono (rota de dados), `fontesDados[]`, testes de isolamento com prompt injection | 1 |
| 3 | #144 | `DataSanitizer`, minimização (top-N), `assistente_interacao` (changeSet 0025), limite de tamanho da pergunta, logs sem PII | 2 |
| 4 | #156 | changeSet `vector` + `legislacao_trecho` + HNSW + tsvector/GIN, fatiador, ingestão idempotente por hash, busca por vigência + limiar, `ValidadorCitacao`, Testcontainers pgvector | 1 |
| 5 | #157 | `ClassificadorIntencao` plugável + `LlmClassificadorIntencao`, `OrquestradorService` (4 rotas), prompt de sistema, SSE, validador de números, auditoria da rota | 3, 4 |

Após cada PR: code review avançado (lógica, segurança, regra de negócio, vazamento de memória e de
dados), correções no mesmo PR, `./mvnw test` verde.
