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
 *   <li>Detalhamento por UF/município em {@code gIBSCBS.gIBSUF.pIBSUF} e {@code gIBSMun.pIBSMun}:
 *       a entidade armazena apenas a alíquota total do IBS — a divisão UF×Município exigiria
 *       parametrização adicional (sprint futura)</li>
 * </ul>
 *
 * <p>Estratégia de mapeamento adotada: CST {@code "101"} (tributação plena normal pelo
 * IBS/CBS), usando {@code gIBSCBS} com alíquotas divididas igualmente entre UF e município
 * (50%/50% do IBS total) — valor meramente ilustrativo para homologação. O sistema de
 * produção precisará de configuração de alíquotas por UF e município.
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
   * Preenchido quando o CST indica tributação normal (ex.: {@code "101"}).
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
   * <p>Débito técnico: as alíquotas por UF ({@code pIBSUF}) e por município
   * ({@code pIBSMun}) são derivadas da alíquota total de IBS do item dividida
   * igualmente (50%/50%), pois a entidade não discrimina as parcelas. A divisão
   * real exige configuração por UF/município — sprint futura.
   */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "TCIBS",
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
      propOrder = {"pIBSUF", "vIBSUF"})
  public static class GIbsUf {

    /** Alíquota do IBS estadual em percentual (ex.: {@code "0.0500"} = 0,05%). */
    @XmlElement(name = "pIBSUF", namespace = NfeNamespace.NS, required = true)
    private String pIBSUF;

    /** Valor do IBS estadual (2 casas decimais). */
    @XmlElement(name = "vIBSUF", namespace = NfeNamespace.NS, required = true)
    private String vIBSUF;

    public String getPIBSUF() { return pIBSUF; }
    public void setPIBSUF(String pIBSUF) { this.pIBSUF = pIBSUF; }
    public String getVIBSUF() { return vIBSUF; }
    public void setVIBSUF(String vIBSUF) { this.vIBSUF = vIBSUF; }
  }

  /** Grupo IBS de competência do Município (parte de {@code TCIBS}). */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "gIBSMun",
      namespace = NfeNamespace.NS,
      propOrder = {"pIBSMun", "vIBSMun"})
  public static class GIbsMun {

    /** Alíquota do IBS municipal em percentual. */
    @XmlElement(name = "pIBSMun", namespace = NfeNamespace.NS, required = true)
    private String pIBSMun;

    /** Valor do IBS municipal (2 casas decimais). */
    @XmlElement(name = "vIBSMun", namespace = NfeNamespace.NS, required = true)
    private String vIBSMun;

    public String getPIBSMun() { return pIBSMun; }
    public void setPIBSMun(String pIBSMun) { this.pIBSMun = pIBSMun; }
    public String getVIBSMun() { return vIBSMun; }
    public void setVIBSMun(String vIBSMun) { this.vIBSMun = vIBSMun; }
  }

  /** Grupo CBS federal (parte de {@code TCIBS}). */
  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlType(
      name = "gCBS",
      namespace = NfeNamespace.NS,
      propOrder = {"pCBS", "vCBS"})
  public static class GCbs {

    /** Alíquota da CBS em percentual (ex.: {@code "0.9000"} = 0,90%). */
    @XmlElement(name = "pCBS", namespace = NfeNamespace.NS, required = true)
    private String pCBS;

    /** Valor da CBS (2 casas decimais). */
    @XmlElement(name = "vCBS", namespace = NfeNamespace.NS, required = true)
    private String vCBS;

    public String getPCBS() { return pCBS; }
    public void setPCBS(String pCBS) { this.pCBS = pCBS; }
    public String getVCBS() { return vCBS; }
    public void setVCBS(String vCBS) { this.vCBS = vCBS; }
  }
}
