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
 * Aviso ao produtor por WhatsApp (issue #182). Na v1 só o registro: o canal real fica atrás da
 * porta {@code EnvioNotificacao}, e o status é sempre {@code REGISTRADA}. Imutável depois de
 * gravada; sai pela retenção de 1 ano, pela exclusão do produtor (LGPD) ou com o escritório.
 */
@Entity
@Table(name = "notificacao")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Notificacao {

  /** Teto do texto gravado (a lista de itens cabe com folga; a mensagem livre tem 500). */
  public static final int TEXTO_MAXIMO = 4000;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "produtor_id", nullable = false, updatable = false)
  private UUID produtorId;

  /** Escritório do membro que disparou; nulo quando foi o produtor ou o sistema. */
  @Column(name = "escritorio_id", updatable = false)
  private UUID escritorioId;

  /** Produtor ou contador que disparou; nulo no aviso automático. */
  @Column(name = "disparado_por", updatable = false)
  private UUID disparadoPor;

  /** Papel de quem disparou ({@code PRODUTOR}/{@code CONTADOR}); nulo no aviso automático. */
  @Column(name = "papel", updatable = false, length = 20)
  private String papel;

  @Enumerated(EnumType.STRING)
  @Column(name = "tipo", nullable = false, updatable = false, length = 30)
  private TipoNotificacao tipo;

  @Enumerated(EnumType.STRING)
  @Column(name = "canal", nullable = false, updatable = false, length = 20)
  private CanalNotificacao canal;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, updatable = false, length = 20)
  private StatusNotificacao status;

  @ToString.Exclude
  @Column(name = "texto", nullable = false, updatable = false, length = TEXTO_MAXIMO)
  private String texto;

  @Column(name = "quantidade_itens", nullable = false, updatable = false)
  private int quantidadeItens;

  @Column(name = "urgentes", nullable = false, updatable = false)
  private int urgentes;

  /** Só no aviso automático: {@code <item>:<data>}, único por produtor (idempotência). */
  @Column(name = "chave_item", updatable = false, length = 120)
  private String chaveItem;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private OffsetDateTime criadoEm;
}
