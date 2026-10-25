package br.com.agrocontabil.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rotas da revisão (issue #178) pela cadeia real com JWT: a ordem dos ids no path do contador,
 * o 400 do motivo e o 404 entre produtores. O fluxo fiscal completo está no
 * {@code RevisaoNotaIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Rotas da revisao da nota (#178)")
class RevisaoNotaControllerIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private CadastroResponse produtorA;
  private CadastroResponse produtorB;
  private String tokenA;
  private String tokenB;
  private String tokenContador;
  private UUID notaA;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    produtorA = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor A", "52998224725", "a@fazenda.com.br", SENHA));
    produtorB = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor B", "39053344705", "b@fazenda.com.br", SENHA));
    CadastroResponse contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio", "11222333000181", "c@escritorio.com.br", SENHA));
    jdbcTemplate.update("INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao,"
        + " inicio) SELECT RANDOM_UUID(), escritorio_id, ?, 'ATIVO', CURRENT_TIMESTAMP"
        + " FROM contadores WHERE id = ?", produtorA.usuarioId(), contador.usuarioId());
    notaA = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO nota_fiscal (id, produtor_id, numero, serie, modelo,"
        + " natureza_operacao, data_emissao, destinatario_nome, destinatario_cpf_cnpj,"
        + " valor_total_produtos, valor_total_nota, status, origem, tipo_documento, version)"
        + " VALUES (?, ?, 1, 1, '55', 'Venda', CURRENT_TIMESTAMP, 'Comprador', '12345678901',"
        + " 100, 100, 'AGUARDANDO_APROVACAO', 'PROPRIA', 'NFE', 0)", notaA, produtorA.usuarioId());
    tokenA = token("a@fazenda.com.br");
    tokenB = token("b@fazenda.com.br");
    tokenContador = token("c@escritorio.com.br");
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  @Test
  @DisplayName("contador devolve pelo path {produtorId}/notas/{id}; motivo curto e 400")
  void devolverPeloPath() throws Exception {
    mockMvc.perform(post("/api/contador/produtores/{p}/notas/{n}/devolver", produtorA.usuarioId(),
            notaA).header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"ok\"}"))
        .andExpect(status().isBadRequest());
    // Espacos nao contam para os 5 caracteres minimos.
    mockMvc.perform(post("/api/contador/produtores/{p}/notas/{n}/devolver", produtorA.usuarioId(),
            notaA).header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"  a    \"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/contador/produtores/{p}/notas/{n}/devolver", produtorA.usuarioId(),
            notaA).header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Falta o frete\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEVOLVIDA"));
    mockMvc.perform(get("/api/notas/{n}/revisoes", notaA).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].acao").value("DEVOLVIDA"))
        .andExpect(jsonPath("$[0].motivo").value("Falta o frete"));
  }

  @Test
  @DisplayName("dois produtores: B nao retira nem le a revisao de A (404); aprovar como B e 403")
  void doisProdutores() throws Exception {
    mockMvc.perform(post("/api/notas/{n}/retirar-da-revisao", notaA)
            .header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/notas/{n}/revisoes", notaA).header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/contador/produtores/{p}/notas/{n}/aprovar", produtorA.usuarioId(),
            notaA).header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isForbidden());
    // Contador com o path do produtor fora da carteira: 404.
    mockMvc.perform(post("/api/contador/produtores/{p}/notas/{n}/aprovar", produtorB.usuarioId(),
            notaA).header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/contador/produtores/{p}/notas/{n}/aprovar", produtorA.usuarioId(),
            notaA).header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APROVADA"));
    mockMvc.perform(post("/api/notas/{n}/retirar-da-revisao", notaA)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("TRANSICAO_REVISAO_INVALIDA"));
  }
}
