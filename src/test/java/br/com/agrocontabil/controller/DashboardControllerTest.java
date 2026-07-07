package br.com.agrocontabil.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.DashboardResponse;
import br.com.agrocontabil.dto.DashboardResponse.DashboardKpis;
import br.com.agrocontabil.dto.DashboardResponse.DistribuicaoImpostos;
import br.com.agrocontabil.dto.DashboardResponse.FinanceiroResumo;
import br.com.agrocontabil.dto.DashboardResponse.NotaPorMes;
import br.com.agrocontabil.dto.DashboardResponse.ProdutorResumo;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.DashboardService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de camada web do {@link DashboardController} (issue Agrocontabil#65 — Sprint 5).
 *
 * <p>Usa {@code @WebMvcTest} (sem JPA). O {@link DashboardService} é mockado e o
 * {@link GlobalExceptionHandler} importado para validar o envelope {@code errors[]} do 400.
 */
@WebMvcTest(controllers = DashboardController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    })
@Import(GlobalExceptionHandler.class)
class DashboardControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private DashboardService dashboardService;

  /** MockProdutorFilter (@Component) depende de ProdutorRepository — mockado no @WebMvcTest. */
  @MockBean
  private ProdutorRepository produtorRepository;

  private DashboardResponse dashboardExemplo(String periodo) {
    return new DashboardResponse(
        new ProdutorResumo("Fazenda Boa Vista", "PESSOA_FISICA", "PR"),
        periodo,
        new DashboardKpis(
            new BigDecimal("150.00"), new BigDecimal("1350.00"),
            new BigDecimal("30.00"), new BigDecimal("270.00"),
            new BigDecimal("120.00"), new BigDecimal("1080.00"), 8L),
        new FinanceiroResumo(
            new BigDecimal("12500.00"), new BigDecimal("3200.00"),
            new BigDecimal("9300.00"), new BigDecimal("8100.00")),
        List.of(new NotaPorMes(periodo, 1L, 3L)),
        new DistribuicaoImpostos(new BigDecimal("150.00"), new BigDecimal("1350.00")),
        List.of());
  }

  @Test
  @DisplayName("GET /api/dashboard com periodo valido retorna 200 com o payload agregado")
  void periodoValidoRetorna200() throws Exception {
    when(dashboardService.montar("2026-07")).thenReturn(dashboardExemplo("2026-07"));

    mockMvc.perform(get("/api/dashboard").param("periodo", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodo").value("2026-07"))
        .andExpect(jsonPath("$.produtor.nome").value("Fazenda Boa Vista"))
        .andExpect(jsonPath("$.produtor.uf").value("PR"))
        .andExpect(jsonPath("$.kpis.saldoIbs").value(120.00))
        .andExpect(jsonPath("$.kpis.totalNotas").value(8))
        .andExpect(jsonPath("$.financeiro.totalEntradas").value(12500.00))
        .andExpect(jsonPath("$.financeiro.totalSaidas").value(3200.00))
        .andExpect(jsonPath("$.financeiro.saldo").value(9300.00))
        .andExpect(jsonPath("$.financeiro.lucroEstimado").value(8100.00))
        .andExpect(jsonPath("$.notasPorMes[0].saidas").value(3))
        .andExpect(jsonPath("$.distribuicaoImpostos.cbs").value(1350.00))
        // O CPF/CNPJ nunca aparece no payload (privacidade).
        .andExpect(jsonPath("$.produtor.cpfCnpj").doesNotExist());
  }

  @Test
  @DisplayName("GET /api/dashboard sem periodo retorna 200 (usa mes corrente via service)")
  void semPeriodoUsaMesCorrente() throws Exception {
    when(dashboardService.montar(null)).thenReturn(dashboardExemplo("2026-07"));

    mockMvc.perform(get("/api/dashboard"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodo").value("2026-07"));
  }

  @Test
  @DisplayName("GET /api/dashboard com periodo mal formatado retorna 400 estruturado (errors[])")
  void periodoInvalidoRetorna400() throws Exception {
    mockMvc.perform(get("/api/dashboard").param("periodo", "2026-13"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].message").exists());

    verifyNoInteractions(dashboardService);
  }
}
