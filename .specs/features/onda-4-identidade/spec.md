# Spec — Onda 4: Identidade multi-usuário sem JWT

- **Data:** 2026-09-23
- **Issues:** #91 (filtro por requisição) → #94 (IDOR nas guias) → #92 (401 fora de dev + seed)
- **Fora de escopo (Sprint 8 / issue #93, Onda 7):** JWT, entidade `Contador`, vínculo Contador↔Produtor, RBAC, login/cadastro, rate limiting.
- **Branch:** `claude/sleepy-wright-937f83` (worktree isolado)

## 1. Problema

O backend é, na prática, **single-user**. `MockProdutorFilter` (`@Profile("dev")`) injeta sempre o
mesmo produtor (CPF fixo `123.456.789-00`, cacheado em campo de instância), sem ler nada da
requisição. Três consequências:

1. **Anti-IDOR nunca é exercido.** Todo o `findByIdAndProdutorId` espalhado pela API é código que
   nenhum teste de ponta a ponta consegue provar, porque só existe um produtor autenticado possível.
2. **`/api/contador/guias` aceita `produtorId` arbitrário** (#94): `criar` só checa existência (404)
   e `listar` devolve as guias de qualquer id informado — sem ownership, ao contrário de
   `marcarComoPaga` e `CalendarioFiscalService.listarVencimentos`, que já guardam.
3. **Fora do profile `dev` a API inteira responde 500** (#92): sem o filtro,
   `AuthenticatedProdutorProvider.get()` lança `IllegalStateException`, que não tem handler dedicado
   e cai no `@ExceptionHandler(Exception.class)` → 500 genérico. E não há seed de produtor fora de `dev`.

## 2. Objetivo

Expressar **N usuários** por requisição, com a estrutura de identidade plugável que a Sprint 8
substitui por JWT **sem tocar em controllers nem services**. Nada de autenticação real: o header é
declarado, não verificado — é o mesmo nível de confiança do mock de hoje, só que multi-usuário.

## 3. Decisões (aprovadas pelo Murillo em 2026-09-23)

| # | Decisão | Alternativa descartada |
|---|---------|------------------------|
| D1 | Header **`X-Usuario-Id`** carregando o **UUID** do produtor | CPF/CNPJ (expõe dado pessoal em header/log, atrito com a pendência LGPD); `X-Produtor-Id` (nome trava na Sprint 8, quando identificar Contador também) |
| D2 | **Header ausente → fallback para o produtor semente** (o do `DataLoader`), em dev e homolog | 401 sempre (quebraria o Angular hoje e o Swagger/curl local) |
| D3 | **Header presente mas não resolvível** (UUID malformado ou id inexistente) → **401 `NAO_AUTENTICADO`** | 404 (vaza existência de id para enumeração); cair no fallback (mascara bug de integração do frontend) |
| D4 | Novo profile **`homolog`** com `DataLoader` semeando **dois produtores (A e B)**; filtro ativo em `{dev, homolog}`, **nunca em `prod`** | Só ampliar dev (homolog fica sem dados para exercitar multi-usuário) |

## 4. Contrato do header de identidade

```
X-Usuario-Id: <uuid do produtor>
```

| Situação | Profile `dev`/`homolog` | Profile `prod` |
|----------|-------------------------|----------------|
| Header ausente | Injeta o **produtor semente** (produtor A do `DataLoader`) | Sem filtro → **401 `NAO_AUTENTICADO`** |
| Header com UUID válido e produtor existente | Injeta **esse** produtor | **401** |
| Header com UUID válido mas produtor inexistente | **401 `NAO_AUTENTICADO`** | **401** |
| Header com string que não é UUID | **401 `NAO_AUTENTICADO`** | **401** |
| Header ausente e **sem** produtor semente no banco | **401 `NAO_AUTENTICADO`** | **401** |

Semântica: o header **declara** uma identidade, não a prova. Vale só onde o filtro está registrado.
Em `prod` nenhum filtro é registrado, logo nenhuma requisição é autenticada e toda a API autenticada
responde 401 — comportamento correto e honesto até a Sprint 8.

**Resposta 401** (envelope padrão `ApiError.errors[]`):

```json
{ "errors": [ {
  "code": "NAO_AUTENTICADO",
  "message": "Requisicao sem identidade de usuario valida.",
  "suggestion": "Envie o header X-Usuario-Id com o identificador do usuario."
} ] }
```

`rejectedValue` **não** carrega o valor recebido do header (evita refletir entrada arbitrária na resposta).

**Onde o 401 nasce:** exceção lançada dentro de um filtro não passa pelo `@RestControllerAdvice`. Por
isso o filtro, ao não resolver a identidade, apenas **deixa o `SecurityContext` vazio** e segue a
cadeia; quem materializa o 401 é o `AuthenticatedProdutorProvider`, já no escopo do handler. Efeito
colateral aceito: endpoint que **não** depende de identidade (ex.: `/api/v1/health`, Swagger)
responde normalmente mesmo com header inválido — correto, já que não há identidade a exigir ali.

## 5. Requisitos

### RF — Identidade por requisição (#91)

- **RF01** `MockProdutorFilter` resolve o usuário **por requisição**, lendo `X-Usuario-Id`.
- **RF02** O cache de instância única (`volatile cachedProdutor`) é removido. Sem cache nesta entrega:
  uma consulta por requisição; o volume do TCC não justifica cache, e cache por id sem invalidação
  esconderia alteração de produtor.
- **RF03** Header ausente → produtor semente (D2). Header não resolvível → 401 (D3).
- **RF04** `AuthenticatedProdutorProvider` e todos os services permanecem **inalterados** em assinatura
  e em comportamento observável. Muda apenas o filtro, o tipo de exceção lançada pelo provider e o
  guard das guias (#94).
- **RF05** O filtro continua respeitando autenticação pré-existente no `SecurityContext`
  (`if (getAuthentication() == null)`) — é o que permite os testes de integração atuais injetarem o
  principal na mão.
- **RF06** O filtro **limpa o `SecurityContext` no `finally`** ao final da requisição. O Tomcat reusa
  threads do pool; sem isso a identidade de A pode vazar para a requisição seguinte.

### RF — Ownership nas guias (#94)

- **RF07** `GuiaFiscalService.criar` valida `request.produtorId()` contra `produtorProvider.get().getId()`;
  divergente → **404** (`ResourceNotFoundException("Produtor", id)`), mesmo guard do `CalendarioFiscalService`.
- **RF08** `GuiaFiscalService.listar` aplica o mesmo guard antes de consultar.
- **RF09** Status e mensagem idênticos entre "produtor inexistente" e "produtor de outro dono" — não
  revela existência (consistente com o anti-IDOR do resto da API).

### RF — Fora do profile dev (#92)

- **RF10** `NaoAutenticadoException extends DomainException` (`HttpStatus.UNAUTHORIZED`) + `ErrorCode.NAO_AUTENTICADO`.
- **RF11** `AuthenticatedProdutorProvider.get()` lança `NaoAutenticadoException` no lugar de
  `IllegalStateException` → 401 pelo `@ExceptionHandler(DomainException.class)` já existente, sem handler novo.
- **RF12** Filtro e `DataLoader` passam de `@Profile("dev")` para `@Profile({"dev","homolog"})`.
- **RF13** `application-homolog.yml` criado (banco por env, sem docker-compose, CORS por env, logs INFO).
- **RF14** `DataLoader` semeia **dois** produtores: A (`123.456.789-00`, o semente/fallback) e
  B (`987.654.321-00`). Idempotente, como hoje. A permanece o fallback, para não mudar o
  comportamento de quem não manda header. B recebe **inscrição estadual e `EnderecoProdutor`
  completos** — desde a issue #88 (Onda 2) isso é pré-condição da emissão; um B sem endereço geraria
  XML com placeholder e o cenário multi-usuário não chegaria a emitir nota.

### RNF

- **RNF01** Zero regressão: os 393 testes atuais continuam verdes.
- **RNF02** Nenhum segredo novo no repositório; `homolog` lê tudo de env var.
- **RNF03** O valor bruto do header não é logado em nível INFO (evita ruído e PII em log).
- **RNF04** `prod` não ganha mecanismo de identidade declarativo — o header é ignorado por ausência do filtro.

## 6. Critérios de aceite

Os critérios 7, 8 e 9 dependem do 401, que chega no **PR 3**; os demais valem a partir do PR em que a
task correspondente entra (ver `tasks.md`).

1. Com `X-Usuario-Id: <id de A>` e depois `<id de B>`, `GET /api/notas` devolve conjuntos **diferentes**.
2. `GET /api/notas/{id de nota de A}` com header de B → **404**.
3. `GET /api/contador/guias?produtorId=<A>` com header de B → **404**.
4. `POST /api/contador/guias` com `produtorId` de A e header de B → **404**, e **nenhuma guia é gravada**.
5. `PATCH /api/contador/guias/{guia de A}/pagar` com header de B → **404** (regressão do que já funcionava).
6. Sem header, em dev/homolog → tudo responde como hoje (produtor A). O Angular atual não muda.
7. `X-Usuario-Id: nao-e-uuid` → **401** com `errors[0].code == "NAO_AUTENTICADO"`.
8. `X-Usuario-Id: <uuid inexistente>` → **401**, mesma resposta do item 7.
9. Em contexto **sem** filtro (simulando prod), endpoint autenticado → **401**, nunca 500.
10. Duas requisições consecutivas na mesma thread, a segunda sem header, não herdam a identidade da primeira.
11. `./mvnw test` verde, com os novos testes somados aos 393.

## 7. Riscos

| Risco | Mitigação |
|-------|-----------|
| Vazamento de identidade entre requisições pelo reuso de thread do Tomcat | RF06 — `clearContext()` em `finally`; teste de duas requisições seguidas com headers diferentes |
| Frontend Angular quebrar | D2 — fallback preserva o comportamento atual; header é opt-in |
| Header ser confundido com autenticação real | Javadoc explícito no filtro + nota no CLAUDE.md: **declara, não prova**; nunca em prod |
| Teste de integração existente que injeta principal na mão parar de funcionar | RF05 — filtro só age com contexto vazio; testes de service não passam pelo filtro |
| `homolog` subir sem `APP_CORS_ALLOWED_ORIGINS` | Env obrigatória no yml, mesmo padrão de `prod` |

## 8. Nota de integração para o frontend Angular

- Header: **`X-Usuario-Id`**, valor = UUID do produtor — o **mesmo** id que o frontend já passa em
  `/api/calendario-fiscal/:produtorId/vencimentos` e no payload de `/api/contador/guias`.
- **Nada quebra hoje:** sem o header, o backend segue respondendo como o produtor semente.
- Ao adotar o header, enviá-lo em **todas** as chamadas (interceptor HTTP) — inclusive nas de
  `/api/contador/guias`, que a partir da #94 exigem que o `produtorId` do payload/query **bata** com
  o do header, sob pena de 404.
- Novo status possível: **401** com `errors[0].code == "NAO_AUTENTICADO"` (header presente e inválido).
  Tratar como sessão inválida — é o mesmo 401 que o JWT devolverá na Sprint 8.
- `X-Usuario-Id` precisa constar nos headers aceitos pelo CORS; a `CorsConfig` atual será conferida
  na task T7 e, se necessário, ajustada + travada por teste.
