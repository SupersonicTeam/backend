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
 * <p>Ativo quando {@code app.nfe.sefaz.mock.enabled=true} (padrão). Simula retorno
 * determinístico de autorização sem qualquer conexão real com a SEFAZ.
 *
 * <p><strong>Atenção:</strong> este componente não realiza comunicação SOAP/HTTPS.
 * O transporte real (SOAP+mTLS com certificado ICP-Brasil) será implementado na
 * <em>Sprint 7</em> como uma nova classe que implemente {@link SefazTransport},
 * ativada por {@code app.nfe.sefaz.mock.enabled=false}. Nenhum serviço precisa
 * ser alterado para fazer o switch.
 *
 * <p>Retorno simulado:
 * <ul>
 *   <li>{@code cStat = "100"} — Autorizado o uso da NF-e</li>
 *   <li>{@code xMotivo = "Autorizado o uso da NF-e"}</li>
 *   <li>{@code situacao = AUTORIZADA}</li>
 *   <li>{@code protocolo} derivado da chave de acesso (prefixo "1" + primeiros 14 dígitos)</li>
 *   <li>{@code dataAutorizacao = OffsetDateTime.now()}</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.nfe.sefaz.mock.enabled", havingValue = "true",
    matchIfMissing = true)
public class MockSefazTransport implements SefazTransport {

  private static final Logger log = LoggerFactory.getLogger(MockSefazTransport.class);

  private static final String CSTAT_AUTORIZADO = "100";
  private static final String XMOTIVO_AUTORIZADO = "Autorizado o uso da NF-e";
  private static final String CSTAT_EVENTO_VINCULADO = "135";
  private static final String XMOTIVO_EVENTO_VINCULADO = "Evento registrado e vinculado a NF-e";
  private static final String CSTAT_NAO_CONSTA = "217";
  private static final String XMOTIVO_NAO_CONSTA =
      "NF-e nao consta na base de dados da SEFAZ";

  /**
   * Chaves efetivamente transmitidas por este mock.
   *
   * <p><strong>Por que existe:</strong> antes, {@code consultarStatus} respondia AUTORIZADA para
   * qualquer chave, inclusive de nota que nunca foi transmitida. Combinado com a compensacao da
   * issue #96 (uma falha no envio deixa a nota em {@code PENDENTE_SEFAZ}), isso fazia o
   * {@code SefazStatusSyncJob} "autorizar" uma NF-e inexistente no fisco e publicar
   * {@code NotaEmitidaEvent} — o debito de IBS/CBS entrava na apuracao de uma nota que nunca saiu.
   * A SEFAZ real responde cStat 217 para chave desconhecida; o mock passa a fazer o mesmo.
   *
   * <p>Memoria do processo apenas (o transporte real consulta o fisco): ao reiniciar a aplicacao,
   * uma nota transmitida antes do restart passa a ser vista como desconhecida. Aceitavel em dev e
   * teste, que e o unico escopo deste componente.
   */
  private final Set<String> chavesTransmitidas = ConcurrentHashMap.newKeySet();

  /**
   * {@inheritDoc}
   *
   * <p>Simula autorização imediata e determinística. O protocolo é derivado da chave
   * de acesso para facilitar rastreabilidade em testes.
   */
  @Override
  public SefazRetorno enviar(String xmlAssinado, String chaveAcesso, SefazAmbiente ambiente) {
    log.info("[MOCK SEFAZ] Simulando envio de NF-e: chave={} ambiente={}", chaveAcesso, ambiente);
    // O Set nao aceita null; uma chave nula ja e tratada mais abaixo pelo buildProtocolo.
    if (chaveAcesso != null) {
      chavesTransmitidas.add(chaveAcesso);
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
   * <p>Responde AUTORIZADA apenas para chave que <strong>passou por
   * {@link #enviar(String, String, SefazAmbiente)}</strong> nesta execucao. Chave desconhecida
   * recebe cStat 217 ({@code REJEITADA}), como a SEFAZ real — ver {@link #chavesTransmitidas}
   * para o defeito que isso evita. Implementacao real na Sprint 7 (NfeConsultaProtocolo).
   */
  @Override
  public SefazRetorno consultarStatus(String chaveAcesso, SefazAmbiente ambiente) {
    log.info("[MOCK SEFAZ] Simulando consulta de status: chave={} ambiente={}", chaveAcesso,
        ambiente);

    if (!chavesTransmitidas.contains(chaveAcesso)) {
      log.warn("[MOCK SEFAZ] Chave nunca transmitida por este mock: chave={} -> cStat={}",
          chaveAcesso, CSTAT_NAO_CONSTA);
      return new SefazRetorno(
          SituacaoSefaz.REJEITADA,
          CSTAT_NAO_CONSTA,
          XMOTIVO_NAO_CONSTA,
          null,
          null);
    }

    String protocolo = buildProtocolo(chaveAcesso);
    return new SefazRetorno(
        SituacaoSefaz.AUTORIZADA,
        CSTAT_AUTORIZADO,
        XMOTIVO_AUTORIZADO,
        protocolo,
        OffsetDateTime.now());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Simula o cancelamento com sucesso determinístico: retorna o evento de cancelamento
   * homologado (cStat 135 — "Evento registrado e vinculado a NF-e"), com protocolo derivado
   * da chave. A {@link SituacaoSefaz} não recebe um valor específico de cancelamento — o
   * sucesso é interpretado pelo serviço via cStat de evento vinculado (∈ {135, 155}); aqui
   * usamos {@link SituacaoSefaz#AUTORIZADA} apenas como marcador semântico de sucesso
   * técnico da comunicação. O evento SOAP real (110111) é débito da Sprint 7.
   */
  @Override
  public SefazRetorno cancelar(
      String chaveAcesso, String justificativa, String protocoloAutorizacao,
      SefazAmbiente ambiente) {
    // Nunca logar a justificativa completa (pode conter dados sensiveis) — LGPD.
    log.info("[MOCK SEFAZ] Simulando cancelamento de NF-e: chave={} ambiente={}",
        chaveAcesso, ambiente);
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
