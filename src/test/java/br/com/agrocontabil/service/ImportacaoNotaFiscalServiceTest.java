package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.dto.ImportacaoNotaResultado;
import br.com.agrocontabil.dto.ImportacaoNotaResultado.Status;
import br.com.agrocontabil.event.NotaImportadaEvent;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import br.com.agrocontabil.exception.SefazRespostaIndeterminadaException;
import br.com.agrocontabil.exception.XmlImportacaoInvalidaException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.nfe.AssinaturaValidator;
import br.com.agrocontabil.nfe.NfeToNotaFiscalMapper;
import br.com.agrocontabil.nfe.NfeXmlImporter;
import br.com.agrocontabil.nfe.NfeXmlImporter.ResultadoImportacao;
import br.com.agrocontabil.nfe.model.Dest;
import br.com.agrocontabil.nfe.model.InfNFe;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import br.com.agrocontabil.service.ImportacaoNotaFiscalService.ArquivoXml;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Testes unitarios do {@link ImportacaoNotaFiscalService} (issues #19 e #223) — resiliencia do
 * lote, dedup por chave do produtor, destinatario, assinatura e confirmacao na SEFAZ, isolando
 * parser/mapper/repositorio/SEFAZ com mocks.
 */
@ExtendWith(MockitoExtension.class)
class ImportacaoNotaFiscalServiceTest {

  private static final String CPF = "52998224725";
  private static final String DIGEST = "q1w2e3r4t5y6u7i8o9p0asdfghj=";
  private static final String XML_ASSINADO =
      "<NFe><Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"><DigestValue>" + DIGEST
          + "</DigestValue></Signature></NFe>";

  @Mock private NfeXmlImporter nfeXmlImporter;
  @Mock private NfeToNotaFiscalMapper nfeToNotaFiscalMapper;
  @Mock private NotaFiscalRepository notaFiscalRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;
  @Mock private CreditoTributarioService creditoTributarioService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private TransactionTemplate transactionTemplate;
  @Mock private AssinaturaValidator assinaturaValidator;
  @Mock private SefazClient sefazClient;

  @InjectMocks private ImportacaoNotaFiscalService service;

  private static final ZoneId FUSO_BRASIL = ZoneId.of("America/Sao_Paulo");

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Produtor").cpfCnpj(CPF)
        .build();
    when(produtorProvider.get()).thenReturn(produtor);
  }

  /**
   * Faz o {@code transactionTemplate} mockado executar o callback de fato (sincronamente, na
   * mesma thread), simulando a transacao real que o {@code publishEvent} do issue #64 depende.
   * Estubado so nos testes que efetivamente chegam a persistir.
   */
  private void stubTransactionTemplateExecutaCallback() {
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(new SimpleTransactionStatus());
    });
  }

  /** NF-e cujo {@code dest} e o documento informado. */
  private static NFe nfeParaDestinatario(String documento) {
    Dest dest = new Dest();
    if (documento.length() == 14) {
      dest.setCnpj(documento);
    } else {
      dest.setCpf(documento);
    }
    InfNFe infNFe = new InfNFe();
    infNFe.setDest(dest);
    NFe nfe = new NFe();
    nfe.setInfNFe(infNFe);
    return nfe;
  }

  /** Arquivo valido do produtor ate o mapeamento, com a nota mapeada em PENDENTE_SEFAZ. */
  private NotaFiscal arquivoDoProdutor(ArquivoXml arquivo, String chave) {
    when(nfeXmlImporter.importar(arquivo.conteudo()))
        .thenReturn(new ResultadoImportacao(nfeParaDestinatario(CPF), XML_ASSINADO));
    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .origem(OrigemNotaFiscal.IMPORTADA)
        .status(StatusNotaFiscal.PENDENTE_SEFAZ)
        .chaveAcesso(chave)
        .emitenteCpfCnpj("11222333000181")
        .dataEmissao(OffsetDateTime.parse("2026-07-10T10:00:00-03:00"))
        .xmlGerado(XML_ASSINADO)
        .build();
    when(nfeToNotaFiscalMapper.toNotaFiscal(any(), any(), anyString())).thenReturn(nota);
    when(notaFiscalRepository.existsByProdutorIdAndChaveAcessoAndOrigem(
        produtor.getId(), chave, OrigemNotaFiscal.IMPORTADA)).thenReturn(false);
    return nota;
  }

  private static ArquivoXml arquivo(String nome) {
    return new ArquivoXml(nome, ("<" + nome + "/>").getBytes(StandardCharsets.UTF_8));
  }

  private static SefazRetorno autorizada(String digest) {
    return new SefazRetorno(SituacaoSefaz.AUTORIZADA, "100", "Autorizado o uso da NF-e",
        "135260000000001", OffsetDateTime.parse("2026-07-10T10:01:00-03:00"), digest);
  }

  @Test
  @DisplayName("Lote com 1 valido + 1 invalido: resultado parcial (nao derruba o lote)")
  void loteParcial() {
    ArquivoXml valido = arquivo("ok");
    ArquivoXml invalido = arquivo("ruim");
    when(nfeXmlImporter.importar(invalido.conteudo()))
        .thenThrow(new XmlImportacaoInvalidaException("XML malformado."));
    String chave = "1".repeat(44);
    NotaFiscal nota = arquivoDoProdutor(valido, chave);
    when(sefazClient.consultarStatus(chave, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(autorizada(DIGEST));
    when(notaFiscalRepository.save(nota)).thenReturn(nota);
    stubTransactionTemplateExecutaCallback();

    List<ImportacaoNotaResultado> resultados = service.importar(List.of(valido, invalido));

    assertThat(resultados).hasSize(2);
    assertThat(resultados.get(0).sucesso()).isTrue();
    assertThat(resultados.get(0).status()).isEqualTo(Status.IMPORTADA);
    assertThat(resultados.get(1).sucesso()).isFalse();
    assertThat(resultados.get(1).status()).isEqualTo(Status.INVALIDA);
    // RN003 (issue Agrocontabil#30): o credito tributario e calculado antes de persistir.
    verify(creditoTributarioService).calcular(nota);
  }

  @Test
  @DisplayName("SEFAZ confirma com o mesmo digVal: AUTORIZADA com o protocolo da SEFAZ e evento")
  void autorizadaPelaSefazPublicaEvento() {
    ArquivoXml arquivo = arquivo("autorizada");
    String chave = "3".repeat(44);
    NotaFiscal nota = arquivoDoProdutor(arquivo, chave);
    when(sefazClient.consultarStatus(chave, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(autorizada(DIGEST));
    when(notaFiscalRepository.save(nota)).thenReturn(nota);
    stubTransactionTemplateExecutaCallback();

    service.importar(List.of(arquivo));

    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getProtocoloAutorizacao()).isEqualTo("135260000000001");
    assertThat(nota.getSefazAmbiente()).isEqualTo(SefazAmbiente.HOMOLOGACAO);
    verify(eventPublisher).publishEvent(new NotaImportadaEvent(produtor.getId(),
        YearMonth.from(nota.getDataEmissao().atZoneSameInstant(FUSO_BRASIL))));
  }

  @Test
  @DisplayName("Destinatario de outro CPF/CNPJ: INVALIDA, sem assinatura, SEFAZ nem gravacao (#223)")
  void destinatarioDeOutroDocumento() {
    ArquivoXml arquivo = arquivo("alheia");
    when(nfeXmlImporter.importar(arquivo.conteudo()))
        .thenReturn(new ResultadoImportacao(nfeParaDestinatario("39053344705"), XML_ASSINADO));

    ImportacaoNotaResultado resultado = service.importar(List.of(arquivo)).get(0);

    assertThat(resultado.status()).isEqualTo(Status.INVALIDA);
    assertThat(resultado.motivo()).contains("destinatario").doesNotContain("39053344705");
    verifyNoInteractions(assinaturaValidator, nfeToNotaFiscalMapper, sefazClient,
        transactionTemplate);
  }

  @Test
  @DisplayName("Destinatario com mascara no cadastro e o mesmo documento: segue (so digitos)")
  void destinatarioComparaSoDigitos() {
    produtor.setCpfCnpj("529.982.247-25");
    ArquivoXml arquivo = arquivo("mascara");
    String chave = "4".repeat(44);
    NotaFiscal nota = arquivoDoProdutor(arquivo, chave);
    when(sefazClient.consultarStatus(chave, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(autorizada(DIGEST));
    when(notaFiscalRepository.save(nota)).thenReturn(nota);
    stubTransactionTemplateExecutaCallback();

    assertThat(service.importar(List.of(arquivo)).get(0).status()).isEqualTo(Status.IMPORTADA);
  }

  @Test
  @DisplayName("Assinatura invalida: INVALIDA, sem consultar a SEFAZ nem gravar (#223)")
  void assinaturaInvalida() {
    ArquivoXml arquivo = arquivo("adulterada");
    when(nfeXmlImporter.importar(arquivo.conteudo()))
        .thenReturn(new ResultadoImportacao(nfeParaDestinatario(CPF), XML_ASSINADO));
    doThrow(new AssinaturaDigitalException(ErrorCode.ASSINATURA_INVALIDA, "nao confere"))
        .when(assinaturaValidator).validar(XML_ASSINADO);

    ImportacaoNotaResultado resultado = service.importar(List.of(arquivo)).get(0);

    assertThat(resultado.status()).isEqualTo(Status.INVALIDA);
    assertThat(resultado.motivo()).contains("Assinatura");
    verifyNoInteractions(sefazClient, transactionTemplate);
  }

  @ParameterizedTest
  @EnumSource(value = SituacaoSefaz.class,
      names = {"NAO_ENCONTRADA", "REJEITADA", "DENEGADA", "CANCELADA"})
  @DisplayName("SEFAZ nao confirma (protNFe forjado, rejeitada, cancelada): INVALIDA, nada gravado")
  void sefazNaoConfirma(SituacaoSefaz situacao) {
    ArquivoXml arquivo = arquivo("forjada");
    String chave = "5".repeat(44);
    arquivoDoProdutor(arquivo, chave);
    when(sefazClient.consultarStatus(chave, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(new SefazRetorno(situacao, "217", "NF-e nao consta", null, null));

    ImportacaoNotaResultado resultado = service.importar(List.of(arquivo)).get(0);

    assertThat(resultado.status()).isEqualTo(Status.INVALIDA);
    assertThat(resultado.motivo()).contains("SEFAZ");
    verifyNoInteractions(transactionTemplate, creditoTributarioService, eventPublisher);
  }

  @Test
  @DisplayName("Autorizada com digVal diferente: outro conteudo com a mesma chave, INVALIDA (#223)")
  void digestDiferente() {
    ArquivoXml arquivo = arquivo("conteudo-trocado");
    String chave = "6".repeat(44);
    arquivoDoProdutor(arquivo, chave);
    when(sefazClient.consultarStatus(chave, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(autorizada("outroDigestDoXmlAutorizado="));

    ImportacaoNotaResultado resultado = service.importar(List.of(arquivo)).get(0);

    assertThat(resultado.status()).isEqualTo(Status.INVALIDA);
    assertThat(resultado.motivo()).contains("difere");
    verifyNoInteractions(transactionTemplate, eventPublisher);
  }

  @Test
  @DisplayName("SEFAZ sem resposta: grava PENDENTE_SEFAZ, sem evento (o job confirma depois)")
  void sefazSemResposta() {
    ArquivoXml arquivo = arquivo("sem-resposta");
    String chave = "7".repeat(44);
    NotaFiscal nota = arquivoDoProdutor(arquivo, chave);
    when(sefazClient.consultarStatus(eq(chave), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("timeout de leitura"));
    when(notaFiscalRepository.save(nota)).thenReturn(nota);
    stubTransactionTemplateExecutaCallback();

    ImportacaoNotaResultado resultado = service.importar(List.of(arquivo)).get(0);

    assertThat(resultado.sucesso()).isTrue();
    assertThat(resultado.motivo()).contains("confirmada depois");
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("SEFAZ fora do ar no lote: so a primeira consulta espera, as demais entram pendentes")
  void disjuntorDoLote() {
    ArquivoXml primeiro = arquivo("primeiro");
    ArquivoXml segundo = arquivo("segundo");
    NotaFiscal notaUm = arquivoDoProdutor(primeiro, "8".repeat(44));
    when(nfeXmlImporter.importar(segundo.conteudo()))
        .thenReturn(new ResultadoImportacao(nfeParaDestinatario(CPF), XML_ASSINADO));
    NotaFiscal notaDois = NotaFiscal.builder().chaveAcesso("9".repeat(44))
        .status(StatusNotaFiscal.PENDENTE_SEFAZ).xmlGerado(XML_ASSINADO).build();
    when(nfeToNotaFiscalMapper.toNotaFiscal(any(), any(), anyString()))
        .thenReturn(notaUm, notaDois);
    when(notaFiscalRepository.existsByProdutorIdAndChaveAcessoAndOrigem(
        produtor.getId(), notaDois.getChaveAcesso(), OrigemNotaFiscal.IMPORTADA))
        .thenReturn(false);
    when(sefazClient.consultarStatus(eq(notaUm.getChaveAcesso()), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("timeout de leitura"));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));
    stubTransactionTemplateExecutaCallback();

    List<ImportacaoNotaResultado> resultados = service.importar(List.of(primeiro, segundo));

    assertThat(resultados).allSatisfy(r -> assertThat(r.sucesso()).isTrue());
    assertThat(notaDois.getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    verify(sefazClient, times(1)).consultarStatus(any(), any());
  }

  @Test
  @DisplayName("Chave ja importada pelo produtor: DUPLICADA, sem SEFAZ e sem persistir")
  void chaveDuplicada() {
    ArquivoXml arquivo = arquivo("dup");
    when(nfeXmlImporter.importar(arquivo.conteudo()))
        .thenReturn(new ResultadoImportacao(nfeParaDestinatario(CPF), XML_ASSINADO));
    NotaFiscal nota = NotaFiscal.builder().chaveAcesso("2".repeat(44)).build();
    when(nfeToNotaFiscalMapper.toNotaFiscal(any(), any(), anyString())).thenReturn(nota);
    when(notaFiscalRepository.existsByProdutorIdAndChaveAcessoAndOrigem(
        produtor.getId(), nota.getChaveAcesso(), OrigemNotaFiscal.IMPORTADA)).thenReturn(true);

    List<ImportacaoNotaResultado> resultados = service.importar(List.of(arquivo));

    assertThat(resultados.get(0).status()).isEqualTo(Status.DUPLICADA);
    assertThat(resultados.get(0).sucesso()).isFalse();
    verify(notaFiscalRepository, never()).save(any());
    verifyNoInteractions(sefazClient, creditoTributarioService, eventPublisher);
  }

  @Test
  @DisplayName("XML acima de 2 MB: INVALIDA sem chegar ao parser (multipart aceita 5 MB, #180)")
  void xmlAcimaDoTeto() {
    ArquivoXml grande = new ArquivoXml("grande.xml",
        new byte[ImportacaoNotaFiscalService.TAMANHO_MAXIMO_XML + 1]);

    List<ImportacaoNotaResultado> resultados = service.importar(List.of(grande));

    assertThat(resultados.get(0).status()).isEqualTo(Status.INVALIDA);
    verifyNoInteractions(nfeXmlImporter);
  }
}
