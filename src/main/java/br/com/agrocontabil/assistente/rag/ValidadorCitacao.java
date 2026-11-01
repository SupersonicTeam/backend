package br.com.agrocontabil.assistente.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Garante em código que toda resposta de legislação da Ceres cita a lei de verdade (issue #156).
 *
 * <p>O modelo só escreve marcas {@code [T1]}…{@code [Tn]} apontando para os trechos recuperados.
 * Este validador:
 * <ol>
 *   <li>exige <strong>pelo menos uma marca</strong>;</li>
 *   <li>exige que <strong>toda marca exista</strong> entre os trechos recuperados — marca inventada
 *       ({@code [T9]} com cinco trechos) derruba a resposta inteira;</li>
 *   <li>troca cada marca pela referência montada <strong>a partir dos metadados</strong> do trecho
 *       ("LC 214/2025, art. 138, § 1º") e devolve as citações com link oficial. O modelo nunca
 *       escreve a referência legal, então não consegue inventar artigo.</li>
 * </ol>
 * Falhou: a resposta é descartada e vira "não encontrei fundamento legal para isso".
 *
 * <p>Aceita marcas agrupadas ({@code [T1, T3]} ou {@code [T1; T3]}).
 */
@Component
public class ValidadorCitacao {

  /** Texto fixo quando não há fundamento legal validado. */
  public static final String SEM_FUNDAMENTO = "Nao encontrei fundamento legal para isso.";

  private static final Pattern MARCA =
      Pattern.compile("\\[\\s*(T\\d+(?:\\s*[,;]\\s*T\\d+)*)\\s*\\]");
  private static final Pattern NUMERO_MARCA = Pattern.compile("T(\\d+)");

  /**
   * Resultado da validação.
   *
   * @param valida    {@code true} se todas as marcas existem e há ao menos uma
   * @param texto     resposta com as referências no lugar das marcas; {@link #SEM_FUNDAMENTO} se
   *                  inválida
   * @param citacoes  trechos citados, na ordem da primeira menção
   * @param idsCitados ids dos trechos citados (auditoria)
   */
  public record Resultado(
      boolean valida, String texto, List<CitacaoLegal> citacoes, List<String> idsCitados) {}

  /**
   * Valida e resolve as marcas de citação.
   *
   * @param resposta texto do modelo
   * @param trechos  trechos recuperados, na ordem das marcas ({@code T1} = primeiro)
   * @return resposta com referências e citações, ou inválida
   */
  public Resultado validar(String resposta, List<TrechoRecuperado> trechos) {
    if (resposta == null || trechos.isEmpty()) {
      return invalida();
    }
    Map<Integer, TrechoRecuperado> citados = new LinkedHashMap<>();
    StringBuilder saida = new StringBuilder();
    Matcher m = MARCA.matcher(resposta);
    int marcas = 0;
    while (m.find()) {
      marcas++;
      List<String> referencias = new ArrayList<>();
      Matcher numero = NUMERO_MARCA.matcher(m.group(1));
      while (numero.find()) {
        int indice = Integer.parseInt(numero.group(1));
        if (indice < 1 || indice > trechos.size()) {
          return invalida();
        }
        TrechoRecuperado trecho = trechos.get(indice - 1);
        citados.putIfAbsent(indice, trecho);
        referencias.add(trecho.comoCitacao().referencia());
      }
      m.appendReplacement(saida, Matcher.quoteReplacement("(" + String.join("; ", referencias) + ")"));
    }
    if (marcas == 0) {
      return invalida();
    }
    m.appendTail(saida);
    return new Resultado(
        true,
        saida.toString().strip(),
        citados.values().stream().map(TrechoRecuperado::comoCitacao).toList(),
        citados.values().stream().map(TrechoRecuperado::id).toList());
  }

  /**
   * As citações das marcas que existem, mesmo numa resposta inválida (issue #258). Uma resposta
   * com uma marca inventada é descartada inteira, mas os trechos que o modelo marcou certo ainda
   * são os artigos que tratam do assunto e podem ser mostrados sem o texto dele.
   *
   * @param resposta texto do modelo
   * @param trechos  trechos recuperados, na ordem das marcas
   * @return citações e ids das marcas válidas, na ordem da primeira menção (vazias se não há)
   */
  public Resultado marcasValidas(String resposta, List<TrechoRecuperado> trechos) {
    if (resposta == null || trechos.isEmpty()) {
      return invalida();
    }
    Map<Integer, TrechoRecuperado> citados = new LinkedHashMap<>();
    Matcher m = MARCA.matcher(resposta);
    while (m.find()) {
      Matcher numero = NUMERO_MARCA.matcher(m.group(1));
      while (numero.find()) {
        int indice = Integer.parseInt(numero.group(1));
        if (indice >= 1 && indice <= trechos.size()) {
          citados.putIfAbsent(indice, trechos.get(indice - 1));
        }
      }
    }
    return new Resultado(false, SEM_FUNDAMENTO,
        citados.values().stream().map(TrechoRecuperado::comoCitacao).toList(),
        citados.values().stream().map(TrechoRecuperado::id).toList());
  }

  private static Resultado invalida() {
    return new Resultado(false, SEM_FUNDAMENTO, List.of(), List.of());
  }
}
