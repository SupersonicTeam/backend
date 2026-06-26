package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Inconsistência de validação registrada para uma {@link NotaFiscal} (issue #22).
 *
 * <p>Cada registro espelha um {@link br.com.agrocontabil.common.ErrorDetail} da camada A
 * (contrato de erro estruturado) encontrado ao validar/gerar o XML da NF-e. Persistir as
 * inconsistências dá ao produtor e ao contador um histórico das tentativas de emissão e
 * permite ao frontend destacar exatamente os campos problemáticos.
 *
 * <p>Mapeada para a tabela {@code nota_fiscal_validacao}. Os registros são
 * <strong>imutáveis</strong> (somente criação) — cada validação acumula um novo lote, sem
 * sobrescrever os anteriores.
 *
 * <h3>Agrupamento por tentativa</h3>
 * Todos os registros gerados numa mesma execução de validação compartilham o mesmo
 * {@link #loteValidacao} e o mesmo {@link #validadoEm}. O frontend agrupa o histórico por
 * {@code loteValidacao} para exibir cada tentativa de emissão separadamente.
 */
@Entity
@Table(name = "nota_fiscal_validacao")
@Data
@EqualsAndHashCode(exclude = "notaFiscal")
@ToString(exclude = "notaFiscal")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotaFiscalValidacao {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /**
   * Nota fiscal à qual esta inconsistência pertence.
   *
   * <p>FK gerenciada pelo Liquibase (changeset 0006); o Hibernate roda com
   * {@code ddl-auto: validate} e nunca cria constraints (padrão do projeto).
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "nota_fiscal_id", nullable = false,
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private NotaFiscal notaFiscal;

  /**
   * Identificador do lote/tentativa de validação. Compartilhado por todas as
   * inconsistências registradas numa mesma execução, permitindo agrupar o histórico.
   */
  @Column(name = "lote_validacao", nullable = false, updatable = false)
  private UUID loteValidacao;

  /** Código estável do erro (ver {@link br.com.agrocontabil.common.ErrorCode}). */
  @Column(name = "code", length = 60, nullable = false)
  private String code;

  /** Campo afetado (ex.: {@code item[1].ncm}); nulo para erros de nível de requisição. */
  @Column(name = "field", length = 120)
  private String field;

  /** Mensagem amigável ao usuário descrevendo a inconsistência. */
  @Column(name = "message", length = 500, nullable = false)
  private String message;

  /** Sugestão de correção; opcional. */
  @Column(name = "suggestion", length = 500)
  private String suggestion;

  /** Valor recebido que foi rejeitado; opcional. */
  @Column(name = "rejected_value", length = 500)
  private String rejectedValue;

  /** Valor esperado; opcional. */
  @Column(name = "expected_value", length = 500)
  private String expectedValue;

  /** Instante (com fuso) em que a validação que originou este registro foi executada. */
  @Column(name = "validado_em", nullable = false, updatable = false)
  private OffsetDateTime validadoEm;

  @PrePersist
  protected void onCreate() {
    if (validadoEm == null) {
      validadoEm = OffsetDateTime.now();
    }
  }
}
