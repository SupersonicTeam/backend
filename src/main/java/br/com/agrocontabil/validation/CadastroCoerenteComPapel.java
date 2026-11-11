package br.com.agrocontabil.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Os campos do cadastro batem com o papel (issue #519).
 *
 * <p>Anotação de <strong>classe</strong>: a regra cruza o papel com os outros campos. No
 * {@code PRODUTOR}, {@code escritorio} e {@code crc} são recusados; no {@code CONTADOR}, o
 * documento da pessoa é só CPF ({@link DocumentoDoContador}). Cada violação vai no campo que o
 * cliente precisa corrigir, para o {@code GlobalExceptionHandler} devolvê-la como
 * {@code VALIDATION_ERROR} com {@code field}.
 */
@Documented
@Constraint(validatedBy = CadastroCoerenteComPapelValidator.class)
@Target({ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CadastroCoerenteComPapel {

  String message() default "Os campos do cadastro não valem para o papel informado.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
