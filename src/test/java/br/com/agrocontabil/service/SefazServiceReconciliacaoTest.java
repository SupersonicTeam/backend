package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.dto.CancelamentoResponse;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.event.NotaCanceladaEvent;
import br.com.agrocontabil.event.NotaEmitidaEvent;
import br.com.agrocontabil.exception.CancelamentoRejeitadoException;
import br.com.agrocontabil.exception.SefazComunicacaoException;
import br.com.agrocontabil.exception.SefazConexaoException;
import br.com.agrocontabil.exception.SefazRespostaIndeterminadaException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Entrega incerta à SEFAZ: reconciliação pela chave em vez de reenvio (issue #83).
 *
 * <p>Com o transporte real, a resposta pode se perder depois de o fisco já ter processado o XML ou
 * o evento. Reenviar às cegas faz a SEFAZ acusar duplicidade (cStat 204 / 573), e a nota ficaria
 * rejeitada ou autorizada no sistema e o contrário no fisco. Estes testes travam a regra: diante
 * de desfecho incerto o serviço <strong>pergunta pela chave</strong> e só então decide.
 *
 * <p>Mockito puro, mesmo arranjo do {@link SefazServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class SefazServiceReconciliacaoTest {

  private static final String CHAVE = "41262612345678000195550010000000011000000012";
  private static final String MOTIVO =
      "Cancelamento por erro de digitacao nos dados do destinatario";
  private static final String PROTOCOLO_AUTORIZACAO = "141260000000017";

  @Mock private NotaFiscalRepository notaFiscalRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;
  @Mock private SefazClient sefazClient;
  @Mock private ApplicationEventPublisher eventPublisher;

  private SefazService sefazService;
  private Produtor produtor;
  private UUID produtorId;
  private UUID notaId;

  @BeforeEach
  void setUp() {
    produtorId = UUID.randomUUID();
    notaId = UUID.randomUUID();
    produtor = Produtor.builder()
        .id(produtorId)
        .nomeCompleto("Murilo Produtor")
        .cpfCnpj("12345678901")
        .build();
    sefazService = new SefazService(
        notaFiscalRepository, produtorProvider, sefazClient, new SefazProperties(),
        eventPublisher, new TransactionTemplate(mock(PlatformTransactionManager.class)),
        mock(NotaFiscalValidacaoService.class));
  }

  // -------------------------------------------------------------------------
  // enviar
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("enviar: falha de conexao devolve a nota a XML_GERADO sem consultar a SEFAZ")
  void conexaoDevolveParaXmlGerado() {
    NotaFiscal nota = prepararEnvio();
    when(sefazClient.enviar(any(), any(), any()))
        .thenThrow(new SefazConexaoException("connection refused"));

    assertThatThrownBy(() -> sefazService.enviar(notaId))
        .isInstanceOf(SefazConexaoException.class);

    // O XML comprovadamente nao saiu: reenviar e seguro, entao a nota volta a ser enviavel.
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.XML_GERADO);
    verify(sefazClient, never()).consultarStatus(any(), any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("enviar: resposta perdida com NF-e autorizada no fisco conclui AUTORIZADA (200)")
  void indeterminadaReconciliaParaAutorizada() {
    NotaFiscal nota = prepararEnvio();
    when(sefazClient.enviar(any(), any(), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("read timeout"));
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(autorizada());

    SefazEnvioResponse response = sefazService.enviar(notaId);

    // O cenario central da #83: sem a consulta, a nota ficaria pendente (ou, com retry cego,
    // REJEITADA por duplicidade) enquanto o fisco a tem autorizada.
    assertThat(response.status()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getProtocoloAutorizacao()).isEqualTo(PROTOCOLO_AUTORIZACAO);
    verify(sefazClient, times(1)).enviar(any(), any(), any());
    verify(eventPublisher, times(1)).publishEvent(any(NotaEmitidaEvent.class));
  }

  @Test
  @DisplayName("enviar: resposta perdida e chave ainda nao encontrada deixa PENDENTE_SEFAZ + 502")
  void indeterminadaNaoEncontradaFicaPendente() {
    NotaFiscal nota = prepararEnvio();
    when(sefazClient.enviar(any(), any(), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("read timeout"));
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(naoEncontrada());

    assertThatThrownBy(() -> sefazService.enviar(notaId))
        .isInstanceOf(SefazRespostaIndeterminadaException.class);

    // Logo apos o timeout, "nao encontrada" nao prova que o XML nao chegou: a SEFAZ pode estar
    // processando. XML_GERADO liberaria um reenvio; a nota fica pendente para o job.
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    // E fica pendente sabendo a qual ambiente perguntar: o job consulta o mesmo que recebeu o XML,
    // nao o que estiver configurado quando ele rodar.
    assertThat(nota.getSefazAmbiente()).isEqualTo(SefazAmbiente.HOMOLOGACAO);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("enviar: consulta de reconciliacao falhando relanca a excecao original, pendente")
  void indeterminadaComConsultaFalhandoFicaPendente() {
    NotaFiscal nota = prepararEnvio();
    SefazRespostaIndeterminadaException original =
        new SefazRespostaIndeterminadaException("read timeout");
    when(sefazClient.enviar(any(), any(), any())).thenThrow(original);
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO))
        .thenThrow(new SefazComunicacaoException("consulta tambem caiu"));

    assertThatThrownBy(() -> sefazService.enviar(notaId)).isSameAs(original);

    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
  }

  @Test
  @DisplayName("enviar: excecao nao classificada e tratada como indeterminada (consulta a chave)")
  void comunicacaoNaoClassificadaEhIndeterminada() {
    NotaFiscal nota = prepararEnvio();
    when(sefazClient.enviar(any(), any(), any()))
        .thenThrow(new SefazComunicacaoException("falha sem classificacao"));
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(autorizada());

    assertThat(sefazService.enviar(notaId).status()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
  }

  @Test
  @DisplayName("enviar: cStat 204 (duplicidade) reconcilia pela chave e fica AUTORIZADA")
  void duplicidadeReconciliaParaAutorizada() {
    NotaFiscal nota = prepararEnvio();
    when(sefazClient.enviar(any(), any(), any()))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.REJEITADA, "204", "Rejeicao: Duplicidade de NF-e", null, null));
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(autorizada());

    SefazEnvioResponse response = sefazService.enviar(notaId);

    assertThat(response.status()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(response.cStat()).isEqualTo("100");
    assertThat(nota.getProtocoloAutorizacao()).isEqualTo(PROTOCOLO_AUTORIZACAO);
    verify(eventPublisher, times(1)).publishEvent(any(NotaEmitidaEvent.class));
  }

  @Test
  @DisplayName("enviar: cStat 204 sem confirmacao na consulta fica PENDENTE_SEFAZ com o 204")
  void duplicidadeSemConfirmacaoFicaPendente() {
    NotaFiscal nota = prepararEnvio();
    when(sefazClient.enviar(any(), any(), any()))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.REJEITADA, "204", "Rejeicao: Duplicidade de NF-e", null, null));
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(new SefazRetorno(SituacaoSefaz.PENDENTE, "105", "Lote em processamento",
            null, null));

    SefazEnvioResponse response = sefazService.enviar(notaId);

    // A duplicidade diz que o fisco TEM a chave: REJEITADA seria mentira. Fica pendente, e o
    // job resolve pela consulta.
    assertThat(response.status()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    assertThat(nota.getSefazCstat()).isEqualTo("204");
    verify(eventPublisher, never()).publishEvent(any());
  }

  // -------------------------------------------------------------------------
  // cancelar
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("cancelar: falha de conexao devolve a AUTORIZADA sem consultar a SEFAZ")
  void cancelarConexaoDevolveParaAutorizada() {
    NotaFiscal nota = prepararCancelamento();
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenThrow(new SefazConexaoException("connection refused"));

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO))
        .isInstanceOf(SefazConexaoException.class);

    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    verify(sefazClient, never()).consultarStatus(any(), any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("cancelar: resposta perdida com cancelamento registrado no fisco conclui CANCELADA")
  void cancelarIndeterminadaReconciliaParaCancelada() {
    NotaFiscal nota = prepararCancelamento();
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("read timeout"));
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO)).thenReturn(cancelada());

    CancelamentoResponse response = sefazService.cancelar(notaId, MOTIVO);

    assertThat(response.status()).isEqualTo(StatusNotaFiscal.CANCELADA);
    assertThat(nota.getProtocoloCancelamento()).isEqualTo("141260000000101");
    assertThat(nota.getMotivoCancelamento()).isEqualTo(MOTIVO);
    // Uma unica retirada do debito do periodo.
    verify(eventPublisher, times(1)).publishEvent(any(NotaCanceladaEvent.class));
  }

  @Test
  @DisplayName("cancelar: resposta perdida sem cancelamento no fisco volta a AUTORIZADA + 502")
  void cancelarIndeterminadaSemRegistroVoltaParaAutorizada() {
    NotaFiscal nota = prepararCancelamento();
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("read timeout"));
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO)).thenReturn(autorizada());

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO))
        .isInstanceOf(SefazRespostaIndeterminadaException.class);

    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("cancelar: cStat 573 confirmado pela consulta conclui CANCELADA")
  void cancelarDuplicidadeDeEventoConfirmadaConcluiCancelada() {
    NotaFiscal nota = prepararCancelamento();
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenReturn(new SefazRetorno(
            SituacaoSefaz.REJEITADA, "573", "Rejeicao: Duplicidade de evento", null, null));
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO)).thenReturn(cancelada());

    CancelamentoResponse response = sefazService.cancelar(notaId, MOTIVO);

    // Um cancelamento anterior foi registrado e a resposta se perdeu. Responder 422 aqui deixaria
    // a nota AUTORIZADA (e no debito) com o fisco dizendo que ela esta cancelada.
    assertThat(response.status()).isEqualTo(StatusNotaFiscal.CANCELADA);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.CANCELADA);
    verify(eventPublisher, times(1)).publishEvent(any(NotaCanceladaEvent.class));
  }

  @Test
  @DisplayName("cancelar: cStat 420 sem confirmacao na consulta vira 422 e a nota segue AUTORIZADA")
  void cancelarJaCanceladaSemConfirmacaoEhRecusa() {
    NotaFiscal nota = prepararCancelamento();
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenReturn(new SefazRetorno(SituacaoSefaz.REJEITADA, "420",
            "Rejeicao: Cancelamento para NF-e ja cancelada", null, null));
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO)).thenReturn(autorizada());

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO))
        .isInstanceOf(CancelamentoRejeitadoException.class);

    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("cancelar: recusa comum (cStat 501) nao consulta a SEFAZ")
  void cancelarRecusaComumNaoConsulta() {
    prepararCancelamento();
    when(sefazClient.cancelar(any(), any(), any(), any()))
        .thenReturn(new SefazRetorno(SituacaoSefaz.REJEITADA, "501",
            "Rejeicao: Pedido de Cancelamento intempestivo", null, null));

    assertThatThrownBy(() -> sefazService.cancelar(notaId, MOTIVO))
        .isInstanceOf(CancelamentoRejeitadoException.class);

    verify(sefazClient, never()).consultarStatus(any(), any());
  }

  // -------------------------------------------------------------------------
  // sincronizarStatus: situacoes novas da consulta
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("sincronizar: NAO_ENCONTRADA devolve a nota pendente a XML_GERADO, sem evento")
  void sincronizarNaoEncontradaVoltaParaXmlGerado() {
    NotaFiscal nota = prepararSincronizacao();
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(naoEncontrada());

    SefazStatusResponse response = sefazService.sincronizarStatus(notaId);

    // O fisco nao tem a NF-e: nunca chegou. XML_GERADO libera o reenvio, e antes a nota virava
    // REJEITADA por um cStat que nao e rejeicao dos dados.
    assertThat(response.status()).isEqualTo(StatusNotaFiscal.XML_GERADO);
    assertThat(nota.getSefazCstat()).isEqualTo("217");
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("sincronizar: NAO_ENCONTRADA depois de um 204 mantem a nota pendente")
  void sincronizarNaoEncontradaAposDuplicidadeFicaPendente() {
    NotaFiscal nota = prepararSincronizacao();
    nota.setSefazCstat("204");
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO))
        .thenReturn(naoEncontrada());

    SefazStatusResponse response = sefazService.sincronizarStatus(notaId);

    // O fisco ja disse que a chave existe. Voltar a XML_GERADO levaria a reenvio -> 204 ->
    // pendente -> nao encontrada -> reenvio, sem nunca convergir.
    assertThat(response.status()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    assertThat(nota.getSefazCstat()).isEqualTo("204");
  }

  @Test
  @DisplayName("sincronizar: CANCELADA marca a nota pendente como CANCELADA, sem evento")
  void sincronizarCanceladaMarcaCancelada() {
    NotaFiscal nota = prepararSincronizacao();
    when(sefazClient.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO)).thenReturn(cancelada());

    SefazStatusResponse response = sefazService.sincronizarStatus(notaId);

    assertThat(response.status()).isEqualTo(StatusNotaFiscal.CANCELADA);
    assertThat(nota.getProtocoloCancelamento()).isEqualTo("141260000000101");
    assertThat(nota.getDataCancelamento()).isNotNull();
    // Nota pendente nunca entrou no debito: nao ha o que retirar.
    verify(eventPublisher, never()).publishEvent(any());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private NotaFiscal prepararEnvio() {
    NotaFiscal nota = buildNota(StatusNotaFiscal.XML_GERADO);
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));
    return nota;
  }

  private NotaFiscal prepararCancelamento() {
    NotaFiscal nota = buildNota(StatusNotaFiscal.AUTORIZADA);
    nota.setDataEmissao(OffsetDateTime.now().minusDays(2));
    nota.setSefazAmbiente(SefazAmbiente.HOMOLOGACAO);
    nota.setProtocoloAutorizacao(PROTOCOLO_AUTORIZACAO);
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));
    return nota;
  }

  private NotaFiscal prepararSincronizacao() {
    NotaFiscal nota = buildNota(StatusNotaFiscal.PENDENTE_SEFAZ);
    nota.setSefazAmbiente(SefazAmbiente.HOMOLOGACAO);
    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(notaFiscalRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));
    return nota;
  }

  private static SefazRetorno autorizada() {
    return new SefazRetorno(SituacaoSefaz.AUTORIZADA, "100", "Autorizado o uso da NF-e",
        PROTOCOLO_AUTORIZACAO, OffsetDateTime.now());
  }

  private static SefazRetorno naoEncontrada() {
    return new SefazRetorno(SituacaoSefaz.NAO_ENCONTRADA, "217",
        "NF-e nao consta na base de dados da SEFAZ", null, null);
  }

  private static SefazRetorno cancelada() {
    return new SefazRetorno(SituacaoSefaz.CANCELADA, "101", "Cancelamento de NF-e homologado",
        "141260000000101", OffsetDateTime.now());
  }

  private NotaFiscal buildNota(StatusNotaFiscal status) {
    return NotaFiscal.builder()
        .id(notaId)
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Jose Comprador")
        .destinatarioCpfCnpj("12345678901")
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(status)
        .chaveAcesso(CHAVE)
        .xmlGerado("<NFe><infNFe Id=\"NFe" + CHAVE + "\"/></NFe>")
        .versaoLayoutNfe("4.00")
        .versaoRegraFiscal(br.com.agrocontabil.nfe.VersaoRegraFiscal.ATUAL)
        .build();
  }
}
