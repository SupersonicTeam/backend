package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Uma ação da revisão da nota pelo contador (issue #178), no padrão de
 * {@link NotaFiscalValidacao}: o histórico só cresce. Guarda o id e o papel de quem agiu, nunca
 * nome nem documento.
 */
@Entity
@Table(name = "nota_fiscal_revisao")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class NotaFiscalRevisao {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "nota_fiscal_id", nullable = false, updatable = false)
  private UUID notaFiscalId;

  @Enumerated(EnumType.STRING)
  @Column(name = "acao", nullable = false, updatable = false, length = 20,
      columnDefinition = "VARCHAR(20)")
  private AcaoRevisao acao;

  /** Motivo da devolução (só em {@code DEVOLVIDA}). */
  @Column(name = "motivo", updatable = false, length = 500)
  private String motivo;

  /** Produtor ou contador que agiu; nulo na caducidade (o sistema). */
  @Column(name = "usuario_id", updatable = false)
  private UUID usuarioId;

  @Column(name = "papel", updatable = false, length = 20)
  private String papel;

  @Column(name = "ocorrido_em", nullable = false, updatable = false)
  private OffsetDateTime ocorridoEm;
}
