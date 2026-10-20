package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Dados pessoais cifrados no banco e em claro na aplicação (issue #147, RNF004) — conferido por
 * SQL direto, por baixo do JPA.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Criptografia em repouso: CPF/CNPJ, e-mail e telefone cifrados (#147)")
class CriptografiaRepousoIntegrationTest {

  private static final String CPF = "52998224725";
  private static final String EMAIL = "titular@fazenda.com.br";

  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private CredencialUsuarioRepository credencialRepository;
  @Autowired private CadastroService cadastroService;
  @Autowired private CifragemLegadoRunner cifragemLegado;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TestDatabaseCleaner cleaner;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("produtor: banco guarda v1:..., a entidade le em claro, a busca por CPF acha")
  void produtorCifrado() {
    Produtor salvo = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Joao").cpfCnpj(CPF).email(EMAIL).telefone("(65) 99999-1234").build());

    Map<String, Object> cru = jdbc.queryForMap(
        "SELECT cpf_cnpj, email, telefone FROM produtores WHERE id = ?", salvo.getId());
    assertThat(cru.values()).allSatisfy(v -> assertThat((String) v).startsWith("v1:"));
    assertThat(cru.toString()).doesNotContain(CPF).doesNotContain("fazenda").doesNotContain("9999");

    Produtor lido = produtorRepository.findById(salvo.getId()).orElseThrow();
    assertThat(lido.getCpfCnpj()).isEqualTo(CPF);
    assertThat(lido.getEmail()).isEqualTo(EMAIL);
    // O Hibernate cifra tambem o parametro da consulta: busca por igualdade e IN seguem valendo.
    assertThat(produtorRepository.findByCpfCnpj(CPF)).isPresent();
    assertThat(produtorRepository.existsByCpfCnpjIn(List.of("529.982.247-25", CPF))).isTrue();
  }

  @Test
  @DisplayName("cadastro: credencial e identidade cifradas; e-mail e documento repetidos seguem 409")
  void cadastroCifrado() {
    CadastroResponse conta = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Joao", CPF, EMAIL, "cavalo bateria grampo correto"));

    Map<String, Object> credencial = jdbc.queryForMap(
        "SELECT email, cpf_cnpj FROM credencial_usuario WHERE produtor_id = ?", conta.usuarioId());
    assertThat(credencial.values()).allSatisfy(v -> assertThat((String) v).startsWith("v1:"));
    assertThat(credencialRepository.findByEmail(EMAIL)).isPresent();
    assertThat(credencialRepository.existsByPapelAndCpfCnpj(PapelUsuario.PRODUTOR, CPF)).isTrue();
  }

  @Test
  @DisplayName("contador: documento cifrado continua unico no banco")
  void contadorCifrado() {
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio", "11222333000181", "esc@campo.com.br",
        "cavalo bateria grampo correto"));

    assertThat(jdbc.queryForObject("SELECT cpf_cnpj FROM contadores", String.class))
        .startsWith("v1:");
    assertThat(contadorRepository.existsByCpfCnpj("11222333000181")).isTrue();
  }

  @Test
  @DisplayName("legado em claro: lido como esta e cifrado pelo runner da subida (idempotente)")
  void legadoCifradoNaSubida() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO produtores (id, nome_completo, cpf_cnpj, email) VALUES (?, ?, ?, ?)",
        id, "Legado", CPF, EMAIL);
    assertThat(produtorRepository.findById(id).orElseThrow().getCpfCnpj()).isEqualTo(CPF);

    assertThat(cifragemLegado.cifrarLegado()).isEqualTo(2);

    assertThat(jdbc.queryForObject("SELECT cpf_cnpj FROM produtores WHERE id = ?", String.class, id))
        .startsWith("v1:");
    assertThat(produtorRepository.findByCpfCnpj(CPF)).map(Produtor::getId).contains(id);
    assertThat(cifragemLegado.cifrarLegado()).isZero();
  }

  @Test
  @DisplayName("linha legada que colide com uma ja cifrada fica em claro, e o resto migra")
  void falhaNumaLinhaNaoParaAMigracao() {
    contadorRepository.save(Contador.builder()
        .nomeCompleto("Escritorio novo").cpfCnpj("11222333000181").build());
    UUID duplicado = UUID.randomUUID();
    jdbc.update("INSERT INTO contadores (id, nome_completo, cpf_cnpj) VALUES (?, ?, ?)",
        duplicado, "Escritorio legado", "11222333000181");
    UUID produtorLegado = UUID.randomUUID();
    jdbc.update("INSERT INTO produtores (id, nome_completo, cpf_cnpj) VALUES (?, ?, ?)",
        produtorLegado, "Legado", CPF);

    // A cifra do legado colide com a do contador novo (uk_contador_cpf_cnpj): sem lancar.
    assertThat(cifragemLegado.cifrarLegado()).isEqualTo(1);

    assertThat(jdbc.queryForObject("SELECT cpf_cnpj FROM contadores WHERE id = ?", String.class,
        duplicado)).isEqualTo("11222333000181");
    assertThat(jdbc.queryForObject("SELECT cpf_cnpj FROM produtores WHERE id = ?", String.class,
        produtorLegado)).startsWith("v1:");
  }
}
