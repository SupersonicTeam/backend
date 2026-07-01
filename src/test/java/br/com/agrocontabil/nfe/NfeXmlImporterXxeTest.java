package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.agrocontabil.exception.XmlImportacaoInvalidaException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Testes de seguranca (prevencao de XXE) do {@link NfeXmlImporter} (issue #19).
 *
 * <p>Um XML importado com entidade externa maliciosa deve ser <strong>bloqueado</strong> —
 * sem vazar o conteudo do arquivo alvo nem realizar acesso a rede/disco a partir do documento.
 */
class NfeXmlImporterXxeTest {

  // XsdValidator real; o XML malicioso e barrado antes de chegar ao XSD (DOCTYPE proibido).
  private final NfeXmlImporter importer = new NfeXmlImporter(new XsdValidator());

  @Test
  @DisplayName("XML com entidade externa (XXE) e bloqueado e nao vaza o arquivo alvo")
  void xmlComEntidadeExternaEhBloqueado(@TempDir Path tempDir) throws Exception {
    Path segredo = tempDir.resolve("segredo.txt");
    String conteudoSecreto = "TOP-SECRET-DO-SISTEMA-1234";
    Files.writeString(segredo, conteudoSecreto);

    String uri = segredo.toUri().toString();
    String xxe = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE NFe [ <!ENTITY xxe SYSTEM "%s"> ]>
        <NFe xmlns="http://www.portalfiscal.inf.br/nfe">
          <infNFe Id="NFe00000000000000000000000000000000000000000000" versao="4.00">
            <ide><natOp>&xxe;</natOp></ide>
          </infNFe>
        </NFe>
        """.formatted(uri);

    Throwable erro = catchThrowable(() -> importer.importar(xxe.getBytes(StandardCharsets.UTF_8)));

    // Deve ser rejeitado (DOCTYPE proibido) — nunca sucesso, nunca 500.
    assertThat(erro).isInstanceOf(XmlImportacaoInvalidaException.class);
    // Garante que o conteudo do arquivo secreto nao vazou na mensagem de erro.
    assertThat(erro.getMessage()).doesNotContain(conteudoSecreto);
  }

  @Test
  @DisplayName("XML malformado e rejeitado como importacao invalida")
  void xmlMalformadoEhRejeitado() {
    byte[] lixo = "<isto nao e xml valido".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> importer.importar(lixo))
        .isInstanceOf(XmlImportacaoInvalidaException.class);
  }

  @Test
  @DisplayName("XML sem elemento NFe e rejeitado")
  void xmlSemNfeEhRejeitado() {
    String semNfe = """
        <?xml version="1.0" encoding="UTF-8"?>
        <outroDocumento xmlns="http://exemplo/ns"><campo>x</campo></outroDocumento>
        """;
    Throwable erro =
        catchThrowable(() -> importer.importar(semNfe.getBytes(StandardCharsets.UTF_8)));

    assertThat(erro).isInstanceOf(XmlImportacaoInvalidaException.class);
    XmlImportacaoInvalidaException ex = (XmlImportacaoInvalidaException) erro;
    assertThat(ex.getErrors()).isNotEmpty();
    assertThat(ex.getErrors().get(0).message()).contains("NFe");
  }

  @Test
  @DisplayName("Arquivo vazio e rejeitado")
  void arquivoVazioEhRejeitado() {
    assertThatThrownBy(() -> importer.importar(new byte[0]))
        .isInstanceOf(XmlImportacaoInvalidaException.class);
  }
}
