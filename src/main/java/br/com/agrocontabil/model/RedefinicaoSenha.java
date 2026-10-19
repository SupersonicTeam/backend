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
 * Pedido de redefinição de senha (issue #209). O token nunca é gravado — só o SHA-256 dele, como
 * no refresh: quem ler a tabela não redefine a senha de ninguém.
 */
@Entity
@Table(name = "redefinicao_senha")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class RedefinicaoSenha {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "credencial_id", nullable = false, updatable = false)
  private UUID credencialId;

  /** SHA-256 do token em hexadecimal. */
  @ToString.Exclude
  @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
  private String tokenHash;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private OffsetDateTime criadoEm;

  @Column(name = "expira_em", nullable = false, updatable = false)
  private OffsetDateTime expiraEm;

  /** Preenchido = já usado; o mesmo link nunca redefine duas vezes. */
  @Column(name = "usado_em")
  private OffsetDateTime usadoEm;
}
