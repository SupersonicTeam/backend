package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.TipoEscritorio;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Escritórios de contabilidade (issue #228). */
public interface EscritorioRepository extends JpaRepository<Escritorio, UUID> {

  /**
   * Escritório sob lock pessimista: serializa os convites dele para o teto diário (#177) não ser
   * furado por requisições simultâneas, e as mudanças de equipe.
   *
   * @param id escritório
   * @return o escritório, se existir
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT e FROM Escritorio e WHERE e.id = :id")
  Optional<Escritorio> findByIdForUpdate(@Param("id") UUID id);

  /**
   * O escritório existe e é deste tipo (#519)? Só o tipo, sem carregar a entidade: o convite de
   * equipe confere se o escritório é empresarial.
   *
   * @param id   escritório
   * @param tipo tipo esperado
   * @return {@code true} se existe com esse tipo
   */
  boolean existsByIdAndTipo(UUID id, TipoEscritorio tipo);

  /**
   * Outro escritório já tem este CNPJ verificado (#520)? Regra da verificação manual; no
   * PostgreSQL o índice único parcial {@code uq_escritorio_cnpj_verificado} também a garante. Nunca
   * usar no cadastro nem no {@code PATCH}: lá CNPJ repetido não é recusado, para não virar oráculo.
   *
   * @param cnpj CNPJ, só dígitos
   * @param id   escritório que quer a verificação
   * @return {@code true} se outro escritório tem o CNPJ verificado
   */
  boolean existsByCnpjAndCnpjVerificadoEmIsNotNullAndIdNot(String cnpj, UUID id);

  /**
   * O slug está em uso por outro escritório, ou em carência de outro escritório (ou de um
   * escritório apagado)? Uma consulta só, para as duas tabelas serem lidas no mesmo instante:
   * em duas, uma troca concorrente podia passar entre as leituras e liberar o slug em carência.
   *
   * @param slug         slug normalizado
   * @param escritorioId escritório que quer o slug (a carência dele mesmo não bloqueia)
   * @param agora        instante da conferência
   * @return {@code true} se outro escritório tem ou guarda o slug
   */
  @Query(value = "SELECT (EXISTS (SELECT 1 FROM escritorio e WHERE e.slug = :slug"
      + " AND e.id <> :escritorioId)"
      + " OR EXISTS (SELECT 1 FROM escritorio_slug_anterior a WHERE a.slug = :slug"
      + " AND a.liberado_em > :agora"
      + " AND (a.escritorio_id IS NULL OR a.escritorio_id <> :escritorioId)))",
      nativeQuery = true)
  boolean slugIndisponivel(@Param("slug") String slug, @Param("escritorioId") UUID escritorioId,
      @Param("agora") OffsetDateTime agora);

  /**
   * Escritório com este endereço ativo e CNPJ verificado: sem a verificação (#520), o endereço não
   * é servido (#464). O slug fica reservado do mesmo jeito ({@link #slugIndisponivel}).
   *
   * @param slug slug normalizado
   * @return o escritório, se houver
   */
  @Query("SELECT e FROM Escritorio e WHERE e.slug = :slug AND e.cnpjVerificadoEm IS NOT NULL")
  Optional<Escritorio> findAtivoPorSlug(@Param("slug") String slug);

  /**
   * Para onde redireciona um slug em carência (#464): o escritório de onde ele saiu, se ainda existe
   * e tem outro endereço ativo e o CNPJ verificado (#520). Carência vencida, de escritório apagado,
   * sem endereço ou não verificado: vazio.
   *
   * @param slug  slug normalizado
   * @param agora instante da consulta
   * @return o escritório de destino, se houver
   */
  @Query("SELECT e FROM EscritorioSlugAnterior a, Escritorio e WHERE a.slug = :slug"
      + " AND a.liberadoEm > :agora AND a.escritorioId = e.id AND e.slug IS NOT NULL"
      + " AND e.cnpjVerificadoEm IS NOT NULL")
  Optional<Escritorio> findDestinoDaCarencia(@Param("slug") String slug,
      @Param("agora") OffsetDateTime agora);

  /**
   * O contador é membro do escritório que tem este endereço ativo e o CNPJ verificado (#520)?
   * Regra do login e do refresh pelo subdomínio (#464).
   *
   * @param slug       slug normalizado
   * @param contadorId contador da credencial
   * @return {@code true} se for membro
   */
  @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Contador c, Escritorio e"
      + " WHERE c.id = :contadorId AND c.escritorioId = e.id AND e.slug = :slug"
      + " AND e.cnpjVerificadoEm IS NOT NULL")
  boolean membroDoEnderecoAtivo(@Param("slug") String slug,
      @Param("contadorId") UUID contadorId);
}
