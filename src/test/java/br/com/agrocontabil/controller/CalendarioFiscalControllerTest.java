package br.com.agrocontabil.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.exception.JanelaCalendarioInvalidaException;
import br.com.agrocontabil.repository.ContadorRepository;
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
import org.springframework.test.context.ActiveProfiles;
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
@ActiveProfiles("test")
class CalendarioFiscalControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private CalendarioFiscalService calendarioFiscalService;

  /** MockIdentidadeFilter (@Component) depende de ProdutorRepository — mockado no @WebMvcTest. */
  @MockBean private ProdutorRepository produtorRepository;

  /** O filtro tambem resolve contador (issue #93) — mockado junto no @WebMvcTest. */
  @MockBean private ContadorRepository contadorRepository;

  @Test
  @DisplayName("GET /api/calendario-fiscal/{produtorId}/vencimentos retorna 200 com os vencimentos")
  void vencimentosRetorna200ComPayload() throws Exception {
    UUID produtorId = UUID.randomUUID();
    VencimentoFiscalResponse vencimento = new VencimentoFiscalResponse(
        UUID.randomUUID().toString(), "Guia ICMS", "Referente a agosto/2026",
        LocalDate.of(2026, 8, 20), "imposto", "pendente", new BigDecimal("1250.00"),
        "Guia ICMS", null, null);
    when(calendarioFiscalService.listarVencimentos(produtorId, null, null))
        .thenReturn(List.of(vencimento));

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
    when(calendarioFiscalService.listarVencimentos(produtorId, null, null)).thenReturn(List.of());

    mockMvc.perform(get("/api/calendario-fiscal/{produtorId}/vencimentos", produtorId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  @DisplayName("GET .../vencimentos repassa a janela de/ate ao service (#103)")
  void vencimentosRepassaJanela() throws Exception {
    UUID produtorId = UUID.randomUUID();
    when(calendarioFiscalService.listarVencimentos(
        produtorId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30)))
        .thenReturn(List.of());

    mockMvc.perform(get("/api/calendario-fiscal/{produtorId}/vencimentos", produtorId)
            .param("de", "2026-07-01")
            .param("ate", "2026-09-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  @DisplayName("GET .../vencimentos com data malformada retorna 400 no envelope (#103)")
  void vencimentosComDataMalformadaRetorna400() throws Exception {
    mockMvc.perform(get("/api/calendario-fiscal/{produtorId}/vencimentos", UUID.randomUUID())
            .param("de", "01/07/2026"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("de"));
    verifyNoInteractions(calendarioFiscalService);
  }

  @Test
  @DisplayName("GET .../vencimentos com janela invalida retorna 400 JANELA_CALENDARIO_INVALIDA")
  void vencimentosComJanelaInvalidaRetorna400() throws Exception {
    UUID produtorId = UUID.randomUUID();
    LocalDate de = LocalDate.of(2026, 10, 1);
    LocalDate ate = LocalDate.of(2026, 9, 30);
    when(calendarioFiscalService.listarVencimentos(produtorId, de, ate))
        .thenThrow(JanelaCalendarioInvalidaException.inicioDepoisDoFim(de, ate));

    mockMvc.perform(get("/api/calendario-fiscal/{produtorId}/vencimentos", produtorId)
            .param("de", "2026-10-01")
            .param("ate", "2026-09-30"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("JANELA_CALENDARIO_INVALIDA"))
        .andExpect(jsonPath("$.errors[0].field").value("de"));
  }
}
