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
 * Convite do escritório para o produtor entrar na carteira (issue #177).
 *
 * <p>O vínculo só nasce do aceite do produtor (consentimento, LGPD art. 7º): o escritório informa
 * um e-mail, nunca um CPF/CNPJ — quem soubesse o documento puxaria o cliente. O e-mail vai
 * cifrado (#147) e, como a cifra é determinística, o produtor encontra o convite por igualdade com
 * o e-mail da própria credencial, inclusive quando se cadastra depois do convite.
 */
@Entity
@Table(name = "convite_carteira")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ConviteCarteira {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /** Escritório que convidou (issue #228). */
  @Column(name = "escritorio_id", nullable = false, updatable = false)
  private UUID escritorioId;

  /** Membro que mandou o convite: vira o responsável pelo cliente no aceite. */
  @Column(name = "convidado_por")
  private UUID convidadoPor;

  /** E-mail normalizado do convidado. */
  @ToString.Exclude
  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "email", nullable = false, updatable = false, length = 512)
  private String email;

  @Enumerated(EnumType.STRING)
  @Column(name = "situacao", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
  private SituacaoConvite situacao;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private OffsetDateTime criadoEm;

  @Column(name = "expira_em", nullable = false, updatable = false)
  private OffsetDateTime expiraEm;

  @Column(name = "respondido_em")
  private OffsetDateTime respondidoEm;

  /** Produtor que respondeu; nulo enquanto pendente. */
  @Column(name = "produtor_id")
  private UUID produtorId;
}
