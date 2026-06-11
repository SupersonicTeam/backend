package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Endereço do emitente (tipo {@code TEnderEmi} no XSD leiauteNFe_v4.00).
 *
 * <p>Campos obrigatórios: {@code xLgr}, {@code nro}, {@code xBairro},
 * {@code cMun}, {@code xMun}, {@code UF}, {@code CEP}.
 * Campos opcionais: {@code xCpl}.
 *
 * <p>Nota de débito técnico: o {@link br.com.agrocontabil.model.Produtor} não
 * armazena todos esses campos (apenas {@code estado}). O mapper preenche com
 * valores placeholder ("SEM ENDERECO", "S/N", "CENTRO", "00000000") para
 * satisfazer o XSD, sinalizando que endereço completo do emitente é um
 * requisito de sprint futura.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "TEnderEmi",
    namespace = NfeNamespace.NS,
    propOrder = {"xLgr", "nro", "xCpl", "xBairro", "cMun", "xMun", "uf", "cep"})
public class EnderEmit {

  @XmlElement(name = "xLgr", namespace = NfeNamespace.NS, required = true)
  private String xLgr;

  @XmlElement(name = "nro", namespace = NfeNamespace.NS, required = true)
  private String nro;

  @XmlElement(name = "xCpl", namespace = NfeNamespace.NS)
  private String xCpl;

  @XmlElement(name = "xBairro", namespace = NfeNamespace.NS, required = true)
  private String xBairro;

  @XmlElement(name = "cMun", namespace = NfeNamespace.NS, required = true)
  private String cMun;

  @XmlElement(name = "xMun", namespace = NfeNamespace.NS, required = true)
  private String xMun;

  @XmlElement(name = "UF", namespace = NfeNamespace.NS, required = true)
  private String uf;

  @XmlElement(name = "CEP", namespace = NfeNamespace.NS, required = true)
  private String cep;

  public String getXLgr() { return xLgr; }
  public void setXLgr(String xLgr) { this.xLgr = xLgr; }
  public String getNro() { return nro; }
  public void setNro(String nro) { this.nro = nro; }
  public String getXCpl() { return xCpl; }
  public void setXCpl(String xCpl) { this.xCpl = xCpl; }
  public String getXBairro() { return xBairro; }
  public void setXBairro(String xBairro) { this.xBairro = xBairro; }
  public String getCMun() { return cMun; }
  public void setCMun(String cMun) { this.cMun = cMun; }
  public String getXMun() { return xMun; }
  public void setXMun(String xMun) { this.xMun = xMun; }
  public String getUf() { return uf; }
  public void setUf(String uf) { this.uf = uf; }
  public String getCep() { return cep; }
  public void setCep(String cep) { this.cep = cep; }
}
