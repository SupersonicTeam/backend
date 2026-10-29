package br.com.agrocontabil.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import br.com.agrocontabil.service.ClassificacaoTributariaService;
import br.com.agrocontabil.service.ReducaoNcmResolver;
import br.com.agrocontabil.service.TratamentoTributarioNcm;
import br.com.agrocontabil.service.TratamentoTributarioNcm.Origem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tabela de classificação tributária simulada para testes de unidade do cálculo (issue #272):
 * todo NCM sem redução (000001), salvo os configurados com {@link #comReducao}.
 */
public final class ReducaoTestSupport {

  private ReducaoTestSupport() {
  }

  /**
   * Serviço simulado em que nenhum NCM tem redução.
   *
   * @return mock com resposta padrão de tributação integral
   */
  public static ClassificacaoTributariaService classificacaoSemReducao() {
    ClassificacaoTributariaService classificacao = mock(ClassificacaoTributariaService.class);
    lenient().when(classificacao.tratamentoPorNcm(anyString(), any(LocalDate.class)))
        .thenAnswer(inv -> tratamento(inv.getArgument(0), "000001", "0", Origem.SEM_REDUCAO));
    return classificacao;
  }

  /**
   * Resolvedor sobre uma tabela em que nenhum NCM tem redução.
   *
   * @return resolvedor
   */
  public static ReducaoNcmResolver resolverSemReducao() {
    return new ReducaoNcmResolver(classificacaoSemReducao());
  }

  /**
   * Configura a redução de um NCM no serviço simulado.
   *
   * @param classificacao mock criado por {@link #classificacaoSemReducao()}
   * @param ncm NCM
   * @param percentual redução, de 0 a 100
   * @param cClassTrib classificação aplicada
   */
  public static void comReducao(ClassificacaoTributariaService classificacao, String ncm,
      String percentual, String cClassTrib) {
    lenient().when(classificacao.tratamentoPorNcm(eq(ncm), any(LocalDate.class)))
        .thenReturn(tratamento(ncm, cClassTrib, percentual, Origem.ANEXO));
  }

  private static TratamentoTributarioNcm tratamento(String ncm, String cClassTrib,
      String percentual, Origem origem) {
    return new TratamentoTributarioNcm(ncm, cClassTrib, cClassTrib.substring(0, 3),
        new BigDecimal(percentual), origem, null, List.of(), "teste");
  }
}
