package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.RegistroAuditoriaResponse;
import br.com.agrocontabil.model.RegistroAuditoria;

/** Converte {@link RegistroAuditoria} no DTO de resposta (issue #149). Só métodos estáticos. */
public final class RegistroAuditoriaMapper {

  private RegistroAuditoriaMapper() {}

  /**
   * Converte o registro para a resposta.
   *
   * @param registro entidade
   * @return o DTO
   */
  public static RegistroAuditoriaResponse toResponse(RegistroAuditoria registro) {
    return new RegistroAuditoriaResponse(
        registro.getId(),
        registro.getOcorridoEm(),
        registro.getAcao(),
        registro.getRecurso(),
        registro.getRecursoId(),
        registro.getUsuarioId(),
        registro.getPapel(),
        registro.isSucesso(),
        registro.getCodigoErro(),
        registro.getEstadoAntes(),
        registro.getEstadoDepois());
  }
}
