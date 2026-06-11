package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Totais de IBS/CBS na NF-e (tipo {@code TIBSCBSMonoTot} em {@code DFeTiposBasicos_v1.00.xsd}).
 *
 * <p>Mapeado para o elemento {@code <IBSCBSTot>} dentro do grupo {@code total},
 * conforme {@code leiauteNFe_v4.00.xsd} linha 5613:
 * {@code <xs:element name="IBSCBSTot" type="TIBSCBSMonoTot" minOccurs="0">}.
 *
 * <p>A estrutura {@code TIBSCBSMonoTot} contém:
 * <ol>
 *   <li>{@code vBCIBSCBS} — total da base de cálculo (obrigatório)</li>
 *   <li>{@code gIBS} — totais do IBS (opcional no TIBSCBSMonoTot)</li>
 *   <li>{@code gCBS} — totais da CBS (opcional no TIBSCBSMonoTot)</li>
 * </ol>
 *
 * <p>Débito técnico — campos não mapeados de {@code TIBSCBSMonoTot}:
 * <ul>
 *   <li>{@code gIBS.vCredPres}, {@code gIBS.vCredPresCondSus} — crédito presumido ausente na entidade</li>
 *   <li>{@code gMono} — monofasia não aplicável</li>
 * </ul>
 *
 * <p>Os subgrupos {@code gIBSUF} e {@code gIBSMun} dentro de {@code gIBS} usam
 * zeros para {@code vDif} e {@code vDevTrib} (sem diferimento nem devolução no escopo atual).
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "TIBSCBSMonoTot",
    namespace = NfeNamespace.NS,
    propOrder = {"vBCIBSCBS", "gIBS", "gCBS"})
public class IbsCbsTot {

  /** Total da base de cálculo IBS/CBS (2 casas decimais). */
  @XmlElement(name = "vBCIBSCBS", namespace = NfeNamespace.NS, required = true)
  private String vBCIBSCBS;

  /** Totais do IBS (opcional no XSD). */
  @XmlElement(name = "gIBS", namespace = NfeNamespace.NS)
  private GIbsTot gIBS;

  /** Totais da CBS (opcional no XSD). */
  @XmlElement(name = "gCBS", namespace = NfeNamespace.NS)
  private GCbsTot gCBS;

  public String getVBCIBSCBS() { return vBCIBSCBS; }
  public void setVBCIBSCBS(String vBCIBSCBS) { this.vBCIBSCBS = vBCIBSCBS; }
  public GIbsTot getGIBS() { return gIBS; }
  public void setGIBS(GIbsTot gIBS) { this.gIBS = gIBS; }
  public GCbsTot getGCBS() { return gCBS; }
  public void setGCBS(GCbsTot gCBS) { this.gCBS = gCBS; }

  // =========================================================================
  // Tipos internos
  // =========================================================================

