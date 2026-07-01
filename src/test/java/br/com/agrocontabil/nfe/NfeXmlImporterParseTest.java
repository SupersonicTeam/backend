package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.nfe.NfeXmlImporter.ResultadoImportacao;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes de parsing/validacao do {@link NfeXmlImporter} com um XML de NF-e real, assinado e
 * valido no XSD (gerado pelo proprio pipeline de emissao) — issue #19.
 */
class NfeXmlImporterParseTest {

  private final NfeXmlImporter importer = new NfeXmlImporter(new XsdValidator());

  @Test
  @DisplayName("Importa um nfeProc autorizado (cStat=100) e desserializa a NFe")
  void importaNfeProcAutorizado() {
    String nfe = NfeImportTestFixtures.gerarNfeAssinada();
    String nfeProc = NfeImportTestFixtures.comoNfeProcAutorizada(nfe);

    ResultadoImportacao resultado =
        importer.importar(nfeProc.getBytes(StandardCharsets.UTF_8));

    assertThat(resultado.nfe()).isNotNull();
    assertThat(resultado.nfe().getInfNFe()).isNotNull();
    assertThat(resultado.nfe().getInfNFe().getId()).startsWith("NFe");
    assertThat(resultado.nfe().getInfNFe().getEmit().getCnpj())
        .isEqualTo(NfeImportTestFixtures.EMITENTE_CNPJ);
    assertThat(resultado.nfe().getInfNFe().getIde().getNNF())
        .isEqualTo(String.valueOf(NfeImportTestFixtures.NUMERO));
    assertThat(resultado.nfe().getInfNFe().getDet()).hasSize(1);
    // nfeProc com cStat=100 → autorizada.
    assertThat(resultado.autorizada()).isTrue();
    // O XML da NFe preserva a assinatura (usada para dedup/auditoria).
    assertThat(resultado.nfeXml()).contains("Signature");
  }

  @Test
  @DisplayName("Importa um <NFe> puro (sem protocolo) — status neutro (nao autorizada)")
  void importaNfePura() {
    String nfe = NfeImportTestFixtures.gerarNfeAssinada();

    ResultadoImportacao resultado = importer.importar(nfe.getBytes(StandardCharsets.UTF_8));

    assertThat(resultado.nfe().getInfNFe().getEmit().getCnpj())
        .isEqualTo(NfeImportTestFixtures.EMITENTE_CNPJ);
    // Sem protNFe → nao autorizada.
    assertThat(resultado.autorizada()).isFalse();
  }
}
