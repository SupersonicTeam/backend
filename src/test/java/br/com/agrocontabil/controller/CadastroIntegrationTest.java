package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.exception.CadastroConflitoException;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
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
  @DisplayName("contador: 201 e contador criado, sem carteira")
  void cadastraContador() throws Exception {
    mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .content(corpo("CONTADOR", CNPJ, "escritorio@campocerto.com.br", SENHA)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.papel").value("CONTADOR"));

    CredencialUsuario credencial =
        credencialRepository.findByEmail("escritorio@campocerto.com.br").orElseThrow();
    assertThat(contadorRepository.findById(credencial.getContadorId())).isPresent();
    assertThat(credencial.getProdutorId()).isNull();
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
