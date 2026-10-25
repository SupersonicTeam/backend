package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ConviteMembro;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Convites de equipe do escritório (issue #228). E-mail cifrado: consulta só por igualdade. */
public interface ConviteMembroRepository extends JpaRepository<ConviteMembro, UUID> {

  /**
   * Convite pelo hash do token, sob lock (uso único).
   *
   * @param tokenHash SHA-256 do token
   * @return o convite, se existir
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM ConviteMembro c WHERE c.tokenHash = :tokenHash")
  Optional<ConviteMembro> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

  /**
   * Convite pelo hash do token, sem lock (leitura antes do cadastro).
   *
   * @param tokenHash SHA-256 do token
   * @return o convite, se existir
   */
  Optional<ConviteMembro> findByTokenHash(String tokenHash);

  /**
   * Convite não usado do escritório para o e-mail (reenviar substitui).
   *
   * @param escritorioId escritório
   * @param email        e-mail normalizado
   * @return o convite pendente, se houver
   */
  Optional<ConviteMembro> findFirstByEscritorioIdAndEmailAndUsadoEmIsNullOrderByCriadoEmDesc(
      UUID escritorioId, String email);

  /**
   * Convites não usados e válidos do escritório.
   *
   * @param escritorioId escritório
   * @param agora        instante atual
   * @return convites, do mais recente ao mais antigo
   */
  List<ConviteMembro> findByEscritorioIdAndUsadoEmIsNullAndExpiraEmAfterOrderByCriadoEmDesc(
      UUID escritorioId, OffsetDateTime agora);

  /**
   * Quantos convites de equipe o escritório criou desde um instante (teto diário).
   *
   * @param escritorioId escritório
   * @param desde        início da janela
   * @return quantidade
   */
  long countByEscritorioIdAndCriadoEmAfter(UUID escritorioId, OffsetDateTime desde);

  /**
   * Convite de equipe mais antigo do escritório desde um instante (quando o teto libera).
   *
   * @param escritorioId escritório
   * @param desde        início da janela
   * @return o mais antigo da janela, se houver
   */
  Optional<ConviteMembro> findFirstByEscritorioIdAndCriadoEmAfterOrderByCriadoEmAsc(
      UUID escritorioId, OffsetDateTime desde);

  /**
   * Apaga os convites não usados do escritório para o e-mail.
   *
   * @param escritorioId escritório
   * @param email        e-mail normalizado
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ConviteMembro c WHERE c.escritorioId = :escritorioId AND c.email = :email"
      + " AND c.usadoEm IS NULL")
  int apagarPendentes(@Param("escritorioId") UUID escritorioId, @Param("email") String email);

  /**
   * Apaga os convites vencidos antes do corte (retenção: o e-mail não fica para sempre).
   *
   * @param corte instante de corte
   * @return linhas removidas
   */
  @Modifying
  @Query("DELETE FROM ConviteMembro c WHERE c.expiraEm < :corte")
  int apagarExpiradosAntes(@Param("corte") OffsetDateTime corte);

  /**
   * Apaga os convites de equipe do escritório (exclusão LGPD do último membro).
   *
   * @param escritorioId escritório
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ConviteMembro c WHERE c.escritorioId = :escritorioId")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);
}
