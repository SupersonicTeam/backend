package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.service.ClassificacaoTributariaService;
import br.com.agrocontabil.validation.NcmValidator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes da validação do emitente e do destinatário no {@link FiscalRulesValidator}
 * (Onda 2, issue #88).
 *
 * <p>Antes desta issue, IE e endereço do emitente não existiam no modelo e o mapper preenchia o
 * XML com placeholders: a nota passava no XSD e seria rejeitada pela SEFAZ real. Aqui garantimos
 * que a emissão é barrada com 422 antes de gerar um XML impróprio.
 */
class FiscalRulesValidatorTest {

  private FiscalRulesValidator validator;

  @BeforeEach
  void setUp() {
    validator = new FiscalRulesValidator();
  }

  @Test
  @DisplayName("emitente completo e destinatario coerente passam na validacao")
  void notaValidaPassa() {
    assertThatCode(() -> validator.validar(notaValida())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("item com NCM fora da referencia e barrado na emissao, nao so no request (#282)")
  void rejeitaNcmForaDaReferencia() {
    NcmReferenciaRepository repository = mock(NcmReferenciaRepository.class);
    when(repository.findAllCodigos()).thenReturn(List.of("12019000"));
    NcmValidator ncmValidator = new NcmValidator(repository);
    ncmValidator.carregarCache();
    FiscalRulesValidator comReferencia = new FiscalRulesValidator(ncmValidator);
    NotaFiscal nota = notaValida();
    // Codigo removido pelo changeSet 0046: um rascunho gravado antes segue com ele.
    nota.getItens().get(0).setNcm("01039000");

    Throwable erro = catchThrowable(() -> comReferencia.validar(nota));

    assertThat(erro).isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);
    assertThat(((NotaFiscalInvalidaParaEmissaoException) erro).getErrors())
        .extracting(ErrorDetail::field)
        .contains("item[" + nota.getItens().get(0).getNumeroItem() + "].ncm");
    nota.getItens().get(0).setNcm("12019000");
    assertThatCode(() -> comReferencia.validar(nota)).doesNotThrowAnyException();
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.CsvSource({
      // caso,                         CST, cClassTrib aplicado, reducao, campo com erro
      "cClassTrib inexistente,         ,    010100, 0,  cClassTrib",
      "CST divergente do cClassTrib,   101, 200036, 60, cstIbsCbs",
      "reducao divergente da oficial,  ,    200036, 30, percentualReducao",
      "percentual sem cClassTrib,      ,    ,       60, cClassTrib",
      "CST nao suportado (410),        ,    410014, 0,  cClassTrib"
  })
  @DisplayName("IBS/CBS do item: cClassTrib, CST e reducao conferidos na tabela oficial (#278)")
  void rejeitaTributacaoIbsCbsInconsistente(String caso, String cst, String cClassTrib,
      String reducao, String campo) {
    ClassificacaoTributariaService classificacao = mock(ClassificacaoTributariaService.class);
    // Nem toda linha chega a consultar a tabela (percentual sem cClassTrib para antes).
    org.mockito.Mockito.lenient().when(classificacao.classificacao(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(java.util.Optional.empty());
    org.mockito.Mockito.lenient().when(classificacao.classificacao(
        org.mockito.ArgumentMatchers.eq("200036"),
        org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.of(
            br.com.agrocontabil.model.ClassificacaoTributaria.builder().codigo("200036")
                .percentualReducaoIbs(new BigDecimal("60")).build()));
    FiscalRulesValidator completo = new FiscalRulesValidator(null, classificacao);
    NotaFiscal nota = notaValida();
    NotaFiscalItem item = nota.getItens().get(0);
    item.setValorIbs(new BigDecimal("0.40"));
    item.setCstIbsCbs(cst);
    if (cst != null) {
      // CST informado so vale com o cClassTrib informado junto (#278).
      item.setCClassTrib(cClassTrib);
    }
    item.setCClassTribAplicado(cClassTrib);
    item.setPercentualReducaoAplicado(new BigDecimal(reducao));

    Throwable erro = catchThrowable(() -> completo.validar(nota));

    assertThat(erro).as(caso).isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);
    assertThat(((NotaFiscalInvalidaParaEmissaoException) erro).getErrors())
        .extracting(ErrorDetail::field).as(caso).contains("item[1]." + campo);
  }

  @Test
  @DisplayName("CST informado sem cClassTrib (resto do exemplo antigo do OpenAPI) e ignorado (#278)")
  void cstSemCClassTribIgnorado() {
    NotaFiscal nota = notaValida();
    NotaFiscalItem item = nota.getItens().get(0);
    item.setValorIbs(new BigDecimal("0.40"));
    item.setCstIbsCbs("000");
    item.setCClassTribAplicado("200036");

    assertThat(NotaFiscalNfeMapper.cst(item)).isEqualTo("200");
    assertThatCode(() -> validator.validar(nota)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("IBS/CBS coerente com a tabela oficial passa (#278)")
  void aceitaTributacaoIbsCbsCoerente() {
    ClassificacaoTributariaService classificacao = mock(ClassificacaoTributariaService.class);
    when(classificacao.classificacao(org.mockito.ArgumentMatchers.eq("200036"),
        org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.of(
            br.com.agrocontabil.model.ClassificacaoTributaria.builder().codigo("200036")
                .percentualReducaoIbs(new BigDecimal("60")).build()));
    NotaFiscal nota = notaValida();
    NotaFiscalItem item = nota.getItens().get(0);
    item.setValorIbs(new BigDecimal("0.40"));
    item.setCClassTribAplicado("200036");
    item.setPercentualReducaoAplicado(new BigDecimal("60.00"));

    assertThatCode(() -> new FiscalRulesValidator(null, classificacao).validar(nota))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("NF-e modelo 55 sem Inscricao Estadual do emitente e rejeitada")
  void rejeitaEmitenteSemIeQuandoNfe() {
    NotaFiscal nota = notaValida();
    nota.setProdutor(ProdutorTestFixtures.produtorSemIe());

    assertThat(violacoesDe(nota)).contains("produtor.inscricaoEstadual");
  }

  @Test
  @DisplayName("NFA sem Inscricao Estadual e aceita (produtor rural PF sem IE emite ISENTO)")
  void aceitaIsentoQuandoNfa() {
    NotaFiscal nota = notaValida();
    nota.setProdutor(ProdutorTestFixtures.produtorSemIe());
    nota.setTipoDocumento(TipoDocumentoFiscal.NFA);

    assertThatCode(() -> validator.validar(nota)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("endereco incompleto do emitente gera uma violacao por campo faltante")
  void rejeitaEnderecoIncompleto() {
    NotaFiscal nota = notaValida();
    nota.setProdutor(ProdutorTestFixtures.produtorEnderecoIncompleto());

    assertThat(violacoesDe(nota)).contains(
        "produtor.endereco.logradouro",
        "produtor.endereco.bairro",
        "produtor.endereco.cep");
  }

  @Test
  @DisplayName("emitente sem endereco algum e rejeitado com uma violacao no campo raiz")
  void rejeitaEmitenteSemEndereco() {
    NotaFiscal nota = notaValida();
    Produtor semEndereco = ProdutorTestFixtures.produtorCompleto();
    semEndereco.setEndereco(null);
    nota.setProdutor(semEndereco);

    assertThat(violacoesDe(nota)).contains("produtor.endereco");
  }

  @Test
  @DisplayName("codigo IBGE do emitente de outra UF e rejeitado")
  void rejeitaIbgeIncoerenteComUf() {
    NotaFiscal nota = notaValida();
    // Produtor de MT com o codigo IBGE de Sao Paulo capital (3550308).
    nota.setProdutor(ProdutorTestFixtures.produtorIbgeIncoerente());

    assertThat(violacoesDe(nota)).contains("produtor.endereco.codigoMunicipioIbge");
  }

  @Test
  @DisplayName("codigo IBGE do destinatario de outra UF e rejeitado")
  void rejeitaIbgeDestinatarioIncoerenteComUf() {
    NotaFiscal nota = notaValida();
    // Destinatario declarado em MG com o codigo IBGE de Sao Paulo capital.
    nota.getDestinatarioEndereco().setCodigoMunicipioIbge("3550308");

    assertThat(violacoesDe(nota)).contains("destinatarioEndereco.codigoMunicipioIbge");
  }

  @Test
  @DisplayName("numero acima de 999.999.999 e rejeitado na emissao (defesa em profundidade)")
  void rejeitaNumeroAcimaDoLimite() {
    NotaFiscal nota = notaValida();
    nota.setNumero(1_000_000_000L);

    assertThat(violacoesDe(nota)).contains("numero");
  }

  @Test
  @DisplayName("serie acima de 999 e rejeitada na emissao")
  void rejeitaSerieAcimaDoLimite() {
    NotaFiscal nota = notaValida();
    nota.setSerie(1000);

    assertThat(violacoesDe(nota)).contains("serie");
  }

  @Test
  @DisplayName("UF do emitente fora das 27 e rejeitada na validacao, nao no mapper")
  void rejeitaUfEmitenteDesconhecida() {
    NotaFiscal nota = notaValida();
    Produtor ufInvalida = ProdutorTestFixtures.produtorCompleto();
    ufInvalida.setEstado("XX");
    nota.setProdutor(ufInvalida);

    assertThat(violacoesDe(nota)).contains("produtor.estado");
  }

  @Test
  @DisplayName("destinatario sem logradouro e rejeitado (o XSD exige xLgr com 2+ caracteres)")
  void rejeitaDestinatarioSemLogradouro() {
    NotaFiscal nota = notaValida();
    nota.getDestinatarioEndereco().setLogradouro(null);

    assertThat(violacoesDe(nota)).contains("destinatarioEndereco.logradouro");
  }

  // ---------------------------------------------------------------------------------------

  /** Campos das violações do 422; falha o teste se a validação passar. */
  private List<String> violacoesDe(NotaFiscal nota) {
    Throwable erro = catchThrowable(() -> validator.validar(nota));
    assertThat(erro).isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);
    return ((NotaFiscalInvalidaParaEmissaoException) erro)
        .getErrors().stream().map(ErrorDetail::field).toList();
  }

  private NotaFiscal notaValida() {
    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .descricao("Soja em graos")
        .ncm("12019000")
        .cfop("6101")
        .unidade("KG")
        .quantidade(BigDecimal.ONE)
        .valorUnitario(new BigDecimal("1000.00"))
        .valorTotal(new BigDecimal("1000.00"))
        .build();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .tipoDocumento(TipoDocumentoFiscal.NFE)
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(ProdutorTestFixtures.produtorCompleto())
        .destinatarioNome("Comprador Teste")
        .destinatarioCpfCnpj("98765432100")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rua das Acacias")
            .numero("250")
            .municipio("Uberaba")
            .codigoMunicipioIbge("3170206")
            .uf("MG")
            .build())
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(StatusNotaFiscal.RASCUNHO)
        .build();
    nota.addItem(item);
    return nota;
  }
}
