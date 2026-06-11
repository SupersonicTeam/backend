# Validação de NCM e CFOP — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validar semanticamente NCM e CFOP nos itens de nota fiscal — existência em tabelas de referência (via Bean Validation customizado → 400) e compatibilidade CFOP × operação (via regra de negócio → 400).

**Architecture:** Duas tabelas de referência (`ncm_agropecuario`, `cfop_produtor_rural`) versionadas no Liquibase. `@ValidNcm`/`@ValidCfop` são `ConstraintValidator` Spring beans que consultam os repositórios, aplicados nos campos do DTO do item → falha no `@Valid` vira 400. A compatibilidade CFOP×operação (precisa da UF do emitente vs. destinatário) é uma regra de negócio em `CfopOperacaoValidator`, chamada no `create`/`update` do `NotaFiscalService`, lançando `CfopIncompativelException` mapeada para 400.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Data JPA, Liquibase, Bean Validation (Jakarta), JUnit 5 + Mockito + AssertJ, H2 (testes).

**Spec:** `docs/superpowers/specs/2026-06-11-validacao-ncm-cfop-design.md`

**Branch:** `sprint-2--notas-fiscais` (já existente).

---

## Notas gerais para o executor

- **Comando de teste:** `.\mvnw.cmd test` (PowerShell, Windows) ou `./mvnw test` (bash). Um só: `-Dtest=NomeDaClasse`.
- **Infra de teste do projeto (importante):** no profile `test`, **Liquibase está desabilitado** e o Hibernate usa `create-drop` (ver `src/test/resources/application-test.yml`). Logo o **seed do Liquibase não é carregado nos testes** — tabelas existem mas vazias. Testes de repositório seguem o padrão de `NotaFiscalRepositoryTest`: `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `ddl-auto=none` + `@Sql` (script H2) e semeiam os dados que precisam.
- **`@WebMvcTest` e os validators:** `NotaFiscalControllerTest` é `@WebMvcTest` (não sobe JPA). Como `@ValidNcm`/`@ValidCfop` injetam repositórios, é preciso `@MockBean` desses repositórios nesse teste para a fábrica de validators do Spring conseguir instanciá-los.
- **Bean Validation não dispara em testes de service/mapper:** eles constroem `NotaFiscalItemRequest` diretamente (sem `@Valid`), então as novas anotações não os afetam.
- **Estilo:** `@DisplayName` em português, AssertJ, Lombok como nas entidades existentes. Conventional Commits. Nunca editar changesets Liquibase já aplicados (0001-0005).
- **Mockito strict stubs:** não stubar colaborador usado só após early return.

---

## Task 1: Tabelas de referência — enums, entidades e migration 0006

**Files:**
- Create: `src/main/java/br/com/agrocontabil/model/TipoOperacao.java`
- Create: `src/main/java/br/com/agrocontabil/model/AmbitoOperacao.java`
- Create: `src/main/java/br/com/agrocontabil/model/NcmAgropecuario.java`
- Create: `src/main/java/br/com/agrocontabil/model/CfopProdutorRural.java`
- Create: `src/main/resources/db/changelog/changes/0006-ncm-cfop-referencia.xml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Criar os enums**

Create `src/main/java/br/com/agrocontabil/model/TipoOperacao.java`:

```java
package br.com.agrocontabil.model;

/** Tipo da operação fiscal de um CFOP: entrada ou saída de mercadoria. */
public enum TipoOperacao {
  ENTRADA,
  SAIDA
}
```

Create `src/main/java/br/com/agrocontabil/model/AmbitoOperacao.java`:

```java
package br.com.agrocontabil.model;

/** Âmbito territorial da operação fiscal de um CFOP. */
public enum AmbitoOperacao {
  /** Operação dentro da mesma UF (CFOP 1xxx/5xxx). */
  INTERNA,
  /** Operação entre UFs diferentes (CFOP 2xxx/6xxx). */
  INTERESTADUAL
}
```

- [ ] **Step 2: Criar a entidade `NcmAgropecuario`**

Create `src/main/java/br/com/agrocontabil/model/NcmAgropecuario.java`:

```java
package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tabela de referência de NCMs (Nomenclatura Comum do Mercosul) válidos para
 * operações agropecuárias. Dados versionados no Liquibase (changeset 0006).
 *
 * <p>Usada pela validação {@code @ValidNcm} para rejeitar itens cujo NCM não
 * esteja cadastrado para o agronegócio.
 */
@Entity
@Table(name = "ncm_agropecuario")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NcmAgropecuario {

  /** Código NCM com 8 dígitos. Chave primária. */
  @Id
  @Column(name = "codigo", length = 8, nullable = false, updatable = false)
  private String codigo;

  /** Descrição do produto/NCM. */
  @Column(name = "descricao", nullable = false)
  private String descricao;
}
```

- [ ] **Step 3: Criar a entidade `CfopProdutorRural`**

Create `src/main/java/br/com/agrocontabil/model/CfopProdutorRural.java`:

```java
package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tabela de referência de CFOPs (Código Fiscal de Operações e Prestações)
 * aplicáveis ao produtor rural. Dados versionados no Liquibase (changeset 0006).
 *
 * <p>Cada CFOP carrega seu {@link TipoOperacao} (entrada/saída) e {@link AmbitoOperacao}
 * (interna/interestadual). Usada por {@code @ValidCfop} (existência) e por
 * {@code CfopOperacaoValidator} (compatibilidade com a operação da nota).
 */
@Entity
@Table(name = "cfop_produtor_rural")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CfopProdutorRural {

  /** Código CFOP com 4 dígitos. Chave primária. */
  @Id
  @Column(name = "codigo", length = 4, nullable = false, updatable = false)
  private String codigo;

  /** Descrição da operação do CFOP. */
  @Column(name = "descricao", nullable = false)
  private String descricao;

  /** Tipo da operação (ENTRADA/SAIDA). */
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_operacao", length = 7, nullable = false,
      columnDefinition = "VARCHAR(7)")
  private TipoOperacao tipoOperacao;

  /** Âmbito territorial (INTERNA/INTERESTADUAL). */
  @Enumerated(EnumType.STRING)
  @Column(name = "ambito", length = 15, nullable = false,
      columnDefinition = "VARCHAR(15)")
  private AmbitoOperacao ambito;
}
```

- [ ] **Step 4: Criar a migration 0006 (tabelas + seed)**

