package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Chave do uso semanal: titular e a segunda-feira da semana (issue #166). */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UsoSemanalAssistenteId implements Serializable {

  @Enumerated(EnumType.STRING)
  @Column(name = "titular_tipo", length = 20, nullable = false, columnDefinition = "VARCHAR(20)")
  private TipoTitularCota titularTipo;

  @Column(name = "titular_id", nullable = false)
  private UUID titularId;

  /** Segunda-feira da semana, em {@code America/Sao_Paulo}. */
  @Column(name = "semana_inicio", nullable = false)
  private LocalDate semanaInicio;
}
