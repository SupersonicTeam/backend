package br.com.agrocontabil.model;

import br.com.agrocontabil.exception.RegimeIncompativelComTipoProdutorException;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "produtores")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Produtor {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "nome_completo", nullable = false)
  private String nomeCompleto;

  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "cpf_cnpj", length = 100)
  private String cpfCnpj;

  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_produtor", columnDefinition = "VARCHAR(20)")
  private TipoProdutor tipoProdutor;

  @Enumerated(EnumType.STRING)
  @Column(name = "regime_tributario", columnDefinition = "VARCHAR(20)")
  private RegimeTributario regimeTributario;

  @Enumerated(EnumType.STRING)
  @Column(name = "porte_empresa", columnDefinition = "VARCHAR(20)")
  private PorteEmpresa porteEmpresa;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "culturas", columnDefinition = "text[]")
  private String[] culturas;

  @Column(name = "faturamento_anual", precision = 15, scale = 2)
  private BigDecimal faturamentoAnual;

  @Column(name = "estado", length = 2)
  private String estado;

  /**
   * Endereço completo do emitente (Onda 2, issue #88) — alimenta {@code <enderEmit>} e o
   * {@code cMunFG} do XML da NF-e. Opcional no cadastro; obrigatório na emissão de NF-e/NFA
   * (validado pelo {@code FiscalRulesValidator}). A UF fica em {@link #estado}, fora do embutido.
   */
  @Embedded
  private EnderecoProdutor endereco;

  /**
   * Inscrição Estadual do emitente — {@code <emit><IE>} (Onda 2, issue #88).
   *
   * <p>Obrigatória para NF-e modelo 55; numa NFA de produtor rural pessoa física sem IE, o mapper
   * emite {@code ISENTO}. Não há dígito verificador nacional padronizado (cada UF tem o seu
   * algoritmo), por isso só o formato é validado — débito registrado no CLAUDE.md.
   */
  @Column(name = "inscricao_estadual", length = 14)
  private String inscricaoEstadual;

  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "email", length = 512)
  private String email;

  @Convert(converter = DadoPessoalCifradoConverter.class)
  @Column(name = "telefone", length = 100)
  private String telefone;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  /**
   * Tipo e regime como vieram do banco: o {@link #onUpdate} só confere o regime quando um dos dois
   * muda. Um cadastro antigo de pessoa física em {@code SIMPLES} (anterior à issue #298) continua
   * podendo ser gravado em outra coluna (anonimização da LGPD, por exemplo) até ser corrigido.
   */
  @Transient
  @EqualsAndHashCode.Exclude
  @ToString.Exclude
  private TipoProdutor tipoCarregado;

  @Transient
  @EqualsAndHashCode.Exclude
  @ToString.Exclude
  private RegimeTributario regimeCarregado;

  @PostLoad
  protected void onLoad() {
    tipoCarregado = tipoProdutor;
    regimeCarregado = regimeTributario;
  }

  @PrePersist
  protected void onCreate() {
    conferirRegime();
    createdAt = OffsetDateTime.now();
    updatedAt = OffsetDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    if (tipoProdutor != tipoCarregado || regimeTributario != regimeCarregado) {
      conferirRegime();
    }
    updatedAt = OffsetDateTime.now();
  }

  /**
   * Pessoa física nunca em {@code SIMPLES} nem {@code MEI} (issue #298), no cadastro e na troca de
   * tipo ou de regime.
   *
   * <p>Invariante da entidade, e não regra de um service, de propósito: nenhuma rota da API grava
   * o regime (o cadastro não o pede) e os caminhos que o gravam são os seeds; a entidade é o único
   * ponto que todos atravessam. Limite: o 422 sai inteiro quando o erro nasce no {@code persist}
   * ou num {@code flush} explícito; no flush do commit de um {@code @Transactional} o JPA o
   * embrulha no rollback e a resposta seria 500. Rota futura que edite o regime confere
   * {@link RegimeTributario#incompativel} no service antes de gravar.
   */
  private void conferirRegime() {
    if (RegimeTributario.incompativel(tipoProdutor, regimeTributario)) {
      throw new RegimeIncompativelComTipoProdutorException(regimeTributario);
    }
  }
}
