package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Obrigação do calendário fiscal (issue #181): modelo do sistema ou obrigação do escritório.
 *
 * <ul>
 *   <li><strong>Modelo</strong> — {@code modelo = true}, sem escritório; semeado por changeSet e
 *       só de leitura pela API;
 *   <li><strong>Versão do escritório</strong> — escritório + {@code modeloId}: vale no lugar do
 *       modelo só para aquele escritório (editar ou desativar um modelo cria uma);
 *   <li><strong>Própria</strong> — escritório sem {@code modeloId}.
 * </ul>
 *
 * <p>Vale para os produtores da carteira que casarem com {@code tipoProdutor} e
 * {@code regimes} (nulo/vazio = todos). As datas do calendário são derivadas: nenhuma ocorrência é
 * gravada.
 */
@Entity
@Table(name = "obrigacao_fiscal")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ObrigacaoFiscal {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /** Escritório dono; nulo nos modelos do sistema. */
  @Column(name = "escritorio_id", updatable = false)
  private UUID escritorioId;

  @Column(name = "modelo", nullable = false, updatable = false)
  private boolean modelo;

  /** Modelo que esta versão do escritório substitui. */
  @Column(name = "modelo_id", updatable = false)
  private UUID modeloId;

  @Column(name = "titulo", nullable = false, length = 120)
  private String titulo;

  @Column(name = "descricao", length = 500)
  private String descricao;

  @Enumerated(EnumType.STRING)
  @Column(name = "periodicidade", nullable = false, length = 10, columnDefinition = "VARCHAR(10)")
  private Periodicidade periodicidade;

  /** Dia do mês (1–31); dia inexistente no mês cai no último. */
  @Column(name = "dia", nullable = false)
  private int dia;

  /** Mês (1–12), só na anual. */
  @Column(name = "mes")
  private Integer mes;

  @Enumerated(EnumType.STRING)
  @Column(name = "ajuste_fim_de_semana", nullable = false, length = 10,
      columnDefinition = "VARCHAR(10)")
  @Builder.Default
  private AjusteFimDeSemana ajusteFimDeSemana = AjusteFimDeSemana.NENHUM;

  /** Nulo = vale para PF e PJ. */
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_produtor", length = 20, columnDefinition = "VARCHAR(20)")
  private TipoProdutor tipoProdutor;

  /** Vazio = vale para todos os regimes. */
  @Convert(converter = RegimesTributariosConverter.class)
  @Column(name = "regimes_tributarios", length = 60)
  @Builder.Default
  private Set<RegimeTributario> regimes = EnumSet.noneOf(RegimeTributario.class);

  /** Fundamento legal, em texto livre (ex. "LC 214/2025, art. ..."). */
  @Column(name = "origem", length = 200)
  private String origem;

  @Column(name = "ativa", nullable = false)
  @Builder.Default
  private boolean ativa = true;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  /**
   * Vale para o produtor? Tipo e regime casam (nulo/vazio = todos).
   *
   * @param tipo   tipo do produtor
   * @param regime regime do produtor
   * @return {@code true} se a obrigação se aplica
   */
  public boolean aplicaA(TipoProdutor tipo, RegimeTributario regime) {
    boolean tipoCasa = tipoProdutor == null || tipoProdutor == tipo;
    boolean regimeCasa = regimes == null || regimes.isEmpty() || regimes.contains(regime);
    return tipoCasa && regimeCasa;
  }
}
