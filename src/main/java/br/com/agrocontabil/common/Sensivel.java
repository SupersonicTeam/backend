package br.com.agrocontabil.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um campo de request cujo valor nunca pode voltar ao cliente nem ir para log — senha,
 * token, código de verificação (issue #205).
 *
 * <p>O {@code GlobalExceptionHandler} omite o {@code rejectedValue} de todo campo marcado quando a
 * validação falha. É a fonte de verdade; a checagem pelo nome do campo ("senha", "token"...) fica
 * só como rede de segurança para quem esquecer a anotação.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Sensivel {
}
