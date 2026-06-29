package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA para {@link Invoice}.
 *
 * <p>Fornece acesso paginado às notas de um producer específico e busca segura
 * por ID vinculada ao producer, evitando acesso cruzado entre produtores.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

  /**
   * Retorna todas as notas fiscais de um producer de forma paginada.
   *
   * @param producerId identificador do {@link br.com.agrocontabil.model.Producer}
   * @param pageable   parâmetros de paginação e ordenação
   * @return página de notas fiscais do producer
   */
  Page<Invoice> findByProducerId(UUID producerId, Pageable pageable);

  /**
   * Busca uma invoice fiscal pelo seu ID e pelo ID do producer proprietário.
   *
   * <p>Retorna {@link Optional#empty()} se a invoice não existir ou se pertencer
   * a um producer diferente do informado, impedindo acesso cruzado.
   *
   * @param id         identificador da invoice fiscal
   * @param producerId identificador do producer esperado como proprietário
   * @return {@link Optional} com a invoice fiscal, ou vazio se não encontrada
   */
  Optional<Invoice> findByIdAndProducerId(UUID id, UUID producerId);

  /**
   * Indica se já existe invoice com a key de acesso informada (dedup de importação).
   *
   * @param accessKey key de 44 dígitos
   * @return {@code true} se já houver uma invoice com essa key
   */
  boolean existsByAccessKey(String accessKey);

  /**
   * Retorna todas as notas em um determinado status, sem filtro de producer.
   *
   * <p>Uso restrito a jobs de sistema (ex.: sincronização agendada de status com a SEFAZ —
   * issue #16), que operam sobre todas as notas pendentes independentemente do producer.
   *
   * @param status status do ciclo de vida a filtrar
   * @return lista de notas no status informado
   */
  List<Invoice> findByStatus(InvoiceStatus status);
}
