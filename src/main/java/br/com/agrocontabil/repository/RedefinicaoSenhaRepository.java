package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.RedefinicaoSenha;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Pedidos de redefinição de senha (issue #209). Só o hash do token é consultado. */
public interface RedefinicaoSenhaRepository extends JpaRepository<RedefinicaoSenha, UUID> {

  /**
   * Pedido pelo hash do token, sem lock (conferência antes do BCrypt).
   *
   * @param tokenHash SHA-256 do token
   * @return o pedido, se existir
   */
  Optional<RedefinicaoSenha> findByTokenHash(String tokenHash);

  /**
   * Pedido pelo hash do token, sob lock: duas redefinições simultâneas com o mesmo link nunca
   * passam ambas.
   *
   * @param tokenHash SHA-256 do token
   * @return o pedido, se existir
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM RedefinicaoSenha r WHERE r.tokenHash = :tokenHash")
  Optional<RedefinicaoSenha> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

  /**
   * Apaga os pedidos de uma credencial: um pedido novo invalida os links já enviados, e a troca de
   * senha invalida todos.
   *
   * @param credencialId credencial
   * @return linhas apagadas
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM RedefinicaoSenha r WHERE r.credencialId = :credencialId")
  int apagarDaCredencial(@Param("credencialId") UUID credencialId);
}
