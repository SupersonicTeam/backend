package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

/**
 * Garante que as operações de lote dos jobs agendados declaram um timeout de transação próprio,
 * maior que o {@code spring.transaction.default-timeout} de 30s introduzido pela issue #98.
 *
 * <p><strong>Por que isto existe:</strong> o default de 30s protege requisições HTTP, mas os jobs
 * varrem a base inteira dentro de <em>uma única</em> transação — {@code fecharPeriodo} itera todos
 * os produtores, {@code refecharPeriodosReabertos} todas as apurações da janela da RN005 e o job
 * de guias carrega e salva todas as vencidas. Conforme a base cresce, essas transações passam dos
 * 30s e o lote inteiro sofre rollback com {@code TransactionTimedOutException} — o fechamento
 * mensal falharia por completo, uma vez por mês, sem ninguém pedir nada.
 *
 * <p>O timeout continua existindo (transação zumbi ainda é cortada), só que na ordem de grandeza
 * de um job de lote e ajustável por propriedade.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobTransacaoTimeoutTest {

  /** Default de {@code spring.transaction.default-timeout} (segundos), da issue #98. */
  private static final int TIMEOUT_PADRAO_SEGUNDOS = 30;

  @Autowired
  private TransactionAttributeSource transactionAttributeSource;

  static Stream<Arguments> operacoesDeLote() {
    return Stream.of(
        Arguments.of(ApuracaoService.class, "fecharPeriodo", new Class<?>[] {String.class}),
        Arguments.of(ApuracaoService.class, "refecharPeriodosReabertos", new Class<?>[] {}),
        Arguments.of(GuiaFiscalStatusSyncJob.class, "sincronizarVencidas",
            new Class<?>[] {}));
  }

  @ParameterizedTest(name = "{0}#{1}")
  @MethodSource("operacoesDeLote")
  @DisplayName("operacao de lote deve declarar timeout de transacao proprio, acima do default")
  void operacaoDeLoteDeveDeclararTimeoutProprio(
      Class<?> tipo, String metodo, Class<?>[] parametros) throws NoSuchMethodException {
    Method alvo = tipo.getMethod(metodo, parametros);

    TransactionAttribute atributo =
        transactionAttributeSource.getTransactionAttribute(alvo, tipo);

    assertThat(atributo)
        .as("%s#%s deveria ser transacional", tipo.getSimpleName(), metodo)
        .isNotNull();
    assertThat(atributo.getTimeout())
        .as("%s#%s varre a base inteira numa transacao: precisa de timeout proprio, "
            + "senao o lote todo sofre rollback ao passar do default de %ds",
            tipo.getSimpleName(), metodo, TIMEOUT_PADRAO_SEGUNDOS)
        .isGreaterThan(TIMEOUT_PADRAO_SEGUNDOS);
  }
}
