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

import br.com.agrocontabil.dto.CancellationRequest;
import br.com.agrocontabil.dto.ImportItemResult;
import br.com.agrocontabil.dto.ImportBatchResponse;
import br.com.agrocontabil.dto.InvoiceRequest;
import br.com.agrocontabil.dto.InvoiceResponse;
import br.com.agrocontabil.dto.InvoiceValidationResponse;
import br.com.agrocontabil.dto.SefazSubmissionResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.exception.DanfeUnavailableException;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.exception.InvoiceNotQueryableException;
import br.com.agrocontabil.exception.InvoiceNotSubmittableException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.repository.RuralProducerCfopRepository;
import br.com.agrocontabil.repository.AgriculturalNcmRepository;
import br.com.agrocontabil.repository.ProducerRepository;
import br.com.agrocontabil.service.InvoiceImportService;
import br.com.agrocontabil.service.InvoiceService;
import br.com.agrocontabil.service.InvoiceValidationService;
import br.com.agrocontabil.service.SefazService;
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
 * Testes de camada web para {@link InvoiceController}.
 *
 * <p>Usa {@code @WebMvcTest} — nao sobe contexto JPA. O {@link InvoiceService}
 * e mockado. O {@link GlobalExceptionHandler} e importado para testar mapeamento
 * de erros.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>POST valido → 201 com header Location</li>
 *   <li>POST invalido (ncm 7 digitos, items vazios, valor negativo) → 400 com erros por field</li>
 *   <li>GET /{id} nao existente → 404</li>
 * </ul>
 */
