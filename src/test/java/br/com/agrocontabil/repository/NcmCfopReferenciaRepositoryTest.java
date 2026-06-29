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
