package br.com.agrocontabil.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Erros dos endpoints de arquivo pela pilha real (issue #250): {@code Accept} sem representação
 * vira 406, e erro de domínio pedido com {@code Accept: application/pdf} sai com o envelope em
 * JSON. Antes, os dois casos viravam 500 (o segundo, com corpo vazio).
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Accept dos endpoints de arquivo: 406 e envelope em JSON (#250)")
class NegociacaoErroArquivoIntegrationTest {

  private static final MediaType PDF = MediaType.APPLICATION_PDF;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor produtorA;
  private Produtor produtorB;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtorA = produtorRepository.save(
        Produtor.builder().nomeCompleto("Maria Agricultora").cpfCnpj("98765432100").build());
    produtorB = produtorRepository.save(
        Produtor.builder().nomeCompleto("Joao Pecuarista").cpfCnpj("12345678909").build());
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  @AfterEach
  void tearDown() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("/pdf com Accept: application/json: 406 MEDIA_TYPE_NOT_ACCEPTABLE em JSON")
  void pdfComAcceptJson406() throws Exception {
    NotaFiscal nota = nota(produtorA, StatusNotaFiscal.AUTORIZADA);
    naoAceitavel(comoA(get("/api/notas/{id}/pdf", nota.getId()).accept(MediaType.APPLICATION_JSON)))
        .andExpect(jsonPath("$.errors[0].expectedValue").value(PDF.toString()));
  }

  @Test
  @DisplayName("/xml, preview e relatorio PDF com Accept: application/json: 406")
  void demaisArquivosComAcceptJson406() throws Exception {
    NotaFiscal nota = nota(produtorA, StatusNotaFiscal.RASCUNHO);
    naoAceitavel(comoA(get("/api/notas/{id}/xml", nota.getId()).accept(MediaType.APPLICATION_JSON)));
    naoAceitavel(comoA(get("/api/notas/{id}/danfe-preview", nota.getId())
        .accept(MediaType.APPLICATION_JSON)));
    naoAceitavel(comoA(get("/api/relatorios/conformidade/pdf").param("periodo", "2026-07")
        .accept(MediaType.APPLICATION_JSON)));
  }

  @Test
  @DisplayName("endpoint JSON pedido com Accept: application/pdf: 406, nao 500")
  void jsonComAcceptPdf406() throws Exception {
    NotaFiscal nota = nota(produtorA, StatusNotaFiscal.RASCUNHO);
    naoAceitavel(comoA(get("/api/notas/{id}", nota.getId()).accept(PDF)));
  }

  @Test
  @DisplayName("preview de nota autorizada com Accept: application/pdf: 409 com envelope JSON")
  void previewAutorizadaComAcceptPdf409() throws Exception {
    NotaFiscal nota = nota(produtorA, StatusNotaFiscal.AUTORIZADA);
    comoA(get("/api/notas/{id}/danfe-preview", nota.getId()).accept(PDF))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errors[0].code").value("DANFE_PREVIEW_INDISPONIVEL"));
  }

  @Test
  @DisplayName("DANFE de outro produtor (404) e de rascunho (409) com Accept: application/pdf")
  void pdfComAcceptPdfErrosDeDominio() throws Exception {
    NotaFiscal deB = nota(produtorB, StatusNotaFiscal.AUTORIZADA);
    comoA(get("/api/notas/{id}/pdf", deB.getId()).accept(PDF))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));

    NotaFiscal rascunho = nota(produtorA, StatusNotaFiscal.RASCUNHO);
    comoA(get("/api/notas/{id}/pdf", rascunho.getId()).accept(PDF))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errors[0].code").exists());
  }

  @Test
  @DisplayName("relatorio PDF com mes 13 e Accept: application/pdf: 400 com envelope JSON")
  void relatorioPdfMesInvalido400() throws Exception {
    comoA(get("/api/relatorios/conformidade/pdf").param("periodo", "2026-13").accept(PDF))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("/xml de outro produtor com Accept: application/xml: o envelope segue em XML")
  void xmlComAcceptXmlSegueEmXml() throws Exception {
    NotaFiscal deB = nota(produtorB, StatusNotaFiscal.AUTORIZADA);
    comoA(get("/api/notas/{id}/xml", deB.getId()).accept(MediaType.APPLICATION_XML))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
        .andExpect(xpath("/ApiError/errors/errors/code").string("RESOURCE_NOT_FOUND"));
  }

  // -------------------------------------------------------------------------

  private ResultActions comoA(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requisicao)
      throws Exception {
    return mockMvc.perform(
        requisicao.header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId()));
  }

  private static ResultActions naoAceitavel(ResultActions resultado) throws Exception {
    return resultado
        .andExpect(status().isNotAcceptable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(406))
        .andExpect(jsonPath("$.errors[0].code").value("MEDIA_TYPE_NOT_ACCEPTABLE"));
  }

  private NotaFiscal nota(Produtor produtor, StatusNotaFiscal status) {
    NotaFiscal nota = NotaFiscal.builder()
        .numero(1L).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Jose Comprador").destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rua A").numero("1").municipio("Cuiaba")
            .codigoMunicipioIbge("5103403").uf("MT").cep("78000000")
            .build())
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(status)
        .build();
    nota.addItem(NotaFiscalItem.builder()
        .numeroItem(1).descricao("Soja em graos").ncm("12010090").cfop("5101")
        .unidade("KG").quantidade(new BigDecimal("500")).valorUnitario(new BigDecimal("2"))
        .valorTotal(new BigDecimal("1000.00"))
        .build());
    return notaFiscalRepository.save(nota);
  }
}
