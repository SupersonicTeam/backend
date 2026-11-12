package br.com.agrocontabil.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * O documento da pessoa do contador é um CPF (issue #519), no cadastro pelo convite da equipe.
 *
 * <p>A regra é a do {@link DocumentoDoContador}; no cadastro direto, que tem o papel no corpo, ela
 * vem pelo {@link CadastroCoerenteComPapel}. Nulo e branco são válidos (a obrigatoriedade é do
 * {@code @NotBlank}).
 */
@Documented
@Constraint(validatedBy = CpfDoContadorValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface CpfDoContador {

  String message() default DocumentoDoContador.MENSAGEM_EQUIPE;

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
