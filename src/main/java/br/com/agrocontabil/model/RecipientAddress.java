package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Endereço do destinatário embutido na entidade {@link Invoice}.
 *
 * <p>As colunas seguem o prefix {@code dest_} definido na migration
 * {@code 0002-invoice-fiscal.xml}. Todos os campos são opcionais no banco (nullable).
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecipientAddress {

  /** Logradouro (rua, avenida etc.) do destinatário. */
  @Column(name = "dest_logradouro")
  private String street;

  /** Número do imóvel. */
  @Column(name = "dest_numero", length = 20)
  private String number;

  /** Nome do município. */
  @Column(name = "dest_municipio")
  private String city;

  /** Código IBGE do município (7 dígitos). */
  @Column(name = "dest_codigo_municipio_ibge", length = 7)
  private String ibgeCityCode;

  /** Sigla da UF (2 caracteres). */
  @Column(name = "dest_uf", length = 2)
  private String uf;

  /** CEP sem formatação (8 dígitos). */
  @Column(name = "dest_cep", length = 8)
  private String zipCode;
}
