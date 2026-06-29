package br.com.agrocontabil.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.repository.NcmAgropecuarioRepository;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("NcmValidator — constraint @ValidNcm")
class NcmValidatorTest {

  @Mock
  private NcmAgropecuarioRepository repository;

  private NcmValidator validator;
  private ConstraintValidatorContext context;

  @BeforeEach
  void setUp() {
    validator = new NcmValidator(repository);
    context = Mockito.mock(ConstraintValidatorContext.class);
  }

  @Test
  @DisplayName("valido quando o NCM existe na tabela de referencia")
  void ncmExistente() {
    when(repository.existsByCodigo("12010090")).thenReturn(true);
    assertThat(validator.isValid("12010090", context)).isTrue();
  }

  @Test
  @DisplayName("invalido quando o NCM nao existe na tabela de referencia")
  void ncmInexistente() {
    when(repository.existsByCodigo("99999999")).thenReturn(false);
    assertThat(validator.isValid("99999999", context)).isFalse();
  }

  @Test
  @DisplayName("valido para null/blank (obrigatoriedade fica a cargo de @NotBlank)")
  void nullOuBlank() {
    assertThat(validator.isValid(null, context)).isTrue();
    assertThat(validator.isValid("   ", context)).isTrue();
  }
}
