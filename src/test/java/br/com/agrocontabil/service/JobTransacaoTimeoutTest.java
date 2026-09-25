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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

/**
 * Garante que as operações de lote dos jobs agendados declaram um timeout de transação próprio,
 * maior que o {@code spring.transaction.default-timeout} de 30s introduzido pela issue #98.
 *
 * <p><strong>Por que isto existe:</strong> o default de 30s protege requisições HTTP, mas os jobs
 * que varrem a base inteira dentro de <em>uma única</em> transação — hoje o job de guias, que
 * carrega e salva todas as vencidas — passariam dos 30s conforme a base cresce, e o lote inteiro
 * sofreria rollback com {@code TransactionTimedOutException}.
 *
 * <p>O fechamento da apuração saiu dessa lista na issue #100: {@code fecharPeriodo} e
 * {@code refecharPeriodosReabertos} não abrem mais transação de lote ({@code NOT_SUPPORTED}) e
 * processam cada item numa transação curta, que herda o timeout padrão. O segundo teste trava isso:
 * se alguém voltar a anotá-los com uma transação de lote, o isolamento de falha por produtor some.
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

  static Stream<Arguments> operacoesPorItem() {
    return Stream.of(
        Arguments.of(ApuracaoService.class, "fecharPeriodo", new Class<?>[] {String.class}),
        Arguments.of(ApuracaoService.class, "refecharPeriodosReabertos", new Class<?>[] {}));
  }

  @ParameterizedTest(name = "{0}#{1}")
  @MethodSource("operacoesPorItem")
  @DisplayName("lote da apuracao nao abre transacao propria: cada item commita sozinho (issue #100)")
  void loteDaApuracaoNaoAbreTransacaoDeLote(
      Class<?> tipo, String metodo, Class<?>[] parametros) throws NoSuchMethodException {
    Method alvo = tipo.getMethod(metodo, parametros);

    TransactionAttribute atributo =
        transactionAttributeSource.getTransactionAttribute(alvo, tipo);

    assertThat(atributo).isNotNull();
    assertThat(atributo.getPropagationBehavior())
        .as("%s#%s precisa ser NOT_SUPPORTED: uma transacao de lote faria a falha de um produtor "
            + "desfazer o fechamento de todos", tipo.getSimpleName(), metodo)
        .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
  }
}
