package br.com.agrocontabil.service;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.repository.AliquotaCbsRepository;
import br.com.agrocontabil.support.ReducaoTestSupport;
import java.util.List;
import java.time.LocalDate;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.exception.NfaInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.nfe.AssinaturaValidator;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.nfe.KeyStoreCertificadoProvider;
import br.com.agrocontabil.nfe.NfeAssinador;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.nfe.XsdValidator;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teste de integracao do fluxo de emissao da NFA (issue #18), exercitando o pipeline real
 * ({@link GeracaoNotaFiscalService}) por dentro do {@link GeracaoNfaService}, sem Spring/banco.
 *
 * <p>Valida a "Definition of Done" do PR:
 * <ul>
 *   <li>NFA de produtor pessoa fisica com CPF valido → XML emitido com {@code <CPF>} no
 *       emitente e sucesso na validacao XSD;</li>
 *   <li>NFA com produtor pessoa juridica → HTTP 422 ({@code NFA_PRODUTOR_NAO_PF});</li>
 *   <li>NFA com CPF de emitente de digito verificador invalido → HTTP 422
 *       ({@code DOCUMENTO_INVALIDO}).</li>
 * </ul>
 */
@DisplayName("GeracaoNfaService — integracao do pipeline (sem Spring, sem banco)")
class GeracaoNfaFlowTest {

  /** CPF sintetico com digito verificador valido. */
  private static final String CPF_VALIDO = "52998224725";

  private GeracaoNfaService geracaoNfaService;
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
    // Sem vigencia de CBS nas fixtures: cai no fallback da aliquota parametrizada (issue #85).
    AliquotaCbsRepository cbsRepo = mock(AliquotaCbsRepository.class);
    when(cbsRepo.findVigente(any(LocalDate.class))).thenReturn(List.of());
    tributacaoService = new TributacaoService(
        ReducaoTestSupport.resolverSemReducao(), new AliquotaIbsCbsResolver(aliquotaRepo, cbsRepo, props));

    ChaveAcessoGenerator chaveAcessoGenerator = new ChaveAcessoGenerator();
    FiscalRulesValidator validator = new FiscalRulesValidator();
    NotaFiscalNfeMapper mapper = new NotaFiscalNfeMapper(chaveAcessoGenerator);
    XmlGenerator xmlGenerator = new XmlGenerator();
    KeyStoreCertificadoProvider certProvider = new KeyStoreCertificadoProvider(
        new CertificadoDigitalProperties("classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"));
    NfeAssinador nfeAssinador = new NfeAssinador(certProvider);
    AssinaturaValidator assinaturaValidator = new AssinaturaValidator();
    XsdValidator xsdValidator = new XsdValidator();

    GeracaoNotaFiscalService pipeline = new GeracaoNotaFiscalService(
        validator, mapper, xmlGenerator, nfeAssinador, assinaturaValidator, xsdValidator);
    geracaoNfaService = new GeracaoNfaService(pipeline);
  }

  @Test
  @DisplayName("NFA de produtor PF com CPF valido emite XML com <CPF> no emitente")
  void nfaPfComCpfValidoEmiteXmlComCpf() {
    NotaFiscal nota = buildNfaCompleta(TipoProdutor.PESSOA_FISICA, CPF_VALIDO);
    tributacaoService.calcular(nota);

    String xml = geracaoNfaService.gerarXml(nota);

    assertThat(xml).contains("<CPF>" + CPF_VALIDO + "</CPF>");
    // Nao deve conter CNPJ no emitente (o bloco <emit> vem antes do <dest>).
    String emit = xml.substring(xml.indexOf("<emit>"), xml.indexOf("</emit>"));
    assertThat(emit).contains("<CPF>");
    assertThat(emit).doesNotContain("<CNPJ>");
  }

  @Test
  @DisplayName("NFA com produtor PJ deve falhar com 422 (NFA_PRODUTOR_NAO_PF)")
  void nfaComProdutorPjDeveFalhar() {
    NotaFiscal nota = buildNfaCompleta(TipoProdutor.PESSOA_JURIDICA, "11222333000181");
    tributacaoService.calcular(nota);

    assertThatThrownBy(() -> geracaoNfaService.gerarXml(nota))
        .isInstanceOf(NfaInvalidaParaEmissaoException.class)
        .satisfies(ex -> assertThat(((NfaInvalidaParaEmissaoException) ex).getStatus().value())
            .isEqualTo(422));
  }

  @Test
  @DisplayName("NFA de PF vendendo a empresa leva o Funrural/Senar no infCpl e passa no XSD (#332)")
  void nfaComFunruralNoInfCpl() {
    NotaFiscal nota = buildNfaCompleta(TipoProdutor.PESSOA_FISICA, CPF_VALIDO); // dest CNPJ
    tributacaoService.calcular(nota);

    String xml = geracaoNfaService.gerarXml(nota); // valida no XSD; lancaria se invalido

    assertThat(xml).contains("FUNRURAL/SENAR RETIDO PELO ADQUIRENTE: BASE R$ 1.000,00")
        .contains("TOTAL 1,5% R$ 15,00");
  }

  @Test
  @DisplayName("NFA de PF com CPF de DV invalido deve falhar com 422 (DOCUMENTO_INVALIDO)")
  void nfaComCpfInvalidoDeveFalhar() {
    NotaFiscal nota = buildNfaCompleta(TipoProdutor.PESSOA_FISICA, "12345678901");
    tributacaoService.calcular(nota);

    assertThatThrownBy(() -> geracaoNfaService.gerarXml(nota))
        .isInstanceOf(NfaInvalidaParaEmissaoException.class);
  }

  /**
   * Constroi uma NFA completa e valida para o pipeline, variando o tipo/documento do emitente.
   */
  private NotaFiscal buildNfaCompleta(TipoProdutor tipo, String cpfCnpjEmitente) {
    // Emitente completo (issue #88): a NFA exige endereco real; a IE fica nula de proposito,
    // pois o produtor rural PF sem IE e justamente o caso de uso da NFA (mapper emite ISENTO).
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("JOAO DA SILVA AGRICULTOR")
        .cpfCnpj(cpfCnpjEmitente)
        .tipoProdutor(tipo)
        .regimeTributario(RegimeTributario.SIMPLES)
        .estado("PR")
        .endereco(ProdutorTestFixtures.enderecoCuritibaPr())
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("SOJA001")
        .descricao("Soja em grao safra 2026")
        .ncm("12019000")
        .cfop("5101")
        .unidade("KG")
        .quantidade(new BigDecimal("1000.0000"))
        .valorUnitario(new BigDecimal("1.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .cstIbsCbs("000")
        .cClassTrib("000001")
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
        .tipoDocumento(TipoDocumentoFiscal.NFA)
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor)
        .destinatarioNome("COMPRADORA SA")
        .destinatarioCpfCnpj("11222333000181")
        .destinatarioIe("9071425318") // contribuinte: venda interna 5101 sai com ICMS51
        .destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();

    nota.addItem(item);
    item.setNotaFiscal(nota);

    return nota;
  }
}
