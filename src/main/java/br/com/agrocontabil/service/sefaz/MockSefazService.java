package br.com.agrocontabil.service.sefaz;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementação mock do {@link SefazService} para as sprints anteriores à
 * integração real (Sprint 7). Gera um protocolo determinístico e não realiza
 * qualquer comunicação externa.
 *
 * <p><strong>Não usar em produção real:</strong> nenhuma transmissão SOAP nem
 * assinatura digital é feita aqui.
 */
@Service
public class MockSefazService implements SefazService {

  private static final Logger log = LoggerFactory.getLogger(MockSefazService.class);

  private final AtomicLong sequencia = new AtomicLong(1);

  @Override
  public CancelamentoSefazResult cancelar(String chaveAcesso, String motivo) {
    String protocolo = "MOCK" + String.format("%015d", sequencia.getAndIncrement());
    log.info("MOCK SEFAZ: cancelamento simulado chave={} protocolo={}", chaveAcesso, protocolo);
    return new CancelamentoSefazResult(protocolo, OffsetDateTime.now());
  }
}
