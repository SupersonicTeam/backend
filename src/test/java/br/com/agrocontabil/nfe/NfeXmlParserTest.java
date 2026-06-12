package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.service.CalculoIbsCbsService;
import br.com.agrocontabil.service.ChaveAcessoGenerator;
import br.com.agrocontabil.service.GeracaoNotaFiscalService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NfeXmlParser — parse XXE-safe + XSD + JAXB")
class NfeXmlParserTest {

  private NfeXmlParser parser;
  private String nfeXml; // XML real de uma NF-e (bare <NFe>)

  @BeforeEach
  void setUp() {
    parser = new NfeXmlParser(new XsdValidator());

    TributosProperties props = new TributosProperties();
    TributosProperties.Ibs ibs = new TributosProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TributosProperties.Cbs cbs = new TributosProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    CalculoIbsCbsService calculo = new CalculoIbsCbsService(props);

    GeracaoNotaFiscalService pipeline = new GeracaoNotaFiscalService(
        new FiscalRulesValidator(),
        new NotaFiscalNfeMapper(new ChaveAcessoGenerator()),
        new XmlGenerator(),
        new XsdValidator());

    NotaFiscal nota = buildNota();
    calculo.calcular(nota);
    nfeXml = pipeline.gerarXml(nota);
  }

  @Test
  @DisplayName("parseia XML de NFe puro e retorna o grafo JAXB")
  void parseNfePuro() {
    NFe nfe = parser.parse(nfeXml.getBytes(StandardCharsets.UTF_8));
    assertThat(nfe.getInfNFe()).isNotNull();
    assertThat(nfe.getInfNFe().getId()).startsWith("NFe");
    assertThat(nfe.getInfNFe().getEmit()).isNotNull();
  }

  @Test
  @DisplayName("parseia XML de NFe dentro de nfeProc")
  void parseNfeDentroDeNfeProc() {
    String semProlog = nfeXml.replaceFirst("<\\?xml[^>]*\\?>", "");
    String nfeProc =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<nfeProc versao=\"4.00\" xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
            + semProlog
            + "</nfeProc>";

    NFe nfe = parser.parse(nfeProc.getBytes(StandardCharsets.UTF_8));
    assertThat(nfe.getInfNFe()).isNotNull();
    assertThat(nfe.getInfNFe().getId()).startsWith("NFe");
  }

  @Test
  @DisplayName("XML malformado lanca XmlImportacaoException")
  void xmlMalformado() {
    assertThatThrownBy(() -> parser.parse("<isto nao e xml valido".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(XmlImportacaoException.class);
  }

  @Test
  @DisplayName("XML sem elemento NFe lanca XmlImportacaoException")
  void xmlSemNfe() {
    String semNfe = "<?xml version=\"1.0\"?><outraCoisa></outraCoisa>";
    assertThatThrownBy(() -> parser.parse(semNfe.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(XmlImportacaoException.class);
  }

  @Test
  @DisplayName("XML com DOCTYPE/entidade externa (XXE) e rejeitado com seguranca")
  void xxeRejeitado() {
    String xxe =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe>&xxe;</infNFe></NFe>";
    assertThatThrownBy(() -> parser.parse(xxe.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(XmlImportacaoException.class);
  }

  private NotaFiscal buildNota() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("EMITENTE TERCEIRO LTDA")
        .cpfCnpj("12345678000195")
        .tipoProdutor(TipoProdutor.PESSOA_JURIDICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .estado("PR")
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("SOJA001")
        .descricao("Soja em grao")
        .ncm("12010090")
        .cfop("5101")
        .unidade("KG")
        .quantidade(new BigDecimal("1000.0000"))
        .valorUnitario(new BigDecimal("1.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .cstIbsCbs("101")
        .cClassTrib("010100")
        .build();

    EnderecoDestinatario endDest = EnderecoDestinatario.builder()
        .logradouro("Rua das Flores").numero("100").municipio("Curitiba")
        .codigoMunicipioIbge("4106902").uf("PR").cep("80010000").build();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor)
        .destinatarioNome("COMPRADORA SA")
        .destinatarioCpfCnpj("98765432000199")
        .destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
    nota.addItem(item);
    return nota;
  }
}
