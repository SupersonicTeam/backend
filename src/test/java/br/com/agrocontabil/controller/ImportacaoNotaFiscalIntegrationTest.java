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
import br.com.agrocontabil.sefaz.MockSefazTransport;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
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
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
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
@Import(TestDatabaseCleaner.class)
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
  private TestDatabaseCleaner cleaner;

  @Autowired
  private NotaFiscalRepository notaFiscalRepository;

  @Autowired
  private MockSefazTransport mockSefaz;

  private String nfeXml;
  private String nfeProcXml;
  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Joao da Silva Produtor")
        .cpfCnpj("52998224725")
        .estado("SC")
        .build());

    SecurityContextHolder.getContext().setAuthentication(
        AutenticacaoTestSupport.tokenDe(produtor));

    // XML de NF-e real, assinado pelo pipeline; o emitente o transmitiu a SEFAZ (mock), que o
    // autorizou e guarda o DigestValue (#223). O protNFe do nfeProc e decorativo.
    nfeXml = NfeImportTestFixtures.gerarNfeAssinada();
    autorizarNaSefaz(nfeXml);
    nfeProcXml = NfeImportTestFixtures.comoNfeProcAutorizada(nfeXml);
  }

  private void autorizarNaSefaz(String xml) {
    mockSefaz.enviar(xml, NfeImportTestFixtures.chaveDe(xml), SefazAmbiente.HOMOLOGACAO);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    cleaner.limparTudo();
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
        .andExpect(jsonPath("$.content[0].id").exists())
        // #409: a lista diz que e compra e quem emitiu (o destinatario e o proprio produtor).
        .andExpect(jsonPath("$.content[0].origem").value("IMPORTADA"))
        .andExpect(jsonPath("$.content[0].emitenteCpfCnpj")
            .value(NfeImportTestFixtures.EMITENTE_CNPJ))
        .andExpect(jsonPath("$.content[0].emitenteNome").isNotEmpty());

    // Confirma a persistencia com origem IMPORTADA e emitente terceiro, AUTORIZADA pela consulta.
    var notas = notaFiscalRepository.findAll();
    org.assertj.core.api.Assertions.assertThat(notas).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(notas.get(0).getOrigem())
        .isEqualTo(OrigemNotaFiscal.IMPORTADA);
    org.assertj.core.api.Assertions.assertThat(notas.get(0).getEmitenteCpfCnpj())
        .isEqualTo(NfeImportTestFixtures.EMITENTE_CNPJ);

    // RN003 (issue Agrocontabil#30): credito de IBS/CBS calculado e persistido na importacao.
    // Item do fixture: CFOP 6101 (elegivel), fertilizante 31052000, valorTotal 1750.00. Insumo
    // do Anexo IX tem 60% de reducao na tabela oficial (art. 138 — issue #272): base efetiva
    // 700.00 → IBS 0,10 % = 0.70 e CBS 0,90 % = 6.30, aproveitamento 100 %.
    org.assertj.core.api.Assertions.assertThat(notas.get(0).getValorTotalCreditoIbs())
        .isEqualByComparingTo("0.70");
    org.assertj.core.api.Assertions.assertThat(notas.get(0).getValorTotalCreditoCbs())
        .isEqualByComparingTo("6.30");
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

  @Test
  @DisplayName("#223: XML com destinatario de outro CPF/CNPJ e recusado e nada e gravado")
  void destinatarioDeOutroProdutor() throws Exception {
    Produtor outro = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Outro Produtor").cpfCnpj("39053344705").estado("SC").build());
    SecurityContextHolder.getContext().setAuthentication(AutenticacaoTestSupport.tokenDe(outro));

    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("alheia.xml", nfeProcXml)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("INVALIDA"))
        .andExpect(jsonPath("$[0].sucesso").value(false))
        .andExpect(jsonPath("$[0].motivo").value(
            org.hamcrest.Matchers.containsString("destinatário")));

    org.assertj.core.api.Assertions.assertThat(notaFiscalRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("#223: XML alterado depois de assinado (assinatura nao confere) e recusado")
  void assinaturaAdulterada() throws Exception {
    String adulterado = nfeProcXml.replace("<vProd>1750.00</vProd>", "<vProd>9750.00</vProd>");
    org.assertj.core.api.Assertions.assertThat(adulterado).isNotEqualTo(nfeProcXml);

    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("adulterada.xml",
            adulterado)))
        .andExpect(jsonPath("$[0].status").value("INVALIDA"))
        .andExpect(jsonPath("$[0].motivo").value(
            org.hamcrest.Matchers.containsString("Assinatura")));

    org.assertj.core.api.Assertions.assertThat(notaFiscalRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("#223: protNFe forjado (cStat=100 no upload) de NF-e que a SEFAZ nao conhece: nada")
  void protNfeForjadoNaoAutoriza() throws Exception {
    // Assinada e com destinatario certo, mas nunca transmitida: a SEFAZ responde 217.
    String forjada = NfeImportTestFixtures.comoNfeProcAutorizada(
        NfeImportTestFixtures.gerarNfeAssinada());

    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("forjada.xml", forjada)))
        .andExpect(jsonPath("$[0].status").value("INVALIDA"))
        .andExpect(jsonPath("$[0].motivo").value(
            org.hamcrest.Matchers.containsString("SEFAZ")));

    // Nem nota AUTORIZADA nem credito: nada gravado.
    org.assertj.core.api.Assertions.assertThat(notaFiscalRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("#223: mesma chave com outro conteudo, reassinado: digVal da SEFAZ nao confere")
  void conteudoDiferenteDoAutorizado() throws Exception {
    String trocado = NfeImportTestFixtures.reassinar(nfeXml,
        xml -> xml.replace("<vProd>1750.00</vProd>", "<vProd>9750.00</vProd>"));
    org.assertj.core.api.Assertions.assertThat(NfeImportTestFixtures.chaveDe(trocado))
        .isEqualTo(NfeImportTestFixtures.chaveDe(nfeXml));

    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("trocada.xml",
            NfeImportTestFixtures.comoNfeProcAutorizada(trocado))))
        .andExpect(jsonPath("$[0].status").value("INVALIDA"))
        .andExpect(jsonPath("$[0].motivo").value(
            org.hamcrest.Matchers.containsString("difere")));

    org.assertj.core.api.Assertions.assertThat(notaFiscalRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("#223: forjado com o digest verdadeiro num CDATA do infCpl tambem e recusado")
  void digestVerdadeiroEmCdataNaoEngana() throws Exception {
    String digestVerdadeiro = br.com.agrocontabil.nfe.DigestAssinatura.extrair(nfeXml)
        .orElseThrow();
    String forjado = NfeImportTestFixtures.reassinar(nfeXml,
        xml -> xml.replace("<vProd>1750.00</vProd>", "<vProd>9750.00</vProd>")
            .replace("<infCpl>", "<infCpl><![CDATA[<DigestValue>" + digestVerdadeiro
                + "</DigestValue>]]>"));
    org.assertj.core.api.Assertions.assertThat(forjado).contains("CDATA");

    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("cdata.xml",
            NfeImportTestFixtures.comoNfeProcAutorizada(forjado))))
        .andExpect(jsonPath("$[0].status").value("INVALIDA"));

    org.assertj.core.api.Assertions.assertThat(notaFiscalRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("#223: dois produtores — o destinatario importa a NF-e que o emitente (usuario) "
      + "tem como PROPRIA; a chave do outro nao responde DUPLICADA")
  void destinatarioImportaNotaDoEmitenteUsuario() throws Exception {
    Produtor emitente = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fornecedora").cpfCnpj(NfeImportTestFixtures.EMITENTE_CNPJ).estado("PR")
        .build());
    String chave = NfeImportTestFixtures.chaveDe(nfeXml);
    notaFiscalRepository.save(br.com.agrocontabil.model.NotaFiscal.builder()
        .produtor(emitente)
        .origem(OrigemNotaFiscal.PROPRIA)
        .status(br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA)
        .tipoDocumento(br.com.agrocontabil.model.TipoDocumentoFiscal.NFE)
        .chaveAcesso(chave)
        .numero(NfeImportTestFixtures.NUMERO)
        .serie(NfeImportTestFixtures.SERIE)
        .modelo("55")
        .naturezaOperacao("Venda")
        .dataEmissao(java.time.OffsetDateTime.now())
        .destinatarioNome("JOAO DA SILVA PRODUTOR")
        .destinatarioCpfCnpj("52998224725")
        .valorTotalProdutos(new java.math.BigDecimal("1750.00"))
        .valorTotalNota(new java.math.BigDecimal("1750.00"))
        .build());

    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("entrada.xml", nfeProcXml)))
        .andExpect(jsonPath("$[0].status").value("IMPORTADA"))
        .andExpect(jsonPath("$[0].sucesso").value(true));

    var doDestinatario = notaFiscalRepository.findAll().stream()
        .filter(n -> n.getProdutor().getId().equals(produtor.getId())).toList();
    org.assertj.core.api.Assertions.assertThat(doDestinatario).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(doDestinatario.get(0).getStatus())
        .isEqualTo(br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA);
  }

  @Test
  @DisplayName("#409: GET /api/notas/{id} da compra importada traz origem e emitente")
  void detalheDaCompraImportadaTrazOrigemEEmitente() throws Exception {
    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("n.xml", nfeProcXml)))
        .andExpect(jsonPath("$[0].status").value("IMPORTADA"));
    var nota = notaFiscalRepository.findAll().get(0);

    mockMvc.perform(get("/api/notas/{id}", nota.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.origem").value("IMPORTADA"))
        .andExpect(jsonPath("$.emitenteNome").value(nota.getEmitenteNome()))
        .andExpect(jsonPath("$.emitenteCpfCnpj").value(NfeImportTestFixtures.EMITENTE_CNPJ));
  }

  @Test
  @DisplayName("#223: GET /xml de nota importada sai como anexo, nunca inline")
  void xmlImportadoSaiComoAnexo() throws Exception {
    mockMvc.perform(multipart("/api/notas/importar").file(arquivoXml("n.xml", nfeProcXml)))
        .andExpect(jsonPath("$[0].status").value("IMPORTADA"));
    var nota = notaFiscalRepository.findAll().get(0);

    mockMvc.perform(get("/api/notas/{id}/xml", nota.getId()))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
            .string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
  }

  private MockMultipartFile arquivoXml(String nome, String conteudo) {
    return new MockMultipartFile(
        "arquivos", nome, "application/xml", conteudo.getBytes(StandardCharsets.UTF_8));
  }
}
