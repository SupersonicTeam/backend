package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.danfe.DanfePdfService;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.nfe.AssinaturaValidator;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.nfe.KeyStoreCertificadoProvider;
import br.com.agrocontabil.nfe.NfeAssinador;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.nfe.XsdValidator;
import br.com.agrocontabil.repository.AliquotaCbsRepository;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.service.ReducaoNcmResolver.ReducaoAplicada;
import br.com.agrocontabil.validation.NcmValidator;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Venda do associado à cooperativa optante pelo regime específico (LC 214/2025, art. 271; #299;
 * #284, pergunta 6), sobre a tabela oficial real do changeSet 0048: com o cClassTrib 200020
 * informado no item, alíquota zero (CST 200, redução de 100%) qualquer que seja o NCM. Cálculo
 * pelo {@link ReducaoNcmResolver}, conferência pelo {@link FiscalRulesValidator} com a tabela, XML
 * assinado e validado no XSD, e a DANFE lida do XML.
 */
@DataJpaTest
@Import({ClassificacaoTributariaService.class, ReducaoNcmResolver.class})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class CooperativaOptanteEmissaoIntegrationTest {

  private static final OffsetDateTime EMISSAO =
      OffsetDateTime.of(2026, 11, 19, 10, 0, 0, 0, ZoneOffset.ofHours(-3));

  private static final String SOJA = "12019000";

  @Autowired private ClassificacaoTributariaService classificacao;
  @Autowired private ReducaoNcmResolver resolver;
  @Autowired private NcmReferenciaRepository ncmReferenciaRepository;

  private TributacaoService tributacao;
  private GeracaoNotaFiscalService geracao;
  private FiscalRulesValidator validador;

  @BeforeEach
  void setUp() {
    TributosProperties props = new TributosProperties();
    TributosProperties.Ibs ibs = new TributosProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TributosProperties.Cbs cbs = new TributosProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    AliquotaIbsUfRepository ibsRepo = mock(AliquotaIbsUfRepository.class);
    when(ibsRepo.findVigente(anyString(), any(LocalDate.class))).thenReturn(List.of());
    AliquotaCbsRepository cbsRepo = mock(AliquotaCbsRepository.class);
    when(cbsRepo.findVigente(any(LocalDate.class))).thenReturn(List.of());
    tributacao = new TributacaoService(resolver,
        new AliquotaIbsCbsResolver(ibsRepo, cbsRepo, props));
    validador = new FiscalRulesValidator(new NcmValidator(ncmReferenciaRepository), classificacao);
    geracao = new GeracaoNotaFiscalService(
        validador,
        new NotaFiscalNfeMapper(new ChaveAcessoGenerator()), new XmlGenerator(),
        new NfeAssinador(new KeyStoreCertificadoProvider(new CertificadoDigitalProperties(
            "classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"))),
        new AssinaturaValidator(), new XsdValidator());
  }

  @Test
  @DisplayName("resolvedor: 200020 informado na soja = 100%; sem ele, a regra do NCM (60%)")
  void resolvedor() {
    ReducaoAplicada cooperativa = resolver.tratamento(nota(SOJA, "200020").getItens().get(0));
    ReducaoAplicada comum = resolver.tratamento(nota(SOJA, null).getItens().get(0));

    assertThat(cooperativa.cClassTrib()).isEqualTo("200020");
    assertThat(cooperativa.percentualReducao()).isEqualByComparingTo("100");
    assertThat(comum.cClassTrib()).isEqualTo("200036");
    assertThat(comum.percentualReducao()).isEqualByComparingTo("60");
  }

  @Test
  @DisplayName("XML da venda a cooperativa optante: CST 200, cClassTrib 200020, gRed de 100% e"
      + " tributo zero, no XSD e assinado")
  void xmlComAliquotaZero() {
    NotaFiscal nota = nota(SOJA, "200020");
    tributacao.calcular(nota);

    String xml = geracao.gerarXml(nota); // regra fiscal com a tabela, assinatura e XSD

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getCClassTribAplicado()).isEqualTo("200020");
    assertThat(item.getPercentualReducaoAplicado()).isEqualByComparingTo("100");
    assertThat(item.getValorIbs()).isEqualByComparingTo("0");
    assertThat(item.getValorCbs()).isEqualByComparingTo("0");
    assertThat(xml).contains("<CST>200</CST>")
        .contains("<cClassTrib>200020</cClassTrib>")
        .contains("<vBC>1000.00</vBC>")
        .contains("<pIBSUF>0.1000</pIBSUF><gRed><pRedAliq>100.0000</pRedAliq>"
            + "<pAliqEfet>0.0000</pAliqEfet></gRed>")
        .contains("<pCBS>0.9000</pCBS><gRed><pRedAliq>100.0000</pRedAliq>")
        .contains("<vIBS>0.00</vIBS>")
        .contains("<vCBS>0.00</vCBS>");
  }

  @Test
  @DisplayName("DANFE da venda a cooperativa: CST/cClassTrib do XML e IBS/CBS zerados")
  void danfeLeOXml() throws IOException {
    NotaFiscal nota = nota(SOJA, "200020");
    tributacao.calcular(nota);
    String xml = geracao.gerarXml(nota);
    nota.setXmlGerado(xml);
    nota.setChaveAcesso(xml.replaceFirst("(?s).*Id=\"NFe(\\d{44})\".*", "$1"));
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);

    String texto = texto(new DanfePdfService().gerarPdf(nota));

    assertThat(texto).contains("IBS/CBS 200").contains("[cClassTrib 200020]")
        .contains("1.000,00");
  }

  @ParameterizedTest(name = "200020 vale para o NCM {0}: a operacao decide, nao o produto")
  @ValueSource(strings = {SOJA, "10059010", "04072100", "04012090"})
  @DisplayName("#299: 200020 passa no validador com qualquer NCM da referencia")
  void qualquerNcm(String ncm) {
    NotaFiscal nota = nota(ncm, "200020");
    tributacao.calcular(nota);

    assertThatCode(() -> validador.validar(nota)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("#299: 200020 a destinatario pessoa fisica e recusado com 422 no cClassTrib")
  void pessoaFisicaRecusada() {
    NotaFiscal nota = nota(SOJA, "200020");
    nota.setDestinatarioCpfCnpj("98765432100");
    nota.setDestinatarioIe(null);
    nota.getItens().get(0).setCstIcms("41");
    tributacao.calcular(nota);

    assertThatThrownBy(() -> geracao.gerarXml(nota))
        .isInstanceOfSatisfying(NotaFiscalInvalidaParaEmissaoException.class,
            e -> assertThat(e.getErrors()).anySatisfy(d -> {
              assertThat(d.field()).isEqualTo("item[1].cClassTrib");
              assertThat(d.message()).contains("200020").contains("CNPJ");
              assertThat(d.rejectedValue()).isEqualTo("200020");
            }));
  }

  @Test
  @DisplayName("#299: compra importada com 200020 nao e recusada (documento do fornecedor)")
  void importadaNaoERecusada() {
    NotaFiscal nota = nota(SOJA, "200020");
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setDestinatarioCpfCnpj("98765432100");
    tributacao.calcular(nota);

    assertThatCode(() -> validador.validar(nota)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("#299: o aviso do rascunho lembra que a opcao da cooperativa nao e conferida")
  void avisoDoRascunho() {
    SinaisNotaConferirService sinais = new SinaisNotaConferirService(
        mock(br.com.agrocontabil.repository.NotaFiscalRepository.class), ncmReferenciaRepository,
        classificacao, java.time.Clock.fixed(EMISSAO.toInstant(),
            java.time.ZoneId.of("America/Sao_Paulo")));

    // Um aviso so: o 200020 nao e "nao admitido" para a soja.
    assertThat(sinais.avisosDoRascunho(nota(SOJA, "200020")))
        .extracting(AvisoRascunho::codigo)
        .containsExactly(AvisoRascunho.Codigo.COOPERATIVA_OPTANTE_A_CONFIRMAR);
    assertThat(sinais.avisosDoRascunho(nota(SOJA, null))).isEmpty();
  }

  /** Venda de produção própria (5101) a uma cooperativa de MT com IE: ICMS 51 pelo padrão. */
  private static NotaFiscal nota(String ncm, String cClassTrib) {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("PRODUTOR ASSOCIADO")
        .cpfCnpj("12345678901")
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.REAL)
        .estado("PR")
        .inscricaoEstadual(ProdutorTestFixtures.INSCRICAO_ESTADUAL)
        .endereco(ProdutorTestFixtures.enderecoCuritibaPr())
        .build();
    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("PROD")
        .descricao("Produto da cooperativa")
        .ncm(ncm)
        .cfop("5101")
        .unidade("KG")
        .quantidade(new BigDecimal("500.0000"))
        .valorUnitario(new BigDecimal("2.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .cClassTrib(cClassTrib)
        .build();
    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(EMISSAO)
        .produtor(produtor)
        .origem(OrigemNotaFiscal.PROPRIA)
        .destinatarioNome("COOPERATIVA AGROINDUSTRIAL")
        .destinatarioCpfCnpj("12345678000195")
        .destinatarioIe("9071425318")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rua das Flores").numero("100").municipio("Curitiba")
            .codigoMunicipioIbge("4106902").uf("PR").cep("80010000").build())
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
    nota.addItem(item);
    item.setNotaFiscal(nota);
    return nota;
  }

  private static String texto(byte[] pdf) throws IOException {
    PdfReader leitor = new PdfReader(pdf);
    try {
      StringBuilder texto = new StringBuilder();
      PdfTextExtractor extrator = new PdfTextExtractor(leitor);
      for (int pagina = 1; pagina <= leitor.getNumberOfPages(); pagina++) {
        texto.append(extrator.getTextFromPage(pagina)).append('\n');
      }
      return texto.toString();
    } finally {
      leitor.close();
    }
  }
}
