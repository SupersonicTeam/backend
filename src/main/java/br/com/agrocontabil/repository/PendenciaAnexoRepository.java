package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.PendenciaAnexo;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Anexos das pendências (issue #180). As listagens usam {@link AnexoResumo} — só metadados, sem
 * o conteúdo do arquivo.
 */
public interface PendenciaAnexoRepository extends JpaRepository<PendenciaAnexo, UUID> {

  /**
   * Metadados dos anexos da pendência, do mais recente ao mais antigo.
   *
   * @param pendenciaId pendência
   * @return anexos sem o conteúdo
   */
  @Query("SELECT new br.com.agrocontabil.repository.AnexoResumo("
      + "a.id, a.nomeArquivo, a.mime, a.tamanho, a.sha256, a.enviadoEm) FROM PendenciaAnexo a"
      + " WHERE a.pendenciaId = :pendenciaId ORDER BY a.enviadoEm DESC, a.id")
  List<AnexoResumo> findResumos(@Param("pendenciaId") UUID pendenciaId);

  /**
   * Quantos anexos a pendência tem (teto de 10).
   *
   * @param pendenciaId pendência
   * @return quantidade
   */
  long countByPendenciaId(UUID pendenciaId);

  /**
   * Anexo da pendência (download).
   *
   * @param id          anexo
   * @param pendenciaId pendência
   * @return o anexo, com o conteúdo
   */
  Optional<PendenciaAnexo> findByIdAndPendenciaId(UUID id, UUID pendenciaId);

  /**
   * Quantidade de anexos por pendência, numa consulta (listas, sem N+1).
   *
   * @param pendencias pendências
   * @return contagens (pendência sem anexo não aparece)
   */
  @Query("SELECT new br.com.agrocontabil.repository.ContagemPorPendencia(a.pendenciaId, COUNT(a))"
      + " FROM PendenciaAnexo a WHERE a.pendenciaId IN :pendencias GROUP BY a.pendenciaId")
  List<ContagemPorPendencia> contarPorPendencia(
      @Param("pendencias") Collection<UUID> pendencias);

  /**
   * Apaga os anexos enviados antes do corte (retenção de 5 anos).
   *
   * @param corte instante de corte
   * @return linhas removidas
   */
  @Modifying
  @Query("DELETE FROM PendenciaAnexo a WHERE a.enviadoEm < :corte")
  int apagarAnterioresA(@Param("corte") OffsetDateTime corte);

  /**
   * Apaga os anexos das pendências do produtor (exclusão LGPD).
   *
   * @param produtorId produtor
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM PendenciaAnexo a WHERE a.pendenciaId IN"
      + " (SELECT p.id FROM Pendencia p WHERE p.produtorId = :produtorId)")
  int apagarDoProdutor(@Param("produtorId") UUID produtorId);

  /**
   * Apaga os anexos das pendências do escritório (o escritório sai).
   *
   * @param escritorioId escritório
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM PendenciaAnexo a WHERE a.pendenciaId IN"
      + " (SELECT p.id FROM Pendencia p WHERE p.escritorioId = :escritorioId)")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);
}
