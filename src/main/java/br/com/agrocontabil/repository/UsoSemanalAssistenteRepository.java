package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.TipoTitularCota;
import br.com.agrocontabil.model.UsoSemanalAssistente;
import br.com.agrocontabil.model.UsoSemanalAssistenteId;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Uso semanal da Ceres (issue #166). As escritas são {@code UPDATE}s condicionais numa linha só:
 * o banco serializa as perguntas simultâneas do mesmo titular na trava da linha, e a condição é
 * reavaliada com o valor já atualizado — duas perguntas não reservam o mesmo crédito.
 */
public interface UsoSemanalAssistenteRepository
    extends JpaRepository<UsoSemanalAssistente, UsoSemanalAssistenteId> {

  /**
   * Reserva {@code teto} créditos se couber no limite da semana.
   *
   * @return 1 se reservou; 0 se não há saldo para o teto
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE UsoSemanalAssistente u
      SET u.creditosReservados = u.creditosReservados + :teto, u.atualizadoEm = :agora
      WHERE u.id.titularTipo = :tipo AND u.id.titularId = :titularId
        AND u.id.semanaInicio = :semana
        AND u.creditosReservados + u.creditosConsumidos + :teto <= :limite
      """)
  int reservar(@Param("tipo") TipoTitularCota tipo,
      @Param("titularId") UUID titularId, @Param("semana") LocalDate semana,
      @Param("teto") long teto,
      @Param("limite") long limite, @Param("agora") OffsetDateTime agora);

  /**
   * Acerto de uma pergunta: devolve a reserva e debita o consumo real.
   *
   * @return 1 se acertou; 0 se a reserva não estava lá (acerto ou estorno repetido)
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE UsoSemanalAssistente u
      SET u.creditosReservados = u.creditosReservados - :teto,
          u.creditosConsumidos = u.creditosConsumidos + :consumidos,
          u.tokensEntrada = u.tokensEntrada + :tokensEntrada,
          u.tokensSaida = u.tokensSaida + :tokensSaida,
          u.perguntas = u.perguntas + 1,
          u.atualizadoEm = :agora
      WHERE u.id.titularTipo = :tipo AND u.id.titularId = :titularId
        AND u.id.semanaInicio = :semana AND u.creditosReservados >= :teto
      """)
  int acertar(@Param("tipo") TipoTitularCota tipo,
      @Param("titularId") UUID titularId, @Param("semana") LocalDate semana,
      @Param("teto") long teto,
      @Param("consumidos") long consumidos, @Param("tokensEntrada") long tokensEntrada,
      @Param("tokensSaida") long tokensSaida, @Param("agora") OffsetDateTime agora);

  /**
   * Zera as reservas de uma linha parada desde {@code paradaDesde}: perguntas que reservaram e
   * nunca acertaram nem estornaram (processo morto). Toda escrita da linha atualiza
   * {@code atualizadoEm}, e nenhuma pergunta dura mais que o timeout dela — quem chama passa o
   * dobro, folga que não alcança uma pergunta viva.
   *
   * @return 1 se havia reserva presa e ela foi liberada
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE UsoSemanalAssistente u
      SET u.creditosReservados = 0, u.atualizadoEm = :agora
      WHERE u.id.titularTipo = :tipo AND u.id.titularId = :titularId
        AND u.id.semanaInicio = :semana
        AND u.creditosReservados > 0 AND u.atualizadoEm < :paradaDesde
      """)
  int liberarReservasParadas(@Param("tipo") TipoTitularCota tipo,
      @Param("titularId") UUID titularId, @Param("semana") LocalDate semana,
      @Param("paradaDesde") OffsetDateTime paradaDesde, @Param("agora") OffsetDateTime agora);

  /**
   * Estorno integral da reserva (falha, timeout, interrupção).
   *
   * @return 1 se estornou; 0 se a reserva não estava lá
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE UsoSemanalAssistente u
      SET u.creditosReservados = u.creditosReservados - :teto, u.atualizadoEm = :agora
      WHERE u.id.titularTipo = :tipo AND u.id.titularId = :titularId
        AND u.id.semanaInicio = :semana AND u.creditosReservados >= :teto
      """)
  int estornar(@Param("tipo") TipoTitularCota tipo,
      @Param("titularId") UUID titularId, @Param("semana") LocalDate semana,
      @Param("teto") long teto,
      @Param("agora") OffsetDateTime agora);
}
