package br.com.agrocontabil.assistente.rag;

import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.config.AssistenteProperties.ParticaoCasoEspecial;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Recupera os trechos de lei que fundamentam uma pergunta (issue #156).
 *
 * <ul>
 *   <li><strong>Vigentes na data da pergunta:</strong> só a redação válida naquele dia
 *       ({@code vigenciaInicio <= data <= vigenciaFim}) — uma redação revogada não fundamenta
 *       resposta de hoje;</li>
 *   <li><strong>top-k</strong> ({@code app.assistente.rag.top-k}, padrão 5), marcados {@code T1}…
 *       {@code Tn} na ordem da busca: similaridade e, com a partição ligada, os trechos de caso
 *       especial cujo setor a pergunta não cita no fim;</li>
 *   <li><strong>limiar mínimo</strong> ({@code app.assistente.rag.similaridade-minima}): abaixo
 *       dele o trecho não entra. Sem trecho, a Ceres responde "não encontrei fundamento legal", em
 *       vez de citar lei que não trata do assunto;</li>
 *   <li><strong>partição de caso especial</strong> ({@code app.assistente.rag.particao-caso-especial},
 *       #436): ligada, pede {@link #FATOR_CANDIDATOS} vezes o {@code top-k} de candidatos, com o
 *       mesmo limiar e o mesmo filtro, e manda para o fim, numa partição estável e sem tirar nenhum
 *       nem mexer na similaridade, os trechos de faixa de caso especial ({@link CasoEspecial}) cuja
 *       palavra de setor a pergunta não cita; só então corta no {@code top-k} e numera.
 *       {@code desligado} chama a busca exatamente como antes.</li>
 * </ul>
 *
 * <p>O embedding da pergunta é local (Ollama), mas a pergunta passa pelo {@link DataSanitizer}
 * mesmo assim: a regra é a mesma para tudo que vai a um modelo.
 */
@Service
public class BuscaLegislacaoService {

  private static final Logger log = LoggerFactory.getLogger(BuscaLegislacaoService.class);

  /**
   * Candidatos buscados por trecho entregue quando a partição está ligada. Com o {@code top-k} até
   * 10 (o {@code AssistenteProperties} recusa mais), são no máximo 40.
   */
  static final int FATOR_CANDIDATOS = 4;

  private final VectorStore vectorStore;
  private final AssistenteProperties propriedades;

  /** Constrói a busca sobre o {@code PgVectorStore}. */
  public BuscaLegislacaoService(VectorStore vectorStore, AssistenteProperties propriedades) {
    this.vectorStore = vectorStore;
    this.propriedades = propriedades;
    log.info("Busca de legislacao: particao de caso especial {}",
        propriedades.getRag().getParticaoCasoEspecial());
  }

  /**
   * Busca os trechos vigentes mais próximos da pergunta.
   *
   * @param pergunta pergunta do usuário
   * @param data     data de referência (hoje, em {@code America/Sao_Paulo})
   * @return trechos acima do limiar, na ordem da busca: do mais para o menos similar e, com a
   *         partição ligada, os de caso especial sem o setor na pergunta no fim (pode ser vazia)
   * @throws AssistenteIndisponivelException se o embedding ou a base falharem
   */
  public List<TrechoRecuperado> buscar(String pergunta, LocalDate data) {
    int topK = propriedades.getRag().getTopK();
    List<CasoEspecial.Faixa> faixas = faixas(propriedades.getRag().getParticaoCasoEspecial());
    FilterExpressionBuilder f = new FilterExpressionBuilder();
    Filter.Expression vigente = f.and(
        f.lte(MetadadosTrecho.VIGENCIA_INICIO, data.toString()),
        f.gte(MetadadosTrecho.VIGENCIA_FIM, data.toString())).build();
    SearchRequest requisicao = SearchRequest.builder()
        .query(DataSanitizer.mascarar(pergunta))
        .topK(faixas.isEmpty() ? topK : topK * FATOR_CANDIDATOS)
        .similarityThreshold(propriedades.getRag().getSimilaridadeMinima())
        .filterExpression(vigente)
        .build();

    List<Document> documentos;
    try {
      documentos = vectorStore.similaritySearch(requisicao);
    } catch (RuntimeException e) {
      log.warn("Falha na busca de legislacao: {}", e.getClass().getName());
      throw new AssistenteIndisponivelException(
          "Falha na busca de legislacao: " + e.getClass().getSimpleName(), e);
    }
    List<Document> ordem = documentos == null ? List.of() : documentos;
    if (!faixas.isEmpty()) {
      ordem = particionar(ordem, pergunta, faixas, topK);
    }

    List<TrechoRecuperado> trechos = new ArrayList<>();
    for (Document documento : ordem) {
      Map<String, Object> m = documento.getMetadata();
      trechos.add(new TrechoRecuperado(
          "T" + (trechos.size() + 1),
          documento.getId(),
          String.valueOf(m.get(MetadadosTrecho.NORMA)),
          String.valueOf(m.get(MetadadosTrecho.DISPOSITIVO)),
          String.valueOf(m.get(MetadadosTrecho.URL)),
          documento.getText(),
          documento.getScore() == null ? 0 : documento.getScore()));
    }
    return trechos;
  }

  /** As faixas da variante; vazia com a partição desligada. */
  static List<CasoEspecial.Faixa> faixas(ParticaoCasoEspecial particao) {
    return switch (particao) {
      case DESLIGADO -> List.of();
      case REGIMES_ESPECIFICOS -> CasoEspecial.REGIMES_ESPECIFICOS;
      case AMPLIADA -> CasoEspecial.AMPLIADA;
    };
  }

  /**
   * Partição estável dos candidatos (#436): primeiro, na ordem em que vieram, os que não são de
   * caso especial ou cujo setor a pergunta cita; depois, também na ordem, os de caso especial sem o
   * setor na pergunta. Nenhum sai e a similaridade não muda; o corte em {@code limite} vem depois
   * da partição.
   *
   * @param candidatos candidatos da busca, do mais para o menos similar
   * @param pergunta   pergunta como o usuário escreveu (normalizada aqui)
   * @param faixas     faixas de caso especial da variante
   * @param limite     {@code top-k}
   * @return até {@code limite} candidatos, na ordem da partição
   */
  static List<Document> particionar(List<Document> candidatos, String pergunta,
      List<CasoEspecial.Faixa> faixas, int limite) {
    String simples = NormalizacaoBusca.normalizar(pergunta);
    List<Document> primeiro = new ArrayList<>();
    List<Document> fim = new ArrayList<>();
    for (Document documento : candidatos) {
      Map<String, Object> m = documento.getMetadata();
      boolean rebaixar = CasoEspecial.casoEspecialSemSetor(faixas,
          String.valueOf(m.get(MetadadosTrecho.NORMA)),
          String.valueOf(m.get(MetadadosTrecho.DISPOSITIVO)), simples);
      (rebaixar ? fim : primeiro).add(documento);
    }
    primeiro.addAll(fim);
    return List.copyOf(primeiro.subList(0, Math.min(limite, primeiro.size())));
  }
}
