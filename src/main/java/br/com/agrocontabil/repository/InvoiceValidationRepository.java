package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.InvoiceValidation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA para {@link InvoiceValidation} (issue #22).
 *
 * <p>O controle de propriedade (anti-IDOR) é feito na camada de serviço, que só consulta o
 * histórico após validate que a invoice pertence ao producer autenticado. Por isso as consultas
 * aqui filtram apenas por {@code notaFiscalId}.
 */
public interface InvoiceValidationRepository extends JpaRepository<InvoiceValidation, UUID> {

  /**
   * Retorna o histórico de inconsistências de uma invoice, da tentativa mais recente para a
   * mais antiga.
   *
   * @param notaFiscalId identificador da invoice fiscal
   * @return inconsistências ordenadas por {@code validatedAt} decrescente
   */
  List<InvoiceValidation> findByInvoiceIdOrderByValidatedAtDesc(UUID notaFiscalId);
}
