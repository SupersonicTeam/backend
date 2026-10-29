package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ClassificacaoTributaria;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório somente leitura da tabela oficial de cClassTrib (issue #272). Consultado pelo
 * {@code ClassificacaoTributariaService}.
 */
public interface ClassificacaoTributariaRepository
    extends JpaRepository<ClassificacaoTributaria, ClassificacaoTributaria.Chave> {

  /**
   * Versões do código vigentes na data, da mais recente para a mais antiga (o chamador usa a
   * primeira; mesma semântica de {@code AliquotaIbsUfRepository.findVigente}).
   *
   * @param codigo cClassTrib de 6 dígitos
   * @param data data de referência
   * @return versões vigentes; vazia se o código não existe ou não vale na data
   */
  @Query("""
      SELECT c FROM ClassificacaoTributaria c
      WHERE c.codigo = :codigo
        AND c.dataInicio <= :data
        AND (c.dataFim IS NULL OR c.dataFim >= :data)
      ORDER BY c.dataInicio DESC
      """)
  List<ClassificacaoTributaria> findVigente(
      @Param("codigo") String codigo, @Param("data") LocalDate data);
}
