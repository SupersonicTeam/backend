package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Tributos incidentes em um item da NF-e (grupo {@code imposto} no XSD leiauteNFe_v4.00).
 *
 * <p>No contexto do AgroContábil (Sprint 2, ano-teste 2026), o grupo {@code ICMS} não é
 * preenchido — a nota fiscal de produtor rural na fase de transição informa apenas o
 * grupo {@code IBSCBS} (IBS/CBS). O ICMS é informado em notas que transitam entre
 * regimes tributários distintos, o que não é o escopo desta sprint.
 *
 * <p>Débito técnico: grupos ICMS, IPI, PIS, COFINS ausentes nesta implementação.
 * Serão necessários para notas com ICMS, conforme sprint futura.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "imposto",
    namespace = NfeNamespace.NS,
    propOrder = {"ibsCbs"})
public class Imposto {

  /**
   * Grupo IBS/CBS do item (elemento {@code IBSCBS}, tipo {@code TTribNFe},
   * minOccurs=0 no XSD).
   */
  @XmlElement(name = "IBSCBS", namespace = NfeNamespace.NS)
  private IbsCbs ibsCbs;

  public IbsCbs getIbsCbs() { return ibsCbs; }
  public void setIbsCbs(IbsCbs ibsCbs) { this.ibsCbs = ibsCbs; }
}
