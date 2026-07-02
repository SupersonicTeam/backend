package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * Agrega o crédito tributário de IBS/CBS das notas de <strong>entrada</strong> de um produtor
   * emitidas dentro de um intervalo de tempo (issue Agrocontabil#30 — RN003).
   *
   * <p><strong>Recorte de segurança (anti-IDOR):</strong> o filtro por {@code produtorId} é
   * parte da própria query — um produtor nunca soma créditos de notas de outro produtor.
   *
   * <p>Considera apenas notas {@link br.com.agrocontabil.model.OrigemNotaFiscal#IMPORTADA}
   * (as únicas que geram crédito de entrada) com {@code dataEmissao} em {@code [inicio, fim)}.
   * O {@code SUM} retorna nulo quando não há notas no intervalo — o serviço trata como
   * {@code 0.00}.
   *
   * @param produtorId identificador do produtor autenticado
   * @param inicio     início do intervalo (inclusivo)
   * @param fim        fim do intervalo (exclusivo)
   * @return projeção com os somatórios de crédito e a contagem de notas do período
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CreditoSaldoAgregado(
          SUM(n.valorTotalCreditoIbs),
          SUM(n.valorTotalCreditoCbs),
          COUNT(n))
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      """)
  CreditoSaldoAgregado somarCreditosPorPeriodo(
      @Param("produtorId") UUID produtorId,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);
}
