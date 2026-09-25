package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SefazTransport;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import br.com.agrocontabil.support.ContadorDeEventos;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
 * Cancelamento recusado pela SEFAZ, ponta a ponta pelo HTTP (issue #84).
 *
 * <p>O {@code MockSefazTransport} sempre aceita o evento, então o caminho da recusa nunca era
 * exercitado fora do teste unitário com o {@code SefazClient} mockado. Aqui o transporte real do
 * contexto é espiado e devolve a recusa que a SEFAZ manda de verdade (cStat 501 — pedido de
 * cancelamento intempestivo), e o teste observa o que o cliente da API recebe: o envelope
 * {@code errors[]} com 422, o {@code cStat} em {@code rejectedValue} e o {@code xMotivo} em
 * {@code message}, e a nota ainda {@code AUTORIZADA} no banco.
 */
@SpringBootTest
@Import({TestDatabaseCleaner.class, ContadorDeEventos.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class CancelamentoRejeitadoIntegrationTest {

  private static final String CHAVE = "41262612345678000195550010000000011000000084";
  private static final String XMOTIVO =
      "Rejeicao: Pedido de Cancelamento intempestivo (NF-e autorizada a mais de 7 dias)";
  private static final String CORPO =
      "{\"motivo\": \"Cancelamento por erro de digitacao nos dados do destinatario\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ContadorDeEventos contador;

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
        new UsernamePasswordAuthenticationToken(produtor, null, List.of()));
    contador.zerar();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    transactionTemplate.executeWithoutResult(status -> cleaner.limparTudo());
  }

  @Test
  @DisplayName("SEFAZ recusa o cancelamento: 422 CANCELAMENTO_REJEITADO com cStat e xMotivo")
  void recusaDoFiscoRespondeUnprocessableEntityComCstatEXmotivo() throws Exception {
    UUID notaId = persistirAutorizada();
    doReturn(new SefazRetorno(SituacaoSefaz.REJEITADA, "501", XMOTIVO, null, null))
        .when(sefazTransport).cancelar(any(), any(), any(), any());

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CORPO))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("CANCELAMENTO_REJEITADO"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("501"))
        // O motivo do fisco vai ao cliente como veio: e ele que diz ao produtor o que resolver.
        .andExpect(jsonPath("$.errors[0].message").value(XMOTIVO));

    // A recusa nao cancelou nada: a nota segue AUTORIZADA (e cancelavel) e o debito do periodo
    // nao muda — nenhum NotaCanceladaEvent.
    NotaFiscal nota = notaFiscalRepository.findById(notaId).orElseThrow();
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getProtocoloCancelamento()).isNull();
    assertThat(contador.canceladas()).isZero();
  }

  @Test
  @DisplayName("SEFAZ recusa sem xMotivo: a mensagem cai no texto generico com o cStat")
  void recusaSemMotivoUsaMensagemGenerica() throws Exception {
    UUID notaId = persistirAutorizada();
    doReturn(new SefazRetorno(SituacaoSefaz.REJEITADA, "580", null, null, null))
        .when(sefazTransport).cancelar(any(), any(), any(), any());

    mockMvc.perform(post("/api/notas/{id}/cancelar", notaId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CORPO))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("CANCELAMENTO_REJEITADO"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("580"))
        .andExpect(jsonPath("$.errors[0].message")
            .value("A SEFAZ recusou o cancelamento (cStat 580)."));
  }

  private UUID persistirAutorizada() {
    return transactionTemplate.execute(status -> notaFiscalRepository.save(NotaFiscal.builder()
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
        .status(StatusNotaFiscal.AUTORIZADA)
        .chaveAcesso(CHAVE)
        .xmlGerado("<NFe><infNFe Id=\"NFe" + CHAVE + "\"/></NFe>")
        .versaoLayoutNfe("4.00")
        .protocoloAutorizacao("141260000000017")
        .build())).getId();
  }
}
