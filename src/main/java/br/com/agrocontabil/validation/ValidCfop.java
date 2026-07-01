package br.com.agrocontabil.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que o CFOP informado existe na tabela de referência e é aplicável ao produtor rural
 * (issue #20, RN008).
 *
 * <p>Aplica-se a campos {@code String}. Valores {@code null}/blank são considerados válidos —
 * a obrigatoriedade e o formato ficam a cargo de {@code @NotBlank}/{@code @Pattern}. CFOPs
 * inexistentes ou inaplicáveis ao produtor rural são rejeitados.
 *
 * <p>Violações são reportadas pelo {@code GlobalExceptionHandler} como HTTP 400
 * ({@code VALIDATION_ERROR}).
 */
@Documented
@Constraint(validatedBy = CfopValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCfop {

  String message() default
      "CFOP informado nao existe ou nao e aplicavel as operacoes do produtor rural";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
