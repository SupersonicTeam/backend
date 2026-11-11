package br.com.agrocontabil.validation;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.CadastroEquipeRequest;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroRequest.EscritorioDoCadastro;
import br.com.agrocontabil.dto.EnderecoPostalRequest;
import br.com.agrocontabil.model.TipoEscritorio;
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
 * Regra do documento do contador e coerência do cadastro com o papel e o tipo de conta (issue
 * #519).
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
    return cadastro(papel, documento, escritorio, crc, telefone, null);
  }

  private static CadastroRequest cadastro(PapelUsuario papel, String documento,
      EscritorioDoCadastro escritorio, String crc, String telefone, TipoEscritorio tipo) {
    return new CadastroRequest(papel, "Fulano", documento, "fulano@exemplo.com.br", SENHA,
        escritorio, crc, telefone, tipo, null);
  }

  private static EnderecoPostalRequest endereco() {
    return new EnderecoPostalRequest("85810000", "Rua Parana", "1200", null, "Centro", "4104808",
        "Cascavel", "PR");
  }

  private static EscritorioDoCadastro escritorio() {
    return new EscritorioDoCadastro("Contabilidade Campo Verde", "11444777000161", "4533330000",
        endereco(), "campoverde", true);
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
  @DisplayName("contador empresarial com escritorio completo, CRC e telefone: valido")
  void contadorCompletoValido() {
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF, escritorio(), "PR-012345/O",
        "45999990000", TipoEscritorio.EMPRESARIAL))).isEmpty();
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF, null, "PR-012345/O", "45999990000",
        TipoEscritorio.PESSOAL))).isEmpty();
  }

  @Test
  @DisplayName("empresarial sem escritorio, ou pessoal (inclusive sem tipo) com ele: no escritorio")
  void escritorioConformeOTipo() {
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF, null, null, null,
        TipoEscritorio.EMPRESARIAL))).containsExactly("escritorio");
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF, escritorio(), null, null,
        TipoEscritorio.PESSOAL))).containsExactly("escritorio");
    assertThat(campos(cadastro(PapelUsuario.CONTADOR, CPF, escritorio(), null, null, null)))
        .containsExactly("escritorio");
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
  @DisplayName("produtor com escritorio, CRC ou tipo de conta: violacao em cada campo")
  void produtorComCamposDoContador() {
    assertThat(campos(cadastro(PapelUsuario.PRODUTOR, CPF, escritorio(), "PR-1", null,
        TipoEscritorio.EMPRESARIAL)))
        .containsExactlyInAnyOrder("escritorio", "crc", "tipoConta");
  }

  @Test
  @DisplayName("sem papel: o @NotNull responde, a regra do papel nao julga nada")
  void semPapel() {
    assertThat(campos(cadastro(null, CNPJ, escritorio(), "PR-1", null,
        TipoEscritorio.EMPRESARIAL))).containsExactly("papel");
  }

  @Test
  @DisplayName("escritorio empresarial incompleto ou invalido: violacao no campo aninhado")
  void escritorioInvalido() {
    assertThat(campos(empresarial(new EscritorioDoCadastro("Escritorio", "11444777000160",
        "4533330000", endereco(), "campoverde", true)))).containsExactly("escritorio.cnpj");
    assertThat(campos(empresarial(new EscritorioDoCadastro("Escritorio", null, "4533330000",
        endereco(), "campoverde", true)))).containsExactly("escritorio.cnpj");
    assertThat(campos(empresarial(new EscritorioDoCadastro(" ", "11444777000161", "4533330000",
        endereco(), "campoverde", true)))).containsExactly("escritorio.nome");
    assertThat(campos(empresarial(new EscritorioDoCadastro("Escritorio", "11444777000161",
        "(45) 3333-0000", endereco(), "campoverde", true)))).containsExactly("escritorio.telefone");
    assertThat(campos(empresarial(new EscritorioDoCadastro("Escritorio", "11444777000161",
        "4533330000", null, "campoverde", true)))).containsExactly("escritorio.endereco");
    assertThat(campos(empresarial(new EscritorioDoCadastro("Escritorio", "11444777000161",
        "4533330000", endereco(), "--", true)))).containsExactly("escritorio.slug");
    assertThat(campos(empresarial(new EscritorioDoCadastro("Escritorio", "11444777000161",
        "4533330000", endereco(), "campoverde", false))))
        .containsExactly("escritorio.cienteExposicaoPublica");
  }

  @Test
  @DisplayName("endereco postal: campos obrigatorios, formatos e IBGE coerente com a UF")
  void enderecoInvalido() {
    EnderecoPostalRequest semBairro = new EnderecoPostalRequest("85810000", "Rua Parana", "1200",
        null, " ", "4104808", "Cascavel", "PR");
    assertThat(campos(semBairro)).containsExactly("bairro");
    EnderecoPostalRequest cepComMascara = new EnderecoPostalRequest("85810-000", "Rua Parana",
        "1200", null, "Centro", "4104808", "Cascavel", "PR");
    assertThat(campos(cepComMascara)).containsExactly("cep");
    EnderecoPostalRequest ufExterior = new EnderecoPostalRequest("85810000", "Rua Parana", "1200",
        null, "Centro", "4104808", "Cascavel", "EX");
    assertThat(campos(ufExterior)).contains("uf");
    EnderecoPostalRequest ibgeDeOutraUf = new EnderecoPostalRequest("85810000", "Rua Parana",
        "1200", null, "Centro", "5103403", "Cascavel", "PR");
    assertThat(campos(ibgeDeOutraUf)).containsExactly("codigoMunicipioIbge");
    // O valor do cliente nunca vira template da mensagem (cadastro publico): so a sigla de 2 letras.
    EnderecoPostalRequest ufComTemplate = new EnderecoPostalRequest("85810000", "Rua Parana",
        "1200", null, "Centro", "4104808", "Cascavel",
        "{jakarta.validation.constraints.NotNull.message}");
    assertThat(validator.validate(ufComTemplate))
        .filteredOn(v -> v.getPropertyPath().toString().equals("codigoMunicipioIbge"))
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("'codigoMunicipioIbge' não pertence à UF informada");
  }

  @Test
  @DisplayName("aceite dos termos: false e recusado; true e ausente passam (a ausencia e do service)")
  void aceiteDosTermos() {
    CadastroRequest recusado = new CadastroRequest(PapelUsuario.PRODUTOR, "Fulano", CPF,
        "fulano@exemplo.com.br", SENHA, null, null, null, null, false);
    assertThat(campos(recusado)).containsExactly("aceiteTermos");
    CadastroRequest aceito = new CadastroRequest(PapelUsuario.PRODUTOR, "Fulano", CPF,
        "fulano@exemplo.com.br", SENHA, null, null, null, null, true);
    assertThat(campos(aceito)).isEmpty();
    assertThat(campos(new CadastroEquipeRequest("token", "Ana", CPF, SENHA, null, null, false)))
        .containsExactly("aceiteTermos");
  }

  private static CadastroRequest empresarial(EscritorioDoCadastro escritorio) {
    return cadastro(PapelUsuario.CONTADOR, CPF, escritorio, null, null,
        TipoEscritorio.EMPRESARIAL);
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
    assertThat(campos(new CadastroEquipeRequest("token", "Ana", CPF, SENHA, "PR-1",
        "4599", true))).containsExactly("telefone");
  }
}
