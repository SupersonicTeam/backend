package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Dados do produto/serviço de um item da NF-e (grupo {@code prod} no XSD leiauteNFe_v4.00).
 *
 * <p>Campos de quantidade e valor unitário seguem os tipos do XSD:
 * <ul>
 *   <li>{@code qCom} / {@code qTrib} — tipo {@code TDec_1104v}: até 4 casas decimais</li>
 *   <li>{@code vUnCom} / {@code vUnTrib} — tipo {@code TDec_1110v}: até 10 casas decimais</li>
 *   <li>{@code vProd} — tipo {@code TDec_1302}: 2 casas decimais</li>
 * </ul>
 *
 * <p>Os campos {@code cEAN} e {@code cEANTrib} são obrigatórios no XSD e recebem
 * o valor {@code "SEM GTIN"} quando o produto não possui código de barras.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "prod",
    namespace = NfeNamespace.NS,
    propOrder = {
      "cProd", "cEAN", "xProd", "ncm", "cfop",
      "uCom", "qCom", "vUnCom", "vProd",
      "cEANTrib", "uTrib", "qTrib", "vUnTrib", "indTot"
    })
public class Prod {

  /** Código do produto/serviço (max 60 chars). */
  @XmlElement(name = "cProd", namespace = NfeNamespace.NS, required = true)
  private String cProd;

  /** GTIN (código de barras) comercial. Use {@code "SEM GTIN"} quando não houver. */
  @XmlElement(name = "cEAN", namespace = NfeNamespace.NS, required = true)
  private String cEAN;

  /** Descrição do produto/serviço (max 120 chars). */
  @XmlElement(name = "xProd", namespace = NfeNamespace.NS, required = true)
  private String xProd;

  /** Código NCM (8 dígitos). */
  @XmlElement(name = "NCM", namespace = NfeNamespace.NS, required = true)
  private String ncm;

  /** CFOP (4 dígitos, iniciando com 1–3 para entrada ou 5–7 para saída). */
  @XmlElement(name = "CFOP", namespace = NfeNamespace.NS, required = true)
  private String cfop;

  /** Unidade comercial (max 6 chars, ex.: {@code "KG"}, {@code "UN"}). */
  @XmlElement(name = "uCom", namespace = NfeNamespace.NS, required = true)
  private String uCom;

  /** Quantidade comercializada (até 4 casas decimais). */
  @XmlElement(name = "qCom", namespace = NfeNamespace.NS, required = true)
  private String qCom;

  /** Valor unitário de comercialização (até 10 casas decimais). */
  @XmlElement(name = "vUnCom", namespace = NfeNamespace.NS, required = true)
  private String vUnCom;

  /** Valor bruto do produto (2 casas decimais). */
  @XmlElement(name = "vProd", namespace = NfeNamespace.NS, required = true)
  private String vProd;

  /** GTIN tributável. Use {@code "SEM GTIN"} quando não houver. */
  @XmlElement(name = "cEANTrib", namespace = NfeNamespace.NS, required = true)
  private String cEANTrib;

  /** Unidade tributável (max 6 chars). */
  @XmlElement(name = "uTrib", namespace = NfeNamespace.NS, required = true)
  private String uTrib;

  /** Quantidade tributável (até 4 casas decimais). */
  @XmlElement(name = "qTrib", namespace = NfeNamespace.NS, required = true)
  private String qTrib;

  /** Valor unitário de tributação (até 10 casas decimais). */
  @XmlElement(name = "vUnTrib", namespace = NfeNamespace.NS, required = true)
  private String vUnTrib;

  /**
   * Indica se o valor compõe o total da NF-e: {@code "0"} = não compõe,
   * {@code "1"} = compõe.
   */
  @XmlElement(name = "indTot", namespace = NfeNamespace.NS, required = true)
  private String indTot;

  public String getCProd() { return cProd; }
  public void setCProd(String cProd) { this.cProd = cProd; }
  public String getCEAN() { return cEAN; }
  public void setCEAN(String cEAN) { this.cEAN = cEAN; }
  public String getXProd() { return xProd; }
  public void setXProd(String xProd) { this.xProd = xProd; }
  public String getNcm() { return ncm; }
  public void setNcm(String ncm) { this.ncm = ncm; }
  public String getCfop() { return cfop; }
  public void setCfop(String cfop) { this.cfop = cfop; }
  public String getUCom() { return uCom; }
  public void setUCom(String uCom) { this.uCom = uCom; }
  public String getQCom() { return qCom; }
  public void setQCom(String qCom) { this.qCom = qCom; }
  public String getVUnCom() { return vUnCom; }
  public void setVUnCom(String vUnCom) { this.vUnCom = vUnCom; }
  public String getVProd() { return vProd; }
  public void setVProd(String vProd) { this.vProd = vProd; }
  public String getCEANTrib() { return cEANTrib; }
  public void setCEANTrib(String cEANTrib) { this.cEANTrib = cEANTrib; }
  public String getUTrib() { return uTrib; }
  public void setUTrib(String uTrib) { this.uTrib = uTrib; }
  public String getQTrib() { return qTrib; }
  public void setQTrib(String qTrib) { this.qTrib = qTrib; }
  public String getVUnTrib() { return vUnTrib; }
  public void setVUnTrib(String vUnTrib) { this.vUnTrib = vUnTrib; }
  public String getIndTot() { return indTot; }
  public void setIndTot(String indTot) { this.indTot = indTot; }
}
