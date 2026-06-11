package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Informações adicionais da NF-e (elemento {@code infAdic} no XSD leiauteNFe_v4.00,
 * minOccurs=0).
 *
 * <p>O campo {@code infCpl} (informações complementares) é opcional e permite incluir
 * observações do contribuinte. Usado nesta implementação para indicar que a nota foi
 * gerada em ambiente de homologação (ano-teste 2026, IBS/CBS informativos).
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "infAdic",
    namespace = NfeNamespace.NS,
    propOrder = {"infCpl"})
public class InfAdic {

  /** Informações complementares (max 5000 chars, opcional). */
  @XmlElement(name = "infCpl", namespace = NfeNamespace.NS)
  private String infCpl;

  public String getInfCpl() { return infCpl; }
  public void setInfCpl(String infCpl) { this.infCpl = infCpl; }
}
