package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.RegistroAuditoria;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registros de auditoria (issue #149). Só insere, lista por produtor e apaga pela retenção.
 *
 * <p>Estende {@link Repository}, não {@code JpaRepository}, de propósito: sem {@code delete},
 * {@code deleteAll} nem {@code saveAll} herdados, nenhum código da aplicação apaga ou reescreve um
 * registro por engano — o único caminho de saída é a retenção.
 */
public interface RegistroAuditoriaRepository extends Repository<RegistroAuditoria, UUID> {

  /**
   * Grava um registro novo.
   *
   * @param registro o registro
   * @return o registro gravado
   */
  RegistroAuditoria save(RegistroAuditoria registro);

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
  @Transactional
  @Modifying
  @Query("DELETE FROM RegistroAuditoria r WHERE r.ocorridoEm < :corte")
  int apagarAnterioresA(@Param("corte") OffsetDateTime corte);
}
