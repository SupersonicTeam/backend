package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resposta de operação auditada que informa à auditoria o que mudou (issue #149).
 *
 * <p>Os records implementam {@link #status()} pelo próprio componente {@code status}; o resto tem
 * padrão nulo. Nada aqui pode ser dado pessoal: vai para um registro guardado por 5 anos.
 */
@Schema(description = "Contrato interno da auditoria: descreve o que a operação mudou (não é resposta da API)")
public interface ResultadoAuditavel {

  /**
   * Estado do recurso depois da operação (ex.: o status da nota).
   *
   * @return estado; {@code toString()} vai ao registro
   */
  Object status();

  /**
   * Estado do recurso antes da operação, quando a auditoria não o lê pelo id dos argumentos (ex.:
   * o endereço do escritório, #464, que vem do membro autenticado, não do path).
   *
   * @return estado anterior, ou nulo
   */
  default Object statusAnterior() {
    return null;
  }

  /**
   * Recurso criado pela operação, quando o id não vem nos argumentos (criação).
   *
   * @return id do recurso, ou nulo
   */
  default UUID recursoAuditado() {
    return null;
  }

  /**
   * Produtor dono do recurso — o que permite ao contador achar o registro.
   *
   * @return id do produtor, ou nulo
   */
  default UUID produtorAuditado() {
    return null;
  }
}
