package br.com.agrocontabil.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.repository.RuralProducerCfopRepository;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CfopValidator — constraint @ValidCfop")
class CfopValidatorTest {

  @Mock
  private RuralProducerCfopRepository repository;

  private CfopValidator validator;
  private ConstraintValidatorContext context;

  @BeforeEach
  void setUp() {
    validator = new CfopValidator(repository);
    context = Mockito.mock(ConstraintValidatorContext.class);
  }

  @Test
  @DisplayName("valido quando o CFOP existe na tabela de referencia")
  void cfopExistente() {
    when(repository.existsByCodigo("5101")).thenReturn(true);
    assertThat(validator.isValid("5101", context)).isTrue();
  }

  @Test
  @DisplayName("invalido quando o CFOP nao existe na tabela de referencia")
  void cfopInexistente() {
    when(repository.existsByCodigo("9999")).thenReturn(false);
    assertThat(validator.isValid("9999", context)).isFalse();
  }

  @Test
  @DisplayName("valido para null/blank (obrigatoriedade fica a cargo de @NotBlank)")
  void nullOuBlank() {
    assertThat(validator.isValid(null, context)).isTrue();
    assertThat(validator.isValid("   ", context)).isTrue();
  }
}
