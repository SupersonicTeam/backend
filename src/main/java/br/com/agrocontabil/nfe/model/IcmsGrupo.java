package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Início comum de todo grupo de tributação do ICMS: {@code orig} e {@code CST} (ou {@code CSOSN},
 * nos grupos do Simples Nacional). Na emissão só vão {@code orig} e {@code CST} (MOC 7.00, Anexo I:
 * os demais campos do {@code ICMS40}, {@code ICMS51} e {@code ICMS90} são 0-1); na importação os
 * outros campos do grupo são ignorados.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "icmsGrupo", namespace = NfeNamespace.NS, propOrder = {"orig", "cst", "csosn"})
public class IcmsGrupo {

  @XmlElement(name = "orig", namespace = NfeNamespace.NS)
  private String orig;

  @XmlElement(name = "CST", namespace = NfeNamespace.NS)
  private String cst;

  @XmlElement(name = "CSOSN", namespace = NfeNamespace.NS)
  private String csosn;

  public IcmsGrupo() {}

  /** Grupo de emissão: origem e CST. */
  public IcmsGrupo(String orig, String cst) {
    this.orig = orig;
    this.cst = cst;
  }

  public String getOrig() { return orig; }
  public void setOrig(String orig) { this.orig = orig; }
  public String getCst() { return cst; }
  public void setCst(String cst) { this.cst = cst; }
  public String getCsosn() { return csosn; }
  public void setCsosn(String csosn) { this.csosn = csosn; }

  /** CST, ou o CSOSN do Simples Nacional quando não há CST. */
  public String cstOuCsosn() {
    return cst != null ? cst : csosn;
  }
}
