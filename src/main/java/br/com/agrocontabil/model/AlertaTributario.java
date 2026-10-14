package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Alerta tributário proativo do produtor (issue #140, RN010): algo vence ou fecha em menos de 5
 * dias. Gerado pelo {@code AlertaTributarioJob} e lido pela tela e pela Ceres.
 *
 * <p>{@code descricao} é montada pelo backend com o tipo e os números do recurso (tipo e valor da
 * guia, número e série da nota, mês da apuração) — <strong>nunca</strong> as observações livres do
 * contador nem dado pessoal: o texto pode chegar a um provedor de modelo pela Ceres.
 *
 * <p>Única por {@code (tipo, referenciaId, dataLimite)}: o job pode rodar de novo no mesmo dia (ou
 * em duas réplicas, se o lock falhar) sem duplicar.
 */
@Entity
@Table(name = "alerta_tributario")
@Data
@EqualsAndHashCode(exclude = "produtor")
@ToString(exclude = "produtor")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertaTributario {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  // NO_CONSTRAINT: a FK real e do Liquibase (changeSet 0028), padrao do projeto.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "produtor_id", nullable = false,
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private Produtor produtor;

  @Enumerated(EnumType.STRING)
  @Column(name = "tipo", length = 30, nullable = false, columnDefinition = "VARCHAR(30)")
  private TipoAlertaTributario tipo;

  /** Guia, nota ou apuração a que o alerta se refere — sempre do mesmo produtor. */
  @Column(name = "referencia_id", nullable = false)
  private UUID referenciaId;

  /** Último dia para agir (vencimento, fechamento ou fim do prazo de cancelamento). */
  @Column(name = "data_limite", nullable = false)
  private LocalDate dataLimite;

  /** Texto curto montado pelo backend, sem dado pessoal. */
  @Column(name = "descricao", length = 160, nullable = false)
  private String descricao;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false, columnDefinition = "VARCHAR(20)")
  private StatusAlertaTributario status;

  @Column(name = "criado_em", nullable = false)
  private OffsetDateTime criadoEm;

  @Column(name = "resolvido_em")
  private OffsetDateTime resolvidoEm;
}
