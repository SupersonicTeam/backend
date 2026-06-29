package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Endereço do destinatário embutido na entidade {@link NotaFiscal}.
 *
 * <p>As colunas seguem o prefixo {@code dest_} definido na migration
 * {@code 0002-nota-fiscal.xml}. Todos os campos são opcionais no banco (nullable).
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnderecoDestinatario {

  /** Logradouro (rua, avenida etc.) do destinatário. */
  @Column(name = "dest_logradouro")
  private String logradouro;

  /** Número do imóvel. */
  @Column(name = "dest_numero", length = 20)
  private String numero;

  /** Nome do município. */
  @Column(name = "dest_municipio")
  private String municipio;

  /** Código IBGE do município (7 dígitos). */
  @Column(name = "dest_codigo_municipio_ibge", length = 7)
  private String codigoMunicipioIbge;

  /** Sigla da UF (2 caracteres). */
  @Column(name = "dest_uf", length = 2)
  private String uf;

  /** CEP sem formatação (8 dígitos). */
  @Column(name = "dest_cep", length = 8)
  private String cep;
}
