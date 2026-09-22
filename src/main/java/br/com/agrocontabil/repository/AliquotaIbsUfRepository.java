package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.AliquotaIbsUf;
import br.com.agrocontabil.model.AliquotaIbsUfId;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório somente leitura da tabela de referência de alíquota de IBS por UF e vigência
 * (Sprint 4, issue Agrocontabil#31, RN002; vigência temporal — Onda 2, issue #85).
 *
 * <p>Consultado exclusivamente pelo {@code AliquotaIbsCbsResolver} (issue #86) — nenhum serviço de
 * cálculo acessa esta tabela diretamente.
 */
public interface AliquotaIbsUfRepository extends JpaRepository<AliquotaIbsUf, AliquotaIbsUfId> {

  /**
   * Busca as vigências da UF que cobrem a data informada, da mais recente para a mais antiga.
   *
   * <p>Devolve lista (e não {@code Optional}) de propósito: a PK {@code (uf, data_inicio)} impede
   * dois inícios iguais, mas não impede sobreposição acidental de intervalos (H2 não suporta
   * exclusion constraint). Com {@code ORDER BY data_inicio DESC}, o chamador pega a primeira —
   * a vigência de início mais recente vence, de forma determinística.
   *
   * @param uf sigla da UF (2 letras, maiúsculas)
   * @param data data de referência (data de emissão da nota em {@code America/Sao_Paulo})
   * @return vigências aplicáveis ordenadas por {@code dataInicio} decrescente; vazia se nenhuma
   */
  @Query("""
      SELECT a FROM AliquotaIbsUf a
      WHERE a.uf = :uf
        AND a.dataInicio <= :data
        AND (a.dataFim IS NULL OR a.dataFim >= :data)
      ORDER BY a.dataInicio DESC
      """)
  List<AliquotaIbsUf> findVigente(@Param("uf") String uf, @Param("data") LocalDate data);
}
