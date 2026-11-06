package br.com.agrocontabil.config;

import br.com.agrocontabil.model.AcaoRevisao;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.NotaFiscalRevisao;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRevisaoRepository;
import br.com.agrocontabil.service.ClassificacaoTributariaService;
import br.com.agrocontabil.service.ReducaoNcmResolver;
import br.com.agrocontabil.service.TributacaoService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * <p><strong>Nota em revisão ou aprovada</strong> (#178): se o recálculo muda algum valor, o
 * contador não aprovou os números novos. A nota volta a {@code RASCUNHO} com
 * {@code APROVACAO_CADUCADA} no histórico da revisão (mesmo desenho da falha no gerar-xml), e o
 * produtor a envia de novo. Sem mudança de valor, só o tratamento aplicado é gravado.
 *
 * <p><strong>Leite (#284):</strong> também recalcula o item de leite do Anexo I, item 2, sem
 * cClassTrib informado cujo tratamento aplicado difere do que a regra dá hoje, nos dois sentidos:
 * o zero antigo nos NCMs que passaram a 60% (#374) e os 60% do #374 no UHT, que voltou a zero
 * (#380). Sem o recálculo a nota iria ao XML pela regra velha (o gerar-xml não recalcula). Item
 * com o cClassTrib informado não é tocado.
 *
 * <p>Mesmo desenho do {@link BuscaNotasLegadoRunner}: {@link SmartInitializingSingleton}, antes de
 * o Tomcat aceitar conexões; uma transação por nota, que relê a nota <strong>sob lock</strong> e
 * reconfere o status e a falta do tratamento (outra réplica pode tê-la emitido ou recalculado
 * entre a seleção e o lock); erro numa nota vai ao log com o id e não derruba a subida. Depois da
 * primeira subida, a consulta não acha nada.
 *
 * <p>Desligável por {@code app.notas.tributos.recalcular-legado} (desligado nos testes, que chamam
 * {@link #recalcularLegado()} direto).
 */
@Component
public class TributosLegadoRunner implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(TributosLegadoRunner.class);

  private static final Set<StatusNotaFiscal> NAO_EMITIDAS = Set.of(StatusNotaFiscal.RASCUNHO,
      StatusNotaFiscal.COM_INCONSISTENCIAS, StatusNotaFiscal.DEVOLVIDA,
      StatusNotaFiscal.AGUARDANDO_APROVACAO, StatusNotaFiscal.APROVADA);

  private static final Set<StatusNotaFiscal> EM_REVISAO =
      Set.of(StatusNotaFiscal.AGUARDANDO_APROVACAO, StatusNotaFiscal.APROVADA);

  private static final String SELECAO = "SELECT DISTINCT n.id, n.produtor_id FROM nota_fiscal n"
      + " JOIN nota_fiscal_item i ON i.nota_fiscal_id = n.id"
      + " WHERE (i.percentual_reducao_aplicado IS NULL"
      + " OR (i.c_class_trib IS NULL AND i.ncm IN ("
      + ClassificacaoTributariaService.LEITE_DO_ANEXO_I.stream().sorted()
          .map(n -> "'" + n + "'").collect(Collectors.joining(", ")) + ")))"
      + " AND n.status IN ('RASCUNHO', 'COM_INCONSISTENCIAS', 'DEVOLVIDA',"
      + " 'AGUARDANDO_APROVACAO', 'APROVADA')";

  static final String MOTIVO_CADUCADA =
      "Valores recalculados pela tabela oficial de reducao do IBS/CBS (IT 2025.002)";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transacao;
  private final NotaFiscalRepository notaFiscalRepository;
  private final NotaFiscalRevisaoRepository revisaoRepository;
  private final TributacaoService tributacaoService;
  private final ReducaoNcmResolver reducaoResolver;
  private final boolean recalcularNaSubida;

  public TributosLegadoRunner(JdbcTemplate jdbc, TransactionTemplate transacao,
      NotaFiscalRepository notaFiscalRepository, NotaFiscalRevisaoRepository revisaoRepository,
      TributacaoService tributacaoService, ReducaoNcmResolver reducaoResolver,
      @Value("${app.notas.tributos.recalcular-legado:true}") boolean recalcularNaSubida) {
    this.jdbc = jdbc;
    this.transacao = transacao;
    this.notaFiscalRepository = notaFiscalRepository;
    this.revisaoRepository = revisaoRepository;
    this.tributacaoService = tributacaoService;
    this.reducaoResolver = reducaoResolver;
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
    List<Map<String, Object>> notas = jdbc.queryForList(SELECAO);
    int total = 0;
    for (Map<String, Object> linha : notas) {
      total += recalcular(uuid(linha.get("id")), uuid(linha.get("produtor_id")));
    }
    if (total > 0) {
      log.info("Tabela oficial de reducao (#272): IBS/CBS recalculado em {} nota(s) nao emitida(s)",
          total);
    }
    return total;
  }

  private int recalcular(UUID id, UUID produtorId) {
    try {
      Boolean feito = transacao.execute(status ->
          notaFiscalRepository.findByIdAndProdutorIdForUpdate(id, produtorId)
              .filter(nota -> NAO_EMITIDAS.contains(nota.getStatus()))
              .filter(nota -> nota.getItens().stream().anyMatch(this::legado))
              .map(this::recalcular)
              .orElse(false));
      return Boolean.TRUE.equals(feito) ? 1 : 0;
    } catch (RuntimeException e) {
      log.error("Tabela oficial de reducao (#272): nota id={} nao foi recalculada ({}); resolver e "
          + "reiniciar", id, e.getClass().getSimpleName());
      return 0;
    }
  }

  private boolean recalcular(NotaFiscal nota) {
    List<String> antes = valores(nota);
    tributacaoService.calcular(nota);
    if (EM_REVISAO.contains(nota.getStatus()) && !antes.equals(valores(nota))) {
      revisaoRepository.save(NotaFiscalRevisao.builder()
          .notaFiscalId(nota.getId())
          .acao(AcaoRevisao.APROVACAO_CADUCADA)
          .motivo(MOTIVO_CADUCADA)
          .ocorridoEm(OffsetDateTime.now())
          .build());
      nota.setStatus(StatusNotaFiscal.RASCUNHO);
    }
    notaFiscalRepository.save(nota);
    return true;
  }

  /**
   * Tributos que o contador aprovou: IBS e CBS de cada item e os totais. A base fica de fora: a
   * #278 passou a gravá-la cheia sem mudar o tributo, e isso não derruba aprovação.
   */
  private static List<String> valores(NotaFiscal nota) {
    List<String> valores = new ArrayList<>();
    nota.getItens().forEach(item -> {
      valores.add(texto(item.getValorIbs()));
      valores.add(texto(item.getValorCbs()));
    });
    valores.add(texto(nota.getValorTotalIbs()));
    valores.add(texto(nota.getValorTotalCbs()));
    return valores;
  }

  private static String texto(BigDecimal valor) {
    return valor == null ? "" : valor.stripTrailingZeros().toPlainString();
  }

  /**
   * Item calculado por regra anterior: sem a redução aplicada (antes da tabela oficial, #272) ou
   * leite do Anexo I, item 2, sem cClassTrib informado cujo tratamento aplicado não é mais o da
   * regra (#284: mudou no #374 e no #380). Compara com o próprio resolvedor do cálculo, então
   * vale para qualquer mudança futura da regra do leite sem predicado novo.
   */
  boolean legado(NotaFiscalItem item) {
    if (item.getPercentualReducaoAplicado() == null) {
      return true;
    }
    if (item.getCClassTrib() != null || item.getPercentualReducao() != null
        || !ClassificacaoTributariaService.LEITE_DO_ANEXO_I.contains(item.getNcm())) {
      return false;
    }
    return !java.util.Objects.equals(item.getCClassTribAplicado(),
        reducaoResolver.tratamento(item).cClassTrib());
  }

  private static UUID uuid(Object valor) {
    return valor instanceof UUID uuid ? uuid : UUID.fromString(valor.toString());
  }
}