@WebMvcTest(controllers = InvoiceController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    })
@Import(GlobalExceptionHandler.class)
class InvoiceControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private InvoiceService invoiceService;

  /** Dependencia do controller para o endpoint de envio a SEFAZ (issue #15). */
  @MockBean
  private SefazService sefazService;

  /** Dependencia do controller para o endpoint de historico de validacoes (issue #22). */
  @MockBean
  private InvoiceValidationService validationService;

  /**
   * MockProducerFilter e um @Component que depende de ProducerRepository.
   * @WebMvcTest nao sobe JPA, entao o ProducerRepository precisa ser mockado
   * para satisfazer a dependencia do filtro durante a inicializacao do contexto.
   */
  @MockBean
  private ProducerRepository produtorRepository;

  /**
   * Os validators @ValidNcm/@ValidCfop injetam estes repositorios. No slice
   * @WebMvcTest (sem JPA) eles precisam existir como bean para a fabrica de
   * validators do Spring conseguir instanciar os ConstraintValidators.
   */
  @MockBean
  private AgriculturalNcmRepository ncmAgropecuarioRepository;

  @MockBean
  private RuralProducerCfopRepository cfopProdutorRuralRepository;

  @MockBean
  private InvoiceImportService invoiceImportService;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.disable(
        com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // Por padrao, NCM/CFOP sao considerados validos (cadastrados) para nao
    // bloquear os caminhos felizes. Testes especificos sobrescrevem com false.
    when(ncmAgropecuarioRepository.existsByCodigo(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    when(cfopProdutorRuralRepository.existsByCodigo(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
  }

  // -------------------------------------------------------------------------
  // POST valido → 201 + Location
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas com payload valido deve retornar 201 com header Location")
  void postValidPayloadShouldReturn201WithLocation() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    InvoiceResponse mockResponse = buildResponse(invoiceId);

    when(invoiceService.create(any(InvoiceRequest.class))).thenReturn(mockResponse);

    String payload = buildValidPayload();

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location",
            org.hamcrest.Matchers.containsString("/api/notas/" + invoiceId)))
        .andExpect(jsonPath("$.id").value(invoiceId.toString()))
        .andExpect(jsonPath("$.status").value("RASCUNHO"))
        .andExpect(jsonPath("$.numero").value(1));
  }

  // -------------------------------------------------------------------------
  // POST invalido → 400 com erros por field
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
        .andExpect(jsonPath("$.errors[?(@.field == 'items[0].ncm')]").exists())
        .andExpect(jsonPath("$.errors[?(@.code == 'VALIDATION_ERROR')]").exists());
  }

  @Test
  @DisplayName("POST com NCM bem-formado porem nao cadastrado deve retornar 400")
  void postWithUnregisteredNcmShouldReturn400() throws Exception {
    // NCM com 8 digitos (passa no @Pattern) mas ausente da tabela de referencia
    when(ncmAgropecuarioRepository.existsByCodigo("00000000")).thenReturn(false);

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
              "ncm": "00000000",
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
        .andExpect(jsonPath("$.errors[?(@.field == 'items[0].ncm')]").exists());
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
        .andExpect(jsonPath("$.errors[?(@.field == 'items')]").exists());
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
            jsonPath("$.errors[?(@.field == 'items[0].unitPrice')]").exists());
  }

  @Test
  @DisplayName("POST cujo servico lanca CfopIncompativelException retorna 400")
  void postComCfopIncompativelRetorna400() throws Exception {
    when(invoiceService.create(any(InvoiceRequest.class)))
        .thenThrow(new br.com.agrocontabil.exception.IncompatibleCfopException(
            "Item 1: CFOP 5101 (INTERNA) incompativel com operacao INTERESTADUAL. Use um CFOP 6xxx."));

    mockMvc.perform(post("/api/notas")
            .contentType(MediaType.APPLICATION_JSON)
            .content(buildValidPayload()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("incompativel")));
  }

  // -------------------------------------------------------------------------
  // GET /{id} nao existente → 404
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/notas/{id} com id inexistente deve retornar 404")
  void getByIdNotFoundShouldReturn404() throws Exception {
    UUID invoiceId = UUID.randomUUID();

    when(invoiceService.findById(eq(invoiceId)))
        .thenThrow(new ResourceNotFoundException("NotaFiscal", invoiceId));

    mockMvc.perform(get("/api/notas/{id}", invoiceId))
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
    UUID invoiceId = UUID.randomUUID();
    InvoiceResponse mockResponse = buildResponse(invoiceId);

    when(invoiceService.update(eq(invoiceId), any(InvoiceRequest.class)))
        .thenReturn(mockResponse);

    mockMvc.perform(put("/api/notas/{id}", invoiceId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(buildValidPayload()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(invoiceId.toString()))
        .andExpect(jsonPath("$.status").value("RASCUNHO"))
        .andExpect(jsonPath("$.numero").value(1));
  }

  // -------------------------------------------------------------------------
  // PUT invalido → 400
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("PUT com lista de itens vazia deve retornar 400 com erro no campo itens")
  void putWithEmptyItensShouldReturn400() throws Exception {
    UUID invoiceId = UUID.randomUUID();

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

    mockMvc.perform(put("/api/notas/{id}", invoiceId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[?(@.field == 'items')]").exists());
  }

  @Test
  @DisplayName("PUT com ncm de 7 digitos deve retornar 400 com erro no campo ncm")
  void putWithInvalidNcmShouldReturn400() throws Exception {
    UUID invoiceId = UUID.randomUUID();

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

    mockMvc.perform(put("/api/notas/{id}", invoiceId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[?(@.field == 'items[0].ncm')]").exists());
  }

  // -------------------------------------------------------------------------
  // DELETE → 204
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("DELETE /api/notas/{id} deve retornar 204 sem corpo")
  void deleteShouldReturn204() throws Exception {
    UUID invoiceId = UUID.randomUUID();

    doNothing().when(invoiceService).delete(eq(invoiceId));

    mockMvc.perform(delete("/api/notas/{id}", invoiceId))
        .andExpect(status().isNoContent());
  }

  // -------------------------------------------------------------------------
  // POST /importInvoices → 200 relatorio  /  400 sem files
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas/importar com 2 arquivos retorna 200 com relatorio")
  void importarMultiplosArquivosRetorna200() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    ImportBatchResponse relatorio = ImportBatchResponse.de(java.util.List.of(
        ImportItemResult.success("nota1.xml", invoiceId,
            "41260612345678000195550010000000011000000017"),
        ImportItemResult.error("nota2.xml", "XML invalido")));
    when(invoiceImportService.importInvoices(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(relatorio);

    org.springframework.mock.web.MockMultipartFile a1 =
        new org.springframework.mock.web.MockMultipartFile(
            "arquivos", "nota1.xml", "application/xml", "<NFe/>".getBytes());
    org.springframework.mock.web.MockMultipartFile a2 =
        new org.springframework.mock.web.MockMultipartFile(
            "arquivos", "nota2.xml", "application/xml", "<x/>".getBytes());

    mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/notas/importar").file(a1).file(a2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.importados").value(1))
        .andExpect(jsonPath("$.falhas").value(1))
        .andExpect(jsonPath("$.resultados").isArray());
  }

  @Test
  @DisplayName("POST /api/notas/importar sem arquivos retorna 400")
  void importarSemArquivosRetorna400() throws Exception {
    mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/notas/importar"))
        .andExpect(status().isBadRequest());
  }

  // -------------------------------------------------------------------------
  // POST /{id}/generate-xml → 200 InvoiceResponse
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas/{id}/gerar-xml deve retornar 200 com status XML_GERADO")
  void postGerarXmlShouldReturn200WithXmlGeradoStatus() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    InvoiceResponse mockResponse = buildResponseXmlGerado(invoiceId);

    when(invoiceService.generateXml(eq(invoiceId))).thenReturn(mockResponse);

    mockMvc.perform(post("/api/notas/{id}/gerar-xml", invoiceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(invoiceId.toString()))
        .andExpect(jsonPath("$.status").value("XML_GERADO"))
        .andExpect(jsonPath("$.chaveAcesso").exists());
  }

  @Test
  @DisplayName("POST /api/notas/{id}/gerar-xml deve retornar 409 quando status nao e RASCUNHO")
  void postGerarXmlShouldReturn409WhenStatusIsNotRascunho() throws Exception {
    UUID invoiceId = UUID.randomUUID();

    when(invoiceService.generateXml(eq(invoiceId)))
        .thenThrow(new br.com.agrocontabil.exception.InvoiceNotEditableException(
            invoiceId, br.com.agrocontabil.model.InvoiceStatus.XML_GERADO));

    mockMvc.perform(post("/api/notas/{id}/gerar-xml", invoiceId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  // -------------------------------------------------------------------------
  // POST /{id}/cancel → 200 / 422 / 409 / 400
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas/{id}/cancelar valido retorna 200")
  void cancelarValidoRetorna200() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    when(invoiceService.cancel(eq(invoiceId), any(CancellationRequest.class)))
        .thenReturn(buildResponse(invoiceId));

    String payload = "{\"motivo\": \"Erro nos dados do destinatario informado\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", invoiceId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(invoiceId.toString()));
  }

  @Test
  @DisplayName("POST cancelar fora do prazo (servico lanca BusinessException) retorna 422")
  void cancelarForaDoPrazoRetorna422() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    when(invoiceService.cancel(eq(invoiceId), any(CancellationRequest.class)))
        .thenThrow(new br.com.agrocontabil.exception.BusinessException(
            "Cancelamento nao permitido: 31 dias, acima do limite de 30 dias (RN006)."));

    String payload = "{\"motivo\": \"Erro nos dados do destinatario informado\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", invoiceId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("RN006")));
  }

  @Test
  @DisplayName("POST cancelar com status invalido (servico lanca 409) retorna 409")
  void cancelarStatusInvalidoRetorna409() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    when(invoiceService.cancel(eq(invoiceId), any(CancellationRequest.class)))
        .thenThrow(br.com.agrocontabil.exception.InvoiceNotEditableException
            .naoCancelavel(invoiceId, InvoiceStatus.RASCUNHO));

    String payload = "{\"motivo\": \"Erro nos dados do destinatario informado\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", invoiceId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  @DisplayName("POST cancelar com motivo curto (<15) retorna 400")
  void cancelarMotivoCurtoRetorna400() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    String payload = "{\"motivo\": \"curto\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", invoiceId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'reason')]").exists());
  }

  // -------------------------------------------------------------------------
  // GET /{id}/xml → 200 application/xml  /  404 quando nao gerado
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/notas/{id}/xml deve retornar 200 com Content-Type application/xml")
  void getXmlShouldReturn200WithApplicationXmlContentType() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    String xmlFake = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><NFe><infNFe/></NFe>";

    when(invoiceService.getXml(eq(invoiceId))).thenReturn(xmlFake);

    mockMvc.perform(get("/api/notas/{id}/xml", invoiceId)
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
    UUID invoiceId = UUID.randomUUID();

    when(invoiceService.getXml(eq(invoiceId)))
        .thenThrow(new ResourceNotFoundException(
            "XML da NF-e ainda nao gerado para a nota '" + invoiceId + "'."));

    mockMvc.perform(get("/api/notas/{id}/xml", invoiceId)
            .accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------
  // GET /{id}/pdf → 200 application/pdf  /  409 quando DANFE indisponivel
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/notas/{id}/pdf deve retornar 200 com Content-Type application/pdf")
  void getPdfShouldReturn200WithPdfContentType() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    byte[] pdf = new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '4'};

    when(invoiceService.generatePdf(eq(invoiceId))).thenReturn(pdf);

    mockMvc.perform(get("/api/notas/{id}/pdf", invoiceId))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.containsString("danfe-" + invoiceId + ".pdf")));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/pdf deve retornar 409 quando a DANFE esta indisponivel")
  void getPdfShouldReturn409WhenDanfeUnavailable() throws Exception {
    UUID invoiceId = UUID.randomUUID();

    when(invoiceService.generatePdf(eq(invoiceId)))
        .thenThrow(new DanfeUnavailableException(invoiceId, InvoiceStatus.RASCUNHO));

    mockMvc.perform(get("/api/notas/{id}/pdf", invoiceId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errors[?(@.code == 'DANFE_INDISPONIVEL')]").exists());
  }

  // -------------------------------------------------------------------------
  // POST /{id}/send-sefaz → 200 SefazSubmissionResponse  /  409 quando nao enviavel
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas/{id}/enviar-sefaz deve retornar 200 com status AUTORIZADA")
  void postEnviarSefazShouldReturn200WithAutorizadaStatus() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    SefazSubmissionResponse mockResponse = new SefazSubmissionResponse(
        "41262612345678000195550010000000011000000012",
        InvoiceStatus.AUTORIZADA,
        "100",
        "Autorizado o uso da NF-e",
        "141260000000017",
        OffsetDateTime.now());

    when(sefazService.send(eq(invoiceId))).thenReturn(mockResponse);

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", invoiceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AUTORIZADA"))
        .andExpect(jsonPath("$.cStat").value("100"))
        .andExpect(jsonPath("$.protocolo").value("141260000000017"));
  }

  @Test
  @DisplayName("POST /api/notas/{id}/enviar-sefaz deve retornar 409 quando status nao e XML_GERADO")
  void postEnviarSefazShouldReturn409WhenNotaNotEnviavel() throws Exception {
    UUID invoiceId = UUID.randomUUID();

    when(sefazService.send(eq(invoiceId)))
        .thenThrow(new InvoiceNotSubmittableException(invoiceId, InvoiceStatus.RASCUNHO));

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", invoiceId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errors[?(@.code == 'NOTA_NAO_ENVIAVEL')]").exists());
  }

  // -------------------------------------------------------------------------
  // GET /{id}/status-sefaz → 200 SefazStatusResponse  /  409 quando nao consultavel
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/notas/{id}/status-sefaz deve retornar 200 com status sincronizado")
  void getStatusSefazShouldReturn200() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    SefazStatusResponse mockResponse = new SefazStatusResponse(
        "41262612345678000195550010000000011000000012",
        InvoiceStatus.AUTORIZADA,
        "100",
        "Autorizado o uso da NF-e",
        "141260000000017",
        OffsetDateTime.now());

    when(sefazService.syncStatus(eq(invoiceId))).thenReturn(mockResponse);

    mockMvc.perform(get("/api/notas/{id}/status-sefaz", invoiceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AUTORIZADA"))
        .andExpect(jsonPath("$.cStat").value("100"));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/status-sefaz deve retornar 409 quando nota nao consultavel")
  void getStatusSefazShouldReturn409WhenNotConsultavel() throws Exception {
    UUID invoiceId = UUID.randomUUID();

    when(sefazService.syncStatus(eq(invoiceId)))
        .thenThrow(new InvoiceNotQueryableException(invoiceId, InvoiceStatus.RASCUNHO));

    mockMvc.perform(get("/api/notas/{id}/status-sefaz", invoiceId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errors[?(@.code == 'NOTA_NAO_CONSULTAVEL')]").exists());
  }

  // -------------------------------------------------------------------------
  // GET /{id}/validacoes → 200 historico  /  404 anti-IDOR (issue #22)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/notas/{id}/validacoes deve retornar 200 com o historico de inconsistencias")
  void getValidacoesShouldReturn200WithHistory() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    UUID lote = UUID.randomUUID();
    InvoiceValidationResponse validacao = new InvoiceValidationResponse(
        lote,
        "NOTA_INVALIDA_EMISSAO",
        "item[1].ncm",
        "NCM e obrigatorio no layout da NF-e",
        null,
        null,
        null,
        OffsetDateTime.now());

    when(validationService.listValidations(eq(invoiceId))).thenReturn(List.of(validacao));

    mockMvc.perform(get("/api/notas/{id}/validacoes", invoiceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].loteValidacao").value(lote.toString()))
        .andExpect(jsonPath("$[0].code").value("NOTA_INVALIDA_EMISSAO"))
        .andExpect(jsonPath("$[0].field").value("item[1].ncm"));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/validacoes deve retornar 200 com lista vazia quando nao ha inconsistencias")
  void getValidacoesShouldReturn200WithEmptyList() throws Exception {
    UUID invoiceId = UUID.randomUUID();

    when(validationService.listValidations(eq(invoiceId))).thenReturn(List.of());

    mockMvc.perform(get("/api/notas/{id}/validacoes", invoiceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/validacoes deve retornar 404 para nota inexistente ou de outro produtor")
  void getValidacoesShouldReturn404WhenNotaNotFound() throws Exception {
    UUID invoiceId = UUID.randomUUID();

    when(validationService.listValidations(eq(invoiceId)))
        .thenThrow(new ResourceNotFoundException("NotaFiscal", invoiceId));

    mockMvc.perform(get("/api/notas/{id}/validacoes", invoiceId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.errors[?(@.code == 'RESOURCE_NOT_FOUND')]").exists());
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

  private InvoiceResponse buildResponse(UUID id) {
    return new InvoiceResponse(
        id,
        null,
        1L,
        1,
        "55",
        "Venda de producao rural",
        InvoiceStatus.RASCUNHO,
        OffsetDateTime.now(),
        null,
        UUID.randomUUID(),
        "Jose Comprador",
        "12345678901",
        null,
        null,
        new BigDecimal("1000.00"),
        new BigDecimal("1000.00"),
        new BigDecimal("1.00"),   // totalIbsValue — informativo 2026
        new BigDecimal("9.00"),   // totalCbsValue — informativo 2026
        List.of(),
        OffsetDateTime.now(),
        OffsetDateTime.now(),
        br.com.agrocontabil.model.DocumentType.NFE,
        null,
        null);
  }

  private InvoiceResponse buildResponseXmlGerado(UUID id) {
    return new InvoiceResponse(
        id,
        "41262612345678000195550010000000011000000012", // accessKey — 44 digitos
        1L,
        1,
        "55",
        "Venda de producao rural",
        InvoiceStatus.XML_GERADO,
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
        OffsetDateTime.now(),
        br.com.agrocontabil.model.DocumentType.NFE,
        null,
        null);
  }
}
