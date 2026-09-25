package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Prova, ponta a ponta, que a API atende mais de um usuario e que o anti-IDOR responde (issue #91).
 *
 * <p>Ate aqui o backend era single-user: o filtro injetava sempre o mesmo produtor, entao o
 * {@code findByIdAndProdutorId} espalhado pelos services nunca chegava a negar nada. Com dois
 * produtores e o header {@code X-Usuario-Id}, o 404 cross-tenant passa a ser verificavel.
 *
 * <p>O {@link MockProdutorFilter} e {@code @Profile("dev")} e o profile daqui e {@code test}: em vez
 * de ligar o profile dev na suite (o que traria junto o {@code DataLoader} e o docker-compose), o
 * filtro e instanciado e registrado a mao no {@link MockMvc} — fica explicito que o teste exercita
 * exatamente o filtro de producao, nao uma imitacao.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Identidade multi-usuario via X-Usuario-Id (#91)")
class IdentidadeMultiUsuarioIntegrationTest {

  /** CPF do produtor semente — vem do DataLoader, fonte unica do valor que o filtro procura. */
  private static final String CPF_SEMENTE = DataLoader.CPF_PRODUTOR_SEMENTE;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor produtorA;
  private Produtor produtorB;
  private NotaFiscal notaDeA;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();

    produtorA = produtorRepository.save(produtor("Joao da Silva Produtor", CPF_SEMENTE));
    produtorB = produtorRepository.save(produtor("Maria Agricultora", "987.654.321-00"));
    notaDeA = notaFiscalRepository.save(nota(produtorA, 1001));
    notaFiscalRepository.save(nota(produtorB, 2001));

    mockMvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(new MockProdutorFilter(produtorRepository))
            .build();
  }

  @Test
  @DisplayName("cada usuario ve apenas as proprias notas")
  void cadaUsuarioVeApenasAsProprias() throws Exception {
    mockMvc
        .perform(get("/api/notas").header(MockProdutorFilter.HEADER_USUARIO_ID, produtorA.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].numero").value(1001));

    mockMvc
        .perform(get("/api/notas").header(MockProdutorFilter.HEADER_USUARIO_ID, produtorB.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].numero").value(2001));
  }

  @Test
  @DisplayName("nota de outro produtor responde 404 — nao 403, para nao revelar existencia")
  void notaDeOutroProdutorResponde404() throws Exception {
    mockMvc
        .perform(
            get("/api/notas/{id}", notaDeA.getId())
                .header(MockProdutorFilter.HEADER_USUARIO_ID, produtorA.getId()))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/notas/{id}", notaDeA.getId())
                .header(MockProdutorFilter.HEADER_USUARIO_ID, produtorB.getId()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("sem header, responde como o produtor semente (o Angular atual nao quebra)")
  void semHeaderUsaProdutorSemente() throws Exception {
    mockMvc
        .perform(get("/api/notas"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].numero").value(1001));
  }

  @Test
  @DisplayName("header invalido nao autentica: nao cai no fallback nem devolve dados de A")
  void headerInvalidoNaoAutenticaComoSemente() throws Exception {
    // O 401 so nasce no PR da issue #92, quando o AuthenticatedProdutorProvider passa a lancar
    // NaoAutenticadoException (ate la o status e 500). A garantia verificada aqui e a que importa
    // para a seguranca e vale nos dois casos: a requisicao nao tem sucesso e nao vaza os dados do
    // produtor semente para quem declarou uma identidade que nao existe.
    naoAutenticaNemVazaDados("nao-e-uuid");
    naoAutenticaNemVazaDados(UUID.randomUUID().toString());
  }

  private void naoAutenticaNemVazaDados(String headerInvalido) throws Exception {
    mockMvc
        .perform(get("/api/notas").header(MockProdutorFilter.HEADER_USUARIO_ID, headerInvalido))
        .andExpect(resultado -> assertThat(resultado.getResponse().getStatus()).isNotEqualTo(200))
        .andExpect(
            resultado ->
                assertThat(resultado.getResponse().getContentAsString()).doesNotContain("1001"));
  }

  @Test
  @DisplayName("identidade nao vaza entre requisicoes consecutivas na mesma thread")
  void identidadeNaoVazaEntreRequisicoes() throws Exception {
    // O MockMvc executa as duas requisicoes na thread do teste: se o filtro nao limpasse o
    // SecurityContext, a segunda herdaria o produtor B da primeira em vez de cair no semente.
    mockMvc
        .perform(get("/api/notas").header(MockProdutorFilter.HEADER_USUARIO_ID, produtorB.getId()))
        .andExpect(jsonPath("$.content[0].numero").value(2001));

    mockMvc
        .perform(get("/api/notas"))
        .andExpect(jsonPath("$.content[0].numero").value(1001));
  }

  private static Produtor produtor(String nome, String cpf) {
    return Produtor.builder().nomeCompleto(nome).cpfCnpj(cpf).estado("MT").build();
  }

  private static NotaFiscal nota(Produtor produtor, long numero) {
    EnderecoDestinatario endereco =
        EnderecoDestinatario.builder()
            .logradouro("Rua A").numero("1").municipio("Cuiaba")
            .codigoMunicipioIbge("5103403").uf("MT").cep("78000000")
            .build();
    return NotaFiscal.builder()
        .numero(numero).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 6, 10, 9, 0, 0, 0, ZoneOffset.ofHours(-3)))
        .produtor(produtor)
        .destinatarioNome("Jose Comprador").destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(endereco)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
  }
}
