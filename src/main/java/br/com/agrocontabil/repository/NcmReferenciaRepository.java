package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NcmReferencia;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório somente leitura da tabela de referência de NCM (issue #20, RN008).
 */
public interface NcmReferenciaRepository extends JpaRepository<NcmReferencia, String> {

  /**
   * Indica se existe um NCM de referência com o código informado.
   *
   * @param codigo código NCM (8 dígitos)
   * @return {@code true} se o código existir na tabela de referência
   */
  boolean existsByCodigo(String codigo);

  /**
   * Retorna todos os códigos NCM de referência para carga em cache pelo validador.
   *
   * @return lista de códigos NCM
   */
  @Query("select n.codigo from NcmReferencia n")
  List<String> findAllCodigos();

  /**
   * Retorna o percentual de redução padrão da base de IBS/CBS para o NCM informado
   * (Sprint 4, issue Agrocontabil#30, RF015).
   *
   * <p>Usado pelo {@code TributacaoService} para aplicar a redução por NCM como default quando
   * o item não informa {@code percentualReducao}. Retorna vazio quando o NCM não consta na
   * tabela de referência (nesse caso o serviço assume redução zero — postura conservadora).
   *
   * @param codigo código NCM (8 dígitos) do item
   * @return percentual de redução padrão (0–100), ou vazio se o NCM não for de referência
   */
  @Query("select n.percentualReducaoPadrao from NcmReferencia n where n.codigo = :codigo")
  Optional<BigDecimal> findPercentualReducaoPadraoByCodigo(@Param("codigo") String codigo);
}
