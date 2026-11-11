package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.exception.CadastroConflitoException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.util.ArrayList;
import java.util.List;
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
@DisplayName("POST /api/auth/cadastro — conta com credencial (#205)")
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
  @DisplayName("contador sem os campos da #519 (tela antiga): escritorio com o nome dele, sem CNPJ")
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
    assertThat(contador.getCrc()).isNull();
    assertThat(contador.getTelefone()).isNull();
    Escritorio escritorio = escritorioRepository.findById(contador.getEscritorioId()).orElseThrow();
    assertThat(escritorio.getNome()).isEqualTo("Fazenda Boa Vista");
    assertThat(escritorio.getCnpj()).isNull();
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

  @Test
  @DisplayName("contador com escritorio, CRC e telefone (#519): tudo gravado; telefone cifrado")
  void cadastraContadorComEscritorio() throws Exception {
    MvcResult resultado = cadastrar(corpoContador(CPF, "patricia@exemplo.com.br",
        "{\"nome\":\" Contabilidade Rural Oeste \",\"cnpj\":\"48315276000195\"}",
        ",\"crc\":\"PR-012345/O\",\"telefone\":\"45999990000\""));
    assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
    assertThat(resultado.getResponse().getContentAsString())
        .doesNotContain("48315276000195", "PR-012345", "45999990000", CPF);

    CredencialUsuario credencial =
        credencialRepository.findByEmail("patricia@exemplo.com.br").orElseThrow();
    Contador contador = contadorRepository.findById(credencial.getContadorId()).orElseThrow();
    assertThat(contador.getNomeCompleto()).isEqualTo("Patrícia Moreira");
    assertThat(contador.getCpfCnpj()).isEqualTo(CPF);
    assertThat(contador.getCrc()).isEqualTo("PR-012345/O");
    assertThat(contador.getTelefone()).isEqualTo("45999990000");
    assertThat(contador.getFuncao()).isEqualTo(FuncaoEscritorio.ADMINISTRADOR);
    Escritorio escritorio = escritorioRepository.findById(contador.getEscritorioId()).orElseThrow();
    assertThat(escritorio.getNome()).isEqualTo("Contabilidade Rural Oeste");
    assertThat(escritorio.getCnpj()).isEqualTo("48315276000195");
    // O telefone ja era cifrado em repouso (#147): o cadastro nao abriu excecao.
    assertThat(jdbc.queryForObject("SELECT telefone FROM contadores WHERE id = ?", String.class,
        contador.getId())).startsWith("v1:");
  }

  @Test
  @DisplayName("contador (#519): CNPJ do escritorio, CRC e telefone vazios = nao informados")
  void contadorComCamposVazios() throws Exception {
    MvcResult resultado = cadastrar(corpoContador(CPF, "autonoma@exemplo.com.br",
        "{\"nome\":\"Patrícia Moreira\",\"cnpj\":\"\"}", ",\"crc\":\"\",\"telefone\":\"\""));
    assertThat(resultado.getResponse().getStatus()).isEqualTo(201);

    Contador contador = contadorRepository.findById(credencialRepository
        .findByEmail("autonoma@exemplo.com.br").orElseThrow().getContadorId()).orElseThrow();
    assertThat(contador.getCrc()).isNull();
    assertThat(contador.getTelefone()).isNull();
    assertThat(escritorioRepository.findById(contador.getEscritorioId()).orElseThrow().getCnpj())
        .isNull();
  }

  @Test
  @DisplayName("contador com CNPJ no proprio documento (#519): 400 no cpfCnpj, nada gravado")
  void contadorComCnpjNoDocumento() throws Exception {
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpoContador(CNPJ, "cnpj@exemplo.com.br",
                "{\"nome\":\"Contabilidade Rural Oeste\"}", "")))
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
  @DisplayName("CNPJ do escritorio com DV errado ou fora de 14 digitos e nome vazio (#519): 400")
  void escritorioInvalido() throws Exception {
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpoContador(CPF, "dv@exemplo.com.br",
                "{\"nome\":\"Escritório\",\"cnpj\":\"48315276000194\"}", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("escritorio.cnpj"));
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpoContador(CPF, "curto@exemplo.com.br",
                "{\"nome\":\"Escritório\",\"cnpj\":\"48.315.276/0001-95\"}", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", Matchers.everyItem(
            Matchers.is("escritorio.cnpj"))));
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpoContador(CPF, "semnome@exemplo.com.br", "{\"nome\":\" \"}", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("escritorio.nome"))
        .andExpect(jsonPath("$.errors[0].message").value("Informe o nome do escritório."));

    assertThat(contadorRepository.count()).isZero();
    assertThat(escritorioRepository.count()).isZero();
  }

  @Test
  @DisplayName("produtor com escritorio ou CRC (#519): 400 no campo, nada gravado")
  void produtorComCamposDoContador() throws Exception {
    String base = corpo("PRODUTOR", CPF, "produtor@fazenda.com.br", SENHA).strip();
    String comEscritorio = base.substring(0, base.length() - 1)
        + ",\"escritorio\":{\"nome\":\"Contabilidade\",\"cnpj\":\"48315276000195\"}}";
    String comCrc = base.substring(0, base.length() - 1) + ",\"crc\":\"PR-012345/O\"}";

    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(comEscritorio))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("escritorio"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(comCrc))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("crc"));

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
