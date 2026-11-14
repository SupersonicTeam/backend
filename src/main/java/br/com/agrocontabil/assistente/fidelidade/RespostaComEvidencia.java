package br.com.agrocontabil.assistente.fidelidade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resposta de lei no formato da conferência de fidelidade (#269): cada frase com as passagens dos
 * trechos que a sustentam, copiadas pelo modelo.
 *
 * <pre>{"frases": [{"frase": "...", "evidencias": [{"trecho": "T1", "copia": "..."}]}]}</pre>
 *
 * <p>Tolerante só no envelope (cercas de código, texto antes ou depois do objeto): o conteúdo tem de
 * vir no formato, senão a resposta inteira reprova. Função pura, sem Spring.
 *
 * @param frases frases na ordem do modelo
 */
record RespostaComEvidencia(List<Frase> frases) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Uma frase da resposta.
   *
   * @param texto      a frase como o produtor a lê (sem a evidência)
   * @param evidencias passagens que a sustentam; vazia = frase sem evidência
   */
  record Frase(String texto, List<Evidencia> evidencias) {}

  /**
   * Passagem copiada de um trecho.
   *
   * @param marca marca do trecho ({@code T1}), como o modelo escreveu
   * @param copia passagem copiada; pode vir vazia (a conferência reprova)
   */
  record Evidencia(String marca, String copia) {}

  /**
   * Lê a resposta do modelo.
   *
   * @param resposta texto do modelo
   * @return as frases; vazio se não é JSON no formato, se não tem frase ou se uma frase não tem
   *         texto
   */
  static Optional<RespostaComEvidencia> ler(String resposta) {
    if (resposta == null) {
      return Optional.empty();
    }
    int inicio = resposta.indexOf('{');
    int fim = resposta.lastIndexOf('}');
    if (inicio < 0 || fim <= inicio) {
      return Optional.empty();
    }
    JsonNode raiz;
    try {
      raiz = JSON.readTree(resposta.substring(inicio, fim + 1));
    } catch (Exception e) {
      return Optional.empty();
    }
    JsonNode lista = raiz == null ? null : raiz.get("frases");
    if (lista == null || !lista.isArray() || lista.isEmpty()) {
      return Optional.empty();
    }
    List<Frase> frases = new ArrayList<>();
    for (JsonNode item : lista) {
      String texto = textoDe(item, "frase");
      if (texto == null || texto.isBlank()) {
        return Optional.empty();
      }
      List<Evidencia> evidencias = new ArrayList<>();
      JsonNode lidas = item.get("evidencias");
      if (lidas != null && lidas.isArray()) {
        for (JsonNode e : lidas) {
          evidencias.add(new Evidencia(marcaDe(e), textoDe(e, "copia")));
        }
      }
      frases.add(new Frase(texto.strip(), List.copyOf(evidencias)));
    }
    return Optional.of(new RespostaComEvidencia(List.copyOf(frases)));
  }

  /** A marca como texto; o número sozinho ({@code "trecho": 2}) vale como "T2". */
  private static String marcaDe(JsonNode evidencia) {
    JsonNode valor = evidencia == null || !evidencia.isObject() ? null : evidencia.get("trecho");
    if (valor != null && valor.isIntegralNumber()) {
      return "T" + valor.asLong();
    }
    return textoDe(evidencia, "trecho");
  }

  private static String textoDe(JsonNode no, String campo) {
    if (no == null || !no.isObject()) {
      return null;
    }
    JsonNode valor = no.get(campo);
    return valor != null && valor.isTextual() ? valor.asText() : null;
  }
}
