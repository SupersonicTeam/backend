package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA para {@link NotaFiscal}.
 *
 * <p>Fornece acesso paginado às notas de um produtor específico e busca segura
 * por ID vinculada ao produtor, evitando acesso cruzado entre produtores.
 */
public interface NotaFiscalRepository extends JpaRepository<NotaFiscal, UUID> {

  /**
   * Retorna todas as notas fiscais de um produtor de forma paginada.
   *
   * @param produtorId identificador do {@link br.com.agrocontabil.model.Produtor}
   * @param pageable   parâmetros de paginação e ordenação
   * @return página de notas fiscais do produtor
   */
  Page<NotaFiscal> findByProdutorId(UUID produtorId, Pageable pageable);

  /**
   * Busca uma nota fiscal pelo seu ID e pelo ID do produtor proprietário.
   *
   * <p>Retorna {@link Optional#empty()} se a nota não existir ou se pertencer
   * a um produtor diferente do informado, impedindo acesso cruzado.
   *
   * @param id         identificador da nota fiscal
   * @param produtorId identificador do produtor esperado como proprietário
   * @return {@link Optional} com a nota fiscal, ou vazio se não encontrada
   */
  Optional<NotaFiscal> findByIdAndProdutorId(UUID id, UUID produtorId);

  /**
   * Retorna todas as notas em um determinado status, sem filtro de produtor.
   *
   * <p>Uso restrito a jobs de sistema (ex.: sincronização agendada de status com a SEFAZ —
   * issue #16), que operam sobre todas as notas pendentes independentemente do produtor.
   *
   * @param status status do ciclo de vida a filtrar
   * @return lista de notas no status informado
   */
  List<NotaFiscal> findByStatus(StatusNotaFiscal status);

  /**
   * Indica se ja existe uma nota com a chave de acesso informada (issue #19).
   *
   * <p>Usado na deduplicacao de notas importadas: a {@code chave_acesso} e globalmente unica
   * (44 digitos), entao a existencia previa sinaliza um XML ja importado.
   *
   * @param chaveAcesso chave de acesso de 44 digitos
   * @return {@code true} se ja houver uma nota com esta chave
   */
  boolean existsByChaveAcesso(String chaveAcesso);
}
