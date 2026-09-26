package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.NotaFiscalItemRequest;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Teste de integração (issue relatada em produção — PUT /api/notas/{id} 500) contra H2 real
 * (profile {@code test}).
 *
 * <p>Diferente do {@link NotaFiscalServiceTest} (Mockito puro, sem flush real do JPA), este
 * sobe o contexto Spring completo e exercita o flush real do Hibernate — é o único jeito de
 * provar a ordem INSERT/DELETE da {@code ActionQueue} que causava a violação da constraint
 * única {@code uk_nota_fiscal_item_nota_numero} ao editar um item reaproveitando o mesmo
 * {@code numeroItem}.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class NotaFiscalUpdateItemFlowTest {

  @Autowired
  private NotaFiscalService notaFiscalService;

  @Autowired
  private NotaFiscalRepository notaFiscalRepository;

  @Autowired
  private ProdutorRepository produtorRepository;

  @Autowired
  private TestDatabaseCleaner cleaner;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Maria Agricultora")
        .cpfCnpj("98765432100")
        .estado("MT")
        .build());

    // Simula o produtor autenticado (papel do MockIdentidadeFilter em runtime).
    SecurityContextHolder.getContext().setAuthentication(
        AutenticacaoTestSupport.tokenDe(produtor));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("update() reaproveitando o mesmo numeroItem nao viola uk_nota_fiscal_item_nota_numero")
  void updateReaproveitandoNumeroItemNaoViolaConstraintUnica() {
    NotaFiscalResponse criada = notaFiscalService.create(buildRequest(1L, "Soja em graos",
        new BigDecimal("500.0"), new BigDecimal("2.50")));
    UUID notaId = criada.id();

    NotaFiscalResponse atualizada = notaFiscalService.update(notaId,
        buildRequest(1L, "Soja em graos - editado", new BigDecimal("600.0"),
            new BigDecimal("3.00")));

    assertThat(atualizada.itens()).hasSize(1);
    assertThat(atualizada.itens().get(0).numeroItem()).isEqualTo(1);
    assertThat(atualizada.itens().get(0).descricao()).isEqualTo("Soja em graos - editado");
    assertThat(atualizada.itens().get(0).quantidade())
        .isEqualByComparingTo(new BigDecimal("600.0"));
    assertThat(atualizada.valorTotalNota()).isEqualByComparingTo(new BigDecimal("1800.00"));
  }

  private NotaFiscalRequest buildRequest(Long numeroItem, String descricao,
      BigDecimal quantidade, BigDecimal valorUnitario) {
    NotaFiscalItemRequest item = new NotaFiscalItemRequest(
        numeroItem.intValue(), null, descricao, "12010090", "6101", "KG",
        quantidade, valorUnitario, null, null, null);

    return new NotaFiscalRequest(
        1L, 1, "Venda de producao rural", OffsetDateTime.now(), null, null,
        "Jose Comprador", "12345678901", null, null, List.of(item));
  }
}
