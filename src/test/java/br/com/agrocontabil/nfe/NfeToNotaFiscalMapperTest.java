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
  @DisplayName("Mapeia nfeProc autorizado: origem IMPORTADA, status AUTORIZADA, emitente terceiro")
  void mapeiaNfeProcAutorizado() {
    ResultadoImportacao r = importer.importar(
        NfeImportTestFixtures.comoNfeProcAutorizada(NfeImportTestFixtures.gerarNfeAssinada())
            .getBytes(StandardCharsets.UTF_8));

    NotaFiscal nota = mapper.toNotaFiscal(r.nfe(), produtor, r.nfeXml(), r.autorizada());

    assertThat(nota.getOrigem()).isEqualTo(OrigemNotaFiscal.IMPORTADA);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
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
  @DisplayName("NFe pura (sem protocolo) mapeia para status neutro XML_GERADO")
  void mapeiaNfePuraComoNeutra() {
    ResultadoImportacao r = importer.importar(
        NfeImportTestFixtures.gerarNfeAssinada().getBytes(StandardCharsets.UTF_8));

    NotaFiscal nota = mapper.toNotaFiscal(r.nfe(), produtor, r.nfeXml(), r.autorizada());

    assertThat(nota.getOrigem()).isEqualTo(OrigemNotaFiscal.IMPORTADA);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.XML_GERADO);
  }
}
