package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.PeriodoDaPergunta;
import br.com.agrocontabil.assistente.RotaDados;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Confere, sem modelo, se a consulta assinada de um par bate com a pergunta dele (#499). Bate, a
 * confirmação reexecuta a mesma consulta (custo zero); não bate, o modelo escolhe de novo.
 *
 * <ol>
 *   <li>todo produto ou insumo com letras aparece na pergunta (prefixo de 5 letras); com dígitos,
 *       os dígitos aparecem;</li>
 *   <li>todo número de {@code consultarNota} aparece na pergunta, e todo "nota N" da pergunta tem
 *       {@code consultarNota(N)};</li>
 *   <li>a situação de {@code listarNotas} tem a palavra dela na pergunta, e palavra de situação na
 *       pergunta exige {@code listarNotas} com essa situação, {@code consultarNota} ou
 *       {@code resumoDoProdutor};</li>
 *   <li>{@code SAIDA} não convive com compra, nem {@code ENTRADA} com venda;</li>
 *   <li>com período na pergunta (resolvido no dia do par), a consulta passada pelo período não
 *       muda.</li>
 * </ol>
 *
 * <p>Funções sem argumento passam. O erro de função não é pego aqui: a abertura diz o que foi
 * consultado, e o produtor pode contestar.
 */
public final class ConferenciaConsulta {

  private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

  /** Radical na pergunta → situações de nota que ele nomeia. */
  static final Map<String, Set<String>> SITUACAO_POR_RADICAL = Map.of(
      "rejeit", Set.of("REJEITADA"),
      "devolv", Set.of("DEVOLVIDA"),
      "cancel", Set.of("CANCELADA", "CANCELANDO"),
      "autoriz", Set.of("AUTORIZADA"),
      "rascunho", Set.of("RASCUNHO"),
      "inconsist", Set.of("COM_INCONSISTENCIAS"),
      "aprov", Set.of("APROVADA", "AGUARDANDO_APROVACAO"),
      "revis", Set.of("AGUARDANDO_APROVACAO"));

  private static final Pattern NOTA_N = Pattern.compile("\\bnota (?:n |no |numero )?(\\d+)\\b");

  private ConferenciaConsulta() {
  }

  /**
   * O dia do par em Brasília: {@code exp} menos a validade (com {@code exp} herdado, o dia do par
   * de origem).
   *
   * @param exp validade do par, em segundos
   * @return o dia em que o par foi emitido
   */
  public static LocalDate diaDoPar(long exp) {
    return Instant.ofEpochSecond(exp).minus(ConferenciaHistorico.VALIDADE)
        .atZone(BRASILIA).toLocalDate();
  }

  /**
   * {@code true} se a consulta bate com a pergunta.
   *
   * @param pergunta pergunta do par
   * @param consulta consulta assinada
   * @param diaDoPar dia do par, para o período relativo
   * @return se as cinco regras passam
   */
  public static boolean bate(String pergunta, List<ChamadaAssinada> consulta, LocalDate diaDoPar) {
    if (consulta == null || consulta.isEmpty()) {
      return false;
    }
    String normal = TextoContinuidade.normalizar(pergunta);
    List<String> palavras = TextoContinuidade.palavras(normal);
    return produtos(normal, palavras, consulta) && notas(normal, consulta)
        && situacoes(palavras, consulta) && tipos(palavras, consulta)
        && periodo(pergunta, consulta, diaDoPar);
  }

  static boolean produtos(String normal, List<String> palavras, List<ChamadaAssinada> consulta) {
    String digitosPergunta = normal.replaceAll("\\D", "");
    for (ChamadaAssinada c : consulta) {
      for (String nome : List.of("descricaoOuNcm", "produtoOuNcm")) {
        String valor = c.argumento(nome);
        if (valor == null || valor.isBlank()) {
          continue;
        }
        String normalValor = TextoContinuidade.normalizar(valor);
        if (normalValor.chars().anyMatch(Character::isLetter)) {
          for (String palavra : TextoContinuidade.palavras(normalValor)) {
            if (palavra.length() >= 3 && !TextoContinuidade.VAZIAS.contains(palavra)
                && !TextoContinuidade.numero(palavra)
                && !TextoContinuidade.contemPorPrefixo(palavras, palavra)) {
              return false;
            }
          }
        } else {
          String digitos = normalValor.replaceAll("\\D", "");
          if (!digitos.isEmpty() && !digitosPergunta.contains(digitos)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  static boolean notas(String normal, List<ChamadaAssinada> consulta) {
    Set<String> consultadas = new HashSet<>();
    for (ChamadaAssinada c : consulta) {
      if ("consultarNota".equals(c.funcao())) {
        String numero = c.argumento("numero");
        if (numero == null) {
          return false;
        }
        String semZeros = numero.replaceFirst("^0+(?=\\d)", "");
        consultadas.add(semZeros);
        if (!TextoContinuidade.numeros(normal).stream()
            .map(n -> n.replaceFirst("^0+(?=\\d)", "")).toList().contains(semZeros)) {
          return false;
        }
      }
    }
    Matcher m = NOTA_N.matcher(normal);
    while (m.find()) {
      if (!consultadas.contains(m.group(1).replaceFirst("^0+(?=\\d)", ""))) {
        return false;
      }
    }
    return true;
  }

  static boolean situacoes(List<String> palavras, List<ChamadaAssinada> consulta) {
    Set<String> pedidasNaPergunta = new HashSet<>();
    for (Map.Entry<String, Set<String>> e : SITUACAO_POR_RADICAL.entrySet()) {
      if (palavras.stream().anyMatch(p -> p.startsWith(e.getKey()))) {
        pedidasNaPergunta.add(e.getKey());
      }
    }
    Set<String> situacoesConsultadas = new HashSet<>();
    boolean amplo = false;
    for (ChamadaAssinada c : consulta) {
      if ("listarNotas".equals(c.funcao())) {
        String situacao = c.argumento("situacao");
        if (situacao != null && !situacao.isBlank()) {
          String s = situacao.strip().toUpperCase(java.util.Locale.ROOT);
          boolean nomeada = SITUACAO_POR_RADICAL.entrySet().stream()
              .anyMatch(e -> e.getValue().contains(s) && pedidasNaPergunta.contains(e.getKey()));
          if (!nomeada) {
            return false;
          }
          situacoesConsultadas.add(s);
        }
      } else if ("consultarNota".equals(c.funcao()) || "resumoDoProdutor".equals(c.funcao())) {
        amplo = true;
      }
    }
    if (amplo) {
      return true;
    }
    for (String radical : pedidasNaPergunta) {
      boolean coberta = SITUACAO_POR_RADICAL.get(radical).stream()
          .anyMatch(situacoesConsultadas::contains);
      if (!coberta) {
        return false;
      }
    }
    return true;
  }

  static boolean tipos(List<String> palavras, List<ChamadaAssinada> consulta) {
    boolean compra = palavras.stream().anyMatch(p -> p.startsWith("compr"));
    boolean venda = palavras.stream().anyMatch(p -> p.startsWith("vend"));
    for (ChamadaAssinada c : consulta) {
      if ("listarNotas".equals(c.funcao())) {
        String tipo = c.argumento("tipo");
        if ("SAIDA".equalsIgnoreCase(tipo) && compra) {
          return false;
        }
        if ("ENTRADA".equalsIgnoreCase(tipo) && venda) {
          return false;
        }
      }
    }
    return true;
  }

  static boolean periodo(String pergunta, List<ChamadaAssinada> consulta, LocalDate diaDoPar) {
    Optional<PeriodoDaPergunta.Periodo> periodo = PeriodoDaPergunta.resolver(pergunta, diaDoPar);
    if (periodo.isEmpty()) {
      return true;
    }
    List<AssistantMessage.ToolCall> chamadas = new ArrayList<>();
    for (int i = 0; i < consulta.size(); i++) {
      ChamadaAssinada c = consulta.get(i);
      chamadas.add(new AssistantMessage.ToolCall("c" + i, "function", c.funcao(),
          c.argumentos()));
    }
    ChatResponse original = new ChatResponse(List.of(new Generation(
        AssistantMessage.builder().content("").toolCalls(chamadas).build())));
    ChatResponse ajustada = RotaDados.comPeriodo(original, periodo.get());
    List<ChamadaAssinada> depois = ajustada.getResults().stream()
        .flatMap(g -> g.getOutput().getToolCalls().stream())
        .map(t -> ChamadaAssinada.de(t.name(), t.arguments()).orElse(null))
        .toList();
    if (depois.contains(null)) {
      return false;
    }
    return ChamadaAssinada.multiconjunto(depois).equals(ChamadaAssinada.multiconjunto(consulta));
  }
}
