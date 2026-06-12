package br.com.agrocontabil.common.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que o NCM informado está cadastrado na tabela de NCMs agropecuários.
 *
 * <p>{@code null}/blank são considerados válidos — combine com {@code @NotBlank}
 * quando obrigatório. A validação de formato (8 dígitos) deve ser feita por
 * {@code @Pattern} separadamente.
 */
@Documented
@Constraint(validatedBy = NcmValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidNcm {

  String message() default "NCM nao cadastrado para agropecuaria";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
