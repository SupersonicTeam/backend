package br.com.agrocontabil.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.exception.ApuracaoNaoReabrivelException;
import br.com.agrocontabil.exception.FaixaApuracaoInvalidaException;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.exception.ReaberturaForaPrazoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.ApuracaoFaixaService;
import br.com.agrocontabil.service.ApuracaoService;
import java.math.BigDecimal;
import java.time.YearMonth;
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
@ActiveProfiles("test")
class ApuracaoControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private ApuracaoService apuracaoService;

  /** Faixa de meses (issue #179); a regra dela e testada com o service real na integracao. */
  @MockBean
  private ApuracaoFaixaService apuracaoFaixaService;

  /** MockIdentidadeFilter (@Component) depende de ProdutorRepository — mockado no @WebMvcTest. */
  @MockBean
  private ProdutorRepository produtorRepository;

  /** O filtro tambem resolve contador (issue #93) — mockado junto no @WebMvcTest. */
  @MockBean
  private ContadorRepository contadorRepository;

  @Test
  @DisplayName("GET /api/apuracao com periodo valido retorna 200 com a apuracao")
  void periodoValidoRetorna200() throws Exception {
    when(apuracaoService.consultar("2026-07")).thenReturn(new ApuracaoResponse(
        "2026-07", "ABERTO",
        new BigDecimal("150.00"), new BigDecimal("1350.00"),
        new BigDecimal("30.00"), new BigDecimal("270.00"),
        new BigDecimal("120.00"), new BigDecimal("1080.00"), 8L, null,
        "2026-07", "2026-07", false));

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
  @DisplayName("GET /api/apuracao sem periodo nem faixa: o service da faixa recusa com 400")
  void semPeriodoRetorna400() throws Exception {
    when(apuracaoFaixaService.consultar(null, null, null, null)).thenThrow(
        new FaixaApuracaoInvalidaException("de", "Informe periodo, a faixa ou ultimos.",
            "Ex.: ?ultimos=6.", null, "YYYY-MM"));

    mockMvc.perform(get("/api/apuracao"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FAIXA_APURACAO_INVALIDA"));

    verifyNoInteractions(apuracaoService);
  }

  @Test
  @DisplayName("GET /api/apuracao com de/ate devolve a lista do service da faixa")
  void faixaDevolveLista() throws Exception {
    when(apuracaoFaixaService.consultar("2026-01", "2026-02", null, null)).thenReturn(List.of(
        new ApuracaoResponse("2026-01", "FECHADO", BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 1L, null,
            "2026-01", "2026-01", false)));

    mockMvc.perform(get("/api/apuracao").param("de", "2026-01").param("ate", "2026-02"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].periodo").value("2026-01"))
        .andExpect(jsonPath("$[0].id").doesNotExist());

    verifyNoInteractions(apuracaoService);
  }

  @Test
  @DisplayName("GET /api/apuracao com de mal formatado retorna 400 VALIDATION_ERROR")
  void deInvalidoRetorna400() throws Exception {
    mockMvc.perform(get("/api/apuracao").param("de", "2026-1").param("ate", "2026-02"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"));

    verifyNoInteractions(apuracaoFaixaService);
  }

  @Test
  @DisplayName("POST /api/apuracao/{id}/reabrir com sucesso retorna 200 com status ABERTO")
  void reabrirComSucessoRetorna200() throws Exception {
    UUID id = UUID.randomUUID();
    when(apuracaoService.reabrir(id)).thenReturn(new ApuracaoResponse(
        "2026-06", "ABERTO",
        new BigDecimal("100.00"), new BigDecimal("50.00"),
        new BigDecimal("10.00"), new BigDecimal("5.00"),
        new BigDecimal("90.00"), new BigDecimal("45.00"), 3L, null,
        "2026-06", "2026-06", false));

    mockMvc.perform(post("/api/apuracao/{id}/reabrir", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABERTO"))
        .andExpect(jsonPath("$.periodo").value("2026-06"));
  }

  @Test
  @DisplayName("POST reabrir de apuracao de outro produtor (ou inexistente) retorna 404")
  void reabrirInexistenteRetorna404() throws Exception {
    UUID id = UUID.randomUUID();
    when(apuracaoService.reabrir(id)).thenThrow(new ResourceNotFoundException("Apuracao", id));

    mockMvc.perform(post("/api/apuracao/{id}/reabrir", id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("POST reabrir de apuracao nao FECHADA retorna 409 estruturado")
  void reabrirNaoFechadaRetorna409() throws Exception {
    UUID id = UUID.randomUUID();
    when(apuracaoService.reabrir(id))
        .thenThrow(new ApuracaoNaoReabrivelException(id, StatusApuracao.ABERTO));

    mockMvc.perform(post("/api/apuracao/{id}/reabrir", id))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errors[0].code").value("APURACAO_NAO_REABRIVEL"));
  }

  @Test
  @DisplayName("POST reabrir fora do prazo de 3 meses retorna 422 estruturado")
  void reabrirForaPrazoRetorna422() throws Exception {
    UUID id = UUID.randomUUID();
    when(apuracaoService.reabrir(id)).thenThrow(
        new ReaberturaForaPrazoException(id, YearMonth.of(2026, 3), YearMonth.of(2026, 4)));

    mockMvc.perform(post("/api/apuracao/{id}/reabrir", id))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.errors[0].code").value("REABERTURA_FORA_PRAZO"));
  }
}
