package br.com.agrocontabil.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que o CPF (11 digitos) ou CNPJ (14 digitos) informado tem digito verificador
 * valido (modulo 11) — issue #18.
 *
 * <p>Aplica-se a campos {@code String}. Valores {@code null}/blank sao considerados validos —
 * a obrigatoriedade e o formato ficam a cargo de {@code @NotBlank}/{@code @Pattern}. A
 * validacao do digito verificador so ocorre quando ha um valor com formato plausivel.
 *
 * <p>Violacoes sao reportadas pelo {@code GlobalExceptionHandler} como HTTP 400
 * ({@code VALIDATION_ERROR}).
 */
@Documented
@Constraint(validatedBy = CpfCnpjValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCpfCnpj {

  String message() default "CPF/CNPJ informado tem digito verificador invalido";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
