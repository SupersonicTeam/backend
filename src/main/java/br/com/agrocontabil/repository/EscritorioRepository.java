package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Escritorio;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Escritórios de contabilidade (issue #228). */
public interface EscritorioRepository extends JpaRepository<Escritorio, UUID> {

  /**
   * Escritório sob lock pessimista: serializa os convites dele para o teto diário (#177) não ser
   * furado por requisições simultâneas, e as mudanças de equipe.
   *
   * @param id escritório
   * @return o escritório, se existir
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT e FROM Escritorio e WHERE e.id = :id")
  Optional<Escritorio> findByIdForUpdate(@Param("id") UUID id);
}
