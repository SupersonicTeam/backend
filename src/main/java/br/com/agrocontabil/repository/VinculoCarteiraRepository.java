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
   * Responde se o escritório tem vínculo na situação informada com o produtor (issue #228: o dono
   * é o escritório, não o contador).
   *
   * @param escritorioId escritório
   * @param produtorId produtor
   * @param situacao   situação exigida
   * @return {@code true} se houver
   */
  boolean existsByEscritorioIdAndProdutorIdAndSituacao(
      UUID escritorioId, UUID produtorId, SituacaoVinculo situacao);

  /**
   * Vínculo não encerrado do par, sob lock pessimista (transições de situação).
   *
   * @param escritorioId escritório
   * @param produtorId produtor
   * @return o vínculo ativo ou suspenso, se houver
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT v FROM VinculoCarteira v WHERE v.escritorioId = :escritorioId"
      + " AND v.produtorId = :produtorId AND v.situacao <> br.com.agrocontabil.model"
      + ".SituacaoVinculo.ENCERRADO")
  Optional<VinculoCarteira> findAbertoForUpdate(
      @Param("escritorioId") UUID escritorioId, @Param("produtorId") UUID produtorId);

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
   * @param escritorioId escritório
   * @param produtorId produtor
   * @param situacao   situação
   * @return o vínculo, se houver
   */
  @Query("SELECT DISTINCT v FROM VinculoCarteira v JOIN FETCH v.produtor"
      + " LEFT JOIN FETCH v.etiquetas WHERE v.escritorioId = :escritorioId"
      + " AND v.produtorId = :produtorId AND v.situacao = :situacao")
  Optional<VinculoCarteira> findFicha(@Param("escritorioId") UUID escritorioId,
      @Param("produtorId") UUID produtorId, @Param("situacao") SituacaoVinculo situacao);

  /**
   * Vínculos do escritório numa situação, com o produtor (lista da carteira).
   *
   * @param escritorioId escritório
   * @param situacao   situação
   * @return vínculos, em qualquer ordem
   */
  @Query("SELECT v FROM VinculoCarteira v JOIN FETCH v.produtor"
      + " WHERE v.escritorioId = :escritorioId AND v.situacao = :situacao")
  List<VinculoCarteira> findDaCarteira(
      @Param("escritorioId") UUID escritorioId, @Param("situacao") SituacaoVinculo situacao);

  /**
   * Escritórios que atendem o produtor com vínculo ativo (obrigações do calendário, #181).
   *
   * @param produtorId produtor
   * @return ids dos escritórios
   */
  @Query("SELECT v.escritorioId FROM VinculoCarteira v WHERE v.produtorId = :produtorId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO")
  List<UUID> findEscritoriosAtivosDoProdutor(@Param("produtorId") UUID produtorId);

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
   * @param escritorioId escritório
   * @param situacao   situação
   * @return pares vínculo × nome
   */
  @Query("SELECT new br.com.agrocontabil.repository.EtiquetaDoVinculo("
      + "v.id, e.nome) FROM VinculoCarteira v JOIN v.etiquetas e"
      + " WHERE v.escritorioId = :escritorioId AND v.situacao = :situacao")
  List<EtiquetaDoVinculo> findEtiquetasDaCarteira(
      @Param("escritorioId") UUID escritorioId, @Param("situacao") SituacaoVinculo situacao);

  /**
   * Notas por produtor da carteira ativa num status.
   *
   * @param escritorioId escritório
   * @param status     status das notas
   * @return contagens (só produtores com alguma)
   */
  @Query("SELECT new br.com.agrocontabil.repository.ContagemPorProdutor("
      + "n.produtor.id, COUNT(n)) FROM NotaFiscal n WHERE n.status = :status"
      + " AND n.produtor.id IN (SELECT v.produtorId FROM VinculoCarteira v"
      + " WHERE v.escritorioId = :escritorioId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)"
      + " GROUP BY n.produtor.id")
  List<ContagemPorProdutor> contarNotasDaCarteira(
      @Param("escritorioId") UUID escritorioId, @Param("status") StatusNotaFiscal status);

  /**
   * Guias vencidas por produtor da carteira ativa: {@code VENCIDA} e também a {@code PENDENTE} com
   * vencimento passado que o job diário ainda não marcou.
   *
   * @param escritorioId escritório
   * @param hoje       data de hoje em Brasília
   * @return contagens (só produtores com alguma)
   */
  @Query("SELECT new br.com.agrocontabil.repository.ContagemPorProdutor("
      + "g.produtor.id, COUNT(g)) FROM GuiaFiscal g"
      + " WHERE (g.status = br.com.agrocontabil.model.StatusGuiaFiscal.VENCIDA"
      + " OR (g.status = br.com.agrocontabil.model.StatusGuiaFiscal.PENDENTE"
      + " AND g.dataVencimento < :hoje))"
      + " AND g.produtor.id IN (SELECT v.produtorId FROM VinculoCarteira v"
      + " WHERE v.escritorioId = :escritorioId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)"
      + " GROUP BY g.produtor.id")
  List<ContagemPorProdutor> contarGuiasVencidasDaCarteira(
      @Param("escritorioId") UUID escritorioId, @Param("hoje") LocalDate hoje);

  /**
   * Guias de um status com vencimento na faixa, por produtor da carteira ativa.
   *
   * @param escritorioId escritório
   * @param status     status das guias
   * @param de         primeiro dia (inclusivo)
   * @param ate        último dia (inclusivo)
   * @return contagens (só produtores com alguma)
   */
  @Query("SELECT new br.com.agrocontabil.repository.ContagemPorProdutor("
      + "g.produtor.id, COUNT(g)) FROM GuiaFiscal g WHERE g.status = :status"
      + " AND g.dataVencimento BETWEEN :de AND :ate"
      + " AND g.produtor.id IN (SELECT v.produtorId FROM VinculoCarteira v"
      + " WHERE v.escritorioId = :escritorioId"
      + " AND v.situacao = br.com.agrocontabil.model.SituacaoVinculo.ATIVO)"
      + " GROUP BY g.produtor.id")
  List<ContagemPorProdutor> contarGuiasNaFaixaDaCarteira(@Param("escritorioId") UUID escritorioId,
      @Param("status") StatusGuiaFiscal status, @Param("de") LocalDate de,
      @Param("ate") LocalDate ate);

  /**
   * Deixa sem responsável os clientes de um membro (removido da equipe ou excluído).
   *
   * @param responsavelId contador
   * @return vínculos alterados
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE VinculoCarteira v SET v.responsavelId = NULL"
      + " WHERE v.responsavelId = :responsavelId")
  int limparResponsavel(@Param("responsavelId") UUID responsavelId);

  /**
   * Quantos vínculos não encerrados o escritório tem (o unipessoal só sai de cena vazio).
   *
   * @param escritorioId escritório
   * @return vínculos ativos ou suspensos
   */
  @Query("SELECT COUNT(v) FROM VinculoCarteira v WHERE v.escritorioId = :escritorioId"
      + " AND v.situacao <> br.com.agrocontabil.model.SituacaoVinculo.ENCERRADO")
  long contarAbertosDoEscritorio(@Param("escritorioId") UUID escritorioId);

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
   * @param escritorioId escritório
   * @return ids
   */
  @Query("SELECT v.id FROM VinculoCarteira v WHERE v.escritorioId = :escritorioId")
  List<UUID> findIdsDoEscritorio(@Param("escritorioId") UUID escritorioId);

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
