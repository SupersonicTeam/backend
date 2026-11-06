package br.com.agrocontabil.nfe.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Grupo {@code ICMS} do item (MOC 7.00, Anexo I, N01: um, e só um, dos grupos de tributação).
 *
 * <p>Na emissão, o sistema monta só os grupos que não exigem o cálculo do ICMS: {@code ICMS40}
 * (CST 40, 41 e 50), {@code ICMS51} e {@code ICMS90}, com {@code orig} e {@code CST}
 * ({@code nfe/TributacaoIcms}). Na importação, qualquer grupo do leiaute é lido para guardar a
 * origem e o CST (ou o CSOSN) da nota do terceiro; os demais campos do grupo são ignorados pelo
 * unmarshaller. Cada campo é um elemento da escolha do XSD; no máximo um vem preenchido.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "ICMS", namespace = NfeNamespace.NS, propOrder = {
    "gICMS00", "gICMS02", "gICMS10", "gICMS15", "gICMS20", "gICMS30", "gICMS40", "gICMS51",
    "gICMS53", "gICMS60", "gICMS61", "gICMS70", "gICMS90", "gICMSPart", "gICMSST", "gICMSSN101",
    "gICMSSN102", "gICMSSN201", "gICMSSN202", "gICMSSN500", "gICMSSN900"})
public class Icms {

  @XmlElement(name = "ICMS00", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS00;
  @XmlElement(name = "ICMS02", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS02;
  @XmlElement(name = "ICMS10", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS10;
  @XmlElement(name = "ICMS15", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS15;
  @XmlElement(name = "ICMS20", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS20;
  @XmlElement(name = "ICMS30", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS30;
  @XmlElement(name = "ICMS40", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS40;
  @XmlElement(name = "ICMS51", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS51;
  @XmlElement(name = "ICMS53", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS53;
  @XmlElement(name = "ICMS60", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS60;
  @XmlElement(name = "ICMS61", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS61;
  @XmlElement(name = "ICMS70", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS70;
  @XmlElement(name = "ICMS90", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMS90;
  @XmlElement(name = "ICMSPart", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMSPart;
  @XmlElement(name = "ICMSST", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMSST;
  @XmlElement(name = "ICMSSN101", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMSSN101;
  @XmlElement(name = "ICMSSN102", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMSSN102;
  @XmlElement(name = "ICMSSN201", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMSSN201;
  @XmlElement(name = "ICMSSN202", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMSSN202;
  @XmlElement(name = "ICMSSN500", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMSSN500;
  @XmlElement(name = "ICMSSN900", namespace = NfeNamespace.NS)
  private IcmsGrupo gICMSSN900;

  /**
   * O grupo preenchido.
   *
   * @return o grupo, ou vazio se nenhum veio
   */
  public Optional<IcmsGrupo> grupo() {
    return Arrays.stream(new IcmsGrupo[] {
        gICMS00,
        gICMS02,
        gICMS10,
        gICMS15,
        gICMS20,
        gICMS30,
        gICMS40,
        gICMS51,
        gICMS53,
        gICMS60,
        gICMS61,
        gICMS70,
        gICMS90,
        gICMSPart,
        gICMSST,
        gICMSSN101,
        gICMSSN102,
        gICMSSN201,
        gICMSSN202,
        gICMSSN500,
        gICMSSN900})
        .filter(Objects::nonNull).findFirst();
  }

  /**
   * Grupo de emissão pelo CST: {@code ICMS40} para 40, 41 e 50; {@code ICMS51}; {@code ICMS90}.
   *
   * @param origem origem da mercadoria
   * @param cst    CST (40, 41, 50, 51 ou 90)
   * @return o grupo {@code ICMS} com orig e CST
   * @throws IllegalArgumentException para CST que exige o cálculo do ICMS
   */
  public static Icms semCalculo(String origem, String cst) {
    Icms icms = new Icms();
    IcmsGrupo grupo = new IcmsGrupo(origem, cst);
    switch (cst) {
      case "40", "41", "50" -> icms.gICMS40 = grupo;
      case "51" -> icms.gICMS51 = grupo;
      case "90" -> icms.gICMS90 = grupo;
      default -> throw new IllegalArgumentException(
          "CST " + cst + " exige o calculo do ICMS, fora do escopo do sistema");
    }
    return icms;
  }
}
