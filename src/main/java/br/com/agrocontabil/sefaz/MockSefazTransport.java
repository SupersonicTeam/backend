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

  /**
   * {@inheritDoc}
   *
   * <p>Simula autorização imediata e determinística. O protocolo é derivado da chave
   * de acesso para facilitar rastreabilidade em testes.
   */
  @Override
  public SefazRetorno enviar(String xmlAssinado, String chaveAcesso, SefazAmbiente ambiente) {
    log.info("[MOCK SEFAZ] Simulando envio de NF-e: chave={} ambiente={}", chaveAcesso, ambiente);
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
   * <p>Simula consulta de status retornando sempre {@link SituacaoSefaz#AUTORIZADA}
   * para a chave informada. Implementação real na Sprint 7 (NfeConsultaProtocolo).
   */
  @Override
  public SefazRetorno consultarStatus(String chaveAcesso, SefazAmbiente ambiente) {
    log.info("[MOCK SEFAZ] Simulando consulta de status: chave={} ambiente={}", chaveAcesso,
        ambiente);
    String protocolo = buildProtocolo(chaveAcesso);
    return new SefazRetorno(
        SituacaoSefaz.AUTORIZADA,
        CSTAT_AUTORIZADO,
        XMOTIVO_AUTORIZADO,
        protocolo,
        OffsetDateTime.now());
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
