package br.com.agrocontabil.service.auditoria;

import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Lê o estado atual de um recurso para o campo "antes" da auditoria (issue #149).
 *
 * <p>Só o status, por consulta de uma coluna: nada da nota (destinatário, valores) chega à
 * auditoria. Sem filtro de dono de propósito — o {@link AuditoriaAspect} só grava o estado lido
 * quando a operação deu certo, e a operação já faz o anti-IDOR; na falha (inclusive o 404 de nota
 * alheia) o estado é descartado.
 */
@Component
class EstadoRecursoLeitor {

  private final NotaFiscalRepository notaRepository;
  private final GuiaFiscalRepository guiaRepository;
  private final ApuracaoMensalRepository apuracaoRepository;

  EstadoRecursoLeitor(NotaFiscalRepository notaRepository, GuiaFiscalRepository guiaRepository,
      ApuracaoMensalRepository apuracaoRepository) {
    this.notaRepository = notaRepository;
    this.guiaRepository = guiaRepository;
    this.apuracaoRepository = apuracaoRepository;
  }

  /**
   * Estado atual do recurso.
   *
   * @param recurso tipo do recurso
   * @param id      id do recurso
   * @return o status, se o recurso tiver um e existir
   */
  Optional<String> estado(RecursoAuditado recurso, UUID id) {
    Optional<?> status = switch (recurso) {
      case NOTA_FISCAL -> notaRepository.findStatusById(id);
      case GUIA_FISCAL -> guiaRepository.findStatusById(id);
      case APURACAO -> apuracaoRepository.findStatusById(id);
      case CONTA -> Optional.empty();
    };
    return status.map(Object::toString);
  }
}
