package br.com.agrocontabil.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;

/**
 * Valida que o valor anotado e um CPF estruturalmente valido (modulo 11).
 *
 * <p>Um valor {@code null} e considerado valido — combine com {@code @NotBlank}
 * quando o field for obrigatorio. A validacao remove formatacao (pontos e traco)
 * antes de checar os digitos verificadores.
 */
@Documented
@Constraint(validatedBy = CpfValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Cpf {

  String message() default "CPF invalido";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
