package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
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

/**
 * Vínculos da carteira do escritório (issue #177) — a autorização do contador sobre o produtor.
 *
 * <p>As contagens de "atenção" da lista da carteira vivem aqui, uma consulta agregada
 * ({@code GROUP BY produtor}) por indicador sobre os produtores com vínculo {@code ATIVO}: nunca
 * uma consulta por cliente.
 */
public interface VinculoCarteiraRepository extends JpaRepository<VinculoCarteira, UUID> {

  /**
   * Responde se o contador tem vínculo na situação informada com o produtor.
   *
   * @param contadorId contador
   * @param produtorId produtor
   * @param situacao   situação exigida
   * @return {@code true} se houver
   */
  boolean existsByContadorIdAndProdutorIdAndSituacao(
      UUID contadorId, UUID produtorId, SituacaoVinculo situacao);

  /**
   * Vínculo não encerrado do par, sob lock pessimista (transições de situação).
   *
   * @param contadorId contador
   * @param produtorId produtor
   * @return o vínculo ativo ou suspenso, se houver
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT v FROM VinculoCarteira v WHERE v.contadorId = :contadorId"
      + " AND v.produtorId = :produtorId AND v.situacao <> br.com.agrocontabil.model"
      + ".SituacaoVinculo.ENCERRADO")
  Optional<VinculoCarteira> findAbertoForUpdate(
      @Param("contadorId") UUID contadorId, @Param("produtorId") UUID produtorId);

  /**
   * Vínculo do produtor por id, sob lock pessimista (o produtor encerra o vínculo).
   *
   * @param id         vínculo
   * @param produtorId produtor dono
   * @return o vínculo, se for do produtor
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT v FROM VinculoCarteira v WHERE v.id = :id AND v.produtorId = :produtorId")
  Optional<VinculoCarteira> findByIdAndProdutorIdForUpdate(
      @Param("id") UUID id, @Param("produtorId") UUID produtorId);

  /**
   * Vínculo do par numa situação, com o produtor e as etiquetas (ficha do cliente).
   *
   * @param contadorId contador
   * @param produtorId produtor
   * @param situacao   situação
   * @return o vínculo, se houver
   */
  @Query("SELECT DISTINCT v FROM VinculoCarteira v JOIN FETCH v.produtor"
      + " LEFT JOIN FETCH v.etiquetas WHERE v.contadorId = :contadorId"
      + " AND v.produtorId = :produtorId AND v.situacao = :situacao")
  Optional<VinculoCarteira> findFicha(@Param("contadorId") UUID contadorId,
      @Param("produtorId") UUID produtorId, @Param("situacao") SituacaoVinculo situacao);

  /**
   * Vínculos do escritório numa situação, com o produtor (lista da carteira).
   *
   * @param contadorId contador
   * @param situacao   situação
   * @return vínculos, em qualquer ordem
   */
  @Query("SELECT v FROM VinculoCarteira v JOIN FETCH v.produtor"
      + " WHERE v.contadorId = :contadorId AND v.situacao = :situacao")
  List<VinculoCarteira> findDaCarteira(
      @Param("contadorId") UUID contadorId, @Param("situacao") SituacaoVinculo situacao);

  /**
   * Vínculos não encerrados do produtor (o produtor vê quem o atende).
   *
   * @param produtorId produtor
   * @return vínculos ativos e suspensos, do mais recente ao mais antigo
   */
  @Query("SELECT v FROM VinculoCarteira v WHERE v.produtorId = :produtorId"
      + " AND v.situacao <> br.com.agrocontabil.model.SituacaoVinculo.ENCERRADO"
      + " ORDER BY v.inicio DESC, v.id")
  List<VinculoCarteira> findAbertosDoProdutor(@Param("produtorId") UUID produtorId);

  /**
   * Etiquetas dos vínculos do escritório numa situação, numa consulta só.
   *
   * @param contadorId contador
   * @param situacao   situação
   * @return pares vínculo × nome
   */
  @Query("SELECT new br.com.agrocontabil.repository.EtiquetaDoVinculo("
      + "v.id, e.nome) FROM VinculoCarteira v JOIN v.etiquetas e"
      + " WHERE v.contadorId = :contadorId AND v.situacao = :situacao")
  List<EtiquetaDoVinculo> findEtiquetasDaCarteira(
      @Param("contadorId") UUID contadorId, @Param("situacao") SituacaoVinculo situacao);

