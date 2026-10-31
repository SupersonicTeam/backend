package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Grupo de informações de IBS/CBS por item da NF-e (tipo {@code TTribNFe} em
 * {@code DFeTiposBasicos_v1.00.xsd}).
 *
 * <p>Mapeado para o elemento {@code <IBSCBS>} dentro de {@code det/imposto},
 * conforme {@code leiauteNFe_v4.00.xsd} linha 5177:
 * {@code <xs:element name="IBSCBS" type="TTribNFe" minOccurs="0">}.
 *
 * <h3>Estrutura do TTribNFe mapeada:</h3>
 * <ol>
 *   <li>{@code CST} — Código Situação Tributária (obrigatório)</li>
 *   <li>{@code cClassTrib} — Código de Classificação Tributária (obrigatório)</li>
 *   <li>{@code gIBSCBS} (choice, opcional) — detalhamento IBS/CBS via tipo {@code TCIBS}</li>
 * </ol>
 *
 * <h3>Débito técnico — campos não mapeados:</h3>
 * <ul>
 *   <li>{@code indDoacao} — não há campo correspondente em {@code NotaFiscalItem}</li>
 *   <li>Choice {@code gIBSCBSMono} (monofasia CST 620) — não aplicável ao escopo atual</li>
 *   <li>Choice {@code gTransfCred} (CST 800) e {@code gAjusteCompet} (CST 811) — não aplicáveis</li>
 *   <li>{@code gEstornoCred}, {@code gCredPresOper}, {@code gCredPresIBSZFM} — ausentes na entidade</li>
 *   <li>{@code gDif}, {@code gDevTrib}, {@code gTribRegular}, {@code gTribCompraGov} — sem
 *       diferimento, devolução de tributo, tributação regular nem compra governamental</li>
 * </ul>
 *
 * <p>CST e cClassTrib vêm do tratamento aplicado no cálculo (tabela oficial, IT 2025.002 —
 * issues #272 e #278); com redução de alíquota (CST 200) o grupo {@code gRed} é preenchido em
 * {@code gIBSUF}, {@code gIBSMun} e {@code gCBS}. As parcelas estadual e municipal são as do
 * item (issues #31 e #277). Esquema: PL_010g (NT 2025.002-RTC), tipo {@code TCIBS_NFe}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "TTribNFe",
    namespace = NfeNamespace.NS,
    propOrder = {"cst", "cClassTrib", "gIBSCBS"})
public class IbsCbs {

  /** Código de Situação Tributária do IBS/CBS (tipo {@code TCST}, 3 dígitos). */
  @XmlElement(name = "CST", namespace = NfeNamespace.NS, required = true)
  private String cst;

  /** Código de Classificação Tributária (6 dígitos). */
  @XmlElement(name = "cClassTrib", namespace = NfeNamespace.NS, required = true)
  private String cClassTrib;

  /**
   * Grupo completo IBS/CBS (choice {@code gIBSCBS} do tipo {@code TCIBS}, opcional).
   * Preenchido com a base, as alíquotas e os valores do item (CST {@code 000} ou {@code 200}).
   */
  @XmlElement(name = "gIBSCBS", namespace = NfeNamespace.NS)
  private GIbsCbs gIBSCBS;

  public String getCst() { return cst; }
  public void setCst(String cst) { this.cst = cst; }
  public String getCClassTrib() { return cClassTrib; }
  public void setCClassTrib(String cClassTrib) { this.cClassTrib = cClassTrib; }
  public GIbsCbs getGIBSCBS() { return gIBSCBS; }
  public void setGIBSCBS(GIbsCbs gIBSCBS) { this.gIBSCBS = gIBSCBS; }

  // =========================================================================
  // Tipo interno: TCIBS (gIBSCBS)
  // =========================================================================

  /**
   * Grupo detalhado IBS/CBS (tipo {@code TCIBS} no XSD).
   *
   * <p>Contém base de cálculo, grupos por UF/município para o IBS, e grupo
   * para a CBS.
   *
   * <p>As alíquotas por UF ({@code pIBSUF}) e por município ({@code pIBSMun}) são as parcelas
   * gravadas no item (issues #31 e #277).
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "TCIBS_NFe",
      namespace = NfeNamespace.NS,
      propOrder = {"vBC", "gIBSUF", "gIBSMun", "vIBS", "gCBS"})
  public static class GIbsCbs {

    /** Base de cálculo do IBS/CBS (2 casas decimais). */
    @XmlElement(name = "vBC", namespace = NfeNamespace.NS, required = true)
    private String vBC;

    /** Grupo IBS estadual (UF). */
    @XmlElement(name = "gIBSUF", namespace = NfeNamespace.NS, required = true)
    private GIbsUf gIBSUF;

    /** Grupo IBS municipal. */
    @XmlElement(name = "gIBSMun", namespace = NfeNamespace.NS, required = true)
    private GIbsMun gIBSMun;

    /** Valor total do IBS (estadual + municipal), 2 casas decimais. */
    @XmlElement(name = "vIBS", namespace = NfeNamespace.NS, required = true)
    private String vIBS;

    /** Grupo CBS federal. */
    @XmlElement(name = "gCBS", namespace = NfeNamespace.NS, required = true)
    private GCbs gCBS;

    public String getVBC() { return vBC; }
    public void setVBC(String vBC) { this.vBC = vBC; }
    public GIbsUf getGIBSUF() { return gIBSUF; }
    public void setGIBSUF(GIbsUf gIBSUF) { this.gIBSUF = gIBSUF; }
    public GIbsMun getGIBSMun() { return gIBSMun; }
    public void setGIBSMun(GIbsMun gIBSMun) { this.gIBSMun = gIBSMun; }
    public String getVIBS() { return vIBS; }
    public void setVIBS(String vIBS) { this.vIBS = vIBS; }
    public GCbs getGCBS() { return gCBS; }
    public void setGCBS(GCbs gCBS) { this.gCBS = gCBS; }
  }

  /** Grupo IBS de competência da UF (parte de {@code TCIBS}). */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "gIBSUF",
      namespace = NfeNamespace.NS,
      propOrder = {"pIBSUF", "gRed", "vIBSUF"})
  public static class GIbsUf {

    /** Alíquota do IBS estadual em percentual (ex.: {@code "0.0500"} = 0,05%). */
    @XmlElement(name = "pIBSUF", namespace = NfeNamespace.NS, required = true)
    private String pIBSUF;

    /** Redução de alíquota ({@code TRed}); obrigatório com CST 200 (ind_gRed = 1). */
    @XmlElement(name = "gRed", namespace = NfeNamespace.NS)
    private GRed gRed;

    /** Valor do IBS estadual (2 casas decimais). */
    @XmlElement(name = "vIBSUF", namespace = NfeNamespace.NS, required = true)
    private String vIBSUF;

    public String getPIBSUF() { return pIBSUF; }
    public void setPIBSUF(String pIBSUF) { this.pIBSUF = pIBSUF; }
    public GRed getGRed() { return gRed; }
    public void setGRed(GRed gRed) { this.gRed = gRed; }
    public String getVIBSUF() { return vIBSUF; }
    public void setVIBSUF(String vIBSUF) { this.vIBSUF = vIBSUF; }
  }

  /** Grupo IBS de competência do Município (parte de {@code TCIBS}). */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "gIBSMun",
      namespace = NfeNamespace.NS,
      propOrder = {"pIBSMun", "gRed", "vIBSMun"})
  public static class GIbsMun {

    /** Alíquota do IBS municipal em percentual. */
    @XmlElement(name = "pIBSMun", namespace = NfeNamespace.NS, required = true)
    private String pIBSMun;

    /** Redução de alíquota ({@code TRed}); obrigatório com CST 200 (ind_gRed = 1). */
    @XmlElement(name = "gRed", namespace = NfeNamespace.NS)
    private GRed gRed;

    /** Valor do IBS municipal (2 casas decimais). */
    @XmlElement(name = "vIBSMun", namespace = NfeNamespace.NS, required = true)
    private String vIBSMun;

    public String getPIBSMun() { return pIBSMun; }
    public void setPIBSMun(String pIBSMun) { this.pIBSMun = pIBSMun; }
    public GRed getGRed() { return gRed; }
    public void setGRed(GRed gRed) { this.gRed = gRed; }
    public String getVIBSMun() { return vIBSMun; }
    public void setVIBSMun(String vIBSMun) { this.vIBSMun = vIBSMun; }
  }

  /** Grupo CBS federal (parte de {@code TCIBS}). */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "gCBS",
      namespace = NfeNamespace.NS,
      propOrder = {"pCBS", "gRed", "vCBS"})
  public static class GCbs {

    /** Alíquota da CBS em percentual (ex.: {@code "0.9000"} = 0,90%). */
    @XmlElement(name = "pCBS", namespace = NfeNamespace.NS, required = true)
    private String pCBS;

    /** Redução de alíquota ({@code TRed}); obrigatório com CST 200 (ind_gRed = 1). */
    @XmlElement(name = "gRed", namespace = NfeNamespace.NS)
    private GRed gRed;

    /** Valor da CBS (2 casas decimais). */
    @XmlElement(name = "vCBS", namespace = NfeNamespace.NS, required = true)
    private String vCBS;

    public String getPCBS() { return pCBS; }
    public void setPCBS(String pCBS) { this.pCBS = pCBS; }
    public GRed getGRed() { return gRed; }
    public void setGRed(GRed gRed) { this.gRed = gRed; }
    public String getVCBS() { return vCBS; }
    public void setVCBS(String vCBS) { this.vCBS = vCBS; }
  }

  /**
   * Redução de alíquota (tipo {@code TRed} no XSD): {@code pRedAliq} é o percentual de redução e
   * {@code pAliqEfet} a alíquota efetiva, {@code alíquota × (1 − pRedAliq / 100)}. O valor do
   * tributo é {@code vBC × pAliqEfet} — issue #278.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "TRed",
      namespace = NfeNamespace.NS,
      propOrder = {"pRedAliq", "pAliqEfet"})
  public static class GRed {

    /** Percentual de redução da alíquota (ex.: {@code "60.0000"}). */
    @XmlElement(name = "pRedAliq", namespace = NfeNamespace.NS, required = true)
    private String pRedAliq;

    /** Alíquota efetiva em percentual (ex.: {@code "0.0400"}). */
    @XmlElement(name = "pAliqEfet", namespace = NfeNamespace.NS, required = true)
    private String pAliqEfet;

    public String getPRedAliq() { return pRedAliq; }
    public void setPRedAliq(String pRedAliq) { this.pRedAliq = pRedAliq; }
    public String getPAliqEfet() { return pAliqEfet; }
    public void setPAliqEfet(String pAliqEfet) { this.pAliqEfet = pAliqEfet; }
  }
}
