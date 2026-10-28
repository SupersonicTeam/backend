package br.com.agrocontabil.model;

import br.com.agrocontabil.common.NormalizacaoBusca;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
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
// NAO declarar uniqueConstraints de (produtor, numero, serie, modelo) aqui: o changeset 0009
// REMOVEU a unique total e a substituiu por um INDICE UNICO PARCIAL (WHERE origem='PROPRIA'),
// porque notas IMPORTADAS de terceiros podem colidir em numero/serie (numero e do emitente).
// Uma @UniqueConstraint global (todas as origens) via ddl-auto=create-drop rejeitaria importadas
// validas nos testes e divergiria do schema real. A unicidade parcial vive no Liquibase (0009) e,
// no perfil de teste, no schema-nota-fiscal-test.sql. A chave de acesso e unica por produtor e
// origem (changeSet 0045, issue #223), nao no sistema inteiro: o emitente (PROPRIA) e o
// destinatario (IMPORTADA) tem a mesma NF-e, cada um na sua conta.
@Table(name = "nota_fiscal", uniqueConstraints = @UniqueConstraint(
    name = "uk_nota_fiscal_produtor_chave_origem",
    columnNames = {"produtor_id", "chave_acesso", "origem"}))
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
  @Column(name = "chave_acesso", length = 44)
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

  /**
   * Somatório do crédito de IBS aproveitável de todos os itens elegíveis da nota
   * (Sprint 4 — issue Agrocontabil#30, RN003), com 2 casas decimais.
   *
   * <p>Preenchido apenas em notas {@link OrigemNotaFiscal#IMPORTADA} (entrada); {@code 0.00}
   * quando nenhum item é elegível. Nulo em notas próprias e em importadas anteriores à
   * Sprint 4. EC 132/2023, art. 156-A, § 1.º, VIII; LC 214/2025, art. 47.
   */
  @Column(name = "valor_total_credito_ibs", precision = 15, scale = 2)
  private BigDecimal valorTotalCreditoIbs;

  /**
   * Somatório do crédito de CBS aproveitável de todos os itens elegíveis da nota
   * (Sprint 4 — issue Agrocontabil#30, RN003), com 2 casas decimais.
   *
   * <p>Preenchido apenas em notas {@link OrigemNotaFiscal#IMPORTADA} (entrada); {@code 0.00}
   * quando nenhum item é elegível. Nulo em notas próprias e em importadas anteriores à
   * Sprint 4. EC 132/2023, art. 156-A, § 1.º, VIII; LC 214/2025, art. 47.
   */
  @Column(name = "valor_total_credito_cbs", precision = 15, scale = 2)
  private BigDecimal valorTotalCreditoCbs;

  /** Status do ciclo de vida da nota. Inicia como {@link StatusNotaFiscal#RASCUNHO}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false, columnDefinition = "VARCHAR(20)")
  @Builder.Default
  private StatusNotaFiscal status = StatusNotaFiscal.RASCUNHO;

  /**
   * Tipo do documento fiscal (issue #18): {@link TipoDocumentoFiscal#NFE} (padrao) ou
   * {@link TipoDocumentoFiscal#NFA} (Nota Fiscal Avulsa do produtor rural pessoa fisica).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "tipo_documento", length = 10, nullable = false, columnDefinition = "VARCHAR(10)")
  @Builder.Default
  private TipoDocumentoFiscal tipoDocumento = TipoDocumentoFiscal.NFE;

  /**
   * Origem da nota (issue #19): {@link OrigemNotaFiscal#PROPRIA} (padrao, emitida pelo
   * produtor) ou {@link OrigemNotaFiscal#IMPORTADA} (nota de entrada importada do XML de um
   * terceiro, em que o produtor autenticado e o destinatario).
   *
   * <p>Afeta a unicidade: apenas notas {@code PROPRIA} respeitam o indice unico parcial
   * {@code (produtor_id, numero, serie, modelo)}. Importadas dedupam por {@code chaveAcesso}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "origem", length = 10, nullable = false, columnDefinition = "VARCHAR(10)")
  @Builder.Default
  private OrigemNotaFiscal origem = OrigemNotaFiscal.PROPRIA;

  /**
   * Nome/razao social do emitente terceiro. Preenchido apenas em notas
   * {@link OrigemNotaFiscal#IMPORTADA} (nulo em notas proprias, cujo emitente e o produtor).
   */
  @Column(name = "emitente_nome")
  private String emitenteNome;

  /**
   * CPF ou CNPJ do emitente terceiro (somente digitos, ate 14 caracteres). Preenchido apenas
   * em notas {@link OrigemNotaFiscal#IMPORTADA}. Nunca logar completo (LGPD).
   */
  @Column(name = "emitente_cpf_cnpj", length = 14)
  private String emitenteCpfCnpj;

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

  // -------------------------------------------------------------------------
  // Cancelamento da NF-e (Sprint 3 — issue #21, RN006). Preenchidos ao cancelar.
  // -------------------------------------------------------------------------

  /**
   * Data e hora (com fuso) em que a NF-e foi cancelada junto a SEFAZ.
   *
   * <p>Nula enquanto a nota nao foi cancelada. Preenchida na transicao terminal
   * {@code AUTORIZADA → CANCELADA} (issue #21).
   */
  @Column(name = "data_cancelamento")
  private OffsetDateTime dataCancelamento;

  /**
   * Justificativa do cancelamento informada pelo emitente (15 a 255 caracteres,
   * padrao SEFAZ). Nula enquanto a nota nao foi cancelada.
   */
  @Column(name = "motivo_cancelamento", length = 255)
  private String motivoCancelamento;

  /**
   * Numero do protocolo do evento de cancelamento retornado pela SEFAZ.
   * Nulo enquanto a nota nao foi cancelada.
   */
  @Column(name = "protocolo_cancelamento", length = 20)
  private String protocoloCancelamento;

  /**
   * Contador de versao para <strong>lock otimista</strong> (issue #95).
   *
   * <p>O Hibernate incrementa este campo em cada UPDATE e inclui a versao lida na cláusula WHERE.
   * Se outra transacao alterou a nota nesse intervalo, o UPDATE nao afeta linha alguma e o commit
   * falha com {@code OptimisticLockingFailureException} — mapeada para HTTP 409 pelo
   * {@code GlobalExceptionHandler}.
   *
   * <p>O que isso impede: duas transicoes de estado concorrentes na mesma nota (ex.: dois
   * {@code POST /gerar-xml} simultaneos) gerarem duas chaves de acesso, com o ultimo {@code save}
   * sobrescrevendo o primeiro — a nota ficaria com uma chave que nao corresponde ao XML gravado.
   *
   * <p><strong>Por que so aqui:</strong> {@code NotaFiscal} e a unica entidade com transicoes de
   * estado disparadas concorrentemente por requisicao HTTP. {@code ApuracaoMensal} e protegida por
   * lock pessimista em todo caminho de escrita (issue #100); {@code GuiaFiscal} so muda pelo
   * {@code PATCH /pagar}, ja guardado por {@code GuiaJaPagaException}. Avaliado na issue #95 e
   * considerado desnecessario nas duas (decisao D-4 do spec da Onda 3).
   */
  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /**
   * Texto pesquisável da nota (issue #174): número, nome do destinatário e natureza da operação,
   * em minúsculas e sem acento ({@link NormalizacaoBusca}). Mantido pela aplicação em
   * {@code @PrePersist}/{@code @PreUpdate} — nunca escrito por fora — e indexado por trigrama no
   * PostgreSQL (changeSet 0037), o que deixa a busca parcial do {@code GET /api/notas} fora da
   * varredura da tabela.
   *
   * <p>Guarda o nome do destinatário em claro, como a própria coluna {@code destinatario_nome}.
   * Se os dados das notas forem cifrados (débito da #147), esta coluna entra na mesma decisão:
   * busca parcial e cifra determinística não convivem.
   */
  @Column(name = "busca_normalizada", length = 600)
  private String buscaNormalizada;

  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = OffsetDateTime.now();
    updatedAt = OffsetDateTime.now();
    atualizarBuscaNormalizada();
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
    atualizarBuscaNormalizada();
  }

  /** Recalcula {@link #buscaNormalizada} a partir dos campos pesquisáveis (issue #174). */
  void atualizarBuscaNormalizada() {
    buscaNormalizada = NormalizacaoBusca.juntar(numero, destinatarioNome, naturezaOperacao);
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
