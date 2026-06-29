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

import br.com.agrocontabil.dto.CancelamentoRequest;
import br.com.agrocontabil.dto.ImportacaoItemResultado;
import br.com.agrocontabil.dto.ImportacaoLoteResponse;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.exception.DanfeIndisponivelException;
import br.com.agrocontabil.exception.GlobalExceptionHandler;
import br.com.agrocontabil.exception.NotaFiscalNaoConsultavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoEnviavelException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.CfopProdutorRuralRepository;
import br.com.agrocontabil.repository.NcmAgropecuarioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.ImportacaoNotaFiscalService;
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

  /**
   * MockProdutorFilter e um @Component que depende de ProdutorRepository.
   * @WebMvcTest nao sobe JPA, entao o ProdutorRepository precisa ser mockado
   * para satisfazer a dependencia do filtro durante a inicializacao do contexto.
   */
  @MockBean
  private ProdutorRepository produtorRepository;

  /**
   * Os validators @ValidNcm/@ValidCfop injetam estes repositorios. No slice
   * @WebMvcTest (sem JPA) eles precisam existir como bean para a fabrica de
   * validators do Spring conseguir instanciar os ConstraintValidators.
   */
  @MockBean
  private NcmAgropecuarioRepository ncmAgropecuarioRepository;

  @MockBean
  private CfopProdutorRuralRepository cfopProdutorRuralRepository;

  @MockBean
  private ImportacaoNotaFiscalService importacaoNotaFiscalService;

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
        .andExpect(jsonPath("$.errors[?(@.field == 'itens[0].ncm')]").exists());
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
  @DisplayName("POST cujo servico lanca CfopIncompativelException retorna 400")
  void postComCfopIncompativelRetorna400() throws Exception {
    when(notaFiscalService.create(any(NotaFiscalRequest.class)))
        .thenThrow(new br.com.agrocontabil.exception.CfopIncompativelException(
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
  // POST /importar → 200 relatorio  /  400 sem arquivos
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas/importar com 2 arquivos retorna 200 com relatorio")
  void importarMultiplosArquivosRetorna200() throws Exception {
    UUID notaId = UUID.randomUUID();
    ImportacaoLoteResponse relatorio = ImportacaoLoteResponse.de(java.util.List.of(
        ImportacaoItemResultado.sucesso("nota1.xml", notaId,
            "41260612345678000195550010000000011000000017"),
        ImportacaoItemResultado.erro("nota2.xml", "XML invalido")));
    when(importacaoNotaFiscalService.importar(org.mockito.ArgumentMatchers.anyList()))
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
  // POST /{id}/cancelar → 200 / 422 / 409 / 400
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("POST /api/notas/{id}/cancelar valido retorna 200")
  void cancelarValidoRetorna200() throws Exception {
    UUID notaId = UUID.randomUUID();
    when(notaFiscalService.cancelar(eq(notaId), any(CancelamentoRequest.class)))
        .thenReturn(buildResponse(notaId));

    String payload = "{\"motivo\": \"Erro nos dados do destinatario informado\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(notaId.toString()));
  }

  @Test
  @DisplayName("POST cancelar fora do prazo (servico lanca BusinessException) retorna 422")
  void cancelarForaDoPrazoRetorna422() throws Exception {
    UUID notaId = UUID.randomUUID();
    when(notaFiscalService.cancelar(eq(notaId), any(CancelamentoRequest.class)))
        .thenThrow(new br.com.agrocontabil.exception.BusinessException(
            "Cancelamento nao permitido: 31 dias, acima do limite de 30 dias (RN006)."));

    String payload = "{\"motivo\": \"Erro nos dados do destinatario informado\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
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
    UUID notaId = UUID.randomUUID();
    when(notaFiscalService.cancelar(eq(notaId), any(CancelamentoRequest.class)))
        .thenThrow(br.com.agrocontabil.exception.NotaFiscalNaoEditavelException
            .naoCancelavel(notaId, StatusNotaFiscal.RASCUNHO));

    String payload = "{\"motivo\": \"Erro nos dados do destinatario informado\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  @DisplayName("POST cancelar com motivo curto (<15) retorna 400")
  void cancelarMotivoCurtoRetorna400() throws Exception {
    UUID notaId = UUID.randomUUID();
    String payload = "{\"motivo\": \"curto\"}";

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'motivo')]").exists());
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
        OffsetDateTime.now(),
        br.com.agrocontabil.model.TipoDocumento.NFE,
        null,
        null);
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
        OffsetDateTime.now(),
        br.com.agrocontabil.model.TipoDocumento.NFE,
        null,
        null);
  }
}
