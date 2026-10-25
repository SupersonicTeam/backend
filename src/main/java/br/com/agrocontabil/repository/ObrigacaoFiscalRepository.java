package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ObrigacaoFiscal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Catálogo de obrigações (issue #181): modelos do sistema e linhas dos escritórios. */
public interface ObrigacaoFiscalRepository extends JpaRepository<ObrigacaoFiscal, UUID> {

  /**
   * Modelos do sistema.
   *
   * @return modelos, por título
   */
  @Query("SELECT o FROM ObrigacaoFiscal o WHERE o.modelo = true ORDER BY o.titulo")
  List<ObrigacaoFiscal> findModelos();

  /**
   * Linhas dos escritórios (próprias e versões de modelo).
   *
   * @param escritorios escritórios
   * @return obrigações deles
   */
  List<ObrigacaoFiscal> findByEscritorioIdIn(Collection<UUID> escritorios);

  /**
   * Obrigação do escritório por id (anti-IDOR: a de outro escritório não existe).
   *
   * @param id           obrigação
   * @param escritorioId escritório
   * @return a obrigação, se for dele
   */
  Optional<ObrigacaoFiscal> findByIdAndEscritorioId(UUID id, UUID escritorioId);

  /**
   * Versão do escritório para um modelo.
   *
   * @param escritorioId escritório
   * @param modeloId     modelo
   * @return a versão, se houver
   */
  Optional<ObrigacaoFiscal> findByEscritorioIdAndModeloId(UUID escritorioId, UUID modeloId);

  /**
   * Apaga o catálogo do escritório (o escritório sai: último membro excluiu os dados, #148).
   *
   * @param escritorioId escritório
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ObrigacaoFiscal o WHERE o.escritorioId = :escritorioId")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);

  /**
   * Apaga as obrigações de todos os escritórios, mantendo os modelos (limpeza dos testes).
   *
   * @return linhas removidas
   */
  @Transactional
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ObrigacaoFiscal o WHERE o.modelo = false")
  int apagarDosEscritorios();

  /**
   * Modelo por id.
   *
   * @param id modelo
   * @return o modelo, se for um
   */
  @Query("SELECT o FROM ObrigacaoFiscal o WHERE o.id = :id AND o.modelo = true")
  Optional<ObrigacaoFiscal> findModeloById(@Param("id") UUID id);
}
