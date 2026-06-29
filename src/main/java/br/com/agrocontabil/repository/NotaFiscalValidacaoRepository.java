package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NotaFiscalValidacao;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA para {@link NotaFiscalValidacao} (issue #22).
 *
 * <p>O controle de propriedade (anti-IDOR) é feito na camada de serviço, que só consulta o
 * histórico após validar que a nota pertence ao produtor autenticado. Por isso as consultas
 * aqui filtram apenas por {@code notaFiscalId}.
 */
public interface NotaFiscalValidacaoRepository extends JpaRepository<NotaFiscalValidacao, UUID> {

  /**
   * Retorna o histórico de inconsistências de uma nota, da tentativa mais recente para a
   * mais antiga.
   *
   * @param notaFiscalId identificador da nota fiscal
   * @return inconsistências ordenadas por {@code validadoEm} decrescente
   */
  List<NotaFiscalValidacao> findByNotaFiscalIdOrderByValidadoEmDesc(UUID notaFiscalId);
}
