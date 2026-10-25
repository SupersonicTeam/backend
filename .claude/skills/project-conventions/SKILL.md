---
name: project-conventions
description: >
  Padrões inegociáveis do AgroContabil Backend: camadas, fluxo de nova feature,
  contrato de erro, DTO/mapper, validação, testes, migrations e git.
  Use ANTES de escrever ou revisar qualquer código, para seguir o projeto sem reler o CLAUDE.md inteiro.
---

# Convenções do AgroContabil Backend

Regras que todo código novo deve seguir. Se um trecho novo diverge daqui, está errado — ajuste o código, não a regra.

## Arquitetura em camadas (direção única)

`Controller → Service → Repository`. Nunca pular nem inverter.

- **Controller** só orquestra: recebe DTO, chama service, devolve resposta. **Zero** regra de negócio.
- **Regra de negócio** vive **só** no `service`.
- **Repository** = Spring Data JPA. Queries de ownership sempre `findByIdAndProdutorId`.
- **Entidade JPA nunca sai da API.** Sempre converter para DTO via `mapper`.
- **Alíquota e redução de base têm um único resolvedor cada.** Nunca ler `TributosProperties.ibs/cbs` nem `AliquotaIbsUfRepository`/`NcmReferenciaRepository` direto num serviço de cálculo — sempre via `AliquotaIbsCbsResolver` (issue #86) e `ReducaoNcmResolver` (issue #52). Débito e crédito precisam sair do mesmo ponto para a apuração ser simétrica.

## Módulo da Ceres (`assistente`) — fronteira travada por ArchUnit (issue #141)

- O módulo fala com o resto do backend **só** pelas interfaces de `assistente.porta`; os records das portas não expõem entidade nem projeção de repository.
- Só `assistente.adaptador` chama services — e **nunca** repository (é no service que vivem o anti-IDOR e a regra fiscal).
- Fora do módulo, só `controller` e `config` dependem dele. Nada no módulo é `@Transactional`: a chamada ao modelo nunca roda dentro de transação.
- Nova necessidade de dado na Ceres = método novo na porta + adaptador chamando um service. Nunca injetar service/repository numa classe do módulo.
- Extrair a Ceres como serviço (só quando um critério de `memory: ceres-extracao-microsservico-futuro` aparecer) = trocar os adaptadores por clientes HTTP.

## Fluxo para adicionar um endpoint (checklist)

1. **DTO** `record` em `dto/` — `XxxRequest` (entrada) e `XxxResponse` (saída). Validação Bean Validation nas anotações do request.
2. **Mapper** em `mapper/` — entidade ↔ DTO. Nunca expor entidade.
3. **Service** em `service/` — regra de negócio + transações. Lança exceção de domínio (`extends DomainException`) em erro.
4. **Controller** — `@Valid` no request, chama service, retorna `ResponseEntity`/DTO. Sem try/catch (o `GlobalExceptionHandler` cuida).
5. **Migration** (se mudar schema) — novo changeSet em `db/changelog/changes/`. Nunca editar um já aplicado.
6. **Testes** — unitário de service (Mockito) + integração do fluxo crítico.
7. Documentar o endpoint na tabela do CLAUDE.md e no `codebase-map` se criar classe significativa.

## Validação

- **Sempre** Bean Validation (`@Valid` + anotações no DTO). **Nunca** validar manualmente o que a anotação resolve.
- Regra fiscal/de-negócio (não expressável por anotação) → no service.
- **Limites do layout NF-e = defesa em profundidade:** `@Max` no DTO (400) **e** regra no `FiscalRulesValidator` (422). O DTO não alcança notas importadas/legadas, que entram por XML.

## Contrato de erro (obrigatório)

- Todo erro sai pelo `GlobalExceptionHandler` (`@RestControllerAdvice`) → `ApiError` com envelope **`errors[]`**.
- Cada erro é um `ErrorDetail`: `code` / `field` / `message` / `suggestion` / `rejectedValue` / `expectedValue`.
- Exceções de domínio estendem `DomainException` (carrega `HttpStatus` + lista de `ErrorDetail`). Código em `ErrorCode`.
- **Nunca** tratar exceção no controller. **Nunca** vazar stack trace / detalhe técnico ao cliente. 5xx = mensagem genérica; detalhe só no log.

## Segurança (já aplicada, manter)

- **Anti-IDOR:** toda operação sobre recurso de produtor usa `findByIdAndProdutorId` e responde **404** (não 403) para recurso de outro produtor — não revela existência.
- **`produtorId` vindo do cliente é afirmação, não identidade.** Quando um endpoint recebe `produtorId` no path/query/payload (calendário, guias fiscais), confrontar com `produtorProvider.get().getId()` **antes** de ler ou gravar; divergiu, `ResourceNotFoundException` (404). Foi o que faltava nas guias até a issue #94.
- Identidade **provada** por JWT (`JwtAutenticacaoFilter` → `UsuarioAutenticadoProvider`/`AuthenticatedProdutorProvider`; credencial relida a cada requisição). Em `dev`/`homolog`, requisição **sem** `Authorization` ainda pode declarar identidade por `X-Usuario-Id` (`MockIdentidadeFilter`); nunca em prod.
- **Dado pessoal novo em coluna** (CPF/CNPJ, e-mail, telefone…) = `@Convert(converter = DadoPessoalCifradoConverter.class)` + `CifragemLegadoRunner.COLUNAS_CIFRADAS` + largura no changeSet (#147). Nunca logar dado pessoal (a máscara do logback é rede de segurança, #148).
- **Operação de escrita crítica nova** = `@Auditado(acao, recurso)` no método público do service (#149); nada de dado pessoal no retorno usado pela auditoria.
- **Sem identidade válida → 401** `NaoAutenticadoException` (`ErrorCode.NAO_AUTENTICADO`), nunca 500. Vale tanto para header que não resolve quanto para profile sem filtro (prod).
- **Nenhum segredo no repo** — env vars (`${DB_PASSWORD}`, `${JWT_SECRET}`, `NFE_CERT_*`). CORS por env, nunca `*`.
- **Logs sem senha/token/credencial.**

## Banco / migrations

- Schema só via **Liquibase** (`db.changelog-master.yaml`). Hibernate em `ddl-auto: validate` — **proibido** `update`/`create`.
- Uma mudança de schema = um changeSet novo. Nunca editar changeSet aplicado.
- **Tabelas de vigência (`aliquota_ibs_uf`, `aliquota_cbs`):** nova alíquota = novo changeSet com `insert` (e `update` só para fechar `data_fim` da vigência anterior). Nunca alterar a alíquota de uma linha existente — notas já calculadas dependem dela.
- Todo changeSet novo precisa do **espelho no `schema-nota-fiscal-test.sql`** (Liquibase é desligado no profile `test`).

## Testes

- Feature nova = teste unitário de service (Mockito) + teste de integração do fluxo crítico.
- Mockito **strict stubs**: não crie stub de objeto usado só após early return.
- Rodar `./mvnw test` (profile test, H2) antes de abrir PR. Manter todos verdes.

## Idioma do código (convenção híbrida)

- **Domínio fiscal → português:** `NotaFiscal`, `Produtor`, `gerarXml`, `sincronizarStatus`, `CalculoIbsCbsService`.
- **Infra/utilitário técnico genérico → inglês:** `ApiError`, `ErrorCode`, `XmlGenerator`, `XsdValidator`, `*Config`, `GlobalExceptionHandler`.
- **API do Java/Spring → inglês:** getters/setters, nomes exigidos por framework.
- **Regra prática:** escreva como o código ao redor. Domínio fiscal na dúvida → português; utilitário genérico → inglês.
- **Comunicação (PR, commit, conversa) → português.**

## Git / PRs

- **Nunca** commitar direto em `main`/`dev`/`prod`. Usar `feature/`, `fix/`, `chore/` + **worktree isolado**.
  - Armadilha: ao usar worktree, Read/Edit/Write e `mvnw` devem apontar para o path do worktree, não a raiz.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `chore:`, `refactor:`).
- **PRs pequenos e deployáveis:** uma responsabilidade lógica por PR.
- **Datas:** `YYYY-MM-DD`, timezone `America/Sao_Paulo`.

## Estado da nota fiscal (referência rápida)

`RASCUNHO → [AGUARDANDO_APROVACAO → APROVADA] → XML_GERADO → AUTORIZADA / REJEITADA / PENDENTE_SEFAZ / CANCELADA`; `COM_INCONSISTENCIAS`; `DEVOLVIDA`.
- Editável (update/delete): `RASCUNHO`, `COM_INCONSISTENCIAS` e `DEVOLVIDA` (#178). Fora disso → **409** (`NotaFiscalNaoEditavelException`).
- `gerar-xml` **com contador vinculado** (vínculo ATIVO conferido na hora): só `APROVADA`; editável → 409 `AprovacaoExigidaException`. Sem contador: os editáveis e `APROVADA`.
- Transições da revisão só pelo `RevisaoNotaService` (enviar/retirar pelo produtor, aprovar/devolver pelo `ADMINISTRADOR`/`CONTADOR`), sob lock e com histórico em `nota_fiscal_revisao`.

## Bibliotecas externas

Ao mexer com Spring, JAXB, JasperReports, Apache Santuario, Liquibase etc., busque doc atualizada via **Context7 MCP** antes de codar (evita API desatualizada).
