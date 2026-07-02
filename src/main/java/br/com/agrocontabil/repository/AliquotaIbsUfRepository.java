package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.AliquotaIbsUf;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório somente leitura da tabela de referência de alíquota de IBS por UF
 * (Sprint 4, issue Agrocontabil#31, RN002).
 *
 * <p>Consultado pelo {@code TributacaoService} para resolver as parcelas estadual/municipal da
 * alíquota de IBS aplicável à operação. Como a tabela é pequena e estável, o serviço mantém um
 * cache em memória (como os validadores da issue #20), evitando uma consulta por item.
 */
public interface AliquotaIbsUfRepository extends JpaRepository<AliquotaIbsUf, String> {

  /**
   * Busca a alíquota de IBS de referência da UF informada.
   *
   * @param uf sigla da UF (2 letras, maiúsculas)
   * @return a alíquota da UF, ou vazio se a UF não constar na tabela de referência
   */
  Optional<AliquotaIbsUf> findByUf(String uf);
}
