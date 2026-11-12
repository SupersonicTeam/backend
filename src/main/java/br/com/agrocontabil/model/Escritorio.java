package br.com.agrocontabil.model;

import br.com.agrocontabil.exception.EmpresarialExigeCnpjException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Escritório de contabilidade (issue #228): dono da carteira de clientes, dos catálogos e dos
 * convites. Os contadores são membros dele, cada um com uma {@link FuncaoEscritorio}; na v1 um
 * contador pertence a um escritório só.
 *
 * <p>Todo contador que se cadastra sozinho ganha um escritório, do qual é administrador: pessoal
 * (com o nome dele) ou empresarial (com nome, CNPJ, telefone, endereço postal e endereço próprio),
 * conforme o {@link #tipo} escolhido (issue #519). Pode trocá-lo pelo de uma equipe aceitando um
 * convite enquanto o dele estiver vazio.
 *
 * <p><strong>Invariante (#519):</strong> escritório empresarial tem CNPJ ({@link
 * #semCnpjExigido}), conferida ao gravar só quando o tipo ou o CNPJ muda, como a do
 * {@link Produtor} (#298): um empresarial antigo sem CNPJ (com equipe, pelo changeSet 0062)
 * continua valendo até alguém mexer no tipo ou no CNPJ. Quem grava confere no service antes, porque
 * no flush do commit o erro sairia 500.
 *
 * <p>O {@link #slug} é o endereço próprio do escritório, {@code {slug}.agroconia.com.br} (issue
 * #464): marca e porta de entrada, nunca fronteira de autorização. Pode ser dado pessoal (o nome do
 * escritório unipessoal é o do contador): fica fora do {@code toString} e de todo log.
 *
 * <p><strong>CNPJ verificado (#520):</strong> o CNPJ só tem o dígito verificador conferido no
 * cadastro e no {@code PATCH}. A titularidade é conferida fora do sistema, e o endereço próprio só
 * é servido depois disso ({@link #cnpjVerificado}). Trocar o CNPJ limpa a verificação; trocar o
 * nome, o telefone ou o endereço, não.
 */
@Entity
@Table(name = "escritorio",
    uniqueConstraints = @UniqueConstraint(name = "uq_escritorio_slug", columnNames = "slug"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Escritorio {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "nome", nullable = false)
  private String nome;

  /** CNPJ do escritório, só dígitos; opcional (contador autônomo não tem). */
  @Column(name = "cnpj", length = 14)
  private String cnpj;

  /**
   * Pessoal (um usuário só) ou empresarial (pode ter equipe e endereço próprio), escolhido no
   * cadastro do contador (issue #519). Gravado, nunca derivado do CNPJ; sem escolha, pessoal.
   */
  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
  private TipoEscritorio tipo = TipoEscritorio.PESSOAL;

  /** Telefone do escritório, só dígitos (issue #519); cifrado em repouso como os outros. */
  @ToString.Exclude
  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "telefone", length = 100)
  private String telefone;

  /** Endereço postal (issue #519); nulo nos escritórios sem ele. */
  @ToString.Exclude
  @Embedded
  private EnderecoPostal endereco;

  /** Rótulo do subdomínio, normalizado; nulo = o escritório usa o {@code www} (issue #464). */
  @ToString.Exclude
  @Column(name = "slug", length = 32)
  private String slug;

  /** Última definição, troca ou remoção do slug: a próxima gravação respeita o intervalo. */
  @Column(name = "slug_alterado_em")
  private OffsetDateTime slugAlteradoEm;

  /**
   * Quando o CNPJ foi verificado (issue #520); nulo = não verificado. Só o escritório verificado
   * tem o endereço próprio servido. Trocar o CNPJ limpa a verificação ({@link #onUpdate}). Grave
   * pelo {@link #verificarCnpj}, nunca pelo setter.
   */
  @Column(name = "cnpj_verificado_em")
  private OffsetDateTime cnpjVerificadoEm;

  /** Como o CNPJ foi verificado; nulo = não verificado (issue #520). */
  @Enumerated(EnumType.STRING)
  @Column(name = "cnpj_verificacao_metodo", length = 20, columnDefinition = "VARCHAR(20)")
  private MetodoVerificacaoCnpj cnpjVerificacaoMetodo;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  /**
   * Tipo do último estado gravado (lido ou escrito): o {@code @PreUpdate} só confere o CNPJ quando
   * o tipo muda em relação a ele.
   */
  @Transient
  @ToString.Exclude
  private TipoEscritorio tipoCarregado;

  /** CNPJ do último estado gravado: o {@code @PreUpdate} só confere quando ele muda. */
  @Transient
  @ToString.Exclude
  private String cnpjCarregado;

  /**
   * Regra única da invariante (#519): escritório empresarial sem CNPJ.
   *
   * @param tipo tipo do escritório
   * @param cnpj CNPJ, ou nulo
   * @return {@code true} se é empresarial e não tem CNPJ
   */
  public static boolean semCnpjExigido(TipoEscritorio tipo, String cnpj) {
    return tipo == TipoEscritorio.EMPRESARIAL && (cnpj == null || cnpj.isBlank());
  }

  /**
   * Tipos de escritório cujo administrador não entra em outra equipe pelo convite (#519, decisão
   * do Murillo de 2026-11-11). Para prender também o do pessoal, acrescente-o aqui.
   */
  private static final Set<TipoEscritorio> ADMINISTRADOR_PRESO =
      Collections.unmodifiableSet(EnumSet.of(TipoEscritorio.EMPRESARIAL));

  /**
   * Regra única (#519): o administrador deste escritório não entra em outra equipe pelo convite,
   * mesmo sozinho e sem clientes; o escritório não é apagado.
   *
   * @param tipo   tipo do escritório atual
   * @param funcao função do contador nele
   * @return {@code true} se ele não pode trocar de equipe por ser o administrador
   */
  public static boolean administradorNaoTrocaDeEquipe(TipoEscritorio tipo,
      FuncaoEscritorio funcao) {
    return funcao == FuncaoEscritorio.ADMINISTRADOR && tipo != null
        && ADMINISTRADOR_PRESO.contains(tipo);
  }

  /**
   * Guarda o estado gravado: ao ler e depois de cada gravação. Sem a renovação depois do flush, um
   * segundo flush na mesma transação compararia com o estado lido, não com o último gravado (gravar
   * o CNPJ num empresarial antigo e apagá-lo de novo passaria sem conferência).
   */
  @PostLoad
  @PostPersist
  @PostUpdate
  protected void onLoad() {
    tipoCarregado = tipo;
    cnpjCarregado = cnpj;
  }

  /**
   * O CNPJ gravado foi verificado (issue #520)? Só então o endereço próprio é servido.
   *
   * @return {@code true} se verificado
   */
  public boolean cnpjVerificado() {
    return cnpjVerificadoEm != null;
  }

  /**
   * Marca o CNPJ atual como verificado (issue #520). Quem chama conferiu a titularidade do CNPJ
   * gravado agora; se ele mudar depois, a verificação cai sozinha.
   *
   * @param metodo   como foi conferido
   * @param instante quando
   * @throws IllegalStateException escritório sem CNPJ
   */
  public void verificarCnpj(MetodoVerificacaoCnpj metodo, OffsetDateTime instante) {
    if (cnpj == null || cnpj.isBlank()) {
      throw new IllegalStateException("Escritorio sem CNPJ nao pode ser verificado");
    }
    cnpjVerificadoEm = Objects.requireNonNull(instante, "instante");
    cnpjVerificacaoMetodo = Objects.requireNonNull(metodo, "metodo");
  }

  /** Tira a verificação do CNPJ (issue #520): o endereço próprio deixa de ser servido. */
  public void revogarVerificacaoCnpj() {
    cnpjVerificadoEm = null;
    cnpjVerificacaoMetodo = null;
  }

  @PrePersist
  protected void onCreate() {
    conferirCnpj();
    createdAt = OffsetDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    if (tipo != tipoCarregado || !Objects.equals(cnpj, cnpjCarregado)) {
      conferirCnpj();
    }
    if (!Objects.equals(cnpj, cnpjCarregado)) {
      // Verificado foi o CNPJ antigo (#520). Limpa no mesmo UPDATE: assim trocar o CNPJ nunca
      // esbarra no indice unico do CNPJ verificado, e o PATCH nao vira oraculo de CNPJ alheio.
      revogarVerificacaoCnpj();
    }
    updatedAt = OffsetDateTime.now();
  }

  private void conferirCnpj() {
    if (semCnpjExigido(tipo, cnpj)) {
      throw new EmpresarialExigeCnpjException();
    }
  }
}
