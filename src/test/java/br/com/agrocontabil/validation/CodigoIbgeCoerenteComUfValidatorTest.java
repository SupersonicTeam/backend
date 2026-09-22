package br.com.agrocontabil.validation;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.NotaFiscalRequest.EnderecoDestinatarioRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes de {@link CodigoIbgeCoerenteComUf} (issue #88).
 *
 * <p>A regra cruza dois campos do endereço, por isso é uma constraint de classe. Campos ausentes
 * são válidos: o endereço é opcional no rascunho e a emissão é barrada pelo
 * {@code FiscalRulesValidator}.
 */
class CodigoIbgeCoerenteComUfValidatorTest {

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

  @Test
  @DisplayName("codigo IBGE da propria UF e valido")
  void codigoCoerenteEValido() {
    assertThat(validar(endereco("5103403", "MT"))).isEmpty();
  }

  @Test
  @DisplayName("codigo IBGE de outra UF e rejeitado no campo codigoMunicipioIbge")
  void codigoDeOutraUfEInvalido() {
    Set<ConstraintViolation<EnderecoDestinatarioRequest>> violacoes =
        validar(endereco("3550308", "MT"));

    assertThat(violacoes).hasSize(1);
    ConstraintViolation<EnderecoDestinatarioRequest> violacao = violacoes.iterator().next();
    assertThat(violacao.getPropertyPath()).hasToString("codigoMunicipioIbge");
    assertThat(violacao.getMessage()).contains("MT");
  }

  @Test
  @DisplayName("codigo IBGE omitido e valido (rascunho sem endereco completo)")
  void codigoOmitidoEValido() {
    assertThat(validar(endereco(null, "MT"))).isEmpty();
  }

  @Test
  @DisplayName("UF omitida e valida (nao ha com o que comparar)")
  void ufOmitidaEValida() {
    assertThat(validar(endereco("5103403", null))).isEmpty();
  }

  private Set<ConstraintViolation<EnderecoDestinatarioRequest>> validar(
      EnderecoDestinatarioRequest endereco) {
    return validator.validate(endereco);
  }

  private static EnderecoDestinatarioRequest endereco(String codigoIbge, String uf) {
    return new EnderecoDestinatarioRequest(
        "Rua das Acacias", "250", "Cuiaba", codigoIbge, uf, "78050000");
  }
}
