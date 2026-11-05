package br.com.agrocontabil.assistente.adaptador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.repository.CreditoSaldoAgregado;
import br.com.agrocontabil.service.ApuracaoService;
import br.com.agrocontabil.service.ConsultaDadosProdutorService;
import br.com.agrocontabil.service.CreditoService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * Timeout por função da Ceres (issue #157): cada consulta roda numa transação curta com o timeout
 * de {@code app.assistente.timeout-funcao}, que o Spring aplica às queries.
 */
@ExtendWith(MockitoExtension.class)
class DadosProdutorAdaptadorTest {

  @Mock private ConsultaDadosProdutorService consultaService;
  @Mock private ApuracaoService apuracaoService;
  @Mock private CreditoService creditoService;
  @Mock private br.com.agrocontabil.service.AlertaTributarioService alertaService;
  @Mock private br.com.agrocontabil.service.RetencaoService retencaoService;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private TransactionStatus status;

  private DadosProdutorAdaptador adaptador;

  @BeforeEach
  void setUp() {
    AssistenteProperties propriedades = new AssistenteProperties();
    propriedades.setTimeoutFuncao(Duration.ofSeconds(7));
    adaptador = new DadosProdutorAdaptador(consultaService, apuracaoService, creditoService,
        alertaService, retencaoService,
        transactionManager, propriedades);
    when(transactionManager.getTransaction(any())).thenReturn(status);
  }

  @Test
  @DisplayName("A consulta roda numa transacao com o timeout configurado, e ela e encerrada")
  void consultaComTimeout() {
    UUID produtorId = UUID.randomUUID();
    LocalDate inicio = LocalDate.of(2026, 1, 1);
    LocalDate fim = LocalDate.of(2026, 6, 30);
    when(creditoService.somarCreditosNoIntervalo(produtorId, inicio, fim)).thenReturn(
        new CreditoSaldoAgregado(new BigDecimal("1.00"), new BigDecimal("2.00"), 3L));

    assertThat(adaptador.creditos(produtorId, inicio, fim).quantidadeNotas()).isEqualTo(3L);

    ArgumentCaptor<TransactionDefinition> definicao =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definicao.capture());
    assertThat(definicao.getValue().getTimeout()).isEqualTo(7);
    verify(transactionManager).commit(status);
  }

  @Test
  @DisplayName("Consulta que estoura o timeout propaga a falha (vira mensagem generica ao modelo)")
  void timeoutPropaga() {
    UUID produtorId = UUID.randomUUID();
    when(apuracaoService.consultarParaProdutor(produtorId, "2026-07"))
        .thenThrow(new TransactionTimedOutException("prazo"));

    assertThatThrownBy(() -> adaptador.apuracao(produtorId, "2026-07"))
        .isInstanceOf(TransactionTimedOutException.class);
    verify(transactionManager).rollback(status);
  }

  @Test
  @DisplayName("#347: perfil do atalho vem do service, na transacao com timeout, so com tipo, "
      + "escritorio e NCMs")
  void perfilDoAtalho() {
    UUID produtorId = UUID.randomUUID();
    LocalDate inicio = LocalDate.of(2026, 1, 1);
    LocalDate fim = LocalDate.of(2026, 11, 5);
    when(consultaService.perfilDoAtalho(produtorId, inicio, fim, 3)).thenReturn(
        new br.com.agrocontabil.service.ConsultaDadosProdutorService.PerfilAtalho(
            br.com.agrocontabil.model.TipoProdutor.PESSOA_JURIDICA, true, List.of("12019000")));

    br.com.agrocontabil.assistente.porta.DadosProdutorPorta.PerfilAtalho perfil =
        adaptador.perfilDoAtalho(produtorId, inicio, fim, 3);

    assertThat(perfil.tipo()).isEqualTo(br.com.agrocontabil.model.TipoProdutor.PESSOA_JURIDICA);
    assertThat(perfil.atendidoPorEscritorio()).isTrue();
    assertThat(perfil.ncmsMaisVendidos()).containsExactly("12019000");
    verify(transactionManager).commit(status);
  }
}
