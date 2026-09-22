package br.com.agrocontabil.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Chave primária composta de {@link AliquotaIbsUf}: {@code (uf, dataInicio)} (Onda 2, issue #85).
 *
 * <p>Chave natural — impede duas vigências da mesma UF com o mesmo início sem depender de gerador
 * de ID. Usada via {@code @IdClass}; precisa de construtor padrão, {@code equals} e
 * {@code hashCode} (contrato JPA).
 */
public class AliquotaIbsUfId implements Serializable {

  private static final long serialVersionUID = 1L;

  private String uf;
  private LocalDate dataInicio;

  /** Construtor padrão exigido pela JPA. */
  public AliquotaIbsUfId() {
  }

  /** Constrói a chave composta. */
  public AliquotaIbsUfId(String uf, LocalDate dataInicio) {
    this.uf = uf;
    this.dataInicio = dataInicio;
  }

  public String getUf() {
    return uf;
  }

  public LocalDate getDataInicio() {
    return dataInicio;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AliquotaIbsUfId other)) {
      return false;
    }
    return Objects.equals(uf, other.uf) && Objects.equals(dataInicio, other.dataInicio);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uf, dataInicio);
  }
}
