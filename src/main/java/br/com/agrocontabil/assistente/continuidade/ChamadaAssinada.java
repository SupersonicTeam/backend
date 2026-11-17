package br.com.agrocontabil.assistente.continuidade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Uma chamada de função de dados executada, como vai na carga assinada (#499): o nome e os
 * argumentos em JSON canônico (chaves em ordem, sem espaços). Duas consultas são iguais quando o
 * multiconjunto destas chamadas é igual.
 *
 * @param funcao     nome da função
 * @param argumentos argumentos em JSON canônico de objeto ({@code {}} sem argumentos)
 */
public record ChamadaAssinada(String funcao, String argumentos) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Valida os campos. */
  public ChamadaAssinada {
    Objects.requireNonNull(funcao, "funcao");
    Objects.requireNonNull(argumentos, "argumentos");
  }

  /**
   * A chamada com os argumentos crus do modelo (ou do backend) em forma canônica.
   *
   * @param funcao nome da função
   * @param brutos argumentos como vieram ({@code null} ou vazio = sem argumentos)
   * @return a chamada, ou vazio se os argumentos não são um objeto JSON
   */
  public static Optional<ChamadaAssinada> de(String funcao, String brutos) {
    if (funcao == null) {
      return Optional.empty();
    }
    if (brutos == null || brutos.isBlank()) {
      return Optional.of(new ChamadaAssinada(funcao, "{}"));
    }
    try {
      JsonNode no = JSON.readTree(brutos);
      return no != null && no.isObject()
          ? Optional.of(new ChamadaAssinada(funcao, canonico(no)))
          : Optional.empty();
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }

  /**
   * A chamada a partir do nó {@code {"f", "a"}} da carga.
   *
   * @param funcao     nome
   * @param argumentos objeto de argumentos
   * @return a chamada canônica
   */
  static ChamadaAssinada deNo(String funcao, JsonNode argumentos) {
    return new ChamadaAssinada(funcao, canonico(argumentos));
  }

  /** Os argumentos como objeto JSON. */
  public ObjectNode argumentosComoObjeto() {
    try {
      JsonNode no = JSON.readTree(argumentos);
      return no instanceof ObjectNode objeto ? objeto : JsonNodeFactory.instance.objectNode();
    } catch (JsonProcessingException e) {
      return JsonNodeFactory.instance.objectNode();
    }
  }

  /** O valor de um argumento como texto ({@code null} se ausente ou nulo). */
  public String argumento(String nome) {
    JsonNode valor = argumentosComoObjeto().get(nome);
    return valor == null || valor.isNull() ? null : valor.asText();
  }

  /** JSON canônico: objetos com as chaves em ordem, recursivamente, sem espaços. */
  static String canonico(JsonNode no) {
    try {
      return JSON.writeValueAsString(ordenado(no));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON canonico", e);
    }
  }

  private static JsonNode ordenado(JsonNode no) {
    if (no.isObject()) {
      List<String> nomes = new ArrayList<>();
      Iterator<String> it = no.fieldNames();
      it.forEachRemaining(nomes::add);
      Collections.sort(nomes);
      ObjectNode saida = JsonNodeFactory.instance.objectNode();
      for (String nome : nomes) {
        saida.set(nome, ordenado(no.get(nome)));
      }
      return saida;
    }
    if (no.isArray()) {
      var saida = JsonNodeFactory.instance.arrayNode();
      no.forEach(item -> saida.add(ordenado(item)));
      return saida;
    }
    return no;
  }

  /**
   * As chamadas como multiconjunto (função e argumentos canônicos), para comparar consultas.
   *
   * @param chamadas consulta
   * @return contagem por chamada
   */
  public static Map<ChamadaAssinada, Long> multiconjunto(List<ChamadaAssinada> chamadas) {
    Map<ChamadaAssinada, Long> contagem = new java.util.HashMap<>();
    for (ChamadaAssinada c : chamadas) {
      contagem.merge(c, 1L, Long::sum);
    }
    return contagem;
  }
}
