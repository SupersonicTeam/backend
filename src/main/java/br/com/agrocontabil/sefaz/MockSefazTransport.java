package br.com.agrocontabil.sefaz;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implementação mock do transporte SEFAZ para desenvolvimento e testes.
 *
 * <p>Ativo quando {@code app.nfe.sefaz.mock.enabled=true} (padrão). Simula os retornos da SEFAZ
 * sem qualquer conexão real.
 *
 * <p><strong>Atenção:</strong> este componente não realiza comunicação SOAP/HTTPS.
 * O transporte real (SOAP+mTLS com certificado ICP-Brasil) será uma nova classe que implemente
 * {@link SefazTransport}, ativada por {@code app.nfe.sefaz.mock.enabled=false}. Nenhum serviço
 * precisa ser alterado para fazer o switch.
 *
 * <p>Retornos simulados (issue #83: fiéis à SEFAZ nos casos de que a reconciliação depende):
 * <ul>
 *   <li>{@code enviar}: cStat 100 (autorizada) na primeira vez; cStat 204 (duplicidade de NF-e)
 *       se a chave já foi transmitida.</li>
 *   <li>{@code cancelar}: cStat 135 (evento vinculado) na primeira vez; cStat 573 (duplicidade de
 *       evento) se a chave já foi cancelada.</li>
 *   <li>{@code consultarStatus}: {@link SituacaoSefaz#CANCELADA} (101) para chave cancelada,
 *       {@link SituacaoSefaz#AUTORIZADA} (100) para chave transmitida e
 *       {@link SituacaoSefaz#NAO_ENCONTRADA} (217) para chave desconhecida.</li>
 *   <li>{@code protocolo} derivado da chave de acesso (prefixo "1" + primeiros 14 dígitos).</li>
 * </ul>
 *
 * <p>Estado só em memória do processo (o transporte real consulta o fisco): ao reiniciar a
 * aplicação, as chaves transmitidas e canceladas antes do restart são esquecidas. Aceitável em dev
 * e teste, que é o único escopo deste componente.
 */
@Component
@ConditionalOnProperty(name = "app.nfe.sefaz.mock.enabled", havingValue = "true",
    matchIfMissing = true)
public class MockSefazTransport implements SefazTransport {

  private static final Logger log = LoggerFactory.getLogger(MockSefazTransport.class);

  private static final String CSTAT_AUTORIZADO = "100";
  private static final String XMOTIVO_AUTORIZADO = "Autorizado o uso da NF-e";
  private static final String CSTAT_CANCELAMENTO_HOMOLOGADO = "101";
  private static final String XMOTIVO_CANCELAMENTO_HOMOLOGADO =
      "Cancelamento de NF-e homologado";
  private static final String CSTAT_DUPLICIDADE_NFE = "204";
  private static final String XMOTIVO_DUPLICIDADE_NFE = "Rejeicao: Duplicidade de NF-e";
  private static final String CSTAT_EVENTO_VINCULADO = "135";
  private static final String XMOTIVO_EVENTO_VINCULADO = "Evento registrado e vinculado a NF-e";
  private static final String CSTAT_DUPLICIDADE_EVENTO = "573";
  private static final String XMOTIVO_DUPLICIDADE_EVENTO = "Rejeicao: Duplicidade de evento";
  private static final String CSTAT_NAO_CONSTA = "217";
  private static final String XMOTIVO_NAO_CONSTA =
      "NF-e nao consta na base de dados da SEFAZ";

  /**
   * Chaves efetivamente transmitidas por este mock.
   *
   * <p><strong>Por que existe:</strong> antes, {@code consultarStatus} respondia AUTORIZADA para
   * qualquer chave, inclusive de nota que nunca foi transmitida. Combinado com a compensação
   * da issue #96 (uma falha no envio deixa a nota em {@code PENDENTE_SEFAZ}), isso fazia o
   * {@code SefazStatusSyncJob} "autorizar" uma NF-e inexistente no fisco e publicar
   * {@code NotaEmitidaEvent} — o débito de IBS/CBS entrava na apuração de uma nota que nunca saiu.
   * Também é o que permite simular a duplicidade (cStat 204) de um reenvio.
   */
  private final Set<String> chavesTransmitidas = ConcurrentHashMap.newKeySet();

  /** Chaves com evento de cancelamento registrado — base do 573 e da situação CANCELADA. */
  private final Set<String> chavesCanceladas = ConcurrentHashMap.newKeySet();

  /**
   * {@inheritDoc}
   *
   * <p>Simula autorização imediata. Reenviar uma chave já transmitida responde cStat 204, como a
   * SEFAZ real: é o cenário que a reconciliação pela chave precisa resolver (issue #83).
   */
  @Override
  public SefazRetorno enviar(String xmlAssinado, String chaveAcesso, SefazAmbiente ambiente) {
    log.info("[MOCK SEFAZ] Simulando envio de NF-e: chave={} ambiente={}", chaveAcesso, ambiente);
    // O Set nao aceita null; uma chave nula ja e tratada mais abaixo pelo buildProtocolo.
    if (chaveAcesso != null && !chavesTransmitidas.add(chaveAcesso)) {
      log.info("[MOCK SEFAZ] Chave ja transmitida: chave={} -> cStat={}",
          chaveAcesso, CSTAT_DUPLICIDADE_NFE);
      return new SefazRetorno(
          SituacaoSefaz.REJEITADA, CSTAT_DUPLICIDADE_NFE, XMOTIVO_DUPLICIDADE_NFE, null, null);
    }
    String protocolo = buildProtocolo(chaveAcesso);
    SefazRetorno retorno = new SefazRetorno(
        SituacaoSefaz.AUTORIZADA,
        CSTAT_AUTORIZADO,
        XMOTIVO_AUTORIZADO,
        protocolo,
        OffsetDateTime.now());
    log.info("[MOCK SEFAZ] Retorno simulado: cStat={} protocolo={}", retorno.cStat(), protocolo);
    return retorno;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Chave cancelada responde {@link SituacaoSefaz#CANCELADA}; chave que passou por
   * {@link #enviar(String, String, SefazAmbiente)} nesta execução responde AUTORIZADA; chave
   * desconhecida responde {@link SituacaoSefaz#NAO_ENCONTRADA} (cStat 217), como a SEFAZ real.
   */
  @Override
  public SefazRetorno consultarStatus(String chaveAcesso, SefazAmbiente ambiente) {
    log.info("[MOCK SEFAZ] Simulando consulta de status: chave={} ambiente={}", chaveAcesso,
        ambiente);

    if (chaveAcesso != null && chavesCanceladas.contains(chaveAcesso)) {
      return new SefazRetorno(
          SituacaoSefaz.CANCELADA,
          CSTAT_CANCELAMENTO_HOMOLOGADO,
          XMOTIVO_CANCELAMENTO_HOMOLOGADO,
          buildProtocolo(chaveAcesso),
          OffsetDateTime.now());
    }

    if (chaveAcesso == null || !chavesTransmitidas.contains(chaveAcesso)) {
      log.warn("[MOCK SEFAZ] Chave nunca transmitida por este mock: chave={} -> cStat={}",
          chaveAcesso, CSTAT_NAO_CONSTA);
      return new SefazRetorno(
          SituacaoSefaz.NAO_ENCONTRADA,
          CSTAT_NAO_CONSTA,
          XMOTIVO_NAO_CONSTA,
          null,
          null);
    }

    return new SefazRetorno(
        SituacaoSefaz.AUTORIZADA,
        CSTAT_AUTORIZADO,
        XMOTIVO_AUTORIZADO,
        buildProtocolo(chaveAcesso),
        OffsetDateTime.now());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Registra o evento de cancelamento (cStat 135, evento vinculado), com protocolo derivado da
   * chave. Um segundo cancelamento da mesma chave responde cStat 573 (duplicidade de evento).
   * O sucesso é interpretado pelo serviço via cStat de evento vinculado (∈ {135, 155}); a
   * {@link SituacaoSefaz#AUTORIZADA} aqui só marca sucesso técnico da comunicação.
   */
  @Override
  public SefazRetorno cancelar(
      String chaveAcesso, String justificativa, String protocoloAutorizacao,
      SefazAmbiente ambiente) {
    // Nunca logar a justificativa completa (pode conter dados sensiveis) — LGPD.
    log.info("[MOCK SEFAZ] Simulando cancelamento de NF-e: chave={} ambiente={}",
        chaveAcesso, ambiente);
    if (chaveAcesso != null && !chavesCanceladas.add(chaveAcesso)) {
      log.info("[MOCK SEFAZ] Evento de cancelamento ja registrado: chave={} -> cStat={}",
          chaveAcesso, CSTAT_DUPLICIDADE_EVENTO);
      return new SefazRetorno(
          SituacaoSefaz.REJEITADA,
          CSTAT_DUPLICIDADE_EVENTO,
          XMOTIVO_DUPLICIDADE_EVENTO,
          null,
          null);
    }
    String protocolo = buildProtocolo(chaveAcesso);
    SefazRetorno retorno = new SefazRetorno(
        SituacaoSefaz.AUTORIZADA,
        CSTAT_EVENTO_VINCULADO,
        XMOTIVO_EVENTO_VINCULADO,
        protocolo,
        OffsetDateTime.now());
    log.info("[MOCK SEFAZ] Retorno de cancelamento simulado: cStat={} protocolo={}",
        retorno.cStat(), protocolo);
    return retorno;
  }

  /**
   * Gera um número de protocolo fictício derivado da chave de acesso.
   *
   * <p>Formato: "1" + primeiros 14 dígitos da chave = 15 dígitos no total.
   * Garante unicidade relativa por chave e facilita rastreabilidade em logs de teste.
   *
   * @param chaveAcesso chave de acesso de 44 dígitos
   * @return protocolo de 15 dígitos (fictício)
   */
  private String buildProtocolo(String chaveAcesso) {
    if (chaveAcesso == null || chaveAcesso.length() < 14) {
      return "100000000000001";
    }
    return "1" + chaveAcesso.substring(0, 14);
  }
}
