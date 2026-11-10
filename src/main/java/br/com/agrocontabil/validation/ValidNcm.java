package br.com.agrocontabil.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que o NCM informado existe na tabela de referência do agronegócio (issue #20, RN008).
 *
 * <p>Aplica-se a campos {@code String}. Valores {@code null}/blank são considerados válidos —
 * a obrigatoriedade e o formato ficam a cargo de {@code @NotBlank}/{@code @Pattern}. A
 * validação de existência só ocorre quando há um valor com formato plausível.
 *
 * <p>Violações são reportadas pelo {@code GlobalExceptionHandler} como HTTP 400
 * ({@code VALIDATION_ERROR}).
 */
@Documented
@Constraint(validatedBy = NcmValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidNcm {

  String message() default "NCM informado não existe na tabela de referência do agronegócio";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
