package br.com.agrocontabil;

import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoProdutor;
import java.util.UUID;

/**
 * Fixtures de {@link Produtor} para os fluxos de emissão de NF-e (Onda 2, issue #88).
 *
 * <p>Desde a #88 o {@code FiscalRulesValidator} exige IE e endereço completo do emitente: sem uma
 * fixture compartilhada, cada teste de emissão precisaria repetir os 8 campos. As variantes
 * inválidas existem para os testes de validação (422).
 *
 * <p>Os valores válidos são os mesmos semeados pelo {@code DataLoader} (Cuiabá/MT) — uma fonte de
 * verdade só, para que o que passa no teste seja o que roda em dev.
 */
public final class ProdutorTestFixtures {

  /** UF do produtor das fixtures. */
  public static final String UF = "MT";
  /** Código IBGE de Cuiabá/MT — prefixo {@code 51} coerente com a UF. */
  public static final String CODIGO_MUNICIPIO_IBGE = "5103403";
  /** IE de teste (sem DV nacional padronizado — só o formato é validado). */
  public static final String INSCRICAO_ESTADUAL = "1234567890";

  private ProdutorTestFixtures() {
  }

  /** Produtor com IE e endereço completos — passa no {@code FiscalRulesValidator}. */
  public static Produtor produtorCompleto() {
    return Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("Joao da Silva Produtor")
        .cpfCnpj("52998224725")
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .estado(UF)
        .inscricaoEstadual(INSCRICAO_ESTADUAL)
        .endereco(enderecoCompleto())
        .build();
  }

  /** Endereço completo e coerente com a UF das fixtures. */
  public static EnderecoProdutor enderecoCompleto() {
    return EnderecoProdutor.builder()
        .logradouro("Avenida Historiador Rubens de Mendonca")
        .numero("1894")
        .bairro("Bosque da Saude")
        .codigoMunicipioIbge(CODIGO_MUNICIPIO_IBGE)
        .municipio("Cuiaba")
        .cep("78050000")
        .build();
  }

  /**
   * Endereço completo em Curitiba/PR (IBGE {@code 4106902}) — para os testes cuja operação já
   * era modelada com emitente no PR, preservando o âmbito do CFOP.
   */
  public static EnderecoProdutor enderecoCuritibaPr() {
    return EnderecoProdutor.builder()
        .logradouro("Rua Barao do Rio Branco")
        .numero("370")
        .bairro("Centro")
        .codigoMunicipioIbge("4106902")
        .municipio("Curitiba")
        .cep("80010180")
        .build();
  }

  /** Produtor sem IE — inválido para NF-e modelo 55, válido para NFA (emite {@code ISENTO}). */
  public static Produtor produtorSemIe() {
    Produtor produtor = produtorCompleto();
    produtor.setInscricaoEstadual(null);
    return produtor;
  }

  /** Produtor com endereço faltando logradouro, bairro e CEP — inválido para emissão. */
  public static Produtor produtorEnderecoIncompleto() {
    Produtor produtor = produtorCompleto();
    produtor.setEndereco(EnderecoProdutor.builder()
        .numero("1894")
        .codigoMunicipioIbge(CODIGO_MUNICIPIO_IBGE)
        .municipio("Cuiaba")
        .build());
    return produtor;
  }

  /** Produtor de MT com código IBGE de São Paulo capital ({@code 3550308}) — incoerente. */
  public static Produtor produtorIbgeIncoerente() {
    Produtor produtor = produtorCompleto();
    produtor.getEndereco().setCodigoMunicipioIbge("3550308");
    return produtor;
  }
}
