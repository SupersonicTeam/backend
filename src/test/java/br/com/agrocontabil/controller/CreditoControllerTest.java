package br.com.agrocontabil.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CreditoSaldoResponse;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.CreditoService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de camada web do {@link CreditoController} (issue Agrocontabil#30 — RN003).
 *
 * <p>Usa {@code @WebMvcTest} (sem JPA). O {@link CreditoService} é mockado e o
 * {@link GlobalExceptionHandler} importado para validar o envelope {@code errors[]} do 400.
 */
@WebMvcTest(controllers = CreditoController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    })
@Import(GlobalExceptionHandler.class)
class CreditoControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private CreditoService creditoService;

  /** MockProdutorFilter (@Component) depende de ProdutorRepository — mockado no @WebMvcTest. */
  @MockBean
  private ProdutorRepository produtorRepository;

  @Test
  @DisplayName("GET /api/creditos com periodo valido retorna 200 com o saldo")
  void periodoValidoRetorna200() throws Exception {
    when(creditoService.consultarSaldo("2026-07")).thenReturn(new CreditoSaldoResponse(
        "2026-07", new BigDecimal("12.50"), new BigDecimal("112.50"),
        new BigDecimal("125.00"), 3L));

    mockMvc.perform(get("/api/creditos").param("periodo", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodo").value("2026-07"))
        .andExpect(jsonPath("$.creditoIbs").value(12.50))
        .andExpect(jsonPath("$.creditoCbs").value(112.50))
        .andExpect(jsonPath("$.creditoTotal").value(125.00))
        .andExpect(jsonPath("$.quantidadeNotas").value(3));
  }

  @Test
  @DisplayName("GET /api/creditos com periodo mal formatado retorna 400 estruturado (errors[])")
  void periodoInvalidoRetorna400() throws Exception {
    mockMvc.perform(get("/api/creditos").param("periodo", "2026-13"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].message").exists());

    verifyNoInteractions(creditoService);
  }

  @Test
  @DisplayName("GET /api/creditos sem o parametro periodo retorna 400")
  void semPeriodoRetorna400() throws Exception {
    mockMvc.perform(get("/api/creditos"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(creditoService);
  }
}
