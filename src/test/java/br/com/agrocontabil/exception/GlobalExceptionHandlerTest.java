package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.security.CookieRefresh;
import java.util.List;
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

  @Test
  @DisplayName("503 da Ceres: message de topo = errors[0], sem NF-e (#251)")
  void mensagemDeTopoDaCeresSemNfe() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/assistente/chat");
    for (DomainException ex : new DomainException[] {
        AssistenteOcupadoException.limiteGlobal(),
        AssistenteOcupadoException.perguntaEmAndamento(),
        new AssistenteTempoEsgotadoException(),
        new AssistenteIndisponivelException("modelo fora", null)}) {
      ApiError body = handler.handleDomain(ex, request).getBody();

      assertThat(body).isNotNull();
      assertThat(body.status()).isEqualTo(503);
      assertThat(body.message()).doesNotContain("NF-e")
          .isEqualTo(body.errors().get(0).message());
    }
  }

  @Test
  @DisplayName("429 da cota da Ceres: topo pela ComMensagemCliente, nao o texto de log (#251)")
  void mensagemDeTopoDaCotaDaCeres() {
    ApiError body = handler.handleDomain(new LimiteUsoAssistenteException(60),
        new MockHttpServletRequest("POST", "/api/assistente/chat")).getBody();

    assertThat(body.status()).isEqualTo(429);
    assertThat(body.message()).isEqualTo(LimiteUsoAssistenteException.MENSAGEM_CLIENTE)
        .isEqualTo(body.errors().get(0).message());
  }

  @Test
  @DisplayName("5xx de dominio: topo = errors[0] (seguro), nunca o getMessage tecnico (#251)")
  void mensagemDeTopoDe5xx() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notas");
    for (DomainException ex : new DomainException[] {
        new RelatorioGenerationException("jasper caiu: /tmp/x.jrxml", null),
        new AssinaturaDigitalException(ErrorCode.ASSINATURA_FALHOU, "keystore senha errada"),
        new DanfeGenerationException("jasper caiu", null),
        new SefazComunicacaoException("timeout em sefaz.mt.gov.br"),
        new SefazConexaoException("connect refused sefaz.mt.gov.br"),
        new SefazRespostaIndeterminadaException("read timeout sefaz.mt.gov.br")}) {
      ApiError body = handler.handleDomain(ex, request).getBody();

      assertThat(body).isNotNull();
      assertThat(body.message()).isEqualTo(body.errors().get(0).message())
          .doesNotContain(ex.getMessage());
    }
  }

  @Test
  @DisplayName("5xx sem errors ou com mensagem vazia: texto generico sem NF-e (#251)")
  void mensagemGenericaDe5xx() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
    for (List<ErrorDetail> errors : List.of(List.<ErrorDetail>of(),
        List.of(new ErrorDetail("X", null, null, null, null, null)),
        List.of(new ErrorDetail("X", null, "  ", null, null, null)))) {
      DomainException ex = new DomainException(
          HttpStatus.INTERNAL_SERVER_ERROR, "detalhe tecnico", errors) {};

      assertThat(handler.handleDomain(ex, request).getBody().message())
          .isEqualTo(GlobalExceptionHandler.MENSAGEM_5XX)
          .doesNotContain("NF-e");
    }
  }

  @Test
  @DisplayName("4xx sem ComMensagemCliente segue com o getMessage como topo (#251)")
  void quatroXxSegueComGetMessage() {
    ApiError body = handler.handleDomain(new GuiaJaPagaException(java.util.UUID.randomUUID()),
        new MockHttpServletRequest("PATCH", "/api/contador/guias/x/pagar")).getBody();

    assertThat(body.status()).isEqualTo(409);
    assertThat(body.message()).isNotBlank();
  }
}
