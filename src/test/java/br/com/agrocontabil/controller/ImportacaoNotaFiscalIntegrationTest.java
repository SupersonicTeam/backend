package br.com.agrocontabil.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.nfe.NfeImportTestFixtures;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Teste de integracao da importacao de XML (issue #19 — UC002).
 *
 * <p>Sobe o contexto Spring completo com o schema de teste carregado via {@code @Sql}
 * (o perfil de teste desabilita o Liquibase; o script replica as colunas da migration 0009).
 * Exercita a "Definition of Done":
 * <ul>
 *   <li>importar um {@code nfeProc} real → nota {@code origem=IMPORTADA} aparece em
 *       {@code GET /api/notas};</li>
 *   <li>lote com 1 valido + 1 invalido → sucesso parcial (o lote nao cai);</li>
 *   <li>reimportar a mesma chave → reportada como DUPLICADA.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class ImportacaoNotaFiscalIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProdutorRepository produtorRepository;

  @Autowired
  private NotaFiscalRepository notaFiscalRepository;

  private String nfeProcXml;

  @BeforeEach
  void setUp() {
    Produtor produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Joao da Silva Produtor")
        .cpfCnpj("52998224725")
        .estado("SC")
        .build());

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(produtor, null, List.of()));

    // XML de NF-e real, assinado e autorizado (nfeProc cStat=100), gerado pelo pipeline.
    nfeProcXml = NfeImportTestFixtures.comoNfeProcAutorizada(
        NfeImportTestFixtures.gerarNfeAssinada());
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    notaFiscalRepository.deleteAll();
    produtorRepository.deleteAll();
  }

  @Test
  @DisplayName("Importa nfeProc real: nota origem=IMPORTADA aparece em GET /api/notas")
  void importaNfeProcRealAparecemEmListagem() throws Exception {
    MockMultipartFile arquivo = arquivoXml("nota-entrada.xml", nfeProcXml);

    mockMvc.perform(multipart("/api/notas/importar").file(arquivo))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sucesso").value(true))
        .andExpect(jsonPath("$[0].status").value("IMPORTADA"));

    mockMvc.perform(get("/api/notas"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").exists());

    // Confirma a persistencia com origem IMPORTADA e emitente terceiro.
    var notas = notaFiscalRepository.findAll();
    org.assertj.core.api.Assertions.assertThat(notas).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(notas.get(0).getOrigem())
        .isEqualTo(OrigemNotaFiscal.IMPORTADA);
    org.assertj.core.api.Assertions.assertThat(notas.get(0).getEmitenteCpfCnpj())
        .isEqualTo(NfeImportTestFixtures.EMITENTE_CNPJ);
  }

  @Test
  @DisplayName("Lote com 1 valido + 1 invalido retorna resultado parcial sem derrubar o lote")
  void loteParcialNaoDerruba() throws Exception {
    MockMultipartFile valido = arquivoXml("valida.xml", nfeProcXml);
    MockMultipartFile invalido = arquivoXml("invalida.xml", "<isto nao e nfe/>");

    mockMvc.perform(multipart("/api/notas/importar").file(valido).file(invalido))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
        .andExpect(jsonPath("$[?(@.arquivo == 'valida.xml')].status").value("IMPORTADA"))
        .andExpect(jsonPath("$[?(@.arquivo == 'invalida.xml')].status").value("INVALIDA"));
  }

  @Test
  @DisplayName("Reimportar a mesma chave e reportado como DUPLICADA, sem 500")
  void chaveDuplicadaReportada() throws Exception {
    // 1a importacao: sucesso.
    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("n.xml", nfeProcXml)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("IMPORTADA"));

    // 2a importacao da mesma chave: DUPLICADA.
    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("n.xml", nfeProcXml)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("DUPLICADA"))
        .andExpect(jsonPath("$[0].sucesso").value(false));
  }

  private MockMultipartFile arquivoXml(String nome, String conteudo) {
    return new MockMultipartFile(
        "arquivos", nome, "application/xml", conteudo.getBytes(StandardCharsets.UTF_8));
  }
}
