package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import br.com.agrocontabil.repository.ClassificacaoTributariaAnexoNcmRepository;
import br.com.agrocontabil.service.TratamentoTributarioNcm.Origem;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * {@link ClassificacaoTributariaService} sobre a tabela oficial carregada dos CSVs do changeSet
 * {@code 0048} (o espelho H2 lê os mesmos arquivos) — issue #272.
 *
 * <p>Trava a tabela conferida na issue (comentário "Conferência oficial (2026-10-29)") e a
 * coerência com o gabarito da Ceres: soja em grão tem redução de 60% (art. 137).
 */
@DataJpaTest
@Import(ClassificacaoTributariaService.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class ClassificacaoTributariaServiceIntegrationTest {

  private static final LocalDate EM_2026 = LocalDate.of(2026, 10, 29);

  /** Item 2 do Anexo I (leite) no CSV do changeSet 0048. */
  private static final int ITEM_LEITE = 2;

  @Autowired private ClassificacaoTributariaService service;
  @Autowired private ClassificacaoTributariaAnexoNcmRepository anexoNcmRepository;

  @ParameterizedTest(name = "{0} ({4}) -> {1}, {2}%")
  @CsvSource({
      // NCM,     cClassTrib, reducao, origem,     produto
      "12019000, 200036, 60,  IN_NATURA,   soja em grao (art. 137)",
      "10059010, 200036, 60,  IN_NATURA,   milho em grao (art. 137)",
      "12011000, 200038, 60,  ANEXO,       soja para semeadura (Anexo IX)",
      "10061092, 200036, 60,  IN_NATURA,   arroz em casca nao parboilizado (art. 137, #284)",
      "10061091, 200038, 60,  ANEXO,       arroz em casca parboilizado: sem a marca (#365)",
      "10061010, 200038, 60,  ANEXO,       arroz em casca para semeadura (Anexo IX)",
      "10063021, 200003, 100, ANEXO,       arroz polido (Anexo I)",
      "04012010, 200003, 100, ANEXO,       leite UHT: consumo direto, Anexo I (#284)",
      "04011010, 200003, 100, ANEXO,       leite UHT desnatado: Anexo I (#284)",
      "04012090, 200036, 60,  IN_NATURA,   leite cru ao laticinio (art. 137, #284)",
      "04072100, 200014, 100, ANEXO,       ovos de galinha (Anexo XV)",
      "07020000, 200014, 100, ANEXO,       tomate (Anexo XV)",
      "08043000, 200014, 100, ANEXO,       abacaxi (Anexo XV)",
      "17011400, 200003, 100, ANEXO,       acucar de cana em bruto (Anexo I)",
      "01022110, 200038, 60,  ANEXO,       bovino reprodutor (Anexo IX)",
      "99999999, 000001, 0,   SEM_REDUCAO, NCM sem hipotese"
  })
  @DisplayName("tabela conferida na issue #272")
  void tabelaConferida(String ncm, String cClassTrib, String reducao, Origem origem,
      String produto) {
    TratamentoTributarioNcm tratamento = service.tratamentoPorNcm(ncm, EM_2026);

    assertThat(tratamento.cClassTrib()).as(produto).isEqualTo(cClassTrib);
    assertThat(tratamento.cst()).isEqualTo(cClassTrib.substring(0, 3));
    assertThat(tratamento.percentualReducao()).as(produto).isEqualByComparingTo(reducao);
    assertThat(tratamento.origem()).isEqualTo(origem);
    if (origem != Origem.SEM_REDUCAO) {
      assertThat(tratamento.versaoTabela()).isEqualTo("IT 2025.002 v1.70");
      assertThat(tratamento.fundamento()).contains("Lei Complementar");
    }
  }

  @ParameterizedTest(name = "leite {0}: in natura por padrao, Anexo I nos enquadramentos")
  @CsvSource({"04011090", "04012090", "04014010", "04015010"})
  @DisplayName("leite do Anexo I, item 2, nao UHT: 60% in natura por padrao, com o Anexo I (#284)")
  void leiteInNaturaPorPadrao(String ncm) {
    TratamentoTributarioNcm tratamento = service.tratamentoPorNcm(ncm, EM_2026);

    assertThat(tratamento.cClassTrib()).isEqualTo("200036");
    assertThat(tratamento.percentualReducao()).isEqualByComparingTo("60");
    assertThat(tratamento.origem()).isEqualTo(Origem.IN_NATURA);
    assertThat(tratamento.fundamento()).contains("art. 137");
    assertThat(tratamento.enquadramentos())
        .extracting(EnquadramentoAnexo::cClassTrib, EnquadramentoAnexo::anexo)
        .contains(tuple("200003", 1));
    assertThat(service.tratamentosPorNcm(java.util.List.of(ncm), EM_2026).get(ncm))
        .isEqualTo(tratamento);
  }

  @Test
  @DisplayName("excecao do leite + UHT = exatamente os NCMs do Anexo I, item 2, da tabela")
  void excecaoDoLeiteSaoOsNcmsDoItem() {
    java.util.Set<String> leite =
        new java.util.HashSet<>(ClassificacaoTributariaService.LEITE_SEM_DESTINO_DEFINIDO);
    leite.addAll(ClassificacaoTributariaService.LEITE_UHT);
    assertThat(ClassificacaoTributariaService.LEITE_UHT)
        .doesNotContainAnyElementsOf(ClassificacaoTributariaService.LEITE_SEM_DESTINO_DEFINIDO);
    assertThat(anexoNcmRepository.findNcmsPermitidosDoItem(ITEM_LEITE, EM_2026))
        .containsExactlyInAnyOrderElementsOf(leite);
    assertThat(anexoNcmRepository.findVigentesEm(leite, EM_2026))
        .allSatisfy(l -> assertThat(l.getItem().getId()).isEqualTo(ITEM_LEITE));
  }

  @Test
  @DisplayName("Anexo I vence o Anexo VII: o arroz e vedado no VII e nao aparece la")
  void vedadoTiraDoAnexo() {
    assertThat(service.enquadramentos("10063021", EM_2026))
        .extracting(EnquadramentoAnexo::cClassTrib, EnquadramentoAnexo::anexo)
        .startsWith(tuple("200003", 1))
        .contains(tuple("200038", 9))
        .doesNotContain(tuple("200034", 7));
  }

  @Test
  @DisplayName("enquadramentos trazem o texto do item, a condicao e a excecao oficiais")
  void enquadramentoComTextoOficial() {
    assertThat(service.enquadramentos("07020000", EM_2026))
        .first()
        .satisfies(e -> {
          assertThat(e.cClassTrib()).isEqualTo("200014");
          assertThat(e.anexo()).isEqualTo(15);
          assertThat(e.descricaoItem()).contains("07.02.00.00");
          assertThat(e.excecao()).contains("Cogumelos e trufas");
        });
  }

  @Test
  @DisplayName("antes da vigencia (2025) nenhum anexo vale: tributacao integral")
  void foraDaVigencia() {
    assertThat(service.tratamentoPorNcm("04072100", LocalDate.of(2025, 12, 31)).cClassTrib())
        .isEqualTo("000001");
  }

  @Test
  @DisplayName("classificacao confere codigo existente e vigente")
  void classificacaoPorCodigo() {
    assertThat(service.classificacao("200036", EM_2026)).isPresent();
    assertThat(service.classificacao("010100", EM_2026)).isEmpty();
  }
}