Create `src/main/resources/db/changelog/changes/0006-ncm-cfop-referencia.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <!-- ================================================================
         0006-ncm-cfop-referencia.xml
         Sprint 2 — issue #20 (RN008): validacao de NCM e CFOP.

         Cria duas tabelas de referencia com seed curado:
           - ncm_agropecuario     : NCMs validos para agropecuaria
           - cfop_produtor_rural  : CFOPs aplicaveis ao produtor rural,
                                    com tipo_operacao e ambito.
         Tipos VARCHAR portaveis H2/PostgreSQL.
         NAO editar changesets anteriores (0001-0005).
         ================================================================ -->

    <changeSet id="0006-create-ncm-agropecuario" author="supersonic-team">
        <createTable tableName="ncm_agropecuario">
            <column name="codigo" type="VARCHAR(8)">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="descricao" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

    <changeSet id="0006-create-cfop-produtor-rural" author="supersonic-team">
        <createTable tableName="cfop_produtor_rural">
            <column name="codigo" type="VARCHAR(4)">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="descricao" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="tipo_operacao" type="VARCHAR(7)">
                <constraints nullable="false"/>
            </column>
            <column name="ambito" type="VARCHAR(15)">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

    <changeSet id="0006-seed-ncm-agropecuario" author="supersonic-team">
        <comment>Seed curado de NCMs agropecuarios representativos.</comment>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="12010090"/><column name="descricao" value="Soja, mesmo triturada (exceto para semeadura)"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="10059010"/><column name="descricao" value="Milho em grao"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="09011110"/><column name="descricao" value="Cafe nao torrado, nao descafeinado, em grao"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="01029000"/><column name="descricao" value="Animais vivos da especie bovina"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="04012010"/><column name="descricao" value="Leite (teor de gordura ate 6%)"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="52010010"/><column name="descricao" value="Algodao nao cardado nem penteado"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="10063021"/><column name="descricao" value="Arroz semibranqueado ou branqueado"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="10019900"/><column name="descricao" value="Trigo (exceto para semeadura)"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="12129200"/><column name="descricao" value="Cana-de-acucar"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="08043000"/><column name="descricao" value="Abacaxis frescos ou secos"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="07020000"/><column name="descricao" value="Tomates frescos ou refrigerados"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="02013000"/><column name="descricao" value="Carne bovina desossada, fresca ou refrigerada"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="12024200"/><column name="descricao" value="Amendoim sem casca"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="18010000"/><column name="descricao" value="Cacau inteiro ou partido, em bruto ou torrado"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="24011010"/><column name="descricao" value="Fumo (tabaco) nao destalado"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="44039900"/><column name="descricao" value="Madeira em bruto"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="23099010"/><column name="descricao" value="Preparacoes para alimentacao animal (racao)"/></insert>
        <insert tableName="ncm_agropecuario"><column name="codigo" value="06031100"/><column name="descricao" value="Rosas cortadas, frescas"/></insert>
    </changeSet>

    <changeSet id="0006-seed-cfop-produtor-rural" author="supersonic-team">
        <comment>Seed dos CFOPs tipicos do produtor rural com tipo de operacao e ambito.</comment>
        <!-- Saidas internas (5xxx) -->
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="5101"/><column name="descricao" value="Venda de producao do estabelecimento"/><column name="tipo_operacao" value="SAIDA"/><column name="ambito" value="INTERNA"/></insert>
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="5102"/><column name="descricao" value="Venda de mercadoria adquirida ou recebida de terceiros"/><column name="tipo_operacao" value="SAIDA"/><column name="ambito" value="INTERNA"/></insert>
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="5949"/><column name="descricao" value="Outra saida de mercadoria nao especificada"/><column name="tipo_operacao" value="SAIDA"/><column name="ambito" value="INTERNA"/></insert>
        <!-- Saidas interestaduais (6xxx) -->
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="6101"/><column name="descricao" value="Venda de producao do estabelecimento"/><column name="tipo_operacao" value="SAIDA"/><column name="ambito" value="INTERESTADUAL"/></insert>
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="6102"/><column name="descricao" value="Venda de mercadoria adquirida ou recebida de terceiros"/><column name="tipo_operacao" value="SAIDA"/><column name="ambito" value="INTERESTADUAL"/></insert>
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="6949"/><column name="descricao" value="Outra saida de mercadoria nao especificada"/><column name="tipo_operacao" value="SAIDA"/><column name="ambito" value="INTERESTADUAL"/></insert>
        <!-- Entradas internas (1xxx) -->
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="1101"/><column name="descricao" value="Compra para industrializacao ou producao rural"/><column name="tipo_operacao" value="ENTRADA"/><column name="ambito" value="INTERNA"/></insert>
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="1102"/><column name="descricao" value="Compra para comercializacao"/><column name="tipo_operacao" value="ENTRADA"/><column name="ambito" value="INTERNA"/></insert>
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="1949"/><column name="descricao" value="Outra entrada de mercadoria nao especificada"/><column name="tipo_operacao" value="ENTRADA"/><column name="ambito" value="INTERNA"/></insert>
        <!-- Entradas interestaduais (2xxx) -->
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="2101"/><column name="descricao" value="Compra para industrializacao ou producao rural"/><column name="tipo_operacao" value="ENTRADA"/><column name="ambito" value="INTERESTADUAL"/></insert>
        <insert tableName="cfop_produtor_rural"><column name="codigo" value="2102"/><column name="descricao" value="Compra para comercializacao"/><column name="tipo_operacao" value="ENTRADA"/><column name="ambito" value="INTERESTADUAL"/></insert>

        <rollback>
            <dropTable tableName="cfop_produtor_rural"/>
            <dropTable tableName="ncm_agropecuario"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

Nota: o `rollback` do último changeSet derruba ambas as tabelas (rollback agregado da feature); os changesets de `createTable` têm rollback automático (Liquibase reverte `createTable` com `dropTable`).

- [ ] **Step 5: Incluir a migration no master changelog**

Em `src/main/resources/db/changelog/db.changelog-master.yaml`, após o `include` do `0005-nota-fiscal-tipo-documento.xml`, adicione:

```yaml
  - include:
      file: db/changelog/changes/0006-ncm-cfop-referencia.xml
