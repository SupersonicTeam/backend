package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Dados de transporte da NF-e (elemento {@code transp} no XSD leiauteNFe_v4.00).
 *
 * <p>O campo {@code modFrete} é o único obrigatório neste grupo. Valores possíveis:
 * <ul>
 *   <li>{@code "0"} = CIF (por conta do remetente)</li>
 *   <li>{@code "1"} = FOB (por conta do destinatário)</li>
 *   <li>{@code "9"} = sem transporte</li>
 * </ul>
 * Esta implementação usa {@code "9"} como padrão (produtor rural entrega direta).
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
    name = "transp",
    namespace = NfeNamespace.NS,
    propOrder = {"modFrete"})
public class Transp {

  /** Modalidade do frete (obrigatório). Default {@code "9"} = sem transporte. */
  @XmlElement(name = "modFrete", namespace = NfeNamespace.NS, required = true)
  private String modFrete;

  public String getModFrete() { return modFrete; }
  public void setModFrete(String modFrete) { this.modFrete = modFrete; }
}
