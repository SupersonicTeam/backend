package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * NCM de um item de anexo da LC 214/2025 (issue #272), {@link Permissao#PERMITIDO} ou
 * {@link Permissao#VEDADO} — a tabela oficial veda, por exemplo, no Anexo VII os produtos do
 * Anexo I. Com vigência.
 *
 * <p>Mapeada para {@code classificacao_tributaria_anexo_ncm} (changeSet {@code 0048}). Somente
 * leitura em runtime.
 */
@Entity
@Table(name = "classificacao_tributaria_anexo_ncm")
@IdClass(ClassificacaoTributariaAnexoNcm.Chave.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificacaoTributariaAnexoNcm {

  /** Se o NCM entra ou sai do item. */
  public enum Permissao { PERMITIDO, VEDADO }

  @Id
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "item_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private ClassificacaoTributariaAnexoItem item;

  @Id
  @Column(name = "ncm", length = 8, nullable = false)
  private String ncm;

  @Id
  @Enumerated(EnumType.STRING)
  @Column(name = "permissao", length = 10, nullable = false)
  private Permissao permissao;

  @Id
  @Column(name = "data_inicio", nullable = false)
  private LocalDate dataInicio;

  @Column(name = "data_fim")
  private LocalDate dataFim;

  /** Chave composta (item, NCM, permissão, início da vigência). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Chave implements Serializable {
    private Integer item;
    private String ncm;
    private Permissao permissao;
    private LocalDate dataInicio;
  }
}
