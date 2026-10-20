package br.com.agrocontabil.service.auditoria;

import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.RecursoAuditado;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um método público de service como operação crítica auditada (issue #149, RNF012).
 *
 * <p>O {@link AuditoriaAspect} grava um {@code RegistroAuditoria} a cada chamada, com sucesso ou
 * falha. Vale só na chamada pelo proxy (de controller ou de outro service): a autoinvocação dentro
 * da mesma classe não passa pelo aspecto.
 *
 * <p>De onde vem cada campo:
 * <ul>
 *   <li><strong>recurso afetado:</strong> o primeiro argumento {@code UUID}; sem ele, o
 *       {@code recursoAuditado()} do retorno ({@code ResultadoAuditavel});</li>
 *   <li><strong>estado antes:</strong> lido do banco pelo id, antes da operação (nota, guia,
 *       apuração); <strong>estado depois:</strong> o {@code status()} do retorno;</li>
 *   <li><strong>produtor afetado:</strong> o próprio produtor autenticado, ou o
 *       {@code produtorAuditado()} do retorno quando quem age é o contador.</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditado {

  /** O que a operação faz. */
  AcaoAuditada acao();

  /** Sobre que tipo de recurso. */
  RecursoAuditado recurso();
}
