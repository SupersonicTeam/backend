# Design — Onda 4: Identidade multi-usuário sem JWT

Complementa `spec.md`. Só o que muda no código; camadas e contrato de erro seguem `project-conventions`.

## 1. Visão

```
              ┌──────────────────────────────────────────────┐
HTTP request  │ MockProdutorFilter  (@Profile{dev,homolog})  │
  X-Usuario-Id│  header? → findById  : produtor semente      │
──────────────▶  resolvido? → SecurityContext                │
              │  não resolvido? → NaoAutenticadoException    │
              │  finally → clearContext()                    │
              └───────────────────┬──────────────────────────┘
                                  ▼
              AuthenticatedProdutorProvider.get()
                 principal ausente → NaoAutenticadoException (401)
                                  ▼
              Services (inalterados) → findByIdAndProdutorId → 404 anti-IDOR
```

Em `prod` o filtro não é registrado (`SecurityConfig` já injeta com `required = false`): nenhuma
requisição carrega principal, e o provider responde 401 em vez do 500 de hoje.

## 2. Componentes

### 2.1 `MockProdutorFilter` (reescrito) — `config/`

```java
public static final String HEADER_USUARIO_ID = "X-Usuario-Id";
```

`doFilterInternal`:

1. Se já há autenticação no contexto → segue a cadeia (RF05) e **não** limpa o contexto no fim
   (o contexto é de quem o montou — caso dos testes de integração).
2. Senão, resolve:
   - header ausente/em branco → `resolverSemente()` → `produtorRepository.findByCpfCnpj(CPF_SEMENTE)`;
   - header presente → `UUID.fromString` (falha → 401) → `findById` (vazio → 401).
3. Resolveu → `UsernamePasswordAuthenticationToken(produtor, null, List.of())` no contexto.
4. `try { chain.doFilter } finally { SecurityContextHolder.clearContext() }` (RF06).
5. Não resolveu → **lança `NaoAutenticadoException`**, que o `GlobalExceptionHandler` converte em 401.

> **Detalhe crítico:** exceção lançada em filtro roda **antes** do `DispatcherServlet` e, por padrão,
> não passa pelo `@RestControllerAdvice`. Para não abrir um segundo caminho de erro, o filtro
> **não lança direto**: ele deixa o contexto vazio e **segue a cadeia**; quem materializa o 401 é o
> `AuthenticatedProdutorProvider` dentro do service (RF11), já no escopo do advice.
> Consequência aceita: endpoint público (ex.: `/api/v1/health`) com header inválido responde normal —
> correto, já que não depende de identidade.

Log: `debug` com o motivo ("header ausente", "uuid malformado", "produtor inexistente"); sem o valor
bruto em INFO (RNF03).

### 2.2 `NaoAutenticadoException` — `exception/`

`ErrorDetail` é um `record` cujos factories `of(...)` não cobrem `suggestion` — usa-se o construtor
canônico, como já faz `CancelamentoForaPrazoException`:

```java
public class NaoAutenticadoException extends DomainException {

  private static final String MENSAGEM = "Requisicao sem identidade de usuario valida.";

  public NaoAutenticadoException() {
    super(HttpStatus.UNAUTHORIZED, MENSAGEM,
        List.of(new ErrorDetail(
            ErrorCode.NAO_AUTENTICADO.code(), null, MENSAGEM,
            "Envie o header X-Usuario-Id com o identificador do usuario.", null, null)));
  }
}
```

`ErrorCode.NAO_AUTENTICADO` entra no catálogo, seção nova "Identidade / autenticação (Onda 4 — #92)".
Nenhum `@ExceptionHandler` novo: `DomainException` já é tratado.

### 2.3 `AuthenticatedProdutorProvider` — `service/`

Troca `IllegalStateException` por `NaoAutenticadoException`. Javadoc atualizado (o `@throws` de hoje
cita `IllegalStateException`). Único ponto de atenção: `AuthenticatedProdutorProviderTest` já existe
e asserta o tipo — atualizar.

### 2.4 `GuiaFiscalService` — `service/`

Guard privado reaproveitado por `criar` e `listar`:

