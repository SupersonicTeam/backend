package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link ReducaoNcmResolver} (issue Agrocontabil#52).
 *
 * <p>Cobre a precedência item → NCM → zero que centraliza a resolução da redução de base
 * compartilhada entre o cálculo de débito e o de crédito.
 */
class ReducaoNcmResolverTest {

  private NcmReferenciaRepository ncmReferenciaRepository;
  private ReducaoNcmResolver resolver;

  @BeforeEach
  void setUp() {
    ncmReferenciaRepository = mock(NcmReferenciaRepository.class);
    resolver = new ReducaoNcmResolver(ncmReferenciaRepository);
  }

  @Test
  @DisplayName("percentualReducao do item prevalece e nao consulta a tabela de NCM")
  void percentualDoItemPrevalece() {
    NotaFiscalItem item = NotaFiscalItem.builder()
        .ncm("12019000")
        .percentualReducao(new BigDecimal("40"))
        .build();

    BigDecimal reducao = resolver.resolver(item);

    assertThat(reducao).isEqualByComparingTo("40");
    verifyNoInteractions(ncmReferenciaRepository);
  }

  @Test
  @DisplayName("Sem percentualReducao no item, usa a reducao padrao do NCM")
  void usaReducaoPadraoDoNcm() {
    when(ncmReferenciaRepository.findPercentualReducaoPadraoByCodigo("12019000"))
        .thenReturn(Optional.of(new BigDecimal("60")));
    NotaFiscalItem item = NotaFiscalItem.builder().ncm("12019000").build();

    BigDecimal reducao = resolver.resolver(item);

    assertThat(reducao).isEqualByComparingTo("60");
  }

  @Test
  @DisplayName("NCM sem reducao de referencia resulta em zero (conservador)")
  void ncmSemReferenciaResultaZero() {
    when(ncmReferenciaRepository.findPercentualReducaoPadraoByCodigo("99999999"))
        .thenReturn(Optional.empty());
    NotaFiscalItem item = NotaFiscalItem.builder().ncm("99999999").build();

    BigDecimal reducao = resolver.resolver(item);

    assertThat(reducao).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("Item sem percentualReducao e sem NCM resulta em zero, sem consultar a tabela")
  void semNcmResultaZeroSemConsultar() {
    NotaFiscalItem item = NotaFiscalItem.builder().build();

    BigDecimal reducao = resolver.resolver(item);

    assertThat(reducao).isEqualByComparingTo("0");
    verify(ncmReferenciaRepository, never()).findPercentualReducaoPadraoByCodigo(null);
  }
}
