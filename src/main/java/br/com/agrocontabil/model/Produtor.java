package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = OffsetDateTime.now();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}
