package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.service.ReducaoNcmResolver.ReducaoAplicada;
import br.com.agrocontabil.service.TratamentoTributarioNcm.Origem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testes do {@link ReducaoNcmResolver} (issues #52 e #272). */
class ReducaoNcmResolverTest {

  private ClassificacaoTributariaService classificacao;
  private ReducaoNcmResolver resolver;

  @BeforeEach
  void setUp() {
    classificacao = mock(ClassificacaoTributariaService.class);
    resolver = new ReducaoNcmResolver(classificacao);
  }

  @Test
  @DisplayName("percentual informado no item prevalece e nao consulta a tabela")
  void percentualDoItemPrevalece() {
    NotaFiscalItem item = NotaFiscalItem.builder()
        .ncm("12019000").percentualReducao(new BigDecimal("15")).cClassTrib("200036").build();

    ReducaoAplicada aplicada = resolver.tratamento(item);

    assertThat(aplicada.percentualReducao()).isEqualByComparingTo("15");
    assertThat(aplicada.cClassTrib()).isEqualTo("200036");
    verifyNoInteractions(classificacao);
  }

  @Test
  @DisplayName("sem percentual no item usa a tabela oficial na data de emissao em BRT")
  void usaTabelaOficialNaDataDeEmissao() {
    // 31/12/2026 23h em BRT = 01/01/2027 em UTC: a vigencia e a do dia em Brasilia.
    NotaFiscal nota = NotaFiscal.builder()
        .dataEmissao(OffsetDateTime.of(2027, 1, 1, 2, 0, 0, 0, ZoneOffset.UTC)).build();
    NotaFiscalItem item = NotaFiscalItem.builder().ncm("04072100").notaFiscal(nota).build();
    when(classificacao.tratamentoPorNcm(eq("04072100"), any(LocalDate.class)))
        .thenReturn(new TratamentoTributarioNcm("04072100", "200014", "200",
            new BigDecimal("100"), Origem.ANEXO, "Anexo XV", List.of(), "IT"));

    ReducaoAplicada aplicada = resolver.tratamento(item);

    assertThat(aplicada.percentualReducao()).isEqualByComparingTo("100");
    assertThat(aplicada.cClassTrib()).isEqualTo("200014");
    verify(classificacao).tratamentoPorNcm("04072100", LocalDate.of(2026, 12, 31));
  }

  @Test
  @DisplayName("NCM ausente resulta em zero sem consultar a tabela")
  void ncmAusenteResultaEmZero() {
    assertThat(resolver.resolver(NotaFiscalItem.builder().build())).isEqualByComparingTo("0");
    verifyNoInteractions(classificacao);
  }
}
