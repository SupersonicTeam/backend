package br.com.agrocontabil.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.repository.NcmReferenciaRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link NcmValidator} (issue #20, RN008).
 *
 * <p>O validador carrega os códigos de NCM curados em cache ({@code Set}) e valida a
 * existência do código informado. Convenção Bean Validation: {@code null}/blank retornam
 * {@code true} (o {@code @NotBlank}/{@code @Pattern} já cuidam disso).
 */
@ExtendWith(MockitoExtension.class)
class NcmValidatorTest {

  @Mock
  private NcmReferenciaRepository repository;

  private NcmValidator validator;

  @BeforeEach
  void setUp() {
    validator = new NcmValidator(repository);
  }

  @Test
  @DisplayName("NCM existente na tabela de referencia deve ser valido")
  void ncmExistenteDeveSerValido() {
    when(repository.findAllCodigos()).thenReturn(List.of("12010090", "10059010"));
    validator.carregarCache();

    assertThat(validator.isValid("12010090", null)).isTrue();
  }

  @Test
  @DisplayName("NCM inexistente na tabela de referencia deve ser invalido")
  void ncmInexistenteDeveSerInvalido() {
    when(repository.findAllCodigos()).thenReturn(List.of("12010090"));
    validator.carregarCache();

    assertThat(validator.isValid("99999999", null)).isFalse();
  }

  @Test
  @DisplayName("NCM null deve ser valido (delegado ao @NotBlank)")
  void ncmNullDeveSerValido() {
    lenient().when(repository.findAllCodigos()).thenReturn(List.of("12010090"));
    validator.carregarCache();

    assertThat(validator.isValid(null, null)).isTrue();
  }

  @Test
  @DisplayName("NCM em branco deve ser valido (delegado ao @NotBlank)")
  void ncmBlankDeveSerValido() {
    lenient().when(repository.findAllCodigos()).thenReturn(List.of("12010090"));
    validator.carregarCache();

    assertThat(validator.isValid("   ", null)).isTrue();
  }

  @Test
  @DisplayName("Quando o cache nao carrega (infra indisponivel) o validador faz fail-open")
  void cacheNaoCarregadoDeveFazerFailOpen() {
    // Sem chamar carregarCache(): simula contexto sem JPA (ex.: @WebMvcTest) onde o
    // @PostConstruct nao rodou ou o repositorio nao esta disponivel.
    assertThat(validator.isValid("99999999", null)).isTrue();
  }

  @Test
  @DisplayName("Tabela de referencia vazia deve resultar em fail-open (nao provisionada)")
  void cacheVazioDeveFazerFailOpen() {
    // Uma tabela de referencia curada nunca e legitimamente vazia: vazio sinaliza que nao foi
    // provisionada (ex.: @WebMvcTest com repositorio mockado). Tratamos como fail-open.
    when(repository.findAllCodigos()).thenReturn(List.of());
    validator.carregarCache();

    assertThat(validator.isValid("99999999", null)).isTrue();
  }
}
