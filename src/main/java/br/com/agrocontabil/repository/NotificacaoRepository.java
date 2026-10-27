package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Notificacao;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Notificações ao produtor (issue #182). */
public interface NotificacaoRepository extends JpaRepository<Notificacao, UUID> {

  /**
   * Notificações do produtor, mais recentes primeiro (lado do produtor: todas).
   *
   * @param produtorId produtor
   * @param pagina     página
   * @return a página
   */
  Page<Notificacao> findByProdutorIdOrderByCriadoEmDescIdDesc(UUID produtorId, Pageable pagina);

  /**
   * Notificações que o escritório disparou ao cliente (lado do escritório: só as dele).
   *
   * @param produtorId   produtor
   * @param escritorioId escritório
   * @param pagina       página
   * @return a página
   */
  Page<Notificacao> findByProdutorIdAndEscritorioIdOrderByCriadoEmDescIdDesc(UUID produtorId,
      UUID escritorioId, Pageable pagina);

  /**
   * Instantes dos disparos manuais ao produtor desde {@code desde}, do mais antigo (teto por
   * hora; o aviso automático não conta).
   *
   * @param produtorId produtor
   * @param desde      início da janela
   * @return instantes, do mais antigo
   */
  @Query("SELECT n.criadoEm FROM Notificacao n WHERE n.produtorId = :produtorId"
      + " AND n.criadoEm > :desde"
      + " AND n.tipo <> br.com.agrocontabil.model.TipoNotificacao.AVISO_URGENTE"
      + " ORDER BY n.criadoEm ASC")
  List<OffsetDateTime> disparosManuaisDesde(@Param("produtorId") UUID produtorId,
      @Param("desde") OffsetDateTime desde);

  /**
   * Se o aviso automático daquele item já foi registrado (idempotência do job).
   *
   * @param produtorId produtor
   * @param chaveItem  {@code <item>:<data>}
   * @return se existe
   */
  boolean existsByProdutorIdAndChaveItem(UUID produtorId, String chaveItem);

  /**
   * Apaga as notificações criadas antes do corte (retenção).
   *
   * @param corte instante de corte
   * @return linhas removidas
   */
  @Modifying
  @Query("DELETE FROM Notificacao n WHERE n.criadoEm < :corte")
  int apagarAnterioresA(@Param("corte") OffsetDateTime corte);

  /**
   * Apaga as notificações do produtor (exclusão LGPD).
   *
   * @param produtorId produtor
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM Notificacao n WHERE n.produtorId = :produtorId")
  int apagarDoProdutor(@Param("produtorId") UUID produtorId);

  /**
   * Apaga as notificações que o escritório disparou (o escritório sai).
   *
   * @param escritorioId escritório
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM Notificacao n WHERE n.escritorioId = :escritorioId")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);
}
