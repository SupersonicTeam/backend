package br.com.agrocontabil.support;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * {@link EmbeddingModel} determinístico do profile {@code test} (issue #141).
 *
 * <p>Mesma dimensão do {@code bge-m3} ({@value #DIMENSOES}). Cada palavra (sem acento, minúscula)
 * cai num dos {@value #DIMENSOES} baldes pelo hash; o vetor é normalizado. Textos que compartilham
 * palavras ficam próximos no cosseno — o bastante para testar busca por similaridade sem modelo.
 */
public class EmbeddingModelSimulado implements EmbeddingModel {

  /** Dimensão do {@code bge-m3}, a mesma da coluna {@code vector(1024)}. */
  public static final int DIMENSOES = 1024;

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<Embedding> embeddings = new ArrayList<>();
    List<String> textos = request.getInstructions();
    for (int i = 0; i < textos.size(); i++) {
      embeddings.add(new Embedding(vetor(textos.get(i)), i));
    }
    return new EmbeddingResponse(embeddings);
  }

  @Override
  public float[] embed(Document document) {
    return vetor(document.getText());
  }

  @Override
  public int dimensions() {
    return DIMENSOES;
  }

  /** Vetor de "saco de palavras" com hashing, normalizado (norma 1). */
  public static float[] vetor(String texto) {
    float[] v = new float[DIMENSOES];
    if (texto == null) {
      return v;
    }
    String normalizado = Normalizer.normalize(texto, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
    for (String palavra : normalizado.split("[^a-z0-9]+")) {
      if (!palavra.isEmpty()) {
        v[Math.floorMod(palavra.hashCode(), DIMENSOES)] += 1f;
      }
    }
    double norma = 0;
    for (float x : v) {
      norma += x * x;
    }
    if (norma > 0) {
      float fator = (float) (1 / Math.sqrt(norma));
      for (int i = 0; i < v.length; i++) {
        v[i] *= fator;
      }
    }
    return v;
  }
}
