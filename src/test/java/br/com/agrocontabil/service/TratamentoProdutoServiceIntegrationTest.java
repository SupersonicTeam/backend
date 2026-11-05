package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.repository.ClassificacaoTributariaAnexoNcmRepository;
import br.com.agrocontabil.service.TratamentoProdutoService.ConsultaTratamentoProduto;
import br.com.agrocontabil.service.TratamentoProdutoService.TipoBusca;
import br.com.agrocontabil.service.TratamentoProdutoService.TratamentoProduto;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * {@link TratamentoProdutoService} sobre a tabela oficial dos CSVs do changeSet {@code 0048}
 * (issue #283, parte 1), e a coerência com o {@link ReducaoNcmResolver}: a Ceres explica o que o
 * cálculo da nota aplica.
 */
@DataJpaTest
@Import({ClassificacaoTributariaService.class, TratamentoProdutoService.class,
    ReducaoNcmResolver.class})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class TratamentoProdutoServiceIntegrationTest {

  private static final LocalDate EM_2026 = LocalDate.of(2026, 11, 2);

  @Autowired private TratamentoProdutoService service;
  @Autowired private ClassificacaoTributariaService classificacaoService;
  @Autowired private ReducaoNcmResolver resolver;
  @Autowired private ClassificacaoTributariaAnexoNcmRepository anexoNcmRepository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("soja: os dois NCMs da referencia, grao in natura (art. 137) e semeadura (Anexo IX)")
  void soja() {
    ConsultaTratamentoProduto c = service.consultar("soja", EM_2026);

    assertThat(c.tipo()).isEqualTo(TipoBusca.NOME);
    assertThat(c.versaoTabela()).isEqualTo("IT 2025.002 v1.70");
    assertThat(c.produtos())
        .extracting(TratamentoProduto::ncm, TratamentoProduto::cClassTrib,
            p -> p.percentualReducao().intValue(), TratamentoProduto::artigo,
            TratamentoProduto::anexo)
        .containsExactly(
            tuple("12011000", "200038", 60, "138", 9),
            tuple("12019000", "200036", 60, "137", null));
    assertThat(c.produtos().get(1).condicao()).contains("in natura");
  }

  @Test
  @DisplayName("'soja em graos': a primeira palavra acha a soja mesmo sem 'grao' na descricao")
  void sojaEmGraos() {
    assertThat(service.consultar("soja em graos", EM_2026).produtos())
        .extracting(TratamentoProduto::ncm).containsExactly("12011000", "12019000");
    assertThat(service.consultar("graos de soja", EM_2026).produtos())
        .extracting(TratamentoProduto::ncm).containsExactly("12011000", "12019000");
  }

  @Test
  @DisplayName("arroz polido: Anexo I, 100%, art. 125, destinado a alimentacao humana")
  void arroz() {
    TratamentoProduto arroz = service.consultar("arroz polido", EM_2026).produtos().get(0);

    assertThat(arroz.ncm()).isEqualTo("10063021");
    assertThat(arroz.cClassTrib()).isEqualTo("200003");
    assertThat(arroz.percentualReducao()).isEqualByComparingTo("100");
    assertThat(arroz.artigo()).isEqualTo("125");
    assertThat(arroz.anexo()).isEqualTo(1);
    assertThat(arroz.condicao()).isEqualTo("destinados à alimentação humana");
  }

  @Test
  @DisplayName("#284 p. 3: 'arroz' traz o em casca in natura (art. 137) e o polido (Anexo I)")
  void arrozEmCascaEPolido() {
    assertThat(service.consultar("arroz", EM_2026).produtos())
        .extracting(TratamentoProduto::ncm, TratamentoProduto::cClassTrib,
            p -> p.percentualReducao().intValue(), TratamentoProduto::artigo,
            TratamentoProduto::anexo)
        .containsExactly(
            tuple("10061091", "200036", 60, "137", null),
            tuple("10061092", "200036", 60, "137", null),
            tuple("10063021", "200003", 100, "125", 1));
    assertThat(service.consultar("10061091", EM_2026).produtos().get(0).condicao())
        .contains("in natura");
  }

  @Test
  @DisplayName("ovo (singular) casa 'Ovos': Anexo XV, 100%, desde que nao cozidos")
  void ovo() {
    List<TratamentoProduto> ovos = service.consultar("ovo", EM_2026).produtos();

    assertThat(ovos).extracting(TratamentoProduto::ncm).containsExactly("04072100", "04072900");
    assertThat(ovos).allSatisfy(o -> {
      assertThat(o.cClassTrib()).isEqualTo("200014");
      assertThat(o.percentualReducao()).isEqualByComparingTo("100");
      assertThat(o.artigo()).isEqualTo("148");
      assertThat(o.condicao()).isEqualTo("desde que não cozidos");
    });
  }

  @Test
  @DisplayName("adubo: fora da referencia de NCM, acha o item 'Fertilizantes (adubos)' do Anexo IX")
  void adubo() {
    ConsultaTratamentoProduto c = service.consultar("adubo", EM_2026);

    assertThat(c.tipo()).isEqualTo(TipoBusca.ITEM_ANEXO);
    assertThat(c.produtos()).hasSize(1);
    TratamentoProduto adubo = c.produtos().get(0);
    assertThat(adubo.descricao()).startsWith("Fertilizantes (adubos)");
    assertThat(adubo.ncm()).isNull();
    assertThat(adubo.quantidadeNcms())
        .isEqualTo(anexoNcmRepository.findNcmsPermitidosDoItem(59, EM_2026).size());
    assertThat(adubo.cClassTrib()).isEqualTo("200038");
    assertThat(adubo.percentualReducao()).isEqualByComparingTo("60");
    assertThat(adubo.artigo()).isEqualTo("138");
    assertThat(adubo.anexo()).isEqualTo(9);
  }

  @Test
  @DisplayName("NCM com ou sem pontos: o proprio codigo, com o nome da referencia")
  void porNcm() {
    ConsultaTratamentoProduto c = service.consultar("1201.90.00", EM_2026);

    assertThat(c.tipo()).isEqualTo(TipoBusca.NCM);
    assertThat(c.produtos()).singleElement().satisfies(p -> {
      assertThat(p.ncm()).isEqualTo("12019000");
      assertThat(p.descricao()).startsWith("Soja");
      assertThat(p.cClassTrib()).isEqualTo("200036");
    });
  }

  @Test
  @DisplayName("NCM sem hipotese: tributacao integral, sem artigo nem condicao")
  void semReducao() {
    TratamentoProduto p = service.consultar("99999999", EM_2026).produtos().get(0);

    assertThat(p.cClassTrib()).isEqualTo("000001");
    assertThat(p.percentualReducao()).isEqualByComparingTo("0");
    assertThat(p.artigo()).isNull();
    assertThat(p.condicao()).isNull();
  }

  @Test
  @DisplayName("review: plural em -oes casa o singular (feijoes, item do Anexo I)")
  void feijao() {
    TratamentoProduto feijao = service.consultar("feijao", EM_2026).produtos().get(0);

    assertThat(feijao.descricao()).startsWith("Feij");
    assertThat(feijao.cClassTrib()).isEqualTo("200003");
    assertThat(feijao.percentualReducao()).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("review: a palavra mais rara decide ('ovos fertilizados' e o item do Anexo IX)")
  void palavraMaisRara() {
    TratamentoProduto ovos = service.consultar("ovos fertilizados", EM_2026).produtos().get(0);

    assertThat(ovos.descricao()).isEqualTo("Ovos fertilizados");
    assertThat(ovos.cClassTrib()).isEqualTo("200038");
  }

  @Test
  @DisplayName("review: 'NCM 1201.90.00' e o codigo, nao a palavra 'ncm' dos itens de anexo")
  void prefixoNcm() {
    ConsultaTratamentoProduto c = service.consultar("NCM 1201.90.00", EM_2026);

    assertThat(c.tipo()).isEqualTo(TipoBusca.NCM);
    assertThat(c.produtos()).extracting(TratamentoProduto::ncm).containsExactly("12019000");
  }

  @Test
  @DisplayName("review: condicao de destino escrita no item do Anexo IX entra na condicao")
  void condicaoNoTextoDoItem() {
    assertThat(service.consultar("farelo", EM_2026).produtos())
        .allSatisfy(p -> assertThat(p.condicao()).containsIgnoringCase("destinad"));
  }

  @Test
  @DisplayName("review: NCM fora da referencia e de todo anexo sai como desconhecido")
  void ncmDesconhecido() {
    assertThat(service.consultar("99999999", EM_2026).produtos().get(0).ncmConhecido()).isFalse();
    assertThat(service.consultar("12019000", EM_2026).produtos().get(0).ncmConhecido()).isTrue();
    assertThat(service.consultar("31010000", EM_2026).produtos().get(0).ncmConhecido()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"trator", "de", "xyzabc"})
  @DisplayName("nome que nao casa nada: lista vazia, sem inventar produto")
  void naoEncontrado(String termo) {
    assertThat(service.consultar(termo, EM_2026).produtos()).isEmpty();
  }

  @Test
  @DisplayName("lote = um a um: tratamentosPorNcm e tratamentoPorNcm concordam NCM a NCM")
  void loteIgualAoUmAUm() {
    List<String> ncms = todosOsNcms();
    assertThat(ncms).hasSizeGreaterThan(100);
    Map<String, TratamentoTributarioNcm> lote =
        classificacaoService.tratamentosPorNcm(ncms, EM_2026);

    assertThat(lote).hasSize(ncms.size());
    for (String ncm : ncms) {
      assertThat(lote.get(ncm)).as(ncm).isEqualTo(classificacaoService.tratamentoPorNcm(ncm,
          EM_2026));
    }
  }

  @Test
  @DisplayName("coerencia #283: a Ceres e o ReducaoNcmResolver dao o mesmo tratamento por NCM")
  void coerenciaComOResolvedor() {
    for (String ncm : todosOsNcms()) {
      TratamentoProduto daCeres = service.consultar(ncm, EM_2026).produtos().get(0);
      ReducaoNcmResolver.ReducaoAplicada doCalculo = resolver.tratamento(item(ncm));

      assertThat(daCeres.cClassTrib()).as(ncm).isEqualTo(doCalculo.cClassTrib());
      assertThat(daCeres.percentualReducao()).as(ncm)
          .isEqualByComparingTo(doCalculo.percentualReducao());
    }
  }

  /**
   * NCMs da referência, mais o primeiro e o último de cada item de anexo (permitidos e vedados):
   * cobre todas as hipóteses sem consultar os ~2.100 NCMs um a um.
   */
  private List<String> todosOsNcms() {
    return jdbc.queryForList("""
        SELECT codigo FROM ncm_agropecuario
        UNION SELECT MIN(ncm) FROM classificacao_tributaria_anexo_ncm GROUP BY item_id, permissao
        UNION SELECT MAX(ncm) FROM classificacao_tributaria_anexo_ncm GROUP BY item_id, permissao
        ORDER BY 1
        """, String.class);
  }

  private static NotaFiscalItem item(String ncm) {
    NotaFiscal nota = new NotaFiscal();
    nota.setDataEmissao(OffsetDateTime.of(2026, 11, 2, 12, 0, 0, 0, ZoneOffset.ofHours(-3)));
    NotaFiscalItem item = new NotaFiscalItem();
    item.setNcm(ncm);
    item.setNotaFiscal(nota);
    return item;
  }
}
