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
 * Valida que o CFOP informado está cadastrado na tabela de CFOPs aplicáveis ao
 * produtor rural.
 *
 * <p>{@code null}/blank são considerados válidos — combine com {@code @NotBlank}.
 * A validação de formato (4 dígitos) é feita por {@code @Pattern}. A compatibilidade
 * com o tipo de operação é validada separadamente como regra de negócio
 * ({@code CfopOperacaoValidator}).
 */
@Documented
@Constraint(validatedBy = CfopValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCfop {

  String message() default "CFOP nao aplicavel ao produtor rural";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
