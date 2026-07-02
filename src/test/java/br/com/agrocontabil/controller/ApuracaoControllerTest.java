package br.com.agrocontabil.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.ApuracaoService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de camada web do {@link ApuracaoController} (issue Agrocontabil#41 — RN004).
 *
 * <p>Usa {@code @WebMvcTest} (sem JPA). O {@link ApuracaoService} é mockado e o
 * {@link GlobalExceptionHandler} importado para validar o envelope {@code errors[]} do 400.
 */
@WebMvcTest(controllers = ApuracaoController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    })
@Import(GlobalExceptionHandler.class)
class ApuracaoControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private ApuracaoService apuracaoService;

  /** MockProdutorFilter (@Component) depende de ProdutorRepository — mockado no @WebMvcTest. */
  @MockBean
  private ProdutorRepository produtorRepository;

  @Test
  @DisplayName("GET /api/apuracao com periodo valido retorna 200 com a apuracao")
  void periodoValidoRetorna200() throws Exception {
    when(apuracaoService.consultar("2026-07")).thenReturn(new ApuracaoResponse(
        "2026-07", "ABERTO",
        new BigDecimal("150.00"), new BigDecimal("1350.00"),
        new BigDecimal("30.00"), new BigDecimal("270.00"),
        new BigDecimal("120.00"), new BigDecimal("1080.00"), 8L, null));

    mockMvc.perform(get("/api/apuracao").param("periodo", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodo").value("2026-07"))
        .andExpect(jsonPath("$.status").value("ABERTO"))
        .andExpect(jsonPath("$.saldoIbs").value(120.00))
        .andExpect(jsonPath("$.saldoCbs").value(1080.00))
        .andExpect(jsonPath("$.totalNotas").value(8));
  }

  @Test
  @DisplayName("GET /api/apuracao com periodo mal formatado retorna 400 estruturado (errors[])")
  void periodoInvalidoRetorna400() throws Exception {
    mockMvc.perform(get("/api/apuracao").param("periodo", "2026-13"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].message").exists());

    verifyNoInteractions(apuracaoService);
  }

  @Test
  @DisplayName("GET /api/apuracao sem o parametro periodo retorna 400")
  void semPeriodoRetorna400() throws Exception {
    mockMvc.perform(get("/api/apuracao"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(apuracaoService);
  }
}
