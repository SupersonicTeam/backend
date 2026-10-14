package br.com.agrocontabil.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.config.MockIdentidadeFilter;
import br.com.agrocontabil.model.AlertaTributario;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusAlertaTributario;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoAlertaTributario;
import br.com.agrocontabil.repository.AlertaTributarioRepository;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.DadosCeresFixture;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/alertas} de ponta a ponta (issue #140): só os alertas do produtor do header,
 * contador barrado com 403, contrato sem dado pessoal.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class AlertaTributarioControllerIntegrationTest {

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private GuiaFiscalRepository guiaRepository;
  @Autowired private AlertaTributarioRepository alertaRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private MockMvc mockMvc;
  private Produtor produtorA;
  private Produtor produtorB;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtorA = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda A").cpfCnpj("98765432100").estado("MT").build());
    produtorB = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda B").cpfCnpj("11122233344").estado("PR").build());
    LocalDate hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    GuiaFiscal guiaA = guiaRepository.save(
        DadosCeresFixture.guia(produtorA, hoje.plusDays(2), "1840.55", StatusGuiaFiscal.PENDENTE));
    alertaRepository.save(alerta(produtorA, guiaA.getId(), hoje.plusDays(2)));

    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(new MockIdentidadeFilter(produtorRepository, contadorRepository))
        .build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Produtor ve o proprio alerta, com dias restantes e sem dado pessoal")
  void produtorVeOsProprios() throws Exception {
    mockMvc.perform(get("/api/alertas")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorA.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].tipo").value("VENCIMENTO_GUIA"))
        .andExpect(jsonPath("$[0].descricao").value("Guia de FUNRURAL de R$ 1.840,55"))
        .andExpect(jsonPath("$[0].diasRestantes").value(2))
        .andExpect(jsonPath("$[0].cpfCnpj").doesNotExist())
        .andExpect(jsonPath("$[0].produtor").doesNotExist());
  }

  @Test
  @DisplayName("Anti-IDOR: o produtor B nao ve o alerta do A")
  void outroProdutorNaoVe() throws Exception {
    mockMvc.perform(get("/api/alertas")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, produtorB.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("Contador: 403 PAPEL_NAO_AUTORIZADO (endpoint ainda sem produtor-alvo)")
  void contadorRecebe403() throws Exception {
    Contador contador = contadorRepository.save(Contador.builder()
        .nomeCompleto("Escritorio").cpfCnpj("12.345.678/0001-95").build());

    mockMvc.perform(get("/api/alertas")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, contador.getId()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
  }

  @Test
  @DisplayName("Header de identidade inexistente: 401 NAO_AUTENTICADO")
  void identidadeInvalida() throws Exception {
    mockMvc.perform(get("/api/alertas")
            .header(MockIdentidadeFilter.HEADER_USUARIO_ID, UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  private static AlertaTributario alerta(Produtor produtor, UUID referencia, LocalDate limite) {
    return AlertaTributario.builder()
        .produtor(produtor)
        .tipo(TipoAlertaTributario.VENCIMENTO_GUIA)
        .referenciaId(referencia)
        .dataLimite(limite)
        .descricao("Guia de FUNRURAL de R$ 1.840,55")
        .status(StatusAlertaTributario.ATIVO)
        .criadoEm(OffsetDateTime.now())
        .build();
  }
}
