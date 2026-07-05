package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.ImportacaoNotaResultado;
import br.com.agrocontabil.dto.ImportacaoNotaResultado.Status;
import br.com.agrocontabil.event.NotaImportadaEvent;
import br.com.agrocontabil.exception.XmlImportacaoInvalidaException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.nfe.NfeToNotaFiscalMapper;
import br.com.agrocontabil.nfe.NfeXmlImporter;
import br.com.agrocontabil.nfe.NfeXmlImporter.ResultadoImportacao;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.NotaFiscalRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Testes unitarios do {@link ImportacaoNotaFiscalService} (issue #19) — resiliencia do lote,
 * dedup por chave e nunca-500-por-arquivo-ruim, isolando parser/mapper/repositorio com mocks.
 */
@ExtendWith(MockitoExtension.class)
class ImportacaoNotaFiscalServiceTest {

  @Mock private NfeXmlImporter nfeXmlImporter;
  @Mock private NfeToNotaFiscalMapper nfeToNotaFiscalMapper;
  @Mock private NotaFiscalRepository notaFiscalRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;
  @Mock private CreditoTributarioService creditoTributarioService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private TransactionTemplate transactionTemplate;

  @InjectMocks private ImportacaoNotaFiscalService service;

  private static final ZoneId FUSO_BRASIL = ZoneId.of("America/Sao_Paulo");

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Produtor").build();
    when(produtorProvider.get()).thenReturn(produtor);
  }

  /**
   * Faz o {@code transactionTemplate} mockado executar o callback de fato (sincronamente, na
   * mesma thread), simulando a transacao real que o {@code publishEvent} do issue #64 depende.
   * Estubado so nos testes que efetivamente chegam a persistir (evita UnnecessaryStubbingException
   * nos testes de dedup/erro, que retornam antes de tocar o {@code transactionTemplate}).
   */
  private void stubTransactionTemplateExecutaCallback() {
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(new SimpleTransactionStatus());
    });
  }

  @Test
  @DisplayName("Lote com 1 valido + 1 invalido: resultado parcial (nao derruba o lote)")
  void loteParcial() {
    ArquivoXml valido = new ArquivoXml("ok.xml", "<ok/>".getBytes(StandardCharsets.UTF_8));
    ArquivoXml invalido = new ArquivoXml("ruim.xml", "<x".getBytes(StandardCharsets.UTF_8));

    NFe nfe = new NFe();
    when(nfeXmlImporter.importar(valido.conteudo()))
        .thenReturn(new ResultadoImportacao(nfe, "<NFe/>", true));
    when(nfeXmlImporter.importar(invalido.conteudo()))
        .thenThrow(new XmlImportacaoInvalidaException("XML malformado."));

    NotaFiscal nota = notaComChave("11111111111111111111111111111111111111111111");
    when(nfeToNotaFiscalMapper.toNotaFiscal(any(), any(), anyString(), anyBoolean()))
        .thenReturn(nota);
    when(notaFiscalRepository.existsByChaveAcesso(nota.getChaveAcesso())).thenReturn(false);
    when(notaFiscalRepository.save(nota)).thenReturn(nota);
    stubTransactionTemplateExecutaCallback();

    List<ImportacaoNotaResultado> resultados =
        service.importar(List.of(valido, invalido));

    assertThat(resultados).hasSize(2);
    assertThat(resultados.get(0).sucesso()).isTrue();
    assertThat(resultados.get(0).status()).isEqualTo(Status.IMPORTADA);
    assertThat(resultados.get(1).sucesso()).isFalse();
    assertThat(resultados.get(1).status()).isEqualTo(Status.INVALIDA);
    // RN003 (issue Agrocontabil#30): o credito tributario e calculado antes de persistir.
    verify(creditoTributarioService).calcular(nota);
    // issue Agrocontabil#64: nota importada em RASCUNHO (nfeProc nao autorizado) nao afeta
    // credito da apuracao — nenhum evento de dominio e publicado.
    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("Nota importada ja AUTORIZADA (nfeProc) publica evento de recalculo da apuracao")
  void notaImportadaAutorizadaPublicaEvento() {
    ArquivoXml arquivo = new ArquivoXml("autorizada.xml", "<ok/>".getBytes(StandardCharsets.UTF_8));
    NFe nfe = new NFe();
    when(nfeXmlImporter.importar(arquivo.conteudo()))
        .thenReturn(new ResultadoImportacao(nfe, "<NFe/>", true));

    OffsetDateTime dataEmissao = OffsetDateTime.parse("2026-07-10T10:00:00-03:00");
    NotaFiscal nota = notaComChave("33333333333333333333333333333333333333333333");
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);
    nota.setDataEmissao(dataEmissao);
    when(nfeToNotaFiscalMapper.toNotaFiscal(any(), any(), anyString(), anyBoolean()))
        .thenReturn(nota);
    when(notaFiscalRepository.existsByChaveAcesso(nota.getChaveAcesso())).thenReturn(false);
    when(notaFiscalRepository.save(nota)).thenReturn(nota);
    stubTransactionTemplateExecutaCallback();

    service.importar(List.of(arquivo));

    verify(eventPublisher).publishEvent(new NotaImportadaEvent(
        produtor.getId(), YearMonth.from(dataEmissao.atZoneSameInstant(FUSO_BRASIL))));
  }

  @Test
  @DisplayName("Chave duplicada: reportada como DUPLICADA, sem persistir e sem 500")
  void chaveDuplicada() {
    ArquivoXml arquivo = new ArquivoXml("dup.xml", "<dup/>".getBytes(StandardCharsets.UTF_8));
    NFe nfe = new NFe();
    when(nfeXmlImporter.importar(arquivo.conteudo()))
        .thenReturn(new ResultadoImportacao(nfe, "<NFe/>", true));

    NotaFiscal nota = notaComChave("22222222222222222222222222222222222222222222");
    when(nfeToNotaFiscalMapper.toNotaFiscal(any(), any(), anyString(), anyBoolean()))
        .thenReturn(nota);
    when(notaFiscalRepository.existsByChaveAcesso(nota.getChaveAcesso())).thenReturn(true);

    List<ImportacaoNotaResultado> resultados = service.importar(List.of(arquivo));

    assertThat(resultados).hasSize(1);
    assertThat(resultados.get(0).status()).isEqualTo(Status.DUPLICADA);
    assertThat(resultados.get(0).sucesso()).isFalse();
    verify(notaFiscalRepository, never()).save(any());
    // Nota duplicada nao e persistida — tampouco recalcula credito nem publica evento.
    verify(creditoTributarioService, never()).calcular(any());
    verifyNoInteractions(eventPublisher);
  }

  private NotaFiscal notaComChave(String chave) {
    return NotaFiscal.builder()
        .id(UUID.randomUUID())
        .origem(OrigemNotaFiscal.IMPORTADA)
        .chaveAcesso(chave)
        .emitenteCpfCnpj("11222333000181")
        .build();
  }
}
