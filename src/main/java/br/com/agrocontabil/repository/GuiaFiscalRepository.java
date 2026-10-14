package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório Spring Data JPA para {@link GuiaFiscal}.
 *
 * <p>Fornece a listagem por produtor (usada tanto pelo painel do Contador quanto pelo
 * calendário fiscal), a busca segura por ID vinculada ao produtor (anti-IDOR, usada no
 * pagamento) e a consulta do {@code GuiaFiscalStatusSyncJob}.
 */
public interface GuiaFiscalRepository extends JpaRepository<GuiaFiscal, UUID> {

  /**
   * Página das guias fiscais de um produtor, para a listagem do Contador (issue #103).
   *
   * <p>Substitui a antiga listagem sem limite, que carregava todas as guias do produtor e crescia
   * indefinidamente com o tempo de uso. A ordenação vem no {@link Pageable}; a consulta e a
   * contagem filtram por {@code produtor_id} e usam o índice
   * {@code idx_guia_fiscal_produtor_vencimento} (issue #102).
   *
   * @param produtorId identificador do {@link br.com.agrocontabil.model.Produtor}
   * @param pagina     página, tamanho e ordenação
   * @return página de guias do produtor, com o total de elementos
   */
  Page<GuiaFiscal> findByProdutorId(UUID produtorId, Pageable pagina);

  /**
   * Guias de um produtor com vencimento na janela {@code [de, ate]} (ambos inclusivos), para o
   * calendário fiscal (issue #103). Mais recentes primeiro, com desempate por ID para a ordem ser
   * estável entre chamadas.
   *
   * @param produtorId identificador do {@link br.com.agrocontabil.model.Produtor}
   * @param de         primeiro dia da janela (inclusivo)
   * @param ate        último dia da janela (inclusivo)
   * @return guias com vencimento na janela
   */
  @Query("""
      SELECT g FROM GuiaFiscal g
      WHERE g.produtor.id = :produtorId AND g.dataVencimento BETWEEN :de AND :ate
      ORDER BY g.dataVencimento DESC, g.id DESC
      """)
  List<GuiaFiscal> findVencimentosNaJanela(
      @Param("produtorId") UUID produtorId,
      @Param("de") LocalDate de,
      @Param("ate") LocalDate ate);

  /**
   * Busca uma guia fiscal pelo seu ID e pelo ID do produtor proprietário.
   *
   * <p>Retorna {@link Optional#empty()} se a guia não existir ou pertencer a um produtor
   * diferente do informado, impedindo acesso cruzado (anti-IDOR) — usado por
   * {@code GuiaFiscalService#marcarComoPaga}, que opera sobre o produtor autenticado.
   *
   * @param id         identificador da guia fiscal
   * @param produtorId identificador do produtor esperado como destinatário
   * @return {@link Optional} com a guia fiscal, ou vazio se não encontrada
   */
  Optional<GuiaFiscal> findByIdAndProdutorId(UUID id, UUID produtorId);

  /**
   * Retorna as guias {@link StatusGuiaFiscal#PENDENTE} cujo vencimento já passou, para o job
   * agendado de sincronização de status transicioná-las para {@link StatusGuiaFiscal#VENCIDA}.
   *
   * @param status  status a filtrar (sempre {@code PENDENTE} na prática)
   * @param hoje    data de referência (vencimento estritamente anterior é considerado vencido)
   * @return guias pendentes com vencimento anterior a {@code hoje}
   */
  List<GuiaFiscal> findByStatusAndDataVencimentoBefore(StatusGuiaFiscal status, LocalDate hoje);

  /**
   * Guias de todos os produtores com o status e vencimento em {@code [de, ate]} — candidatas a
   * alerta (issue #140). Usa o índice {@code (status, data_vencimento)} da issue #102.
   */
  List<GuiaFiscal> findByStatusAndDataVencimentoBetween(
      StatusGuiaFiscal status, LocalDate de, LocalDate ate);

  /**
   * Guias ainda não pagas do produtor com vencimento na janela {@code [de, ate]}, da mais próxima
   * para a mais distante (issue #142 — Ceres, função {@code proximosVencimentos}).
   *
   * @param pagina limite (top-N): a Ceres nunca lista tudo
   */
  @Query(value = """
      SELECT new br.com.agrocontabil.repository.VencimentoGuiaResumo(
          g.tipoGuia, g.dataVencimento, g.valor, g.status)
      FROM GuiaFiscal g
      WHERE g.produtor.id = :produtorId
        AND g.status <> br.com.agrocontabil.model.StatusGuiaFiscal.PAGA
        AND g.dataVencimento BETWEEN :de AND :ate
      ORDER BY g.dataVencimento ASC, g.id ASC
      """,
      countQuery = """
      SELECT COUNT(g) FROM GuiaFiscal g
      WHERE g.produtor.id = :produtorId
        AND g.status <> br.com.agrocontabil.model.StatusGuiaFiscal.PAGA
        AND g.dataVencimento BETWEEN :de AND :ate
      """)
  Page<VencimentoGuiaResumo> listarNaoPagasNaJanela(
      @Param("produtorId") UUID produtorId,
      @Param("de") LocalDate de,
      @Param("ate") LocalDate ate,
      Pageable pagina);

  /**
   * Quantidade e soma do valor de <em>todas</em> as guias não pagas da janela — mesmo recorte de
   * {@link #listarNaoPagasNaJanela}, sem o limite da página (issue #169: a Ceres não soma, o
   * backend soma).
   *
   * @return quantidade e soma (soma {@code null} quando não há guia na janela)
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.TotalAgregado(COUNT(g), SUM(g.valor))
      FROM GuiaFiscal g
      WHERE g.produtor.id = :produtorId
        AND g.status <> br.com.agrocontabil.model.StatusGuiaFiscal.PAGA
        AND g.dataVencimento BETWEEN :de AND :ate
      """)
  TotalAgregado totalizarNaoPagasNaJanela(
      @Param("produtorId") UUID produtorId,
      @Param("de") LocalDate de,
      @Param("ate") LocalDate ate);
}
