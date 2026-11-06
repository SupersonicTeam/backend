package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
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
import org.junit.jupiter.params.provider.CsvSource;
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
 * Leite vendido pelo produtor (#284, pergunta 2; decisão de 2026-11-06), sobre a tabela oficial
 * real do changeSet 0048: sem cClassTrib informado sai in natura (200036, 60%, art. 137); com o
 * 200003 informado, alíquota zero do Anexo I. Cálculo pelo {@link ReducaoNcmResolver}, conferência
 * pelo {@link FiscalRulesValidator} com a tabela e XML validado no XSD (CST, cClassTrib e gRed).
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
class LeiteInNaturaEmissaoIntegrationTest {

  private static final OffsetDateTime EMISSAO =
      OffsetDateTime.of(2026, 11, 6, 10, 0, 0, 0, ZoneOffset.ofHours(-3));

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
    // Validador completo: tambem confere o NCM na referencia (o 0401.20.90 entrou na 0052).
    validador = new FiscalRulesValidator(new NcmValidator(ncmReferenciaRepository), classificacao);
    geracao = new GeracaoNotaFiscalService(
        validador,
        new NotaFiscalNfeMapper(new ChaveAcessoGenerator()), new XmlGenerator(),
        new NfeAssinador(new KeyStoreCertificadoProvider(new CertificadoDigitalProperties(
            "classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"))),
        new AssinaturaValidator(), new XsdValidator());
  }

  @ParameterizedTest(name = "{0} com cClassTrib informado {1} -> {2}, {3}%")
  @CsvSource({
      // NCM,   informado, aplicado, reducao
      "04012090, ,       200036, 60",
      "04012010, ,       200003, 100",
      "04011010, ,       200003, 100",
      "04011090, ,       200036, 60",
      "04012090, 200003, 200003, 100",
      "04012010, 200003, 200003, 100"
  })
  @DisplayName("resolvedor: leite sem informado = in natura, UHT = zero; com 200003 = zero")
  void resolvedor(String ncm, String informado, String aplicado, String reducao) {
    NotaFiscalItem item = nota(ncm, informado).getItens().get(0);

    ReducaoAplicada aplicada = resolver.tratamento(item);

    assertThat(aplicada.cClassTrib()).isEqualTo(aplicado);
    assertThat(aplicada.percentualReducao()).isEqualByComparingTo(reducao);
  }

  @Test
  @DisplayName("XML do leite sem informado: CST 200, cClassTrib 200036 e gRed de 60%")
  void xmlInNatura() {
    NotaFiscal nota = nota("04012090", null);
    tributacao.calcular(nota);

    String xml = geracao.gerarXml(nota); // valida regra fiscal com a tabela e o XSD

    assertThat(nota.getItens().get(0).getCClassTribAplicado()).isEqualTo("200036");
    assertThat(xml).contains("<CST>200</CST>")
        .contains("<cClassTrib>200036</cClassTrib>")
        .contains("<vBC>1000.00</vBC>")
        .contains("<pIBSUF>0.1000</pIBSUF><gRed><pRedAliq>60.0000</pRedAliq>"
            + "<pAliqEfet>0.0400</pAliqEfet></gRed>")
        .contains("<pCBS>0.9000</pCBS><gRed><pRedAliq>60.0000</pRedAliq>")
        .contains("<vIBS>0.40</vIBS>")
        .contains("<vCBS>3.60</vCBS>");
  }

  @Test
  @DisplayName("XML do leite com 200003 informado: CST 200, cClassTrib 200003 e aliquota zero")
  void xmlConsumoDireto() {
    NotaFiscal nota = nota("04012090", "200003");
    tributacao.calcular(nota);

    String xml = geracao.gerarXml(nota);

    assertThat(xml).contains("<CST>200</CST>")
        .contains("<cClassTrib>200003</cClassTrib>")
        .contains("<pRedAliq>100.0000</pRedAliq><pAliqEfet>0.0000</pAliqEfet>")
        .contains("<vIBS>0.00</vIBS>")
        .contains("<vCBS>0.00</vCBS>");
  }

  @Test
  @DisplayName("validador: leite com 200003 mas reducao de 60% informada e recusado")
  void validadorRecusaReducaoDivergente() {
    NotaFiscal nota = nota("04012090", "200003");
    nota.getItens().get(0).setPercentualReducao(new BigDecimal("60"));
    tributacao.calcular(nota);

    assertThatThrownBy(() -> geracao.gerarXml(nota))
        .isInstanceOfSatisfying(NotaFiscalInvalidaParaEmissaoException.class,
            e -> assertThat(e.getErrors()).anySatisfy(d -> {
              assertThat(d.field()).endsWith(".percentualReducao");
              assertThat(d.message()).contains("200003");
            }));
  }

  @Test
  @DisplayName("#375: 200003 informado em NCM fora do Anexo I (soja) e recusado com a sugestao")
  void anexoInformadoForaDoNcmRecusado() {
    NotaFiscal nota = nota("12019000", "200003");
    tributacao.calcular(nota);

    assertThatThrownBy(() -> geracao.gerarXml(nota))
        .isInstanceOfSatisfying(NotaFiscalInvalidaParaEmissaoException.class,
            e -> assertThat(e.getErrors()).anySatisfy(d -> {
              assertThat(d.field()).endsWith(".cClassTrib");
              assertThat(d.message()).contains("200003").contains("12019000");
              assertThat(d.rejectedValue()).isEqualTo("200003");
              assertThat(d.expectedValue()).isEqualTo("200036, 200038, 200034");
              assertThat(d.suggestion()).contains("200036").contains("60% de reducao");
            }));
  }

  @Test
  @DisplayName("#389: o aviso do rascunho usa a mesma tabela do validador, sem validar o resto")
  void avisoDoRascunhoSegueARegraDoValidador() {
    SinaisNotaConferirService sinais = new SinaisNotaConferirService(
        mock(br.com.agrocontabil.repository.NotaFiscalRepository.class), ncmReferenciaRepository,
        classificacao, java.time.Clock.fixed(java.time.Instant.parse("2026-11-06T15:00:00Z"),
            java.time.ZoneId.of("America/Sao_Paulo")));

    // O 200003 (Anexo I) nao vale para a soja; no leite vale. Sem codigo informado, sem aviso.
    assertThat(sinais.avisosDoRascunho(nota("12019000", "200003")))
        .extracting(SinalNota::numeroItem, SinalNota::codigo)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(1,
            SinalNota.CodigoSinalNota.CCLASSTRIB_NAO_ADMITIDO));
    assertThat(sinais.avisosDoRascunho(nota("04012090", "200003"))).isEmpty();
    assertThat(sinais.avisosDoRascunho(nota("12019000", null))).isEmpty();
  }

  @Test
  @DisplayName("#375: outro codigo de reducao (200009, medicamentos) na soja tambem e recusado")
  void codigoDeOutraHipoteseRecusado() {
    NotaFiscal nota = nota("12019000", "200009");
    tributacao.calcular(nota);

    assertThatThrownBy(() -> validador.validar(nota))
        .isInstanceOfSatisfying(NotaFiscalInvalidaParaEmissaoException.class,
            e -> assertThat(e.getErrors())
                .anySatisfy(d -> assertThat(d.rejectedValue()).isEqualTo("200009")));
  }

  @ParameterizedTest(name = "{0} com {1} informado passa")
  @CsvSource({
      "04012090, 200003",  // leite no Anexo I, item 2
      "04072100, 200014",  // ovos no Anexo XV
      "12019000, 200036",  // in natura nao e codigo de anexo
      "12019000, 000001"   // integral
  })
  @DisplayName("#375: codigo de anexo que alcanca o NCM, ou codigo sem anexo, passa")
  void informadoCoerentePassa(String ncm, String cClassTrib) {
    NotaFiscal nota = nota(ncm, cClassTrib);
    tributacao.calcular(nota);

    org.assertj.core.api.Assertions.assertThatCode(() -> validador.validar(nota))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("#375: nota importada com codigo de anexo fora do NCM nao e recusada por isso")
  void importadaNaoERecusada() {
    NotaFiscal nota = nota("12019000", "200003");
    nota.setOrigem(br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA);
    tributacao.calcular(nota);

    org.assertj.core.api.Assertions.assertThatCode(() -> validador.validar(nota))
        .doesNotThrowAnyException();
  }

  private static NotaFiscal nota(String ncm, String cClassTrib) {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("LUIZ PRODUTOR DE LEITE")
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
        .codigoProduto("LEITE")
        .descricao("Leite")
        .ncm(ncm)
        .cfop("5101")
        .unidade("L")
        .quantidade(new BigDecimal("400.0000"))
        .valorUnitario(new BigDecimal("2.5000"))
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
        .destinatarioNome("LATICINIO SA")
        .destinatarioCpfCnpj("12345678000195")
        .destinatarioIe("9071425318") // laticinio contribuinte: 5101 interna sai com ICMS51 (#376)
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
}
