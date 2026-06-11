package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Identificação do destinatário da NF-e (grupo {@code dest} no XSD leiauteNFe_v4.00).
 *
 * <p>O destinatário pode ser identificado por CNPJ ou CPF (mutuamente exclusivos no XSD
 * — xs:choice). Esta classe mantém ambos como campos opcionais; o mapper garante que
 * apenas um seja preenchido.
 *
 * <p>O campo {@code indIEDest} é obrigatório no XSD:
 * <ul>
 *   <li>{@code "1"} = contribuinte ICMS (IE obrigatória)</li>
 *   <li>{@code "2"} = contribuinte isento</li>
 *   <li>{@code "9"} = não contribuinte</li>
 * </ul>
 * O mapper usa {@code "9"} como default para destinatários sem IE.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "dest",
    namespace = NfeNamespace.NS,
    propOrder = {"cnpj", "cpf", "xNome", "enderDest", "indIEDest", "ie"})
public class Dest {

  /** CNPJ do destinatário (14 dígitos). Mutuamente exclusivo com {@code cpf}. */
  @XmlElement(name = "CNPJ", namespace = NfeNamespace.NS)
  private String cnpj;

  /** CPF do destinatário (11 dígitos). Mutuamente exclusivo com {@code cnpj}. */
  @XmlElement(name = "CPF", namespace = NfeNamespace.NS)
  private String cpf;

  /** Nome ou razão social do destinatário (max 60 chars, opcional no XSD). */
  @XmlElement(name = "xNome", namespace = NfeNamespace.NS)
  private String xNome;

  /** Endereço do destinatário (opcional no XSD, minOccurs=0). */
  @XmlElement(name = "enderDest", namespace = NfeNamespace.NS)
  private EnderDest enderDest;

  /** Indicador da IE do destinatário. Default: {@code "9"} = não contribuinte. */
  @XmlElement(name = "indIEDest", namespace = NfeNamespace.NS, required = true)
  private String indIEDest;

  /** Inscrição Estadual do destinatário (opcional, obrigatória quando indIEDest="1"). */
  @XmlElement(name = "IE", namespace = NfeNamespace.NS)
  private String ie;

  public String getCnpj() { return cnpj; }
  public void setCnpj(String cnpj) { this.cnpj = cnpj; }
  public String getCpf() { return cpf; }
  public void setCpf(String cpf) { this.cpf = cpf; }
  public String getXNome() { return xNome; }
  public void setXNome(String xNome) { this.xNome = xNome; }
  public EnderDest getEnderDest() { return enderDest; }
  public void setEnderDest(EnderDest enderDest) { this.enderDest = enderDest; }
  public String getIndIEDest() { return indIEDest; }
  public void setIndIEDest(String indIEDest) { this.indIEDest = indIEDest; }
  public String getIe() { return ie; }
  public void setIe(String ie) { this.ie = ie; }
}
