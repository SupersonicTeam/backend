package br.com.agrocontabil.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET /api/notas/proximo-numero} pela pilha real (issue #539). */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class ProximoNumeroIntegrationTest {

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

  private ResultActions pedir(UUID usuario, String query) throws Exception {
    return mockMvc.perform(get("/api/notas/proximo-numero" + query)
        .header(MockIdentidadeFilter.HEADER_USUARIO_ID, usuario));
  }

  @Test
  @DisplayName("Sem nota nenhuma: 1, e a série padrão é 1")
  void semNota() throws Exception {
    pedir(produtorA.getId(), "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serie").value(1))
        .andExpect(jsonPath("$.numero").value(1));
  }

  @Test
  @DisplayName("Compra importada com número maior não conta")
  void importadaNaoConta() throws Exception {
    nota(produtorA, 3184, 1, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.AUTORIZADA,
        TipoDocumentoFiscal.NFE);
    nota(produtorA, 40_000, 1, OrigemNotaFiscal.IMPORTADA, StatusNotaFiscal.AUTORIZADA,
        TipoDocumentoFiscal.NFE);

    pedir(produtorA.getId(), "?serie=1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.numero").value(3185));
  }

  @Test
  @DisplayName("Outra série não conta")
  void outraSerieNaoConta() throws Exception {
    nota(produtorA, 10, 1, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.AUTORIZADA,
        TipoDocumentoFiscal.NFE);
    nota(produtorA, 500, 2, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.AUTORIZADA,
        TipoDocumentoFiscal.NFE);

    pedir(produtorA.getId(), "?serie=1").andExpect(jsonPath("$.numero").value(11));
    pedir(produtorA.getId(), "?serie=2").andExpect(jsonPath("$.numero").value(501))
        .andExpect(jsonPath("$.serie").value(2));
    pedir(produtorA.getId(), "?serie=0").andExpect(jsonPath("$.numero").value(1));
  }

  @Test
  @DisplayName("Rascunho, cancelada e rejeitada contam; NFA conta na mesma sequência")
  void statusETipoContam() throws Exception {
    nota(produtorA, 5, 1, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.RASCUNHO,
        TipoDocumentoFiscal.NFE);
    nota(produtorA, 6, 1, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.CANCELADA,
        TipoDocumentoFiscal.NFE);
    nota(produtorA, 7, 1, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.REJEITADA,
        TipoDocumentoFiscal.NFE);
    pedir(produtorA.getId(), "?serie=1").andExpect(jsonPath("$.numero").value(8));

    nota(produtorA, 20, 1, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.RASCUNHO,
        TipoDocumentoFiscal.NFA);
    pedir(produtorA.getId(), "?serie=1").andExpect(jsonPath("$.numero").value(21));
  }

  @Test
  @DisplayName("Outro produtor não conta")
  void outroProdutorNaoConta() throws Exception {
    nota(produtorB, 900, 1, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.AUTORIZADA,
        TipoDocumentoFiscal.NFE);

    pedir(produtorA.getId(), "?serie=1").andExpect(jsonPath("$.numero").value(1));
    pedir(produtorB.getId(), "?serie=1").andExpect(jsonPath("$.numero").value(901));
  }

  @Test
  @DisplayName("Série fora de 0 a 999: 400 VALIDATION_ERROR")
  void serieForaDaFaixa() throws Exception {
    pedir(produtorA.getId(), "?serie=1000")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("serie"));
    pedir(produtorA.getId(), "?serie=-1").andExpect(status().isBadRequest());
    pedir(produtorA.getId(), "?serie=abc").andExpect(status().isBadRequest());
    pedir(produtorA.getId(), "?serie=999").andExpect(status().isOk());
  }

  @Test
  @DisplayName("Contador: 403 PAPEL_NAO_AUTORIZADO")
  void contadorRecebe403() throws Exception {
    Contador contador = contadorRepository.save(Contador.builder()
        .nomeCompleto("Escritorio").cpfCnpj("12.345.678/0001-95").build());

    pedir(contador.getId(), "")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
  }

  @Test
  @DisplayName("Série esgotada: 409 SERIE_ESGOTADA; um número antes do limite ainda sugere o último")
  void serieEsgotada() throws Exception {
    nota(produtorA, 999_999_998L, 1, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.AUTORIZADA,
        TipoDocumentoFiscal.NFE);
    pedir(produtorA.getId(), "?serie=1").andExpect(jsonPath("$.numero").value(999_999_999L));

    nota(produtorA, 999_999_999L, 1, OrigemNotaFiscal.PROPRIA, StatusNotaFiscal.AUTORIZADA,
        TipoDocumentoFiscal.NFE);
    pedir(produtorA.getId(), "?serie=1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("SERIE_ESGOTADA"));
  }

  @Test
  @DisplayName("A rota literal não é lida como {id}")
  void rotaLiteralNaoViraId() throws Exception {
    pedir(produtorA.getId(), "").andExpect(status().isOk())
        .andExpect(jsonPath("$.numero").exists());
  }

  private NotaFiscal nota(Produtor produtor, long numero, int serie, OrigemNotaFiscal origem,
      StatusNotaFiscal status, TipoDocumentoFiscal tipo) {
    NotaFiscal nota = NotaFiscal.builder()
        .numero(numero).serie(serie).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Jose Comprador").destinatarioCpfCnpj("12345678901")
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .origem(origem)
        .tipoDocumento(tipo)
        .status(status)
        .chaveAcesso(origem == OrigemNotaFiscal.IMPORTADA
            ? String.format("%044d", numero * 10 + serie) : null)
        .build();
    return notaFiscalRepository.save(nota);
  }
}