```

- [ ] **Step 6: Compilar e validar o contexto**

Run: `.\mvnw.cmd test -Dtest=AgroContabilApplicationTests`
Expected: PASS (o contexto sobe; as entidades mapeiam para as tabelas; no profile test o Hibernate cria as tabelas via create-drop).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/br/com/agrocontabil/model/TipoOperacao.java src/main/java/br/com/agrocontabil/model/AmbitoOperacao.java src/main/java/br/com/agrocontabil/model/NcmAgropecuario.java src/main/java/br/com/agrocontabil/model/CfopProdutorRural.java src/main/resources/db/changelog/changes/0006-ncm-cfop-referencia.xml src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(ncm-cfop): tabelas de referencia NCM agro e CFOP produtor rural (migration 0006)"
```

---

## Task 2: Repositórios + teste de repositório

**Files:**
- Create: `src/main/java/br/com/agrocontabil/repository/NcmAgropecuarioRepository.java`
- Create: `src/main/java/br/com/agrocontabil/repository/CfopProdutorRuralRepository.java`
- Create: `src/test/resources/schema-ncm-cfop-test.sql`
- Test: `src/test/java/br/com/agrocontabil/repository/NcmCfopReferenciaRepositoryTest.java`

- [ ] **Step 1: Criar os repositórios**

Create `src/main/java/br/com/agrocontabil/repository/NcmAgropecuarioRepository.java`:

```java
package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NcmAgropecuario;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositório da tabela de referência de NCMs agropecuários. */
public interface NcmAgropecuarioRepository extends JpaRepository<NcmAgropecuario, String> {

  /**
   * Indica se o NCM informado está cadastrado para agropecuária.
   *
   * @param codigo código NCM (8 dígitos)
   * @return {@code true} se existir na tabela de referência
   */
  boolean existsByCodigo(String codigo);
}
```

Create `src/main/java/br/com/agrocontabil/repository/CfopProdutorRuralRepository.java`:

```java
package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.CfopProdutorRural;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositório da tabela de referência de CFOPs do produtor rural. */
public interface CfopProdutorRuralRepository extends JpaRepository<CfopProdutorRural, String> {

  /**
   * Indica se o CFOP informado é aplicável ao produtor rural.
   *
   * @param codigo código CFOP (4 dígitos)
   * @return {@code true} se existir na tabela de referência
   */
  boolean existsByCodigo(String codigo);

  /**
   * Busca o CFOP de referência pelo código.
   *
   * @param codigo código CFOP (4 dígitos)
   * @return o CFOP com seus metadados, ou vazio se não cadastrado
   */
  Optional<CfopProdutorRural> findByCodigo(String codigo);
}
```

- [ ] **Step 2: Criar o script de schema H2 para o teste**

Segue o padrão de `schema-nota-fiscal-test.sql` (o profile test desabilita Liquibase, então o teste cria o schema via `@Sql`).

Create `src/test/resources/schema-ncm-cfop-test.sql`:

```sql
-- Schema H2-compativel para NcmCfopReferenciaRepositoryTest.
-- O profile test desabilita Liquibase; este script cria as tabelas de referencia.

CREATE TABLE IF NOT EXISTS ncm_agropecuario (
    codigo     VARCHAR(8)    NOT NULL,
    descricao  VARCHAR(255)  NOT NULL,
    PRIMARY KEY (codigo)
);

CREATE TABLE IF NOT EXISTS cfop_produtor_rural (
    codigo         VARCHAR(4)    NOT NULL,
    descricao      VARCHAR(255)  NOT NULL,
    tipo_operacao  VARCHAR(7)    NOT NULL,
    ambito         VARCHAR(15)   NOT NULL,
    PRIMARY KEY (codigo)
);
```

- [ ] **Step 3: Escrever o teste de repositório**

Create `src/test/java/br/com/agrocontabil/repository/NcmCfopReferenciaRepositoryTest.java`:

```java
package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.AmbitoOperacao;
import br.com.agrocontabil.model.CfopProdutorRural;
import br.com.agrocontabil.model.NcmAgropecuario;
import br.com.agrocontabil.model.TipoOperacao;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Testes de integração dos repositórios de referência NCM/CFOP.
 *
 * <p>H2 em memória (profile test). Schema criado via {@code @Sql} (Liquibase está
 * desabilitado no profile test); o seed é inserido pelo próprio teste via repositório.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-ncm-cfop-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS
)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class NcmCfopReferenciaRepositoryTest {

  @Autowired
  private NcmAgropecuarioRepository ncmRepository;

  @Autowired
  private CfopProdutorRuralRepository cfopRepository;

  @BeforeEach
  void setUp() {
    ncmRepository.deleteAll();
    cfopRepository.deleteAll();
    ncmRepository.save(NcmAgropecuario.builder()
        .codigo("12010090").descricao("Soja").build());
    cfopRepository.save(CfopProdutorRural.builder()
        .codigo("5101").descricao("Venda de producao do estabelecimento")
        .tipoOperacao(TipoOperacao.SAIDA).ambito(AmbitoOperacao.INTERNA).build());
  }

  @Test
  @DisplayName("existsByCodigo retorna true para NCM cadastrado e false para inexistente")
  void existsNcm() {
    assertThat(ncmRepository.existsByCodigo("12010090")).isTrue();
    assertThat(ncmRepository.existsByCodigo("99999999")).isFalse();
  }

  @Test
  @DisplayName("existsByCodigo retorna true para CFOP cadastrado e false para inexistente")
  void existsCfop() {
    assertThat(cfopRepository.existsByCodigo("5101")).isTrue();
    assertThat(cfopRepository.existsByCodigo("9999")).isFalse();
  }

  @Test
  @DisplayName("findByCodigo recupera os metadados do CFOP")
  void findCfop() {
    Optional<CfopProdutorRural> found = cfopRepository.findByCodigo("5101");
    assertThat(found).isPresent();
    assertThat(found.get().getTipoOperacao()).isEqualTo(TipoOperacao.SAIDA);
    assertThat(found.get().getAmbito()).isEqualTo(AmbitoOperacao.INTERNA);
  }
}
```

- [ ] **Step 4: Rodar o teste**

Run: `.\mvnw.cmd test -Dtest=NcmCfopReferenciaRepositoryTest`
Expected: PASS (3 cenários). Se a fábrica de schema reclamar, confirme que o `@Sql` aponta para `classpath:schema-ncm-cfop-test.sql` e que os tipos batem com as entidades.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/repository/NcmAgropecuarioRepository.java src/main/java/br/com/agrocontabil/repository/CfopProdutorRuralRepository.java src/test/resources/schema-ncm-cfop-test.sql src/test/java/br/com/agrocontabil/repository/NcmCfopReferenciaRepositoryTest.java
git commit -m "feat(ncm-cfop): repositorios de referencia + teste de integracao"
```

---

## Task 3: `@ValidNcm` (Bean Validation customizado)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/common/validation/ValidNcm.java`
- Create: `src/main/java/br/com/agrocontabil/common/validation/NcmValidator.java`
- Test: `src/test/java/br/com/agrocontabil/common/validation/NcmValidatorTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/common/validation/NcmValidatorTest.java`:

