package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * Convite para a equipe do escritório (issue #228). Como a redefinição de senha (#209), guarda só
 * o SHA-256 do token, que vai no fragmento do link; uso único, validade de 7 dias. Aceitar exige
 * estar logado (ou se cadastrar) com o e-mail convidado.
 */
@Entity
@Table(name = "convite_membro")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ConviteMembro {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "escritorio_id", nullable = false, updatable = false)
  private UUID escritorioId;

  /** E-mail normalizado do convidado. */
  @ToString.Exclude
  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "email", nullable = false, updatable = false, length = 512)
  private String email;

  @Enumerated(EnumType.STRING)
  @Column(name = "funcao", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
  private FuncaoEscritorio funcao;

  /** SHA-256 do token em hexadecimal. */
  @ToString.Exclude
  @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
  private String tokenHash;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private OffsetDateTime criadoEm;

  @Column(name = "expira_em", nullable = false, updatable = false)
  private OffsetDateTime expiraEm;

  /** Preenchido = já usado. */
  @Column(name = "usado_em")
  private OffsetDateTime usadoEm;

  @Column(name = "convidado_por")
  private UUID convidadoPor;
}
