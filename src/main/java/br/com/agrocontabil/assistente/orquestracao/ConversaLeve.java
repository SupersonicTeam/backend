package br.com.agrocontabil.assistente.orquestracao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Conversa leve com a Ceres (#493): saudação, agradecimento, despedida, reconhecimento, pedido de
 * apresentação e pedido de seguimento ("tem certeza?"), respondidos pelo backend, sem modelo e sem
 * crédito. Mesmo molde dos {@link AtalhosFixos}: lista fechada, mensagem inteira, determinística.
 *
 * <p>A mensagem só é conversa leve se, depois de normalizada ({@link RegrasClassificacao}, sem
 * acento, minúsculas e sem pontuação), com as letras repetidas juntadas ("oiii", "valeuuu") e sem
 * o vocativo "ceres", sobrar uma sequência de expressões da lista (com "e" opcional entre elas),
 * de no máximo 12 palavras. Qualquer outra palavra ("oi, quanto vendi de soja?") manda a mensagem
 * para o fluxo normal.
 *
 * <p>A resposta é montada com partes fixas: o cumprimento (só com saudação), o estado ("Tudo certo
 * por aqui, obrigada." quando perguntam como ela está), a abertura do tipo e o corpo. O corpo é
 * uma das variantes do tipo, escolhida pelo {@link String#hashCode()} da mensagem normalizada: o
 * algoritmo está especificado no Javadoc do {@link String}, então a mesma mensagem dá o mesmo
 * texto em qualquer JVM, sem {@code Random} nem relógio.
 *
 * <p><strong>Histórico.</strong> Quando a continuidade da conversa entrar, o SEGUIMENTO e as
 * saudações ambíguas ("e aí", "como está", "está aí", "tá aí", "tudo bem") só valem sem histórico
 * válido: com histórico, elas dependem da mensagem anterior.
 */
public final class ConversaLeve {

  /** O que a mensagem pede, na ordem de precedência quando ela mistura tipos (o primeiro vence). */
  public enum Tipo { DESPEDIDA, AGRADECIMENTO, SEGUIMENTO, APRESENTACAO, SAUDACAO, RECONHECIMENTO }

  /** Uma mensagem reconhecida: o tipo que vence e o texto da resposta. */
  public record Resposta(Tipo tipo, String texto) {}

  static final int MAXIMO_PALAVRAS = 12;

  /** Antes de todo conjunto normalizado: os campos estáticos iniciam na ordem do texto. */
  private static final Pattern LETRAS_REPETIDAS = Pattern.compile("([a-z])\\1+");

  private static final List<String> SAUDACOES = List.of("oi", "oie", "ola", "opa", "e ai", "eai",
      "salve", "fala", "buenas", "bom dia", "boa tarde", "boa noite", "tudo bem", "tudo bom",
      "td bem", "td bom", "tudo joia", "tudo tranquilo", "tudo beleza", "tudo bao", "bao",
      "como vai", "como vai voce", "como voce esta", "como vc esta", "como voce ta", "como vc ta",
      "como esta", "tudo bem com voce", "tudo bem com vc", "voce esta ai", "vc esta ai",
      "esta ai", "ta ai");

  private static final List<String> AGRADECIMENTOS = List.of("obrigado", "obrigada", "obrigadao",
      "brigado", "brigada", "brigadao", "obg", "obgd", "valeu", "vlw", "valeu demais", "grato",
      "grata", "agradeco", "agradecido", "agradecida", "gratidao", "deus lhe pague",
      "deus te pague", "muito obrigado", "muito obrigada");

  /** Complementos do agradecimento ("obrigada pela ajuda", "obrigado mesmo"): só depois dele. */
  private static final List<String> COMPLEMENTOS = List.of("muito", "mesmo", "demais",
      "pela ajuda", "pela informacao", "pelas respostas", "ajudou muito");

  private static final List<String> DESPEDIDAS = List.of("tchau", "tchauzinho", "xau", "ate mais",
      "ate logo", "ate depois", "ate amanha", "ate a proxima", "ate breve", "falou", "flw",
      "abraco", "um abraco", "abracos", "abs", "fica com deus", "vai com deus", "inte");

  private static final List<String> RECONHECIMENTOS = List.of("ok", "okay", "certo", "entendi",
      "entendido", "beleza", "blz", "show", "perfeito", "ta bom", "ta certo", "ah ta",
      "combinado", "joia", "tranquilo", "legal", "otimo", "isso mesmo");

  /** "Quem é você", "o que faz", "como ajuda": a Ceres se apresenta. */
  private static final List<String> APRESENTACOES_QUEM = List.of("quem e voce", "quem e vc",
      "o que voce faz", "o que vc faz", "o que voce sabe fazer", "o que vc sabe fazer",
      "o que voce pode fazer", "como voce pode me ajudar", "como vc pode me ajudar");

  /** Pedido de ajuda, dúvida ou pergunta: a Ceres convida a perguntar. */
  private static final List<String> APRESENTACOES_PEDIDO = List.of("ajuda", "me ajuda",
      "preciso de ajuda", "tenho uma duvida", "tenho uma pergunta", "uma duvida",
      "posso fazer uma pergunta", "posso perguntar");

  private static final List<String> SEGUIMENTOS = List.of("tem certeza", "certeza", "serio",
      "e serio", "por que", "porque", "como assim", "explica melhor", "explica de novo",
      "explique melhor", "repete", "repita", "de novo", "confere de novo", "confira de novo",
      "confirma", "e entao", "e agora", "nao entendi");

  /** Reconhecimento que, com "?", pede confirmação da resposta anterior: seguimento. */
  private static final Set<String> CONFIRMACAO_COM_INTERROGACAO =
      normalizadas(List.of("certo", "ta certo", "isso mesmo"));

  /** Saudação que pergunta como ela está. */
  private static final Set<String> COMO_ESTA = normalizadas(List.of("tudo bem", "tudo bom",
      "td bem", "td bom", "tudo joia", "tudo tranquilo", "tudo beleza", "tudo bao", "bao",
      "como vai", "como vai voce", "como voce esta", "como vc esta", "como voce ta", "como vc ta",
      "como esta", "tudo bem com voce", "tudo bem com vc"));

  /** Saudação que pergunta se ela está ali. */
  private static final Set<String> ESTA_AI = normalizadas(List.of("voce esta ai", "vc esta ai",
      "esta ai", "ta ai"));

  private static final Set<String> QUEM_NORMALIZADAS = normalizadas(APRESENTACOES_QUEM);

  static final String SAUDACAO_0 = "Sou a Ceres, a assistente fiscal do AgroConIA."
      + " Consulto as suas notas, guias e prazos e explico a reforma tributária do agronegócio."
      + "\n\nVocê pode perguntar, por exemplo:\n- Tenho nota rejeitada?\n"
      + "- Ficou alguma guia sem pagar?";

  static final String SAUDACAO_1 = "Posso consultar as suas notas, guias e prazos ou explicar uma"
      + " regra da reforma tributária do agronegócio.\n\nExperimente perguntar:\n"
      + "- Como estão as minhas pendências?\n- Tenho imposto para pagar na semana que vem?";

  static final String SAUDACAO_2 = "Posso olhar as suas notas, guias e prazos no AgroConIA e"
      + " também explicar a reforma tributária do agronegócio.\n\nUm bom começo:\n"
      + "- Tenho nota rejeitada?\n- Tenho algum prazo perto?";

  static final String AGRADECIMENTO_0 = "Por nada! Se surgir outra dúvida sobre as suas notas,"
      + " guias ou a reforma tributária, é só perguntar.";

  static final String AGRADECIMENTO_1 = "Disponha! Quando precisar, estou por aqui.";

  static final String DESPEDIDA_0 = "Até mais! Quando voltar, posso conferir as suas guias e"
      + " prazos ou explicar a reforma tributária.";

  static final String DESPEDIDA_1 = "Tchau, bom trabalho! Sempre que precisar, é só me chamar.";

  static final String RECONHECIMENTO_0 = "Combinado! Se surgir outra dúvida, é só perguntar.";

  static final String RECONHECIMENTO_1 = "Certo! Estou por aqui se precisar.";

  static final String SEGUIMENTO = "Por enquanto eu não leio as mensagens anteriores da"
      + " conversa, então não sei a que você se refere. Escreva a pergunta completa que eu confiro"
      + " de novo. Por exemplo:\n- Tenho nota rejeitada?\n- Ficou alguma guia sem pagar?";

  static final String BOA_NOITE_SOZINHO = "Boa noite! Se precisar de algo, é só perguntar.";

  static final String ESTADO_COMO_ESTA = "Tudo certo por aqui, obrigada.";
  static final String ESTADO_ESTA_AI = "Estou aqui, sim.";
  static final String ABERTURA_QUEM = "Sou a Ceres, a assistente fiscal do AgroConIA.";
  static final String ABERTURA_PEDIDO = "Claro, pode perguntar!";
  static final String ABERTURA_POR_NADA = "Por nada!";

  /** As variantes do corpo de cada tipo (o SEGUIMENTO tem uma só). */
  static final Map<Tipo, List<String>> CORPOS = Map.of(
      Tipo.SAUDACAO, List.of(SAUDACAO_0, SAUDACAO_1, SAUDACAO_2),
      Tipo.APRESENTACAO, List.of(SAUDACAO_1, SAUDACAO_2),
      Tipo.AGRADECIMENTO, List.of(AGRADECIMENTO_0, AGRADECIMENTO_1),
      Tipo.DESPEDIDA, List.of(DESPEDIDA_0, DESPEDIDA_1),
      Tipo.RECONHECIMENTO, List.of(RECONHECIMENTO_0, RECONHECIMENTO_1),
      Tipo.SEGUIMENTO, List.of(SEGUIMENTO));

  private static final Pattern VOCATIVO = Pattern.compile("\\bceres\\b");

  /** Expressão normalizada → tipo, da mais longa para a mais curta (a busca tenta nessa ordem). */
  private static final Map<String, Tipo> EXPRESSOES = expressoes();

  /** Complementos normalizados, do mais longo para o mais curto. */
  private static final List<String> COMPLEMENTOS_NORMALIZADOS = COMPLEMENTOS.stream()
      .map(ConversaLeve::juntarRepetidas)
      .sorted(Comparator.comparingInt(ConversaLeve::palavras).reversed())
      .toList();

  /** As palavras de cada expressão e complemento, divididas uma vez só. */
  private static final Map<String, String[]> PALAVRAS_DA_EXPRESSAO =
      new java.util.concurrent.ConcurrentHashMap<>();

  private ConversaLeve() {}

  /**
   * {@code true} se a mensagem é conversa leve: responde sem o modelo, então fica fora da cota da
   * Ceres (o {@code AssistenteService} não reserva o teto para ela).
   *
   * @param pergunta mensagem do usuário
   * @return se é conversa leve
   */
  public static boolean ehConversaLeve(String pergunta) {
    return reconhecer(pergunta).isPresent();
  }

  /**
   * A resposta da conversa leve, se a mensagem for uma.
   *
   * @param pergunta mensagem do usuário
   * @return o tipo e o texto, ou vazio (a mensagem segue o fluxo normal)
   */
  public static Optional<Resposta> reconhecer(String pergunta) {
    if (pergunta == null || pergunta.isBlank()) {
      return Optional.empty();
    }
    String normalizada = juntarRepetidas(RegrasClassificacao.normalizar(pergunta));
    boolean comVocativo = VOCATIVO.matcher(normalizada).find();
    String chave = VOCATIVO.matcher(normalizada).replaceAll(" ").replaceAll("\\s+", " ").strip();
    boolean interrogacao = terminaEmInterrogacao(pergunta);
    if (chave.isEmpty()) {
      Tipo tipo = comVocativo ? Tipo.SAUDACAO : Tipo.RECONHECIMENTO;
      if (tipo == Tipo.RECONHECIMENTO && interrogacao) {
        return Optional.empty();
      }
      return Optional.of(montar(tipo, List.of(), chave));
    }
    String[] palavras = chave.split(" ");
    if (palavras.length > MAXIMO_PALAVRAS) {
      return Optional.empty();
    }
    List<String> lidas = new ArrayList<>();
    if (!ler(palavras, 0, lidas)) {
      return Optional.empty();
    }
    if (interrogacao && lidas.size() == 1
        && CONFIRMACAO_COM_INTERROGACAO.contains(lidas.get(0))) {
      return Optional.of(montar(Tipo.SEGUIMENTO, lidas, chave));
    }
    Tipo tipo = tipo(lidas);
    if (interrogacao && (tipo == Tipo.AGRADECIMENTO || tipo == Tipo.DESPEDIDA
        || tipo == Tipo.RECONHECIMENTO)) {
      // "Obrigado?" quer dizer "sou obrigado?": pergunta, nao cortesia.
      return Optional.empty();
    }
    return Optional.of(montar(tipo, lidas, chave));
  }

  /**
   * Todas as partes que a conversa leve pode devolver, pelo nome ("SAUDACAO #0", "ESTADO
   * COMO_ESTA"...), para os testes de texto fixo (acento, sigla e textos proibidos).
   *
   * @return nome → texto, na ordem dos tipos
   */
  public static Map<String, String> textos() {
    Map<String, String> textos = new LinkedHashMap<>();
    for (Tipo tipo : Tipo.values()) {
      List<String> variantes = CORPOS.get(tipo);
      for (int i = 0; i < variantes.size(); i++) {
        textos.put(tipo + " #" + i, variantes.get(i));
      }
    }
    textos.put("BOA_NOITE_SOZINHO", BOA_NOITE_SOZINHO);
    textos.put("ESTADO COMO_ESTA", ESTADO_COMO_ESTA);
    textos.put("ESTADO ESTA_AI", ESTADO_ESTA_AI);
    textos.put("ABERTURA QUEM", ABERTURA_QUEM);
    textos.put("ABERTURA PEDIDO", ABERTURA_PEDIDO);
    textos.put("ABERTURA POR_NADA", ABERTURA_POR_NADA);
    return Collections.unmodifiableMap(textos);
  }

  /** As expressões da lista, normalizadas, com o tipo de cada uma (para os testes). */
  static Map<String, Tipo> expressoesNormalizadas() {
    return EXPRESSOES;
  }

  /**
   * A variante escolhida pela chave: {@code Math.floorMod(chave.hashCode(), variantes.size())}.
   *
   * @param variantes as variantes do corpo
   * @param chave     a mensagem normalizada, sem o vocativo e com as letras juntadas
   * @return a variante
   */
  static String escolher(List<String> variantes, String chave) {
    return variantes.get(Math.floorMod(chave.hashCode(), variantes.size()));
  }

  /**
   * Lê as palavras a partir de {@code inicio} como uma sequência de expressões da lista, com "e"
   * opcional entre elas e complemento só depois de agradecimento. Volta atrás quando a expressão
   * mais longa não deixa ler o resto.
   */
  private static boolean ler(String[] palavras, int inicio, List<String> lidas) {
    if (inicio == palavras.length) {
      return !lidas.isEmpty();
    }
    for (Map.Entry<String, Tipo> e : EXPRESSOES.entrySet()) {
      if (casa(palavras, inicio, e.getKey())) {
        lidas.add(e.getKey());
        if (ler(palavras, inicio + palavras(e.getKey()), lidas)) {
          return true;
        }
        lidas.remove(lidas.size() - 1);
      }
    }
    if (!lidas.isEmpty() && aceitaComplemento(lidas.get(lidas.size() - 1))) {
      for (String complemento : COMPLEMENTOS_NORMALIZADOS) {
        if (casa(palavras, inicio, complemento)) {
          lidas.add(complemento);
          if (ler(palavras, inicio + palavras(complemento), lidas)) {
            return true;
          }
          lidas.remove(lidas.size() - 1);
        }
      }
    }
    // "e" entre duas expressoes ("obrigado e tchau"), nunca no comeco nem no fim.
    return !lidas.isEmpty() && "e".equals(palavras[inicio]) && inicio + 1 < palavras.length
        && ler(palavras, inicio + 1, lidas);
  }

  /**
   * O texto termina em "?", ignorando o que vem depois que não é letra nem dígito ("obrigado?!",
   * "tchau? 😊").
   */
  static boolean terminaEmInterrogacao(String pergunta) {
    for (int i = pergunta.length(); i > 0; ) {
      int c = pergunta.codePointBefore(i);
      if (c == '?') {
        return true;
      }
      if (Character.isLetterOrDigit(c)) {
        return false;
      }
      i -= Character.charCount(c);
    }
    return false;
  }

  private static boolean aceitaComplemento(String anterior) {
    return COMPLEMENTOS_NORMALIZADOS.contains(anterior)
        || EXPRESSOES.get(anterior) == Tipo.AGRADECIMENTO;
  }

  private static boolean casa(String[] palavras, int inicio, String expressao) {
    String[] partes = PALAVRAS_DA_EXPRESSAO.computeIfAbsent(expressao, e -> e.split(" "));
    if (inicio + partes.length > palavras.length) {
      return false;
    }
    for (int i = 0; i < partes.length; i++) {
      if (!partes[i].equals(palavras[inicio + i])) {
        return false;
      }
    }
    return true;
  }

  /** O tipo que vence entre as expressões lidas (complementos não contam). */
  private static Tipo tipo(List<String> lidas) {
    Set<Tipo> tipos = tipos(lidas);
    for (Tipo t : Tipo.values()) {
      if (tipos.contains(t)) {
        return t;
      }
    }
    return Tipo.RECONHECIMENTO;
  }

  private static Set<Tipo> tipos(List<String> lidas) {
    Set<Tipo> tipos = EnumSet.noneOf(Tipo.class);
    for (String lida : lidas) {
      Tipo t = EXPRESSOES.get(lida);
      if (t != null) {
        tipos.add(t);
      }
    }
    return tipos;
  }

  private static Resposta montar(Tipo tipo, List<String> lidas, String chave) {
    if (lidas.size() == 1 && "boa noite".equals(lidas.get(0))) {
      return new Resposta(tipo, BOA_NOITE_SOZINHO);
    }
    Set<Tipo> tipos = tipos(lidas);
    boolean saudacao = tipo == Tipo.SAUDACAO || tipos.contains(Tipo.SAUDACAO);
    List<String> partes = new ArrayList<>();
    if (saudacao) {
      partes.add(cumprimento(lidas));
    }
    if (lidas.stream().anyMatch(l -> COMO_ESTA.contains(l))) {
      partes.add(ESTADO_COMO_ESTA);
    } else if (lidas.stream().anyMatch(l -> ESTA_AI.contains(l))) {
      partes.add(ESTADO_ESTA_AI);
    }
    if (tipo == Tipo.APRESENTACAO) {
      partes.add(lidas.stream().anyMatch(QUEM_NORMALIZADAS::contains)
          ? ABERTURA_QUEM : ABERTURA_PEDIDO);
    } else if (tipo == Tipo.DESPEDIDA && tipos.contains(Tipo.AGRADECIMENTO)) {
      partes.add(ABERTURA_POR_NADA);
    }
    partes.add(escolher(CORPOS.get(tipo), chave));
    return new Resposta(tipo, String.join(" ", partes));
  }

  private static String cumprimento(List<String> lidas) {
    if (lidas.contains("bom dia")) {
      return "Bom dia!";
    }
    if (lidas.contains("boa tarde")) {
      return "Boa tarde!";
    }
    if (lidas.contains("boa noite")) {
      return "Boa noite!";
    }
    return "Olá!";
  }

  private static Map<String, Tipo> expressoes() {
    Map<String, Tipo> todas = new LinkedHashMap<>();
    adicionar(todas, SAUDACOES, Tipo.SAUDACAO);
    adicionar(todas, AGRADECIMENTOS, Tipo.AGRADECIMENTO);
    adicionar(todas, DESPEDIDAS, Tipo.DESPEDIDA);
    adicionar(todas, RECONHECIMENTOS, Tipo.RECONHECIMENTO);
    adicionar(todas, APRESENTACOES_QUEM, Tipo.APRESENTACAO);
    adicionar(todas, APRESENTACOES_PEDIDO, Tipo.APRESENTACAO);
    adicionar(todas, SEGUIMENTOS, Tipo.SEGUIMENTO);
    Map<String, Tipo> ordenadas = new LinkedHashMap<>();
    todas.entrySet().stream()
        .sorted(Comparator.<Map.Entry<String, Tipo>>comparingInt(e -> palavras(e.getKey()))
            .thenComparingInt(e -> e.getKey().length()).reversed())
        .forEach(e -> ordenadas.put(e.getKey(), e.getValue()));
    return Collections.unmodifiableMap(ordenadas);
  }

  private static void adicionar(Map<String, Tipo> todas, List<String> lista, Tipo tipo) {
    for (String expressao : lista) {
      String normalizada = juntarRepetidas(expressao);
      Tipo anterior = todas.putIfAbsent(normalizada, tipo);
      if (anterior != null && anterior != tipo) {
        throw new IllegalStateException("Expressao em dois tipos: " + expressao);
      }
    }
  }

  private static Set<String> normalizadas(List<String> expressoes) {
    return expressoes.stream().map(ConversaLeve::juntarRepetidas)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String juntarRepetidas(String texto) {
    return LETRAS_REPETIDAS.matcher(texto).replaceAll("$1");
  }

  private static int palavras(String expressao) {
    return expressao.split(" ").length;
  }
}
