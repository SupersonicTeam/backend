package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Arquivo anexado a uma pendência (issue #180), guardado no banco. O {@code mime} é o conferido
 * pelos bytes iniciais, nunca o declarado pelo cliente. As listagens leem só os metadados
 * (projeção no repositório); o conteúdo sai apenas no download.
 */
@Entity
@Table(name = "pendencia_anexo")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class PendenciaAnexo {

  /** Teto de um anexo: 5 MB. */
  public static final int TAMANHO_MAXIMO = 5 * 1024 * 1024;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "pendencia_id", nullable = false, updatable = false)
  private UUID pendenciaId;

  /** Nome original, sanitizado (sem caminho nem caractere de controle, até 120). */
  @Column(name = "nome_arquivo", nullable = false, updatable = false, length = 120)
  private String nomeArquivo;

  @Column(name = "mime", nullable = false, updatable = false, length = 40)
  private String mime;

  @Column(name = "tamanho", nullable = false, updatable = false)
  private int tamanho;

  @Column(name = "sha256", nullable = false, updatable = false, length = 64)
  private String sha256;

  @ToString.Exclude
  @Column(name = "conteudo", nullable = false, updatable = false, length = TAMANHO_MAXIMO)
  private byte[] conteudo;

  /** Produtor que anexou. */
  @Column(name = "enviado_por", updatable = false)
  private UUID enviadoPor;

  @Column(name = "enviado_em", nullable = false, updatable = false)
  private OffsetDateTime enviadoEm;
}
