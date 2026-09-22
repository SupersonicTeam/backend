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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import br.com.agrocontabil.dto.CancelamentoResponse;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.exception.CancelamentoForaPrazoException;
import br.com.agrocontabil.exception.DanfeIndisponivelException;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.exception.NotaFiscalNaoCancelavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoConsultavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoEnviavelException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.XmlIndisponivelException;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.NotaFiscalService;
import br.com.agrocontabil.service.NotaFiscalValidacaoService;
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

  /** Dependencia do controller para o endpoint de envio a SEFAZ (issue #15). */
  @MockBean
  private SefazService sefazService;

  /** Dependencia do controller para o endpoint de historico de validacoes (issue #22). */
  @MockBean
  private NotaFiscalValidacaoService validacaoService;

  /** Dependencia do controller para o endpoint de importacao de XML (issue #19). */
  @MockBean
  private br.com.agrocontabil.service.ImportacaoNotaFiscalService importacaoService;

  /**
   * MockProdutorFilter e um @Component que depende de ProdutorRepository.
   * @WebMvcTest nao sobe JPA, entao o ProdutorRepository precisa ser mockado
   * para satisfazer a dependencia do filtro durante a inicializacao do contexto.
   */
  @MockBean
  private ProdutorRepository produtorRepository;

  /**
   * Os validadores @ValidNcm/@ValidCfop (issue #20) sao @Component instanciados pela fabrica de
   * ConstraintValidator do Spring e injetam estes repositorios por construtor. Como @WebMvcTest
   * nao sobe JPA, mockamos os repositorios para satisfazer a injecao na inicializacao do
   * contexto. Sem stub configurado, findAllCodigos/findCodigosAplicaveisProdutorRural retornam
   * lista vazia (Mockito default) — combinado com a estrategia fail-open dos validadores, NCM e
   * CFOP validos no payload nao sao bloqueados nestes testes de camada web.
   */
  @MockBean
  private br.com.agrocontabil.repository.NcmReferenciaRepository ncmReferenciaRepository;

  @MockBean
  private br.com.agrocontabil.repository.CfopReferenciaRepository cfopReferenciaRepository;

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

  @Test
  @DisplayName("POST com destinatarioCpfCnpj de digito verificador invalido deve retornar 400")
  void postWithInvalidCpfCnpjShouldReturn400() throws Exception {
    // Formato valido (11 digitos) mas DV invalido: @Pattern passa, @ValidCpfCnpj rejeita (#18).
    String payload = """
        {
          "numero": 1,
          "naturezaOperacao": "Venda",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "12345678902",
          "itens": [
            {
              "numeroItem": 1,
              "descricao": "Produto X",
              "ncm": "12010090",
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
        .andExpect(jsonPath("$.errors[?(@.field == 'destinatarioCpfCnpj')]").exists());
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
  @DisplayName("GET /api/notas/{id}/xml deve retornar 409 quando XML nao foi gerado")
  void getXmlShouldReturn409WhenXmlNotYetGenerated() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(notaFiscalService.obterXml(eq(notaId)))
        .thenThrow(new XmlIndisponivelException(notaId, StatusNotaFiscal.RASCUNHO));

    // Simetrico ao /pdf: recurso ainda nao gerado e conflito de estado, nao ausencia de nota.
    // O envelope de erro e JSON (nao ha conversor XML no projeto), por isso o cliente precisa
    // aceitar application/json para ler os detalhes — o Angular ja envia ambos.
    mockMvc.perform(get("/api/notas/{id}/xml", notaId)
            .accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict())
        // O envelope acompanha o Accept: aqui sai em XML, com o mesmo errors[] do JSON.
        .andExpect(xpath("/ApiError/errors/errors/code").string("XML_INDISPONIVEL"));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/xml deve devolver o envelope errors[] em JSON quando o cliente pede JSON")
  void getXmlShouldReturnJsonEnvelopeWhenClientAsksJson() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(notaFiscalService.obterXml(eq(notaId)))
        .thenThrow(new XmlIndisponivelException(notaId, StatusNotaFiscal.RASCUNHO));

    mockMvc.perform(get("/api/notas/{id}/xml", notaId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errors[?(@.code == 'XML_INDISPONIVEL')]").exists());
  }

  @Test
  @DisplayName("GET /api/notas/{id}/xml deve retornar 404 quando a nota nao existe ou e de outro produtor")
  void getXmlShouldReturn404WhenNotaNotFound() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(notaFiscalService.obterXml(eq(notaId)))
        .thenThrow(new ResourceNotFoundException("NotaFiscal", notaId));

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
  // POST /{id}/enviar-sefaz → 200 SefazEnvioResponse  /  409 quando nao enviavel
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas/{id}/enviar-sefaz deve retornar 200 com status AUTORIZADA")
  void postEnviarSefazShouldReturn200WithAutorizadaStatus() throws Exception {
    UUID notaId = UUID.randomUUID();
    SefazEnvioResponse mockResponse = new SefazEnvioResponse(
        "41262612345678000195550010000000011000000012",
        StatusNotaFiscal.AUTORIZADA,
        "100",
        "Autorizado o uso da NF-e",
        "141260000000017",
        OffsetDateTime.now());

    when(sefazService.enviar(eq(notaId))).thenReturn(mockResponse);

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AUTORIZADA"))
        .andExpect(jsonPath("$.cStat").value("100"))
        .andExpect(jsonPath("$.protocolo").value("141260000000017"));
  }

  @Test
  @DisplayName("POST /api/notas/{id}/enviar-sefaz deve retornar 409 quando status nao e XML_GERADO")
  void postEnviarSefazShouldReturn409WhenNotaNotEnviavel() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(sefazService.enviar(eq(notaId)))
        .thenThrow(new NotaFiscalNaoEnviavelException(notaId, StatusNotaFiscal.RASCUNHO));

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
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
    UUID notaId = UUID.randomUUID();
    SefazStatusResponse mockResponse = new SefazStatusResponse(
        "41262612345678000195550010000000011000000012",
        StatusNotaFiscal.AUTORIZADA,
        "100",
        "Autorizado o uso da NF-e",
        "141260000000017",
        OffsetDateTime.now());

    when(sefazService.sincronizarStatus(eq(notaId))).thenReturn(mockResponse);

    mockMvc.perform(get("/api/notas/{id}/status-sefaz", notaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AUTORIZADA"))
        .andExpect(jsonPath("$.cStat").value("100"));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/status-sefaz deve retornar 409 quando nota nao consultavel")
  void getStatusSefazShouldReturn409WhenNotConsultavel() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(sefazService.sincronizarStatus(eq(notaId)))
        .thenThrow(new NotaFiscalNaoConsultavelException(notaId, StatusNotaFiscal.RASCUNHO));

    mockMvc.perform(get("/api/notas/{id}/status-sefaz", notaId))
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
    UUID notaId = UUID.randomUUID();
    UUID lote = UUID.randomUUID();
    NotaFiscalValidacaoResponse validacao = new NotaFiscalValidacaoResponse(
        lote,
        "NOTA_INVALIDA_EMISSAO",
        "item[1].ncm",
        "NCM e obrigatorio no layout da NF-e",
        null,
        null,
        null,
        OffsetDateTime.now());

    when(validacaoService.listarValidacoes(eq(notaId))).thenReturn(List.of(validacao));

    mockMvc.perform(get("/api/notas/{id}/validacoes", notaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].loteValidacao").value(lote.toString()))
        .andExpect(jsonPath("$[0].code").value("NOTA_INVALIDA_EMISSAO"))
        .andExpect(jsonPath("$[0].field").value("item[1].ncm"));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/validacoes deve retornar 200 com lista vazia quando nao ha inconsistencias")
  void getValidacoesShouldReturn200WithEmptyList() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(validacaoService.listarValidacoes(eq(notaId))).thenReturn(List.of());

    mockMvc.perform(get("/api/notas/{id}/validacoes", notaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("GET /api/notas/{id}/validacoes deve retornar 404 para nota inexistente ou de outro produtor")
  void getValidacoesShouldReturn404WhenNotaNotFound() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(validacaoService.listarValidacoes(eq(notaId)))
        .thenThrow(new ResourceNotFoundException("NotaFiscal", notaId));

    mockMvc.perform(get("/api/notas/{id}/validacoes", notaId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.errors[?(@.code == 'RESOURCE_NOT_FOUND')]").exists());
  }

  // -------------------------------------------------------------------------
  // POST /{id}/cancelar → 200 CancelamentoResponse / 400 / 404 / 409 / 422 (issue #21)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas/{id}/cancelar valido deve retornar 200 com status CANCELADA")
  void postCancelarShouldReturn200WithCanceladaStatus() throws Exception {
    UUID notaId = UUID.randomUUID();
    CancelamentoResponse mockResponse = new CancelamentoResponse(
        "41262612345678000195550010000000011000000012",
        StatusNotaFiscal.CANCELADA,
        OffsetDateTime.now(),
        "141260000000099",
        "Erro na quantidade dos produtos vendidos",
        "135",
        "Evento registrado e vinculado a NF-e");

    when(sefazService.cancelar(eq(notaId), eq("Erro na quantidade dos produtos vendidos")))
        .thenReturn(mockResponse);

    String payload = """
        {"motivo": "Erro na quantidade dos produtos vendidos"}
        """;

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELADA"))
        .andExpect(jsonPath("$.sefazCstat").value("135"))
        .andExpect(jsonPath("$.protocoloCancelamento").value("141260000000099"));
  }

  @Test
  @DisplayName("POST /api/notas/{id}/cancelar com motivo curto (< 15 chars) deve retornar 400")
  void postCancelarShouldReturn400WhenMotivoTooShort() throws Exception {
    UUID notaId = UUID.randomUUID();

    String payload = """
        {"motivo": "erro"}
        """;

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[?(@.field == 'motivo')]").exists());
  }

  @Test
  @DisplayName("POST /api/notas/{id}/cancelar deve retornar 409 quando status nao e AUTORIZADA")
  void postCancelarShouldReturn409WhenNotaNaoCancelavel() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(sefazService.cancelar(eq(notaId), any()))
        .thenThrow(new NotaFiscalNaoCancelavelException(notaId, StatusNotaFiscal.XML_GERADO));

    String payload = """
        {"motivo": "Erro na quantidade dos produtos vendidos"}
        """;

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errors[?(@.code == 'NOTA_NAO_CANCELAVEL')]").exists());
  }

  @Test
  @DisplayName("POST /api/notas/{id}/cancelar deve retornar 422 quando fora do prazo de 30 dias")
  void postCancelarShouldReturn422WhenForaPrazo() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(sefazService.cancelar(eq(notaId), any()))
        .thenThrow(new CancelamentoForaPrazoException(notaId, OffsetDateTime.now().minusDays(1)));

    String payload = """
        {"motivo": "Erro na quantidade dos produtos vendidos"}
        """;

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.errors[?(@.code == 'CANCELAMENTO_FORA_PRAZO')]").exists());
  }

  @Test
  @DisplayName("POST /api/notas/{id}/cancelar deve retornar 404 para nota de outro produtor")
  void postCancelarShouldReturn404WhenNotaNotFound() throws Exception {
    UUID notaId = UUID.randomUUID();

    when(sefazService.cancelar(eq(notaId), any()))
        .thenThrow(new ResourceNotFoundException("NotaFiscal", notaId));

    String payload = """
        {"motivo": "Erro na quantidade dos produtos vendidos"}
        """;

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
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
          "destinatarioCpfCnpj": "52998224725",
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
        br.com.agrocontabil.model.TipoDocumentoFiscal.NFE,
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
        br.com.agrocontabil.model.TipoDocumentoFiscal.NFE,
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
