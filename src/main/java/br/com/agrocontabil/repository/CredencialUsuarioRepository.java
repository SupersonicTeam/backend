package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.security.PapelUsuario;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Credenciais de acesso (issue #205). O e-mail chega sempre normalizado pelo service. */
public interface CredencialUsuarioRepository extends JpaRepository<CredencialUsuario, UUID> {

  /**
   * Credencial pelo e-mail de login.
   *
   * @param email e-mail já normalizado
   * @return a credencial, se houver
   */
  Optional<CredencialUsuario> findByEmail(String email);

  /**
   * Diz se o e-mail já tem credencial.
   *
   * @param email e-mail já normalizado
   * @return {@code true} se já existe credencial com este e-mail
   */
  boolean existsByEmail(String email);

  /**
   * Diz se o documento já tem credencial naquele papel.
   *
   * @param papel   papel da credencial
   * @param cpfCnpj documento, só dígitos
   * @return {@code true} se já existe credencial deste documento neste papel
   */
  boolean existsByPapelAndCpfCnpj(PapelUsuario papel, String cpfCnpj);

  /**
   * Credencial sob lock pessimista (issue #208): serializa as falhas de login simultâneas da mesma
   * conta, para que dez tentativas paralelas contem dez — e não uma só, como leria um
   * incremento sobre a cópia em memória.
   *
   * @param id credencial
   * @return a credencial, se existir
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM CredencialUsuario c WHERE c.id = :id")
  Optional<CredencialUsuario> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Regrava só o hash da senha (issue #206: rehash no login quando o custo do BCrypt sobe).
   *
   * <p>{@code UPDATE} de uma coluna, e não {@code save} da entidade: não pode sobrescrever a
   * versão de sessão nem o {@code ativo} gravados por outra requisição no meio do login.
   *
   * @param id        credencial
   * @param senhaHash hash novo
   * @return linhas alteradas
   */
  @Transactional
  @Modifying
  @Query("UPDATE CredencialUsuario c SET c.senhaHash = :senhaHash WHERE c.id = :id")
  int atualizarSenhaHash(@Param("id") UUID id, @Param("senhaHash") String senhaHash);

  /**
   * Regrava o hash só se ele ainda for o que foi lido — o rehash do login (issue #206).
   *
   * <p>Achado do review da #209: o rehash incondicional corria com a redefinição de senha. O
   * invasor entrando com a senha antiga no instante em que a vítima a redefine podia regravar o
   * hash da senha <em>antiga</em> por cima do novo, e a senha roubada voltava a funcionar. Com a
   * condição, se a senha mudou no meio, o rehash não acontece (0 linhas).
   *
   * @param id       credencial
   * @param hashLido hash conferido no login
   * @param novoHash hash no custo atual
   * @return linhas alteradas (0 se a senha mudou no meio)
   */
  @Transactional
  @Modifying
  @Query("UPDATE CredencialUsuario c SET c.senhaHash = :novoHash"
      + " WHERE c.id = :id AND c.senhaHash = :hashLido")
  int atualizarSenhaHashSeIgual(@Param("id") UUID id, @Param("hashLido") String hashLido,
      @Param("novoHash") String novoHash);

  /**
   * Incrementa a versão de sessão: todo access token e todo refresh emitidos antes deixam de valer
   * (issue #207). {@code UPDATE} atômico — duas revogações simultâneas nunca se anulam.
   *
   * @param id credencial
   * @return linhas alteradas
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE CredencialUsuario c SET c.versaoSessao = c.versaoSessao + 1 WHERE c.id = :id")
  int incrementarVersaoSessao(@Param("id") UUID id);

  /**
   * Credencial de um produtor.
   *
   * @param produtorId produtor
   * @return a credencial, se o produtor tiver uma
   */
  Optional<CredencialUsuario> findByProdutorId(UUID produtorId);

  /**
   * Credencial de um contador.
   *
   * @param contadorId contador
   * @return a credencial, se o contador tiver uma
   */
  Optional<CredencialUsuario> findByContadorId(UUID contadorId);
}
