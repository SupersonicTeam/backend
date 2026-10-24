package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/notas/{id}/danfe-preview} pela pilha real (issue #89), com dois produtores.
 *
 * <p>O preview é de uma nota <strong>incompleta</strong> de propósito (sem IE e sem endereço do
 * emitente, que a emissão exigiria desde a #88): ele não valida. E não pode gravar nada — a nota
 * sai do preview exatamente como entrou.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class DanfePreviewIntegrationTest {

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
  @DisplayName("Rascunho incompleto do próprio produtor: 200 PDF inline, no-store, nada gravado")
  void previewDoRascunhoNaoGravaNada() throws Exception {
    NotaFiscal nota = nota(produtorA, StatusNotaFiscal.RASCUNHO);
    Long versaoAntes = nota.getVersion();

    byte[] pdf = mockMvc.perform(get("/api/notas/{id}/danfe-preview", nota.getId())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId()))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Content-Disposition",
            "inline; filename=\"danfe-preview-" + nota.getId() + ".pdf\""))
        .andReturn().getResponse().getContentAsByteArray();

    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    NotaFiscal depois = notaFiscalRepository.findById(nota.getId()).orElseThrow();
    assertThat(depois.getStatus()).isEqualTo(StatusNotaFiscal.RASCUNHO);
    assertThat(depois.getVersion()).isEqualTo(versaoAntes);
    assertThat(depois.getChaveAcesso()).isNull();
    assertThat(depois.getXmlGerado()).isNull();
  }

  @Test
  @DisplayName("COM_INCONSISTENCIAS também tem preview")
  void previewComInconsistencias() throws Exception {
    NotaFiscal nota = nota(produtorA, StatusNotaFiscal.COM_INCONSISTENCIAS);

    mockMvc.perform(get("/api/notas/{id}/danfe-preview", nota.getId())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId()))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Rascunho de B pedido por A: 404, e B continua vendo o próprio")
  void previewDeOutroProdutorResponde404() throws Exception {
    NotaFiscal notaDeB = nota(produtorB, StatusNotaFiscal.RASCUNHO);

    mockMvc.perform(get("/api/notas/{id}/danfe-preview", notaDeB.getId())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));

    mockMvc.perform(get("/api/notas/{id}/danfe-preview", notaDeB.getId())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorB.getId()))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Nota já emitida: 409 DANFE_PREVIEW_INDISPONIVEL")
  void previewDeNotaEmitidaResponde409() throws Exception {
    NotaFiscal nota = nota(produtorA, StatusNotaFiscal.AUTORIZADA);

    mockMvc.perform(get("/api/notas/{id}/danfe-preview", nota.getId())
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("DANFE_PREVIEW_INDISPONIVEL"));
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
