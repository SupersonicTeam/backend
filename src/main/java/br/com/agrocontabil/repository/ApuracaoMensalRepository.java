package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.StatusApuracao;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório Spring Data JPA para {@link ApuracaoMensal} (issue Agrocontabil#41 — RN004).
 *
 * <p>Fornece a busca por produtor + período (recorte anti-IDOR) usada tanto na consolidação sob
 * demanda quanto no fechamento agendado.
 */
public interface ApuracaoMensalRepository extends JpaRepository<ApuracaoMensal, UUID> {

  /**
   * Busca a apuração de um produtor em um período específico.
   *
   * <p><strong>Recorte de segurança (anti-IDOR):</strong> o filtro por {@code produtorId} é parte
   * da consulta — um produtor nunca acessa a apuração de outro. O par
   * {@code (produtor_id, periodo)} é único, logo o resultado é no máximo um.
   *
   * @param produtorId identificador do produtor autenticado
   * @param periodo    período no formato {@code YYYY-MM}
   * @return {@link Optional} com a apuração, ou vazio se ainda não consolidada
   */
  Optional<ApuracaoMensal> findByProdutorIdAndPeriodo(UUID produtorId, String periodo);

  /**
   * Apurações de todos os produtores num período e status — as abertas do mês que vai fechar são
   * candidatas a alerta (issue #140). Usa o índice {@code (status, periodo)} da 0016.
   */
  List<ApuracaoMensal> findByPeriodoAndStatus(String periodo, StatusApuracao status);

  /**
   * Busca uma apuração por ID restringindo ao produtor dono (recorte anti-IDOR).
   *
   * <p>Usada na reabertura (issue Agrocontabil#42): retorna vazio — resultando em 404 — quando a
   * apuração pertence a outro produtor, sem revelar sua existência.
   *
   * @param id         identificador da apuração
   * @param produtorId identificador do produtor autenticado
   * @return {@link Optional} com a apuração, ou vazio se inexistente ou de outro produtor
   */
  Optional<ApuracaoMensal> findByIdAndProdutorId(UUID id, UUID produtorId);

  /**
   * Mesma busca de {@link #findByProdutorIdAndPeriodo}, com lock pessimista de escrita na linha
   * ({@code SELECT ... FOR UPDATE}).
   *
   * <p>Usada por todo caminho que <em>grava</em> a apuração (consolidação, fechamento do job e
   * recálculo por evento — issues #100 e #64). Serializa escritas concorrentes na mesma apuração:
   * sem o lock, um recálculo por evento que leu a linha ABERTA antes de o job commitar o
   * fechamento regravaria {@code status = ABERTO} por cima do FECHADO, burlando a guarda da #54.
   * Com o lock, o segundo a chegar espera o commit do primeiro e relê o estado já fechado.
   *
   * @param produtorId identificador do produtor
   * @param periodo    período no formato {@code YYYY-MM}
   * @return a apuração travada até o fim da transação, ou vazio se ainda não consolidada
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      SELECT a FROM ApuracaoMensal a
      WHERE a.produtor.id = :produtorId AND a.periodo = :periodo
      """)
  Optional<ApuracaoMensal> findByProdutorIdAndPeriodoForUpdate(
      @Param("produtorId") UUID produtorId, @Param("periodo") String periodo);

  /**
   * Busca por ID com lock pessimista de escrita, para o refechamento do job (issue #100).
   *
   * @param id identificador da apuração
   * @return a apuração travada até o fim da transação, ou vazio se removida
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM ApuracaoMensal a WHERE a.id = :id")
  Optional<ApuracaoMensal> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Mesma busca de {@link #findByIdAndProdutorId} (recorte anti-IDOR), com lock pessimista de
   * escrita: a reabertura (RN005) não pode se intercalar com o refechamento do job.
   *
   * @param id         identificador da apuração
   * @param produtorId identificador do produtor autenticado
   * @return a apuração travada até o fim da transação, ou vazio se inexistente ou de outro produtor
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM ApuracaoMensal a WHERE a.id = :id AND a.produtor.id = :produtorId")
  Optional<ApuracaoMensal> findByIdAndProdutorIdForUpdate(
      @Param("id") UUID id, @Param("produtorId") UUID produtorId);

  /**
   * IDs das apurações em um dado status cujo período esteja na faixa {@code [inicio, fim)}.
   *
   * <p>Usada pelo refechamento automático de períodos reabertos (issues Agrocontabil#57 e #100 —
   * RN005): o job varre as apurações {@link StatusApuracao#ABERTO} dos meses anteriores dentro da
   * janela de 3 meses e refecha cada uma em transação própria. Devolve só os IDs para que cada
   * apuração seja carregada dentro da transação que a refecha, e não numa transação de lote.
   *
   * <p>Como o período é armazenado no formato {@code YYYY-MM}, a comparação lexicográfica de
   * {@code String} coincide com a ordem cronológica. A faixa é meio-aberta: {@code inicio}
   * inclusivo, {@code fim} exclusivo (tipicamente o mês corrente, já coberto pelo fechamento
   * normal). Coberta pelo índice {@code (status, periodo)} da migration {@code 0016}.
   *
   * @param status status alvo (ex.: {@link StatusApuracao#ABERTO})
   * @param inicio período inicial inclusivo no formato {@code YYYY-MM}
   * @param fim    período final exclusivo no formato {@code YYYY-MM}
   * @return IDs das apurações no status e faixa informados, em ordem de ID (vazia se nenhuma)
   */
  @Query("""
      SELECT a.id FROM ApuracaoMensal a
      WHERE a.status = :status AND a.periodo >= :inicio AND a.periodo < :fim
      ORDER BY a.id
      """)
  List<UUID> findIdsByStatusAndPeriodoEntre(
      @Param("status") StatusApuracao status,
      @Param("inicio") String inicio,
      @Param("fim") String fim);
}
