package br.com.agrocontabil.validation;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.CadastroEquipeRequest;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroRequest.EscritorioDoCadastro;
import br.com.agrocontabil.security.PapelUsuario;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regra do documento do contador e coerência do cadastro com o papel (issue #519).
 *
 * <p>Se a regra "só CPF" for revertida ({@link DocumentoDoContador}), os testes de CNPJ no
 * contador saem junto.
 */
@DisplayName("Cadastro por papel e documento do contador (#519)")
class DocumentoDoContadorTest {

  private static final String CPF = "52998224725";
  private static final String CNPJ = "11222333000181";
  private static final String SENHA = "cavalo bateria grampo correto";

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static CadastroRequest cadastro(PapelUsuario papel, String documento,
      EscritorioDoCadastro escritorio, String crc, String telefone) {
    return new CadastroRequest(papel, "Fulano", documento, "fulano@exemplo.com.br", SENHA,
        escritorio, crc, telefone);
  }

  /** Campo de cada violação, para conferir onde o erro aparece para o cliente. */
  private static <T> Set<String> campos(T alvo) {
    return validator.validate(alvo).stream()
        .map(v -> v.getPropertyPath().toString())
        .collect(Collectors.toSet());
  }

  @Test
  @DisplayName("so o CNPJ (14 digitos) e recusado; formato e DV ficam com as anotacoes do campo")
  void recusado() {
    assertThat(DocumentoDoContador.recusado(CNPJ)).isTrue();
    assertThat(DocumentoDoContador.recusado(CPF)).isFalse();
    assertThat(DocumentoDoContador.recusado(null)).isFalse();
    assertThat(DocumentoDoContador.recusado("")).isFalse();
    assertThat(DocumentoDoContador.recusado("1122233300018a")).isFalse();
    assertThat(DocumentoDoContador.recusado("123")).isFalse();
  }

  @Test
  @DisplayName("contador com CPF, escritorio, CRC e telefone: valido")
  void contadorCompletoValido() {
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF,
        new EscritorioDoCadastro("Contabilidade Rural Oeste", "48315276000195"), "PR-012345/O",
        "45999990000"))).isEmpty();
  }

  @Test
  @DisplayName("contador sem os campos novos (tela antiga): valido")
  void contadorSemCamposNovosValido() {
    assertThat(campos(new CadastroRequest(PapelUsuario.CONTADOR, "Fulano", CPF,
        "fulano@exemplo.com.br", SENHA))).isEmpty();
  }

  @Test
  @DisplayName("contador com CNPJ no documento: uma violacao so, no cpfCnpj, com o campo certo")
  void contadorComCnpj() {
    Set<ConstraintViolation<CadastroRequest>> violacoes =
        validator.validate(cadastro(PapelUsuario.CONTADOR, CNPJ, null, null, null));

    assertThat(violacoes).hasSize(1);
    ConstraintViolation<CadastroRequest> violacao = violacoes.iterator().next();
    assertThat(violacao.getPropertyPath()).hasToString("cpfCnpj");
    assertThat(violacao.getMessage()).isEqualTo(DocumentoDoContador.MENSAGEM_CADASTRO)
        .contains("'escritorio.cnpj'");
  }

  @Test
  @DisplayName("produtor segue com CPF ou CNPJ, e com telefone")
  void produtorComCnpj() {
    assertThat(campos(cadastro(PapelUsuario.PRODUTOR, CNPJ, null, null, "6533330001"))).isEmpty();
    assertThat(campos(cadastro(PapelUsuario.PRODUTOR, CPF, null, "", null))).isEmpty();
  }

  @Test
  @DisplayName("produtor com escritorio ou CRC: violacao em cada campo")
  void produtorComCamposDoContador() {
    assertThat(campos(cadastro(PapelUsuario.PRODUTOR, CPF,
        new EscritorioDoCadastro("Escritorio", ""), "PR-1", null)))
        .containsExactlyInAnyOrder("escritorio", "crc");
  }

  @Test
  @DisplayName("sem papel: o @NotNull responde, a regra do papel nao julga nada")
  void semPapel() {
    assertThat(campos(cadastro(null, CNPJ, new EscritorioDoCadastro("X", ""), "PR-1", null)))
        .containsExactly("papel");
  }

  @Test
  @DisplayName("CNPJ do escritorio com DV errado, curto ou nome vazio: violacao no campo aninhado")
  void escritorioInvalido() {
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF,
        new EscritorioDoCadastro("Escritorio", "48315276000194"), null, null)))
        .containsExactly("escritorio.cnpj");
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF,
        new EscritorioDoCadastro("Escritorio", "4831527600019"), null, null)))
        .containsExactly("escritorio.cnpj");
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF,
        new EscritorioDoCadastro(" ", null), null, null)))
        .containsExactly("escritorio.nome");
  }

  @Test
  @DisplayName("telefone fora de 10 ou 11 digitos e CRC acima de 20: violacao no campo")
  void telefoneECrcInvalidos() {
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF, null, null, "999990000")))
        .containsExactly("telefone");
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF, null, null, "(45) 99999-0000")))
        .containsExactly("telefone");
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF, null, "PR-0123456789/O-123456", null)))
        .containsExactly("crc");
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF, null, "", ""))).isEmpty();
  }

  @Test
  @DisplayName("cadastro pela equipe: CNPJ recusado com a mensagem da equipe; CPF aceito")
  void cadastroPelaEquipe() {
    Set<ConstraintViolation<CadastroEquipeRequest>> violacoes =
        validator.validate(new CadastroEquipeRequest("token", "Ana", CNPJ, SENHA));

    assertThat(violacoes).hasSize(1);
    ConstraintViolation<CadastroEquipeRequest> violacao = violacoes.iterator().next();
    assertThat(violacao.getPropertyPath()).hasToString("cpfCnpj");
    assertThat(violacao.getMessage()).isEqualTo(DocumentoDoContador.MENSAGEM_EQUIPE);
    assertThat(campos(new CadastroEquipeRequest("token", "Ana", CPF, SENHA))).isEmpty();
  }
}
