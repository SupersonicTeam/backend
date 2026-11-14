package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.RegistroAuditoria;
import br.com.agrocontabil.repository.RegistroAuditoriaRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Auditoria das operações críticas pela cadeia real, com JWT (issue #149, RNF012). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Auditoria: registro das operacoes criticas e consulta do contador (#149)")
class AuditoriaIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL_PRODUTOR = "auditado@fazenda.com.br";
  private static final String EMAIL_CONTADOR = "escritorio@campo.com.br";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private RegistroAuditoriaRepository auditoriaRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private CadastroResponse produtor;
  private CadastroResponse contador;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtor = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Joaquim da Silva", "52998224725", EMAIL_PRODUTOR, SENHA));
    contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio Campo", "11222333000181", EMAIL_CONTADOR, SENHA));
    jdbcTemplate.update("UPDATE produtores SET estado = 'MT' WHERE id = ?", produtor.usuarioId());
    jdbcTemplate.update("INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao,"
        + " inicio) SELECT RANDOM_UUID(), escritorio_id, ?, 'ATIVO', CURRENT_TIMESTAMP"
        + " FROM contadores WHERE id = ?",
        produtor.usuarioId(), contador.usuarioId());
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private List<RegistroAuditoria> registros() {
    return auditoriaRepository.findByProdutorId(produtor.usuarioId(), Pageable.unpaged()).stream()
        .sorted(Comparator.comparing(RegistroAuditoria::getOcorridoEm))
        .toList();
  }

  @Test
  @DisplayName("criar, editar e excluir nota: um registro cada, com recurso, autor e status")
  void cicloDaNota() throws Exception {
    String token = token(EMAIL_PRODUTOR);

    MvcResult criada = mockMvc.perform(post("/api/notas").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).content(payloadNota(1)))
        .andExpect(status().isCreated())
        // ResultadoAuditavel e so para a auditoria: nada dele aparece no contrato JSON.
        .andExpect(jsonPath("$.recursoAuditado").doesNotExist())
        .andExpect(jsonPath("$.produtorAuditado").doesNotExist())
        .andReturn();
    UUID notaId = UUID.fromString(objectMapper.readTree(criada.getResponse().getContentAsString())
        .get("id").asText());
    mockMvc.perform(put("/api/notas/{id}", notaId).header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).content(payloadNota(2)))
        .andExpect(status().isOk());
    mockMvc.perform(delete("/api/notas/{id}", notaId).header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isNoContent());

    List<RegistroAuditoria> registros = registros();
    assertThat(registros).extracting(RegistroAuditoria::getAcao).containsExactly(
        AcaoAuditada.NOTA_CRIADA, AcaoAuditada.NOTA_ATUALIZADA, AcaoAuditada.NOTA_EXCLUIDA);
    assertThat(registros).allSatisfy(r -> {
      assertThat(r.getRecursoId()).isEqualTo(notaId);
      assertThat(r.getUsuarioId()).isEqualTo(produtor.usuarioId());
      assertThat(r.getProdutorId()).isEqualTo(produtor.usuarioId());
      assertThat(r.getPapel()).isEqualTo("PRODUTOR");
      assertThat(r.isSucesso()).isTrue();
    });
    assertThat(registros.get(0).getEstadoDepois()).isEqualTo("RASCUNHO");
    assertThat(registros.get(1).getEstadoAntes()).isEqualTo("RASCUNHO");
    assertThat(registros.get(2).getEstadoAntes()).isEqualTo("RASCUNHO");
  }

  @Test
  @DisplayName("falha tambem e registrada, so com o codigo do erro e sem o estado lido antes")
  void falhaRegistrada() throws Exception {
    String token = token(EMAIL_PRODUTOR);
    UUID alheia = UUID.randomUUID();

    mockMvc.perform(delete("/api/notas/{id}", alheia).header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isNotFound());

    RegistroAuditoria registro = registros().get(0);
    assertThat(registro.getAcao()).isEqualTo(AcaoAuditada.NOTA_EXCLUIDA);
    assertThat(registro.isSucesso()).isFalse();
    assertThat(registro.getCodigoErro()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(registro.getRecursoId()).isEqualTo(alheia);
    assertThat(registro.getEstadoAntes()).isNull();
  }

  @Test
  @DisplayName("guia criada pelo contador entra no produtor da carteira; paga duas vezes, 1 falha")
  void guiasDoContadorEDoProdutor() throws Exception {
    MvcResult criada = mockMvc.perform(post("/api/contador/guias")
            .header(HttpHeaders.AUTHORIZATION, token(EMAIL_CONTADOR))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"produtorId": "%s", "tipoGuia": "DARF", "dataVencimento": "2026-12-20",
                 "valor": 500.00}
                """.formatted(produtor.usuarioId())))
        .andExpect(status().isCreated()).andReturn();
    String guiaId = objectMapper.readTree(criada.getResponse().getContentAsString())
        .get("id").asText();
    String tokenProdutor = token(EMAIL_PRODUTOR);
    mockMvc.perform(patch("/api/contador/guias/{id}/pagar", guiaId)
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andExpect(status().isOk());
    mockMvc.perform(patch("/api/contador/guias/{id}/pagar", guiaId)
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andExpect(status().isConflict());

    List<RegistroAuditoria> registros = registros();
    assertThat(registros).extracting(RegistroAuditoria::getAcao).containsExactly(
        AcaoAuditada.GUIA_CRIADA, AcaoAuditada.GUIA_PAGA, AcaoAuditada.GUIA_PAGA);
    assertThat(registros.get(0).getPapel()).isEqualTo("CONTADOR");
    assertThat(registros.get(0).getUsuarioId()).isEqualTo(contador.usuarioId());
    assertThat(registros.get(0).getProdutorId()).isEqualTo(produtor.usuarioId());
    assertThat(registros.get(1).getEstadoAntes()).isEqualTo("PENDENTE");
    assertThat(registros.get(1).getEstadoDepois()).isEqualTo("PAGA");
    assertThat(registros.get(2).isSucesso()).isFalse();
    assertThat(registros.get(2).getCodigoErro()).isEqualTo("GUIA_JA_PAGA");
  }

  @Test
  @DisplayName("rotas novas de guia (#273) tambem sao auditadas: criar pela carteira e pelo produtor, pagar")
  void guiasPelasRotasNovas() throws Exception {
    String corpo = "{\"tipoGuia\": \"DARF\", \"dataVencimento\": \"2026-12-20\", \"valor\": 500.00}";
    MvcResult criada = mockMvc.perform(
            post("/api/contador/produtores/{id}/guias", produtor.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, token(EMAIL_CONTADOR))
            .contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isCreated()).andReturn();
    String guiaId = objectMapper.readTree(criada.getResponse().getContentAsString())
        .get("id").asText();
    String tokenProdutor = token(EMAIL_PRODUTOR);
    mockMvc.perform(post("/api/guias")
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor)
            .contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isCreated());
    mockMvc.perform(patch("/api/guias/{id}/pagar", guiaId)
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andExpect(status().isOk());

    List<RegistroAuditoria> registros = registros();
    assertThat(registros).extracting(RegistroAuditoria::getAcao).containsExactly(
        AcaoAuditada.GUIA_CRIADA, AcaoAuditada.GUIA_CRIADA, AcaoAuditada.GUIA_PAGA);
    assertThat(registros.get(0).getPapel()).isEqualTo("CONTADOR");
    assertThat(registros.get(0).getProdutorId()).isEqualTo(produtor.usuarioId());
    assertThat(registros.get(1).getPapel()).isEqualTo("PRODUTOR");
    assertThat(registros.get(2).getEstadoAntes()).isEqualTo("PENDENTE");
    assertThat(registros.get(2).getEstadoDepois()).isEqualTo("PAGA");
  }

  @Test
  @DisplayName("troca de senha e registrada sobre a conta, sem nada da senha")
  void trocaDeSenha() throws Exception {
    mockMvc.perform(post("/api/auth/senha/alterar")
            .header(HttpHeaders.AUTHORIZATION, token(EMAIL_PRODUTOR))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"senhaAtual\":\"%s\",\"novaSenha\":\"%s\"}"
                .formatted(SENHA, "outra frase bem comprida e nova")))
        .andExpect(status().isNoContent());

    RegistroAuditoria registro = registros().get(0);
    assertThat(registro.getAcao()).isEqualTo(AcaoAuditada.SENHA_ALTERADA);
    assertThat(registro.getRecursoId()).isEqualTo(produtor.usuarioId());
    assertThat(registro.toString()).doesNotContain(SENHA).doesNotContain("outra frase");
  }

  @Test
  @DisplayName("GET /api/auditoria: contador da carteira le (array + X-Total-Count)")
  void contadorConsulta() throws Exception {
    String token = token(EMAIL_PRODUTOR);
    mockMvc.perform(post("/api/notas").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).content(payloadNota(1)))
        .andExpect(status().isCreated());

    mockMvc.perform(get("/api/auditoria").param("produtorId", produtor.usuarioId().toString())
            .header(HttpHeaders.AUTHORIZATION, token(EMAIL_CONTADOR)))
        .andExpect(status().isOk())
        .andExpect(header().string(GuiaFiscalController.HEADER_TOTAL, "1"))
        .andExpect(jsonPath("$[0].acao").value("NOTA_CRIADA"))
        .andExpect(jsonPath("$[0].estadoDepois").value("RASCUNHO"))
        .andExpect(jsonPath("$[0].sucesso").value(true));
  }

  @Test
  @DisplayName("GET /api/auditoria: produtor 403, produtor fora da carteira 404, size 400")
  void consultaRestrita() throws Exception {
    mockMvc.perform(get("/api/auditoria").param("produtorId", produtor.usuarioId().toString())
            .header(HttpHeaders.AUTHORIZATION, token(EMAIL_PRODUTOR)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));

    CadastroResponse outro = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Maria Souza", "11144477735", "maria@fazenda.com.br", SENHA));
    String tokenContador = token(EMAIL_CONTADOR);
    mockMvc.perform(get("/api/auditoria").param("produtorId", outro.usuarioId().toString())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/auditoria").param("produtorId", produtor.usuarioId().toString())
            .param("size", "201").header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isBadRequest());
  }

  private static String payloadNota(int numero) {
    return """
        {
          "numero": %d,
          "naturezaOperacao": "Venda de producao",
          "dataEmissao": "2026-06-10T10:00:00-03:00",
          "destinatarioNome": "Comprador",
          "destinatarioCpfCnpj": "11144477735",
          "itens": [
            {
              "numeroItem": 1,
              "descricao": "Soja em graos",
              "ncm": "12019000",
              "cfop": "6101",
              "unidade": "KG",
              "quantidade": 100,
              "valorUnitario": 2.50
            }
          ]
        }
        """.formatted(numero);
  }
}
