package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.SessaoRefresh;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Sessões de refresh (issue #207). Só o hash do token é consultado, nunca o token. */
public interface SessaoRefreshRepository extends JpaRepository<SessaoRefresh, UUID> {

  /**
   * Sessão pelo hash do token, sob lock pessimista.
   *
   * <p>O lock serializa duas renovações simultâneas com o mesmo token: sem ele as duas leriam
   * "não usado", as duas emitiriam um sucessor e a detecção de reuso nunca dispararia.
   *
   * @param tokenHash SHA-256 do token em hexadecimal
   * @return a sessão, se o hash existir
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM SessaoRefresh s WHERE s.tokenHash = :tokenHash")
  Optional<SessaoRefresh> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

  /**
   * Sessão pelo hash do token, sem lock (logout).
   *
   * @param tokenHash SHA-256 do token em hexadecimal
   * @return a sessão, se o hash existir
   */
  Optional<SessaoRefresh> findByTokenHash(String tokenHash);

  /**
   * Revoga todos os tokens ainda não revogados de uma família.
   *
   * @param familiaId família
   * @param agora     instante da revogação
   * @return linhas revogadas
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE SessaoRefresh s SET s.revogadoEm = :agora"
      + " WHERE s.familiaId = :familiaId AND s.revogadoEm IS NULL")
  int revogarFamilia(@Param("familiaId") UUID familiaId, @Param("agora") OffsetDateTime agora);

  /**
   * Revoga todas as sessões de uma credencial ("sair de todos os dispositivos", troca de senha).
   *
   * @param credencialId credencial
   * @param agora        instante da revogação
   * @return linhas revogadas
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE SessaoRefresh s SET s.revogadoEm = :agora"
      + " WHERE s.credencialId = :credencialId AND s.revogadoEm IS NULL")
  int revogarDaCredencial(
      @Param("credencialId") UUID credencialId, @Param("agora") OffsetDateTime agora);

  /**
   * Apaga as sessões vencidas antes do corte (limpeza diária).
   *
   * @param corte instante: o que expirou antes dele é apagado
   * @return linhas apagadas
   */
  @Modifying
  @Query("DELETE FROM SessaoRefresh s WHERE s.expiraEm < :corte")
  int apagarExpiradasAntes(@Param("corte") OffsetDateTime corte);
}
