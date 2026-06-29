package br.com.agrocontabil.sefaz;

import java.time.OffsetDateTime;
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
 *   <li>{@code situation = AUTORIZADA}</li>
 *   <li>{@code protocol} derivado da key de acesso (prefix "1" + primeiros 14 dígitos)</li>
 *   <li>{@code authorizationDate = OffsetDateTime.now()}</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.nfe.sefaz.mock.enabled", havingValue = "true",
    matchIfMissing = true)
public class MockSefazTransport implements SefazTransport {

  private static final Logger log = LoggerFactory.getLogger(MockSefazTransport.class);

  private static final String CSTAT_AUTORIZADO = "100";
  private static final String XMOTIVO_AUTORIZADO = "Autorizado o uso da NF-e";

  /**
   * {@inheritDoc}
   *
   * <p>Simula autorização imediata e determinística. O protocol é derivado da key
   * de acesso para facilitar rastreabilidade em testes.
   */
  @Override
  public SefazReturn send(String xmlAssinado, String accessKey, SefazEnvironment ambiente) {
    log.info("[MOCK SEFAZ] Simulando envio de NF-e: chave={} ambiente={}", accessKey, ambiente);
    String protocol = buildProtocolo(accessKey);
    SefazReturn retorno = new SefazReturn(
        SefazSituation.AUTORIZADA,
        CSTAT_AUTORIZADO,
        XMOTIVO_AUTORIZADO,
        protocol,
        OffsetDateTime.now());
    log.info("[MOCK SEFAZ] Retorno simulado: cStat={} protocolo={}", retorno.cStat(), protocol);
    return retorno;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Simula consulta de status retornando sempre {@link SefazSituation#AUTORIZADA}
   * para a key informada. Implementação real na Sprint 7 (NfeConsultaProtocolo).
   */
  @Override
  public SefazReturn queryStatus(String accessKey, SefazEnvironment ambiente) {
    log.info("[MOCK SEFAZ] Simulando consulta de status: chave={} ambiente={}", accessKey,
        ambiente);
    String protocol = buildProtocolo(accessKey);
    return new SefazReturn(
        SefazSituation.AUTORIZADA,
        CSTAT_AUTORIZADO,
        XMOTIVO_AUTORIZADO,
        protocol,
        OffsetDateTime.now());
  }

  /**
   * Gera um número de protocol fictício derivado da key de acesso.
   *
   * <p>Formato: "1" + primeiros 14 dígitos da key = 15 dígitos no total.
   * Garante unicidade relativa por key e facilita rastreabilidade em logs de teste.
   *
   * @param accessKey key de acesso de 44 dígitos
   * @return protocol de 15 dígitos (fictício)
   */
  private String buildProtocolo(String accessKey) {
    if (accessKey == null || accessKey.length() < 14) {
      return "100000000000001";
    }
    return "1" + accessKey.substring(0, 14);
  }
}
