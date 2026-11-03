package br.com.agrocontabil.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.RegimeIncompativelComTipoProdutorException;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Produtor pessoa física nunca em {@code SIMPLES} nem {@code MEI} (issue #298): o cadastro e a
 * troca de tipo ou de regime são recusados pela entidade; o cadastro antigo, gravado antes da
 * regra, ainda pode ser atualizado em outra coluna até ser corrigido.
 */
@DataJpaTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@DisplayName("Regime tributario do produtor pessoa fisica (#298)")
class ProdutorRegimeTributarioTest {

  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TestDatabaseCleaner cleaner;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
  }

  @ParameterizedTest
  @EnumSource(value = RegimeTributario.class, names = {"SIMPLES", "MEI"})
  @DisplayName("cadastro de pessoa fisica no Simples ou no MEI e recusado")
  void cadastroRecusado(RegimeTributario regime) {
    Produtor produtor = produtor(TipoProdutor.PESSOA_FISICA, regime);

    assertThatThrownBy(() -> produtorRepository.saveAndFlush(produtor))
        .isInstanceOf(RegimeIncompativelComTipoProdutorException.class)
        .hasMessageContaining(regime.name());
    assertThat(produtorRepository.count()).isZero();
  }

  @Test
  @DisplayName("pessoa fisica no regime normal e pessoa juridica no Simples sao aceitas")
  void combinacoesValidas() {
    produtorRepository.saveAndFlush(produtor(TipoProdutor.PESSOA_FISICA, RegimeTributario.REAL));
    produtorRepository.saveAndFlush(
        produtor(TipoProdutor.PESSOA_FISICA, RegimeTributario.PRESUMIDO));
    produtorRepository.saveAndFlush(produtor(TipoProdutor.PESSOA_FISICA, null));
    produtorRepository.saveAndFlush(
        produtor(TipoProdutor.PESSOA_JURIDICA, RegimeTributario.SIMPLES));

    assertThat(produtorRepository.count()).isEqualTo(4);
  }

  @Test
  @DisplayName("edicao que poe pessoa fisica no Simples e recusada")
  void edicaoParaSimplesRecusada() {
    UUID id = produtorRepository.saveAndFlush(
        produtor(TipoProdutor.PESSOA_FISICA, RegimeTributario.REAL)).getId();
    entityManager.clear();

    Produtor carregado = produtorRepository.findById(id).orElseThrow();
    carregado.setRegimeTributario(RegimeTributario.SIMPLES);

    assertThatThrownBy(() -> produtorRepository.saveAndFlush(carregado))
        .isInstanceOf(RegimeIncompativelComTipoProdutorException.class);
  }

  @Test
  @DisplayName("edicao que troca pessoa juridica do Simples para pessoa fisica e recusada")
  void trocaDeTipoRecusada() {
    UUID id = produtorRepository.saveAndFlush(
        produtor(TipoProdutor.PESSOA_JURIDICA, RegimeTributario.SIMPLES)).getId();
    entityManager.clear();

    Produtor carregado = produtorRepository.findById(id).orElseThrow();
    carregado.setTipoProdutor(TipoProdutor.PESSOA_FISICA);

    assertThatThrownBy(() -> produtorRepository.saveAndFlush(carregado))
        .isInstanceOf(RegimeIncompativelComTipoProdutorException.class);
  }

  @Test
  @DisplayName("cadastro antigo em SIMPLES ainda grava outra coluna; trocar so o regime corrige")
  void cadastroAntigoSegueGravavel() {
    UUID id = produtorRepository.saveAndFlush(
        produtor(TipoProdutor.PESSOA_FISICA, RegimeTributario.REAL)).getId();
    // Como na VPS: gravado antes da regra, por fora da entidade.
    jdbcTemplate.update("UPDATE produtores SET regime_tributario = 'SIMPLES' WHERE id = ?", id);
    entityManager.clear();

    Produtor antigo = produtorRepository.findById(id).orElseThrow();
    antigo.setNomeCompleto("Titular excluido (LGPD)");
    produtorRepository.saveAndFlush(antigo);
    entityManager.clear();

    Produtor corrigido = produtorRepository.findById(id).orElseThrow();
    assertThat(corrigido.getNomeCompleto()).isEqualTo("Titular excluido (LGPD)");
    corrigido.setRegimeTributario(RegimeTributario.REAL);
    produtorRepository.saveAndFlush(corrigido);

    assertThat(jdbcTemplate.queryForObject(
        "SELECT regime_tributario FROM produtores WHERE id = ?", String.class, id))
        .isEqualTo("REAL");
  }

  private static Produtor produtor(TipoProdutor tipo, RegimeTributario regime) {
    return Produtor.builder()
        .nomeCompleto("Produtor de teste")
        .cpfCnpj("%011d".formatted(ThreadLocalRandom.current().nextLong(100_000_000_000L)))
        .tipoProdutor(tipo)
        .regimeTributario(regime)
        .estado("PR")
        .build();
  }
}
