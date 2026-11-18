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
 * confirmação reexecuta a mesma consulta, sem modelo (paga só a leitura, #511); não bate, o modelo
 * escolhe de novo.
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
 *
 * <p>Cada regra dá um de três níveis (#591): {@link Nivel#PASSA}, {@link Nivel#SEM_PROVA} (a
 * palavra não aparece na pergunta, mas nada nela contradiz a consulta: o modelo troca "adubo" por
 * "fertilizante" e o produto por NCM de propósito) e {@link Nivel#CONTRADIZ} (a pergunta diz outra
 * coisa: outra nota, outro mês, outra situação, compra no lugar de venda). {@link #bate} exige que
 * todas passem; {@link #contradiz} é a **prova de que a consulta anterior estava errada**: só ela
 * autoriza o modelo a escolher de novo e a Ceres a afirmar "corrijo".
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

  /**
   * A pergunta fala de um NCM: a palavra, o código com ponto ou o código corrido de oito dígitos.
   * Número de nota ou de CPF não conta como NCM.
   */
  private static final Pattern NCM_NA_PERGUNTA = Pattern.compile(
      "\\bncm\\b|\\d{4}\\.\\d{2}|\\b\\d{8}\\b");

  private static final Pattern NOTA_N = Pattern.compile("\\bnota (?:n |no |numero )?(\\d+)\\b");

  /** Mês nomeado na pergunta normalizada, com o ano opcional ("setembro de 2026"). */
  private static final Pattern MES_NOMEADO = Pattern.compile("\\b(janeiro|fevereiro|marco|abril"
      + "|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)(?: de (\\d{4}))?\\b");

  private static final List<String> MESES = List.of("janeiro", "fevereiro", "marco", "abril",
      "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro");

  /** O que uma regra conclui sobre a consulta, vista a partir da pergunta. */
  enum Nivel {
    /** A consulta é coerente com o que a regra lê na pergunta. */
    PASSA,
    /** O texto da pergunta não mostra a consulta, mas nada nele a contradiz. */
    SEM_PROVA,
    /** A pergunta diz explicitamente outra coisa. */
    CONTRADIZ;

    Nivel pior(Nivel outro) {
      return outro.ordinal() > ordinal() ? outro : this;
    }
  }

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
    return avaliar(pergunta, consulta, diaDoPar) == Nivel.PASSA;
  }

  /**
   * {@code true} se a pergunta contradiz a consulta: a prova de que ela estava errada (#591). A
   * palavra que não aparece (produto ou situação com outro nome, NCM) não é prova. Consulta vazia
   * ou ausente não tem o que contradizer.
   *
   * @param pergunta pergunta do par
   * @param consulta consulta assinada
   * @param diaDoPar dia do par, para o período relativo
   * @return se alguma regra encontrou contradição explícita
   */
  public static boolean contradiz(String pergunta, List<ChamadaAssinada> consulta,
      LocalDate diaDoPar) {
    if (consulta == null || consulta.isEmpty()) {
      return false;
    }
    return avaliar(pergunta, consulta, diaDoPar) == Nivel.CONTRADIZ;
  }

  /** O pior nível entre as cinco regras. */
  private static Nivel avaliar(String pergunta, List<ChamadaAssinada> consulta,
      LocalDate diaDoPar) {
    String normal = TextoContinuidade.normalizar(pergunta);
    List<String> palavras = TextoContinuidade.palavras(normal);
    return produtos(normal, palavras, consulta)
        .pior(notas(normal, consulta))
        .pior(situacoes(palavras, consulta))
        .pior(tipos(palavras, consulta))
        .pior(periodo(pergunta, consulta, diaDoPar));
  }

  static Nivel produtos(String normal, List<String> palavras, List<ChamadaAssinada> consulta) {
    String digitosPergunta = normal.replaceAll("\\D", "");
    Nivel nivel = Nivel.PASSA;
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
              // Sinônimo ("adubo" por "fertilizante") ou outro produto: o texto não distingue.
              nivel = nivel.pior(Nivel.SEM_PROVA);
            }
          }
        } else {
          String digitos = normalValor.replaceAll("\\D", "");
          if (!digitos.isEmpty() && !digitosPergunta.contains(digitos)) {
            // O NCM que o modelo escolheu para um nome não é erro; outro NCM dito na pergunta é.
            nivel = nivel.pior(NCM_NA_PERGUNTA.matcher(normal).find() ? Nivel.CONTRADIZ
                : Nivel.SEM_PROVA);
          }
        }
      }
    }
    return nivel;
  }

  static Nivel notas(String normal, List<ChamadaAssinada> consulta) {
    Set<String> consultadas = new HashSet<>();
    Nivel nivel = Nivel.PASSA;
    List<String> numerosDaPergunta = TextoContinuidade.numeros(normal).stream()
        .map(n -> n.replaceFirst("^0+(?=\\d)", "")).toList();
    for (ChamadaAssinada c : consulta) {
      if ("consultarNota".equals(c.funcao())) {
        String numero = c.argumento("numero");
        if (numero == null) {
          nivel = nivel.pior(Nivel.SEM_PROVA);
          continue;
        }
        String semZeros = numero.replaceFirst("^0+(?=\\d)", "");
        consultadas.add(semZeros);
        if (!numerosDaPergunta.contains(semZeros)) {
          // Sem número na pergunta ("nota doze") não há com o que comparar.
          nivel = nivel.pior(numerosDaPergunta.isEmpty() ? Nivel.SEM_PROVA : Nivel.CONTRADIZ);
        }
      }
    }
    Matcher m = NOTA_N.matcher(normal);
    while (m.find()) {
      if (!consultadas.contains(m.group(1).replaceFirst("^0+(?=\\d)", ""))) {
        nivel = nivel.pior(Nivel.CONTRADIZ);
      }
    }
    return nivel;
  }

  static Nivel situacoes(List<String> palavras, List<ChamadaAssinada> consulta) {
    Set<String> pedidasNaPergunta = new HashSet<>();
    for (Map.Entry<String, Set<String>> e : SITUACAO_POR_RADICAL.entrySet()) {
      if (palavras.stream().anyMatch(p -> p.startsWith(e.getKey()))) {
        pedidasNaPergunta.add(e.getKey());
      }
    }
    Set<String> situacoesConsultadas = new HashSet<>();
    boolean amplo = false;
    boolean listaNotas = false;
    Nivel nivel = Nivel.PASSA;
    for (ChamadaAssinada c : consulta) {
      if ("listarNotas".equals(c.funcao())) {
        listaNotas = true;
        String situacao = c.argumento("situacao");
        if (situacao != null && !situacao.isBlank()) {
          String s = situacao.strip().toUpperCase(java.util.Locale.ROOT);
          boolean nomeada = SITUACAO_POR_RADICAL.entrySet().stream()
              .anyMatch(e -> e.getValue().contains(s) && pedidasNaPergunta.contains(e.getKey()));
          if (!nomeada) {
            // A situação com outro nome ("deram problema") é só texto; a pergunta que nomeia
            // outra situação contradiz.
            nivel = nivel.pior(pedidasNaPergunta.isEmpty() ? Nivel.SEM_PROVA : Nivel.CONTRADIZ);
          }
          situacoesConsultadas.add(s);
        }
      } else if ("consultarNota".equals(c.funcao()) || "resumoDoProdutor".equals(c.funcao())) {
        amplo = true;
      }
    }
    if (amplo) {
      return nivel;
    }
    for (String radical : pedidasNaPergunta) {
      boolean coberta = SITUACAO_POR_RADICAL.get(radical).stream()
          .anyMatch(situacoesConsultadas::contains);
      if (!coberta) {
        // Só a lista de notas carrega a situação; "aproveitamento do crédito" casa "aprov" e a
        // consulta de crédito nunca teria o filtro.
        nivel = nivel.pior(listaNotas ? Nivel.CONTRADIZ : Nivel.SEM_PROVA);
      }
    }
    return nivel;
  }

  static Nivel tipos(List<String> palavras, List<ChamadaAssinada> consulta) {
    boolean compra = palavras.stream().anyMatch(p -> p.startsWith("compr"));
    boolean venda = palavras.stream().anyMatch(p -> p.startsWith("vend"));
    Nivel nivel = Nivel.PASSA;
    for (ChamadaAssinada c : consulta) {
      if ("listarNotas".equals(c.funcao())) {
        String tipo = c.argumento("tipo");
        // As duas palavras juntas ("a compra que entrou na venda") não dizem qual é o tipo.
        if ("SAIDA".equalsIgnoreCase(tipo) && compra) {
          nivel = nivel.pior(venda ? Nivel.SEM_PROVA : Nivel.CONTRADIZ);
        }
        if ("ENTRADA".equalsIgnoreCase(tipo) && venda) {
          nivel = nivel.pior(compra ? Nivel.SEM_PROVA : Nivel.CONTRADIZ);
        }
      }
    }
    return nivel;
  }

  static Nivel periodo(String pergunta, List<ChamadaAssinada> consulta, LocalDate diaDoPar) {
    Optional<PeriodoDaPergunta.Periodo> periodo = PeriodoDaPergunta.resolver(pergunta, diaDoPar);
    if (periodo.isEmpty()) {
      // O PeriodoDaPergunta so resolve periodo relativo; o mes nomeado ("em setembro") fica com
      // o modelo. Sem esta conferencia, a consulta de outro mes "batia" e a confirmacao a
      // reexecutava dizendo que o resultado e o mesmo.
      return mesesNomeados(pergunta, consulta) ? Nivel.PASSA : Nivel.CONTRADIZ;
    }
    return comoPeriodoAplicado(consulta, periodo.get());
  }

  /**
   * Regra 5 com mês nomeado (#499): com "setembro" (ou "setembro de 2026") na pergunta, toda
   * chamada de período cai num dos meses nomeados, no ano dito quando há ano: as datas de
   * {@code dataInicio} e {@code dataFim} e o {@code mes} das funções de mês. Chamada de período
   * sem a data ou o mês não cabe num mês nomeado, então não bate. Sem mês nomeado, passa.
   */
  static boolean mesesNomeados(String pergunta, List<ChamadaAssinada> consulta) {
    Matcher m = MES_NOMEADO.matcher(TextoContinuidade.normalizar(pergunta));
    List<int[]> nomeados = new ArrayList<>();
    while (m.find()) {
      int mes = MESES.indexOf(m.group(1)) + 1;
      int ano = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
      nomeados.add(new int[] {mes, ano});
    }
    if (nomeados.isEmpty()) {
      return true;
    }
    for (ChamadaAssinada c : consulta) {
      if (PeriodoDaPergunta.FUNCOES_DE_DATAS.contains(c.funcao())) {
        if (!dataNumMesNomeado(c.argumento("dataInicio"), nomeados)
            || !dataNumMesNomeado(c.argumento("dataFim"), nomeados)) {
          return false;
        }
      } else if (PeriodoDaPergunta.FUNCAO_APURACAO.equals(c.funcao())
          || PeriodoDaPergunta.FUNCAO_VENCIMENTOS_DO_MES.equals(c.funcao())) {
        String mes = c.argumento("mes");
        if (mes == null || !dataNumMesNomeado(mes.strip() + "-01", nomeados)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean dataNumMesNomeado(String iso, List<int[]> nomeados) {
    if (iso == null) {
      return false;
    }
    LocalDate data;
    try {
      data = LocalDate.parse(iso.strip());
    } catch (java.time.format.DateTimeParseException e) {
      return false;
    }
    return nomeados.stream().anyMatch(n -> n[0] == data.getMonthValue()
        && (n[1] == 0 || n[1] == data.getYear()));
  }

  private static Nivel comoPeriodoAplicado(List<ChamadaAssinada> consulta,
      PeriodoDaPergunta.Periodo periodo) {
    List<AssistantMessage.ToolCall> chamadas = new ArrayList<>();
    for (int i = 0; i < consulta.size(); i++) {
      ChamadaAssinada c = consulta.get(i);
      chamadas.add(new AssistantMessage.ToolCall("c" + i, "function", c.funcao(),
          c.argumentos()));
    }
    ChatResponse original = new ChatResponse(List.of(new Generation(
        AssistantMessage.builder().content("").toolCalls(chamadas).build())));
    ChatResponse ajustada = RotaDados.comPeriodo(original, periodo);
    List<ChamadaAssinada> depois = ajustada.getResults().stream()
        .flatMap(g -> g.getOutput().getToolCalls().stream())
        .map(t -> ChamadaAssinada.de(t.name(), t.arguments()).orElse(null))
        .toList();
    if (depois.contains(null)) {
      return Nivel.SEM_PROVA;
    }
    return ChamadaAssinada.multiconjunto(depois).equals(ChamadaAssinada.multiconjunto(consulta))
        ? Nivel.PASSA : Nivel.CONTRADIZ;
  }
}