  /** Grupo de totais do IBS dentro de {@code TIBSCBSMonoTot}. */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "gIBSTot",
      namespace = NfeNamespace.NS,
      propOrder = {"gIBSUF", "gIBSMun", "vIBS", "vCredPres", "vCredPresCondSus"})
  public static class GIbsTot {

    @XmlElement(name = "gIBSUF", namespace = NfeNamespace.NS, required = true)
    private GIbsUfTot gIBSUF;

    @XmlElement(name = "gIBSMun", namespace = NfeNamespace.NS, required = true)
    private GIbsMunTot gIBSMun;

    /** Valor total do IBS (estadual + municipal). */
    @XmlElement(name = "vIBS", namespace = NfeNamespace.NS, required = true)
    private String vIBS;

    /** Total do Crédito Presumido IBS (obrigatório no grupo gIBS de TIBSCBSMonoTot). */
    @XmlElement(name = "vCredPres", namespace = NfeNamespace.NS, required = true)
    private String vCredPres;

    /** Total do Crédito Presumido IBS em Condição Suspensiva (obrigatório). */
    @XmlElement(name = "vCredPresCondSus", namespace = NfeNamespace.NS, required = true)
    private String vCredPresCondSus;

    public GIbsUfTot getGIBSUF() { return gIBSUF; }
    public void setGIBSUF(GIbsUfTot gIBSUF) { this.gIBSUF = gIBSUF; }
    public GIbsMunTot getGIBSMun() { return gIBSMun; }
    public void setGIBSMun(GIbsMunTot gIBSMun) { this.gIBSMun = gIBSMun; }
    public String getVIBS() { return vIBS; }
    public void setVIBS(String vIBS) { this.vIBS = vIBS; }
    public String getVCredPres() { return vCredPres; }
    public void setVCredPres(String vCredPres) { this.vCredPres = vCredPres; }
    public String getVCredPresCondSus() { return vCredPresCondSus; }
    public void setVCredPresCondSus(String v) { this.vCredPresCondSus = v; }
  }

  /** Totais do IBS estadual dentro de {@code TIBSCBSMonoTot.gIBS}. */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "gIBSUFTot",
      namespace = NfeNamespace.NS,
      propOrder = {"vDif", "vDevTrib", "vIBSUF"})
  public static class GIbsUfTot {

    @XmlElement(name = "vDif", namespace = NfeNamespace.NS, required = true)
    private String vDif;

    @XmlElement(name = "vDevTrib", namespace = NfeNamespace.NS, required = true)
    private String vDevTrib;

    @XmlElement(name = "vIBSUF", namespace = NfeNamespace.NS, required = true)
    private String vIBSUF;

    public String getVDif() { return vDif; }
    public void setVDif(String vDif) { this.vDif = vDif; }
    public String getVDevTrib() { return vDevTrib; }
    public void setVDevTrib(String vDevTrib) { this.vDevTrib = vDevTrib; }
    public String getVIBSUF() { return vIBSUF; }
    public void setVIBSUF(String vIBSUF) { this.vIBSUF = vIBSUF; }
  }

  /** Totais do IBS municipal dentro de {@code TIBSCBSMonoTot.gIBS}. */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "gIBSMunTot",
      namespace = NfeNamespace.NS,
      propOrder = {"vDif", "vDevTrib", "vIBSMun"})
  public static class GIbsMunTot {

    @XmlElement(name = "vDif", namespace = NfeNamespace.NS, required = true)
    private String vDif;

    @XmlElement(name = "vDevTrib", namespace = NfeNamespace.NS, required = true)
    private String vDevTrib;

    @XmlElement(name = "vIBSMun", namespace = NfeNamespace.NS, required = true)
    private String vIBSMun;

    public String getVDif() { return vDif; }
    public void setVDif(String vDif) { this.vDif = vDif; }
    public String getVDevTrib() { return vDevTrib; }
    public void setVDevTrib(String vDevTrib) { this.vDevTrib = vDevTrib; }
    public String getVIBSMun() { return vIBSMun; }
    public void setVIBSMun(String vIBSMun) { this.vIBSMun = vIBSMun; }
  }

  /** Grupo de totais da CBS dentro de {@code TIBSCBSMonoTot}. */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "gCBSTot",
      namespace = NfeNamespace.NS,
      propOrder = {"vDif", "vDevTrib", "vCBS", "vCredPres", "vCredPresCondSus"})
  public static class GCbsTot {

    @XmlElement(name = "vDif", namespace = NfeNamespace.NS, required = true)
    private String vDif;

    @XmlElement(name = "vDevTrib", namespace = NfeNamespace.NS, required = true)
    private String vDevTrib;

    @XmlElement(name = "vCBS", namespace = NfeNamespace.NS, required = true)
    private String vCBS;

    /** Total do Crédito Presumido CBS (obrigatório no grupo gCBS de TIBSCBSMonoTot). */
    @XmlElement(name = "vCredPres", namespace = NfeNamespace.NS, required = true)
    private String vCredPres;

    /** Total do Crédito Presumido CBS em Condição Suspensiva (obrigatório). */
    @XmlElement(name = "vCredPresCondSus", namespace = NfeNamespace.NS, required = true)
    private String vCredPresCondSus;

    public String getVDif() { return vDif; }
    public void setVDif(String vDif) { this.vDif = vDif; }
    public String getVDevTrib() { return vDevTrib; }
    public void setVDevTrib(String vDevTrib) { this.vDevTrib = vDevTrib; }
    public String getVCBS() { return vCBS; }
    public void setVCBS(String vCBS) { this.vCBS = vCBS; }
    public String getVCredPres() { return vCredPres; }
    public void setVCredPres(String vCredPres) { this.vCredPres = vCredPres; }
    public String getVCredPresCondSus() { return vCredPresCondSus; }
    public void setVCredPresCondSus(String v) { this.vCredPresCondSus = v; }
  }
}
