package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.DigitalCertificateProperties;
import br.com.agrocontabil.config.TaxProperties;
import br.com.agrocontabil.exception.InvoiceInvalidForIssuanceException;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.TaxRegime;
import br.com.agrocontabil.model.ProducerType;
import br.com.agrocontabil.nfe.SignatureValidator;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.nfe.KeyStoreCertificateProvider;
import br.com.agrocontabil.nfe.NfeSigner;
import br.com.agrocontabil.nfe.InvoiceNfeMapper;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.nfe.XsdValidator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teste de integração do pipeline de geração do XML da NF-e.
 *
 * <p>Sem contexto Spring (sem banco): instancia os componentes reais em memória.
 * Usa o {@link IbsCbsCalculationService} com alíquotas padrão para calculate IBS/CBS,
 * simulando o DataLoader.
 *
 * <p>Cobertura:
 * <ol>
 *   <li>XML gerado valida contra o XSD (assert sem erros de validação)</li>
 *   <li>XML contém as tags IBS/CBS com os valores calculados</li>
 *   <li>Nota faltando field obrigatório → {@link InvoiceInvalidForIssuanceException}
 *       com lista de campos</li>
 * </ol>
 */
@DisplayName("GeracaoNotaFiscalService — integração (sem Spring, sem banco)")
class InvoiceXmlServiceTest {

  private InvoiceXmlService service;
  private IbsCbsCalculationService ibsCbsCalculationService;

  @BeforeEach
  void setUp() {
    TaxProperties props = new TaxProperties();
    // Alíquotas padrão: IBS 0,10% e CBS 0,90%
    TaxProperties.Ibs ibs = new TaxProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TaxProperties.Cbs cbs = new TaxProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);

    ibsCbsCalculationService = new IbsCbsCalculationService(props);

    AccessKeyGenerator accessKeyGenerator = new AccessKeyGenerator();
    FiscalRulesValidator validator = new FiscalRulesValidator();
    InvoiceNfeMapper mapper = new InvoiceNfeMapper(accessKeyGenerator);
    XmlGenerator xmlGenerator = new XmlGenerator();
    KeyStoreCertificateProvider certProvider = new KeyStoreCertificateProvider(
        new DigitalCertificateProperties("classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"));
    NfeSigner nfeAssinador = new NfeSigner(certProvider);
    SignatureValidator assinaturaValidator = new SignatureValidator();
    XsdValidator xsdValidator = new XsdValidator();

