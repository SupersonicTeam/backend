package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.AlertaTributarioResponse;
import br.com.agrocontabil.model.AlertaTributario;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Entidade {@link AlertaTributario} → DTO (issue #140). */
@Component
public class AlertaTributarioMapper {

  /**
   * Converte o alerta.
   *
   * @param alerta alerta ativo
   * @param hoje   data de referência para os dias restantes
   */
  public AlertaTributarioResponse toResponse(AlertaTributario alerta, LocalDate hoje) {
    return new AlertaTributarioResponse(
        alerta.getId(),
        alerta.getTipo().name(),
        alerta.getReferenciaId(),
        alerta.getDescricao(),
        alerta.getDataLimite(),
        ChronoUnit.DAYS.between(hoje, alerta.getDataLimite()));
  }
}
