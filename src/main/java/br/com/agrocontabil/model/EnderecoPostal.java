package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Endereço postal do escritório de contabilidade (issue #519), embutido em {@link Escritorio}.
 *
 * <p>Mesmo formato e tamanhos do {@link EnderecoProdutor} (#88), com a UF junto: no produtor ela
 * fica em {@code Produtor.estado}, coluna anterior ao endereço. "Postal" o separa do endereço
 * próprio do escritório na internet, o {@code {slug}.agroconia.com.br} (#464).
 *
 * <p>Opcional no banco: o cadastro empresarial o exige pela validação do request, e escritórios
 * antigos não o têm.
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnderecoPostal {

  /** CEP com 8 dígitos, sem formatação. */
  @Column(name = "endereco_cep", length = 8)
  private String cep;

  @Column(name = "endereco_logradouro", length = 60)
  private String logradouro;

  /** Número do imóvel; {@code S/N} quando não houver. */
  @Column(name = "endereco_numero", length = 60)
  private String numero;

  @Column(name = "endereco_complemento", length = 60)
  private String complemento;

  @Column(name = "endereco_bairro", length = 60)
  private String bairro;

  /** Código IBGE do município, 7 dígitos; os 2 primeiros são o cUF da {@link #uf}. */
  @Column(name = "endereco_codigo_municipio_ibge", length = 7)
  private String codigoMunicipioIbge;

  @Column(name = "endereco_municipio", length = 60)
  private String municipio;

  /** Sigla da UF. */
  @Column(name = "endereco_uf", length = 2)
  private String uf;
}
