package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.AlertaTributario;
import br.com.agrocontabil.model.TipoAlertaTributario;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Alertas tributários (issue #140, RN010).
 *
 * <p>A leitura ({@link #listarAtivos}) não confia só no status gravado pelo job: confere, na
 * mesma consulta, que o recurso ainda está na situação que gerou o alerta (guia pendente, nota
 * autorizada, apuração aberta) e que pertence ao mesmo produtor. Assim, uma guia paga às 10h não
 * alerta até o job do dia seguinte.
 */
public interface AlertaTributarioRepository extends JpaRepository<AlertaTributario, UUID> {

  /** Idempotência do job: o alerta deste recurso e prazo já existe? */
  boolean existsByTipoAndReferenciaIdAndDataLimite(
      TipoAlertaTributario tipo, UUID referenciaId, LocalDate dataLimite);

  /**
   * Alertas ativos do produtor com prazo a partir de hoje e motivo ainda válido, do mais urgente
   * para o mais distante.
   *
   * <p>O alerta de fechamento é do mês corrente ({@code periodo}): todo alerta ativo dele tem o
   * último dia deste mês como prazo, e some quando a apuração do mês aparece {@code FECHADO}.
   *
   * @param periodo mês corrente, {@code AAAA-MM}
   * @param pagina  limite (a tela e a Ceres nunca leem tudo)
   */
  @Query("""
      SELECT a FROM AlertaTributario a
      WHERE a.produtor.id = :produtorId
        AND a.status = br.com.agrocontabil.model.StatusAlertaTributario.ATIVO
        AND a.dataLimite >= :hoje
        AND (a.tipo <> br.com.agrocontabil.model.TipoAlertaTributario.VENCIMENTO_GUIA
          OR EXISTS (SELECT g.id FROM GuiaFiscal g
              WHERE g.id = a.referenciaId AND g.produtor.id = :produtorId
                AND g.status = br.com.agrocontabil.model.StatusGuiaFiscal.PENDENTE))
        AND (a.tipo <> br.com.agrocontabil.model.TipoAlertaTributario.PRAZO_CANCELAMENTO_NFE
          OR EXISTS (SELECT n.id FROM NotaFiscal n
              WHERE n.id = a.referenciaId AND n.produtor.id = :produtorId
                AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA))
        AND (a.tipo <> br.com.agrocontabil.model.TipoAlertaTributario.FECHAMENTO_APURACAO
          OR (a.referenciaId = :produtorId AND NOT EXISTS (SELECT m.id FROM ApuracaoMensal m
              WHERE m.produtor.id = :produtorId AND m.periodo = :periodo
                AND m.status = br.com.agrocontabil.model.StatusApuracao.FECHADO)))
      ORDER BY a.dataLimite ASC, a.tipo ASC, a.id ASC
      """)
  List<AlertaTributario> listarAtivos(
      @Param("produtorId") UUID produtorId, @Param("hoje") LocalDate hoje,
      @Param("periodo") String periodo, Pageable pagina);

  /**
   * Marca como resolvidos os alertas cujo prazo passou ou cujo motivo deixou de existir (limpeza
   * do job; a leitura já os esconde).
   *
   * @return quantos foram resolvidos
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE AlertaTributario a
      SET a.status = br.com.agrocontabil.model.StatusAlertaTributario.RESOLVIDO,
          a.resolvidoEm = :agora
      WHERE a.status = br.com.agrocontabil.model.StatusAlertaTributario.ATIVO
        AND (a.dataLimite < :hoje
          OR (a.tipo = br.com.agrocontabil.model.TipoAlertaTributario.VENCIMENTO_GUIA
            AND NOT EXISTS (SELECT g.id FROM GuiaFiscal g WHERE g.id = a.referenciaId
                AND g.status = br.com.agrocontabil.model.StatusGuiaFiscal.PENDENTE))
          OR (a.tipo = br.com.agrocontabil.model.TipoAlertaTributario.PRAZO_CANCELAMENTO_NFE
            AND NOT EXISTS (SELECT n.id FROM NotaFiscal n WHERE n.id = a.referenciaId
                AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA))
          OR (a.tipo = br.com.agrocontabil.model.TipoAlertaTributario.FECHAMENTO_APURACAO
            AND EXISTS (SELECT m.id FROM ApuracaoMensal m WHERE m.produtor.id = a.referenciaId
                AND m.periodo = :periodo
                AND m.status = br.com.agrocontabil.model.StatusApuracao.FECHADO)))
      """)
  int resolverObsoletos(@Param("hoje") LocalDate hoje, @Param("periodo") String periodo,
      @Param("agora") OffsetDateTime agora);
}
