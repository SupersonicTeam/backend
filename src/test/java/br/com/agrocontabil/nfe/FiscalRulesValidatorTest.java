package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
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
