package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * Uso da Ceres de um titular numa semana (issue #166). Só o service da cota escreve aqui, e sempre
 * por {@code UPDATE} condicional no repositório — nunca lendo, somando em memória e salvando, o
 * que perderia atualizações de perguntas simultâneas.
 */
@Entity
@Table(name = "assistente_uso_semanal")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsoSemanalAssistente implements Persistable<UsoSemanalAssistenteId> {

  @EmbeddedId
  private UsoSemanalAssistenteId id;

  /**
   * Linha nova vai ao banco como {@code INSERT}, nunca como {@code merge}: com a chave atribuída,
   * o {@code save} do Spring Data faria {@code merge}, e numa corrida entre duas perguntas o
   * segundo {@code merge} sobrescreveria com zero a reserva que a primeira acabou de gravar. Com
   * {@code INSERT}, a segunda recebe violação de chave e segue para o {@code UPDATE} condicional.
   */
  @Transient
  @Builder.Default
  private boolean nova = true;

  @Override
  public boolean isNew() {
    return nova;
  }

  @PostLoad
  @PostPersist
  void marcarPersistida() {
    this.nova = false;
  }

  /** Teto das perguntas em andamento, ainda não acertado. */
  @Column(name = "creditos_reservados", nullable = false)
  private long creditosReservados;

  /** Créditos já consumidos na semana. */
  @Column(name = "creditos_consumidos", nullable = false)
  private long creditosConsumidos;

  @Column(name = "tokens_entrada", nullable = false)
  private long tokensEntrada;

  @Column(name = "tokens_saida", nullable = false)
  private long tokensSaida;

  @Column(name = "perguntas", nullable = false)
  private int perguntas;

  @Column(name = "atualizado_em", nullable = false)
  private OffsetDateTime atualizadoEm;
}
