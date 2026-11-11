package br.com.agrocontabil.assistente.porta;

import br.com.agrocontabil.config.CaminhoCota;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Cota semanal de créditos da Ceres (issue #166), vista pelo módulo do assistente (fronteira da
 * issue #141). A regra — cálculo de créditos, plano, semana, atomicidade — vive no service; a
 * Ceres só reserva antes da pergunta, acerta ou estorna no fim e consulta para a barra de uso.
 *
 * <p>Titular nesta etapa: o produtor da pergunta. Quando o contador puder perguntar, o adaptador
 * passa a reservar na cota do escritório — sem mudar quem chama esta porta.
 */
public interface CotaUsoPorta {

  /**
   * Reserva o teto de uma pergunta, antes de qualquer chamada ao modelo.
   *
   * @param produtorId produtor autenticado
   * @param modelo     identificação do modelo em uso (fator de custo)
   * @return a reserva, a ser acertada ou estornada exatamente uma vez
   * @throws br.com.agrocontabil.exception.LimiteUsoAssistenteException sem saldo (429)
   */
  ReservaCota reservar(UUID produtorId, String modelo);

  /**
   * Reserva o teto do caminho da pergunta (#499). O padrão reserva o da pergunta comum.
   *
   * @param produtorId produtor autenticado
   * @param modelo     identificação do modelo em uso
   * @param caminho    caminho da pergunta (reconferência, reescrita...)
   * @return a reserva
   */
  default ReservaCota reservar(UUID produtorId, String modelo, CaminhoCota caminho) {
    return reservar(produtorId, modelo);
  }

  /**
   * Acerta pelo consumo real (tokens informados pelo provedor ou estimados).
   *
   * @return créditos consumidos
   */
  long acertar(ReservaCota reserva, long tokensEntrada, long tokensSaida, String modelo);

  /** Devolve a reserva inteira (falha, timeout, interrupção). */
  void estornar(ReservaCota reserva);

  /** Uso da semana do produtor, em créditos. */
  UsoCota consultar(UUID produtorId);

  /**
   * Reserva feita para uma pergunta.
   *
   * @param titularId titular da cota
   * @param semana    segunda-feira da semana da reserva
   * @param creditos  teto reservado
   * @param ativa     {@code false} com a cota desligada ou no atalho fixo da tela (#343, sem
   *                  modelo); inativa, só {@code titularId} vale (semana nula no atalho): acerto
   *                  e estorno não mexem na cota
   */
  record ReservaCota(UUID titularId, LocalDate semana, long creditos, boolean ativa) {}

  /**
   * Uso da semana, só em créditos.
   *
   * @param plano          nome do plano
   * @param habilitada     cota ligada
   * @param creditosSemana limite da semana
   * @param consumidos     gastos
   * @param restantes      ainda disponíveis
   * @param renovaEm       próxima segunda 00:00 (Brasília)
   */
  record UsoCota(String plano, boolean habilitada, long creditosSemana, long consumidos,
      long restantes, OffsetDateTime renovaEm) {}
}