```java
private void exigirProdutorAutenticado(UUID produtorId) {
  if (!produtorProvider.get().getId().equals(produtorId)) {
    throw new ResourceNotFoundException("Produtor", produtorId);
  }
}
```

- `criar`: guard **antes** do `findById` (não grava nada — critério de aceite 4).
- `listar`: guard antes da consulta.
- Javadoc da classe reescrito: o "sem autenticação de Contador" vira "produtorId precisa bater com o
  produtor autenticado até a #93 entregar o papel Contador".

### 2.5 `DataLoader` — `config/`

`@Profile({"dev","homolog"})`; extrai `criarProdutor(...)` e semeia A (fallback, CPF `123.456.789-00`)
e B (`987.654.321-00`, "Maria Agricultora", MT, PF/SIMPLES). Idempotência por `existsByCpfCnpj`, por
produtor. Loga os UUIDs semeados em INFO para facilitar o uso do header no Swagger/Angular — id não
é segredo e já viaja na URL de outros endpoints.

### 2.6 `application-homolog.yml` — `resources/`

Espelha `prod` (env vars, sem docker-compose, `show-sql: false`, `APP_CORS_ALLOWED_ORIGINS`
obrigatória), com `logging.level.br.com.agrocontabil: DEBUG` e `show-details: when-authorized`.
Nenhum segredo literal.

### 2.7 `SecurityConfig`

Sem mudança funcional (`required = false` já cobre a ausência do filtro em prod). Só o Javadoc, que
hoje diz "em perfil dev" — passa a descrever `{dev, homolog}` e o contrato do header.

## 3. CORS

`CorsConfig` usa `setAllowedHeaders(List.of("*"))` — `X-Usuario-Id` **já passa** no preflight, nenhuma
mudança necessária. A task T7 apenas **trava isso com teste** no `CorsIntegrationTest`
(`Access-Control-Request-Headers: X-Usuario-Id`), para que um aperto futuro da lista não quebre o
frontend em silêncio.

## 4. Estratégia de teste

| Nível | Arquivo | Cobre |
|-------|---------|-------|
| Unit | `MockProdutorFilterTest` (novo) | header ausente→semente; uuid válido→esse produtor; uuid malformado→contexto vazio; id inexistente→contexto vazio; contexto pré-existente preservado; `clearContext` no finally |
| Unit | `AuthenticatedProdutorProviderTest` (ajuste) | sem principal → `NaoAutenticadoException` |
| Unit | `GuiaFiscalServiceTest` (ajuste) | `criar`/`listar` com produtorId divergente → 404 e repositório não tocado |
| Integração | `IdentidadeMultiUsuarioIntegrationTest` (novo, `@SpringBootTest` + `MockMvc`) | A e B veem notas/guias diferentes; 404 cross-tenant em notas e guias; 401 sem identidade; 401 header inválido; sem header → produtor A; duas requisições seguidas sem herança |
| Integração | `CorsIntegrationTest` (ajuste) | preflight aceita `X-Usuario-Id` |

Profile de teste: `test` (H2). Como o filtro é `{dev,homolog}`, o teste de integração o registra
explicitamente (`@Import`/`@TestConfiguration` com o filtro + `MockMvcBuilders` com `addFilters`) — o
mais fiel entre as opções, sem ligar profile `dev` na suíte.

## 5. Ordem e PRs

| PR | Issue | Conteúdo | Deployável só? |
|----|-------|----------|----------------|
| 1 | #91 | Filtro por requisição + `MockProdutorFilterTest` + seed de 2 produtores + CORS travado | Sim — fallback mantém o comportamento atual |
| 2 | #94 | Guard em `criar`/`listar` + testes unit/integração cross-tenant | Sim — depende do PR 1 só para **testar** com 2 usuários |
| 3 | #92 | `NaoAutenticadoException` + `ErrorCode` + provider + `@Profile{dev,homolog}` + `application-homolog.yml` + docs | Sim |

O seed de 2 produtores entra no PR 1 (sem ele a #91 não é exercível); o `@Profile` do `DataLoader`
muda no PR 3, junto do resto do pacote "fora de dev".
