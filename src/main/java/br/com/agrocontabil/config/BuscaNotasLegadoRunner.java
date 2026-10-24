package br.com.agrocontabil.config;

import br.com.agrocontabil.common.NormalizacaoBusca;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Preenche, na subida, a {@code busca_normalizada} das notas gravadas antes do changeSet 0037
 * (issue #174).
 *
 * <p><strong>Na aplicação, não no SQL.</strong> A coluna é gravada pela {@link NormalizacaoBusca}
 * (NFD sem marcas + NFC), e um backfill em SQL com {@code translate} só cobriria as letras do mapa:
 * uma nota antiga com "å" ou "ł" ficaria com um texto diferente do que a aplicação gravaria, e a
 * busca não a acharia até alguém editá-la (achado do review). Aqui a regra é a mesma, por
 * construção.
 *
 * <p>Mesmo desenho do {@link CifragemLegadoRunner}: roda em {@link SmartInitializingSingleton},
 * antes de o Tomcat aceitar conexões; lê em lotes as linhas sem valor; a atualização só vale se a
 * linha ainda estiver sem valor ({@code AND busca_normalizada IS NULL}), então é idempotente e
 * segura entre réplicas; erro numa linha vai ao log com o id e não derruba a subida. Depois da
 * primeira subida, a consulta não acha nada.
 *
 * <p>Desligável por {@code app.notas.busca.preencher-legado} (desligado nos testes, que chamam
 * {@link #preencherLegado()} direto).
 */
@Component
public class BuscaNotasLegadoRunner implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(BuscaNotasLegadoRunner.class);

  static final int LOTE = 500;

  private static final String SELECAO = "SELECT id, numero, destinatario_nome, natureza_operacao"
      + " FROM nota_fiscal WHERE busca_normalizada IS NULL LIMIT " + LOTE;

  private static final String ATUALIZACAO = "UPDATE nota_fiscal SET busca_normalizada = ?"
      + " WHERE id = ? AND busca_normalizada IS NULL";

  private final JdbcTemplate jdbc;
  private final boolean preencherNaSubida;

  public BuscaNotasLegadoRunner(JdbcTemplate jdbc,
      @Value("${app.notas.busca.preencher-legado:true}") boolean preencherNaSubida) {
    this.jdbc = jdbc;
    this.preencherNaSubida = preencherNaSubida;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (preencherNaSubida) {
      preencherLegado();
    }
  }

  /**
   * Preenche toda nota sem {@code busca_normalizada}.
   *
   * @return quantidade de notas preenchidas
   */
  public int preencherLegado() {
    int total = 0;
    int noLote;
    List<Map<String, Object>> lote;
    do {
      lote = jdbc.queryForList(SELECAO);
      noLote = 0;
      for (Map<String, Object> linha : lote) {
        noLote += preencher(linha);
      }
      total += noLote;
      // Lote cheio de falhas voltaria igual: sem progresso, para (a proxima subida tenta de novo).
    } while (lote.size() == LOTE && noLote > 0);
    if (total > 0) {
      log.info("Busca das notas (#174): busca_normalizada preenchida em {} nota(s)", total);
    }
    return total;
  }

  private int preencher(Map<String, Object> linha) {
    try {
      return jdbc.update(ATUALIZACAO, NormalizacaoBusca.juntar(linha.get("numero"),
          linha.get("destinatario_nome"), linha.get("natureza_operacao")), linha.get("id"));
    } catch (RuntimeException e) {
      // Sem o texto e sem a excecao do banco, que traria o nome do destinatario.
      log.error("Busca das notas (#174): nota id={} ficou sem busca_normalizada ({}); resolver e "
          + "reiniciar", linha.get("id"), e.getClass().getSimpleName());
      return 0;
    }
  }
}
