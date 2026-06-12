package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Origem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.StatusNotaFiscal;
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

@DisplayName("NfeImportMapper — JAXB NFe -> NotaFiscal")
class NfeImportMapperTest {

  private NfeImportMapper mapper;
  private NfeXmlParser parser;
  private String nfeXml;

  @BeforeEach
  void setUp() {
    mapper = new NfeImportMapper();
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

    NotaFiscal origem = buildNota();
    calculo.calcular(origem);
    nfeXml = pipeline.gerarXml(origem);
  }

  @Test
  @DisplayName("extrai campos principais e marca como IMPORTADA/AUTORIZADA")
  void extraiCampos() {
    NFe nfe = parser.parse(nfeXml.getBytes(StandardCharsets.UTF_8));
    Produtor dono = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Dono").build();

    NotaFiscal nota = mapper.toEntity(nfe, nfeXml, dono);

    assertThat(nota.getOrigem()).isEqualTo(Origem.IMPORTADA);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getProdutor()).isSameAs(dono);
    assertThat(nota.getChaveAcesso()).hasSize(44);
    assertThat(nota.getModelo()).isEqualTo("55");
    assertThat(nota.getNumero()).isEqualTo(1L);
    assertThat(nota.getEmitenteCpfCnpj()).isEqualTo("12345678000195");
    assertThat(nota.getEmitenteNome()).isNotBlank();
    assertThat(nota.getDestinatarioCpfCnpj()).isEqualTo("98765432000199");
    assertThat(nota.getItens()).hasSize(1);
    assertThat(nota.getItens().get(0).getNcm()).isEqualTo("12010090");
    assertThat(nota.getItens().get(0).getCfop()).isEqualTo("5101");
    assertThat(nota.getValorTotalProdutos()).isEqualByComparingTo("1000.00");
    assertThat(nota.getXmlGerado()).isEqualTo(nfeXml);
    assertThat(nota.getDataGeracaoXml()).isNotNull();
  }

  private NotaFiscal buildNota() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID()).nomeCompleto("EMITENTE TERCEIRO LTDA")
        .cpfCnpj("12345678000195").tipoProdutor(TipoProdutor.PESSOA_JURIDICA)
        .regimeTributario(RegimeTributario.SIMPLES).estado("PR").build();
    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID()).numeroItem(1).codigoProduto("SOJA001").descricao("Soja em grao")
        .ncm("12010090").cfop("5101").unidade("KG")
        .quantidade(new BigDecimal("1000.0000")).valorUnitario(new BigDecimal("1.0000"))
        .valorTotal(new BigDecimal("1000.00")).cstIbsCbs("101").cClassTrib("010100").build();
    EnderecoDestinatario endDest = EnderecoDestinatario.builder()
        .logradouro("Rua das Flores").numero("100").municipio("Curitiba")
        .codigoMunicipioIbge("4106902").uf("PR").cep("80010000").build();
    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID()).numero(1L).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor).destinatarioNome("COMPRADORA SA")
        .destinatarioCpfCnpj("98765432000199").destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1000.00")).valorTotalNota(new BigDecimal("1000.00"))
        .build();
    nota.addItem(item);
    return nota;
  }
}
