package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.AcaoRevisao;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalRevisao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.nfe.VersaoRegraFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRevisaoRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.sefaz.SefazTransport;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code enviar-sefaz} diante de XML gerado por regra fiscal anterior à atual (issue #389), ponta
 * a ponta pelo HTTP: nada vai à SEFAZ, a nota volta a um estado editável com histórico, e a nota
 * de versão atual ou já autorizada segue como antes.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class RegraFiscalDesatualizadaIntegrationTest {

  private static final String CHAVE_PREFIXO = "41262612345678000195550010000000011000";
  private static final AtomicInteger SEQUENCIA_CHAVE = new AtomicInteger();

  @Autowired private MockMvc mockMvc;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private NotaFiscalValidacaoRepository validacaoRepository;
  @Autowired private NotaFiscalRevisaoRepository revisaoRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private TransactionTemplate transactionTemplate;

  @SpyBean private SefazTransport sefazTransport;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    transactionTemplate.executeWithoutResult(status -> {
      cleaner.limparTudo();
      produtor = produtorRepository.save(Produtor.builder()
          .nomeCompleto("Maria Agricultora")
          .cpfCnpj("98765432100")
          .build());
    });
    SecurityContextHolder.getContext().setAuthentication(
        AutenticacaoTestSupport.tokenDe(produtor));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    transactionTemplate.executeWithoutResult(status -> cleaner.limparTudo());
  }

  @Test
  @DisplayName("XML sem marca de versao: 409, nada transmitido, nota editavel e motivo no historico")
  void xmlSemMarcaNaoEhTransmitido() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.XML_GERADO, null);

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("XML_REGRA_FISCAL_DESATUALIZADA"))
        .andExpect(jsonPath("$.errors[0].expectedValue")
            .value(String.valueOf(VersaoRegraFiscal.ATUAL)));

    verify(sefazTransport, never()).enviar(any(), any(), any());
    assertThat(statusDe(notaId)).isEqualTo(StatusNotaFiscal.COM_INCONSISTENCIAS);
    assertThat(validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId))
        .extracting("code").containsExactly("XML_REGRA_FISCAL_DESATUALIZADA");
    // Sem aprovacao no historico, nada de caducidade.
    assertThat(revisaoRepository.findByNotaFiscalIdOrderByOcorridoEmDesc(notaId)).isEmpty();
  }

  @Test
  @DisplayName("XML de versao menor que a atual tambem e recusado")
  void xmlDeVersaoMenorNaoEhTransmitido() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.XML_GERADO, VersaoRegraFiscal.ATUAL - 1);

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("XML_REGRA_FISCAL_DESATUALIZADA"))
        .andExpect(jsonPath("$.errors[0].rejectedValue")
            .value(String.valueOf(VersaoRegraFiscal.ATUAL - 1)));

    verify(sefazTransport, never()).enviar(any(), any(), any());
    assertThat(statusDe(notaId)).isEqualTo(StatusNotaFiscal.COM_INCONSISTENCIAS);
  }

  @Test
  @DisplayName("nota cuja ultima acao da revisao foi a aprovacao registra APROVACAO_CADUCADA")
  void aprovacaoCaiJuntoComOXml() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.XML_GERADO, null);
    transactionTemplate.executeWithoutResult(tx -> revisaoRepository.save(
        NotaFiscalRevisao.builder().notaFiscalId(notaId).acao(AcaoRevisao.APROVADA)
            .ocorridoEm(OffsetDateTime.now().minusHours(1)).build()));

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
        .andExpect(status().isConflict());

    List<NotaFiscalRevisao> historico =
        revisaoRepository.findByNotaFiscalIdOrderByOcorridoEmDesc(notaId);
    assertThat(historico).extracting(NotaFiscalRevisao::getAcao)
        .containsExactly(AcaoRevisao.APROVACAO_CADUCADA, AcaoRevisao.APROVADA);
  }

  @Test
  @DisplayName("XML na versao atual segue o fluxo de sempre")
  void xmlNaVersaoAtualEhTransmitido() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.XML_GERADO, VersaoRegraFiscal.ATUAL);

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AUTORIZADA"));

    assertThat(statusDe(notaId)).isEqualTo(StatusNotaFiscal.AUTORIZADA);
  }

  @Test
  @DisplayName("nota AUTORIZADA sem marca nao e tocada")
  void notaAutorizadaNaoEhTocada() throws Exception {
    UUID notaId = persistir(StatusNotaFiscal.AUTORIZADA, null);

    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", notaId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("NOTA_NAO_ENVIAVEL"));

    assertThat(statusDe(notaId)).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId)).isEmpty();
  }

  private StatusNotaFiscal statusDe(UUID notaId) {
    return notaFiscalRepository.findById(notaId).orElseThrow().getStatus();
  }

  private UUID persistir(StatusNotaFiscal status, Integer versaoRegra) {
    String chave = CHAVE_PREFIXO + String.format("%06d", SEQUENCIA_CHAVE.incrementAndGet());
    return transactionTemplate.execute(tx -> notaFiscalRepository.save(NotaFiscal.builder()
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now().minusDays(2))
        .produtor(produtor)
        .destinatarioNome("Jose Comprador")
        .destinatarioCpfCnpj("12345678901")
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(status)
        .chaveAcesso(chave)
        .xmlGerado("<NFe><infNFe Id=\"NFe" + chave + "\"/></NFe>")
        .versaoLayoutNfe("4.00")
        .versaoRegraFiscal(versaoRegra)
        .protocoloAutorizacao(status == StatusNotaFiscal.AUTORIZADA ? "141260000000017" : null)
        .build())).getId();
  }
}
