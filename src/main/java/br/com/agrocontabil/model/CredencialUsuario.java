package br.com.agrocontabil.model;

import br.com.agrocontabil.security.PapelUsuario;
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
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Credencial de acesso de um usuário — e-mail e hash da senha (issue #205).
 *
 * <p>Fica em tabela própria, e não em {@code produtores}/{@code contadores}, por dois motivos:
 *
 * <ul>
 *   <li>o login precisa resolver um e-mail para <em>uma</em> identidade sem saber de antemão se é
 *       produtor ou contador — com a credencial nas duas tabelas, o e-mail poderia existir nas duas
 *       e o login ficaria ambíguo;
 *   <li>o agregado do {@link Produtor} é carregado na emissão de NF-e, no dashboard e na apuração:
 *       manter o hash da senha fora dele evita que ele viaje por esses caminhos (e por algum
 *       {@code toString} em log).
 * </ul>
 *
 * <p>Exatamente uma das duas referências é preenchida, conforme o {@link #papel} — garantido no
 * banco por CHECK ({@code ck_credencial_identidade}, changeSet 0031). As referências são ids, sem
 * relacionamento JPA: o login carrega a identidade pelo repositório dela, e nenhum caminho de
 * leitura do produtor passa a arrastar a credencial.
 *
 * <p><strong>{@link #cpfCnpj}</strong> repete o documento da identidade (só dígitos) para que a
 * restrição {@code uk_credencial_papel_cpf_cnpj} barre, no banco, dois cadastros simultâneos do
 * mesmo documento — {@code produtores.cpf_cnpj} não tem unicidade.
 */
@Entity
@Table(name = "credencial_usuario")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class CredencialUsuario {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /**
   * E-mail de login, já normalizado (NFKC, sem espaços nas pontas, minúsculas). Único. Dado
   * pessoal: fora do {@code toString}.
   */
  @ToString.Exclude
  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "email", nullable = false, length = 512)
  private String email;

  /**
   * Hash da senha no formato do {@code DelegatingPasswordEncoder} ({@code {bcrypt}$2a$12$...}).
   *
   * <p>Fora do {@code toString}: um log de depuração da credencial não pode carregar o hash.
   */
  @ToString.Exclude
  @Column(name = "senha_hash", nullable = false, length = 100)
  private String senhaHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "papel", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
  private PapelUsuario papel;

  /** Documento da identidade, só dígitos (11 = CPF, 14 = CNPJ). */
  @ToString.Exclude
  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "cpf_cnpj", nullable = false, length = 100)
  private String cpfCnpj;

  /** Produtor dono desta credencial; preenchido só quando o papel é PRODUTOR. */
  @Column(name = "produtor_id")
  private UUID produtorId;

  /** Contador dono desta credencial; preenchido só quando o papel é CONTADOR. */
  @Column(name = "contador_id")
  private UUID contadorId;

  /** Credencial desativada não autentica, mesmo com a senha certa. */
  @Builder.Default
  @Column(name = "ativo", nullable = false)
  private boolean ativo = true;

  /**
   * Versão das sessões desta credencial: todo token carrega a versão em que foi emitido, e só
   * vale enquanto ela for a atual.
   *
   * <p>É o mecanismo de revogação em massa — troca de senha, redefinição e "sair de todos os
   * dispositivos" incrementam o número. Um contador, e não um instante: o {@code iat} do JWT tem
   * resolução de segundo, e comparar com um timestamp deixaria passar o token emitido no mesmo
   * segundo da revogação (ou recusaria o emitido logo depois dela).
   */
  @Builder.Default
  @Column(name = "versao_sessao", nullable = false)
  private int versaoSessao = 0;

  /** Senhas erradas seguidas desde o último login com sucesso (issue #208). */
  @Builder.Default
  @Column(name = "falhas_login", nullable = false)
  private int falhasLogin = 0;

  /** Até quando o login está travado por excesso de falhas; nulo = não travado (issue #208). */
  @Column(name = "bloqueada_ate")
  private OffsetDateTime bloqueadaAte;

  /**
   * Quando o titular aceitou os termos de uso no cadastro (issue #519); nulo = aceite não
   * registrado (conta anterior, ou cadastro sem o campo enquanto o aceite não é exigido).
   */
  @Column(name = "termos_aceitos_em")
  private OffsetDateTime termosAceitosEm;

  /** Versão dos termos aceita ({@code app.termos.versao}); nula junto com {@link #termosAceitosEm}. */
  @Column(name = "termos_versao", length = 40)
  private String termosVersao;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    OffsetDateTime agora = OffsetDateTime.now();
    createdAt = agora;
    updatedAt = agora;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  /**
   * Id da identidade dona desta credencial — do produtor ou do contador, conforme o papel.
   *
   * @return id do produtor ou do contador
   */
  public UUID usuarioId() {
    return papel == PapelUsuario.CONTADOR ? contadorId : produtorId;
  }
}
