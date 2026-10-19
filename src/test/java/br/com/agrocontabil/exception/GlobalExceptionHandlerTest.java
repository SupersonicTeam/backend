package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.security.CookieRefresh;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Testes unitários do {@link GlobalExceptionHandler} para casos que o MockMvc não consegue
 * disparar de ponta a ponta (issue Agrocontabil#66 — Sprint 5).
 *
 * <p>O limite de upload multipart é aplicado pelo container/multipart resolver real, ausente
 * no MockMvc — por isso o 413 é exercitado invocando o handler diretamente.
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
      new StaticListableBeanFactory().getBeanProvider(CookieRefresh.class));

  @Test
  @DisplayName("Upload acima do limite responde 413 com envelope errors[]")
  void uploadAcimaDoLimiteResponde413ComEnvelope() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/notas/importar");

    ResponseEntity<ApiError> response =
        handler.handleMaxUploadSize(new MaxUploadSizeExceededException(1_048_576L), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    ApiError body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(413);
    assertThat(body.path()).isEqualTo("/api/notas/importar");
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).code()).isEqualTo("PAYLOAD_TOO_LARGE");
  }

  @Test
  @DisplayName("Violacao de integridade (unique/FK) responde 409 sem vazar detalhe tecnico")
  void violacaoDeIntegridadeResponde409SemVazarDetalhe() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/notas");

    ResponseEntity<ApiError> response = handler.handleDataIntegrityViolation(
        new DataIntegrityViolationException(
            "could not execute statement; constraint [uk_nota_fiscal_produtor_numero_serie_modelo]"),
        request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    ApiError body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(409);
    assertThat(body.path()).isEqualTo("/api/notas");
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).code()).isEqualTo("VIOLACAO_INTEGRIDADE");
    // Nunca vaza o nome da constraint / SQL ao cliente — detalhe tecnico fica so no log.
    assertThat(body.errors().get(0).message()).doesNotContain("uk_");
    assertThat(body.message()).doesNotContain("constraint");
  }

  @Test
  @DisplayName("Timeout de transacao responde 500 com envelope errors[] e mensagem generica")
  void timeoutDeTransacaoResponde500ComEnvelope() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/notas/importar");

    // spring.transaction.default-timeout (issue #98) aborta a transacao presa; a excecao nao tem
    // handler proprio e deve cair na rede de seguranca, nunca num 500 sem corpo.
    ResponseEntity<ApiError> response = handler.handleUnexpected(
        new TransactionTimedOutException(
            "Transaction timed out: deadline was Tue Sep 22 10:00:00 BRT 2026"),
        request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

    ApiError body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(500);
    assertThat(body.path()).isEqualTo("/api/notas/importar");
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).code()).isEqualTo("INTERNAL_ERROR");
    // Detalhe tecnico (deadline, nome da excecao) fica so no log.
    assertThat(body.message()).doesNotContain("deadline");
    assertThat(body.errors().get(0).message()).doesNotContain("Transaction timed out");
  }

  @Test
  @DisplayName("Conflito de lock otimista responde 409 orientando o recarregamento")
  void conflitoDeLockOtimistaResponde409() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/notas/abc/gerar-xml");

    // issue #95: duas transicoes concorrentes na mesma nota — o @Version faz o segundo commit
    // falhar. Sem handler proprio isso cairia no 500 genérico, escondendo um conflito que o
    // cliente resolve recarregando a nota.
    ResponseEntity<ApiError> response = handler.handleOptimisticLocking(
        new OptimisticLockingFailureException(
            "Row was updated or deleted by another transaction "
                + "(or unsaved-value mapping was incorrect): "
                + "[br.com.agrocontabil.model.NotaFiscal#8f1e...]"),
        request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    ApiError body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(409);
    assertThat(body.path()).isEqualTo("/api/notas/abc/gerar-xml");
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).code()).isEqualTo("CONFLITO_CONCORRENCIA");
    // A mensagem diz ao cliente o que fazer, nao o que aconteceu no banco.
    assertThat(body.errors().get(0).message()).contains("recarregue");
    assertThat(body.errors().get(0).suggestion()).isNotBlank();
    // Nunca vaza nome de entidade, tabela ou texto de excecao do ORM.
    assertThat(body.message()).doesNotContain("NotaFiscal");
    assertThat(body.errors().get(0).message())
        .doesNotContain("transaction")
        .doesNotContain("unsaved-value");
  }

  @Test
  @DisplayName("Ceres indisponivel responde 503 com mensagem propria, sem detalhe do provedor")
  void assistenteIndisponivelResponde503() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/assistente/chat");

    ResponseEntity<ApiError> response = handler.handleDomain(
        new AssistenteIndisponivelException(
            "Falha ao chamar o modelo ollama: ResourceAccessException", null),
        request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    ApiError body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.errors()).singleElement()
        .satisfies(e -> assertThat(e.code()).isEqualTo("ASSISTENTE_INDISPONIVEL"));
    // Nem a mensagem generica de NF-e (que confundiria o chat) nem o detalhe tecnico.
    assertThat(body.message()).isEqualTo(AssistenteIndisponivelException.MENSAGEM_CLIENTE);
    assertThat(body.message()).doesNotContain("NF-e", "ollama", "ResourceAccess");
  }
}
