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
import lombok.Setter;
import lombok.ToString;

/**
 * Arquivo anexado a uma pendência (issue #180), guardado no banco. O {@code mime} é o conferido
 * pelos bytes iniciais, nunca o declarado pelo cliente. As listagens leem só os metadados
 * (projeção no repositório); o conteúdo sai apenas no download, e só de anexo {@code LIMPO}
 * (antivírus, #227).
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

  /** O arquivo; {@code null} só em {@code INFECTADO}, que descarta o conteúdo (#227). */
  @ToString.Exclude
  @Setter
  @Column(name = "conteudo", length = TAMANHO_MAXIMO)
  private byte[] conteudo;

  /** Produtor que anexou. */
  @Column(name = "enviado_por", updatable = false)
  private UUID enviadoPor;

  @Column(name = "enviado_em", nullable = false, updatable = false)
  private OffsetDateTime enviadoEm;

  /** Resultado do antivírus (#227); só {@code LIMPO} pode ser baixado. */
  @Setter
  @Enumerated(EnumType.STRING)
  @Column(name = "situacao", nullable = false, length = 20)
  private SituacaoAnexo situacao;

  /** Nome da assinatura achada pelo antivírus, em {@code INFECTADO}. */
  @Setter
  @Column(name = "assinatura_virus", length = 200)
  private String assinaturaVirus;

  /**
   * O envio ainda não moveu a pendência: o veredito {@code LIMPO} a leva de {@code PENDENTE} a
   * {@code ENVIADA}. {@code false} nos anexos de antes da #227, que já a moveram no envio.
   */
  @Column(name = "libera_pendencia", nullable = false, updatable = false)
  private boolean liberaPendencia;

  /** Última tentativa de verificação (a definitiva, fora de {@code EM_VERIFICACAO}). */
  @Setter
  @Column(name = "verificado_em")
  private OffsetDateTime verificadoEm;
}
