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
 * <p>Anotação de <strong>classe</strong>: a regra cruza o papel e o tipo de conta com os outros
 * campos. No {@code PRODUTOR}, {@code escritorio}, {@code crc} e {@code tipoConta} são recusados.
 * No {@code CONTADOR}, o documento da pessoa é só CPF ({@link DocumentoDoContador}), o
 * {@code EMPRESARIAL} exige o {@code escritorio} e o {@code PESSOAL} (ou sem {@code tipoConta}) o
 * recusa. Cada violação vai no campo que o cliente precisa corrigir, para o
 * {@code GlobalExceptionHandler} devolvê-la como {@code VALIDATION_ERROR} com {@code field}.
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
