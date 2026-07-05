package br.com.agrocontabil.service;

import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.dto.CancelamentoResponse;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.event.NotaCanceladaEvent;
import br.com.agrocontabil.event.NotaEmitidaEvent;
import br.com.agrocontabil.exception.CancelamentoForaPrazoException;
import br.com.agrocontabil.exception.NotaFiscalNaoCancelavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoConsultavelException;
import br.com.agrocontabil.exception.NotaFiscalNaoEnviavelException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.SefazComunicacaoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço de transmissão de notas fiscais para a SEFAZ (issue #15 — RF012).
 *
 * <p>Orquestra o envio do XML assinado ao webservice da SEFAZ (via {@link SefazClient},
 * que aplica retry em falha de comunicação) e persiste o resultado na nota: status
 * resultante, código/motivo retornados, protocolo e data de autorização.
 *
 * <p>Regras:
 * <ul>
 *   <li>Ownership anti-IDOR: busca via {@code findByIdAndProdutorId} — 404 se a nota não
 *       pertencer ao produtor autenticado.</li>
 *   <li>Pré-condição: somente notas em {@link StatusNotaFiscal#XML_GERADO} podem ser
 *       enviadas; caso contrário, {@link NotaFiscalNaoEnviavelException} (HTTP 409).</li>
 *   <li>Mapeamento do retorno: AUTORIZADA → {@code AUTORIZADA} (com protocolo e data);
 *       REJEITADA/DENEGADA → {@code REJEITADA}; PENDENTE → {@code PENDENTE_SEFAZ}.</li>
 *   <li>Rejeição do fisco não é erro: é resultado válido persistido com cStat e motivo.</li>
 * </ul>
 *
 * <p><strong>Sprint 3:</strong> o transporte ativo é o mock determinístico
 * ({@link br.com.agrocontabil.sefaz.MockSefazTransport}). O envio SOAP+mTLS real à SEFAZ
 * com certificado ICP-Brasil é débito da Sprint 7 — basta implementar
 * {@link br.com.agrocontabil.sefaz.SefazTransport} sem alterar este serviço.
 */
@Service
@Transactional
public class SefazService {

  private static final Logger log = LoggerFactory.getLogger(SefazService.class);

  /** Fuso oficial da aplicação para o cálculo do prazo de cancelamento (RN006). */
  private static final ZoneId FUSO_BRASIL = ZoneId.of("America/Sao_Paulo");

  /** Prazo legal, em dias corridos, para cancelar uma NF-e após a emissão (RN006). */
  private static final int PRAZO_CANCELAMENTO_DIAS = 30;

  /**
   * Códigos cStat que indicam cancelamento aceito pela SEFAZ (evento vinculado à NF-e):
   * 135 (evento registrado e vinculado) e 155 (evento registrado, vinculado fora do prazo).
   */
  private static final Set<String> CSTAT_CANCELAMENTO_ACEITO = Set.of("135", "155");

  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final SefazClient sefazClient;
  private final SefazProperties sefazProperties;
  private final ApplicationEventPublisher eventPublisher;

  /** Constrói o serviço com as dependências de persistência, transporte, configuração e eventos. */
  public SefazService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      SefazClient sefazClient,
      SefazProperties sefazProperties,
      ApplicationEventPublisher eventPublisher) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.sefazClient = sefazClient;
    this.sefazProperties = sefazProperties;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Envia a NF-e indicada à SEFAZ e persiste o retorno na nota.
   *
   * @param notaId identificador da nota fiscal
   * @return DTO com o status resultante e os dados do retorno do fisco
   * @throws ResourceNotFoundException       se a nota não existir ou pertencer a outro produtor
   * @throws NotaFiscalNaoEnviavelException  se o status não for XML_GERADO (HTTP 409)
   * @throws br.com.agrocontabil.exception.SefazComunicacaoException
   *         se a comunicação com a SEFAZ falhar após esgotar as tentativas (HTTP 502)
   */
  public SefazEnvioResponse enviar(UUID notaId) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(notaId, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));

    if (nota.getStatus() != StatusNotaFiscal.XML_GERADO) {
      throw new NotaFiscalNaoEnviavelException(notaId, nota.getStatus());
    }

    SefazAmbiente ambiente = sefazProperties.getAmbiente();
    SefazRetorno retorno =
        sefazClient.enviar(nota.getXmlGerado(), nota.getChaveAcesso(), ambiente);

    aplicarRetorno(nota, retorno, ambiente);
    NotaFiscal salva = notaFiscalRepository.save(nota);

    // Log de auditoria do envio — nunca inclui dados sensíveis (CPF/CNPJ) nem o XML.
    log.info(
        "NF-e enviada a SEFAZ: notaId={} chave={} ambiente={} cStat={} situacao={} protocolo={}",
        salva.getId(),
        salva.getChaveAcesso(),
        ambiente,
        retorno.cStat(),
        retorno.situacao(),
        retorno.protocolo());

    // issue Agrocontabil#64: so a autorizacao afeta o debito da apuracao (RN004) — rejeicao e
    // pendencia nao publicam evento.
    if (retorno.situacao() == SituacaoSefaz.AUTORIZADA) {
      eventPublisher.publishEvent(new NotaEmitidaEvent(produtor.getId(), periodoEmissao(salva)));
    }

    return toResponse(salva);
  }

  /**
   * Consulta o status atual da NF-e na SEFAZ e sincroniza o status local (issue #16 — RF013).
   *
   * @param notaId identificador da nota fiscal
   * @return DTO com o status atualizado e os dados do retorno do fisco
   * @throws ResourceNotFoundException          se a nota não existir ou pertencer a outro produtor
   * @throws NotaFiscalNaoConsultavelException  se a nota ainda não foi transmitida (HTTP 409)
   * @throws SefazComunicacaoException          se a comunicação falhar após o retry (HTTP 502)
   */
  public SefazStatusResponse sincronizarStatus(UUID notaId) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(notaId, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));

    if (nota.getChaveAcesso() == null || nota.getStatus() == StatusNotaFiscal.RASCUNHO) {
      throw new NotaFiscalNaoConsultavelException(notaId, nota.getStatus());
    }

    consultarEAtualizar(nota);
    NotaFiscal salva = notaFiscalRepository.save(nota);

    log.info(
        "Status sincronizado com a SEFAZ: notaId={} chave={} cStat={} situacao={}",
        salva.getId(),
        salva.getChaveAcesso(),
        salva.getSefazCstat(),
        salva.getStatus());

    return toStatusResponse(salva);
  }

  /**
   * Cancela uma NF-e autorizada junto à SEFAZ e persiste o resultado (issue #21 — RN006).
   *
   * <p>Fluxo (espelha {@link #enviar(UUID)}):
   * <ol>
   *   <li>Ownership anti-IDOR: {@code findByIdAndProdutorId} → 404 se inexistente/de outro
   *       produtor.</li>
   *   <li>Pré-condição: status deve ser {@link StatusNotaFiscal#AUTORIZADA}; caso contrário
   *       {@link NotaFiscalNaoCancelavelException} (HTTP 409).</li>
   *   <li>RN006: {@code dataEmissao} + 30 dias (fuso {@code America/Sao_Paulo}, limite
   *       inclusivo) vs. agora; se expirado, {@link CancelamentoForaPrazoException}
   *       (HTTP 422).</li>
   *   <li>{@link SefazClient#cancelar} (com retry em falha de comunicação → HTTP 502).</li>
   *   <li>Sucesso (cStat de evento vinculado ∈ {135, 155}): status {@code CANCELADA},
   *       data/motivo/protocolo de cancelamento e cStat/motivo atualizados; persiste.</li>
   * </ol>
   *
   * @param notaId identificador da nota fiscal
   * @param motivo justificativa do cancelamento (15 a 255 caracteres — validada no controller)
   * @return DTO com o resultado do cancelamento
   * @throws ResourceNotFoundException           se a nota não existir ou pertencer a outro produtor
   * @throws NotaFiscalNaoCancelavelException     se o status não for AUTORIZADA (HTTP 409)
   * @throws CancelamentoForaPrazoException       se fora do prazo de 30 dias (HTTP 422)
   * @throws SefazComunicacaoException            se a comunicação falhar após o retry (HTTP 502)
   */
  public CancelamentoResponse cancelar(UUID notaId, String motivo) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(notaId, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));

    if (nota.getStatus() != StatusNotaFiscal.AUTORIZADA) {
      throw new NotaFiscalNaoCancelavelException(notaId, nota.getStatus());
    }

    // RN006: prazo de 30 dias corridos da emissao, limite inclusivo (fuso America/Sao_Paulo).
    OffsetDateTime agora = OffsetDateTime.now(FUSO_BRASIL);
    OffsetDateTime limite = nota.getDataEmissao().plusDays(PRAZO_CANCELAMENTO_DIAS);
    if (agora.isAfter(limite)) {
      throw new CancelamentoForaPrazoException(notaId, limite);
    }

    SefazAmbiente ambiente = nota.getSefazAmbiente() != null
        ? nota.getSefazAmbiente()
        : sefazProperties.getAmbiente();
    SefazRetorno retorno = sefazClient.cancelar(
        nota.getChaveAcesso(), motivo, nota.getProtocoloAutorizacao(), ambiente);

    if (!CSTAT_CANCELAMENTO_ACEITO.contains(retorno.cStat())) {
      // Cancelamento rejeitado pelo fisco (cStat de evento nao vinculado). Na Sprint 3 o mock
      // sempre aceita; o tratamento fino da rejeicao de cancelamento e debito da Sprint 7.
      throw new NotaFiscalNaoCancelavelException(notaId, nota.getStatus());
    }

    nota.setStatus(StatusNotaFiscal.CANCELADA);
    nota.setDataCancelamento(agora);
    nota.setMotivoCancelamento(motivo);
    nota.setProtocoloCancelamento(retorno.protocolo());
    nota.setSefazCstat(retorno.cStat());
    nota.setSefazMotivo(retorno.xMotivo());
    nota.setSefazAmbiente(ambiente);
    NotaFiscal salva = notaFiscalRepository.save(nota);

    // Log de auditoria — nunca inclui a justificativa (pode conter dado sensivel) nem CPF/CNPJ.
    log.info(
        "NF-e cancelada na SEFAZ: notaId={} chave={} ambiente={} cStat={} protocolo={}",
        salva.getId(),
        salva.getChaveAcesso(),
        ambiente,
        retorno.cStat(),
        retorno.protocolo());

    // issue Agrocontabil#64: cancelamento retira a nota do debito — recalcula o periodo original
    // de emissao (nao o da data do cancelamento).
    eventPublisher.publishEvent(new NotaCanceladaEvent(produtor.getId(), periodoEmissao(salva)));

    return toCancelamentoResponse(salva);
  }

  /**
   * Sincroniza o status de todas as notas em {@link StatusNotaFiscal#PENDENTE_SEFAZ}.
   *
   * <p>Operação de sistema (sem produtor autenticado), executada pelo job agendado
   * {@link br.com.agrocontabil.sefaz.SefazStatusSyncJob}. Uma falha de comunicação numa
   * nota é registrada e não interrompe o processamento das demais.
   *
   * @return quantidade de notas cujo status mudou após a consulta
   */
  public int sincronizarPendentes() {
    List<NotaFiscal> pendentes =
        notaFiscalRepository.findByStatus(StatusNotaFiscal.PENDENTE_SEFAZ);
    int atualizadas = 0;
    for (NotaFiscal nota : pendentes) {
      try {
        StatusNotaFiscal anterior = nota.getStatus();
        consultarEAtualizar(nota);
        notaFiscalRepository.save(nota);
        if (nota.getStatus() != anterior) {
          atualizadas++;
        }
      } catch (SefazComunicacaoException e) {
        // Falha numa nota não interrompe o lote — sera reprocessada na proxima execucao.
        log.warn("Falha ao sincronizar status da nota {} com a SEFAZ: {}",
            nota.getId(), e.getMessage());
      }
    }
    return atualizadas;
  }

  /** Consulta a SEFAZ pela chave de acesso e aplica o retorno à nota. */
  private void consultarEAtualizar(NotaFiscal nota) {
    SefazAmbiente ambiente = nota.getSefazAmbiente() != null
        ? nota.getSefazAmbiente()
        : sefazProperties.getAmbiente();
    SefazRetorno retorno = sefazClient.consultarStatus(nota.getChaveAcesso(), ambiente);
    aplicarRetorno(nota, retorno, ambiente);
  }

  /**
   * Aplica o retorno do fisco à entidade: status, código/motivo, ambiente e, se autorizada,
   * protocolo e data de autorização.
   */
  private void aplicarRetorno(NotaFiscal nota, SefazRetorno retorno, SefazAmbiente ambiente) {
    nota.setStatus(mapStatus(retorno.situacao()));
    nota.setSefazCstat(retorno.cStat());
    nota.setSefazMotivo(retorno.xMotivo());
    nota.setSefazAmbiente(ambiente);
    if (retorno.situacao() == SituacaoSefaz.AUTORIZADA) {
      nota.setProtocoloAutorizacao(retorno.protocolo());
      nota.setDataAutorizacao(retorno.dataAutorizacao());
    }
  }

  /** Mês ({@code America/Sao_Paulo}) da {@code dataEmissao} — período afetado na apuração. */
  private YearMonth periodoEmissao(NotaFiscal nota) {
    return YearMonth.from(nota.getDataEmissao().atZoneSameInstant(FUSO_BRASIL));
  }

  /** Mapeia a situação retornada pela SEFAZ para o status de domínio da nota. */
  private StatusNotaFiscal mapStatus(SituacaoSefaz situacao) {
    return switch (situacao) {
      case AUTORIZADA -> StatusNotaFiscal.AUTORIZADA;
      case REJEITADA, DENEGADA -> StatusNotaFiscal.REJEITADA;
      case PENDENTE -> StatusNotaFiscal.PENDENTE_SEFAZ;
    };
  }

  /** Converte a entidade já atualizada no DTO de resposta do envio. */
  private SefazEnvioResponse toResponse(NotaFiscal nota) {
    return new SefazEnvioResponse(
        nota.getChaveAcesso(),
        nota.getStatus(),
        nota.getSefazCstat(),
        nota.getSefazMotivo(),
        nota.getProtocoloAutorizacao(),
        nota.getDataAutorizacao());
  }

  /** Converte a entidade já cancelada no DTO de resposta do cancelamento. */
  private CancelamentoResponse toCancelamentoResponse(NotaFiscal nota) {
    return new CancelamentoResponse(
        nota.getChaveAcesso(),
        nota.getStatus(),
        nota.getDataCancelamento(),
        nota.getProtocoloCancelamento(),
        nota.getMotivoCancelamento(),
        nota.getSefazCstat(),
        nota.getSefazMotivo());
  }

  /** Converte a entidade já atualizada no DTO de resposta da consulta de status. */
  private SefazStatusResponse toStatusResponse(NotaFiscal nota) {
    return new SefazStatusResponse(
        nota.getChaveAcesso(),
        nota.getStatus(),
        nota.getSefazCstat(),
        nota.getSefazMotivo(),
        nota.getProtocoloAutorizacao(),
        nota.getDataAutorizacao());
  }
}
