package br.com.agrocontabil.model;

import br.com.agrocontabil.sefaz.SefazAmbiente;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Nota Fiscal eletrônica (NF-e) emitida por um {@link Produtor}.
 *
 * <p>Mapeada para a tabela {@code nota_fiscal}. O endereço do destinatário é embutido via
 * {@link EnderecoDestinatario}. Os itens da nota são gerenciados em cascata por meio de
 * {@link #itens}: use {@link #addItem(NotaFiscalItem)} e {@link #removeItem(NotaFiscalItem)}
 * para manter a consistência bidirecional.
 *
 * <p>Na Sprint 2 apenas os status {@link StatusNotaFiscal#RASCUNHO} e
 * {@link StatusNotaFiscal#XML_GERADO} são utilizados. Os demais são reservados para integração
 * futura com a SEFAZ.
 */
@Entity
@Table(name = "nota_fiscal")
@Data
@EqualsAndHashCode(exclude = {"itens", "produtor"})
@ToString(exclude = {"itens", "produtor"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotaFiscal {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  /**
   * Chave de acesso da NF-e (44 dígitos numéricos). Nula enquanto a nota está em rascunho;
   * preenchida na geração do XML.
   */
  @Column(name = "chave_acesso", length = 44, unique = true)
  private String chaveAcesso;

  /** Número sequencial da NF-e no emissor. */
  @Column(name = "numero", nullable = false)
  private Long numero;

  /** Série da NF-e. Padrão {@code 1}. */
  @Column(name = "serie", nullable = false)
  @Builder.Default
  private Integer serie = 1;

  /** Modelo do documento fiscal. {@code "55"} para NF-e. */
  @Column(name = "modelo", length = 2, nullable = false)
  @Builder.Default
  private String modelo = "55";

  /**
   * Tipo do documento fiscal: {@link TipoDocumento#NFE} (padrao) ou
   * {@link TipoDocumento#NFA} (Nota Fiscal Avulsa do produtor rural PF).
   * Diferencia NF-e de NFA no banco e roteia o fluxo de geracao.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_documento", length = 3, nullable = false,
      columnDefinition = "VARCHAR(3)")
  @Builder.Default
  private TipoDocumento tipoDocumento = TipoDocumento.NFE;

  /**
   * Origem da nota: {@link Origem#PROPRIA} (emitida pelo produtor) ou
   * {@link Origem#IMPORTADA} (XML de terceiro importado).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "origem", length = 10, nullable = false, columnDefinition = "VARCHAR(10)")
  @Builder.Default
  private Origem origem = Origem.PROPRIA;

  /**
   * Nome/razão social do emitente terceiro. Preenchido apenas em notas
   * {@link Origem#IMPORTADA}; nulo nas próprias (o emitente é o {@link #produtor}).
   */
  @Column(name = "emitente_nome", length = 255)
  private String emitenteNome;

  /** CPF/CNPJ do emitente terceiro (somente dígitos). Apenas em notas importadas. */
  @Column(name = "emitente_cpf_cnpj", length = 14)
  private String emitenteCpfCnpj;

  /** Descrição da natureza da operação (ex.: "Venda de produção rural"). */
  @Column(name = "natureza_operacao", nullable = false)
  private String naturezaOperacao;

  /** Data e hora de emissão da nota. */
  @Column(name = "data_emissao", nullable = false)
  private OffsetDateTime dataEmissao;

  /** Data e hora de saída/entrega da mercadoria. Pode ser nula. */
  @Column(name = "data_saida")
  private OffsetDateTime dataSaida;

  /** Produtor emissor da nota fiscal. */
  // NO_CONSTRAINT: a FK real é criada e gerenciada pelo Liquibase (changeset 0002);
  // o Hibernate roda com ddl-auto: validate e nunca cria constraints (padrão do projeto).
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "produtor_id", nullable = false,
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private Produtor produtor;

  /** Nome/razão social do destinatário. */
  @Column(name = "destinatario_nome", nullable = false)
  private String destinatarioNome;

  /** CPF ou CNPJ do destinatário (somente dígitos, até 14 caracteres). */
  @Column(name = "destinatario_cpf_cnpj", length = 14, nullable = false)
  private String destinatarioCpfCnpj;

  /** Inscrição Estadual do destinatário. Opcional. */
  @Column(name = "destinatario_ie", length = 20)
  private String destinatarioIe;

  /** Endereço do destinatário embutido nas colunas {@code dest_*}. */
  @Embedded
  private EnderecoDestinatario destinatarioEndereco;

  /**
   * Itens da nota fiscal. Gerenciados em cascata: inserção, atualização e remoção
   * propagam para {@code nota_fiscal_item}. Itens órfãos são removidos automaticamente.
   */
  @OneToMany(mappedBy = "notaFiscal", cascade = CascadeType.ALL, orphanRemoval = true)
  @Builder.Default
  private List<NotaFiscalItem> itens = new ArrayList<>();

  /** Somatório dos valores dos produtos, sem impostos. */
  @Column(name = "valor_total_produtos", precision = 15, scale = 2, nullable = false)
  private BigDecimal valorTotalProdutos;

  /** Valor total da nota fiscal (produtos + tributos + frete etc.). */
  @Column(name = "valor_total_nota", precision = 15, scale = 2, nullable = false)
  private BigDecimal valorTotalNota;

  /**
   * Somatório do IBS calculado de todos os itens da nota, com 2 casas decimais.
   *
   * <p>Nullable: notas emitidas antes da Sessão 4 (IBS/CBS) não possuem este valor.
   * EC 132/2023; LC 214/2025.
   */
  @Column(name = "valor_total_ibs", precision = 15, scale = 2)
  private BigDecimal valorTotalIbs;

  /**
   * Somatório da CBS calculada de todos os itens da nota, com 2 casas decimais.
   *
   * <p>Nullable: notas emitidas antes da Sessão 4 (IBS/CBS) não possuem este valor.
   * EC 132/2023; LC 214/2025.
   */
  @Column(name = "valor_total_cbs", precision = 15, scale = 2)
  private BigDecimal valorTotalCbs;

  /** Status do ciclo de vida da nota. Inicia como {@link StatusNotaFiscal#RASCUNHO}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false, columnDefinition = "VARCHAR(20)")
  @Builder.Default
  private StatusNotaFiscal status = StatusNotaFiscal.RASCUNHO;

  /**
   * Corpo completo do XML da NF-e gerado e validado pelo pipeline de geracao.
   *
   * <p>Nulo enquanto a nota esta em status {@link StatusNotaFiscal#RASCUNHO}. Preenchido
   * na transicao {@code RASCUNHO → XML_GERADO}. Imutavel apos essa transicao — o bloqueio
   * de edicao do status garante que nenhum update sobrescreve este campo.
   */
  @Column(name = "xml_gerado", columnDefinition = "TEXT")
  private String xmlGerado;

  /**
   * Versao do layout NF-e utilizado na geracao do XML (ex.: {@code "4.00"}).
   *
   * <p>Nulo enquanto a nota esta em {@link StatusNotaFiscal#RASCUNHO}.
   */
  @Column(name = "versao_layout_nfe", length = 10)
  private String versaoLayoutNfe;

  /**
   * Data e hora (com fuso horario) em que o XML da NF-e foi gerado pela primeira vez.
   *
   * <p>Nulo enquanto a nota esta em {@link StatusNotaFiscal#RASCUNHO}.
   */
  @Column(name = "data_geracao_xml")
  private OffsetDateTime dataGeracaoXml;

  /**
   * Data e hora do cancelamento da nota junto à SEFAZ. Nula até o cancelamento.
   */
  @Column(name = "data_cancelamento")
  private OffsetDateTime dataCancelamento;

  /** Justificativa do cancelamento (15–255 caracteres). Nula até o cancelamento. */
  @Column(name = "motivo_cancelamento", length = 255)
  private String motivoCancelamento;

  /** Protocolo do evento de cancelamento retornado pela SEFAZ. Nulo até o cancelamento. */
  @Column(name = "protocolo_cancelamento", length = 50)
  private String protocoloCancelamento;

  // -------------------------------------------------------------------------
  // Retorno da SEFAZ (Sprint 3 — issue #15). Preenchidos apos o envio.
  // -------------------------------------------------------------------------

  /**
   * Numero do protocolo de autorizacao retornado pela SEFAZ.
   *
   * <p>Nulo enquanto a nota nao foi autorizada. Preenchido na transicao
   * {@code XML_GERADO → AUTORIZADA}.
   */
  @Column(name = "protocolo_autorizacao", length = 20)
  private String protocoloAutorizacao;

  /** Data e hora (com fuso) da autorizacao pelo fisco. Nula se nao autorizada. */
  @Column(name = "data_autorizacao")
  private OffsetDateTime dataAutorizacao;

  /** Codigo de status (cStat) retornado pela SEFAZ no ultimo envio/consulta. */
  @Column(name = "sefaz_cstat", length = 10)
  private String sefazCstat;

  /** Descricao textual (xMotivo) do status retornado pela SEFAZ. */
  @Column(name = "sefaz_motivo", length = 255)
  private String sefazMotivo;

  /** Ambiente do envio (homologacao/producao). Nulo enquanto a nota nao foi enviada. */
  @Enumerated(EnumType.STRING)
  @Column(name = "sefaz_ambiente", length = 20)
  private SefazAmbiente sefazAmbiente;

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

  // -------------------------------------------------------------------------
  // Helpers de relacionamento bidirecional
  // -------------------------------------------------------------------------

  /**
   * Adiciona um item à nota fiscal, mantendo a consistência bidirecional.
   *
   * @param item item a ser associado a esta nota
   */
  public void addItem(NotaFiscalItem item) {
    itens.add(item);
    item.setNotaFiscal(this);
  }

  /**
   * Remove um item da nota fiscal, mantendo a consistência bidirecional.
   *
   * @param item item a ser desassociado desta nota
   */
  public void removeItem(NotaFiscalItem item) {
    itens.remove(item);
    item.setNotaFiscal(null);
  }
}
