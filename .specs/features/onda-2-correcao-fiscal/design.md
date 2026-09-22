# Onda 2 — Design técnico

Complementa `spec.md`. Ordem obrigatória: **#86 → #85 → #88**, um PR por issue, worktree isolado.

## 1. Abordagens consideradas para o resolvedor (#86 + #85)

| Abordagem | Prós | Contras | Veredito |
|-----------|------|---------|----------|
| **A. `AliquotaIbsCbsResolver` como `@Component` dedicado, mesmo padrão do `ReducaoNcmResolver` (#52)** | Simetria garantida por construção (um só ponto); testável isolado com Mockito; débito e crédito perdem a dependência de `AliquotaIbsUfRepository`/`TributosProperties`; a vigência entra depois sem tocar os serviços | Mais uma classe; os testes atuais dos dois serviços trocam o mock do repositório pelo mock do resolvedor | **Escolhida** — repete o padrão que já resolveu o bug simétrico da #52 |
| B. Tornar `TributacaoService.resolveAliquotaIbs` público e injetar `TributacaoService` no `CreditoTributarioService` | Menor diff imediato | Acopla crédito ao serviço de débito (que também calcula/persiste); testes do crédito passam a precisar de todo o grafo do débito; viola "uma responsabilidade por classe" | Rejeitada |
| C. Persistir a alíquota vigente no item na importação e recalcular crédito a partir do `aliquotaIbs` do item | Zero resolução no crédito | O item importado não tem alíquota destacada (layout 4.00 sem grupo IBS/CBS); apenas move o problema | Rejeitada |

Para a vigência (#85), duas opções de armazenamento foram avaliadas: **PK composta `(uf, data_inicio)`** vs. surrogate `id`. Escolhida a PK composta: chave natural, impede duas vigências com o mesmo início, sem gerador de ID, e o H2 replica sem ajuste. CBS ganha tabela própria `aliquota_cbs` (decisão do Murillo, 2026-09-21) em vez de lista no yml.

## 2. Resolvedor unificado

### 2.1 Contrato

```java
// service/AliquotaIbsCbsResolver.java  (domínio fiscal → português; @Component como ReducaoNcmResolver)
public AliquotasVigentes resolver(NotaFiscal nota);

public record AliquotasVigentes(
    BigDecimal ibsEstadual,
    BigDecimal ibsMunicipal,
    BigDecimal cbs,
    OrigemAliquota origemIbs,   // TABELA_UF | FALLBACK_YML
    OrigemAliquota origemCbs) { // TABELA_VIGENCIA | FALLBACK_YML
  public BigDecimal ibsTotal() { return ibsEstadual.add(ibsMunicipal); }
}
```

`OrigemAliquota` é um enum interno do record — serve só para log/teste (assertar que o fallback **não** foi usado). Não é persistido.

### 2.2 Algoritmo

```
data      = nota.dataEmissao != null
              ? nota.dataEmissao.atZoneSameInstant(America/Sao_Paulo).toLocalDate()
              : LocalDate.now(America/Sao_Paulo)   (+ WARN)
uf        = resolveUfAplicavel(nota)               (movido de TributacaoService, inalterado)
ibs       = uf != null ? aliquotaIbsUfRepository.findVigente(uf, data) : empty
            .map(a -> (a.estadual, a.municipal, TABELA_UF))
            .orElseGet(() -> fallback yml ÷ 2 (HALF_UP, 4 casas), FALLBACK_YML, WARN)
cbs       = aliquotaCbsRepository.findVigente(data)
            .map(c -> (c.aliquota, TABELA_VIGENCIA))
            .orElseGet(() -> tributosProperties.cbs.aliquota, FALLBACK_YML, WARN)
```

`resolveUfAplicavel` (RN002) permanece: interestadual → UF destino; intraestadual/destino desconhecido → UF emitente; emitente desconhecido → UF destino.

### 2.3 Nota importada (crédito)

Em nota `IMPORTADA` o produtor é o **destinatário**; a UF do emitente terceiro não é persistida (só `emitente_nome`/`emitente_cpf_cnpj`). Com `ufEmitente = produtor.estado` o algoritmo acima produz:

- interestadual (emitente ≠ destino): UF destino = produtor ✔ (princípio do destino, EC 132 art. 156-A §1.º VII);
- intraestadual: UF emitente = UF destino = produtor ✔.

Logo, para nota importada a UF aplicável é **sempre a do destinatário** e não é preciso persistir `emitente_uf`. O resolvedor usa `nota.destinatarioEndereco.uf` quando presente (dado do XML) e cai para `produtor.estado`; divergência entre os dois → `WARN`. Registrar essa premissa no Javadoc.

### 2.4 Consumo

| Serviço | Antes | Depois |
|---------|-------|--------|
| `TributacaoService` | injeta `TributosProperties` + `AliquotaIbsUfRepository`; `resolveAliquotaIbs` privado; CBS do yml | injeta `AliquotaIbsCbsResolver`; `IbsSplit`/`resolveUfAplicavel`/`normalizarUf` **removidos** (migram para o resolvedor); `item.setAliquotaCbs(vigentes.cbs())` |
| `CreditoTributarioService` | `tributosProperties.getIbs()/getCbs()` | `AliquotaIbsCbsResolver`; mantém `TributosProperties` **só** para `credito.percentualAproveitamento` |
| `ApuracaoService`, `DashboardService`, jobs | — | **sem alteração** (somam valores persistidos por item — FISC-08) |

Cache: tabela pequena e estável; primeira entrega **sem** cache (uma query por nota, não por item — já é assim hoje). Se o profiling da Onda 1 (#102) indicar, `@Cacheable` em PR separado.

## 3. Schema (Liquibase) — #85

Numeração: **próximo número livre no momento do merge** (hoje 0019; a Onda 1 #102 pode consumi-lo — conferir `db.changelog-master.yaml`). Abaixo referido como `00NN`.

### 3.1 `00NN-aliquota-vigencia.xml`

```
changeSet 00NN-aliquota-ibs-uf-add-vigencia
  addColumn aliquota_ibs_uf: data_inicio DATE NOT NULL defaultValueDate="2026-01-01"
  addColumn aliquota_ibs_uf: data_fim    DATE NULL
  dropPrimaryKey  aliquota_ibs_uf   (Postgres resolve o nome; H2 não roda Liquibase)
  addPrimaryKey   aliquota_ibs_uf (uf, data_inicio)  constraintName="pk_aliquota_ibs_uf"
  sql (CHECK):    ALTER TABLE aliquota_ibs_uf ADD CONSTRAINT ck_aliquota_ibs_uf_vigencia
                  CHECK (data_fim IS NULL OR data_fim >= data_inicio)
  rollback: drop check, drop PK, addPrimaryKey(uf), dropColumn ×2

changeSet 00NN-create-aliquota-cbs
  createTable aliquota_cbs (data_inicio DATE PK, data_fim DATE NULL, aliquota NUMERIC(7,4) NOT NULL)
  CHECK idem
  insert (2026-01-01, NULL, 0.0090)
  rollback: dropTable
```

Migração de dados: o `defaultValueDate` preenche as 27 linhas existentes com `2026-01-01`/`NULL` = vigência aberta — nenhuma linha reinserida, 0013 intacta. Índice extra desnecessário (PK cobre `uf, data_inicio`).

### 3.2 Espelho H2 — `src/test/resources/schema-nota-fiscal-test.sql`

Reescrever o bloco `aliquota_ibs_uf` com as novas colunas e PK composta (o `CREATE TABLE IF NOT EXISTS` do H2 recria do zero por `create-drop`), `MERGE ... KEY (uf, data_inicio)`, e adicionar `aliquota_cbs`. Comentário "manter sincronizado com 00NN".

### 3.3 Entidades / repositórios

- `AliquotaIbsUf`: `@IdClass(AliquotaIbsUfId.class)` com `uf` + `dataInicio`; campo `dataFim` (`LocalDate`, nullable). `AliquotaIbsUfId` record-like (`Serializable`, `equals/hashCode`).
- `AliquotaIbsUfRepository extends JpaRepository<AliquotaIbsUf, AliquotaIbsUfId>`; **remover** `findByUf`; adicionar:
  ```java
  @Query("""
      SELECT a FROM AliquotaIbsUf a
      WHERE a.uf = :uf AND a.dataInicio <= :data
        AND (a.dataFim IS NULL OR a.dataFim >= :data)
      ORDER BY a.dataInicio DESC""")
  Optional<AliquotaIbsUf> findVigente(String uf, LocalDate data);   // Pageable/limit 1 via `Slice`/`setMaxResults` — usar `List` + `stream().findFirst()` se o Spring Data reclamar de Optional com múltiplas linhas
  ```
- `AliquotaCbs` (`@Id dataInicio`, `dataFim`, `aliquota`) + `AliquotaCbsRepository.findVigente(LocalDate)` (mesma query sem UF).
- `TributosProperties`: **mantido** como fallback; Javadoc passa a dizer "fallback conservador quando não há vigência na tabela".

## 4. Modelo do emitente e validações — #88

### 4.1 `Produtor`

Novo `@Embeddable EnderecoProdutor` (domínio → português; não reutiliza `EnderecoDestinatario` porque o emitente exige `bairro` e o layout tem tamanhos próprios):

| Campo | Coluna (`produtores`) | Tipo | Layout NF-e |
|-------|------------------------|------|-------------|
| `logradouro` | `endereco_logradouro` | VARCHAR(60) | `xLgr` 2–60 |
| `numero` | `endereco_numero` | VARCHAR(60) | `nro` 1–60 |
| `complemento` | `endereco_complemento` | VARCHAR(60) nullable | `xCpl` |
| `bairro` | `endereco_bairro` | VARCHAR(60) | `xBairro` 2–60 |
| `codigoMunicipioIbge` | `endereco_codigo_municipio_ibge` | VARCHAR(7) | `cMun` |
| `municipio` | `endereco_municipio` | VARCHAR(60) | `xMun` 2–60 |
| `cep` | `endereco_cep` | VARCHAR(8) | `CEP` 8 dígitos |

`estado` (UF) **permanece** na coluna existente (não entra no embeddable) — evita tocar as 31 fixtures que usam `Produtor.builder().estado(...)`. Novo campo direto `inscricaoEstadual` → `inscricao_estadual VARCHAR(14)` nullable (`TIe`: 2–14 dígitos; `ISENTO` é derivado, nunca gravado).

Todas as colunas **nullable** (rascunho e produtor existente não quebram); a obrigatoriedade é regra de emissão no `FiscalRulesValidator`.

changeSet `00NN+1-produtor-endereco-ie.xml`: `addColumn` ×8 em `produtores`; espelho no `schema-nota-fiscal-test.sql`. `DataLoader`: seed com IE `1234567890` (formato válido; não há DV padronizado nacional para IE) e endereço Cuiabá/MT (IBGE `5103403`, CEP `78005-000` → `78005000`).

Sem DTO/mapper/controller de `Produtor` nesta onda (decisão do Murillo).

### 4.2 `NotaFiscalNfeMapper`

- `buildEnderEmit`: mapeia os campos reais; **remove** todos os placeholders. `truncate(…, 60)` mantido por segurança.
- `buildEmit`: `IE = produtor.inscricaoEstadual` se presente; senão `ISENTO` **apenas** quando `nota.tipoDocumento == NFA` (para NFE o validador já bloqueou antes).
- `resolveCMunFG`: passa a devolver `produtor.endereco.codigoMunicipioIbge` (município do fato gerador = local da operação do emitente). Remove o fallback `cUF+"9999"`.
- `buildEnderDest`: remove `"0000000"`, `"SEM LOGRADOURO"`, `"MUNICIPIO"`, `"SP"` — usa os valores do domínio (validador garante presença na emissão).
- `UF_CODE` (mapa UF→cUF) sai do mapper para `common/UfIbge` (utilitário técnico, inglês OK) para ser reutilizado pelo validador Bean Validation e pelo `FiscalRulesValidator`.

### 4.3 Validações

| Onde | Regra | HTTP |
|------|-------|------|
| `NotaFiscalRequest.numero` | `@Max(999_999_999)` | 400 |
| `NotaFiscalRequest.serie` | `@Max(999)` | 400 |
| `EnderecoDestinatarioRequest` | nova anotação de classe `@CodigoIbgeCoerenteComUf` (`validation/`): se `codigoMunicipioIbge` e `uf` presentes, `codigoMunicipioIbge.substring(0,2) == UfIbge.codigo(uf)` | 400 |
| `FiscalRulesValidator.validarEmitente` | IE obrigatória se `NFE`; endereço completo (6 campos) obrigatório; prefixo IBGE = cUF de `estado`; `numero ≤ 999.999.999`; `serie ≤ 999` | 422 |
| `FiscalRulesValidator.validarDestinatario` | prefixo IBGE = cUF de `uf` (já exige presença) | 422 |
| `ChaveAcessoGenerator.zeroPad` | `value.length() > length` → `IllegalArgumentException("valor '%s' excede %d dígitos")` | — (nunca alcançado após validação; é a rede de segurança) |

`FiscalRulesValidator` agrupa todas as violações num único 422 (comportamento atual) e o `NotaFiscalValidacaoService` persiste o lote (#22) — sem mudança.

## 5. Testes que provam cada correção

| Teste | Tipo | Prova | Requisito |
|-------|------|-------|-----------|
| `CreditoTributarioServiceTest.creditoUsaAliquotaPorUfDivergenteDoYml` | Mockito | resolvedor devolve `0,0030+0,0020` com yml `0,0010` → `valorCreditoIbs = base × 0,0050`. **Escrever antes da correção e registrar a falha** | FISC-01 |
| `AliquotaIbsCbsResolverTest.debitoECreditoObtemMesmaAliquota` | Mockito | mesma nota → mesmo `AliquotasVigentes` para ambos os fluxos | FISC-01 |
| `AliquotaIbsCbsResolverTest.fallbackYmlQuandoUfAusente` / `…QuandoUfNaoCadastrada` | Mockito | `origemIbs = FALLBACK_YML`, split 50/50 | FISC-02 |
| `AliquotaIbsCbsResolverTest.notaImportadaUsaUfDoDestinatario` | Mockito | nota IMPORTADA, `destinatarioEndereco.uf = PR`, produtor `MT` → consulta `PR` | FISC-03 |
| `AliquotaIbsCbsResolverTest.selecionaVigenciaPelaDataDeEmissao` | Mockito | duas vigências MT; `dataEmissao` 2027 → 2.ª; 2026 → 1.ª | FISC-04 |
| `AliquotaIbsCbsResolverTest.converteDataEmissaoParaBrt` | Mockito | `2027-01-01T01:00Z` → consulta `2026-12-31` | FISC-04 |
| `AliquotaIbsCbsResolverTest.cbsVigenteEFallback` | Mockito | tabela presente → `TABELA_VIGENCIA`; ausente → yml | FISC-05, FISC-06 |
| `AliquotaVigenciaIntegrationTest` | Spring + H2 | insere 2.ª vigência para MT (2027); `POST /api/notas` com `dataEmissao` 2027 → `valorIbs` pela nova alíquota; nota 2026 inalterada | FISC-04, FISC-07 |
| `ApuracaoEventoDominioIntegrationTest` (existente) | Spring + H2 | continua verde sem diff em `ApuracaoService` | FISC-08 |
| `NotaFiscalNfeMapperTest.xmlComEmitenteRealValidaXsdSemPlaceholders` | JAXB + XSD | produtor completo → XML válido; asserta ausência dos 6 literais; `cMunFG == emit.cMun` | NFE-01 |
| `FiscalRulesValidatorTest.rejeitaEmitenteSemIeQuandoNfe` / `aceitaIsentoQuandoNfa` / `rejeitaEnderecoIncompleto` / `rejeitaIbgeIncoerenteComUf` | unitário | 422 com `FieldViolation` por campo | NFE-02 |
| `CodigoIbgeCoerenteComUfValidatorTest` + `NotaFiscalControllerValidationTest` | unitário + MockMvc | `MT` + `3550308` → 400 `errors[]`; omitido → aceito | NFE-03 |
| `ChaveAcessoGeneratorTest.zeroPadLancaEmOverflow` / `chaveComNnfESerieNoLimite` | unitário | `zeroPad("1234567890", 9)` lança; `999999999`/`999` → chave consistente com XML | NFE-04 |
| `NotaFiscalControllerValidationTest.numeroAcimaDoLimite` / `serieAcimaDoLimite` | MockMvc | 400 | NFE-04 |
| `DataLoaderTest` (ou asserção em `AgroContabilApplicationTests`) | Spring | produtor seed passa no `FiscalRulesValidator.validarEmitente` | NFE-01 |

Fixture compartilhada: `src/test/java/.../ProdutorTestFixtures.produtorCompleto()` — os testes de geração de XML (`GeracaoNotaFiscalServiceTest`, `GeracaoNfaFlowTest`, `NotaFiscalValidacaoFlowTest`, integrações) passam a usá-la; os 31 usos de `Produtor.builder()` que **não** emitem XML ficam intactos.

## 6. Sequência de PRs

| # | Branch | Issue | Conteúdo | Changeset |
|---|--------|-------|----------|-----------|
| 1 | `fix/86-aliquota-resolver-credito` | #86 | `AliquotaIbsCbsResolver` (ainda **sem** vigência: `findByUf`), `TributacaoService` e `CreditoTributarioService` migrados, testes de divergência (commit 1: teste vermelho; commit 2: correção) | — |
| 2 | `feat/85-aliquota-vigencia` | #85 | changeSet `00NN`, `AliquotaIbsUfId`, `AliquotaCbs` + repo, `findVigente`, resolvedor por data, espelho H2, testes de vigência | `00NN` |
| 3 | `fix/88-emitente-real-nnf` | #88 | changeSet `00NN+1`, `EnderecoProdutor`, IE, `DataLoader`, `UfIbge`, mapper sem placeholders, validador, `@Max`, `@CodigoIbgeCoerenteComUf`, `zeroPad` lança, fixture, testes XSD | `00NN+1` |

Commits Conventional (`fix(tributacao):`, `feat(tributacao):`, `fix(nfe):`), cada PR passa por code review + `./mvnw test` verde. PR 3 é o maior; se o review pedir, dividir em 3a (modelo+mapper+validador) e 3b (nNF/série/IBGE dest) — ambos deployáveis isolados.

## 7. Riscos e mitigação

| Risco | Mitigação |
|-------|-----------|
| `dropPrimaryKey` sem nome no Postgres | Liquibase resolve o nome pelo catálogo; testar a migração num Postgres local via `./mvnw spring-boot:run` antes de abrir o PR 2 (registrar no PR) |
| Spring Data + `Optional` com `ORDER BY` retornando >1 linha (sobreposição) | Assinatura `List<AliquotaIbsUf>` + `findFirst()` no resolvedor, ou `Limit.of(1)` (Spring Data 3.2+) |
| Testes existentes de XML quebram por 422 (emitente incompleto) | Fixture `produtorCompleto()` aplicada nos fluxos de emissão no mesmo PR |
| Frontend envia `numero`/`serie` fora do limite | Comportamento novo é 400 com `errors[]` já no contrato — comunicar no PR; sem mudança de payload |
| Valores de 2027+ desconhecidos | Seed só 2026 aberta; Javadoc + CLAUDE.md registram "inserir vigências por changeSet quando publicadas" |

## 8. Documentação a atualizar (no PR de cada issue)

- `CLAUDE.md`: seção de estado (Onda 2), débito "Produtor sem endereço completo nem IE" removido, novo débito "alíquotas 2027+ pendentes de publicação" e "IE de produtor sem DV".
- `.claude/skills/codebase-map`: `AliquotaIbsCbsResolver`, `AliquotaCbs`, `EnderecoProdutor`, `UfIbge`, `@CodigoIbgeCoerenteComUf`, changeSets `00NN`/`00NN+1`.
- `.claude/skills/project-conventions`: nota "alíquota nunca lida direto de `TributosProperties`/repositório — sempre via `AliquotaIbsCbsResolver`".
