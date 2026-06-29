package br.com.agrocontabil.service;

import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.dto.SefazSubmissionResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.exception.InvoiceNotQueryableException;
import br.com.agrocontabil.exception.InvoiceNotSubmittableException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.SefazCommunicationException;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import br.com.agrocontabil.repository.InvoiceRepository;
import br.com.agrocontabil.sefaz.SefazEnvironment;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazReturn;
import br.com.agrocontabil.sefaz.SefazSituation;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço de transmissão de notas fiscais para a SEFAZ (issue #15 — RF012).
 *
 * <p>Orquestra o envio do XML assinado ao webservice da SEFAZ (via {@link SefazClient},
 * que aplica retry em falha de comunicação) e persiste o result na invoice: status
 * resultante, código/reason retornados, protocol e data de autorização.
 *
 * <p>Regras:
 * <ul>
 *   <li>Ownership anti-IDOR: busca via {@code findByIdAndProducerId} — 404 se a invoice não
 *       pertencer ao producer autenticado.</li>
 *   <li>Pré-condição: somente notas em {@link InvoiceStatus#XML_GERADO} podem ser
 *       enviadas; caso contrário, {@link InvoiceNotSubmittableException} (HTTP 409).</li>
 *   <li>Mapeamento do retorno: AUTORIZADA → {@code AUTORIZADA} (com protocol e data);
 *       REJEITADA/DENEGADA → {@code REJEITADA}; PENDENTE → {@code PENDENTE_SEFAZ}.</li>
 *   <li>Rejeição do fisco não é error: é result válido persistido com cStat e reason.</li>
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

  private final InvoiceRepository invoiceRepository;
  private final AuthenticatedProducerProvider producerProvider;
  private final SefazClient sefazClient;
  private final SefazProperties sefazProperties;

  /** Constrói o serviço com as dependências de persistência, transporte e configuração. */
  public SefazService(
      InvoiceRepository invoiceRepository,
      AuthenticatedProducerProvider producerProvider,
      SefazClient sefazClient,
      SefazProperties sefazProperties) {
    this.invoiceRepository = invoiceRepository;
    this.producerProvider = producerProvider;
    this.sefazClient = sefazClient;
    this.sefazProperties = sefazProperties;
  }

  /**
   * Envia a NF-e indicada à SEFAZ e persiste o retorno na invoice.
   *
   * @param invoiceId identificador da invoice fiscal
   * @return DTO com o status resultante e os dados do retorno do fisco
   * @throws ResourceNotFoundException       se a invoice não existir ou pertencer a outro producer
   * @throws InvoiceNotSubmittableException  se o status não for XML_GERADO (HTTP 409)
   * @throws br.com.agrocontabil.exception.SefazCommunicationException
   *         se a comunicação com a SEFAZ falhar após esgotar as tentativas (HTTP 502)
   */
  public SefazSubmissionResponse send(UUID invoiceId) {
    Producer producer = producerProvider.get();
    Invoice invoice = invoiceRepository
        .findByIdAndProducerId(invoiceId, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", invoiceId));

    if (invoice.getStatus() != InvoiceStatus.XML_GERADO) {
      throw new InvoiceNotSubmittableException(invoiceId, invoice.getStatus());
    }

    SefazEnvironment ambiente = sefazProperties.getAmbiente();
    SefazReturn retorno =
        sefazClient.send(invoice.getGeneratedXml(), invoice.getAccessKey(), ambiente);

    applyReturn(invoice, retorno, ambiente);
    Invoice saved = invoiceRepository.save(invoice);

    // Log de auditoria do envio — nunca inclui dados sensíveis (CPF/CNPJ) nem o XML.
    log.info(
        "NF-e enviada a SEFAZ: notaId={} chave={} ambiente={} cStat={} situacao={} protocolo={}",
        saved.getId(),
        saved.getAccessKey(),
        ambiente,
        retorno.cStat(),
        retorno.situation(),
        retorno.protocol());

    return toResponse(saved);
  }

  /**
   * Consulta o status atual da NF-e na SEFAZ e sincroniza o status local (issue #16 — RF013).
   *
   * @param invoiceId identificador da invoice fiscal
   * @return DTO com o status atualizado e os dados do retorno do fisco
   * @throws ResourceNotFoundException          se a invoice não existir ou pertencer a outro producer
   * @throws InvoiceNotQueryableException  se a invoice ainda não foi transmitida (HTTP 409)
   * @throws SefazCommunicationException          se a comunicação falhar após o retry (HTTP 502)
   */
  public SefazStatusResponse syncStatus(UUID invoiceId) {
    Producer producer = producerProvider.get();
    Invoice invoice = invoiceRepository
        .findByIdAndProducerId(invoiceId, producer.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", invoiceId));

    if (invoice.getAccessKey() == null || invoice.getStatus() == InvoiceStatus.RASCUNHO) {
      throw new InvoiceNotQueryableException(invoiceId, invoice.getStatus());
    }

    consultarEAtualizar(invoice);
    Invoice saved = invoiceRepository.save(invoice);

    log.info(
        "Status sincronizado com a SEFAZ: notaId={} chave={} cStat={} situacao={}",
        saved.getId(),
        saved.getAccessKey(),
        saved.getSefazCstat(),
        saved.getStatus());

    return toStatusResponse(saved);
  }

  /**
   * Sincroniza o status de todas as notas em {@link InvoiceStatus#PENDENTE_SEFAZ}.
   *
   * <p>Operação de sistema (sem producer autenticado), executada pelo job agendado
   * {@link br.com.agrocontabil.sefaz.SefazStatusSyncJob}. Uma falha de comunicação numa
   * invoice é registrada e não interrompe o processamento das demais.
   *
   * @return quantity de notas cujo status mudou após a consulta
   */
  public int sincronizarPendentes() {
    List<Invoice> pendentes =
        invoiceRepository.findByStatus(InvoiceStatus.PENDENTE_SEFAZ);
    int atualizadas = 0;
    for (Invoice invoice : pendentes) {
      try {
        InvoiceStatus anterior = invoice.getStatus();
        consultarEAtualizar(invoice);
        invoiceRepository.save(invoice);
        if (invoice.getStatus() != anterior) {
          atualizadas++;
        }
      } catch (SefazCommunicationException e) {
        // Falha numa invoice não interrompe o lote — sera reprocessada na proxima execucao.
        log.warn("Falha ao sincronizar status da nota {} com a SEFAZ: {}",
            invoice.getId(), e.getMessage());
      }
    }
    return atualizadas;
  }

  /** Consulta a SEFAZ pela key de acesso e aplica o retorno à invoice. */
  private void consultarEAtualizar(Invoice invoice) {
    SefazEnvironment ambiente = invoice.getSefazEnvironment() != null
        ? invoice.getSefazEnvironment()
        : sefazProperties.getAmbiente();
    SefazReturn retorno = sefazClient.queryStatus(invoice.getAccessKey(), ambiente);
    applyReturn(invoice, retorno, ambiente);
  }

  /**
   * Aplica o retorno do fisco à entidade: status, código/reason, ambiente e, se autorizada,
   * protocol e data de autorização.
   */
  private void applyReturn(Invoice invoice, SefazReturn retorno, SefazEnvironment ambiente) {
    invoice.setStatus(mapStatus(retorno.situation()));
    invoice.setSefazCstat(retorno.cStat());
    invoice.setSefazReason(retorno.xMotivo());
    invoice.setSefazEnvironment(ambiente);
    if (retorno.situation() == SefazSituation.AUTORIZADA) {
      invoice.setAuthorizationProtocol(retorno.protocol());
      invoice.setAuthorizationDate(retorno.authorizationDate());
    }
  }

  /** Mapeia a situação retornada pela SEFAZ para o status de domínio da invoice. */
  private InvoiceStatus mapStatus(SefazSituation situation) {
    return switch (situation) {
      case AUTORIZADA -> InvoiceStatus.AUTORIZADA;
      case REJEITADA, DENEGADA -> InvoiceStatus.REJEITADA;
      case PENDENTE -> InvoiceStatus.PENDENTE_SEFAZ;
    };
  }

  /** Converte a entidade já atualizada no DTO de resposta do envio. */
  private SefazSubmissionResponse toResponse(Invoice invoice) {
    return new SefazSubmissionResponse(
        invoice.getAccessKey(),
        invoice.getStatus(),
        invoice.getSefazCstat(),
        invoice.getSefazReason(),
        invoice.getAuthorizationProtocol(),
        invoice.getAuthorizationDate());
  }

  /** Converte a entidade já atualizada no DTO de resposta da consulta de status. */
  private SefazStatusResponse toStatusResponse(Invoice invoice) {
    return new SefazStatusResponse(
        invoice.getAccessKey(),
        invoice.getStatus(),
        invoice.getSefazCstat(),
        invoice.getSefazReason(),
        invoice.getAuthorizationProtocol(),
        invoice.getAuthorizationDate());
  }
}
