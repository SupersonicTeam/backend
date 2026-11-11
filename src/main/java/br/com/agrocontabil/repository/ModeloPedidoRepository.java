package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ModeloPedido;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Catálogo de modelos de pedido de documento do escritório (issue #517). */
public interface ModeloPedidoRepository extends JpaRepository<ModeloPedido, UUID> {

  /**
   * Modelos do escritório na ordem do título normalizado (sem acento nem caixa).
   *
   * @param escritorioId escritório
   * @return modelos
   */
  List<ModeloPedido> findByEscritorioIdOrderByTituloNormalizadoAsc(UUID escritorioId);

  /**
   * Modelo do escritório por id (anti-IDOR: o de outro escritório não existe).
   *
   * @param id           modelo
   * @param escritorioId escritório
   * @return o modelo, se for dele
   */
  Optional<ModeloPedido> findByIdAndEscritorioId(UUID id, UUID escritorioId);

  /**
   * Modelo do escritório com o título normalizado (título repetido).
   *
   * @param escritorioId      escritório
   * @param tituloNormalizado título sem acento nem caixa
   * @return o modelo que já usa o título
   */
  Optional<ModeloPedido> findByEscritorioIdAndTituloNormalizado(UUID escritorioId,
      String tituloNormalizado);

  /**
   * Quantos modelos o escritório tem (teto do catálogo).
   *
   * @param escritorioId escritório
   * @return total
   */
  long countByEscritorioId(UUID escritorioId);

  /**
   * Apaga o catálogo do escritório que sai (aceite de equipe ou exclusão LGPD). O CASCADE da FK
   * faria o mesmo; explícito, como os outros catálogos do escritório.
   *
   * @param escritorioId escritório
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("DELETE FROM ModeloPedido m WHERE m.escritorioId = :escritorioId")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);
}
