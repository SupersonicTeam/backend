package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ConviteCarteira;
import br.com.agrocontabil.model.SituacaoConvite;
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

/**
 * Convites da carteira (issue #177). O e-mail é cifrado de forma determinística (#147), então as
 * consultas por e-mail são por igualdade — o Hibernate cifra também o parâmetro.
 */
public interface ConviteCarteiraRepository extends JpaRepository<ConviteCarteira, UUID> {

  /**
   * Convites ainda válidos para um e-mail (o produtor vê os dele).
   *
   * @param email    e-mail normalizado
   * @param situacao {@code PENDENTE}
   * @param agora    instante atual
   * @return convites, do mais recente ao mais antigo
   */
  List<ConviteCarteira> findByEmailAndSituacaoAndExpiraEmAfterOrderByCriadoEmDesc(
      String email, SituacaoConvite situacao, OffsetDateTime agora);

  /**
   * Convite por id e e-mail, sob lock pessimista (aceite e recusa).
   *
   * @param id    convite
   * @param email e-mail do produtor autenticado
   * @return o convite, se for para aquele e-mail
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM ConviteCarteira c WHERE c.id = :id AND c.email = :email")
  Optional<ConviteCarteira> findByIdAndEmailForUpdate(
      @Param("id") UUID id, @Param("email") String email);

  /**
   * Convite do escritório para o e-mail numa situação.
   *
   * @param escritorioId escritório
   * @param email      e-mail normalizado
   * @param situacao   situação
   * @return o convite, se houver (pendente é um só por par)
   */
  Optional<ConviteCarteira> findByEscritorioIdAndEmailAndSituacao(
      UUID escritorioId, String email, SituacaoConvite situacao);

  /**
   * Situação do convite (estado "antes" da auditoria).
   *
   * @param id convite
   * @return a situação, se existir
   */
  @Query("SELECT c.situacao FROM ConviteCarteira c WHERE c.id = :id")
  Optional<SituacaoConvite> findSituacaoById(@Param("id") UUID id);

  /**
   * Convites pendentes e válidos do escritório.
   *
   * @param escritorioId escritório
   * @param situacao   {@code PENDENTE}
   * @param agora      instante atual
   * @return convites, do mais recente ao mais antigo
   */
  List<ConviteCarteira> findByEscritorioIdAndSituacaoAndExpiraEmAfterOrderByCriadoEmDesc(
      UUID escritorioId, SituacaoConvite situacao, OffsetDateTime agora);

  /**
   * Quantos convites o escritório criou desde um instante (teto contra envio em massa).
   *
   * @param escritorioId escritório
   * @param desde      início da janela
   * @return quantidade
   */
  long countByEscritorioIdAndCriadoEmAfter(UUID escritorioId, OffsetDateTime desde);

  /**
   * Convite mais antigo do escritório desde um instante (quando o teto libera de novo).
   *
   * @param escritorioId escritório
   * @param desde      início da janela
   * @return o mais antigo da janela, se houver
   */
  Optional<ConviteCarteira> findFirstByEscritorioIdAndCriadoEmAfterOrderByCriadoEmAsc(
      UUID escritorioId, OffsetDateTime desde);

  /**
   * Apaga o convite pendente do escritório para o e-mail (reenviar substitui o anterior).
   *
   * @param escritorioId escritório
   * @param email      e-mail normalizado
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ConviteCarteira c WHERE c.escritorioId = :escritorioId AND c.email = :email"
      + " AND c.situacao = br.com.agrocontabil.model.SituacaoConvite.PENDENTE")
  int apagarPendente(@Param("escritorioId") UUID escritorioId, @Param("email") String email);

  /**
   * Apaga os convites vencidos ou respondidos antes do corte (retenção, LGPD: o e-mail de quem
   * nunca respondeu não fica para sempre).
   *
   * @param corte instante de corte
   * @return linhas removidas
   */
  @Modifying
  @Query("DELETE FROM ConviteCarteira c WHERE c.expiraEm < :corte")
  int apagarExpiradosAntes(@Param("corte") OffsetDateTime corte);

  /**
   * Apaga os convites do escritório (exclusão LGPD).
   *
   * @param escritorioId escritório
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ConviteCarteira c WHERE c.escritorioId = :escritorioId")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);

  /**
   * Apaga os convites respondidos pelo produtor ou dirigidos ao e-mail dele (exclusão LGPD).
   *
   * @param produtorId produtor
   * @param email      e-mail da credencial (pode ser nulo)
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ConviteCarteira c WHERE c.produtorId = :produtorId OR c.email = :email")
  int apagarDoProdutor(@Param("produtorId") UUID produtorId, @Param("email") String email);
}
