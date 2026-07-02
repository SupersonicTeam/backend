package br.com.agrocontabil.nfe;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.service.ChaveAcessoGenerator;
import br.com.agrocontabil.service.GeracaoNotaFiscalService;
import br.com.agrocontabil.service.TributacaoService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Fixtures compartilhados para os testes de importacao de XML (issue #19).
 *
 * <p>Gera um XML de NF-e <strong>real, assinado e valido no XSD</strong> usando o mesmo
 * pipeline de emissao do sistema (sem Spring/banco), permitindo exercitar a importacao com
 * um documento representativo. Tambem monta o envelope {@code nfeProc} com {@code protNFe}.
 */
public final class NfeImportTestFixtures {

  /** Numero de NF-e usado no XML gerado (emitente terceiro). */
  public static final long NUMERO = 12345L;
  public static final int SERIE = 2;
  public static final String EMITENTE_CNPJ = "11222333000181";
  public static final String EMITENTE_NOME = "FORNECEDORA DE INSUMOS LTDA";

  private NfeImportTestFixtures() {}

  /** Monta o pipeline de geracao com o certificado de teste (autoassinado). */
  public static GeracaoNotaFiscalService pipeline() {
    ChaveAcessoGenerator chaveAcessoGenerator = new ChaveAcessoGenerator();
    FiscalRulesValidator validator = new FiscalRulesValidator();
    NotaFiscalNfeMapper mapper = new NotaFiscalNfeMapper(chaveAcessoGenerator);
    XmlGenerator xmlGenerator = new XmlGenerator();
    KeyStoreCertificadoProvider certProvider = new KeyStoreCertificadoProvider(
        new CertificadoDigitalProperties(
            "classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"));
    NfeAssinador nfeAssinador = new NfeAssinador(certProvider);
    AssinaturaValidator assinaturaValidator = new AssinaturaValidator();
    XsdValidator xsdValidator = new XsdValidator();
    return new GeracaoNotaFiscalService(
        validator, mapper, xmlGenerator, nfeAssinador, assinaturaValidator, xsdValidator);
  }

  /**
   * Gera o XML {@code <NFe>} assinado de uma nota emitida por um terceiro (CNPJ fornecedor).
   * O produtor que importa sera o destinatario — mas o XML em si e o do emitente.
   */
  public static String gerarNfeAssinada() {
    Produtor emitente = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto(EMITENTE_NOME)
        .cpfCnpj(EMITENTE_CNPJ)
        .tipoProdutor(TipoProdutor.PESSOA_JURIDICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .estado("PR")
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("FERT-001")
        .descricao("Fertilizante NPK 20-05-20")
        .ncm("31052000")
        .cfop("6101")
        .unidade("KG")
        .quantidade(new BigDecimal("500.0000"))
        .valorUnitario(new BigDecimal("3.5000"))
        .valorTotal(new BigDecimal("1750.00"))
        .cstIbsCbs("101")
        .cClassTrib("010100")
        .build();

    // Destinatario em SC (emitente em PR) para casar com o CFOP 6101 (interestadual).
    EnderecoDestinatario endDest = EnderecoDestinatario.builder()
        .logradouro("Estrada Rural KM 10")
        .numero("SN")
        .municipio("Chapeco")
        .codigoMunicipioIbge("4204202")
        .uf("SC")
        .cep("89800000")
        .build();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(NUMERO)
        .serie(SERIE)
        .modelo("55")
        .naturezaOperacao("Venda de insumos agropecuarios")
        .dataEmissao(OffsetDateTime.of(2026, 5, 20, 9, 30, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(emitente)
        .destinatarioNome("JOAO DA SILVA PRODUTOR")
        .destinatarioCpfCnpj("52998224725")
        .destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1750.00"))
        .valorTotalNota(new BigDecimal("1750.00"))
        .build();
    nota.addItem(item);
    item.setNotaFiscal(nota);

    tributos().calcular(nota);
    return pipeline().gerarXml(nota);
  }

  /** Envolve um {@code <NFe>} assinado em um {@code nfeProc} com protNFe cStat=100. */
  public static String comoNfeProcAutorizada(String nfeXml) {
    String nfeSemDecl = removerDeclaracao(nfeXml);
    String chave = extrairChave(nfeSemDecl);
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe" versao="4.00">
        %s
          <protNFe versao="4.00">
            <infProt>
              <tpAmb>2</tpAmb>
              <verAplic>TESTE</verAplic>
              <chNFe>%s</chNFe>
              <dhRecbto>2026-05-20T09:31:00-03:00</dhRecbto>
              <nProt>141260000000001</nProt>
              <digVal>abc</digVal>
              <cStat>100</cStat>
              <xMotivo>Autorizado o uso da NF-e</xMotivo>
            </infProt>
          </protNFe>
        </nfeProc>
        """.formatted(nfeSemDecl, chave);
  }

  private static TributacaoService tributos() {
    TributosProperties props = new TributosProperties();
    TributosProperties.Ibs ibs = new TributosProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TributosProperties.Cbs cbs = new TributosProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    // Sem redução por NCM nas fixtures: repositório mockado devolve Optional.empty().
    NcmReferenciaRepository ncmRepo = mock(NcmReferenciaRepository.class);
    when(ncmRepo.findPercentualReducaoPadraoByCodigo(anyString())).thenReturn(Optional.empty());
    return new TributacaoService(props, ncmRepo);
  }

  private static String removerDeclaracao(String xml) {
    int idx = xml.indexOf("?>");
    return idx >= 0 ? xml.substring(idx + 2).trim() : xml.trim();
  }

  private static String extrairChave(String nfeXml) {
    String prefix = "Id=\"NFe";
    int start = nfeXml.indexOf(prefix) + prefix.length();
    return nfeXml.substring(start, start + 44);
  }
}
