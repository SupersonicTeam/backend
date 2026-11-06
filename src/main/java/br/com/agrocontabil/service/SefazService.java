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
import br.com.agrocontabil.exception.RegraFiscalDesatualizadaException;
import br.com.agrocontabil.nfe.VersaoRegraFiscal;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.SefazComunicacaoException;
import br.com.agrocontabil.exception.SefazConexaoException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.nfe.DigestAssinatura;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
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
 *       REJEITADA/DENEGADA → {@code REJEITADA}; PENDENTE → {@code PENDENTE_SEFAZ}; na consulta,
 *       NAO_ENCONTRADA → {@code XML_GERADO} e CANCELADA → {@code CANCELADA}.</li>
 *   <li>Rejeição do fisco não é erro: é resultado válido persistido com cStat e motivo.</li>
 * </ul>
 *
 * <p><strong>Entrega incerta (issue #83):</strong> envio e cancelamento não são idempotentes, e
 * o {@link SefazClient} só os re-tenta em falha de conexão. Quando a resposta se perde
 * ({@link br.com.agrocontabil.exception.SefazRespostaIndeterminadaException}) ou o fisco acusa
 * duplicidade (cStat 204 no envio; 218/420/573 no cancelamento), este serviço
 * <strong>consulta a situação pela chave</strong> antes de decidir, em vez de reenviar. A
 * máquina de estados completa está em {@code .specs/features/onda-6-sefaz-real/spec.md}.
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
  public static final int PRAZO_CANCELAMENTO_DIAS = 30;

  /**
   * Códigos cStat que indicam cancelamento aceito pela SEFAZ (evento vinculado à NF-e):
   * 135 (evento registrado e vinculado) e 155 (evento registrado, vinculado fora do prazo).
   */
  private static final Set<String> CSTAT_CANCELAMENTO_ACEITO = Set.of("135", "155");

  /**
   * cStat de envio que acusa duplicidade de NF-e (issue #83): a chave já está na base do fisco,
   * tipicamente porque um envio anterior foi processado e a resposta se perdeu. Não é rejeição
   * dos dados; a situação real vem da consulta pela chave.
   */
  private static final String CSTAT_DUPLICIDADE_NFE = "204";

  /**
   * cStats de evento que dizem que a NF-e <strong>já está cancelada</strong> (issue #83):
   * 218 (NF-e já cancelada na base da SEFAZ), 420 (cancelamento para NF-e já cancelada) e 573
   * (duplicidade de evento). Não são recusa: um cancelamento anterior foi registrado e a resposta
   * se perdeu. Confirma-se pela consulta antes de marcar a nota como cancelada.
   */
  private static final Set<String> CSTAT_JA_CANCELADA = Set.of("218", "420", "573");

  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final SefazClient sefazClient;
  private final SefazProperties sefazProperties;
  private final ApplicationEventPublisher eventPublisher;
  private final TransactionTemplate transactionTemplate;

  /**
   * Onde a última sincronização parou por causa do disjuntor; a próxima retoma logo depois
   * (issue #83). Nulo = começar do início do keyset.
   *
   * <p>Sem isso, cada execução recomeçaria do menor ID: notas que falham de forma persistente no
   * começo da ordenação disparariam o disjuntor sempre no mesmo ponto e as demais nunca seriam
   * sincronizadas. Com a retomada, cada execução avança; quando uma chega ao fim sem disparar, a
   * seguinte volta ao início. Estado só em memória, por instância: basta para garantir progresso,
   * e um restart apenas recomeça do início.
   */
  private final NotaFiscalValidacaoService validacaoService;
  private final AtomicReference<UUID> retomarSincronizacaoApos = new AtomicReference<>();

  /** Constrói o serviço com as dependências de persistência, transporte, configuração e eventos. */
  public SefazService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      SefazClient sefazClient,
      SefazProperties sefazProperties,
      ApplicationEventPublisher eventPublisher,
      TransactionTemplate transactionTemplate,
      NotaFiscalValidacaoService validacaoService) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.sefazClient = sefazClient;
    this.sefazProperties = sefazProperties;
    this.eventPublisher = eventPublisher;
    this.transactionTemplate = transactionTemplate;
    this.validacaoService = validacaoService;
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
  @Auditado(acao = AcaoAuditada.NOTA_ENVIADA_SEFAZ, recurso = RecursoAuditado.NOTA_FISCAL)
  public SefazEnvioResponse enviar(UUID notaId) {
    Produtor produtor = produtorProvider.get();
    SefazAmbiente ambiente = sefazProperties.getAmbiente();

    // Transacao 1 (curta): sob lock, valida a pre-condicao e reserva a nota marcando ENVIANDO.
    // O commit libera lock e conexao ANTES da rede. Uma segunda requisicao concorrente espera o
    // lock, encontra ENVIANDO e recebe 409 — o XML chega a SEFAZ uma unica vez.
    DadosTransmissao dados;
    try {
      dados = reservarParaEnvio(notaId, produtor, ambiente);
    } catch (RegraFiscalDesatualizadaException e) {
      // Melhor esforco: o 409 de acao sai sempre; se a nota nao puder voltar a editavel agora, o
      // proximo envio recusa de novo e tenta de novo.
      try {
        validacaoService.registrarXmlDesatualizado(notaId, produtor.getId());
      } catch (RuntimeException falha) {
        log.error("Nao foi possivel devolver a nota {} a edicao apos recusar o XML desatualizado",
            notaId, falha);
      }
      throw e;
    }
    return enviarReservada(notaId, produtor, ambiente, dados);
  }

  private DadosTransmissao reservarParaEnvio(UUID notaId, Produtor produtor,
      SefazAmbiente ambiente) {
    return emTransacao(() -> {
      NotaFiscal nota = carregarComLock(notaId, produtor);
      if (nota.getStatus() != StatusNotaFiscal.XML_GERADO) {
        throw new NotaFiscalNaoEnviavelException(notaId, nota.getStatus());
      }
      // XML gerado por regra fiscal anterior a atual (issue #389): nao transmite. A nota volta a
      // um estado editavel fora desta transacao (ver o catch abaixo), porque a excecao a desfaz.
      if (VersaoRegraFiscal.desatualizada(nota.getVersaoRegraFiscal())) {
        throw new RegraFiscalDesatualizadaException(notaId, nota.getVersaoRegraFiscal());
      }
      nota.setStatus(StatusNotaFiscal.ENVIANDO);
      // O ambiente vai junto na reserva, antes da rede: se o desfecho ficar incerto, toda consulta
      // posterior (reconciliacao, job, endpoint) precisa perguntar ao MESMO ambiente que recebeu o
      // XML. Sem isso a nota pendente herdaria o ambiente configurado no momento da consulta, e um
      // "nao encontrada" no ambiente errado a devolveria a XML_GERADO (issue #83).
      nota.setSefazAmbiente(ambiente);
      notaFiscalRepository.save(nota);
      return new DadosTransmissao(nota.getXmlGerado(), nota.getChaveAcesso());
    });
  }

  private SefazEnvioResponse enviarReservada(UUID notaId, Produtor produtor,
      SefazAmbiente ambiente, DadosTransmissao dados) {
    // Fora de transacao: a chamada de rede (com retry) e a eventual consulta de reconciliacao
    // nao seguram conexao do pool nem o lock.
    SefazRetorno retorno = transmitirEnvio(notaId, produtor, dados, ambiente);

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
   *   <li>{@link SefazClient#cancelar} (retry só em falha de conexão → HTTP 502; resposta
   *       indeterminada ou cStat de "já cancelada" são conferidos pela consulta da chave).</li>
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
  @Auditado(acao = AcaoAuditada.NOTA_CANCELADA, recurso = RecursoAuditado.NOTA_FISCAL)
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

    // Fora de transacao: o evento de cancelamento (e a eventual consulta de reconciliacao) vai a
    // SEFAZ sem segurar conexao nem lock.
    SefazRetorno retorno = transmitirCancelamento(notaId, produtor, dados, motivo);

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
   * {@link br.com.agrocontabil.sefaz.SefazStatusSyncJob}. Desenho (issue #83):
   * <ul>
   *   <li>Lê <strong>só IDs</strong>, em lotes por keyset ({@code app.nfe.sefaz.sync.lote-tamanho}),
   *       cada lote numa transação curta de leitura. Antes, {@code findByStatus} materializava todas
   *       as notas pendentes de uma vez.</li>
   *   <li>Cada nota é processada isoladamente ({@link #sincronizarPendente(UUID)}): leitura curta,
   *       consulta fora de transação, aplicação curta com recheck. Qualquer falha de uma nota é
   *       logada e não afeta as demais.</li>
   *   <li><strong>Disjuntor:</strong> {@code app.nfe.sefaz.sync.max-falhas-consecutivas} falhas de
   *       comunicação seguidas encerram a execução — a SEFAZ está fora, e insistir só prenderia o
   *       job pelo pior caso de cada nota. A execução seguinte retoma depois da última nota tentada
   *       ({@link #retomarSincronizacaoApos}), para que falhas persistentes no começo da ordenação
   *       não impeçam para sempre a sincronização das demais.</li>
   * </ul>
   *
   * @return quantidade de notas cujo status mudou após a consulta
   */
  public int sincronizarPendentes() {
    int loteTamanho = sefazProperties.getSync().getLoteTamanho();
    int limiteFalhas = sefazProperties.getSync().getMaxFalhasConsecutivas();
    int atualizadas = 0;
    int falhasSeguidas = 0;
    UUID ultimo = retomarSincronizacaoApos.getAndSet(null);

    while (true) {
      UUID apos = ultimo;
      List<UUID> lote = emTransacao(() -> apos == null
          ? notaFiscalRepository.findIdsPorStatus(
              StatusNotaFiscal.PENDENTE_SEFAZ, PageRequest.ofSize(loteTamanho))
          : notaFiscalRepository.findIdsPorStatusApos(
              StatusNotaFiscal.PENDENTE_SEFAZ, apos, PageRequest.ofSize(loteTamanho)));

      for (UUID notaId : lote) {
        switch (sincronizarPendente(notaId)) {
          case ATUALIZADA -> {
            atualizadas++;
            falhasSeguidas = 0;
          }
          case INALTERADA -> falhasSeguidas = 0;
          case FALHA_COMUNICACAO -> {
            falhasSeguidas++;
            if (falhasSeguidas >= limiteFalhas) {
              retomarSincronizacaoApos.set(notaId);
              log.warn("Sincronizacao SEFAZ interrompida apos {} falhas de comunicacao seguidas; "
                  + "a proxima execucao retoma depois da nota {}", falhasSeguidas, notaId);
              return atualizadas;
            }
          }
          // Falha que nao e de comunicacao (dado inconsistente, colisao de versao): diz respeito
          // a nota, nao a SEFAZ, entao nao conta para o disjuntor nem o zera.
          case FALHA -> { }
          default -> throw new IllegalStateException("Resultado nao tratado");
        }
      }

      if (lote.size() < loteTamanho) {
        return atualizadas;
      }
      ultimo = lote.get(lote.size() - 1);
    }
  }

  /** Desfecho da sincronização de uma nota, para a contagem e o disjuntor do lote. */
  private enum ResultadoSincronizacao { ATUALIZADA, INALTERADA, FALHA_COMUNICACAO, FALHA }

  /**
   * Sincroniza uma única nota pendente com a SEFAZ, isolada das demais (issue #83).
   *
   * <p>Três passos, só os de banco em transação: lê chave e ambiente (a nota pode ter saído de
   * {@code PENDENTE_SEFAZ} desde que o ID foi listado), consulta a SEFAZ fora de transação e aplica
   * o retorno numa transação curta que confere o status de novo antes de gravar — a guarda da
   * issue #79 contra sobrescrever nota que já chegou a um estado terminal.
   *
   * @param notaId nota listada como pendente
   * @return o desfecho; nunca lança
   */
  private ResultadoSincronizacao sincronizarPendente(UUID notaId) {
    try {
      DadosConsulta dados = emTransacao(() -> notaFiscalRepository.findById(notaId)
          .filter(nota -> nota.getStatus() == StatusNotaFiscal.PENDENTE_SEFAZ)
          .filter(nota -> nota.getChaveAcesso() != null)
          .map(nota -> new DadosConsulta(nota.getChaveAcesso(), ambienteDe(nota)))
          .orElse(null));
      if (dados == null) {
        return ResultadoSincronizacao.INALTERADA;
      }

      SefazRetorno retorno = sefazClient.consultarStatus(dados.chaveAcesso(), dados.ambiente());

      boolean mudou = Boolean.TRUE.equals(emTransacao(() -> {
        NotaFiscal nota = notaFiscalRepository.findById(notaId).orElse(null);
        if (nota == null || nota.getStatus() != StatusNotaFiscal.PENDENTE_SEFAZ) {
          // Saiu de PENDENTE_SEFAZ entre a leitura e agora: nao sobrescreve.
          return false;
        }
        aplicarRetorno(nota, retorno, dados.ambiente());
        NotaFiscal salva = notaFiscalRepository.save(nota);
        if (salva.getStatus() == StatusNotaFiscal.PENDENTE_SEFAZ) {
          return false;
        }
        publicarSeAutorizou(StatusNotaFiscal.PENDENTE_SEFAZ, salva);
        return true;
      }));
      return mudou ? ResultadoSincronizacao.ATUALIZADA : ResultadoSincronizacao.INALTERADA;
    } catch (SefazComunicacaoException e) {
      // Sera reprocessada na proxima execucao.
      log.warn("Falha ao sincronizar status da nota {} com a SEFAZ: {}", notaId, e.getMessage());
      return ResultadoSincronizacao.FALHA_COMUNICACAO;
    } catch (RuntimeException e) {
      log.warn("Falha ao sincronizar a nota {}; sera retentada no proximo ciclo", notaId, e);
      return ResultadoSincronizacao.FALHA;
    }
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
   * Transmite o XML e resolve os desfechos incertos, sem nunca reenviar (issue #83).
   *
   * <p>Roda fora de transação. Em cada desfecho que não chega à tx2, a nota sai de
   * {@code ENVIANDO} pela compensação antes de a exceção subir:
   * <ul>
   *   <li>{@link SefazConexaoException}: o XML comprovadamente não saiu (o client já esgotou as
   *       tentativas de conectar), então {@code XML_GERADO}, que libera o reenvio.</li>
   *   <li>Resposta indeterminada: consulta pela chave. Se o fisco já decidiu, o desfecho segue
   *       para a tx2 como se a resposta tivesse chegado; senão {@code PENDENTE_SEFAZ}, e o job
   *       estabelece a verdade depois. Nunca {@code XML_GERADO}: liberaria um reenvio que
   *       duplicaria a NF-e.</li>
   *   <li>cStat 204 (duplicidade): consulta pela chave; se o fisco não confirmar, a nota fica
   *       pendente com o 204 registrado, e nunca {@code REJEITADA}.</li>
   * </ul>
   */
  private SefazRetorno transmitirEnvio(
      UUID notaId, Produtor produtor, DadosTransmissao dados, SefazAmbiente ambiente) {
    SefazRetorno retorno;
    try {
      retorno = sefazClient.enviar(dados.xml(), dados.chaveAcesso(), ambiente);
    } catch (SefazConexaoException e) {
      compensar(notaId, produtor, StatusNotaFiscal.XML_GERADO, "envio");
      throw e;
    } catch (SefazComunicacaoException e) {
      Optional<SefazRetorno> desfecho = consultarDesfechoDoEnvio(dados.chaveAcesso(), ambiente);
      if (desfecho.isEmpty()) {
        compensar(notaId, produtor, StatusNotaFiscal.PENDENTE_SEFAZ, "envio");
        throw e;
      }
      log.warn("Envio sem resposta reconciliado pela chave: notaId={} chave={} cStat={}",
          notaId, dados.chaveAcesso(), desfecho.get().cStat());
      return desfecho.get();
    } catch (RuntimeException e) {
      // Falha fora do contrato do transporte: nao se sabe se o XML saiu. PENDENTE_SEFAZ e o
      // estado "transmiti, desfecho desconhecido" que o SefazStatusSyncJob resolve pela chave.
      compensar(notaId, produtor, StatusNotaFiscal.PENDENTE_SEFAZ, "envio");
      throw e;
    }

    if (!CSTAT_DUPLICIDADE_NFE.equals(retorno.cStat())) {
      return retorno;
    }
    SefazRetorno duplicidade = retorno;
    Optional<SefazRetorno> desfecho = consultarDesfechoDoEnvio(dados.chaveAcesso(), ambiente);
    log.warn("Duplicidade de NF-e no envio: notaId={} chave={} reconciliada={}",
        notaId, dados.chaveAcesso(), desfecho.isPresent());
    return desfecho.orElseGet(() -> new SefazRetorno(
        SituacaoSefaz.PENDENTE, duplicidade.cStat(), duplicidade.xMotivo(), null, null));
  }

  /**
   * Consulta pela chave o desfecho de um envio incerto. Devolve o retorno só quando o fisco já
   * decidiu (autorizou, rejeitou ou denegou); em qualquer outro caso, inclusive falha da própria
   * consulta, devolve vazio e a nota fica pendente.
   *
   * <p>{@link SituacaoSefaz#NAO_ENCONTRADA} logo após um timeout não prova que o XML não chegou:
   * a SEFAZ pode ainda estar processando. Por isso não devolve a nota a {@code XML_GERADO} aqui.
   */
  private Optional<SefazRetorno> consultarDesfechoDoEnvio(
      String chaveAcesso, SefazAmbiente ambiente) {
    try {
      SefazRetorno consulta = sefazClient.consultarStatus(chaveAcesso, ambiente);
      return switch (consulta.situacao()) {
        case AUTORIZADA, REJEITADA, DENEGADA -> Optional.of(consulta);
        case PENDENTE, NAO_ENCONTRADA, CANCELADA -> Optional.empty();
      };
    } catch (RuntimeException e) {
      log.warn("Consulta de reconciliacao do envio falhou: chave={}", chaveAcesso, e);
      return Optional.empty();
    }
  }

  /**
   * Transmite o evento de cancelamento e resolve os desfechos incertos (issues #83 e #84).
   *
   * <p>Roda fora de transação. Toda saída que não é um cancelamento confirmado devolve a nota a
   * {@code AUTORIZADA}, e não a {@code PENDENTE_SEFAZ}: a nota segue autorizada no fisco, e
   * {@code PENDENTE_SEFAZ} a tiraria do débito da apuração. Um cancelamento confirmado vem de:
   * <ul>
   *   <li>cStat 135/155 (evento vinculado);</li>
   *   <li>resposta indeterminada, ou cStat de "já cancelada" (218/420/573), em que a consulta
   *       pela chave responde {@link SituacaoSefaz#CANCELADA}.</li>
   * </ul>
   * Qualquer outro cStat é recusa do fisco: {@link CancelamentoRejeitadoException} (422).
   */
  private SefazRetorno transmitirCancelamento(
      UUID notaId, Produtor produtor, DadosCancelamento dados, String motivo) {
    SefazRetorno retorno;
    try {
      retorno = sefazClient.cancelar(
          dados.chaveAcesso(), motivo, dados.protocoloAutorizacao(), dados.ambiente());
    } catch (SefazConexaoException e) {
      compensar(notaId, produtor, StatusNotaFiscal.AUTORIZADA, "cancelamento");
      throw e;
    } catch (SefazComunicacaoException e) {
      Optional<SefazRetorno> confirmacao =
          consultarCancelamentoRegistrado(dados.chaveAcesso(), dados.ambiente());
      if (confirmacao.isEmpty()) {
        compensar(notaId, produtor, StatusNotaFiscal.AUTORIZADA, "cancelamento");
        throw e;
      }
      log.warn("Cancelamento sem resposta reconciliado pela chave: notaId={} chave={}",
          notaId, dados.chaveAcesso());
      return confirmacao.get();
    } catch (RuntimeException e) {
      compensar(notaId, produtor, StatusNotaFiscal.AUTORIZADA, "cancelamento");
      throw e;
    }

    if (CSTAT_CANCELAMENTO_ACEITO.contains(retorno.cStat())) {
      return retorno;
    }
    if (CSTAT_JA_CANCELADA.contains(retorno.cStat())) {
      Optional<SefazRetorno> confirmacao =
          consultarCancelamentoRegistrado(dados.chaveAcesso(), dados.ambiente());
      log.warn("Cancelamento com cStat {} (ja cancelada): notaId={} chave={} confirmado={}",
          retorno.cStat(), notaId, dados.chaveAcesso(), confirmacao.isPresent());
      if (confirmacao.isPresent()) {
        return confirmacao.get();
      }
    }

    // Recusa do fisco (issue #84). A nota volta a AUTORIZADA e segue cancelavel; a excecao
    // repassa o cStat/xMotivo do fisco ao produtor.
    compensar(notaId, produtor, StatusNotaFiscal.AUTORIZADA, "cancelamento");
    throw new CancelamentoRejeitadoException(notaId, retorno.cStat(), retorno.xMotivo());
  }

  /**
   * Consulta pela chave se o cancelamento está registrado no fisco. Devolve o retorno só quando a
   * situação é {@link SituacaoSefaz#CANCELADA}; qualquer outra resposta ou falha devolve vazio.
   */
  private Optional<SefazRetorno> consultarCancelamentoRegistrado(
      String chaveAcesso, SefazAmbiente ambiente) {
    try {
      SefazRetorno consulta = sefazClient.consultarStatus(chaveAcesso, ambiente);
      return consulta.situacao() == SituacaoSefaz.CANCELADA
          ? Optional.of(consulta)
          : Optional.empty();
    } catch (RuntimeException e) {
      log.warn("Consulta de reconciliacao do cancelamento falhou: chave={}", chaveAcesso, e);
      return Optional.empty();
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
   * protocolo e data de autorização; se a consulta revelou cancelamento, protocolo e data dele.
   */
  private void aplicarRetorno(NotaFiscal nota, SefazRetorno retorno, SefazAmbiente ambiente) {
    if (retorno.situacao() == SituacaoSefaz.NAO_ENCONTRADA
        && CSTAT_DUPLICIDADE_NFE.equals(nota.getSefazCstat())) {
      // O fisco ja acusou esta chave como existente (204). Um "nao encontrada" depois disso e
      // atraso entre as bases da SEFAZ, nao prova de que o XML nao chegou: devolver a XML_GERADO
      // levaria a um reenvio, outro 204, e o ciclo nao convergiria. A nota fica pendente com o 204
      // registrado ate a consulta confirmar o desfecho.
      log.warn("Chave {} nao encontrada apos duplicidade (204); nota {} segue pendente",
          nota.getChaveAcesso(), nota.getId());
      return;
    }
    nota.setSefazCstat(retorno.cStat());
    nota.setSefazMotivo(retorno.xMotivo());
    nota.setSefazAmbiente(ambiente);
    if (nota.getOrigem() == OrigemNotaFiscal.IMPORTADA && !importadaConfirmada(nota, retorno)) {
      return;
    }
    nota.setStatus(mapStatus(retorno.situacao()));
    if (retorno.situacao() == SituacaoSefaz.AUTORIZADA) {
      nota.setProtocoloAutorizacao(retorno.protocolo());
      nota.setDataAutorizacao(retorno.dataAutorizacao());
    } else if (retorno.situacao() == SituacaoSefaz.CANCELADA) {
      nota.setProtocoloCancelamento(retorno.protocolo());
      nota.setDataCancelamento(retorno.dataAutorizacao() != null
          ? retorno.dataAutorizacao()
          : OffsetDateTime.now(FUSO_BRASIL));
    }
  }

  /**
   * Regras da NF-e de terceiro importada que entrou pendente (issue #223), as mesmas da
   * importação: chave que o fisco não conhece nunca foi autorizada (não volta a {@code XML_GERADO},
   * que liberaria "transmitir" o XML de outro emitente), e autorizada com {@code digVal} diferente
   * do DigestValue do XML é outro conteúdo com a mesma chave.
   *
   * @return {@code true} para seguir o mapeamento comum; {@code false} se a nota já foi recusada
   */
  private boolean importadaConfirmada(NotaFiscal nota, SefazRetorno retorno) {
    if (retorno.situacao() == SituacaoSefaz.NAO_ENCONTRADA) {
      nota.setStatus(StatusNotaFiscal.REJEITADA);
      return false;
    }
    if (retorno.situacao() == SituacaoSefaz.AUTORIZADA
        && !DigestAssinatura.confere(nota.getXmlGerado(), retorno.digestValue())) {
      log.warn("NF-e importada {} com conteudo diferente do autorizado na SEFAZ (digVal)",
          nota.getId());
      nota.setStatus(StatusNotaFiscal.REJEITADA);
      nota.setSefazMotivo("Conteudo do XML importado difere da NF-e autorizada na SEFAZ");
      return false;
    }
    return true;
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

  /**
   * Mapeia a situação retornada pela SEFAZ para o status de domínio da nota.
   *
   * <p>{@code NAO_ENCONTRADA} e {@code CANCELADA} só vêm da consulta, que só é aplicada a notas em
   * {@code PENDENTE_SEFAZ} (guarda da issue #79). {@code NAO_ENCONTRADA} devolve a nota a
   * {@code XML_GERADO}: o fisco não tem a NF-e, então reenviar é seguro, e um reenvio que colida
   * com um processamento tardio volta como cStat 204 e é reconciliado. {@code CANCELADA} não
   * publica evento: a nota pendente nunca entrou no débito.
   */
  private StatusNotaFiscal mapStatus(SituacaoSefaz situacao) {
    return switch (situacao) {
      case AUTORIZADA -> StatusNotaFiscal.AUTORIZADA;
      case REJEITADA, DENEGADA -> StatusNotaFiscal.REJEITADA;
      case PENDENTE -> StatusNotaFiscal.PENDENTE_SEFAZ;
      case NAO_ENCONTRADA -> StatusNotaFiscal.XML_GERADO;
      case CANCELADA -> StatusNotaFiscal.CANCELADA;
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
