package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Identificação do emitente da NF-e (grupo {@code emit} no XSD leiauteNFe_v4.00).
 *
 * <p>O emitente pode ser pessoa jurídica (CNPJ, 14 dígitos) ou pessoa física (CPF,
 * 11 dígitos zero-padded para 14). No layout, são campos mutuamente exclusivos
 * (xs:choice); nesta classe, apenas um deles deve ser preenchido — o mapper aplica
 * essa regra.
 *
 * <p>Campos obrigatórios pelo XSD: {@code xNome}, {@code enderEmit}, {@code IE}, {@code CRT}.
 * O campo {@code xFant} é opcional.
 *
 * <p>O CRT (Código de Regime Tributário) é mapeado a partir de
 * {@link br.com.agrocontabil.model.RegimeTributario} do Produtor:
 * Simples Nacional → "1"; Regime Normal → "3". Outros casos são mapeados para "3"
 * como fallback seguro.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "emit",
    namespace = NfeNamespace.NS,
    propOrder = {"cnpj", "cpf", "xNome", "xFant", "enderEmit", "ie", "crt"})
public class Emit {

  /**
   * CNPJ do emitente (14 dígitos). Preenchido quando o produtor possui CNPJ.
   * Mutuamente exclusivo com {@code cpf}.
   */
  @XmlElement(name = "CNPJ", namespace = NfeNamespace.NS)
  private String cnpj;

  /**
   * CPF do emitente (11 dígitos). Preenchido quando o produtor é pessoa física.
   * Mutuamente exclusivo com {@code cnpj}.
   */
  @XmlElement(name = "CPF", namespace = NfeNamespace.NS)
  private String cpf;

  /** Razão social ou nome do emitente (max 60 chars). */
  @XmlElement(name = "xNome", namespace = NfeNamespace.NS, required = true)
  private String xNome;

  /** Nome fantasia (opcional, max 60 chars). */
  @XmlElement(name = "xFant", namespace = NfeNamespace.NS)
  private String xFant;

  /** Endereço do emitente. */
  @XmlElement(name = "enderEmit", namespace = NfeNamespace.NS, required = true)
  private EnderEmit enderEmit;

  /** Inscrição Estadual do emitente. */
  @XmlElement(name = "IE", namespace = NfeNamespace.NS, required = true)
  private String ie;

  /**
   * Código de Regime Tributário:
   * {@code "1"} = Simples Nacional, {@code "2"} = SN excesso,
   * {@code "3"} = Regime Normal, {@code "4"} = MEI.
   */
  @XmlElement(name = "CRT", namespace = NfeNamespace.NS, required = true)
  private String crt;

  public String getCnpj() { return cnpj; }
  public void setCnpj(String cnpj) { this.cnpj = cnpj; }
  public String getCpf() { return cpf; }
  public void setCpf(String cpf) { this.cpf = cpf; }
  public String getXNome() { return xNome; }
  public void setXNome(String xNome) { this.xNome = xNome; }
  public String getXFant() { return xFant; }
  public void setXFant(String xFant) { this.xFant = xFant; }
  public EnderEmit getEnderEmit() { return enderEmit; }
  public void setEnderEmit(EnderEmit enderEmit) { this.enderEmit = enderEmit; }
  public String getIe() { return ie; }
  public void setIe(String ie) { this.ie = ie; }
  public String getCrt() { return crt; }
  public void setCrt(String crt) { this.crt = crt; }
}
