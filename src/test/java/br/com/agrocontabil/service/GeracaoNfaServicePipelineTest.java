package br.com.agrocontabil.service;
import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.nfe.AssinaturaValidator;
import br.com.agrocontabil.nfe.KeyStoreCertificadoProvider;
import br.com.agrocontabil.nfe.NfeAssinador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.exception.NfaEmitenteInvalidoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoDocumento;
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

@DisplayName("GeracaoNfaService — pipeline real (sem Spring, sem banco)")
class GeracaoNfaServicePipelineTest {

  private GeracaoNfaService nfaService;
  private CalculoIbsCbsService calculoIbsCbsService;

  @BeforeEach
  void setUp() {
    TributosProperties props = new TributosProperties();
    TributosProperties.Ibs ibs = new TributosProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TributosProperties.Cbs cbs = new TributosProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    calculoIbsCbsService = new CalculoIbsCbsService(props);

    GeracaoNotaFiscalService pipeline = new GeracaoNotaFiscalService(
        new FiscalRulesValidator(),
        new NotaFiscalNfeMapper(new ChaveAcessoGenerator()),
        new XmlGenerator(),
        new NfeAssinador(new KeyStoreCertificadoProvider(
            new CertificadoDigitalProperties(
                "classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"))),
        new AssinaturaValidator(),
        new XsdValidator());
    nfaService = new GeracaoNfaService(pipeline);
  }

  @Test
  @DisplayName("gera XML valido com CPF do emitente e marcador NFA para produtor PF")
  void geraXmlValidoParaNfaPf() {
    NotaFiscal nota = buildNfaCompleta();
    calculoIbsCbsService.calcular(nota);

    String xml = nfaService.gerarXml(nota);

    assertThat(xml).startsWith("<?xml");
    assertThat(xml).contains("<CPF>52998224725</CPF>");
    assertThat(xml).contains("NOTA FISCAL AVULSA");
  }

  @Test
  @DisplayName("rejeita (422) NFA cujo emitente e PJ, sem gerar XML")
  void rejeitaNfaComEmitentePj() {
    NotaFiscal nota = buildNfaCompleta();
    nota.getProdutor().setTipoProdutor(TipoProdutor.PESSOA_JURIDICA);
    nota.getProdutor().setCpfCnpj("12345678000195");
    calculoIbsCbsService.calcular(nota);

    assertThatThrownBy(() -> nfaService.gerarXml(nota))
        .isInstanceOf(NfaEmitenteInvalidoException.class);
  }

  private NotaFiscal buildNfaCompleta() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("JOAO DA SILVA AGRICULTOR")
        .cpfCnpj("52998224725")
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
        .tipoDocumento(TipoDocumento.NFA)
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
