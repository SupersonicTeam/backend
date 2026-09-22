package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Endereço completo do produtor emitente, embutido em {@link Produtor} (Onda 2, issue #88).
 *
 * <p>Alimenta o grupo {@code <enderEmit>} do XML da NF-e e o {@code cMunFG} (município do fato
 * gerador, que é o do <strong>emitente</strong>). Antes desta issue o
 * {@code NotaFiscalNfeMapper} preenchia esses campos com placeholders — o XML passava no XSD e na
 * assinatura, mas seria rejeitado pela SEFAZ real.
 *
 * <p>Classe própria em vez de reaproveitar {@link EnderecoDestinatario}: o emitente exige
 * {@code bairro} (obrigatório no layout) e o destinatário não tem {@code complemento}. A UF fica
 * fora do embeddable — permanece em {@code Produtor.estado}, coluna que já existia.
 *
 * <p>Todos os campos são opcionais no schema (produtores antigos e rascunhos continuam válidos);
 * a obrigatoriedade para emissão é regra do {@code FiscalRulesValidator} (422). Tamanhos seguem o
 * layout NF-e 4.00.
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnderecoProdutor {

  /** Logradouro — {@code xLgr} (2 a 60 caracteres no layout). */
  @Column(name = "endereco_logradouro", length = 60)
  private String logradouro;

  /** Número do imóvel — {@code nro}; use {@code S/N} quando não houver. */
  @Column(name = "endereco_numero", length = 60)
  private String numero;

  /** Complemento — {@code xCpl}, opcional também na emissão. */
  @Column(name = "endereco_complemento", length = 60)
  private String complemento;

  /** Bairro — {@code xBairro} (obrigatório na emissão). */
  @Column(name = "endereco_bairro", length = 60)
  private String bairro;

  /**
   * Código IBGE do município (7 dígitos) — {@code cMun} e {@code cMunFG}.
   *
   * <p>Os 2 primeiros dígitos são o cUF da UF do produtor; a coerência é validada por
   * {@code UfIbge.prefixoCoerente} na emissão.
   */
  @Column(name = "endereco_codigo_municipio_ibge", length = 7)
  private String codigoMunicipioIbge;

  /** Nome do município — {@code xMun}. */
  @Column(name = "endereco_municipio", length = 60)
  private String municipio;

  /** CEP com 8 dígitos, sem formatação — {@code CEP}. */
  @Column(name = "endereco_cep", length = 8)
  private String cep;
}
