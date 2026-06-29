package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.OperationScope;
import br.com.agrocontabil.model.RuralProducerCfop;
import br.com.agrocontabil.model.AgriculturalNcm;
import br.com.agrocontabil.model.OperationType;
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
class NcmCfopReferenceRepositoryTest {

  @Autowired
  private AgriculturalNcmRepository ncmRepository;

  @Autowired
  private RuralProducerCfopRepository cfopRepository;

  @BeforeEach
  void setUp() {
    ncmRepository.deleteAll();
    cfopRepository.deleteAll();
    ncmRepository.save(AgriculturalNcm.builder()
        .codigo("12010090").description("Soja").build());
    cfopRepository.save(RuralProducerCfop.builder()
        .codigo("5101").description("Venda de producao do estabelecimento")
        .operationType(OperationType.SAIDA).scope(OperationScope.INTERNA).build());
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
    Optional<RuralProducerCfop> found = cfopRepository.findByCodigo("5101");
    assertThat(found).isPresent();
    assertThat(found.get().getOperationType()).isEqualTo(OperationType.SAIDA);
    assertThat(found.get().getScope()).isEqualTo(OperationScope.INTERNA);
  }
}
