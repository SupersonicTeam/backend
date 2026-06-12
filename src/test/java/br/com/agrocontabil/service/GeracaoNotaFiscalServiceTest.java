package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
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
 * Usa o {@link CalculoIbsCbsService} com alíquotas padrão para calcular IBS/CBS,
 * simulando o DataLoader.
 *
 * <p>Cobertura:
 * <ol>
 *   <li>XML gerado valida contra o XSD (assert sem erros de validação)</li>
 *   <li>XML contém as tags IBS/CBS com os valores calculados</li>
 *   <li>Nota faltando campo obrigatório → {@link NotaFiscalInvalidaParaEmissaoException}
 *       com lista de campos</li>
 * </ol>
 */
@DisplayName("GeracaoNotaFiscalService — integração (sem Spring, sem banco)")
class GeracaoNotaFiscalServiceTest {

  private GeracaoNotaFiscalService service;
  private CalculoIbsCbsService calculoIbsCbsService;

  @BeforeEach
  void setUp() {
    TributosProperties props = new TributosProperties();
    // Alíquotas padrão: IBS 0,10% e CBS 0,90%
    TributosProperties.Ibs ibs = new TributosProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TributosProperties.Cbs cbs = new TributosProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);

    calculoIbsCbsService = new CalculoIbsCbsService(props);

    ChaveAcessoGenerator chaveAcessoGenerator = new ChaveAcessoGenerator();
    FiscalRulesValidator validator = new FiscalRulesValidator();
    NotaFiscalNfeMapper mapper = new NotaFiscalNfeMapper(chaveAcessoGenerator);
    XmlGenerator xmlGenerator = new XmlGenerator();
    XsdValidator xsdValidator = new XsdValidator();

