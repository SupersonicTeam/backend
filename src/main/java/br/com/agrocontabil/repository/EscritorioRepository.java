package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Escritorio;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
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

  /**
   * Slug do escritório, sem carregar a entidade (nulo = sem endereço).
   *
   * @param id escritório
   * @return o slug, se houver
   */
  @Query("SELECT e.slug FROM Escritorio e WHERE e.id = :id")
  Optional<String> findSlugById(@Param("id") UUID id);

  /**
   * O slug está em uso por outro escritório, ou em carência de outro escritório (ou de um
   * escritório apagado)? Uma consulta só, para as duas tabelas serem lidas no mesmo instante:
   * em duas, uma troca concorrente podia passar entre as leituras e liberar o slug em carência.
   *
   * @param slug         slug normalizado
   * @param escritorioId escritório que quer o slug (a carência dele mesmo não bloqueia)
   * @param agora        instante da conferência
   * @return {@code true} se outro escritório tem ou guarda o slug
   */
  @Query(value = "SELECT (EXISTS (SELECT 1 FROM escritorio e WHERE e.slug = :slug"
      + " AND e.id <> :escritorioId)"
      + " OR EXISTS (SELECT 1 FROM escritorio_slug_anterior a WHERE a.slug = :slug"
      + " AND a.liberado_em > :agora"
      + " AND (a.escritorio_id IS NULL OR a.escritorio_id <> :escritorioId)))",
      nativeQuery = true)
  boolean slugIndisponivel(@Param("slug") String slug, @Param("escritorioId") UUID escritorioId,
      @Param("agora") OffsetDateTime agora);
}
