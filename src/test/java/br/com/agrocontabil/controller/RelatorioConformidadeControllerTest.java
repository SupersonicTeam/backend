package br.com.agrocontabil.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.RelatorioConformidadeResponse;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.RelatorioConformidadeService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de camada web do {@link RelatorioConformidadeController} (issue Agrocontabil#32).
 *
 * <p>{@code @WebMvcTest} (sem JPA). O {@link RelatorioConformidadeService} é mockado e o
 * {@link GlobalExceptionHandler} importado para validar o envelope de erro.
 */
@WebMvcTest(controllers = RelatorioConformidadeController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    })
@Import(GlobalExceptionHandler.class)
class RelatorioConformidadeControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private RelatorioConformidadeService relatorioService;

  /** MockProdutorFilter (@Component) depende de ProdutorRepository — mockado no @WebMvcTest. */
  @MockBean
  private ProdutorRepository produtorRepository;

  private RelatorioConformidadeResponse exemplo() {
    return new RelatorioConformidadeResponse(
        "2026-07",
        new BigDecimal("10.00"), new BigDecimal("90.00"), new BigDecimal("100.00"), 5L,
        new BigDecimal("4.00"), new BigDecimal("36.00"), new BigDecimal("40.00"), 2L,
        new BigDecimal("6.00"), new BigDecimal("54.00"), new BigDecimal("60.00"));
  }

  @Test
  @DisplayName("GET conformidade (JSON) com periodo valido retorna 200 com o consolidado")
  void jsonPeriodoValido() throws Exception {
    when(relatorioService.gerar("2026-07")).thenReturn(exemplo());

    mockMvc.perform(get("/api/relatorios/conformidade").param("periodo", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodo").value("2026-07"))
        .andExpect(jsonPath("$.debitoTotal").value(100.00))
        .andExpect(jsonPath("$.creditoTotal").value(40.00))
        .andExpect(jsonPath("$.saldoAPagar").value(60.00));
  }

  @Test
  @DisplayName("GET conformidade/pdf retorna 200 application/pdf com Content-Disposition")
  void pdfPeriodoValido() throws Exception {
    when(relatorioService.gerar("2026-07")).thenReturn(exemplo());
    when(relatorioService.gerarPdf(eq("2026-07"))).thenReturn("%PDF-fake".getBytes());

    mockMvc.perform(get("/api/relatorios/conformidade/pdf").param("periodo", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.containsString("attachment")));
  }

  @Test
  @DisplayName("GET conformidade com periodo invalido retorna 400 estruturado")
  void periodoInvalido() throws Exception {
    mockMvc.perform(get("/api/relatorios/conformidade").param("periodo", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"));

    verifyNoInteractions(relatorioService);
  }
}
