package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.nfe.AssinaturaValidator;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.nfe.KeyStoreCertificadoProvider;
import br.com.agrocontabil.nfe.NfeAssinador;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
import br.com.agrocontabil.nfe.SecureXmlFactories;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.nfe.XsdValidator;
import br.com.agrocontabil.repository.AliquotaCbsRepository;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import br.com.agrocontabil.support.ReducaoTestSupport;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Grupo {@code ICMS} por item no pipeline real de emissão (validação, mapper, assinatura e XSD
 * PL_010g), sem Spring nem banco.
 *
 * <p>Trava a regra B25-90 da NT 2025.002 v1.53 (rejeição 1002, "NF-e sem informação de ICMS /
 * ISSQN"): toda NF-e com {@code finNFe = 1} que o pipeline aceita leva o grupo {@code ICMS} em todo
 * item. Antes do changeSet 0052 nenhuma levava, e o XSD (escolha {@code minOccurs="0"}) não pegava.
 */
@DisplayName("Grupo ICMS por item na NF-e (B25-90, ICMS51 diferido, CST informado)")
class IcmsObrigatorioNaNfeNormalTest {

  private static final String NS = "http://www.portalfiscal.inf.br/nfe";

  private GeracaoNotaFiscalService service;
  private TributacaoService tributacaoService;

  @BeforeEach
  void setUp() {
    TributosProperties props = new TributosProperties();
    TributosProperties.Ibs ibs = new TributosProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TributosProperties.Cbs cbs = new TributosProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    AliquotaIbsUfRepository aliquotaRepo = mock(AliquotaIbsUfRepository.class);
    when(aliquotaRepo.findVigente(anyString(), any(LocalDate.class))).thenReturn(List.of());
    AliquotaCbsRepository cbsRepo = mock(AliquotaCbsRepository.class);
    when(cbsRepo.findVigente(any(LocalDate.class))).thenReturn(List.of());
    tributacaoService = new TributacaoService(ReducaoTestSupport.resolverSemReducao(),
        new AliquotaIbsCbsResolver(aliquotaRepo, cbsRepo, props));
    service = new GeracaoNotaFiscalService(new FiscalRulesValidator(),
        new NotaFiscalNfeMapper(new ChaveAcessoGenerator()), new XmlGenerator(),
        new NfeAssinador(new KeyStoreCertificadoProvider(new CertificadoDigitalProperties(
            "classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"))),
        new AssinaturaValidator(), new XsdValidator());
  }

  @Test
  @DisplayName("venda interna de producao propria a contribuinte: ICMS51, antes do IBSCBS")
  void vendaInternaPadraoSaiComIcms51() {
    NotaFiscal nota = nota("MT", "133518276", item(1, "5101", null, null));

    String xml = service.gerarXml(nota); // assina e valida no XSD; lancaria se invalido

    assertThat(xml).contains("<ICMS><ICMS51><orig>0</orig><CST>51</CST></ICMS51></ICMS>");
    assertThat(xml.indexOf("<ICMS>")).isLessThan(xml.indexOf("<IBSCBS>"));
    // Diferimento total: nada de ICMS proprio, ST ou FCP no total.
    assertThat(xml).contains("<ICMSTot><vBC>0.00</vBC><vICMS>0.00</vICMS>")
        .contains("<vBCST>0.00</vBCST><vST>0.00</vST>");
  }

  @ParameterizedTest(name = "CST {0} informado no CFOP {1} -> grupo {2}")
  @CsvSource({
      "41, 6101, ICMS40",
      "40, 6101, ICMS40",
      "50, 6101, ICMS40",
      "90, 6101, ICMS90",
      "51, 6101, ICMS51",
      // informado prevalece sobre o padrao da venda interna
      "90, 5101, ICMS90"
  })
  void cstInformadoERespeitado(String cst, String cfop, String grupo) {
    NotaFiscal nota = nota(cfop.startsWith("5") ? "MT" : "PR", "9071425318",
        item(1, cfop, cst, "2"));

    String xml = service.gerarXml(nota);

    assertThat(xml).contains("<ICMS><" + grupo + "><orig>2</orig><CST>" + cst + "</CST></"
        + grupo + "></ICMS>");
  }

  @Test
  @DisplayName("interestadual sem CST: 422 ICMS_A_DEFINIR com a sugestao, nada gerado")
  void interestadualSemCstEBarrada() {
    NotaFiscal nota = nota("PR", "9071425318", item(1, "6101", null, null));

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .satisfies(e -> {
          ErrorDetail erro = ((NotaFiscalInvalidaParaEmissaoException) e).getErrors().get(0);
          assertThat(erro.code()).isEqualTo("ICMS_A_DEFINIR");
          assertThat(erro.field()).isEqualTo("item[1].cstIcms");
          assertThat(erro.suggestion()).isEqualTo(
              "Informe o CST do ICMS combinado com o contador: 40, 41, 50, 51 ou 90");
        });
  }

  @Test
  @DisplayName("venda interna a destinatario sem IE: sem padrao, a definir")
  void internaSemIeEBarrada() {
    NotaFiscal nota = nota("MT", null, item(1, "5101", null, null));

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .extracting(e -> ((NotaFiscalInvalidaParaEmissaoException) e).getErrors().get(0).code())
        .isEqualTo("ICMS_A_DEFINIR");
  }

  @Test
  @DisplayName("so o item sem CST e apontado; o outro, no padrao, passa")
  void apontaSoOItemADefinir() {
    NotaFiscal nota = nota("MT", "133518276", item(1, "5101", null, null),
        item(2, "5949", null, null));

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .extracting(e -> ((NotaFiscalInvalidaParaEmissaoException) e).getErrors().stream()
            .filter(d -> d.code().equals("ICMS_A_DEFINIR")).map(ErrorDetail::field).toList())
        .isEqualTo(List.of("item[2].cstIcms"));
  }

  @Test
  @DisplayName("exportacao (CFOP 7xxx) exige CST 41 (MOC, regra N12-10)")
  void exportacaoExigeCst41() {
    Produtor produtor = ProdutorTestFixtures.produtorCompleto();
    NotaFiscal nota = nota("EX", null, item(1, "7101", "51", null));
    nota.getDestinatarioEndereco().setCodigoMunicipioIbge("9999999");
    nota.setProdutor(produtor);

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .extracting(e -> ((NotaFiscalInvalidaParaEmissaoException) e).getErrors().stream()
            .filter(d -> "item[1].cstIcms".equals(d.field())).map(ErrorDetail::message).toList())
        .asList().singleElement().asString().contains("exige o CST do ICMS 41");
  }

  @Test
  @DisplayName("exportacao sem CST: ICMS_A_DEFINIR sugerindo so o 41, nao a lista inteira")
  void exportacaoSemCstSugereSo41() {
    NotaFiscal nota = nota("EX", null, item(1, "7101", null, null));
    nota.getDestinatarioEndereco().setCodigoMunicipioIbge("9999999");

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .satisfies(e -> assertThat(((NotaFiscalInvalidaParaEmissaoException) e).getErrors())
            .filteredOn(d -> "item[1].cstIcms".equals(d.field())).singleElement()
            .satisfies(d -> {
              assertThat(d.code()).isEqualTo("ICMS_A_DEFINIR");
              assertThat(d.suggestion()).startsWith("Na exportacao o CST do ICMS e o 41");
            }));
  }

  @Test
  @DisplayName("emitente PJ do Simples (CRT 1): barrado, CSOSN fora do escopo (rejeicao 590)")
  void emitenteDoSimplesEBarrado() {
    NotaFiscal nota = nota("MT", "133518276", item(1, "5101", null, null));
    nota.getProdutor().setTipoProdutor(br.com.agrocontabil.model.TipoProdutor.PESSOA_JURIDICA);
    nota.getProdutor().setCpfCnpj("11222333000181");
    nota.getProdutor().setRegimeTributario(br.com.agrocontabil.model.RegimeTributario.SIMPLES);

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .satisfies(e -> assertThat(((NotaFiscalInvalidaParaEmissaoException) e).getErrors())
            .filteredOn(d -> "item[1].cstIcms".equals(d.field())).singleElement()
            .satisfies(d -> assertThat(d.message()).contains("CSOSN").contains("590")));
  }

  @Test
  @DisplayName("item sem CFOP: so a falta do CFOP e apontada, sem o ICMS a definir")
  void semCfopSoApontaOCfop() {
    NotaFiscal nota = nota("MT", "133518276", item(1, "5101", null, null));
    nota.getItens().get(0).setCfop(null);

    assertThatThrownBy(() -> new FiscalRulesValidator().validar(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .satisfies(e -> assertThat(((NotaFiscalInvalidaParaEmissaoException) e).getErrors())
            .extracting(ErrorDetail::field).contains("item[1].cfop")
            .doesNotContain("item[1].cstIcms"));
  }

  @Test
  @DisplayName("CST gravado fora da lista (nota antiga) e barrado na emissao")
  void cstForaDaListaEBarrado() {
    NotaFiscal nota = nota("PR", "9071425318", item(1, "6101", "00", null));

    assertThatThrownBy(() -> service.gerarXml(nota))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class)
        .extracting(e -> ((NotaFiscalInvalidaParaEmissaoException) e).getErrors().get(0).message())
        .asString().contains("exige o calculo do ICMS");
  }

  @ParameterizedTest(name = "B25-90: {0}")
  @CsvSource({
      "interna padrao, MT, 133518276, 5101, ''",
      "interestadual informada, PR, 9071425318, 6101, 41",
      "interna informada sem IE, MT, '', 5101, 90"
  })
  void todaNfeNormalAceitaTemIcmsEmTodoItem(String caso, String ufDestino, String ie,
      String cfop, String cst) throws Exception {
    NotaFiscal nota = nota(ufDestino, ie.isEmpty() ? null : ie,
        item(1, cfop, cst.isEmpty() ? null : cst, null),
        item(2, cfop, cst.isEmpty() ? null : cst, null));

    Document xml = SecureXmlFactories.hardenedDocumentBuilderFactory().newDocumentBuilder()
        .parse(new ByteArrayInputStream(service.gerarXml(nota).getBytes(StandardCharsets.UTF_8)));

    assertThat(xml.getElementsByTagNameNS(NS, "finNFe").item(0).getTextContent()).isEqualTo("1");
    NodeList dets = xml.getElementsByTagNameNS(NS, "det");
    assertThat(dets.getLength()).isEqualTo(2);
    for (int i = 0; i < dets.getLength(); i++) {
      Element imposto = (Element) ((Element) dets.item(i))
          .getElementsByTagNameNS(NS, "imposto").item(0);
      List<String> filhos = new ArrayList<>();
      for (Node n = imposto.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof Element e) {
          filhos.add(e.getLocalName());
        }
      }
      assertThat(filhos).as("det %d", i + 1).contains("ICMS");
    }
  }

  // ===========================================================================================

  private NotaFiscal nota(String ufDestino, String ieDestino, NotaFiscalItem... itens) {
    Produtor produtor = ProdutorTestFixtures.produtorCompleto(); // MT, com IE
    String cuf = br.com.agrocontabil.common.UfIbge.codigo(ufDestino).orElse("99");
    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(10L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao do estabelecimento")
        .dataEmissao(OffsetDateTime.of(2026, 11, 6, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor)
        .destinatarioNome("COMPRADORA DE GRAOS LTDA")
        .destinatarioCpfCnpj("12345678000195")
        .destinatarioIe(ieDestino)
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rodovia BR-163")
            .numero("742")
            .municipio("Municipio Teste")
            .codigoMunicipioIbge(cuf + "00001")
            .uf(ufDestino)
            .cep("78890000")
            .build())
        .build();
    BigDecimal total = BigDecimal.ZERO;
    for (NotaFiscalItem item : itens) {
      nota.addItem(item);
      item.setNotaFiscal(nota);
      total = total.add(item.getValorTotal());
    }
    nota.setValorTotalProdutos(total);
    nota.setValorTotalNota(total);
    tributacaoService.calcular(nota);
    return nota;
  }

  private static NotaFiscalItem item(int numero, String cfop, String cstIcms, String origem) {
    return NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(numero)
        .codigoProduto("SOJA00" + numero)
        .descricao("Soja em grao a granel")
        .ncm("12019000")
        .cfop(cfop)
        .unidade("KG")
        .quantidade(new BigDecimal("1000.0000"))
        .valorUnitario(new BigDecimal("2.0000"))
        .valorTotal(new BigDecimal("2000.00"))
        .cstIbsCbs("000")
        .cClassTrib("000001")
        .cstIcms(cstIcms)
        .origemIcms(origem)
        .build();
  }
}
