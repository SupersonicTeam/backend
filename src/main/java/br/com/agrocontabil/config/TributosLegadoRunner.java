package br.com.agrocontabil.config;

import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.TributacaoService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recalcula, na subida, o IBS/CBS das notas <strong>ainda não emitidas</strong> calculadas antes
 * da tabela oficial de redução (changeSet {@code 0048}, issue #272).
 *
 * <p>O cálculo roda só ao criar e ao editar a nota. Um rascunho gravado com a redução hipotética
 * do {@code 0012} (arroz a 60% em vez de zero, soja sem redução) iria assim para o XML. Aqui ele é
 * recalculado pela regra atual, uma vez: a nota com item sem {@code percentual_reducao_aplicado}
 * (que o cálculo novo sempre grava) é a que nunca passou por ele. Notas emitidas (de
 * {@code XML_GERADO} em diante) não mudam: o XML assinado é o documento fiscal.
 *
 * <p>Mesmo desenho do {@link BuscaNotasLegadoRunner}: {@link SmartInitializingSingleton}, antes de
 * o Tomcat aceitar conexões; uma transação por nota; erro numa nota vai ao log com o id e não
 * derruba a subida. Depois da primeira subida, a consulta não acha nada.
 *
 * <p>Desligável por {@code app.notas.tributos.recalcular-legado} (desligado nos testes, que chamam
 * {@link #recalcularLegado()} direto).
 */
@Component
public class TributosLegadoRunner implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(TributosLegadoRunner.class);

  private static final String SELECAO = "SELECT DISTINCT n.id FROM nota_fiscal n"
      + " JOIN nota_fiscal_item i ON i.nota_fiscal_id = n.id"
      + " WHERE i.percentual_reducao_aplicado IS NULL"
      + " AND n.status IN ('RASCUNHO', 'COM_INCONSISTENCIAS', 'DEVOLVIDA',"
      + " 'AGUARDANDO_APROVACAO', 'APROVADA')";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transacao;
  private final NotaFiscalRepository notaFiscalRepository;
  private final TributacaoService tributacaoService;
  private final boolean recalcularNaSubida;

  public TributosLegadoRunner(JdbcTemplate jdbc, TransactionTemplate transacao,
      NotaFiscalRepository notaFiscalRepository, TributacaoService tributacaoService,
      @Value("${app.notas.tributos.recalcular-legado:true}") boolean recalcularNaSubida) {
    this.jdbc = jdbc;
    this.transacao = transacao;
    this.notaFiscalRepository = notaFiscalRepository;
    this.tributacaoService = tributacaoService;
    this.recalcularNaSubida = recalcularNaSubida;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (recalcularNaSubida) {
      recalcularLegado();
    }
  }

  /**
   * Recalcula toda nota não emitida com item sem a redução aplicada gravada.
   *
   * @return quantidade de notas recalculadas
   */
  public int recalcularLegado() {
    List<UUID> ids = jdbc.queryForList(SELECAO, UUID.class);
    int total = 0;
    for (UUID id : ids) {
      total += recalcular(id);
    }
    if (total > 0) {
      log.info("Tabela oficial de reducao (#272): IBS/CBS recalculado em {} nota(s) nao emitida(s)",
          total);
    }
    return total;
  }

  private int recalcular(UUID id) {
    try {
      Boolean feito = transacao.execute(status -> notaFiscalRepository.findById(id)
          .map(nota -> {
            tributacaoService.calcular(nota);
            notaFiscalRepository.save(nota);
            return true;
          })
          .orElse(false));
      return Boolean.TRUE.equals(feito) ? 1 : 0;
    } catch (RuntimeException e) {
      log.error("Tabela oficial de reducao (#272): nota id={} nao foi recalculada ({}); resolver e "
          + "reiniciar", id, e.getClass().getSimpleName());
      return 0;
    }
  }
}
