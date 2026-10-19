package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.security.PapelUsuario;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
