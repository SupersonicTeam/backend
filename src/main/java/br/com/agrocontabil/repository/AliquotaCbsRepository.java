package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.AliquotaCbs;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório somente leitura da tabela de referência de alíquota de CBS por vigência
 * (Onda 2, issue #85).
 *
 * <p>Consultado exclusivamente pelo {@code AliquotaIbsCbsResolver} (issue #86).
 */
public interface AliquotaCbsRepository extends JpaRepository<AliquotaCbs, LocalDate> {

  /**
   * Busca as vigências que cobrem a data informada, da mais recente para a mais antiga (mesma
   * semântica de {@link AliquotaIbsUfRepository#findVigente}: o chamador usa a primeira).
   *
   * @param data data de referência (data de emissão da nota em {@code America/Sao_Paulo})
   * @return vigências aplicáveis ordenadas por {@code dataInicio} decrescente; vazia se nenhuma
   */
  @Query("""
      SELECT c FROM AliquotaCbs c
      WHERE c.dataInicio <= :data
        AND (c.dataFim IS NULL OR c.dataFim >= :data)
      ORDER BY c.dataInicio DESC
      """)
  List<AliquotaCbs> findVigente(@Param("data") LocalDate data);
}
