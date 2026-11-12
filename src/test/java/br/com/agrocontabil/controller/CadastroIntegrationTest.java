package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.exception.CadastroConflitoException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoEscritorio;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Cadastro de conta pela cadeia de filtros real (issue #205).
 *
 * <p>Profile {@code test}: o {@code MockIdentidadeFilter} não existe, como em produção — o endpoint
 * precisa responder sem identidade nenhuma.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("POST /api/auth/cadastro — conta com credencial (#205, #519)")
class CadastroIntegrationTest {

  private static final String URL = "/api/auth/cadastro";
  private static final String CPF = "52998224725";
  private static final String CNPJ = "11222333000181";
  private static final String SENHA = "cavalo bateria grampo correto";

  @Autowired private MockMvc mockMvc;
  @Autowired private CredencialUsuarioRepository credencialRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private EscritorioRepository escritorioRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private CadastroService cadastroService;
  @Autowired private TestDatabaseCleaner cleaner;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
  }

  private static String corpo(String papel, String documento, String email, String senha) {
    String campoSenha = senha == null ? "" : ",\"senha\":\"" + senha + "\"";
    return """
        {"papel":"%s","nomeCompleto":"Fazenda Boa Vista","cpfCnpj":"%s","email":"%s"%s}
        """.formatted(papel, documento, email, campoSenha);
  }

  private MvcResult cadastrar(String json) throws Exception {
    return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
        .andReturn();
  }

  @Test
  @DisplayName("produtor: 201, credencial com hash BCrypt e nenhum segredo na resposta")
  void cadastraProdutor() throws Exception {
    MvcResult resultado = mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("PRODUTOR", CPF, "Joaquim@Fazenda.com.br", SENHA)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.papel").value("PRODUTOR"))
        .andExpect(jsonPath("$.email").value("joaquim@fazenda.com.br"))
        .andExpect(jsonPath("$.usuarioId").isNotEmpty())
        .andReturn();

    String resposta = resultado.getResponse().getContentAsString();
    assertThat(resposta).doesNotContain(SENHA, "bcrypt", "senha", CPF);

    CredencialUsuario credencial =
        credencialRepository.findByEmail("joaquim@fazenda.com.br").orElseThrow();
    assertThat(credencial.getSenhaHash()).startsWith("{bcrypt}$2a$");
    assertThat(passwordEncoder.matches(SENHA, credencial.getSenhaHash())).isTrue();
    assertThat(credencial.getPapel()).isEqualTo(PapelUsuario.PRODUTOR);
    assertThat(credencial.getVersaoSessao()).isZero();

    Produtor produtor = produtorRepository.findById(credencial.getProdutorId()).orElseThrow();
    assertThat(produtor.getCpfCnpj()).isEqualTo(CPF);
    assertThat(resposta).contains(produtor.getId().toString());
  }

  @Test
  @DisplayName("contador sem tipo de conta (tela antiga): escritorio pessoal com o nome dele")
  void cadastraContador() throws Exception {
    mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("CONTADOR", CPF, "escritorio@campocerto.com.br", SENHA)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.papel").value("CONTADOR"));

    CredencialUsuario credencial =
        credencialRepository.findByEmail("escritorio@campocerto.com.br").orElseThrow();
    Contador contador = contadorRepository.findById(credencial.getContadorId()).orElseThrow();
    assertThat(credencial.getProdutorId()).isNull();
    assertThat(credencial.getTermosAceitosEm()).isNull();
    assertThat(credencial.getTermosVersao()).isNull();
    assertThat(contador.getCrc()).isNull();
    assertThat(contador.getTelefone()).isNull();
    Escritorio escritorio = escritorioRepository.findById(contador.getEscritorioId()).orElseThrow();
    assertThat(escritorio.getTipo()).isEqualTo(TipoEscritorio.PESSOAL);
    assertThat(escritorio.getNome()).isEqualTo("Fazenda Boa Vista");
    assertThat(escritorio.getCnpj()).isNull();
    assertThat(escritorio.getSlug()).isNull();
    assertThat(contador.getFuncao()).isEqualTo(FuncaoEscritorio.ADMINISTRADOR);
  }

  /** Cadastro de contador da tela nova (#519), com os campos opcionais como vierem. */
  private static String corpoContador(String documento, String email, String escritorio,
      String extras) {
    return """
        {"papel":"CONTADOR","nomeCompleto":"Patrícia Moreira","cpfCnpj":"%s","email":"%s",\
        "senha":"%s"%s%s}
        """.formatted(documento, email, SENHA,
        escritorio == null ? "" : ",\"escritorio\":" + escritorio, extras);
  }

  private static final String ENDERECO = """
      {"cep":"85810000","logradouro":"Rua Paraná","numero":"1200","complemento":"Sala 3",\
      "bairro":"Centro","codigoMunicipioIbge":"4104808","municipio":"Cascavel","uf":"PR"}""";

  /** Escritório empresarial completo, com o slug e o CNPJ dados. */
  private static String escritorioEmpresarial(String cnpj, String slug) {
    return """
        {"nome":" Contabilidade Campo Verde ","cnpj":"%s","telefone":"4533330000",\
        "endereco":%s,"slug":"%s","cienteExposicaoPublica":true}""".formatted(cnpj, ENDERECO, slug);
  }

  private static final String EMPRESARIAL = ",\"tipoConta\":\"EMPRESARIAL\"";

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA)))
        .andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  @Test
  @DisplayName("contador empresarial (#519): escritorio completo, endereco proprio e aceite gravados")
  void cadastraContadorEmpresarial() throws Exception {
    MvcResult resultado = cadastrar(corpoContador(CPF, "patricia@exemplo.com.br",
        escritorioEmpresarial("11444777000161", " CampoVerde "),
        EMPRESARIAL + ",\"crc\":\"PR-012345/O\",\"aceiteTermos\":true"));
    assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
    assertThat(resultado.getResponse().getContentAsString())
        .doesNotContain("11444777000161", "PR-012345", "4533330000", CPF, "campoverde");

    CredencialUsuario credencial =
        credencialRepository.findByEmail("patricia@exemplo.com.br").orElseThrow();
    assertThat(credencial.getTermosAceitosEm()).isNotNull();
    assertThat(credencial.getTermosVersao()).isEqualTo("2026-11-11");
    Contador contador = contadorRepository.findById(credencial.getContadorId()).orElseThrow();
    assertThat(contador.getNomeCompleto()).isEqualTo("Patrícia Moreira");
    assertThat(contador.getCpfCnpj()).isEqualTo(CPF);
    assertThat(contador.getCrc()).isEqualTo("PR-012345/O");
    assertThat(contador.getTelefone()).isNull();
    assertThat(contador.getFuncao()).isEqualTo(FuncaoEscritorio.ADMINISTRADOR);
    Escritorio escritorio = escritorioRepository.findById(contador.getEscritorioId()).orElseThrow();
    assertThat(escritorio.getTipo()).isEqualTo(TipoEscritorio.EMPRESARIAL);
    assertThat(escritorio.getNome()).isEqualTo("Contabilidade Campo Verde");
    assertThat(escritorio.getCnpj()).isEqualTo("11444777000161");
    assertThat(escritorio.getTelefone()).isEqualTo("4533330000");
    assertThat(escritorio.getEndereco().getLogradouro()).isEqualTo("Rua Paraná");
    assertThat(escritorio.getEndereco().getUf()).isEqualTo("PR");
    // Slug normalizado e fora do intervalo de 30 dias: o do cadastro se corrige na hora.
    assertThat(escritorio.getSlug()).isEqualTo("campoverde");
    assertThat(escritorio.getSlugAlteradoEm()).isNull();
    // Reservado, mas nao servido: o CNPJ do cadastro publico nasce sem verificacao (#520).
    assertThat(escritorio.cnpjVerificado()).isFalse();
    mockMvc.perform(get("/api/publico/escritorios/campoverde")).andExpect(status().isNotFound());
    // O telefone do escritorio e cifrado em repouso, como os outros (#147).
    assertThat(jdbc.queryForObject("SELECT telefone FROM escritorio WHERE id = ?", String.class,
        escritorio.getId())).startsWith("v1:");

    String token = token("patricia@exemplo.com.br");
    mockMvc.perform(get("/api/escritorio").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tipo").value("EMPRESARIAL"))
        .andExpect(jsonPath("$.telefone").value("4533330000"))
        .andExpect(jsonPath("$.endereco.codigoMunicipioIbge").value("4104808"))
        .andExpect(jsonPath("$.endereco.complemento").value("Sala 3"))
        .andExpect(jsonPath("$.slug").value("campoverde"))
        .andExpect(jsonPath("$.enderecoAlteravelEm").doesNotExist());
    mockMvc.perform(put("/api/escritorio/endereco").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slug\":\"campoverdeoeste\",\"cienteExposicaoPublica\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("campoverdeoeste"));
  }

  @Test
  @DisplayName("contador pessoal (#519): sem escritorio no corpo; CRC e telefone da pessoa")
  void cadastraContadorPessoal() throws Exception {
    MvcResult resultado = cadastrar(corpoContador(CPF, "autonoma@exemplo.com.br", null,
        ",\"tipoConta\":\"PESSOAL\",\"crc\":\"PR-1\",\"telefone\":\"45999990000\""));
    assertThat(resultado.getResponse().getStatus()).isEqualTo(201);

    Contador contador = contadorRepository.findById(credencialRepository
        .findByEmail("autonoma@exemplo.com.br").orElseThrow().getContadorId()).orElseThrow();
    assertThat(contador.getCrc()).isEqualTo("PR-1");
    assertThat(contador.getTelefone()).isEqualTo("45999990000");
    assertThat(jdbc.queryForObject("SELECT telefone FROM contadores WHERE id = ?", String.class,
        contador.getId())).startsWith("v1:");
    Escritorio escritorio = escritorioRepository.findById(contador.getEscritorioId()).orElseThrow();
    assertThat(escritorio.getTipo()).isEqualTo(TipoEscritorio.PESSOAL);
    assertThat(escritorio.getNome()).isEqualTo("Patrícia Moreira");
    assertThat(escritorio.getCnpj()).isNull();
    assertThat(escritorio.getTelefone()).isNull();
    assertThat(escritorio.getEndereco()).isNull();
  }

  @Test
  @DisplayName("contador (#519): CRC e telefone vazios = nao informados")
  void contadorComCamposVazios() throws Exception {
    MvcResult resultado = cadastrar(corpoContador(CPF, "vazios@exemplo.com.br", null,
        ",\"crc\":\"\",\"telefone\":\"\""));
    assertThat(resultado.getResponse().getStatus()).isEqualTo(201);

    Contador contador = contadorRepository.findById(credencialRepository
        .findByEmail("vazios@exemplo.com.br").orElseThrow().getContadorId()).orElseThrow();
    assertThat(contador.getCrc()).isNull();
    assertThat(contador.getTelefone()).isNull();
  }

  @Test
  @DisplayName("tipo de conta e escritorio fora do par (#519): 400 no escritorio, nada gravado")
  void escritorioConformeOTipo() throws Exception {
    // Empresarial sem escritorio.
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpoContador(CPF, "semesc@exemplo.com.br", null, EMPRESARIAL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("escritorio"));
    // Pessoal, ou sem tipo, com escritorio.
    for (String tipo : List.of(",\"tipoConta\":\"PESSOAL\"", "")) {
      mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
              .content(corpoContador(CPF, "comesc@exemplo.com.br",
                  escritorioEmpresarial("11444777000161", "campoverde"), tipo)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.length()").value(1))
          .andExpect(jsonPath("$.errors[0].field").value("escritorio"))
          .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    }
    assertThat(contadorRepository.count()).isZero();
    assertThat(escritorioRepository.count()).isZero();
  }

  @Test
  @DisplayName("contador com CNPJ no proprio documento (#519): 400 no cpfCnpj, nada gravado")
  void contadorComCnpjNoDocumento() throws Exception {
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpoContador(CNPJ, "cnpj@exemplo.com.br",
                escritorioEmpresarial("11444777000161", "campoverde"), EMPRESARIAL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("cpfCnpj"))
        .andExpect(jsonPath("$.errors[0].message").value(
            Matchers.containsString("'escritorio.cnpj'")));
    // O mesmo CNPJ no produtor continua valendo.
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("PRODUTOR", CNPJ, "empresa@fazenda.com.br", SENHA)))
        .andExpect(status().isCreated());

    assertThat(contadorRepository.count()).isZero();
    assertThat(escritorioRepository.count()).isZero();
  }

  @Test
  @DisplayName("escritorio empresarial invalido (#519): 400 no campo aninhado, nada gravado")
  void escritorioInvalido() throws Exception {
    Map<String, String> casos = new LinkedHashMap<>();
    casos.put("escritorio.cnpj", escritorioEmpresarial("11444777000160", "campoverde"));
    casos.put("escritorio.telefone", escritorioEmpresarial("11444777000161", "campoverde")
        .replace("\"4533330000\"", "\"(45) 3333-0000\""));
    casos.put("escritorio.endereco.codigoMunicipioIbge",
        escritorioEmpresarial("11444777000161", "campoverde").replace("4104808", "5103403"));
    casos.put("escritorio.endereco.bairro",
        escritorioEmpresarial("11444777000161", "campoverde").replace("\"Centro\"", "\" \""));
    casos.put("escritorio.cienteExposicaoPublica",
        escritorioEmpresarial("11444777000161", "campoverde").replace(":true}", ":false}"));
    for (Map.Entry<String, String> caso : casos.entrySet()) {
      mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
              .content(corpoContador(CPF, "invalido@exemplo.com.br", caso.getValue(),
                  EMPRESARIAL)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[*].field", Matchers.everyItem(Matchers.is(caso.getKey()))));
    }
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpoContador(CPF, "slug@exemplo.com.br",
                escritorioEmpresarial("11444777000161", "a--b"), EMPRESARIAL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("SLUG_INVALIDO"))
        .andExpect(jsonPath("$.errors[0].field").value("escritorio.slug"));

    assertThat(contadorRepository.count()).isZero();
    assertThat(escritorioRepository.count()).isZero();
  }

  @Test
  @DisplayName("slug em uso ou reservado no cadastro empresarial (#519): 409, nada gravado")
  void slugIndisponivel() throws Exception {
    assertThat(cadastrar(corpoContador(CPF, "primeira@exemplo.com.br",
        escritorioEmpresarial("11444777000161", "campoverde"), EMPRESARIAL))
        .getResponse().getStatus()).isEqualTo(201);

    for (String slug : List.of("campoverde", "www")) {
      mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
              .content(corpoContador("11144477735", "segunda@exemplo.com.br",
                  escritorioEmpresarial("11444777000161", slug), EMPRESARIAL)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.errors[0].code").value("SLUG_INDISPONIVEL"))
          .andExpect(jsonPath("$.errors[0].field").value("escritorio.slug"));
    }
    // A transacao inteira voltou: so a primeira conta existe. O CNPJ repetido nao e conflito.
    assertThat(contadorRepository.count()).isEqualTo(1);
    assertThat(escritorioRepository.count()).isEqualTo(1);
    assertThat(credencialRepository.findByEmail("segunda@exemplo.com.br")).isEmpty();
  }

  @Test
  @DisplayName("CNPJ repetido no cadastro empresarial (#520): 201 nos dois, sem oraculo, e nenhum endereco servido")
  void cnpjRepetidoNaoEConflito() throws Exception {
    assertThat(cadastrar(corpoContador(CPF, "primeira@exemplo.com.br",
        escritorioEmpresarial("11444777000161", "campoverde"), EMPRESARIAL))
        .getResponse().getStatus()).isEqualTo(201);
    assertThat(cadastrar(corpoContador("11144477735", "segunda@exemplo.com.br",
        escritorioEmpresarial("11444777000161", "campoverde-oeste"), EMPRESARIAL))
        .getResponse().getStatus()).isEqualTo(201);

    assertThat(escritorioRepository.findAll()).hasSize(2)
        .allSatisfy(e -> assertThat(e.cnpjVerificado()).isFalse());
    for (String slug : List.of("campoverde", "campoverde-oeste")) {
      mockMvc.perform(get("/api/publico/escritorios/{slug}", slug))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  @DisplayName("aceite dos termos (#519): false e 400 no aceiteTermos, sem gravar nada")
  void aceiteDosTermosRecusado() throws Exception {
    String base = corpo("PRODUTOR", CPF, "termos@fazenda.com.br", SENHA).strip();
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(base.substring(0, base.length() - 1) + ",\"aceiteTermos\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("aceiteTermos"));
    assertThat(produtorRepository.count()).isZero();

    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(base.substring(0, base.length() - 1) + ",\"aceiteTermos\":true}"))
        .andExpect(status().isCreated());
    CredencialUsuario credencial =
        credencialRepository.findByEmail("termos@fazenda.com.br").orElseThrow();
    assertThat(credencial.getTermosAceitosEm()).isNotNull();
    assertThat(credencial.getTermosVersao()).isEqualTo("2026-11-11");
  }

  @Test
  @DisplayName("produtor com escritorio, CRC ou tipo de conta (#519): 400 no campo, nada gravado")
  void produtorComCamposDoContador() throws Exception {
    String base = corpo("PRODUTOR", CPF, "produtor@fazenda.com.br", SENHA).strip();
    String semFecho = base.substring(0, base.length() - 1);
    Map<String, String> casos = Map.of(
        "escritorio", semFecho + ",\"escritorio\":"
            + escritorioEmpresarial("11444777000161", "campoverde") + "}",
        "crc", semFecho + ",\"crc\":\"PR-012345/O\"}",
        "tipoConta", semFecho + ",\"tipoConta\":\"EMPRESARIAL\"}");
    for (Map.Entry<String, String> caso : casos.entrySet()) {
      mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(caso.getValue()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.length()").value(1))
          .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.errors[0].field").value(caso.getKey()));
    }

    assertThat(produtorRepository.count()).isZero();
    assertThat(escritorioRepository.count()).isZero();
  }

  @Test
  @DisplayName("telefone (#519): invalido e 400 nos dois papeis; valido vai para o produtor")
  void telefone() throws Exception {
    String base = corpo("PRODUTOR", CPF, "fone@fazenda.com.br", SENHA).strip();
    String semFecho = base.substring(0, base.length() - 1);

    for (String invalido : List.of("999990000", "(45) 99999-0000", "459999900001")) {
      mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
              .content(semFecho + ",\"telefone\":\"" + invalido + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.errors[0].field").value("telefone"));
    }
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpoContador(CPF, "fone@exemplo.com.br", null, ",\"telefone\":\"4599\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("telefone"));
    assertThat(credencialRepository.count()).isZero();

    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(semFecho + ",\"telefone\":\"6533330001\"}"))
        .andExpect(status().isCreated());
    Produtor produtor = produtorRepository.findById(credencialRepository
        .findByEmail("fone@fazenda.com.br").orElseThrow().getProdutorId()).orElseThrow();
    assertThat(produtor.getTelefone()).isEqualTo("6533330001");
  }

  @Test
  @DisplayName("CRC acima de 20 caracteres (#519): 400 no crc")
  void crcLongo() throws Exception {
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpoContador(CPF, "crc@exemplo.com.br", null,
                ",\"crc\":\"PR-0123456789/O-123456\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("crc"));
    assertThat(contadorRepository.count()).isZero();
  }

  @Test
  @DisplayName("e-mail repetido (outra caixa) e CPF repetido respondem o mesmo 409 generico")
  void conflitosIndistinguiveis() throws Exception {
    assertThat(cadastrar(corpo("PRODUTOR", CPF, "um@fazenda.com.br", SENHA))
        .getResponse().getStatus()).isEqualTo(201);

    String emailRepetido = cadastrar(corpo("PRODUTOR", "11144477735", "UM@fazenda.com.br", SENHA))
        .getResponse().getContentAsString();
    String cpfRepetido = cadastrar(corpo("PRODUTOR", CPF, "dois@fazenda.com.br", SENHA))
        .getResponse().getContentAsString();

    assertThat(emailRepetido).contains("CADASTRO_CONFLITO").doesNotContain("um@fazenda");
    assertThat(cpfRepetido).contains("CADASTRO_CONFLITO").doesNotContain(CPF);
    assertThat(emailRepetido.replaceAll("\"timestamp\":\"[^\"]*\"", ""))
        .isEqualTo(cpfRepetido.replaceAll("\"timestamp\":\"[^\"]*\"", ""));
    assertThat(credencialRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("CPF de produtor ja existente sem credencial nao e reivindicado (anti tomada de conta)")
  void produtorExistenteNaoEhReivindicado() throws Exception {
    // Grafia do seed, com mascara: a checagem precisa enxergar as duas.
    produtorRepository.save(Produtor.builder()
        .nomeCompleto("Produtor do Escritorio").cpfCnpj("529.982.247-25").build());

    mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("PRODUTOR", CPF, "invasor@exemplo.com", SENHA)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("CADASTRO_CONFLITO"));

    assertThat(credencialRepository.count()).isZero();
    assertThat(produtorRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("senha fraca: 400 SENHA_FRACA sem ecoar a senha e sem gravar nada")
  void senhaFraca() throws Exception {
    String senhaFraca = "Senha123456";
    String resposta = mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("PRODUTOR", CPF, "fraca@fazenda.com.br", senhaFraca)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("SENHA_FRACA"))
        .andExpect(jsonPath("$.errors[0].field").value("senha"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
        .andReturn().getResponse().getContentAsString();

    assertThat(resposta).doesNotContain(senhaFraca);
    assertThat(produtorRepository.count()).isZero();
  }

  @Test
  @DisplayName("senha com o proprio telefone (#521): 400 SENHA_FRACA sem ecoar o numero")
  void senhaComTelefone() throws Exception {
    String json = """
        {"papel":"PRODUTOR","nomeCompleto":"Fazenda Boa Vista","cpfCnpj":"%s",\
        "email":"fone@fazenda.com.br","senha":"lavoura (45) 99999-0000 boa",\
        "telefone":"45999990000"}""".formatted(CPF);
    String resposta = mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("SENHA_FRACA"))
        .andExpect(jsonPath("$.errors[0].field").value("senha"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
        .andReturn().getResponse().getContentAsString();

    assertThat(resposta).doesNotContain("99999", "45999990000");
    assertThat(produtorRepository.count()).isZero();
    assertThat(credencialRepository.count()).isZero();
  }

  @Test
  @DisplayName("contador empresarial com o CNPJ do escritorio na senha (#521): 400, nada gravado"
      + " e o endereco proprio continua livre")
  void senhaComCnpjDoEscritorio() throws Exception {
    String json = corpoContador(CPF, "cnpjsenha@exemplo.com.br",
        escritorioEmpresarial("11444777000161", "campoverde"), EMPRESARIAL)
        .replace(SENHA, "escritorio 11.444.777/0001-61 campo");
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("SENHA_FRACA"))
        .andExpect(jsonPath("$.errors[0].field").value("senha"));

    assertThat(contadorRepository.count()).isZero();
    assertThat(escritorioRepository.count()).isZero();
    // O mesmo slug segue disponivel com uma senha valida.
    assertThat(cadastrar(corpoContador(CPF, "cnpjsenha@exemplo.com.br",
        escritorioEmpresarial("11444777000161", "campoverde"), EMPRESARIAL))
        .getResponse().getStatus()).isEqualTo(201);
  }

  @Test
  @DisplayName("CPF com digito verificador invalido: 400")
  void cpfInvalido() throws Exception {
    mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("PRODUTOR", "52998224724", "dv@fazenda.com.br", SENHA)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", Matchers.hasItem("cpfCnpj")));
  }

  @Test
  @DisplayName("CPF/CNPJ e obrigatorio")
  void documentoObrigatorio() throws Exception {
    mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("PRODUTOR", "", "semdoc@fazenda.com.br", SENHA)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", Matchers.hasItem("cpfCnpj")));
  }

  @Test
  @DisplayName("sem senha: 400 no campo senha")
  void semSenha() throws Exception {
    mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("PRODUTOR", CPF, "semsenha@fazenda.com.br", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", Matchers.hasItem("senha")));
  }

  @Test
  @DisplayName("papel inexistente (ex.: ADMIN) e recusado — nao ha elevacao pelo cadastro")
  void papelInexistente() throws Exception {
    mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("ADMIN", CPF, "admin@fazenda.com.br", SENHA)))
        .andExpect(status().isBadRequest());
    assertThat(credencialRepository.count()).isZero();
  }

  @Test
  @DisplayName("a restricao (papel, cpf_cnpj) existe no banco e e reconhecida como de cadastro")
  void restricaoUnicaDoDocumento() throws Exception {
    assertThat(cadastrar(corpo("PRODUTOR", CPF, "primeiro@fazenda.com.br", SENHA))
        .getResponse().getStatus()).isEqualTo(201);
    Produtor outro = produtorRepository.save(
        Produtor.builder().nomeCompleto("Outro").cpfCnpj("11144477735").build());

    // Grava direto, pulando a checagem previa do service: so a restricao pode barrar.
    CredencialUsuario duplicada = CredencialUsuario.builder()
        .email("segundo@fazenda.com.br").senhaHash("{bcrypt}x").papel(PapelUsuario.PRODUTOR)
        .cpfCnpj(CPF).produtorId(outro.getId()).build();

    assertThatThrownBy(() -> credencialRepository.saveAndFlush(duplicada))
        .isInstanceOfSatisfying(DataIntegrityViolationException.class,
            e -> assertThat(CadastroService.violouUnicidadeDeCadastro(e)).isTrue());
  }

  @Test
  @DisplayName("dois cadastros simultaneos do mesmo CPF: exatamente um vence")
  void cadastrosSimultaneos() throws Exception {
    int concorrentes = 4;
    ExecutorService pool = Executors.newFixedThreadPool(concorrentes);
    CountDownLatch largada = new CountDownLatch(1);
    try {
      List<Future<Boolean>> resultados = new ArrayList<>();
      for (int i = 0; i < concorrentes; i++) {
        String email = "simultaneo" + i + "@fazenda.com.br";
        resultados.add(pool.submit(() -> {
          largada.await();
          try {
            cadastroService.cadastrar(
                new CadastroRequest(PapelUsuario.PRODUTOR, "Fazenda", CPF, email, SENHA));
            return true;
          } catch (CadastroConflitoException e) {
            return false;
          }
        }));
      }
      largada.countDown();
      long vencedores = 0;
      for (Future<Boolean> resultado : resultados) {
        if (resultado.get(30, TimeUnit.SECONDS)) {
          vencedores++;
        }
      }
      assertThat(vencedores).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    // O perdedor nao deixa produtor orfao: a gravacao do produtor e da credencial e atomica.
    assertThat(credencialRepository.count()).isEqualTo(1);
    assertThat(produtorRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("so o POST e publico: GET no mesmo caminho exige identidade")
  void soPostEhPublico() throws Exception {
    mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/auth/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
  }
}
