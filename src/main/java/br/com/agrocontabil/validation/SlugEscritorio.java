package br.com.agrocontabil.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Forma do slug do escritório (issue #464), pela regra do {@code FormatoSlug}: o valor é
 * normalizado (sem espaços nas pontas, em minúsculas) antes da conferência. Nulo é válido: a
 * obrigatoriedade é do {@code @NotNull}.
 *
 * <p>O {@code GlobalExceptionHandler} responde a violação com o código {@code SLUG_INVALIDO}, não
 * com o {@code VALIDATION_ERROR} genérico. Nome reservado não é forma: o serviço o recusa com
 * {@code SLUG_INDISPONIVEL}.
 */
@Documented
@Constraint(validatedBy = SlugEscritorioValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SlugEscritorio {

  /** Mensagem padrão. */
  String message() default "Use de 3 a 32 caracteres: letras minúsculas sem acento, números e "
      + "hífen, começando e terminando com letra ou número, sem dois hífens seguidos e não só "
      + "números.";

  /** Grupos. */
  Class<?>[] groups() default {};

  /** Payload. */
  Class<? extends Payload>[] payload() default {};
}
