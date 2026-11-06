package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ClassificacaoTributariaAnexoNcm;
import java.time.LocalDate;
import java.util.Collection;
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

  /**
   * Linhas vigentes de vários NCMs de uma vez (issue #283), com o item carregado.
   *
   * @param ncms códigos NCM (8 dígitos)
   * @param data data de referência
   * @return linhas vigentes dos NCMs
   */
  @Query("""
      SELECT n FROM ClassificacaoTributariaAnexoNcm n JOIN FETCH n.item
      WHERE n.ncm IN :ncms
        AND n.dataInicio <= :data
        AND (n.dataFim IS NULL OR n.dataFim >= :data)
      """)
  List<ClassificacaoTributariaAnexoNcm> findVigentesEm(
      @Param("ncms") Collection<String> ncms, @Param("data") LocalDate data);

  /**
   * NCMs permitidos de um item de anexo na data, em ordem (issue #283).
   *
   * @param itemId item do anexo
   * @param data data de referência
   * @return códigos NCM distintos
   */
  @Query("""
      SELECT DISTINCT n.ncm FROM ClassificacaoTributariaAnexoNcm n
      WHERE n.item.id = :itemId
        AND n.permissao = br.com.agrocontabil.model.ClassificacaoTributariaAnexoNcm.Permissao.PERMITIDO
        AND n.dataInicio <= :data
        AND (n.dataFim IS NULL OR n.dataFim >= :data)
      ORDER BY n.ncm
      """)
  List<String> findNcmsPermitidosDoItem(
      @Param("itemId") Integer itemId, @Param("data") LocalDate data);

  /**
   * Se o {@code cClassTrib} tem algum item de anexo com NCM vigente na data (issue #375): só os
   * códigos de anexo (I, VII, IX e XV) dependem do NCM; os demais (integral, in natura...) não.
   *
   * @param cClassTrib código de 6 dígitos
   * @param data data de referência
   * @return {@code true} se o código é de anexo
   */
  @Query("""
      SELECT COUNT(n) > 0 FROM ClassificacaoTributariaAnexoNcm n
      WHERE n.item.cClassTrib = :cClassTrib
        AND n.dataInicio <= :data
        AND (n.dataFim IS NULL OR n.dataFim >= :data)
      """)
  boolean existeAnexoDoCodigo(
      @Param("cClassTrib") String cClassTrib, @Param("data") LocalDate data);
}
