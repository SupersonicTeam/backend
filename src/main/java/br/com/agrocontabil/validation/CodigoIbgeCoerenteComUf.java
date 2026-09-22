package br.com.agrocontabil.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que o código IBGE do município pertence à UF informada no mesmo endereço (issue #88).
 *
 * <p>Anotação de <strong>classe</strong>: a regra cruza dois campos do endereço. Quando qualquer um
 * dos dois está ausente a validação passa — o endereço é opcional no rascunho, e a
 * obrigatoriedade para emissão é regra do {@code FiscalRulesValidator} (422).
 */
@Documented
@Constraint(validatedBy = CodigoIbgeCoerenteComUfValidator.class)
@Target({ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodigoIbgeCoerenteComUf {

  String message() default "codigoMunicipioIbge nao pertence a UF informada";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