    service = new GeracaoNotaFiscalService(validator, mapper, xmlGenerator, xsdValidator);
  }

  // =========================================================================
  // Cenário 1: XML válido contra o XSD
  // =========================================================================

  @Test
  @DisplayName("deve gerar XML valido contra o XSD para nota completa com IBS/CBS")
  void deveGerarXmlValidoContraXsd() {
    NotaFiscal nota = buildNotaCompleta();
    calculoIbsCbsService.calcular(nota);

    String xml = service.gerarXml(nota);

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
    NotaFiscal nota = buildNotaCompleta();
    calculoIbsCbsService.calcular(nota);

    String xml = service.gerarXml(nota);

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
    NotaFiscal nota = buildNotaCompleta();
    calculoIbsCbsService.calcular(nota);

    String xml = service.gerarXml(nota);

    assertThat(xml).contains("<IBSCBSTot>");
    assertThat(xml).contains("<vBCIBSCBS>");
    assertThat(xml).contains("<vIBSUF>");
    assertThat(xml).contains("<vIBSMun>");
    assertThat(xml).contains("<vCBS>");
  }

  @Test
  @DisplayName("valor do IBS no XML deve corresponder ao calculado pelo servico")
  void valorIbsNoXmlDeveCorresponderAoCalculado() {
    NotaFiscal nota = buildNotaCompleta();
    calculoIbsCbsService.calcular(nota);

    // IBS = base * 0.0010; base = 1000.00 (sem redução) → IBS = 1.00
    BigDecimal expectedIbs = nota.getValorTotalIbs();
    assertThat(expectedIbs).isNotNull();

    String xml = service.gerarXml(nota);

    // vIBS total no XML deve ser o valor calculado formatado com 2 casas
    String expectedIbsStr = expectedIbs.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    assertThat(xml).contains("<vIBS>" + expectedIbsStr + "</vIBS>");
  }

  // =========================================================================
  // Cenário 2b: o filtro de "documento sem assinatura" NÃO mascara outros erros
  // =========================================================================

  @Test
  @DisplayName("XsdValidator deve lancar para erro estrutural (alem da assinatura ausente)")
  void xsdValidatorDeveRejeitarErroEstruturalAlemDaAssinatura() {
    NotaFiscal nota = buildNotaCompleta();
    calculoIbsCbsService.calcular(nota);

    String xmlValido = service.gerarXml(nota);
    // Corrompe a estrutura: remove o elemento obrigatorio <vNF> dos totais (ICMSTot).
    // Isso gera um erro XSD que NAO e a ausencia de assinatura — deve permanecer fatal.
    String xmlCorrompido = xmlValido.replaceFirst("<vNF>[^<]*</vNF>", "");
    assertThat(xmlCorrompido).doesNotContain("<vNF>");

    XsdValidator validatorReal = new XsdValidator();

    assertThatThrownBy(() -> validatorReal.validar(xmlCorrompido))
        .isInstanceOf(br.com.agrocontabil.exception.XmlGenerationException.class)
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("infNFeSupl"));
  }

  // =========================================================================
  // Cenário 3: Validação de campos obrigatórios
  // =========================================================================

  @Test
  @DisplayName("deve lancar NotaFiscalInvalidaParaEmissaoException quando produtor sem CPF/CNPJ")
  void deveLancarExcecaoParaProdutorSemCpfCnpj() {
    NotaFiscal nota = buildNotaCompleta();
    nota.getProdutor().setCpfCnpj(null);

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .satisfies(ex -> {
          NotaFiscalInvalidaParaEmissaoException e =
              (NotaFiscalInvalidaParaEmissaoException) ex;
          assertThat(e.getErrors())
              .anyMatch(v -> v.field() != null && v.field().contains("cpfCnpj"));
        });
  }

  @Test
  @DisplayName("deve lancar excecao com multiplas violacoes quando varios campos faltam")
  void deveLancarExcecaoComMultiplasViolacoes() {
    NotaFiscal nota = buildNotaCompleta();
    nota.setNaturezaOperacao(null);
    nota.setDestinatarioCpfCnpj(null);
    nota.getProdutor().setCpfCnpj(null);

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .satisfies(ex -> {
          NotaFiscalInvalidaParaEmissaoException e =
              (NotaFiscalInvalidaParaEmissaoException) ex;
          assertThat(e.getErrors()).hasSizeGreaterThanOrEqualTo(3);
        });
  }

  @Test
  @DisplayName("deve lancar excecao quando nota sem itens")
  void deveLancarExcecaoParaNotaSemItens() {
    NotaFiscal nota = buildNotaCompleta();
    nota.getItens().clear();

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .satisfies(ex -> {
          NotaFiscalInvalidaParaEmissaoException e =
              (NotaFiscalInvalidaParaEmissaoException) ex;
          assertThat(e.getErrors())
              .anyMatch(v -> v.field() != null && v.field().equals("itens"));
        });
  }

  @Test
  @DisplayName("deve lancar excecao quando endereco do destinatario esta ausente")
  void deveLancarExcecaoParaDestinatarioSemEndereco() {
    NotaFiscal nota = buildNotaCompleta();
    nota.setDestinatarioEndereco(null);

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .satisfies(ex -> {
          NotaFiscalInvalidaParaEmissaoException e =
              (NotaFiscalInvalidaParaEmissaoException) ex;
          assertThat(e.getErrors())
              .anyMatch(v -> v.field() != null && v.field().contains("destinatarioEndereco"));
        });
  }

  // =========================================================================
  // Builders de entidades de teste
  // =========================================================================

  /**
   * Constrói uma {@link NotaFiscal} completa com dados coerentes para teste.
   * Simula o que o DataLoader criaria em ambiente real.
   */
  private NotaFiscal buildNotaCompleta() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("JOAO DA SILVA AGRICULTOR")
        .cpfCnpj("12345678901")
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .estado("PR")
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("SOJA001")
        .descricao("Soja em grao safra 2026")
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
        .logradouro("Rua das Flores")
        .numero("100")
        .municipio("Curitiba")
        .codigoMunicipioIbge("4106902")
        .uf("PR")
        .cep("80010000")
        .build();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor)
        .destinatarioNome("COMPRADORA SA")
        .destinatarioCpfCnpj("12345678000195")
        .destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();

    nota.addItem(item);
    item.setNotaFiscal(nota);

    return nota;
  }
}
