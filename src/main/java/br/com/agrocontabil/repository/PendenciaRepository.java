package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Pendencia;
import br.com.agrocontabil.model.StatusPendencia;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Pendências (pedidos de documento) do escritório ao produtor (issue #180). */
public interface PendenciaRepository extends JpaRepository<Pendencia, UUID> {

  /**
   * Pendências de um produtor no escritório, mais recentes primeiro; {@code status} vazio = todas.
   *
   * @param escritorioId escritório
   * @param produtorId   produtor
   * @param status       status aceitos
   * @return pendências
   */
  @Query("SELECT p FROM Pendencia p WHERE p.escritorioId = :escritorioId"
      + " AND p.produtorId = :produtorId AND p.status IN :status ORDER BY p.createdAt DESC, p.id")
  List<Pendencia> findDoEscritorio(@Param("escritorioId") UUID escritorioId,
      @Param("produtorId") UUID produtorId, @Param("status") Collection<StatusPendencia> status);

  /**
   * Pendências do produtor nos escritórios com vínculo {@code ATIVO}, mais recentes primeiro.
   * Escritório suspenso ou encerrado some da visão do produtor, como no calendário.
   *
   * @param produtorId produtor
   * @param status     status aceitos
   * @return pendências
   */
  @Query("SELECT p FROM Pendencia p WHERE p.produtorId = :produtorId AND p.status IN :status"
      + " AND p.escritorioId IN (SELECT v.escritorioId FROM VinculoCarteira v"
      + " WHERE v.produtorId = :produtorId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)"
      + " ORDER BY p.createdAt DESC, p.id")
  List<Pendencia> findDoProdutor(@Param("produtorId") UUID produtorId,
      @Param("status") Collection<StatusPendencia> status);

  /**
   * Status da pendência (estado "antes" da auditoria).
   *
   * @param id pendência
   * @return o status, se existir
   */
  @Query("SELECT p.status FROM Pendencia p WHERE p.id = :id")
  Optional<StatusPendencia> findStatusById(@Param("id") UUID id);

  /**
   * Pendência do produtor num escritório com vínculo {@code ATIVO} (anti-IDOR: a de outro
   * produtor não existe).
   *
   * @param id         pendência
   * @param produtorId produtor
   * @return a pendência, se for dele
   */
  @Query("SELECT p FROM Pendencia p WHERE p.id = :id AND p.produtorId = :produtorId"
      + " AND p.escritorioId IN (SELECT v.escritorioId FROM VinculoCarteira v"
      + " WHERE v.produtorId = :produtorId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)")
  Optional<Pendencia> findDoProdutorAtiva(@Param("id") UUID id,
      @Param("produtorId") UUID produtorId);

  /**
   * Pendência do escritório para o produtor, sob lock pessimista (transições).
   *
   * @param id           pendência
   * @param escritorioId escritório
   * @param produtorId   produtor
   * @return a pendência, se for daquele escritório e produtor
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM Pendencia p WHERE p.id = :id AND p.escritorioId = :escritorioId"
      + " AND p.produtorId = :produtorId")
  Optional<Pendencia> findDoEscritorioForUpdate(@Param("id") UUID id,
      @Param("escritorioId") UUID escritorioId, @Param("produtorId") UUID produtorId);

  /**
   * Pendência do produtor num escritório com vínculo {@code ATIVO}, sob lock pessimista (anexo).
   *
   * @param id         pendência
   * @param produtorId produtor
   * @return a pendência, se for dele
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM Pendencia p WHERE p.id = :id AND p.produtorId = :produtorId"
      + " AND p.escritorioId IN (SELECT v.escritorioId FROM VinculoCarteira v"
      + " WHERE v.produtorId = :produtorId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)")
  Optional<Pendencia> findDoProdutorAtivaForUpdate(@Param("id") UUID id,
      @Param("produtorId") UUID produtorId);

  /**
   * Pendência sob lock pessimista, sem filtro de dono: só para o job do antivírus (#227), que
   * parte de um anexo já gravado, não de uma requisição.
   *
   * @param id pendência
   * @return a pendência
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM Pendencia p WHERE p.id = :id")
  Optional<Pendencia> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Pendências em aberto com prazo na janela, dos escritórios informados (calendário).
   *
   * @param produtorId  produtor
   * @param escritorios escritórios
   * @param de          primeiro dia
   * @param ate         último dia
   * @return pendências {@code PENDENTE} com {@code dataLimite} na janela
   */
  @Query("SELECT p FROM Pendencia p WHERE p.produtorId = :produtorId"
      + " AND p.escritorioId IN :escritorios"
      + " AND p.status = br.com.agrocontabil.model.StatusPendencia.PENDENTE"
      + " AND p.dataLimite BETWEEN :de AND :ate")
  List<Pendencia> findNoCalendario(@Param("produtorId") UUID produtorId,
      @Param("escritorios") Collection<UUID> escritorios, @Param("de") LocalDate de,
      @Param("ate") LocalDate ate);

  /**
   * Pendências por produtor da carteira ativa num status (indicador de atenção).
   *
   * @param escritorioId escritório
   * @param status       status
   * @return contagens
   */
  @Query("SELECT new br.com.agrocontabil.repository.ContagemPorProdutor(p.produtorId, COUNT(p))"
      + " FROM Pendencia p WHERE p.escritorioId = :escritorioId AND p.status = :status"
      + " AND p.produtorId IN (SELECT v.produtorId FROM VinculoCarteira v"
      + " WHERE v.escritorioId = :escritorioId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)"
      + " GROUP BY p.produtorId")
  List<ContagemPorProdutor> contarDaCarteira(@Param("escritorioId") UUID escritorioId,
      @Param("status") StatusPendencia status);

  /**
   * Apaga as pendências do produtor (exclusão LGPD; os anexos vão antes).
   *
   * @param produtorId produtor
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM Pendencia p WHERE p.produtorId = :produtorId")
  int apagarDoProdutor(@Param("produtorId") UUID produtorId);

  /**
   * Apaga as pendências do escritório (o escritório sai; os anexos vão antes).
   *
   * @param escritorioId escritório
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM Pendencia p WHERE p.escritorioId = :escritorioId")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);
}
