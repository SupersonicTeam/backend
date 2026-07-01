package br.com.agrocontabil.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.repository.CfopReferenciaRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link CfopValidator} (issue #20, RN008).
 *
 * <p>O validador só considera válidos os CFOPs presentes na tabela de referência e marcados
 * como aplicáveis ao produtor rural. CFOPs inexistentes ou inaplicáveis ao produtor rural são
 * rejeitados (HTTP 400). Convenção Bean Validation: {@code null}/blank retornam {@code true}.
 */
@ExtendWith(MockitoExtension.class)
class CfopValidatorTest {

  @Mock
  private CfopReferenciaRepository repository;

  private CfopValidator validator;

  @BeforeEach
  void setUp() {
    validator = new CfopValidator(repository);
  }

  @Test
  @DisplayName("CFOP existente e aplicavel ao produtor rural deve ser valido")
  void cfopAplicavelDeveSerValido() {
    when(repository.findCodigosAplicaveisProdutorRural()).thenReturn(List.of("5101", "6101"));
    validator.carregarCache();

    assertThat(validator.isValid("6101", null)).isTrue();
  }

  @Test
  @DisplayName("CFOP inexistente deve ser invalido")
  void cfopInexistenteDeveSerInvalido() {
    when(repository.findCodigosAplicaveisProdutorRural()).thenReturn(List.of("5101", "6101"));
    validator.carregarCache();

    assertThat(validator.isValid("9999", null)).isFalse();
  }

  @Test
  @DisplayName("CFOP existente mas inaplicavel ao produtor rural deve ser invalido")
  void cfopInaplicavelDeveSerInvalido() {
    // 1556 (compra para industrializacao) existe na tabela, mas nao e marcado como
    // aplicavel ao produtor rural — nao entra no cache de codigos aplicaveis.
    when(repository.findCodigosAplicaveisProdutorRural()).thenReturn(List.of("5101", "6101"));
    validator.carregarCache();

    assertThat(validator.isValid("1556", null)).isFalse();
  }

  @Test
  @DisplayName("CFOP null deve ser valido (delegado ao @NotBlank)")
  void cfopNullDeveSerValido() {
    lenient().when(repository.findCodigosAplicaveisProdutorRural()).thenReturn(List.of("5101"));
    validator.carregarCache();

    assertThat(validator.isValid(null, null)).isTrue();
  }

  @Test
  @DisplayName("Quando o cache nao carrega (infra indisponivel) o validador faz fail-open")
  void cacheNaoCarregadoDeveFazerFailOpen() {
    assertThat(validator.isValid("9999", null)).isTrue();
  }

  @Test
  @DisplayName("Tabela de referencia vazia deve resultar em fail-open (nao provisionada)")
  void cacheVazioDeveFazerFailOpen() {
    when(repository.findCodigosAplicaveisProdutorRural()).thenReturn(List.of());
    validator.carregarCache();

    assertThat(validator.isValid("9999", null)).isTrue();
  }
}