```java
package br.com.agrocontabil.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.repository.NcmAgropecuarioRepository;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("NcmValidator — constraint @ValidNcm")
class NcmValidatorTest {

  @Mock
  private NcmAgropecuarioRepository repository;

  private NcmValidator validator;
  private ConstraintValidatorContext context;

  @BeforeEach
  void setUp() {
    validator = new NcmValidator(repository);
    context = Mockito.mock(ConstraintValidatorContext.class);
  }

  @Test
  @DisplayName("valido quando o NCM existe na tabela de referencia")
  void ncmExistente() {
    when(repository.existsByCodigo("12010090")).thenReturn(true);
    assertThat(validator.isValid("12010090", context)).isTrue();
  }

  @Test
  @DisplayName("invalido quando o NCM nao existe na tabela de referencia")
  void ncmInexistente() {
    when(repository.existsByCodigo("99999999")).thenReturn(false);
    assertThat(validator.isValid("99999999", context)).isFalse();
  }

  @Test
  @DisplayName("valido para null/blank (obrigatoriedade fica a cargo de @NotBlank)")
  void nullOuBlank() {
    assertThat(validator.isValid(null, context)).isTrue();
    assertThat(validator.isValid("   ", context)).isTrue();
  }
}
```

- [ ] **Step 2: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=NcmValidatorTest`
Expected: FAIL (compilação: `ValidNcm`/`NcmValidator` não existem).

- [ ] **Step 3: Criar a anotação `@ValidNcm`**

Create `src/main/java/br/com/agrocontabil/common/validation/ValidNcm.java`:

```java
package br.com.agrocontabil.common.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que o NCM informado está cadastrado na tabela de NCMs agropecuários.
 *
 * <p>{@code null}/blank são considerados válidos — combine com {@code @NotBlank}
 * quando obrigatório. A validação de formato (8 dígitos) deve ser feita por
 * {@code @Pattern} separadamente.
 */
