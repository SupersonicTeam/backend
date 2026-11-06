package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Tributos incidentes em um item da NF-e (grupo {@code imposto} no XSD leiauteNFe_v4.00).
 *
 * <p><strong>ICMS:</strong> o grupo {@code ICMS} sai em todo item da NF-e emitida, com o CST
 * informado pelo usuário ou pelo contador ou o padrão do diferimento (CST 51) na venda interna de
 * produção própria a contribuinte ({@code nfe/TributacaoIcms}). A NT 2025.002 v1.53, regra B25-90,
 * rejeita (1002) a NF-e normal sem o grupo {@code ICMS} nem o {@code ISSQN}. O cálculo do ICMS
 * devido e o do ICMS-ST continuam fora do sistema (contador): só saem os grupos que não exigem
 * valores (40/41/50, 51 e 90), com o {@code ICMSTot} zerado.
 *
 * <p>Ordem do XSD: {@code vTotTrib}, {@code ICMS} (escolha com IPI/II ou ISSQN), PIS, COFINS,
 * ICMSUFDest, IS e {@code IBSCBS}: o ICMS vem antes do IBS/CBS no mesmo {@code imposto}.
 *
 * <p>Débito técnico: IPI, PIS e COFINS ausentes (o produtor rural não os destaca na venda).
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "imposto",
    namespace = NfeNamespace.NS,
    propOrder = {"icms", "ibsCbs"})
public class Imposto {

  /** Grupo ICMS do item (minOccurs=0 no XSD, mas obrigatório pela regra B25-90 na NF-e normal). */
  @XmlElement(name = "ICMS", namespace = NfeNamespace.NS)
  private Icms icms;

  /**
   * Grupo IBS/CBS do item (elemento {@code IBSCBS}, tipo {@code TTribNFe},
   * minOccurs=0 no XSD).
   */
  @XmlElement(name = "IBSCBS", namespace = NfeNamespace.NS)
  private IbsCbs ibsCbs;

  public Icms getIcms() { return icms; }
  public void setIcms(Icms icms) { this.icms = icms; }
  public IbsCbs getIbsCbs() { return ibsCbs; }
  public void setIbsCbs(IbsCbs ibsCbs) { this.ibsCbs = ibsCbs; }
}
