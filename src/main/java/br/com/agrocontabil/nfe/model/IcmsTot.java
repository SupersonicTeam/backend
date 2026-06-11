package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Totais referentes ao ICMS (elemento {@code ICMSTot} no XSD leiauteNFe_v4.00).
 *
 * <p>Todos os campos do {@code ICMSTot} são obrigatórios no XSD. Para notas de produtor
 * rural no ano-teste 2026 (sem incidência de ICMS), os campos de imposto são preenchidos
 * com {@code "0.00"} e o {@code vNF} reflete o valor total dos produtos.
 *
 * <p>Campos mapeados (todos obrigatórios no XSD, tipo {@code TDec_1302}):
 * {@code vBC}, {@code vICMS}, {@code vICMSDeson}, {@code vFCP}, {@code vBCST},
 * {@code vST}, {@code vFCPST}, {@code vFCPSTRet}, {@code vProd}, {@code vFrete},
 * {@code vSeg}, {@code vDesc}, {@code vII}, {@code vIPI}, {@code vIPIDevol},
 * {@code vPIS}, {@code vCOFINS}, {@code vOutro}, {@code vNF}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "ICMSTot",
    namespace = NfeNamespace.NS,
    propOrder = {
      "vBC", "vICMS", "vICMSDeson", "vFCP", "vBCST", "vST",
      "vFCPST", "vFCPSTRet", "vProd", "vFrete", "vSeg", "vDesc",
      "vII", "vIPI", "vIPIDevol", "vPIS", "vCOFINS", "vOutro", "vNF"
    })
public class IcmsTot {

  @XmlElement(name = "vBC", namespace = NfeNamespace.NS, required = true)
  private String vBC;
  @XmlElement(name = "vICMS", namespace = NfeNamespace.NS, required = true)
  private String vICMS;
  @XmlElement(name = "vICMSDeson", namespace = NfeNamespace.NS, required = true)
  private String vICMSDeson;
  @XmlElement(name = "vFCP", namespace = NfeNamespace.NS, required = true)
  private String vFCP;
  @XmlElement(name = "vBCST", namespace = NfeNamespace.NS, required = true)
  private String vBCST;
  @XmlElement(name = "vST", namespace = NfeNamespace.NS, required = true)
  private String vST;
  @XmlElement(name = "vFCPST", namespace = NfeNamespace.NS, required = true)
  private String vFCPST;
  @XmlElement(name = "vFCPSTRet", namespace = NfeNamespace.NS, required = true)
  private String vFCPSTRet;
  @XmlElement(name = "vProd", namespace = NfeNamespace.NS, required = true)
  private String vProd;
  @XmlElement(name = "vFrete", namespace = NfeNamespace.NS, required = true)
  private String vFrete;
  @XmlElement(name = "vSeg", namespace = NfeNamespace.NS, required = true)
  private String vSeg;
  @XmlElement(name = "vDesc", namespace = NfeNamespace.NS, required = true)
  private String vDesc;
  @XmlElement(name = "vII", namespace = NfeNamespace.NS, required = true)
  private String vII;
  @XmlElement(name = "vIPI", namespace = NfeNamespace.NS, required = true)
  private String vIPI;
  @XmlElement(name = "vIPIDevol", namespace = NfeNamespace.NS, required = true)
  private String vIPIDevol;
  @XmlElement(name = "vPIS", namespace = NfeNamespace.NS, required = true)
  private String vPIS;
  @XmlElement(name = "vCOFINS", namespace = NfeNamespace.NS, required = true)
  private String vCOFINS;
  @XmlElement(name = "vOutro", namespace = NfeNamespace.NS, required = true)
  private String vOutro;
  @XmlElement(name = "vNF", namespace = NfeNamespace.NS, required = true)
  private String vNF;

  public String getVBC() { return vBC; }
  public void setVBC(String vBC) { this.vBC = vBC; }
  public String getVICMS() { return vICMS; }
  public void setVICMS(String vICMS) { this.vICMS = vICMS; }
  public String getVICMSDeson() { return vICMSDeson; }
  public void setVICMSDeson(String vICMSDeson) { this.vICMSDeson = vICMSDeson; }
  public String getVFCP() { return vFCP; }
  public void setVFCP(String vFCP) { this.vFCP = vFCP; }
  public String getVBCST() { return vBCST; }
  public void setVBCST(String vBCST) { this.vBCST = vBCST; }
  public String getVST() { return vST; }
  public void setVST(String vST) { this.vST = vST; }
  public String getVFCPST() { return vFCPST; }
  public void setVFCPST(String vFCPST) { this.vFCPST = vFCPST; }
  public String getVFCPSTRet() { return vFCPSTRet; }
  public void setVFCPSTRet(String vFCPSTRet) { this.vFCPSTRet = vFCPSTRet; }
  public String getVProd() { return vProd; }
  public void setVProd(String vProd) { this.vProd = vProd; }
  public String getVFrete() { return vFrete; }
  public void setVFrete(String vFrete) { this.vFrete = vFrete; }
  public String getVSeg() { return vSeg; }
  public void setVSeg(String vSeg) { this.vSeg = vSeg; }
  public String getVDesc() { return vDesc; }
  public void setVDesc(String vDesc) { this.vDesc = vDesc; }
  public String getVII() { return vII; }
  public void setVII(String vII) { this.vII = vII; }
  public String getVIPI() { return vIPI; }
  public void setVIPI(String vIPI) { this.vIPI = vIPI; }
  public String getVIPIDevol() { return vIPIDevol; }
  public void setVIPIDevol(String vIPIDevol) { this.vIPIDevol = vIPIDevol; }
  public String getVPIS() { return vPIS; }
  public void setVPIS(String vPIS) { this.vPIS = vPIS; }
  public String getVCOFINS() { return vCOFINS; }
  public void setVCOFINS(String vCOFINS) { this.vCOFINS = vCOFINS; }
  public String getVOutro() { return vOutro; }
  public void setVOutro(String vOutro) { this.vOutro = vOutro; }
  public String getVNF() { return vNF; }
  public void setVNF(String vNF) { this.vNF = vNF; }
}
