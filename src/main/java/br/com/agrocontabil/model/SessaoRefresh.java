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
import lombok.Setter;
import lombok.ToString;

/**
 * Um refresh token emitido (issue #207). O token nunca é gravado — só o SHA-256 dele.
 *
 * <p>Os tokens de um mesmo login formam uma <em>família</em>: cada renovação marca o atual como
 * usado e emite o seguinte na mesma família. Um token já usado que reaparece é cópia (roubo, ou a
 * vítima usando o que o ladrão já trocou): a família inteira é revogada. Detalhes no
 * {@code SessaoRefreshService}.
 *
 * <p>Referência à credencial por id, sem relacionamento JPA — mesmo motivo da
 * {@link CredencialUsuario}: a sessão é lida sozinha, e nada mais carrega a sessão junto.
 */
@Entity
@Table(name = "sessao_refresh")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SessaoRefresh {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "credencial_id", nullable = false, updatable = false)
  private UUID credencialId;

  /** Todos os tokens nascidos do mesmo login. */
  @Column(name = "familia_id", nullable = false, updatable = false)
  private UUID familiaId;

  /** SHA-256 do token em hexadecimal. Fora do {@code toString} por precaução. */
  @ToString.Exclude
  @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
  private String tokenHash;

  /** Versão de sessão da credencial na emissão: se a da credencial mudou, este token morreu. */
  @Column(name = "versao_sessao", nullable = false, updatable = false)
  private int versaoSessao;

  /** Início da família: limite da vida máxima da sessão, que a rotação não estende. */
  @Column(name = "familia_iniciada_em", nullable = false, updatable = false)
  private OffsetDateTime familiaIniciadaEm;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private OffsetDateTime criadoEm;

  @Column(name = "expira_em", nullable = false, updatable = false)
  private OffsetDateTime expiraEm;

  /** Quando este token foi trocado pelo seguinte. Preenchido = não pode ser usado de novo. */
  @Column(name = "usado_em")
  private OffsetDateTime usadoEm;

  /** Quando a família foi encerrada (logout, reuso detectado, credencial revogada). */
  @Column(name = "revogado_em")
  private OffsetDateTime revogadoEm;
}
