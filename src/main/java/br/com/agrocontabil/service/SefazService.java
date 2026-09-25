package br.com.agrocontabil.service;

import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.dto.CancelamentoResponse;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.event.NotaCanceladaEvent;
import br.com.agrocontabil.event.NotaEmitidaEvent;
import br.com.agrocontabil.exception.CancelamentoForaPrazoException;
import br.com.agrocontabil.exception.CancelamentoRejeitadoException;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p><strong>Escopo transacional (issue #96):</strong> a classe <strong>não</strong> é
 * {@code @Transactional}. {@link #enviar(UUID)} e {@link #cancelar(UUID, String)} orquestram
 * <em>três</em> transações curtas — reservar sob lock, chamar a SEFAZ fora de transação, aplicar
 * o desfecho — para que a chamada de rede não segure conexão do pool nem lock de linha. O estado
 * transitório ({@link StatusNotaFiscal#ENVIANDO} / {@link StatusNotaFiscal#CANCELANDO}) é o que
 * fecha a janela de transmissão dupla sem manter a transação aberta durante a rede.
 *
 * <p><strong>Sprint 3:</strong> o transporte ativo é o mock determinístico
 * ({@link br.com.agrocontabil.sefaz.MockSefazTransport}). O envio SOAP+mTLS real à SEFAZ
 * com certificado ICP-Brasil é débito da Sprint 7 — basta implementar
 * {@link br.com.agrocontabil.sefaz.SefazTransport} sem alterar este serviço.
 */
@Service
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
  private final TransactionTemplate transactionTemplate;

  /** Constrói o serviço com as dependências de persistência, transporte, configuração e eventos. */
  public SefazService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      SefazClient sefazClient,
      SefazProperties sefazProperties,
      ApplicationEventPublisher eventPublisher,
      TransactionTemplate transactionTemplate) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.sefazClient = sefazClient;
    this.sefazProperties = sefazProperties;
    this.eventPublisher = eventPublisher;
    this.transactionTemplate = transactionTemplate;
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
    SefazAmbiente ambiente = sefazProperties.getAmbiente();

    // Transacao 1 (curta): sob lock, valida a pre-condicao e reserva a nota marcando ENVIANDO.
    // O commit libera lock e conexao ANTES da rede. Uma segunda requisicao concorrente espera o
    // lock, encontra ENVIANDO e recebe 409 — o XML chega a SEFAZ uma unica vez.
    DadosTransmissao dados = emTransacao(() -> {
      NotaFiscal nota = carregarComLock(notaId, produtor);
      if (nota.getStatus() != StatusNotaFiscal.XML_GERADO) {
        throw new NotaFiscalNaoEnviavelException(notaId, nota.getStatus());
      }
      nota.setStatus(StatusNotaFiscal.ENVIANDO);
      notaFiscalRepository.save(nota);
      return new DadosTransmissao(nota.getXmlGerado(), nota.getChaveAcesso());
    });

    // Fora de transacao: a chamada de rede (com retry) nao segura conexao do pool nem o lock.
    SefazRetorno retorno;
    try {
      retorno = sefazClient.enviar(dados.xml(), dados.chaveAcesso(), ambiente);
    } catch (RuntimeException e) {
      // Nao sabemos se o XML chegou ao fisco. PENDENTE_SEFAZ e exatamente o estado "transmiti,
      // desfecho desconhecido": o SefazStatusSyncJob consulta pela chave e estabelece a verdade.
      // Voltar para XML_GERADO seria pior — liberaria um reenvio que duplicaria a NF-e.
      compensar(notaId, produtor, StatusNotaFiscal.PENDENTE_SEFAZ, "envio");
      throw e;
    }

    // Transacao 2 (curta): aplica o desfecho e publica o evento de dominio.
    return emTransacao(() -> {
      NotaFiscal nota = carregarComLock(notaId, produtor);
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
      // pendencia nao publicam evento. A publicacao fica DENTRO da transacao porque o listener e
      // @TransactionalEventListener(AFTER_COMMIT): publicado sem transacao ativa, o evento seria
      // descartado em silencio e a apuracao nunca recalcularia.
      if (retorno.situacao() == SituacaoSefaz.AUTORIZADA) {
        eventPublisher.publishEvent(new NotaEmitidaEvent(produtor.getId(), periodoEmissao(salva)));
      }

      return toResponse(salva);
    });
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

    // Transacao 1 (curta): valida a pre-condicao e materializa o que a consulta precisa.
    DadosConsulta dados = emTransacao(() -> {
      NotaFiscal nota = notaFiscalRepository
          .findByIdAndProdutorId(notaId, produtor.getId())
          .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));

      // Só é seguro sincronizar a partir de PENDENTE_SEFAZ — o único estado "aguardando desfecho".
      // Estados terminais (AUTORIZADA/REJEITADA/CANCELADA) e pré-transmissao
      // (RASCUNHO/XML_GERADO/COM_INCONSISTENCIAS) nunca podem ser sobrescritos por uma consulta:
      // como o transporte de consulta responde a situacao atual da chave, sincronizar uma nota
      // CANCELADA a ressuscitaria para AUTORIZADA (a nota voltaria a compor o debito da apuracao),
      // e sincronizar uma XML_GERADO nunca transmitida a autorizaria sem envio.
      // A chave nula tambem barra (defesa contra dado corrompido): consultar a SEFAZ exige a chave.
      if (nota.getStatus() != StatusNotaFiscal.PENDENTE_SEFAZ || nota.getChaveAcesso() == null) {
        throw new NotaFiscalNaoConsultavelException(notaId, nota.getStatus());
      }
      return new DadosConsulta(nota.getChaveAcesso(), ambienteDe(nota));
    });

    // Fora de transacao: a consulta a SEFAZ nao segura conexao do pool (issue #96).
    SefazRetorno retorno = sefazClient.consultarStatus(dados.chaveAcesso(), dados.ambiente());

    // Transacao 2 (curta): aplica o retorno e publica o evento, se a nota foi autorizada.
    return emTransacao(() -> {
      NotaFiscal nota = carregarComLock(notaId, produtor);
      StatusNotaFiscal statusAnterior = nota.getStatus();
      aplicarRetorno(nota, retorno, dados.ambiente());
      NotaFiscal salva = notaFiscalRepository.save(nota);
      publicarSeAutorizou(statusAnterior, salva);

      log.info(
          "Status sincronizado com a SEFAZ: notaId={} chave={} cStat={} situacao={}",
          salva.getId(),
          salva.getChaveAcesso(),
          salva.getSefazCstat(),
          salva.getStatus());

      return toStatusResponse(salva);
    });
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
   * @throws CancelamentoRejeitadoException       se a SEFAZ recusar o evento (HTTP 422, issue #84)
   * @throws SefazComunicacaoException            se a comunicação falhar após o retry (HTTP 502)
   */
  public CancelamentoResponse cancelar(UUID notaId, String motivo) {
    Produtor produtor = produtorProvider.get();

    // Transacao 1 (curta): sob lock, valida status e prazo (RN006) e reserva marcando CANCELANDO.
    DadosCancelamento dados = emTransacao(() -> {
      NotaFiscal nota = carregarComLock(notaId, produtor);
      if (nota.getStatus() != StatusNotaFiscal.AUTORIZADA) {
        throw new NotaFiscalNaoCancelavelException(notaId, nota.getStatus());
      }

      // RN006: prazo de 30 dias corridos da emissao, limite inclusivo (fuso America/Sao_Paulo).
      OffsetDateTime agora = OffsetDateTime.now(FUSO_BRASIL);
      OffsetDateTime limite = nota.getDataEmissao().plusDays(PRAZO_CANCELAMENTO_DIAS);
      if (agora.isAfter(limite)) {
        throw new CancelamentoForaPrazoException(notaId, limite);
      }

      SefazAmbiente ambienteNota = ambienteDe(nota);

      nota.setStatus(StatusNotaFiscal.CANCELANDO);
      notaFiscalRepository.save(nota);
      return new DadosCancelamento(
          nota.getChaveAcesso(), nota.getProtocoloAutorizacao(), ambienteNota, agora);
    });

    // Fora de transacao: o evento de cancelamento vai a SEFAZ sem segurar conexao nem lock.
    SefazRetorno retorno;
    try {
      retorno = sefazClient.cancelar(
          dados.chaveAcesso(), motivo, dados.protocoloAutorizacao(), dados.ambiente());
    } catch (RuntimeException e) {
      // Volta para AUTORIZADA, e NAO para PENDENTE_SEFAZ: a consulta de status nao reconhece
      // cancelamento (mapStatus nao mapeia CANCELADA), entao o job reautorizaria a nota — a
      // regressao fechada pela issue #79. Repetir o cancelamento e seguro: o fisco responde
      // cStat de evento ja vinculado (135/155).
      compensar(notaId, produtor, StatusNotaFiscal.AUTORIZADA, "cancelamento");
      throw e;
    }

    if (!CSTAT_CANCELAMENTO_ACEITO.contains(retorno.cStat())) {
      // Cancelamento recusado pelo fisco (cStat de evento nao vinculado). A nota volta a
      // AUTORIZADA: a recusa nao a cancelou, e ela segue cancelavel. A excecao e especifica
      // (nao NotaFiscalNaoCancelavelException, que diria "status AUTORIZADA nao permite
      // cancelar" — contraditorio) e repassa o cStat/xMotivo do fisco ao produtor.
      compensar(notaId, produtor, StatusNotaFiscal.AUTORIZADA, "cancelamento");
      throw new CancelamentoRejeitadoException(notaId, retorno.cStat(), retorno.xMotivo());
    }

    // Transacao 2 (curta): aplica o desfecho e publica o evento de dominio.
    return emTransacao(() -> {
      NotaFiscal nota = carregarComLock(notaId, produtor);
      nota.setStatus(StatusNotaFiscal.CANCELADA);
      nota.setDataCancelamento(dados.solicitadoEm());
      nota.setMotivoCancelamento(motivo);
      nota.setProtocoloCancelamento(retorno.protocolo());
      nota.setSefazCstat(retorno.cStat());
      nota.setSefazMotivo(retorno.xMotivo());
      nota.setSefazAmbiente(dados.ambiente());
      NotaFiscal salva = notaFiscalRepository.save(nota);

      // Log de auditoria — nunca inclui a justificativa (pode conter dado sensivel) nem CPF/CNPJ.
      log.info(
          "NF-e cancelada na SEFAZ: notaId={} chave={} ambiente={} cStat={} protocolo={}",
          salva.getId(),
          salva.getChaveAcesso(),
          dados.ambiente(),
          retorno.cStat(),
          retorno.protocolo());

      // issue Agrocontabil#64: cancelamento retira a nota do debito — recalcula o periodo original
      // de emissao (nao o da data do cancelamento). Dentro da transacao, pelo mesmo motivo do
      // envio: o listener AFTER_COMMIT ignora evento publicado sem transacao ativa.
      eventPublisher.publishEvent(new NotaCanceladaEvent(produtor.getId(), periodoEmissao(salva)));

      return toCancelamentoResponse(salva);
    });
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
    // A leitura da lista tem a sua propria transacao curta; o lote NAO roda numa transacao unica.
    // Antes, com @Transactional no metodo, N consultas de rede (cada uma com retry) corriam com a
    // transacao aberta: uma conexao do pool ficava presa por dezenas de segundos e o
    // leak-detection (#109) acusava a cada execucao do job.
    List<NotaFiscal> pendentes = emTransacao(
        () -> notaFiscalRepository.findByStatus(StatusNotaFiscal.PENDENTE_SEFAZ));

    int atualizadas = 0;
    for (NotaFiscal pendente : pendentes) {
      UUID notaId = pendente.getId();
      try {
        SefazAmbiente ambiente = ambienteDe(pendente);
        // Consulta fora de transacao.
        SefazRetorno retorno = sefazClient.consultarStatus(pendente.getChaveAcesso(), ambiente);

        // Transacao curta por nota: recarrega, aplica e publica. Isola tambem a colisao de
        // versao de uma nota, que nao pode desfazer as demais do lote.
        boolean mudou = Boolean.TRUE.equals(emTransacao(() -> {
          NotaFiscal nota = notaFiscalRepository.findById(notaId).orElse(null);
          if (nota == null || nota.getStatus() != StatusNotaFiscal.PENDENTE_SEFAZ) {
            // Saiu de PENDENTE_SEFAZ entre a leitura e agora: nao sobrescreve.
            return false;
          }
          StatusNotaFiscal anterior = nota.getStatus();
          aplicarRetorno(nota, retorno, ambiente);
          NotaFiscal salva = notaFiscalRepository.save(nota);
          if (salva.getStatus() != anterior) {
            publicarSeAutorizou(anterior, salva);
            return true;
          }
          return false;
        }));
        if (mudou) {
          atualizadas++;
        }
      } catch (SefazComunicacaoException e) {
        // Falha numa nota não interrompe o lote — sera reprocessada na proxima execucao.
        log.warn("Falha ao sincronizar status da nota {} com a SEFAZ: {}",
            notaId, e.getMessage());
      } catch (RuntimeException e) {
        log.warn("Falha ao aplicar o status consultado da nota {}; sera retentada", notaId, e);
      }
    }
    return atualizadas;
  }

  /**
   * Publica {@link NotaEmitidaEvent} quando a sincronização com a SEFAZ confirma a autorização de
   * uma nota que ainda não constava como AUTORIZADA — espelhando a publicação feita por
   * {@link #enviar(UUID)}. Sem isso, uma nota que ficou {@link StatusNotaFiscal#PENDENTE_SEFAZ} no
   * envio e só foi autorizada na sincronização (endpoint manual ou job) nunca dispararia o
   * recálculo da apuração (issue Agrocontabil#64): se o período já estivesse consolidado, o débito
   * de IBS/CBS dessa nota ficaria de fora — guia fiscal apurada a menor.
   */
  private void publicarSeAutorizou(StatusNotaFiscal statusAnterior, NotaFiscal nota) {
    if (statusAnterior != StatusNotaFiscal.AUTORIZADA
        && nota.getStatus() == StatusNotaFiscal.AUTORIZADA) {
      eventPublisher.publishEvent(
          new NotaEmitidaEvent(nota.getProdutor().getId(), periodoEmissao(nota)));
    }
  }

  /**
   * Devolve notas presas num estado transitório de transmissão ao estado de repouso correto
   * (issue #96).
   *
   * <p>Se o processo cair entre a transação que marca {@link StatusNotaFiscal#ENVIANDO} /
   * {@link StatusNotaFiscal#CANCELANDO} e a que aplica o desfecho, a nota fica parada nesse
   * estado — e, como ele bloqueia toda operação, ela ficaria inutilizável para sempre. Este
   * método é a rede de segurança, executada pelo job antes da sincronização normal.
   *
   * <p>Para onde cada uma vai e por quê está documentado em {@link StatusNotaFiscal#ENVIANDO} e
   * {@link StatusNotaFiscal#CANCELANDO}: envio vira {@code PENDENTE_SEFAZ} (a consulta pela chave
   * estabelece a verdade); cancelamento volta a {@code AUTORIZADA} (a consulta não reconhece
   * cancelamento e reautorizaria a nota).
   *
   * <p>Operação de sistema, sem produtor autenticado. Só alcança notas paradas há mais que
   * {@code app.nfe.sefaz.transmissao.timeout-ms}, para não interromper transmissão em andamento.
   *
   * @return quantidade de notas reconciliadas
   */
  public int reconciliarTransmissoesPresas() {
    OffsetDateTime limite = OffsetDateTime.now()
        .minus(sefazProperties.getTransmissao().getTimeoutMs(), ChronoUnit.MILLIS);
    List<NotaFiscal> presas = notaFiscalRepository.findByStatusInAndUpdatedAtBefore(
        List.of(StatusNotaFiscal.ENVIANDO, StatusNotaFiscal.CANCELANDO), limite);

    int reconciliadas = 0;
    for (NotaFiscal nota : presas) {
      StatusNotaFiscal anterior = nota.getStatus();
      StatusNotaFiscal destino = anterior == StatusNotaFiscal.ENVIANDO
          ? StatusNotaFiscal.PENDENTE_SEFAZ
          : StatusNotaFiscal.AUTORIZADA;
      try {
        // Uma transacao por nota: a colisao de versao de UMA nota nao pode desfazer as demais.
        // O caso concreto e a corrida com a tx2 de uma transmissao que finalmente respondeu — ela
        // incrementa a versao e faz este save falhar; sem o isolamento, o lote inteiro seria
        // revertido e a excecao subiria para o job, abortando tambem a sincronizacao seguinte.
        boolean mudou = Boolean.TRUE.equals(emTransacao(() -> {
          NotaFiscal atual = notaFiscalRepository.findById(nota.getId()).orElse(null);
          if (atual == null || atual.getStatus() != anterior) {
            // A nota saiu do estado transitorio entre a leitura e agora (a transmissao respondeu):
            // nao ha o que reconciliar, e contar isso mentiria no total devolvido ao job.
            return false;
          }
          atual.setStatus(destino);
          notaFiscalRepository.save(atual);
          return true;
        }));
        if (!mudou) {
          continue;
        }
        reconciliadas++;
        log.warn(
            "Transmissao presa reconciliada: notaId={} chave={} de={} para={} parada desde={}",
            nota.getId(), nota.getChaveAcesso(), anterior, destino, nota.getUpdatedAt());
      } catch (RuntimeException e) {
        log.warn("Falha ao reconciliar a nota {} (de {} para {}); sera retentada no proximo ciclo",
            nota.getId(), anterior, destino, e);
      }
    }
    return reconciliadas;
  }

  /** Ambiente em que a nota foi transmitida, ou o configurado quando ela ainda nao tem um. */
  private SefazAmbiente ambienteDe(NotaFiscal nota) {
    return nota.getSefazAmbiente() != null
        ? nota.getSefazAmbiente()
        : sefazProperties.getAmbiente();
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

  // -------------------------------------------------------------------------
  // Suporte transacional das transmissoes (issue #96)
  // -------------------------------------------------------------------------

  /**
   * Executa um bloco numa transação curta e própria.
   *
   * <p>Usa {@link TransactionTemplate} em vez de {@code @Transactional} por método porque as
   * fases de uma transmissão são chamadas de dentro do próprio serviço: uma chamada direta entre
   * métodos da mesma classe <strong>não passa pelo proxy do Spring</strong> e a anotação seria
   * ignorada em silêncio — tudo voltaria a rodar numa transação só, incluindo a rede, que é
   * exatamente o defeito que esta issue remove. Mesmo desenho já usado em
   * {@link ImportacaoNotaFiscalService}.
   */
  private <T> T emTransacao(Supplier<T> acao) {
    return transactionTemplate.execute(status -> acao.get());
  }

  /** Carrega a nota com lock pessimista, mantendo o 404 anti-IDOR antes de qualquer 409. */
  private NotaFiscal carregarComLock(UUID notaId, Produtor produtor) {
    return notaFiscalRepository
        .findByIdAndProdutorIdForUpdate(notaId, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));
  }

  /**
   * Tira a nota do estado transitório após uma transmissão que não chegou a um desfecho,
   * devolvendo-a a um estado operável.
   *
   * <p>A falha original é sempre relançada ao chamador; esta compensação não a substitui. Se a
   * própria compensação falhar, a exceção original prevalece e a nota é resgatada mais tarde por
   * {@link #reconciliarTransmissoesPresas()} — nunca fica presa.
   */
  private void compensar(
      UUID notaId, Produtor produtor, StatusNotaFiscal destino, String operacao) {
    try {
      emTransacao(() -> {
        NotaFiscal nota = carregarComLock(notaId, produtor);
        nota.setStatus(destino);
        return notaFiscalRepository.save(nota);
      });
      log.warn("Transmissao de {} sem desfecho: notaId={} devolvida para {}",
          operacao, notaId, destino);
    } catch (RuntimeException e) {
      // Nao mascara a falha original — o job de reconciliacao resgata a nota depois.
      log.error("Falha ao compensar {} da nota {}; sera reconciliada pelo job",
          operacao, notaId, e);
    }
  }

  /** Dados necessários à transmissão, materializados antes de sair da transação. */
  private record DadosTransmissao(String xml, String chaveAcesso) {
  }

  /** Dados necessários à consulta de status, materializados antes de sair da transação. */
  private record DadosConsulta(String chaveAcesso, SefazAmbiente ambiente) {
  }

  /** Dados necessários ao cancelamento, materializados antes de sair da transação. */
  private record DadosCancelamento(
      String chaveAcesso,
      String protocoloAutorizacao,
      SefazAmbiente ambiente,
      OffsetDateTime solicitadoEm) {
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
