package br.com.agrocontabil.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.exception.DanfeIndisponivelException;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.NotaFiscalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes de camada web para {@link NotaFiscalController}.
 *
 * <p>Usa {@code @WebMvcTest} — nao sobe contexto JPA. O {@link NotaFiscalService}
 * e mockado. O {@link GlobalExceptionHandler} e importado para testar mapeamento
 * de erros.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>POST valido → 201 com header Location</li>
 *   <li>POST invalido (ncm 7 digitos, itens vazios, valor negativo) → 400 com erros por campo</li>
 *   <li>GET /{id} nao existente → 404</li>
 * </ul>
 */
@WebMvcTest(controllers = NotaFiscalController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    })
@Import(GlobalExceptionHandler.class)
class NotaFiscalControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private NotaFiscalService notaFiscalService;

  /**
   * MockProdutorFilter e um @Component que depende de ProdutorRepository.
   * @WebMvcTest nao sobe JPA, entao o ProdutorRepository precisa ser mockado
   * para satisfazer a dependencia do filtro durante a inicializacao do contexto.
   */
  @MockBean
  private ProdutorRepository produtorRepository;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.disable(
        com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  // -------------------------------------------------------------------------
  // POST valido → 201 + Location
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas com payload valido deve retornar 201 com header Location")
  void postValidPayloadShouldReturn201WithLocation() throws Exception {
    UUID notaId = UUID.randomUUID();
    NotaFiscalResponse mockResponse = buildResponse(notaId);

    when(notaFiscalService.create(any(NotaFiscalRequest.class))).thenReturn(mockResponse);

    String payload = buildValidPayload();

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location",
            org.hamcrest.Matchers.containsString("/api/notas/" + notaId)))
        .andExpect(jsonPath("$.id").value(notaId.toString()))
        .andExpect(jsonPath("$.status").value("RASCUNHO"))
        .andExpect(jsonPath("$.numero").value(1));
  }

  // -------------------------------------------------------------------------
  // POST invalido → 400 com erros por campo
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST com ncm de 7 digitos deve retornar 400 com erro no campo ncm")
  void postWithInvalidNcmShouldReturn400WithFieldError() throws Exception {
    String payload = """
        {
          "numero": 1,
          "naturezaOperacao": "Venda",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "12345678901",
          "itens": [
            {
              "numeroItem": 1,
              "descricao": "Produto X",
              "ncm": "1234567",
              "cfop": "6101",
              "unidade": "UN",
              "quantidade": 10,
              "valorUnitario": 5.00
            }
          ]
        }
        """;

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[?(@.field == 'itens[0].ncm')]").exists())
        .andExpect(jsonPath("$.errors[?(@.code == 'VALIDATION_ERROR')]").exists());
  }

  @Test
  @DisplayName("POST com lista de itens vazia deve retornar 400 com erro no campo itens")
  void postWithEmptyItensShouldReturn400WithFieldError() throws Exception {
    String payload = """
        {
          "numero": 1,
          "naturezaOperacao": "Venda",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "12345678901",
          "itens": []
        }
        """;

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[?(@.field == 'itens')]").exists());
  }

  @Test
  @DisplayName("POST com valorUnitario negativo deve retornar 400 com erro no campo valorUnitario")
  void postWithNegativeValueShouldReturn400WithFieldError() throws Exception {
    String payload = """
        {
          "numero": 1,
          "naturezaOperacao": "Venda",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "12345678901",
          "itens": [
            {
              "numeroItem": 1,
              "descricao": "Produto X",
              "ncm": "12010090",
              "cfop": "6101",
              "unidade": "UN",
              "quantidade": 10,
              "valorUnitario": -5.00
            }
          ]
        }
        """;

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(
            jsonPath("$.errors[?(@.field == 'itens[0].valorUnitario')]").exists());
  }

  // -------------------------------------------------------------------------
  // GET /{id} nao existente → 404
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/notas/{id} com id inexistente deve retornar 404")
  void getByIdNotFoundShouldReturn404() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(notaFiscalService.findById(eq(notaId)))
        .thenThrow(new ResourceNotFoundException("NotaFiscal", notaId));

    mockMvc.perform(get("/api/notas/{id}", notaId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[?(@.code == 'RESOURCE_NOT_FOUND')]").exists());
  }

  // -------------------------------------------------------------------------
  // PUT valido → 200
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("PUT /api/notas/{id} com payload valido deve retornar 200 com nota atualizada")
  void putValidPayloadShouldReturn200() throws Exception {
    UUID notaId = UUID.randomUUID();
    NotaFiscalResponse mockResponse = buildResponse(notaId);

    when(notaFiscalService.update(eq(notaId), any(NotaFiscalRequest.class)))
        .thenReturn(mockResponse);

    mockMvc.perform(put("/api/notas/{id}", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(buildValidPayload()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(notaId.toString()))
        .andExpect(jsonPath("$.status").value("RASCUNHO"))
        .andExpect(jsonPath("$.numero").value(1));
  }

  // -------------------------------------------------------------------------
  // PUT invalido → 400
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("PUT com lista de itens vazia deve retornar 400 com erro no campo itens")
  void putWithEmptyItensShouldReturn400() throws Exception {
    UUID notaId = UUID.randomUUID();

    String payload = """
        {
          "numero": 1,
          "naturezaOperacao": "Venda",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "12345678901",
          "itens": []
        }
        """;

    mockMvc.perform(put("/api/notas/{id}", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[?(@.field == 'itens')]").exists());
  }

  @Test
  @DisplayName("PUT com ncm de 7 digitos deve retornar 400 com erro no campo ncm")
  void putWithInvalidNcmShouldReturn400() throws Exception {
    UUID notaId = UUID.randomUUID();

    String payload = """
        {
          "numero": 1,
          "naturezaOperacao": "Venda",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "12345678901",
          "itens": [
            {
              "numeroItem": 1,
              "descricao": "Produto X",
              "ncm": "1234567",
              "cfop": "6101",
              "unidade": "UN",
              "quantidade": 10,
              "valorUnitario": 5.00
            }
          ]
        }
        """;

    mockMvc.perform(put("/api/notas/{id}", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[?(@.field == 'itens[0].ncm')]").exists());
  }

  // -------------------------------------------------------------------------
  // DELETE → 204
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("DELETE /api/notas/{id} deve retornar 204 sem corpo")
  void deleteShouldReturn204() throws Exception {
    UUID notaId = UUID.randomUUID();

    doNothing().when(notaFiscalService).delete(eq(notaId));

    mockMvc.perform(delete("/api/notas/{id}", notaId))
        .andExpect(status().isNoContent());
  }

  // -------------------------------------------------------------------------
  // POST /{id}/gerar-xml → 200 NotaFiscalResponse
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas/{id}/gerar-xml deve retornar 200 com status XML_GERADO")
  void postGerarXmlShouldReturn200WithXmlGeradoStatus() throws Exception {
    UUID notaId = UUID.randomUUID();
    NotaFiscalResponse mockResponse = buildResponseXmlGerado(notaId);

    when(notaFiscalService.gerarXml(eq(notaId))).thenReturn(mockResponse);

    mockMvc.perform(post("/api/notas/{id}/gerar-xml", notaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(notaId.toString()))
        .andExpect(jsonPath("$.status").value("XML_GERADO"))
        .andExpect(jsonPath("$.chaveAcesso").exists());
  }

  @Test
  @DisplayName("POST /api/notas/{id}/gerar-xml deve retornar 409 quando status nao e RASCUNHO")
  void postGerarXmlShouldReturn409WhenStatusIsNotRascunho() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(notaFiscalService.gerarXml(eq(notaId)))
        .thenThrow(new br.com.agrocontabil.exception.NotaFiscalNaoEditavelException(
            notaId, br.com.agrocontabil.model.StatusNotaFiscal.XML_GERADO));

    mockMvc.perform(post("/api/notas/{id}/gerar-xml", notaId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  // -------------------------------------------------------------------------
  // GET /{id}/xml → 200 application/xml  /  404 quando nao gerado
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/notas/{id}/xml deve retornar 200 com Content-Type application/xml")
  void getXmlShouldReturn200WithApplicationXmlContentType() throws Exception {
    UUID notaId = UUID.randomUUID();
    String xmlFake = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><NFe><infNFe/></NFe>";

    when(notaFiscalService.obterXml(eq(notaId))).thenReturn(xmlFake);

    mockMvc.perform(get("/api/notas/{id}/xml", notaId)
            .accept(MediaType.APPLICATION_XML))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .content().string(xmlFake));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/xml deve retornar 404 quando XML nao foi gerado")
  void getXmlShouldReturn404WhenXmlNotYetGenerated() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(notaFiscalService.obterXml(eq(notaId)))
        .thenThrow(new ResourceNotFoundException(
            "XML da NF-e ainda nao gerado para a nota '" + notaId + "'."));

    mockMvc.perform(get("/api/notas/{id}/xml", notaId)
            .accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------
  // GET /{id}/pdf → 200 application/pdf  /  409 quando DANFE indisponivel
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/notas/{id}/pdf deve retornar 200 com Content-Type application/pdf")
  void getPdfShouldReturn200WithPdfContentType() throws Exception {
    UUID notaId = UUID.randomUUID();
    byte[] pdf = new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '4'};

    when(notaFiscalService.gerarPdf(eq(notaId))).thenReturn(pdf);

    mockMvc.perform(get("/api/notas/{id}/pdf", notaId))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.containsString("danfe-" + notaId + ".pdf")));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/pdf deve retornar 409 quando a DANFE esta indisponivel")
  void getPdfShouldReturn409WhenDanfeUnavailable() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(notaFiscalService.gerarPdf(eq(notaId)))
        .thenThrow(new DanfeIndisponivelException(notaId, StatusNotaFiscal.RASCUNHO));

    mockMvc.perform(get("/api/notas/{id}/pdf", notaId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errors[?(@.code == 'DANFE_INDISPONIVEL')]").exists());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private String buildValidPayload() throws Exception {
    return """
        {
          "numero": 1,
          "serie": 1,
          "naturezaOperacao": "Venda de producao rural",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Jose Comprador",
          "destinatarioCpfCnpj": "12345678901",
          "itens": [
            {
              "numeroItem": 1,
              "descricao": "Soja em graos",
              "ncm": "12010090",
              "cfop": "6101",
              "unidade": "KG",
              "quantidade": 500.0,
              "valorUnitario": 2.00
            }
          ]
        }
        """;
  }

  private NotaFiscalResponse buildResponse(UUID id) {
    return new NotaFiscalResponse(
        id,
        null,
        1L,
        1,
        "55",
        "Venda de producao rural",
        StatusNotaFiscal.RASCUNHO,
        OffsetDateTime.now(),
        null,
        UUID.randomUUID(),
        "Jose Comprador",
        "12345678901",
        null,
        null,
        new BigDecimal("1000.00"),
        new BigDecimal("1000.00"),
        new BigDecimal("1.00"),   // valorTotalIbs — informativo 2026
        new BigDecimal("9.00"),   // valorTotalCbs — informativo 2026
        List.of(),
        OffsetDateTime.now(),
        OffsetDateTime.now());
  }

  private NotaFiscalResponse buildResponseXmlGerado(UUID id) {
    return new NotaFiscalResponse(
        id,
        "41262612345678000195550010000000011000000012", // chaveAcesso — 44 digitos
        1L,
        1,
        "55",
        "Venda de producao rural",
        StatusNotaFiscal.XML_GERADO,
        OffsetDateTime.now(),
        null,
        UUID.randomUUID(),
        "Jose Comprador",
        "12345678901",
        null,
        null,
        new BigDecimal("1000.00"),
        new BigDecimal("1000.00"),
        new BigDecimal("1.00"),
        new BigDecimal("9.00"),
        List.of(),
        OffsetDateTime.now(),
        OffsetDateTime.now());
  }
}