    service = new InvoiceXmlService(
        validator, mapper, xmlGenerator, nfeAssinador, assinaturaValidator, xsdValidator);
  }

  // =========================================================================
  // Cenário 1: XML válido contra o XSD
  // =========================================================================

  @Test
  @DisplayName("deve gerar XML valido contra o XSD para nota completa com IBS/CBS")
  void deveGerarXmlValidoContraXsd() {
    Invoice invoice = buildNotaCompleta();
    ibsCbsCalculationService.calculate(invoice);

    String xml = service.generateXml(invoice);

    assertThat(xml).isNotBlank();
    assertThat(xml).startsWith("<?xml");
    assertThat(xml).contains("<NFe ");
    assertThat(xml).contains("http://www.portalfiscal.inf.br/nfe");
    // Não deve lançar exceção (XsdValidator internamente já valida e lança se inválido)
  }

  // =========================================================================
  // Cenário 2: XML contém tags IBS/CBS com valores calculados
  // =========================================================================

  @Test
  @DisplayName("XML deve conter grupo IBSCBS com valores de IBS e CBS do item")
  void xmlDeveConterGrupoIbsCbsComValores() {
    Invoice invoice = buildNotaCompleta();
    ibsCbsCalculationService.calculate(invoice);

    String xml = service.generateXml(invoice);

    // Grupo IBS/CBS no item (elemento IBSCBS)
    assertThat(xml).contains("<IBSCBS>");
    assertThat(xml).contains("<CST>");
    assertThat(xml).contains("<cClassTrib>");
    assertThat(xml).contains("<gIBSCBS>");
    assertThat(xml).contains("<vBC>");
    assertThat(xml).contains("<vIBS>");
    assertThat(xml).contains("<vCBS>");
  }

  @Test
  @DisplayName("XML deve conter grupo IBSCBSTot nos totais")
  void xmlDeveConterIbsCbsTotNosTotal() {
    Invoice invoice = buildNotaCompleta();
    ibsCbsCalculationService.calculate(invoice);

    String xml = service.generateXml(invoice);

    assertThat(xml).contains("<IBSCBSTot>");
    assertThat(xml).contains("<vBCIBSCBS>");
    assertThat(xml).contains("<vIBSUF>");
    assertThat(xml).contains("<vIBSMun>");
    assertThat(xml).contains("<vCBS>");
  }

  @Test
  @DisplayName("valor do IBS no XML deve corresponder ao calculado pelo servico")
  void valorIbsNoXmlDeveCorresponderAoCalculado() {
    Invoice invoice = buildNotaCompleta();
    ibsCbsCalculationService.calculate(invoice);

    // IBS = base * 0.0010; base = 1000.00 (sem redução) → IBS = 1.00
    BigDecimal expectedIbs = invoice.getTotalIbsValue();
    assertThat(expectedIbs).isNotNull();

    String xml = service.generateXml(invoice);

    // vIBS total no XML deve ser o valor calculado formatado com 2 casas
    String expectedIbsStr = expectedIbs.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    assertThat(xml).contains("<vIBS>" + expectedIbsStr + "</vIBS>");
  }

  // =========================================================================
  // Cenário 2b: XML gerado vem assinado; error estrutural no XSD é fatal
  // =========================================================================

  @Test
  @DisplayName("XML gerado pelo pipeline vem assinado digitalmente (ds:Signature + X509)")
  void xmlGeradoVemAssinado() {
    Invoice invoice = buildNotaCompleta();
    ibsCbsCalculationService.calculate(invoice);

    String xml = service.generateXml(invoice);

    assertThat(xml).contains("Signature");
    assertThat(xml).contains("X509Certificate");
    assertThat(xml).contains("rsa-sha1");
  }

  @Test
  @DisplayName("XsdValidator rejeita erro estrutural (hardening da assinatura ausente revertido)")
  void xsdValidatorRejeitaErroEstrutural() {
    Invoice invoice = buildNotaCompleta();
    ibsCbsCalculationService.calculate(invoice);

    String xmlValido = service.generateXml(invoice);
    // Corrompe a estrutura: remove o elemento obrigatorio <vNF> dos totais (ICMSTot).
    String xmlCorrompido = xmlValido.replaceFirst("<vNF>[^<]*</vNF>", "");
    assertThat(xmlCorrompido).doesNotContain("<vNF>");

    XsdValidator validatorReal = new XsdValidator();

    // Todo error XSD agora e fatal — nada e tolerado.
    assertThatThrownBy(() -> validatorReal.validate(xmlCorrompido))
        .isInstanceOf(br.com.agrocontabil.exception.XmlGenerationException.class)
        .satisfies(ex -> assertThat(ex.getMessage()).contains("vNF"));
  }

  // =========================================================================
  // Cenário 3: Validação de campos obrigatórios
  // =========================================================================

  @Test
  @DisplayName("deve lancar NotaFiscalInvalidaParaEmissaoException quando produtor sem CPF/CNPJ")
  void deveLancarExcecaoParaProdutorSemCpfCnpj() {
    Invoice invoice = buildNotaCompleta();
    invoice.getProducer().setTaxId(null);

    assertThatThrownBy(() -> service.generateXml(invoice))
        .isInstanceOf(InvoiceInvalidForIssuanceException.class)
        .satisfies(ex -> {
          InvoiceInvalidForIssuanceException e =
              (InvoiceInvalidForIssuanceException) ex;
          assertThat(e.getErrors())
              .anyMatch(v -> v.field() != null && v.field().contains("cpfCnpj"));
        });
  }

  @Test
  @DisplayName("deve lancar excecao com multiplas violacoes quando varios campos faltam")
  void deveLancarExcecaoComMultiplasViolacoes() {
    Invoice invoice = buildNotaCompleta();
    invoice.setOperationNature(null);
    invoice.setRecipientTaxId(null);
    invoice.getProducer().setTaxId(null);

    assertThatThrownBy(() -> service.generateXml(invoice))
        .isInstanceOf(InvoiceInvalidForIssuanceException.class)
        .satisfies(ex -> {
          InvoiceInvalidForIssuanceException e =
              (InvoiceInvalidForIssuanceException) ex;
          assertThat(e.getErrors()).hasSizeGreaterThanOrEqualTo(3);
        });
  }

  @Test
  @DisplayName("deve lancar excecao quando nota sem itens")
  void deveLancarExcecaoParaNotaSemItens() {
    Invoice invoice = buildNotaCompleta();
    invoice.getItems().clear();

    assertThatThrownBy(() -> service.generateXml(invoice))
        .isInstanceOf(InvoiceInvalidForIssuanceException.class)
        .satisfies(ex -> {
          InvoiceInvalidForIssuanceException e =
              (InvoiceInvalidForIssuanceException) ex;
          assertThat(e.getErrors())
              .anyMatch(v -> v.field() != null && v.field().equals("itens"));
        });
  }

  @Test
  @DisplayName("deve lancar excecao quando endereco do destinatario esta ausente")
  void deveLancarExcecaoParaDestinatarioSemEndereco() {
    Invoice invoice = buildNotaCompleta();
    invoice.setRecipientAddress(null);

    assertThatThrownBy(() -> service.generateXml(invoice))
        .isInstanceOf(InvoiceInvalidForIssuanceException.class)
        .satisfies(ex -> {
          InvoiceInvalidForIssuanceException e =
              (InvoiceInvalidForIssuanceException) ex;
          assertThat(e.getErrors())
              .anyMatch(v -> v.field() != null && v.field().contains("destinatarioEndereco"));
        });
  }

  // =========================================================================
  // Builders de entidades de teste
  // =========================================================================

  /**
   * Constrói uma {@link Invoice} completa com dados coerentes para teste.
   * Simula o que o DataLoader criaria em ambiente real.
   */
  private Invoice buildNotaCompleta() {
    Producer producer = Producer.builder()
        .id(UUID.randomUUID())
        .fullName("JOAO DA SILVA AGRICULTOR")
        .taxId("12345678901")
        .producerType(ProducerType.PESSOA_FISICA)
        .taxRegime(TaxRegime.SIMPLES)
        .state("PR")
        .build();

    InvoiceItem item = InvoiceItem.builder()
        .id(UUID.randomUUID())
        .itemNumber(1)
        .productCode("SOJA001")
        .description("Soja em grao safra 2026")
        .ncm("12010090")
        .cfop("5101")
        .unit("KG")
        .quantity(new BigDecimal("1000.0000"))
        .unitPrice(new BigDecimal("1.0000"))
        .totalValue(new BigDecimal("1000.00"))
        .cstIbsCbs("101")
        .cClassTrib("010100")
        .build();

    RecipientAddress endDest = RecipientAddress.builder()
        .street("Rua das Flores")
        .number("100")
        .city("Curitiba")
        .ibgeCityCode("4106902")
        .uf("PR")
        .zipCode("80010000")
        .build();

    Invoice invoice = Invoice.builder()
        .id(UUID.randomUUID())
        .number(1L)
        .series(1)
        .model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .producer(producer)
        .recipientName("COMPRADORA SA")
        .recipientTaxId("12345678000195")
        .recipientAddress(endDest)
        .totalProductsValue(new BigDecimal("1000.00"))
        .totalInvoiceValue(new BigDecimal("1000.00"))
        .build();

    invoice.addItem(item);
    item.setInvoice(invoice);

    return invoice;
  }
}
