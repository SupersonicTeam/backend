package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.EtiquetaCarteira;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Catálogo de etiquetas do escritório (issue #177). */
public interface EtiquetaCarteiraRepository extends JpaRepository<EtiquetaCarteira, UUID> {

  /**
   * Catálogo do escritório em ordem alfabética.
   *
   * @param escritorioId escritório
   * @return etiquetas
   */
  List<EtiquetaCarteira> findByEscritorioIdOrderByNomeNormalizadoAsc(UUID escritorioId);

  /**
   * Etiquetas do escritório pelos nomes normalizados.
   *
   * @param escritorioId escritório
   * @param nomes      nomes normalizados
   * @return as que existem
   */
  List<EtiquetaCarteira> findByEscritorioIdAndNomeNormalizadoIn(
      UUID escritorioId, Collection<String> nomes);

  /**
   * Etiqueta do escritório por id (anti-IDOR: a de outro escritório não existe).
   *
   * @param id         etiqueta
   * @param escritorioId escritório
   * @return a etiqueta, se for dele
   */
  Optional<EtiquetaCarteira> findByIdAndEscritorioId(UUID id, UUID escritorioId);

  /**
   * Tira a etiqueta de todos os vínculos (antes de apagá-la do catálogo).
   *
   * @param etiquetaId etiqueta
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query(value = "DELETE FROM carteira_vinculo_etiqueta WHERE etiqueta_id = :etiquetaId",
      nativeQuery = true)
  int desaplicar(@Param("etiquetaId") UUID etiquetaId);

  /**
   * Apaga o catálogo do escritório (exclusão LGPD), já sem vínculos que o usem.
   *
   * @param escritorioId escritório
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("DELETE FROM EtiquetaCarteira e WHERE e.escritorioId = :escritorioId")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);
}