  /**
   * Notas por produtor da carteira ativa num status.
   *
   * @param contadorId contador
   * @param status     status das notas
   * @return contagens (só produtores com alguma)
   */
  @Query("SELECT new br.com.agrocontabil.repository.ContagemPorProdutor("
      + "n.produtor.id, COUNT(n)) FROM NotaFiscal n WHERE n.status = :status"
      + " AND n.produtor.id IN (SELECT v.produtorId FROM VinculoCarteira v"
      + " WHERE v.contadorId = :contadorId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)"
      + " GROUP BY n.produtor.id")
  List<ContagemPorProdutor> contarNotasDaCarteira(
      @Param("contadorId") UUID contadorId, @Param("status") StatusNotaFiscal status);

  /**
   * Guias vencidas por produtor da carteira ativa: {@code VENCIDA} e também a {@code PENDENTE} com
   * vencimento passado que o job diário ainda não marcou.
   *
   * @param contadorId contador
   * @param hoje       data de hoje em Brasília
   * @return contagens (só produtores com alguma)
   */
  @Query("SELECT new br.com.agrocontabil.repository.ContagemPorProdutor("
      + "g.produtor.id, COUNT(g)) FROM GuiaFiscal g"
      + " WHERE (g.status = br.com.agrocontabil.model.StatusGuiaFiscal.VENCIDA"
      + " OR (g.status = br.com.agrocontabil.model.StatusGuiaFiscal.PENDENTE"
      + " AND g.dataVencimento < :hoje))"
      + " AND g.produtor.id IN (SELECT v.produtorId FROM VinculoCarteira v"
      + " WHERE v.contadorId = :contadorId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)"
      + " GROUP BY g.produtor.id")
  List<ContagemPorProdutor> contarGuiasVencidasDaCarteira(
      @Param("contadorId") UUID contadorId, @Param("hoje") LocalDate hoje);

  /**
   * Guias de um status com vencimento na faixa, por produtor da carteira ativa.
   *
   * @param contadorId contador
   * @param status     status das guias
   * @param de         primeiro dia (inclusivo)
   * @param ate        último dia (inclusivo)
   * @return contagens (só produtores com alguma)
   */
  @Query("SELECT new br.com.agrocontabil.repository.ContagemPorProdutor("
      + "g.produtor.id, COUNT(g)) FROM GuiaFiscal g WHERE g.status = :status"
      + " AND g.dataVencimento BETWEEN :de AND :ate"
      + " AND g.produtor.id IN (SELECT v.produtorId FROM VinculoCarteira v"
      + " WHERE v.contadorId = :contadorId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)"
      + " GROUP BY g.produtor.id")
  List<ContagemPorProdutor> contarGuiasNaFaixaDaCarteira(@Param("contadorId") UUID contadorId,
      @Param("status") StatusGuiaFiscal status, @Param("de") LocalDate de,
      @Param("ate") LocalDate ate);

  /**
   * Tira as etiquetas dos vínculos informados (antes de apagá-los).
   *
   * @param vinculos ids dos vínculos
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query(value = "DELETE FROM carteira_vinculo_etiqueta WHERE vinculo_id IN (:vinculos)",
      nativeQuery = true)
  int apagarEtiquetasDosVinculos(@Param("vinculos") Collection<UUID> vinculos);

  /**
   * Ids dos vínculos de um produtor (exclusão LGPD).
   *
   * @param produtorId produtor
   * @return ids
   */
  @Query("SELECT v.id FROM VinculoCarteira v WHERE v.produtorId = :produtorId")
  List<UUID> findIdsDoProdutor(@Param("produtorId") UUID produtorId);

  /**
   * Ids dos vínculos de um escritório (exclusão LGPD).
   *
   * @param contadorId contador
   * @return ids
   */
  @Query("SELECT v.id FROM VinculoCarteira v WHERE v.contadorId = :contadorId")
  List<UUID> findIdsDoContador(@Param("contadorId") UUID contadorId);

  /**
   * Apaga vínculos por id (a ficha vai junto: as observações moram no vínculo).
   *
   * @param ids vínculos
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("DELETE FROM VinculoCarteira v WHERE v.id IN :ids")
  int apagarPorIds(@Param("ids") Collection<UUID> ids);
}
