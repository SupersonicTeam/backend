package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Detalhe de um item da NF-e (elemento {@code det} no XSD leiauteNFe_v4.00).
 *
 * <p>O atributo {@code nItem} é obrigatório e deve conter o número sequencial do item
 * (1-based), conforme exigido pelo XSD. O grupo de produto ({@code prod}) e o de
 * tributos ({@code imposto}) também são obrigatórios no layout.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "det",
    namespace = NfeNamespace.NS,
    propOrder = {"prod", "imposto"})
public class Det {

  /** Número sequencial do item (1-based), atributo obrigatório. */
  @XmlAttribute(name = "nItem", required = true)
  private String nItem;

  /** Dados do produto/serviço. */
  @XmlElement(name = "prod", namespace = NfeNamespace.NS, required = true)
  private Prod prod;

  /** Tributos incidentes no item. */
  @XmlElement(name = "imposto", namespace = NfeNamespace.NS, required = true)
  private Imposto imposto;

  public String getNItem() { return nItem; }
  public void setNItem(String nItem) { this.nItem = nItem; }
  public Prod getProd() { return prod; }
  public void setProd(Prod prod) { this.prod = prod; }
  public Imposto getImposto() { return imposto; }
  public void setImposto(Imposto imposto) { this.imposto = imposto; }
}