@Documented
@Constraint(validatedBy = NcmValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidNcm {

  String message() default "NCM nao cadastrado para agropecuaria";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
```

- [ ] **Step 4: Criar o `NcmValidator`**

Create `src/main/java/br/com/agrocontabil/common/validation/NcmValidator.java`:

```java
package br.com.agrocontabil.common.validation;

import br.com.agrocontabil.repository.NcmAgropecuarioRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementa {@link ValidNcm} consultando a tabela de referência via
 * {@link NcmAgropecuarioRepository}. Instanciado pela fábrica de validators do
 * Spring com injeção de construtor (o repositório vem do contexto).
 *
 * <p>{@code null}/blank → válido (defere ao {@code @NotBlank}).
 */
public class NcmValidator implements ConstraintValidator<ValidNcm, String> {

  private final NcmAgropecuarioRepository repository;

  public NcmValidator(NcmAgropecuarioRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      return true;
    }
    return repository.existsByCodigo(value);
  }
}
```

- [ ] **Step 5: Rodar, confirmar que passa**

Run: `.\mvnw.cmd test -Dtest=NcmValidatorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/common/validation/ValidNcm.java src/main/java/br/com/agrocontabil/common/validation/NcmValidator.java src/test/java/br/com/agrocontabil/common/validation/NcmValidatorTest.java
git commit -m "feat(ncm-cfop): constraint @ValidNcm valida existencia do NCM"
```

---

## Task 4: `@ValidCfop` (Bean Validation customizado)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/common/validation/ValidCfop.java`
- Create: `src/main/java/br/com/agrocontabil/common/validation/CfopValidator.java`
- Test: `src/test/java/br/com/agrocontabil/common/validation/CfopValidatorTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/common/validation/CfopValidatorTest.java`:

```java
package br.com.agrocontabil.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.repository.CfopProdutorRuralRepository;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CfopValidator — constraint @ValidCfop")
class CfopValidatorTest {

  @Mock
  private CfopProdutorRuralRepository repository;

  private CfopValidator validator;
  private ConstraintValidatorContext context;

  @BeforeEach
  void setUp() {
    validator = new CfopValidator(repository);
    context = Mockito.mock(ConstraintValidatorContext.class);
  }

  @Test
  @DisplayName("valido quando o CFOP existe na tabela de referencia")
  void cfopExistente() {
    when(repository.existsByCodigo("5101")).thenReturn(true);
    assertThat(validator.isValid("5101", context)).isTrue();
  }

  @Test
  @DisplayName("invalido quando o CFOP nao existe na tabela de referencia")
  void cfopInexistente() {
    when(repository.existsByCodigo("9999")).thenReturn(false);
    assertThat(validator.isValid("9999", context)).isFalse();
  }

  @Test
  @DisplayName("valido para null/blank (obrigatoriedade fica a cargo de @NotBlank)")
  void nullOuBlank() {
    assertThat(validator.isValid(null, context)).isTrue();
    assertThat(validator.isValid("   ", context)).isTrue();
  }
}
```

- [ ] **Step 2: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=CfopValidatorTest`
Expected: FAIL (compilação).

- [ ] **Step 3: Criar a anotação `@ValidCfop`**

Create `src/main/java/br/com/agrocontabil/common/validation/ValidCfop.java`:

```java
package br.com.agrocontabil.common.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que o CFOP informado está cadastrado na tabela de CFOPs aplicáveis ao
 * produtor rural.
 *
 * <p>{@code null}/blank são considerados válidos — combine com {@code @NotBlank}.
 * A validação de formato (4 dígitos) é feita por {@code @Pattern}. A compatibilidade
 * com o tipo de operação é validada separadamente como regra de negócio
 * ({@code CfopOperacaoValidator}).
 */
@Documented
@Constraint(validatedBy = CfopValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCfop {

  String message() default "CFOP nao aplicavel ao produtor rural";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
```

- [ ] **Step 4: Criar o `CfopValidator`**

Create `src/main/java/br/com/agrocontabil/common/validation/CfopValidator.java`:

```java
package br.com.agrocontabil.common.validation;

import br.com.agrocontabil.repository.CfopProdutorRuralRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementa {@link ValidCfop} consultando a tabela de referência via
 * {@link CfopProdutorRuralRepository}. Injeção de construtor pela fábrica de
 * validators do Spring. {@code null}/blank → válido.
 */
public class CfopValidator implements ConstraintValidator<ValidCfop, String> {

  private final CfopProdutorRuralRepository repository;

  public CfopValidator(CfopProdutorRuralRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      return true;
    }
    return repository.existsByCodigo(value);
  }
}
```

- [ ] **Step 5: Rodar, confirmar que passa**

Run: `.\mvnw.cmd test -Dtest=CfopValidatorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/common/validation/ValidCfop.java src/main/java/br/com/agrocontabil/common/validation/CfopValidator.java src/test/java/br/com/agrocontabil/common/validation/CfopValidatorTest.java
git commit -m "feat(ncm-cfop): constraint @ValidCfop valida aplicabilidade do CFOP"
```

---

## Task 5: Aplicar `@ValidNcm`/`@ValidCfop` no DTO e ajustar o teste do controller

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/dto/NotaFiscalItemRequest.java`
- Modify: `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`

- [ ] **Step 1: READ `NotaFiscalControllerTest.java` inteiro**

É `@WebMvcTest` (não sobe JPA). Já tem `@MockBean ProdutorRepository`. Os novos validators injetam os repositórios de referência, então é preciso mocká-los aqui também, senão a fábrica de validators falha ao instanciá-los durante o POST com `@Valid`.

- [ ] **Step 2: Adicionar as anotações ao DTO**

Em `src/main/java/br/com/agrocontabil/dto/NotaFiscalItemRequest.java`:

(a) Imports:
```java
import br.com.agrocontabil.common.validation.ValidCfop;
import br.com.agrocontabil.common.validation.ValidNcm;
```

(b) No campo `ncm`, adicione `@ValidNcm` junto às anotações existentes (`@NotBlank`, `@Pattern`):
```java
    @Schema(description = "Codigo NCM com 8 digitos numericos", example = "12010090")
    @NotBlank(message = "ncm e obrigatorio")
    @Pattern(regexp = "\\d{8}", message = "ncm deve ter exatamente 8 digitos numericos")
    @ValidNcm
    String ncm,
```

(c) No campo `cfop`, adicione `@ValidCfop`:
```java
    @Schema(description = "CFOP — Codigo Fiscal de Operacoes e Prestacoes (4 digitos)",
        example = "6101")
    @NotBlank(message = "cfop e obrigatorio")
    @Pattern(regexp = "\\d{4}", message = "cfop deve ter exatamente 4 digitos numericos")
    @ValidCfop
    String cfop,
```

- [ ] **Step 3: Adicionar os `@MockBean` dos repositórios e estabilizar o happy-path no teste do controller**

Em `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`:

(a) Imports:
```java
import br.com.agrocontabil.repository.NcmAgropecuarioRepository;
import br.com.agrocontabil.repository.CfopProdutorRuralRepository;
```

(b) Adicione dois `@MockBean` junto ao `produtorRepository`:
```java
  @MockBean
  private NcmAgropecuarioRepository ncmAgropecuarioRepository;

  @MockBean
  private CfopProdutorRuralRepository cfopProdutorRuralRepository;
```

(c) No `@BeforeEach` (ou no início do teste de POST válido), faça os mocks aceitarem qualquer NCM/CFOP como válido por padrão, para que o caminho feliz continue 201:
```java
    when(ncmAgropecuarioRepository.existsByCodigo(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    when(cfopProdutorRuralRepository.existsByCodigo(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
```
> Se o teste usa Mockito strict stubs e algum teste não dispara esses stubs, marque-os com `lenient()` (`import static org.mockito.Mockito.lenient;`) ou mova a stubagem para os testes que realmente fazem POST. Rode a classe e ajuste conforme necessário.

> O teste existente de POST inválido (NCM com 7 dígitos) continua retornando 400 pelo `@Pattern`; com os mocks acima, `@ValidNcm` não adiciona violação extra para o caminho feliz. Confirme que as asserções `jsonPath` existentes ainda batem (o `@Pattern` é a violação relevante para o NCM de 7 dígitos).

- [ ] **Step 4: Adicionar teste de 400 para NCM não cadastrado (prova do DoD via HTTP)**

Ainda em `NotaFiscalControllerTest.java`, adicione um teste. Ele monta um request válido em formato (NCM com 8 dígitos), mas o repositório mockado diz que o NCM não existe → `@ValidNcm` falha → 400. READ os helpers existentes da classe (há um `buildRequestJson`/`new NotaFiscalRequest(...)` e um `objectMapper`) e reutilize-os; o esqueleto:

```java
  @Test
  @DisplayName("POST com NCM nao cadastrado retorna 400")
  void postComNcmNaoCadastradoRetorna400() throws Exception {
    // NCM bem formado (8 digitos) porem ausente da tabela de referencia
    when(ncmAgropecuarioRepository.existsByCodigo("00000000")).thenReturn(false);
    when(cfopProdutorRuralRepository.existsByCodigo(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);

    // Reutilize o helper que monta o JSON de um request valido, trocando o ncm do item
    // para "00000000". Se o helper existente nao permitir, construa o NotaFiscalRequest
    // inline com um item cujo ncm = "00000000" e serialize com objectMapper.
    String json = /* JSON do request com itens[0].ncm = "00000000" */ buildRequestJsonComNcm("00000000");

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
        .andExpect(status().isBadRequest());
  }
```

> IMPORTANTE: não invente helper. Inspecione a classe: ela já tem um padrão para montar o corpo do POST (provavelmente via `objectMapper.writeValueAsString(new NotaFiscalRequest(...))`). Construa o request com um item cujo `ncm = "00000000"` e `cfop` válido (ex.: `"6101"`), serialize e poste. Os demais `existsByCodigo` devem retornar true para isolar a falha no NCM. Ajuste o nome/refatoração conforme o que existe na classe.

- [ ] **Step 5: Rodar o teste do controller**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalControllerTest`
Expected: PASS — happy-path 201 (com NCM/CFOP mockados como válidos), POST inválido por formato 400, e o novo POST com NCM não cadastrado 400.

> Se a fábrica de validators reclamar que não encontra o bean do repositório, confirme que os `@MockBean` foram adicionados. Se houver `UnnecessaryStubbingException`, mova as stubagens `existsByCodigo` para os testes que de fato fazem POST, ou use `lenient()`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/dto/NotaFiscalItemRequest.java src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java
git commit -m "feat(ncm-cfop): aplica @ValidNcm/@ValidCfop no item; NCM invalido retorna 400"
```

---

## Task 6: `CfopIncompativelException` + mapeamento para 400

**Files:**
- Create: `src/main/java/br/com/agrocontabil/exception/CfopIncompativelException.java`
- Modify: `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java`
- Modify: `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`

- [ ] **Step 1: Criar a exceção**

Create `src/main/java/br/com/agrocontabil/exception/CfopIncompativelException.java`:

```java
package br.com.agrocontabil.exception;

/**
 * Lançada quando o CFOP de um item é incompatível com o tipo de operação da nota
 * (entrada/saída ou âmbito interno/interestadual).
 *
 * <p>Diferente das exceções de emissão (422), esta é uma falha de validação do
 * request de criação/atualização — mapeada para HTTP 400 pelo
 * {@code GlobalExceptionHandler}. A mensagem é descritiva e segura para o cliente
 * (não contém dado sensível).
 *
 * @see br.com.agrocontabil.service.CfopOperacaoValidator
 */
public class CfopIncompativelException extends RuntimeException {

  public CfopIncompativelException(String message) {
    super(message);
  }
}
```

- [ ] **Step 2: Mapear para 400 no `GlobalExceptionHandler`**

Em `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java`, adicione um handler (junto aos demais; antes do `handleIllegalArgument` é um bom lugar):

```java
    /**
     * CFOP incompativel com o tipo de operacao da nota (entrada/saida, ambito).
     * HTTP 400 Bad Request, com a mensagem descritiva da regra de negocio.
     */
    @ExceptionHandler(CfopIncompativelException.class)
    public ResponseEntity<ApiError> handleCfopIncompativel(
            CfopIncompativelException ex, HttpServletRequest request) {
        log.warn("CFOP incompativel: uri={} detalhe={}",
                request.getRequestURI(), ex.getMessage());
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }
```

`CfopIncompativelException` está no mesmo pacote — sem import. Os demais tipos já estão importados.

- [ ] **Step 3: Teste de controller — serviço lança → 400 (prova do DoD via HTTP)**

Em `NotaFiscalControllerTest.java`, adicione um teste onde o `notaFiscalService.create(...)` mockado lança `CfopIncompativelException`, e o controller (com `GlobalExceptionHandler` importado) responde 400. Reutilize o helper de montagem de request válido e os mocks de repositório como válidos (true):

```java
  @Test
  @DisplayName("POST cujo servico lanca CfopIncompativelException retorna 400")
  void postComCfopIncompativelRetorna400() throws Exception {
    when(ncmAgropecuarioRepository.existsByCodigo(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    when(cfopProdutorRuralRepository.existsByCodigo(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    when(notaFiscalService.create(any()))
        .thenThrow(new br.com.agrocontabil.exception.CfopIncompativelException(
            "Item 1: CFOP 5101 (INTERNA) incompativel com operacao INTERESTADUAL."));

    String json = /* JSON de um request valido (use o helper existente) */ buildRequestJsonValido();

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("incompativel")));
  }
```

> Use o padrão de montagem de JSON que já existe na classe. O ponto é: serviço lança a exceção → handler → 400 com a mensagem.

- [ ] **Step 4: Rodar o teste**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalControllerTest`
Expected: PASS (incluindo o novo teste 400 do CFOP).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/exception/CfopIncompativelException.java src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java
git commit -m "feat(ncm-cfop): CfopIncompativelException mapeada para 400"
```

---

## Task 7: `CfopOperacaoValidator` (regra de negócio CFOP × operação)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/service/CfopOperacaoValidator.java`
- Test: `src/test/java/br/com/agrocontabil/service/CfopOperacaoValidatorTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/service/CfopOperacaoValidatorTest.java`:

```java
package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.exception.CfopIncompativelException;
import br.com.agrocontabil.model.AmbitoOperacao;
import br.com.agrocontabil.model.CfopProdutorRural;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoOperacao;
import br.com.agrocontabil.repository.CfopProdutorRuralRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CfopOperacaoValidator — compatibilidade CFOP x operacao")
class CfopOperacaoValidatorTest {

  @Mock
  private CfopProdutorRuralRepository repository;

  private CfopOperacaoValidator validator;

  @BeforeEach
  void setUp() {
    validator = new CfopOperacaoValidator(repository);
  }

  private CfopProdutorRural cfop(String codigo, TipoOperacao tipo, AmbitoOperacao ambito) {
    return CfopProdutorRural.builder()
        .codigo(codigo).descricao("desc").tipoOperacao(tipo).ambito(ambito).build();
  }

  private NotaFiscal nota(String ufEmit, String ufDest, String cfopItem) {
    Produtor produtor = Produtor.builder().id(UUID.randomUUID()).estado(ufEmit).build();
    EnderecoDestinatario end = ufDest == null ? null
        : EnderecoDestinatario.builder().uf(ufDest).build();
    NotaFiscalItem item = NotaFiscalItem.builder()
        .numeroItem(1).cfop(cfopItem).build();
    NotaFiscal n = NotaFiscal.builder()
        .id(UUID.randomUUID()).produtor(produtor).destinatarioEndereco(end).build();
    n.addItem(item);
    return n;
  }

  @Test
  @DisplayName("operacao interna com CFOP 5xxx (saida interna) e valida")
  void saidaInternaOk() {
    when(repository.findByCodigo("5101"))
        .thenReturn(Optional.of(cfop("5101", TipoOperacao.SAIDA, AmbitoOperacao.INTERNA)));
    assertThatCode(() -> validator.validar(nota("PR", "PR", "5101"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("CFOP interno (5xxx) em venda interestadual lanca CfopIncompativelException")
  void internoEmInterestadual() {
    when(repository.findByCodigo("5101"))
        .thenReturn(Optional.of(cfop("5101", TipoOperacao.SAIDA, AmbitoOperacao.INTERNA)));
    assertThatThrownBy(() -> validator.validar(nota("PR", "SP", "5101")))
        .isInstanceOf(CfopIncompativelException.class);
  }

  @Test
  @DisplayName("CFOP de entrada (1xxx) numa saida lanca CfopIncompativelException")
  void entradaEmSaida() {
    when(repository.findByCodigo("1101"))
        .thenReturn(Optional.of(cfop("1101", TipoOperacao.ENTRADA, AmbitoOperacao.INTERNA)));
    assertThatThrownBy(() -> validator.validar(nota("PR", "PR", "1101")))
        .isInstanceOf(CfopIncompativelException.class);
  }

  @Test
  @DisplayName("UF do destinatario ausente: pula checagem de ambito, mas exige saida")
  void ufDestAusentePulaAmbito() {
    when(repository.findByCodigo("5101"))
        .thenReturn(Optional.of(cfop("5101", TipoOperacao.SAIDA, AmbitoOperacao.INTERNA)));
    // sem UF de destino -> ambito indeterminavel -> nao lanca por ambito
    assertThatCode(() -> validator.validar(nota("PR", null, "5101"))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("CFOP nao encontrado na referencia e ignorado (existencia ja validada por @ValidCfop)")
  void cfopNaoEncontradoIgnorado() {
    when(repository.findByCodigo("9999")).thenReturn(Optional.empty());
    assertThatCode(() -> validator.validar(nota("PR", "PR", "9999"))).doesNotThrowAnyException();
  }
}
```

- [ ] **Step 2: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=CfopOperacaoValidatorTest`
Expected: FAIL (compilação: `CfopOperacaoValidator` não existe).

- [ ] **Step 3: Implementar `CfopOperacaoValidator`**

Create `src/main/java/br/com/agrocontabil/service/CfopOperacaoValidator.java`:

```java
package br.com.agrocontabil.service;

import br.com.agrocontabil.exception.CfopIncompativelException;
import br.com.agrocontabil.model.AmbitoOperacao;
import br.com.agrocontabil.model.CfopProdutorRural;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoOperacao;
import br.com.agrocontabil.repository.CfopProdutorRuralRepository;
import org.springframework.stereotype.Component;

/**
 * Valida a compatibilidade do CFOP de cada item com o tipo de operação da nota.
 *
 * <p>O modelo atual emite sempre <strong>saída</strong> ({@code tpNF=1}); o âmbito é
 * derivado da UF do emitente (produtor) versus a UF do destinatário:
 * {@link AmbitoOperacao#INTERNA} se iguais, {@link AmbitoOperacao#INTERESTADUAL} se
 * diferentes. Para cada item, carrega os metadados do CFOP e exige:
 * <ul>
 *   <li>{@code tipoOperacao == SAIDA} (rejeita CFOP de entrada — 1xxx/2xxx — numa saída);</li>
 *   <li>{@code ambito} igual ao esperado, quando determinável.</li>
 * </ul>
 *
 * <p>Se a UF do emitente ou do destinatário estiver ausente (endereço opcional em
 * rascunho), o âmbito é indeterminável e a checagem de âmbito é ignorada (mantém-se a
 * de tipo de operação). A existência do CFOP na referência é responsabilidade de
 * {@code @ValidCfop} no request; aqui, CFOP ausente é ignorado.
 *
 * <p>Viola­ções lançam {@link CfopIncompativelException} (HTTP 400) com mensagem descritiva.
 */
@Component
public class CfopOperacaoValidator {

  private final CfopProdutorRuralRepository repository;

  public CfopOperacaoValidator(CfopProdutorRuralRepository repository) {
    this.repository = repository;
  }

  /**
   * Valida todos os itens da nota quanto à compatibilidade CFOP × operação.
   *
   * @param nota nota fiscal com produtor, endereço do destinatário e itens
   * @throws CfopIncompativelException se algum item tiver CFOP incompatível
   */
  public void validar(NotaFiscal nota) {
    AmbitoOperacao ambitoEsperado = resolverAmbito(nota);

    for (NotaFiscalItem item : nota.getItens()) {
      CfopProdutorRural cfop = repository.findByCodigo(item.getCfop()).orElse(null);
      if (cfop == null) {
        continue; // existência tratada por @ValidCfop no request
      }

      if (cfop.getTipoOperacao() != TipoOperacao.SAIDA) {
        throw new CfopIncompativelException(
            "Item " + item.getNumeroItem() + ": CFOP " + item.getCfop()
                + " e de operacao de ENTRADA, incompativel com a saida da nota fiscal. "
                + "Use um CFOP de saida (5xxx interna / 6xxx interestadual).");
      }

      if (ambitoEsperado != null && cfop.getAmbito() != ambitoEsperado) {
        String sugestao = ambitoEsperado == AmbitoOperacao.INTERNA ? "5xxx" : "6xxx";
        throw new CfopIncompativelException(
            "Item " + item.getNumeroItem() + ": CFOP " + item.getCfop()
                + " (" + cfop.getAmbito() + ") incompativel com operacao " + ambitoEsperado
                + ". Use um CFOP " + sugestao + ".");
      }
    }
  }

  /**
   * Determina o âmbito esperado a partir das UFs do emitente e do destinatário.
   *
   * @return {@code INTERNA}/{@code INTERESTADUAL}, ou {@code null} se indeterminável
   */
  private AmbitoOperacao resolverAmbito(NotaFiscal nota) {
    Produtor produtor = nota.getProdutor();
    String ufEmit = produtor != null ? produtor.getEstado() : null;
    String ufDest = nota.getDestinatarioEndereco() != null
        ? nota.getDestinatarioEndereco().getUf() : null;

    if (ufEmit == null || ufEmit.isBlank() || ufDest == null || ufDest.isBlank()) {
      return null;
    }
    return ufEmit.equalsIgnoreCase(ufDest)
        ? AmbitoOperacao.INTERNA : AmbitoOperacao.INTERESTADUAL;
  }
}
```

- [ ] **Step 4: Rodar, confirmar que passa**

Run: `.\mvnw.cmd test -Dtest=CfopOperacaoValidatorTest`
Expected: PASS (5 cenários).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/service/CfopOperacaoValidator.java src/test/java/br/com/agrocontabil/service/CfopOperacaoValidatorTest.java
git commit -m "feat(ncm-cfop): CfopOperacaoValidator valida CFOP x tipo de operacao"
```

---

## Task 8: Integrar `CfopOperacaoValidator` no `NotaFiscalService` + verificação final

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/service/NotaFiscalService.java`
- Modify: `src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java`

- [ ] **Step 1: READ ambos os arquivos**

`NotaFiscalService` tem `create(NotaFiscalRequest)` e `update(UUID, NotaFiscalRequest)`. `NotaFiscalServiceTest` usa `@ExtendWith(MockitoExtension.class)` + `@InjectMocks` + mocks dos colaboradores. Adicionar um colaborador ao construtor exige um `@Mock` novo lá.

- [ ] **Step 2: Escrever os testes que falham**

Em `src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java`:

(a) Adicione o `@Mock`:
```java
  @Mock
  private CfopOperacaoValidator cfopOperacaoValidator;
```

(b) Imports (se faltarem): `import br.com.agrocontabil.exception.CfopIncompativelException;` e (provavelmente já presentes) `org.mockito.Mockito.doThrow`, `any`.

(c) Adicione dois testes (use os helpers de request/produtor já existentes na classe — há um helper que monta `NotaFiscalRequest` e o `produtor`/`produtorId` do `setUp`):

```java
  @Test
  @DisplayName("create chama o CfopOperacaoValidator")
  void createChamaCfopValidator() {
    NotaFiscalRequest request = /* helper existente que monta um request valido */ buildValidRequest();
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.save(any(NotaFiscal.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    notaFiscalService.create(request);

    verify(cfopOperacaoValidator).validar(any(NotaFiscal.class));
  }

  @Test
  @DisplayName("create propaga CfopIncompativelException quando o CFOP e incompativel")
  void createPropagaCfopIncompativel() {
    NotaFiscalRequest request = /* helper existente */ buildValidRequest();
    when(produtorProvider.get()).thenReturn(produtor);
    org.mockito.Mockito.doThrow(new CfopIncompativelException("incompativel"))
        .when(cfopOperacaoValidator).validar(any(NotaFiscal.class));

    assertThatThrownBy(() -> notaFiscalService.create(request))
        .isInstanceOf(CfopIncompativelException.class);

    verify(notaFiscalRepository, never()).save(any(NotaFiscal.class));
  }
```

> Use o helper de request já existente na classe (NÃO invente). No segundo teste, como a exceção é lançada antes do `save`, NÃO stube `notaFiscalRepository.save` (strict stubs) e verifique `never()` no save. Se o `calculoIbsCbsService` for chamado antes do validator, posicione a chamada do validator no serviço (Step 3) ANTES do `save` mas a ordem relativa ao cálculo não afeta os testes; ajuste stubs conforme o necessário e rode.

- [ ] **Step 3: Integrar no serviço**

Em `src/main/java/br/com/agrocontabil/service/NotaFiscalService.java`:

(a) Import: `import br.com.agrocontabil.service.CfopOperacaoValidator;` não é necessário (mesmo pacote `service`). Nenhum import novo.

(b) Adicione o campo final (após `geracaoNfaService`):
```java
  private final CfopOperacaoValidator cfopOperacaoValidator;
```

(c) Adicione o parâmetro ao construtor (no FINAL da lista) e a atribuição:
```java
      GeracaoNfaService geracaoNfaService,
      CfopOperacaoValidator cfopOperacaoValidator) {
    ...
    this.geracaoNfaService = geracaoNfaService;
    this.cfopOperacaoValidator = cfopOperacaoValidator;
  }
```

(d) Em `create`, após `calculoIbsCbsService.calcular(nota);` e antes de `notaFiscalRepository.save(nota)`:
```java
    cfopOperacaoValidator.validar(nota);
```

(e) Em `update`, após `calculoIbsCbsService.calcular(nota);` e antes de `notaFiscalRepository.save(nota)`:
```java
    cfopOperacaoValidator.validar(nota);
```

- [ ] **Step 4: Rodar o teste do serviço**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalServiceTest`
Expected: PASS — testes pré-existentes (o validator mockado é no-op) + os dois novos. Se algum teste pré-existente de `create`/`update` quebrar por causa de strict stubs no validator, lembre-se: chamada de método void não-stubado em mock não gera erro; só stub não usado gera. Ajuste se necessário.

- [ ] **Step 5: Rodar a suíte completa (verificação final)**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS — todos os testes verdes (pré-existentes + novos).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/service/NotaFiscalService.java src/test/java/br/com/agrocontabil/service/NotaFiscalServiceTest.java
git commit -m "feat(ncm-cfop): NotaFiscalService valida CFOP x operacao no create/update"
```

---

## Self-Review (preenchido pelo autor do plano)

**Cobertura do spec:**
- Migration com tabela de NCMs válidos → Task 1 ✓
- Migration com tabela de CFOPs do produtor rural → Task 1 ✓
- `@ValidNcm`/`@ValidCfop` como Bean Validation customizados → Tasks 3, 4 (aplicados no DTO em Task 5) ✓
- Regra CFOP compatível com tipo de operação (entrada/saída, intra/inter) → Task 7 ✓
- Exceção de negócio com mensagem descritiva → Task 6 (`CfopIncompativelException`), lançada em Task 7 ✓
- DoD "NCM inválido → 400 com mensagem" → Task 5 (teste HTTP via @WebMvcTest) ✓
- DoD "CFOP incompatível → 400" → Task 6 (teste HTTP) + Task 7 (regra) ✓

**Consistência de tipos/assinaturas:**
- `NcmAgropecuarioRepository.existsByCodigo(String)` definido na Task 2, usado nas Tasks 3 e 5 ✓
- `CfopProdutorRuralRepository.existsByCodigo/findByCodigo` definidos na Task 2, usados nas Tasks 4, 5, 7 ✓
- `CfopOperacaoValidator.validar(NotaFiscal)` definido na Task 7, usado na Task 8 ✓
- `CfopIncompativelException(String)` definido na Task 6, lançado na Task 7, testado nas Tasks 6 e 8 ✓
- Enums `TipoOperacao`/`AmbitoOperacao` e entidades consistentes em todas as tasks ✓
- Colunas da migration 0006 (`tipo_operacao VARCHAR(7)`, `ambito VARCHAR(15)`) batem com `@Column` das entidades (validate em prod) ✓

**Placeholders:** os trechos de teste do controller (Tasks 5 e 6) instruem reutilizar o helper de montagem de request existente em vez de inventar um — isso é deliberado (o executor deve inspecionar a classe), não um placeholder de código de produção. Todo código de produção está completo.

**Riscos sinalizados ao executor:**
- Strict stubs no `NotaFiscalControllerTest`/`NotaFiscalServiceTest` ao adicionar mocks dos repositórios/validator — instruções de mitigação (`lenient()`, posicionamento de stubs) incluídas.
- Fábrica de validators do Spring precisa dos repositórios como beans no slice `@WebMvcTest` — `@MockBean` instruído na Task 5.
