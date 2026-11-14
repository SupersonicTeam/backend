package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.ModeloPedido;
import br.com.agrocontabil.model.TipoDocumentoPendencia;
import br.com.agrocontabil.repository.ModeloPedidoRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.ModeloPedidoService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Modelos de pedido de documento do escritório (issue #517), pela cadeia real com JWT: escopo
 * pelo escritório (404), papéis e funções (403), título repetido e teto (409), validação (400),
 * auditoria sem texto e limpeza junto com o escritório. Contrato da frontend#185.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Modelos de pedido do escritorio (#517)")
class ModeloPedidoIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String ROTA = "/api/contador/modelos-pedido";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private ModeloPedidoRepository modeloRepository;

  private CadastroResponse adminA;
  private UUID escritorioA;
  private String tokenA;
  private String tokenB;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    adminA = contador("Escritorio A", "11222333000181", "a@escritorio.com.br");
    contador("Escritorio B", "11444777000161", "b@escritorio.com.br");
    escritorioA = jdbcTemplate.queryForObject(
        "SELECT escritorio_id FROM contadores WHERE id = ?", UUID.class, adminA.usuarioId());
    tokenA = token("a@escritorio.com.br");
    tokenB = token("b@escritorio.com.br");
  }

  private CadastroResponse contador(String nome, String documento, String email) {
    return cadastroService.cadastrar(
        new CadastroRequest(PapelUsuario.CONTADOR, nome, documento, email, SENHA));
  }

  /** Contador que entra no escritório A com a função dada ({@code null} = sem escritório). */
  private String membroDeA(String documento, String email, String funcao) throws Exception {
    CadastroResponse membro = contador("Membro " + email, documento, email);
    jdbcTemplate.update("UPDATE contadores SET escritorio_id = ?, funcao_escritorio = ?"
        + " WHERE id = ?", funcao == null ? null : escritorioA, funcao, membro.usuarioId());
    return token(email);
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private static String corpo(String titulo, String tipos, Object prazo) {
    return ("{\"titulo\":\"%s\",\"descricao\":\"Extrato de todas as contas\","
        + "\"tiposDocumento\":%s,\"prazoEmDias\":%s,\"observacoes\":\"Pode mandar em PDF\"}")
        .formatted(titulo, tipos, prazo);
  }

  private ResultActions criar(String token, String json) throws Exception {
    return mockMvc.perform(post(ROTA).header(HttpHeaders.AUTHORIZATION, token)
        .contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private UUID criado(String token, String titulo) throws Exception {
    MvcResult r = criar(token, corpo(titulo, "[\"EXTRATO\"]", 10))
        .andExpect(status().isCreated()).andReturn();
    return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString())
        .get("id").asText());
  }

  @Test
  @DisplayName("administrador cria (201), qualquer membro lista na ordem do titulo sem acento nem caixa")
  void criaEListaNaOrdemDoTitulo() throws Exception {
    criar(tokenA, "{\"titulo\":\"  Extrato bancário do mês  \",\"descricao\":\"   \","
        + "\"tiposDocumento\":[\"EXTRATO\",\"COMPROVANTE_PAGAMENTO\",\"EXTRATO\"],"
        + "\"prazoEmDias\":10,\"observacoes\":\"Pode mandar em PDF\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.titulo").value("Extrato bancário do mês"))
        .andExpect(jsonPath("$.descricao").doesNotExist())
        .andExpect(jsonPath("$.tiposDocumento", contains("COMPROVANTE_PAGAMENTO", "EXTRATO")))
        .andExpect(jsonPath("$.prazoEmDias").value(10))
        .andExpect(jsonPath("$.observacoes").value("Pode mandar em PDF"))
        .andExpect(jsonPath("$.status").doesNotExist());
    criado(tokenA, "Ágio do arrendamento");
    criar(tokenA, corpo("contrato de arrendamento", "[\"CONTRATO\"]", "null"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.prazoEmDias").doesNotExist());

    String assistente = membroDeA("52998224725", "juliana@escritorio.com.br", "ASSISTENTE");
    for (String token : List.of(tokenA, assistente)) {
      mockMvc.perform(get(ROTA).header(HttpHeaders.AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(header().string("X-Total-Count", "3"))
          .andExpect(jsonPath("$[*].titulo", contains("Ágio do arrendamento",
              "contrato de arrendamento", "Extrato bancário do mês")));
    }
  }

  @Test
  @DisplayName("modelo de outro escritorio nao aparece e e 404 no PUT e no DELETE")
  void escopoPeloEscritorio() throws Exception {
    UUID modeloDeA = criado(tokenA, "Extrato bancário do mês");

    mockMvc.perform(get(ROTA).header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "0"))
        .andExpect(content().json("[]"));
    mockMvc.perform(put(ROTA + "/{id}", modeloDeA).header(HttpHeaders.AUTHORIZATION, tokenB)
            .contentType(MediaType.APPLICATION_JSON).content(corpo("Outro", "[\"OUTRO\"]", 1)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
    mockMvc.perform(delete(ROTA + "/{id}", modeloDeA).header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete(ROTA + "/{id}", UUID.randomUUID())
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isNotFound());

    // B pode ter um modelo com o mesmo titulo: a unicidade e por escritorio.
    criado(tokenB, "Extrato bancário do mês");
    assertThat(modeloRepository.findById(modeloDeA)).hasValueSatisfying(m ->
        assertThat(m.getTitulo()).isEqualTo("Extrato bancário do mês"));
  }

  @Test
  @DisplayName("PUT substitui os campos (200); DELETE apaga (204) e o id vira 404")
  void editaEApaga() throws Exception {
    UUID id = criado(tokenA, "Extrato bancário do mês");

    mockMvc.perform(put(ROTA + "/{id}", id).header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"titulo\":\"EXTRATO BANCARIO DO MES\",\"tiposDocumento\":[\"OUTRO\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.titulo").value("EXTRATO BANCARIO DO MES"))
        .andExpect(jsonPath("$.descricao").doesNotExist())
        .andExpect(jsonPath("$.tiposDocumento", contains("OUTRO")))
        .andExpect(jsonPath("$.prazoEmDias").doesNotExist())
        .andExpect(jsonPath("$.observacoes").doesNotExist());

    mockMvc.perform(delete(ROTA + "/{id}", id).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isNoContent());
    assertThat(modeloRepository.findById(id)).isEmpty();
    mockMvc.perform(delete(ROTA + "/{id}", id).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("titulo repetido sem acento nem caixa: 409 MODELO_PEDIDO_DUPLICADO sem ecoar o titulo")
  void tituloRepetido() throws Exception {
    criado(tokenA, "Extrato bancário do mês");
    UUID outro = criado(tokenA, "Contrato de arrendamento");

    criar(tokenA, corpo("  extrato   BANCARIO do mes ", "[\"EXTRATO\"]", 5))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("MODELO_PEDIDO_DUPLICADO"))
        .andExpect(jsonPath("$.errors[0].field").value("titulo"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
        .andExpect(content().string(not(containsString("BANCARIO"))));
    mockMvc.perform(put(ROTA + "/{id}", outro).header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content(corpo("Extrato Bancário do Mês", "[\"EXTRATO\"]", 5)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("MODELO_PEDIDO_DUPLICADO"));
  }

  @Test
  @DisplayName("teto de 50 modelos por escritorio: o 51o e 409 LIMITE_MODELOS_PEDIDO")
  void tetoDoCatalogo() throws Exception {
    for (int i = 1; i < ModeloPedidoService.MAXIMO_POR_ESCRITORIO; i++) {
      modeloRepository.save(ModeloPedido.builder().escritorioId(escritorioA)
          .titulo("Modelo " + i).tituloNormalizado("modelo " + i)
          .tiposDocumento(EnumSet.of(TipoDocumentoPendencia.OUTRO)).build());
    }
    criado(tokenA, "O quinquagésimo");

    criar(tokenA, corpo("O quinquagésimo primeiro", "[\"OUTRO\"]", 1))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("LIMITE_MODELOS_PEDIDO"))
        .andExpect(jsonPath("$.errors[0].expectedValue").value("50"));
    mockMvc.perform(get(ROTA).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(header().string("X-Total-Count", "50"));
    // B segue com o catalogo dele.
    criado(tokenB, "O quinquagésimo primeiro");
  }

  @Test
  @DisplayName("CONTADOR e ASSISTENTE leem e nao alteram; produtor 403; sem escritorio 403")
  void papeisEFuncoes() throws Exception {
    UUID id = criado(tokenA, "Extrato bancário do mês");
    String contador = membroDeA("11144477735", "rafael@escritorio.com.br", "CONTADOR");
    String assistente = membroDeA("12345678909", "juliana@escritorio.com.br", "ASSISTENTE");
    String semEscritorio = membroDeA("39053344705", "ex@escritorio.com.br", null);
    cadastroService.cadastrar(new CadastroRequest(PapelUsuario.PRODUTOR, "Joaquim",
        "71460238001", "joaquim@fazenda.com.br", SENHA));
    String produtor = token("joaquim@fazenda.com.br");

    for (String token : List.of(contador, assistente)) {
      mockMvc.perform(get(ROTA).header(HttpHeaders.AUTHORIZATION, token))
          .andExpect(status().isOk());
      criar(token, corpo("Novo", "[\"OUTRO\"]", 1))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
      mockMvc.perform(put(ROTA + "/{id}", id).header(HttpHeaders.AUTHORIZATION, token)
              .contentType(MediaType.APPLICATION_JSON).content(corpo("Novo", "[\"OUTRO\"]", 1)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
      mockMvc.perform(delete(ROTA + "/{id}", id).header(HttpHeaders.AUTHORIZATION, token))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    }
    mockMvc.perform(get(ROTA).header(HttpHeaders.AUTHORIZATION, produtor))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    criar(produtor, corpo("Novo", "[\"OUTRO\"]", 1))
        .andExpect(status().isForbidden());
    mockMvc.perform(get(ROTA).header(HttpHeaders.AUTHORIZATION, semEscritorio))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("SEM_ESCRITORIO"));
    mockMvc.perform(get(ROTA)).andExpect(status().isUnauthorized());

    assertThat(modeloRepository.findById(id)).hasValueSatisfying(m ->
        assertThat(m.getTitulo()).isEqualTo("Extrato bancário do mês"));
    assertThat(modeloRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("validacao: 400 VALIDATION_ERROR, sem ecoar os textos do escritorio")
  void validacao() throws Exception {
    String longo = "Cliente Fulano de Tal ".repeat(6);
    Map<String, String> invalidos = Map.of(
        "titulo", corpo(" ", "[\"EXTRATO\"]", 1),
        "tiposDocumento", corpo("Extrato", "[]", 1),
        "prazoEmDias", corpo("Extrato", "[\"EXTRATO\"]", 0));
    for (Map.Entry<String, String> caso : invalidos.entrySet()) {
      criar(tokenA, caso.getValue())
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.errors[0].field").value(caso.getKey()));
    }
    criar(tokenA, corpo("Extrato", "[\"EXTRATO\"]", 366))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("prazoEmDias"));
    criar(tokenA, corpo("Extrato", "[\"EXTRATO\",\"CONTRATO\",\"OUTRO\",\"NOTA_FISCAL\","
        + "\"COMPROVANTE_PAGAMENTO\",\"EXTRATO\"]", 1))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("tiposDocumento"));
    criar(tokenA, corpo("Extrato", "[\"BOLETO\"]", 1))
        .andExpect(status().isBadRequest());
    criar(tokenA, corpo(longo, "[\"EXTRATO\"]", 1))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("titulo"))
        .andExpect(content().string(not(containsString("Fulano"))));
    criar(tokenA, "{\"titulo\":\"Extrato\",\"tiposDocumento\":[\"EXTRATO\"],\"observacoes\":\""
        + longo.repeat(8) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("observacoes"))
        .andExpect(content().string(not(containsString("Fulano"))));

    assertThat(modeloRepository.count()).isZero();
  }

  @Test
  @DisplayName("auditoria: criar, editar e apagar com o id do modelo e sem texto; recusa com o codigo")
  void auditoriaSemTexto() throws Exception {
    UUID id = criado(tokenA, "Extrato bancário do mês");
    mockMvc.perform(put(ROTA + "/{id}", id).header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content(corpo("Extrato", "[\"OUTRO\"]", 2)))
        .andExpect(status().isOk());
    criar(tokenA, corpo("extrato", "[\"OUTRO\"]", 2)).andExpect(status().isConflict());
    mockMvc.perform(delete(ROTA + "/{id}", id).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isNoContent());

    List<Map<String, Object>> registros = jdbcTemplate.queryForList("SELECT acao, recurso,"
        + " recurso_id, sucesso, codigo_erro, estado_antes, estado_depois, escritorio_id"
        + " FROM registro_auditoria WHERE recurso = 'MODELO_PEDIDO' ORDER BY ocorrido_em");
    assertThat(registros).extracting(r -> r.get("acao") + ":" + r.get("sucesso"))
        .containsExactly("MODELO_PEDIDO_CRIADO:true", "MODELO_PEDIDO_ALTERADO:true",
            "MODELO_PEDIDO_CRIADO:false", "MODELO_PEDIDO_APAGADO:true");
    assertThat(registros).filteredOn(r -> Boolean.TRUE.equals(r.get("sucesso")))
        .allSatisfy(r -> {
          assertThat(r.get("recurso_id")).isEqualTo(id);
          assertThat(r.get("escritorio_id")).isEqualTo(escritorioA);
          assertThat(r.get("estado_antes")).isNull();
          assertThat(r.get("estado_depois")).isNull();
        });
    assertThat(registros.get(2).get("codigo_erro")).isEqualTo("MODELO_PEDIDO_DUPLICADO");
  }

  @Test
  @DisplayName("escritorio que sai (exclusao de dados do unico membro) leva os modelos junto")
  void semModeloOrfao() throws Exception {
    UUID id = criado(tokenA, "Extrato bancário do mês");

    mockMvc.perform(delete("/api/usuarios/{id}/dados", adminA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"senha\":\"%s\"}".formatted(SENHA)))
        .andExpect(status().isNoContent());

    assertThat(modeloRepository.findById(id)).isEmpty();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM modelo_pedido"
        + " WHERE escritorio_id = ?", Integer.class, escritorioA)).isZero();
  }
}
