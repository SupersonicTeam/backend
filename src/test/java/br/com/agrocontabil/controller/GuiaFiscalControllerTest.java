package br.com.agrocontabil.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.GuiaFiscalRequest;
import br.com.agrocontabil.dto.GuiaFiscalResponse;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.exception.GuiaJaPagaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.GuiaFiscalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de camada web do {@link GuiaFiscalController} (issue #74).
 *
 * <p>Usa {@code @WebMvcTest} (sem JPA). O {@link GuiaFiscalService} é mockado e o
 * {@link GlobalExceptionHandler} importado para validar o envelope {@code errors[]} dos erros.
 */
@WebMvcTest(controllers = GuiaFiscalController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    })
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class GuiaFiscalControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private GuiaFiscalService guiaFiscalService;

  /** MockIdentidadeFilter (@Component) depende de ProdutorRepository — mockado no @WebMvcTest. */
  @MockBean private ProdutorRepository produtorRepository;

  /** O filtro tambem resolve contador (issue #93) — mockado junto no @WebMvcTest. */
  @MockBean private ContadorRepository contadorRepository;

  private GuiaFiscalResponse respostaExemplo(UUID produtorId) {
    return new GuiaFiscalResponse(
        UUID.randomUUID(), produtorId, "ICMS", LocalDate.of(2026, 8, 20),
        new BigDecimal("1250.00"), "Referente a agosto/2026", "PENDENTE", null,
        OffsetDateTime.now());
  }

  @Test
  @DisplayName("POST /api/contador/guias com dados validos retorna 201 com Location")
  void criarComDadosValidosRetorna201() throws Exception {
    UUID produtorId = UUID.randomUUID();
    GuiaFiscalRequest request = new GuiaFiscalRequest(
        produtorId, TipoGuiaFiscal.ICMS, LocalDate.of(2026, 8, 20),
        new BigDecimal("1250.00"), "Referente a agosto/2026");
    when(guiaFiscalService.criar(any())).thenReturn(respostaExemplo(produtorId));

    mockMvc.perform(post("/api/contador/guias")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.tipoGuia").value("ICMS"))
        .andExpect(jsonPath("$.status").value("PENDENTE"));
  }

  @Test
  @DisplayName("POST /api/contador/guias sem produtorId retorna 400 estruturado (errors[])")
  void criarSemProdutorIdRetorna400() throws Exception {
    String corpoInvalido = """
        {"tipoGuia":"ICMS","dataVencimento":"2026-08-20","valor":100.00}
        """;

    mockMvc.perform(post("/api/contador/guias")
            .contentType(MediaType.APPLICATION_JSON)
            .content(corpoInvalido))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("POST /api/contador/guias com produtorId inexistente retorna 404")
  void criarComProdutorInexistenteRetorna404() throws Exception {
    UUID produtorId = UUID.randomUUID();
    GuiaFiscalRequest request = new GuiaFiscalRequest(
        produtorId, TipoGuiaFiscal.DARF, LocalDate.of(2026, 8, 20), new BigDecimal("100.00"), null);
    when(guiaFiscalService.criar(any()))
        .thenThrow(new ResourceNotFoundException("Produtor", produtorId));

    mockMvc.perform(post("/api/contador/guias")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("GET /api/contador/guias?produtorId= de outro produtor retorna 404 (#94)")
  void listarDeOutroProdutorRetorna404() throws Exception {
    UUID produtorDeOutroDono = UUID.randomUUID();
    when(guiaFiscalService.listar(produtorDeOutroDono, 0, 100))
        .thenThrow(new ResourceNotFoundException("Produtor", produtorDeOutroDono));

    mockMvc.perform(get("/api/contador/guias").param("produtorId", produtorDeOutroDono.toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("GET /api/contador/guias?produtorId= retorna 200 com array e X-Total-Count (#103)")
  void listarRetorna200ComListaDoProdutor() throws Exception {
    UUID produtorId = UUID.randomUUID();
    // Pagina padrao (0, 100) com 1 guia de um total de 150 do produtor.
    when(guiaFiscalService.listar(produtorId, 0, 100)).thenReturn(
        new PageImpl<>(List.of(respostaExemplo(produtorId)), PageRequest.of(0, 100), 150));

    mockMvc.perform(get("/api/contador/guias").param("produtorId", produtorId.toString()))
        .andExpect(status().isOk())
        // Corpo continua sendo array: o Angular atual le GuiaFiscal[] (compatibilidade).
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].produtorId").value(produtorId.toString()))
        .andExpect(header().string(GuiaFiscalController.HEADER_TOTAL, "150"));
  }

  @Test
  @DisplayName("GET /api/contador/guias repassa page e size informados (#103)")
  void listarRepassaPaginaETamanho() throws Exception {
    UUID produtorId = UUID.randomUUID();
    when(guiaFiscalService.listar(produtorId, 3, 20)).thenReturn(Page.empty());

    mockMvc.perform(get("/api/contador/guias")
            .param("produtorId", produtorId.toString())
            .param("page", "3")
            .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty())
        .andExpect(header().string(GuiaFiscalController.HEADER_TOTAL, "0"));
  }

  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({"size,201", "size,0", "page,-1", "page,10001"})
  @DisplayName("GET /api/contador/guias com page/size fora da faixa retorna 400 no envelope (#103)")
  void listarComPaginacaoForaDaFaixaRetorna400(String parametro, String valor) throws Exception {
    mockMvc.perform(get("/api/contador/guias")
            .param("produtorId", UUID.randomUUID().toString())
            .param(parametro, valor))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value(parametro));
    verifyNoInteractions(guiaFiscalService);
  }

  @Test
  @DisplayName("PATCH /api/contador/guias/{id}/pagar retorna 200 com status PAGA")
  void pagarRetorna200ComStatusPaga() throws Exception {
    UUID guiaId = UUID.randomUUID();
    GuiaFiscalResponse paga = new GuiaFiscalResponse(
        guiaId, UUID.randomUUID(), "ICMS", LocalDate.of(2026, 8, 20),
        new BigDecimal("1250.00"), null, "PAGA", OffsetDateTime.now(), OffsetDateTime.now());
    when(guiaFiscalService.marcarComoPaga(guiaId)).thenReturn(paga);

    mockMvc.perform(patch("/api/contador/guias/{id}/pagar", guiaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAGA"));
  }

  @Test
  @DisplayName("PATCH /api/contador/guias/{id}/pagar retorna 409 quando ja esta paga")
  void pagarRetorna409QuandoJaPaga() throws Exception {
    UUID guiaId = UUID.randomUUID();
    when(guiaFiscalService.marcarComoPaga(guiaId)).thenThrow(new GuiaJaPagaException(guiaId));

    mockMvc.perform(patch("/api/contador/guias/{id}/pagar", guiaId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("GUIA_JA_PAGA"));
  }

  @Test
  @DisplayName("PATCH /api/contador/guias/{id}/pagar retorna 404 quando a guia nao pertence ao produtor")
  void pagarRetorna404QuandoNaoPertenceAoProdutor() throws Exception {
    UUID guiaId = UUID.randomUUID();
    when(guiaFiscalService.marcarComoPaga(guiaId))
        .thenThrow(new ResourceNotFoundException("Guia fiscal", guiaId));

    mockMvc.perform(patch("/api/contador/guias/{id}/pagar", guiaId))
        .andExpect(status().isNotFound());
  }
}
