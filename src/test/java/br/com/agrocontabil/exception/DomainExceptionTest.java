package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DomainExceptionTest {

  /** Subclasse concreta mínima para exercitar a base. */
  private static final class SampleException extends DomainException {
    SampleException() {
      super(HttpStatus.CONFLICT, "Conflito de estado",
          List.of(ErrorDetail.of(ErrorCode.NOTA_NAO_EDITAVEL, "Nota em XML_GERADO")));
    }
  }

  /** Subclasse que recebe a lista de fora, para testar a cópia defensiva. */
  private static final class FromListException extends DomainException {
    FromListException(List<ErrorDetail> errors) {
      super(HttpStatus.UNPROCESSABLE_ENTITY, "msg", errors);
    }
  }

  @Test
  void shouldCarryStatusMessageAndErrors() {
    DomainException ex = new SampleException();
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ex.getMessage()).isEqualTo("Conflito de estado");
    assertThat(ex.getErrors()).hasSize(1);
    assertThat(ex.getErrors().get(0).code()).isEqualTo("NOTA_NAO_EDITAVEL");
  }

  @Test
  void shouldMakeDefensiveCopyDecouplingFromCallerList() {
    List<ErrorDetail> mutavel = new ArrayList<>();
    mutavel.add(ErrorDetail.of(ErrorCode.VALIDATION_ERROR, "campo", "msg"));

    DomainException ex = new FromListException(mutavel);

    // mutar a lista original depois NÃO pode afetar getErrors()
    mutavel.add(ErrorDetail.of(ErrorCode.INTERNAL_ERROR, "outro"));
    mutavel.clear();

    assertThat(ex.getErrors()).hasSize(1);
    assertThat(ex.getErrors().get(0).field()).isEqualTo("campo");
  }
}
