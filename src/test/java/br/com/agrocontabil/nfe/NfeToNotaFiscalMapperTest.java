package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.nfe.NfeXmlImporter.ResultadoImportacao;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes do mapper reverso {@link NfeToNotaFiscalMapper} (issue #19): NFe importada →
 * {@link NotaFiscal}, com o produtor autenticado como destinatario e o emitente do XML como
 * terceiro.
 */
class NfeToNotaFiscalMapperTest {

  private final NfeXmlImporter importer = new NfeXmlImporter(new XsdValidator());
  private final NfeToNotaFiscalMapper mapper = new NfeToNotaFiscalMapper();

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("JOAO DA SILVA PRODUTOR")
        .cpfCnpj("529.982.247-25")
        .estado("SC")
        .build();
  }

  @Test
  @DisplayName("Mapeia nfeProc: origem IMPORTADA, PENDENTE_SEFAZ (o protNFe do upload nao prova "
      + "nada, #223), emitente terceiro")
  void mapeiaNfeProcAutorizado() {
    ResultadoImportacao r = importer.importar(
        NfeImportTestFixtures.comoNfeProcAutorizada(NfeImportTestFixtures.gerarNfeAssinada())
            .getBytes(StandardCharsets.UTF_8));

    NotaFiscal nota = mapper.toNotaFiscal(r.nfe(), produtor, r.nfeXml());

    assertThat(nota.getOrigem()).isEqualTo(OrigemNotaFiscal.IMPORTADA);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    assertThat(nota.getProtocoloAutorizacao()).isNull();
    // Produtor autenticado e o dono/destinatario.
    assertThat(nota.getProdutor()).isSameAs(produtor);
    // Emitente terceiro nas colunas dedicadas.
    assertThat(nota.getEmitenteNome()).isEqualTo(NfeImportTestFixtures.EMITENTE_NOME);
    assertThat(nota.getEmitenteCpfCnpj()).isEqualTo(NfeImportTestFixtures.EMITENTE_CNPJ);
    // Numero/serie/chave do emitente terceiro.
    assertThat(nota.getNumero()).isEqualTo(NfeImportTestFixtures.NUMERO);
    assertThat(nota.getSerie()).isEqualTo(NfeImportTestFixtures.SERIE);
    assertThat(nota.getChaveAcesso()).hasSize(44);
    // Item mapeado com NCM/CFOP informativos e valores.
    assertThat(nota.getItens()).hasSize(1);
    assertThat(nota.getItens().get(0).getNcm()).isEqualTo("31052000");
    assertThat(nota.getItens().get(0).getCfop()).isEqualTo("6101");
    assertThat(nota.getValorTotalProdutos()).isEqualByComparingTo(new BigDecimal("1750.00"));
    // XML preservado para auditoria.
    assertThat(nota.getXmlGerado()).contains("Signature");
  }

  @Test
  @DisplayName("NFe pura (sem protocolo) tambem entra PENDENTE_SEFAZ, nunca XML_GERADO (#223)")
  void mapeiaNfePuraComoNeutra() {
    ResultadoImportacao r = importer.importar(
        NfeImportTestFixtures.gerarNfeAssinada().getBytes(StandardCharsets.UTF_8));

    NotaFiscal nota = mapper.toNotaFiscal(r.nfe(), produtor, r.nfeXml());

    assertThat(nota.getOrigem()).isEqualTo(OrigemNotaFiscal.IMPORTADA);
    // XML_GERADO liberaria "transmitir" o XML de outro emitente com o certificado do produtor.
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
      // caso | grupo ICMS no XML do fornecedor | CST lido | origem lida
      "ICMS40 do fixture | | 41 | 0",
      "ICMS00 com valores | <ICMS><ICMS00><orig>1</orig><CST>00</CST><modBC>3</modBC>"
          + "<vBC>1750.00</vBC><pICMS>12.00</pICMS><vICMS>210.00</vICMS></ICMS00></ICMS> | 00 | 1",
      "Simples (CSOSN) | <ICMS><ICMSSN102><orig>0</orig><CSOSN>102</CSOSN></ICMSSN102></ICMS>"
          + " | 102 | 0",
      "sem o grupo (nota do terceiro segue aceita) | SEM | | "
  })
  @DisplayName("Le o CST (ou CSOSN) e a origem do ICMS do XML do terceiro, so para informar")
  void leIcmsDoTerceiro(String caso, String grupo, String cst, String origem) {
    String xml = NfeImportTestFixtures.gerarNfeAssinada();
    String doFixture = "<ICMS><ICMS40><orig>0</orig><CST>41</CST></ICMS40></ICMS>";
    assertThat(xml).contains(doFixture);
    if (grupo != null) {
      xml = xml.replace(doFixture, "SEM".equals(grupo) ? "" : grupo);
    }
    ResultadoImportacao r = importer.importar(xml.getBytes(StandardCharsets.UTF_8));

    NotaFiscal nota = mapper.toNotaFiscal(r.nfe(), produtor, r.nfeXml());

    assertThat(nota.getItens().get(0).getCstIcms()).isEqualTo(cst);
    assertThat(nota.getItens().get(0).getOrigemIcms()).isEqualTo(origem);
    assertThat(nota.getValorTotalProdutos()).isEqualByComparingTo(new BigDecimal("1750.00"));
  }
}
