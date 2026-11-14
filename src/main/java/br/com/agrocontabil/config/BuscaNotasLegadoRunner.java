package br.com.agrocontabil.config;

import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.common.NormalizacaoBusca.ItemBusca;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Preenche e recalcula a {@code busca_normalizada} das notas gravadas com uma regra anterior à
 * atual ({@link NormalizacaoBusca#VERSAO_REGRA}): as de antes do changeSet 0037 (sem texto), as
 * da regra 1, sem descrição e NCM dos itens (issues #174 e #271), e as da regra 2, que ainda
 * guardam o CPF/CNPJ do destinatário no texto (#399): reescritas, elas deixam de tê-lo.
 *
 * <p><strong>Na aplicação, não no SQL.</strong> A coluna é gravada pela {@link NormalizacaoBusca}
 * (NFD sem marcas + NFC), e um backfill em SQL com {@code translate} só cobriria as letras do mapa
 * e divergiria da regra Java. Aqui a regra é a mesma, por construção
 * ({@link NormalizacaoBusca#montarTextoDaNota}).
 *
 * <p><strong>Em segundo plano, depois de a aplicação estar pronta.</strong> Antes (#174) rodava
 * antes de o Tomcat aceitar conexões, o que era barato com poucas notas sem texto. Recalcular
 * todas as notas da regra 1 alongaria o boot, e o healthcheck do deploy pode fazer rollback de um
 * boot lento. Uma nota que ainda não foi recalculada só deixa de ser achada pelos termos novos
 * (produto) até a vez dela; nada quebra (a busca por documento já é pela coluna, #399). Roda numa thread própria, em lotes por
 * {@code id} (keyset: uma nota que falha não trava o laço), com a atualização condicionada à
 * versão ({@code AND (busca_versao IS NULL OR busca_versao < ?)}): idempotente, segura entre
 * réplicas e incapaz de sobrescrever o texto que a própria aplicação gravou entretanto. Erro numa
 * linha vai ao log com o id e não derruba nada.
 *
 * <p>Desligável por {@code app.notas.busca.preencher-legado} (desligado nos testes, que chamam
 * {@link #preencherLegado()} direto).
 */
@Component
public class BuscaNotasLegadoRunner {

  private static final Logger log = LoggerFactory.getLogger(BuscaNotasLegadoRunner.class);

  static final int LOTE = 500;

  /** De quantos em quantos lotes o progresso vai ao log. */
  private static final int LOTES_POR_LOG = 20;

  private static final UUID MENOR_ID = new UUID(0L, 0L);

  private static final String SELECAO = "SELECT id, numero, destinatario_nome, natureza_operacao"
      + " FROM nota_fiscal WHERE (busca_versao IS NULL OR busca_versao < ?) AND id > ? ORDER BY id LIMIT ?";

  private static final String ATUALIZACAO = "UPDATE nota_fiscal SET busca_normalizada = ?,"
      + " busca_versao = ? WHERE id = ? AND (busca_versao IS NULL OR busca_versao < ?)";

  private final JdbcTemplate jdbc;
  private final boolean preencherNaSubida;
  private final int lote;

  @Autowired
  public BuscaNotasLegadoRunner(JdbcTemplate jdbc,
      @Value("${app.notas.busca.preencher-legado:true}") boolean preencherNaSubida) {
    this(jdbc, preencherNaSubida, LOTE);
  }

  /** Com o tamanho do lote, para os testes cruzarem lotes com poucas notas. */
  BuscaNotasLegadoRunner(JdbcTemplate jdbc, boolean preencherNaSubida, int lote) {
    this.jdbc = jdbc;
    this.preencherNaSubida = preencherNaSubida;
    this.lote = lote;
  }

  /** Dispara o recálculo numa thread própria, sem segurar a prontidão da aplicação. */
  @EventListener(ApplicationReadyEvent.class)
  public void aoFicarPronta() {
    if (!preencherNaSubida) {
      return;
    }
    Thread fio = new Thread(() -> {
      try {
        preencherLegado();
      } catch (RuntimeException e) {
        log.error("Busca das notas (#271): recalculo interrompido ({}); a proxima subida retoma",
            e.getClass().getSimpleName());
      }
    }, "agro-busca-backfill");
    fio.setDaemon(true);
    fio.start();
  }

  /**
   * Recalcula toda nota sem {@code busca_versao} ou de versão anterior à atual.
   *
   * @return quantidade de notas atualizadas
   */
  public int preencherLegado() {
    int total = 0;
    int lotes = 0;
    UUID ultimo = MENOR_ID;
    List<Map<String, Object>> notas;
    do {
      notas = jdbc.queryForList(SELECAO, NormalizacaoBusca.VERSAO_REGRA, ultimo, lote);
      if (notas.isEmpty()) {
        break;
      }
      Map<UUID, List<ItemBusca>> itens = itensDoLote(notas);
      total += gravarLote(notas, itens);
      ultimo = (UUID) notas.get(notas.size() - 1).get("id");
      lotes++;
      if (lotes % LOTES_POR_LOG == 0) {
        log.info("Busca das notas (#271): {} nota(s) recalculadas ate agora", total);
      }
    } while (notas.size() == lote);
    if (total > 0) {
      log.info("Busca das notas (#174, #271): busca_normalizada recalculada em {} nota(s)", total);
    }
    return total;
  }

  private Map<UUID, List<ItemBusca>> itensDoLote(List<Map<String, Object>> lote) {
    String marcas = String.join(", ", java.util.Collections.nCopies(lote.size(), "?"));
    List<Object> ids = new ArrayList<>(lote.stream().map(l -> l.get("id")).toList());
    Map<UUID, List<ItemBusca>> porNota = new HashMap<>();
    jdbc.query("SELECT nota_fiscal_id, descricao, ncm FROM nota_fiscal_item"
        + " WHERE nota_fiscal_id IN (" + marcas + ") ORDER BY nota_fiscal_id, numero_item",
        rs -> {
          porNota.computeIfAbsent((UUID) rs.getObject("nota_fiscal_id"), k -> new ArrayList<>())
              .add(new ItemBusca(rs.getString("descricao"), rs.getString("ncm")));
        }, ids.toArray());
    return porNota;
  }

  /**
   * Calcula o texto de cada nota do lote (uma nota que não calcula é logada e pulada) e grava o
   * lote de uma vez, num único lote JDBC. Se o lote inteiro falhar, grava nota a nota, para uma
   * linha ruim não impedir as outras.
   */
  private int gravarLote(List<Map<String, Object>> notas, Map<UUID, List<ItemBusca>> itens) {
    List<Object[]> parametros = new ArrayList<>();
    for (Map<String, Object> linha : notas) {
      try {
        NormalizacaoBusca.TextoNota texto = NormalizacaoBusca.montarTextoDaNota(
            linha.get("numero"), (String) linha.get("destinatario_nome"),
            (String) linha.get("natureza_operacao"),
            itens.getOrDefault(linha.get("id"), List.of()));
        if (texto.itensForaDaBusca() > 0) {
          log.warn("Busca da nota {}: {} item(ns) nao couberam em {} caracteres", linha.get("id"),
              texto.itensForaDaBusca(), NormalizacaoBusca.TAMANHO_MAXIMO);
        }
        parametros.add(new Object[] {texto.texto(), NormalizacaoBusca.VERSAO_REGRA,
            linha.get("id"), NormalizacaoBusca.VERSAO_REGRA});
      } catch (RuntimeException e) {
        registrarFalha(linha.get("id"), e);
      }
    }
    try {
      return somar(jdbc.batchUpdate(ATUALIZACAO, parametros));
    } catch (RuntimeException e) {
      int gravadas = 0;
      for (Object[] p : parametros) {
        try {
          gravadas += jdbc.update(ATUALIZACAO, p);
        } catch (RuntimeException falha) {
          registrarFalha(p[2], falha);
        }
      }
      return gravadas;
    }
  }

  private static int somar(int[] resultados) {
    int total = 0;
    for (int r : resultados) {
      // Alguns drivers devolvem SUCCESS_NO_INFO (-2) em vez da contagem de linhas.
      total += r == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(r, 0);
    }
    return total;
  }

  private static void registrarFalha(Object id, RuntimeException e) {
    // Sem o texto e sem a excecao do banco, que traria o nome do destinatario.
    log.error("Busca das notas (#271): nota id={} ficou sem recalculo ({}); resolver e reiniciar",
        id, e.getClass().getSimpleName());
  }
}
