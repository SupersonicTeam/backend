package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Grupo de identificação da NF-e (grupo {@code ide} no XSD leiauteNFe_v4.00).
 *
 * <p>Contém os campos de identificação do documento fiscal, incluindo código da UF,
 * código numérico, natureza da operação, modelo, série, número, data/hora de emissão,
 * tipo de operação, formato de impressão, forma de emissão, dígito verificador,
 * ambiente, finalidade, indicadores de consumidor final e presença, processo de
 * emissão e versão do processo.
 *
 * <p>Ordem dos campos ({@code propOrder}) segue exatamente a sequência definida no
 * XSD {@code leiauteNFe_v4.00.xsd} — qualquer alteração invalida o XML.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "ide",
    namespace = NfeNamespace.NS,
    propOrder = {
      "cUF", "cNF", "natOp", "mod", "serie", "nNF", "dhEmi", "dhSaiEnt",
      "tpNF", "idDest", "cMunFG", "tpImp", "tpEmis", "cDV", "tpAmb",
      "finNFe", "indFinal", "indPres", "procEmi", "verProc"
    })
public class Ide {

  /** Código da UF do emitente (tabela IBGE). Ex.: 41 = Paraná. */
  @XmlElement(name = "cUF", namespace = NfeNamespace.NS, required = true)
  private String cUF;

  /** Código numérico da chave de acesso (8 dígitos). */
  @XmlElement(name = "cNF", namespace = NfeNamespace.NS, required = true)
  private String cNF;

  /** Descrição da natureza da operação (max 60 chars). */
  @XmlElement(name = "natOp", namespace = NfeNamespace.NS, required = true)
  private String natOp;

  /** Modelo do documento fiscal: {@code "55"} para NF-e. */
  @XmlElement(name = "mod", namespace = NfeNamespace.NS, required = true)
  private String mod;

  /** Série do documento fiscal (zero-padded 3 dígitos). */
  @XmlElement(name = "serie", namespace = NfeNamespace.NS, required = true)
  private String serie;

  /** Número do documento fiscal (zero-padded 9 dígitos). */
  @XmlElement(name = "nNF", namespace = NfeNamespace.NS, required = true)
  private String nNF;

  /** Data e hora de emissão no formato {@code AAAA-MM-DDThh:mm:ssTZD}. */
  @XmlElement(name = "dhEmi", namespace = NfeNamespace.NS, required = true)
  private String dhEmi;

  /** Data e hora de saída/entrada (opcional). Mesmo formato de {@code dhEmi}. */
  @XmlElement(name = "dhSaiEnt", namespace = NfeNamespace.NS)
  private String dhSaiEnt;

  /** Tipo de documento fiscal: {@code "0"} = entrada, {@code "1"} = saída. */
  @XmlElement(name = "tpNF", namespace = NfeNamespace.NS, required = true)
  private String tpNF;

  /** Identificador do destino: {@code "1"} interna, {@code "2"} interestadual. */
  @XmlElement(name = "idDest", namespace = NfeNamespace.NS, required = true)
  private String idDest;

  /** Código do município do fato gerador (7 dígitos IBGE). */
  @XmlElement(name = "cMunFG", namespace = NfeNamespace.NS, required = true)
  private String cMunFG;

  /** Formato de impressão do DANFE: {@code "1"} = retrato. */
  @XmlElement(name = "tpImp", namespace = NfeNamespace.NS, required = true)
  private String tpImp;

  /** Forma de emissão: {@code "1"} = normal. */
  @XmlElement(name = "tpEmis", namespace = NfeNamespace.NS, required = true)
  private String tpEmis;

  /** Dígito verificador da chave de acesso (1 dígito). */
  @XmlElement(name = "cDV", namespace = NfeNamespace.NS, required = true)
  private String cDV;

  /** Ambiente: {@code "1"} = produção, {@code "2"} = homologação. */
  @XmlElement(name = "tpAmb", namespace = NfeNamespace.NS, required = true)
  private String tpAmb;

  /** Finalidade: {@code "1"} = NF-e normal. */
  @XmlElement(name = "finNFe", namespace = NfeNamespace.NS, required = true)
  private String finNFe;

  /** Indicador de consumidor final: {@code "0"} não, {@code "1"} sim. */
  @XmlElement(name = "indFinal", namespace = NfeNamespace.NS, required = true)
  private String indFinal;

  /** Indicador de presença: {@code "9"} = não presencial. */
  @XmlElement(name = "indPres", namespace = NfeNamespace.NS, required = true)
  private String indPres;

  /** Processo de emissão: {@code "0"} = aplicativo do contribuinte. */
  @XmlElement(name = "procEmi", namespace = NfeNamespace.NS, required = true)
  private String procEmi;

  /** Versão do processo de emissão (ex.: {@code "1.0"}). */
  @XmlElement(name = "verProc", namespace = NfeNamespace.NS, required = true)
  private String verProc;

  public String getCUF() { return cUF; }
  public void setCUF(String cUF) { this.cUF = cUF; }
  public String getCNF() { return cNF; }
  public void setCNF(String cNF) { this.cNF = cNF; }
  public String getNatOp() { return natOp; }
  public void setNatOp(String natOp) { this.natOp = natOp; }
  public String getMod() { return mod; }
  public void setMod(String mod) { this.mod = mod; }
  public String getSerie() { return serie; }
  public void setSerie(String serie) { this.serie = serie; }
  public String getNNF() { return nNF; }
  public void setNNF(String nNF) { this.nNF = nNF; }
  public String getDhEmi() { return dhEmi; }
  public void setDhEmi(String dhEmi) { this.dhEmi = dhEmi; }
  public String getDhSaiEnt() { return dhSaiEnt; }
  public void setDhSaiEnt(String dhSaiEnt) { this.dhSaiEnt = dhSaiEnt; }
  public String getTpNF() { return tpNF; }
  public void setTpNF(String tpNF) { this.tpNF = tpNF; }
  public String getIdDest() { return idDest; }
  public void setIdDest(String idDest) { this.idDest = idDest; }
  public String getCMunFG() { return cMunFG; }
  public void setCMunFG(String cMunFG) { this.cMunFG = cMunFG; }
  public String getTpImp() { return tpImp; }
  public void setTpImp(String tpImp) { this.tpImp = tpImp; }
  public String getTpEmis() { return tpEmis; }
  public void setTpEmis(String tpEmis) { this.tpEmis = tpEmis; }
  public String getCDV() { return cDV; }
  public void setCDV(String cDV) { this.cDV = cDV; }
  public String getTpAmb() { return tpAmb; }
  public void setTpAmb(String tpAmb) { this.tpAmb = tpAmb; }
  public String getFinNFe() { return finNFe; }
  public void setFinNFe(String finNFe) { this.finNFe = finNFe; }
  public String getIndFinal() { return indFinal; }
  public void setIndFinal(String indFinal) { this.indFinal = indFinal; }
  public String getIndPres() { return indPres; }
  public void setIndPres(String indPres) { this.indPres = indPres; }
  public String getProcEmi() { return procEmi; }
  public void setProcEmi(String procEmi) { this.procEmi = procEmi; }
  public String getVerProc() { return verProc; }
  public void setVerProc(String verProc) { this.verProc = verProc; }
}
