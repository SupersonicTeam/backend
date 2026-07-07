package br.com.agrocontabil.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.CalendarioFiscalService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de camada web do {@link CalendarioFiscalController} (issue #74).
 *
 * <p>Confirma que o endpoint que hoje quebra a tela de Calendário do Angular (sem fallback mock
 * no frontend) responde 200 mesmo sem guias, e que o payload segue o shape esperado pelo
 * {@code CalendarioApi} do frontend.
 */
@WebMvcTest(controllers = CalendarioFiscalController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    })
@Import(GlobalExceptionHandler.class)
class CalendarioFiscalControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private CalendarioFiscalService calendarioFiscalService;

  /** MockProdutorFilter (@Component) depende de ProdutorRepository — mockado no @WebMvcTest. */
  @MockBean private ProdutorRepository produtorRepository;

  @Test
  @DisplayName("GET /api/calendario-fiscal/{produtorId}/vencimentos retorna 200 com os vencimentos")
  void vencimentosRetorna200ComPayload() throws Exception {
    UUID produtorId = UUID.randomUUID();
    VencimentoFiscalResponse vencimento = new VencimentoFiscalResponse(
        UUID.randomUUID(), "Guia ICMS", "Referente a agosto/2026",
        LocalDate.of(2026, 8, 20), "imposto", "pendente", new BigDecimal("1250.00"));
    when(calendarioFiscalService.listarVencimentos(produtorId)).thenReturn(List.of(vencimento));

    mockMvc.perform(get("/api/calendario-fiscal/{produtorId}/vencimentos", produtorId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].evento").value("Guia ICMS"))
        .andExpect(jsonPath("$[0].tipo").value("imposto"))
        .andExpect(jsonPath("$[0].status").value("pendente"));
  }

  @Test
  @DisplayName("GET /api/calendario-fiscal/{produtorId}/vencimentos retorna 200 com lista vazia")
  void vencimentosRetorna200ComListaVazia() throws Exception {
    UUID produtorId = UUID.randomUUID();
    when(calendarioFiscalService.listarVencimentos(produtorId)).thenReturn(List.of());

    mockMvc.perform(get("/api/calendario-fiscal/{produtorId}/vencimentos", produtorId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }
}
