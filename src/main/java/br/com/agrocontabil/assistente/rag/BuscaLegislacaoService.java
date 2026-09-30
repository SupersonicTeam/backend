package br.com.agrocontabil.assistente.rag;

import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.config.AssistenteProperties;
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
 *       {@code Tn} na ordem de similaridade;</li>
 *   <li><strong>limiar mínimo</strong> ({@code app.assistente.rag.similaridade-minima}): abaixo
 *       dele o trecho não entra. Sem trecho, a Ceres responde "não encontrei fundamento legal", em
 *       vez de citar lei que não trata do assunto.</li>
 * </ul>
 *
 * <p>O embedding da pergunta é local (Ollama), mas a pergunta passa pelo {@link DataSanitizer}
 * mesmo assim: a regra é a mesma para tudo que vai a um modelo.
 */
@Service
public class BuscaLegislacaoService {

  private static final Logger log = LoggerFactory.getLogger(BuscaLegislacaoService.class);

  private final VectorStore vectorStore;
  private final AssistenteProperties propriedades;

  /** Constrói a busca sobre o {@code PgVectorStore}. */
  public BuscaLegislacaoService(VectorStore vectorStore, AssistenteProperties propriedades) {
    this.vectorStore = vectorStore;
    this.propriedades = propriedades;
  }

  /**
   * Busca os trechos vigentes mais próximos da pergunta.
   *
   * @param pergunta pergunta do usuário
   * @param data     data de referência (hoje, em {@code America/Sao_Paulo})
   * @return trechos acima do limiar, do mais para o menos similar (pode ser vazia)
   * @throws AssistenteIndisponivelException se o embedding ou a base falharem
   */
  public List<TrechoRecuperado> buscar(String pergunta, LocalDate data) {
    FilterExpressionBuilder f = new FilterExpressionBuilder();
    Filter.Expression vigente = f.and(
        f.lte(MetadadosTrecho.VIGENCIA_INICIO, data.toString()),
        f.gte(MetadadosTrecho.VIGENCIA_FIM, data.toString())).build();
    SearchRequest requisicao = SearchRequest.builder()
        .query(DataSanitizer.mascarar(pergunta))
        .topK(propriedades.getRag().getTopK())
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

    List<TrechoRecuperado> trechos = new ArrayList<>();
    for (Document documento : documentos == null ? List.<Document>of() : documentos) {
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
}
