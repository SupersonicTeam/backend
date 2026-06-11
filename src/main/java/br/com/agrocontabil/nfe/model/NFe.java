package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Raiz do documento NF-e modelo 55 (layout 4.00).
 *
 * <p>Mapeado para o elemento {@code <NFe>} no namespace
 * {@code http://www.portalfiscal.inf.br/nfe}, conforme definido em
 * {@code nfe_v4.00.xsd} (tipo {@code TNFe}).
 *
 * <p>Esta classe contém <strong>exclusivamente</strong> a estrutura XML — nenhuma
 * lógica de negócio ou formatação. Toda lógica reside em
 * {@link br.com.agrocontabil.nfe.NotaFiscalNfeMapper}.
 */
@XmlRootElement(name = "NFe", namespace = NfeNamespace.NS)
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "TNFe",
    namespace = NfeNamespace.NS,
    propOrder = {"infNFe"})
public class NFe {

  @XmlElement(name = "infNFe", namespace = NfeNamespace.NS, required = true)
  private InfNFe infNFe;

  /** Retorna as informações da NF-e. */
  public InfNFe getInfNFe() {
    return infNFe;
  }

  /** Define as informações da NF-e. */
  public void setInfNFe(InfNFe infNFe) {
    this.infNFe = infNFe;
  }
}
