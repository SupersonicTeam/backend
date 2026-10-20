package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.RegistroAuditoria;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Registros de auditoria (issue #149). Só insere, lista por produtor e apaga pela retenção: nenhum
 * método altera um registro existente.
 */
public interface RegistroAuditoriaRepository extends JpaRepository<RegistroAuditoria, UUID> {

  /**
   * Registros que tocaram os dados de um produtor, na ordem do {@code Pageable}.
   *
   * @param produtorId produtor
   * @param pageable   página e ordem
   * @return a página
   */
  Page<RegistroAuditoria> findByProdutorId(UUID produtorId, Pageable pageable);

  /**
   * Apaga os registros anteriores ao corte da retenção.
   *
   * @param corte instante limite (exclusivo)
   * @return registros apagados
   */
  @Modifying
  @Query("DELETE FROM RegistroAuditoria r WHERE r.ocorridoEm < :corte")
  int apagarAnterioresA(@Param("corte") OffsetDateTime corte);
}
