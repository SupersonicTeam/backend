package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ClassificacaoTributariaAnexoNcm;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório somente leitura dos NCMs dos itens de anexo da LC 214/2025 (issue #272). Consultado
 * pelo {@code ClassificacaoTributariaService}.
 */
public interface ClassificacaoTributariaAnexoNcmRepository
    extends JpaRepository<ClassificacaoTributariaAnexoNcm, ClassificacaoTributariaAnexoNcm.Chave> {

  /**
   * Linhas (permitidas e vedadas) do NCM vigentes na data, com o item do anexo carregado.
   *
   * @param ncm código NCM (8 dígitos)
   * @param data data de referência (emissão da nota em {@code America/Sao_Paulo})
   * @return linhas vigentes; vazia se o NCM não está em nenhum anexo importado
   */
  @Query("""
      SELECT n FROM ClassificacaoTributariaAnexoNcm n JOIN FETCH n.item
      WHERE n.ncm = :ncm
        AND n.dataInicio <= :data
        AND (n.dataFim IS NULL OR n.dataFim >= :data)
      """)
  List<ClassificacaoTributariaAnexoNcm> findVigentes(
      @Param("ncm") String ncm, @Param("data") LocalDate data);
}
