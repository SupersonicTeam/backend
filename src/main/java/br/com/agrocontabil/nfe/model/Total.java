package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Totais da NF-e (elemento {@code total} no XSD leiauteNFe_v4.00).
 *
 * <p>O grupo {@code ICMSTot} é obrigatório no layout. O grupo {@code IBSCBSTot}
 * (tipo {@code TIBSCBSMonoTot}) é opcional (minOccurs=0), mas preenchido quando
 * a nota possui IBS/CBS calculados.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "total",
    namespace = NfeNamespace.NS,
    propOrder = {"icmsTot", "ibsCbsTot"})
public class Total {

  /** Totais do ICMS (obrigatório). */
  @XmlElement(name = "ICMSTot", namespace = NfeNamespace.NS, required = true)
  private IcmsTot icmsTot;

  /** Totais do IBS/CBS (opcional, preenchido quando há tributos da reforma). */
  @XmlElement(name = "IBSCBSTot", namespace = NfeNamespace.NS)
  private IbsCbsTot ibsCbsTot;

  public IcmsTot getIcmsTot() { return icmsTot; }
  public void setIcmsTot(IcmsTot icmsTot) { this.icmsTot = icmsTot; }
  public IbsCbsTot getIbsCbsTot() { return ibsCbsTot; }
  public void setIbsCbsTot(IbsCbsTot ibsCbsTot) { this.ibsCbsTot = ibsCbsTot; }
}
