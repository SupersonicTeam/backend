package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.EscritorioSlugAnterior;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Slugs em carência depois de sair de um escritório (issue #464). */
public interface EscritorioSlugAnteriorRepository
    extends JpaRepository<EscritorioSlugAnterior, String> {

  /**
   * Apaga a linha do slug, vencida ou do próprio escritório, antes de ele voltar a ser usado ou de
   * entrar de novo na carência (a chave é o slug).
   *
   * @param slug slug normalizado
   * @return linhas apagadas
   */
  @Modifying
  @Query("DELETE FROM EscritorioSlugAnterior a WHERE a.slug = :slug")
  int apagarPorSlug(@Param("slug") String slug);

  /**
   * Escritório apagado pelo aceite de equipe: os slugs dele seguem bloqueados até o fim da
   * carência, mas sem escritório para onde redirecionar.
   *
   * @param escritorioId escritório que sai
   * @return linhas alteradas
   */
  @Modifying
  @Query("UPDATE EscritorioSlugAnterior a SET a.escritorioId = NULL"
      + " WHERE a.escritorioId = :escritorioId")
  int desvincularDoEscritorio(@Param("escritorioId") UUID escritorioId);

  /**
   * Exclusão de dados (LGPD, #148): os slugs anteriores do escritório saem sem carência.
   *
   * @param escritorioId escritório que sai
   * @return linhas apagadas
   */
  @Modifying
  @Query("DELETE FROM EscritorioSlugAnterior a WHERE a.escritorioId = :escritorioId")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);

  /**
   * Fim da carência: o slug que saiu não fica guardado além dela (pode ser dado pessoal).
   *
   * @param agora instante da limpeza
   * @return linhas apagadas
   */
  @Modifying
  @Query("DELETE FROM EscritorioSlugAnterior a WHERE a.liberadoEm <= :agora")
  int apagarVencidos(@Param("agora") OffsetDateTime agora);
}
