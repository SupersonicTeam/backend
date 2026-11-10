package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.support.LiteraisJava;
import br.com.agrocontabil.support.LiteraisJava.Literal;
import br.com.agrocontabil.support.MensagemSemAcento;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As mensagens de erro que a API devolve ao usuário levam acento (issue #476): o {@code message} de
 * topo do {@code ApiError} e o {@code message}/{@code suggestion} de cada {@code ErrorDetail}
 * aparecem como estão na tela do Angular.
 *
 * <p>Varre {@code src/main/java} e confere, com o {@link MensagemSemAcento}, os literais que chegam
 * ao cliente:
 * <ol>
 *   <li>no pacote {@code exception}, todo literal com espaço das exceções que o cliente lê
 *       (as 5xx e as {@link ComMensagemCliente} têm o {@code getMessage()} só de log: nelas valem
 *       as regras 2 e 3);</li>
 *   <li>em qualquer lugar, os argumentos de {@code ErrorDetail}, {@code ApiError.of},
 *       {@code FieldViolation}, das mensagens de Bean Validation e dos textos gravados para a tela
 *       (avisos do rascunho, validações, motivo da SEFAZ, resultado da importação);</li>
 *   <li>em qualquer lugar, constantes e variáveis de mensagem ({@code MENSAGEM*},
 *       {@code SUGESTAO*}, {@code CLIENT_MESSAGE}, {@code *_MSG}, {@code MOTIVO_*},
 *       {@code mensagem}, {@code message});</li>
 *   <li>fora do pacote {@code exception}, os argumentos com espaço de exceção de domínio que o
 *       cliente lê ({@code new XmlImportacaoInvalidaException("...")}) e todos os do
 *       {@link ResourceNotFoundException} (o nome do recurso entra na mensagem);</li>
 *   <li>nos pacotes {@code service} e {@code controller}, o texto de
 *       {@code IllegalArgumentException}, que o {@code GlobalExceptionHandler} devolve ao cliente
 *       como está (menos o {@code ConsultaDadosProdutorService}, cujo erro de parâmetro vai ao
 *       modelo da Ceres).</li>
 * </ol>
 *
 * <p>Ficam de fora o log (ASCII por convenção), o Swagger e os módulos que não respondem a API com
 * esses textos: a Ceres ({@code assistente}, com a guarda própria {@code TextosFixosAcentuadosTest}
 * e textos que vão ao modelo), a avaliação e o seed da banca. Nome de parâmetro ou campo dentro da
 * mensagem vai entre aspas simples ({@code 'periodo'}, {@code 'ate'}), e a guarda o ignora.
 */
class MensagensErroAcentuadasTest {

  private static final Path FONTES = Path.of("src", "main", "java");

  private static final String PACOTE_BASE = "br.com.agrocontabil.";

  /** Pacotes fora da varredura (ver o Javadoc da classe). */
  private static final List<String> PACOTES_FORA = List.of(
      "br.com.agrocontabil.assistente", "br.com.agrocontabil.config.avaliacao",
      "br.com.agrocontabil.config.demonstracao");

  /** Status 5xx: o {@code getMessage()} da exceção é só log (#251). */
  private static final Pattern STATUS_5XX = Pattern.compile(
      "HttpStatus\\.(INTERNAL_SERVER_ERROR|BAD_GATEWAY|SERVICE_UNAVAILABLE|GATEWAY_TIMEOUT)");

  /** Regra 2: chamadas cujos argumentos chegam ao cliente. */
  private static final Pattern CHAMADA_PARA_O_CLIENTE = Pattern.compile(
      "(.*\\.)?(ErrorDetail|ErrorDetail\\.of|ApiError\\.of|FieldViolation|violacao"
          + "|AvisoNotaResponse|ImportacaoNotaResultado|setSefazMotivo"
          + "|buildConstraintViolationWithTemplate|message|suggestion)");

  /** Regra 2: anotações de Bean Validation (o texto livre delas é o {@code message}). */
  private static final Set<String> CONSTRAINTS = Set.of("@NotNull", "@NotBlank", "@NotEmpty",
      "@Size", "@Pattern", "@Min", "@Max", "@DecimalMin", "@DecimalMax", "@Positive",
      "@PositiveOrZero", "@Negative", "@NegativeOrZero", "@AssertTrue", "@AssertFalse", "@Email",
      "@Past", "@PastOrPresent", "@Future", "@FutureOrPresent", "@Digits", "@Null");

  /** Regra 2: mensagem padrão de constraint própria ({@code String message() default "..."}). */
  private static final Pattern MENSAGEM_PADRAO = Pattern.compile("=.*message\\(\\)\\s*default$");

  /**
   * Regra 3: constante ou variável de mensagem. O texto depois do {@code =} cobre os pedaços de
   * uma concatenação ({@code MENSAGEM = "a" + "b"}).
   */
  private static final Pattern CONSTANTE_DE_MENSAGEM = Pattern.compile(
      "=.*\\b(MENSAGEM\\w*|SUGESTAO\\w*|CLIENT_MESSAGE|\\w+_MSG|MOTIVO_\\w+|mensagem|message"
          + "|sugestao|suggestion)\\s*=(?:[^=].*)?");

  private static final Pattern LOG = Pattern.compile("(log|LOG|logger|LOGGER)\\.\\w+");

  /** Regra 5: pacotes cuja {@code IllegalArgumentException} chega ao cliente (400). */
  private static final List<String> PACOTES_COM_ARGUMENTO_INVALIDO = List.of(
      PACOTE_BASE + "service.", PACOTE_BASE + "controller.");

  /** Regra 5: o erro de parâmetro das funções da Ceres vai ao modelo, não ao cliente. */
  private static final Set<String> ARGUMENTO_INVALIDO_FORA = Set.of(
      PACOTE_BASE + "service.ConsultaDadosProdutorService");

  /** Exceções de domínio do projeto, pelo nome simples. */
  private static final Map<String, Class<?>> EXCECOES = new HashMap<>();

  @BeforeAll
  static void carregarExcecoes() throws IOException {
    try (Stream<Path> arquivos = Files.walk(FONTES)) {
      for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
        String nome = nomeDaClasse(arquivo);
        try {
          Class<?> classe = Class.forName(nome, false, MensagensErroAcentuadasTest.class
              .getClassLoader());
          if (DomainException.class.isAssignableFrom(classe)) {
            EXCECOES.put(classe.getSimpleName(), classe);
          }
        } catch (ClassNotFoundException | LinkageError e) {
          // package-info, module-info ou arquivo sem classe com o nome dele: não é exceção.
        }
      }
    }
  }

  @Test
  @DisplayName("nenhuma mensagem de erro que chega ao usuário sai sem acento")
  void mensagensDeErroLevamAcento() throws IOException {
    List<String> problemas = new ArrayList<>();
    try (Stream<Path> arquivos = Files.walk(FONTES)) {
      for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        String classe = nomeDaClasse(arquivo);
        if (PACOTES_FORA.stream().anyMatch(classe::startsWith)) {
          continue;
        }
        String fonte = Files.readString(arquivo, StandardCharsets.UTF_8);
        for (String problema : semAcento(classe, fonte)) {
          problemas.add(FONTES.relativize(arquivo).toString().replace('\\', '/') + ":" + problema);
        }
      }
    }
    assertThat(problemas)
        .as("Mensagem de erro ao usuário sem acento (#476). Acentue o texto; nome de parâmetro ou "
            + "campo vai entre aspas simples ('ate'). Log fica em ASCII e não entra aqui.")
        .isEmpty();
  }

  @Test
  @DisplayName("a guarda pega a mensagem sem acento em cada lugar que chega ao cliente")
  void guardaPegaCadaLugar() {
    String fonte = """
        package br.com.agrocontabil.service;
        class Exemplo {
          private static final String MENSAGEM_X = "Periodo nao informado.";
          void a() {
            log.warn("Requisicao invalida para o produtor {}", id);
            throw new XmlImportacaoInvalidaException("Arquivo nao e um XML.");
          }
          void b() {
            throw new ResourceNotFoundException("Apuracao", id);
          }
          void c() {
            errors.add(ErrorDetail.of(ErrorCode.X, "campo", "Valor invalido."));
          }
          @NotBlank(message = "descricao e obrigatoria") String descricao;
          void d() {
            throw new FaixaApuracaoInvalidaException("ultimos", "Use 'ultimos' ou 'de'/'ate'.",
                "Consulte em /api/apuracao.", null, "'ultimos' OU 'de'/'ate'");
          }
          void e() {
            throw new IllegalArgumentException("Periodo invalido em app.apuracao.lote.");
          }
        }
        """;

    List<String> problemas = semAcento("br.com.agrocontabil.service.Exemplo", fonte);

    assertThat(problemas).containsExactly(
        "3: \"Periodo nao informado.\" (Periodo)",
        "6: \"Arquivo nao e um XML.\" (nao)",
        "9: \"Apuracao\" (Apuracao)",
        "12: \"Valor invalido.\" (invalido)",
        "14: \"descricao e obrigatoria\" (descricao)",
        "20: \"Periodo invalido em app.apuracao.lote.\" (Periodo)");
    assertThat(semAcento("br.com.agrocontabil.service.ConsultaDadosProdutorService", fonte))
        .as("o erro de parâmetro das funções da Ceres vai ao modelo")
        .doesNotContain("20: \"Periodo invalido em app.apuracao.lote.\" (Periodo)");
  }

  @Test
  @DisplayName("na exceção que o cliente lê, todo texto conta; na 5xx, o getMessage() é log")
  void excecaoDoClienteETecnica() {
    String cliente = """
        package br.com.agrocontabil.exception;
        public class GuiaJaPagaException extends DomainException {
          public GuiaJaPagaException(UUID id) {
            super(HttpStatus.CONFLICT, "Guia '%s' ja esta paga.".formatted(id), List.of());
          }
        }
        """;
    String tecnica = """
        package br.com.agrocontabil.exception;
        public class AssistenteTempoEsgotadoException extends DomainException {
          public AssistenteTempoEsgotadoException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "Pergunta passou do tempo maximo",
                List.of(ErrorDetail.of(ErrorCode.X, "A Ceres nao respondeu.")));
          }
        }
        """;

    assertThat(semAcento("br.com.agrocontabil.exception.GuiaJaPagaException", cliente))
        .containsExactly("4: \"Guia '%s' ja esta paga.\" (ja)");
    assertThat(semAcento("br.com.agrocontabil.exception.AssistenteTempoEsgotadoException",
        tecnica)).containsExactly("5: \"A Ceres nao respondeu.\" (nao)");
  }

  @Test
  @DisplayName("palavra sem acento é pega; nome técnico e palavra ambígua ficam de fora")
  void detectorDePalavras() {
    assertThat(MensagemSemAcento.primeiraPalavraSemAcento("Nao foi possivel.")).isEqualTo("Nao");
    assertThat(MensagemSemAcento.primeiraPalavraSemAcento("Sessão expirada; a requisicao"))
        .isEqualTo("requisicao");
    assertThat(MensagemSemAcento.primeiraPalavraSemAcento("o calendario do mes"))
        .isEqualTo("calendario");
    assertThat(MensagemSemAcento.primeiraPalavraSemAcento("Gere antes de baixa-lo."))
        .isEqualTo("baixa-lo");
    assertThat(MensagemSemAcento.primeiraPalavraSemAcento("Data de emissao ao contador"))
        .isEqualTo("emissao");
    assertThat(MensagemSemAcento.primeiraPalavraSemAcento("Sem revisoes nem padrao"))
        .isEqualTo("revisoes");
    assertThat(MensagemSemAcento.primeiraPalavraSemAcento(
        "Informe 'de' anterior a 'ate' (POST /api/escritorio/membros/{id}, de/ate).")).isNull();
    assertThat(MensagemSemAcento.primeiraPalavraSemAcento(
        "cClassTrib e naturezaOperacao em COM_INCONSISTENCIAS: esta nota e valida")).isNull();
    assertThat(MensagemSemAcento.primeiraPalavraSemAcento("Não foi possível emitir.")).isNull();
  }

  /**
   * Literais sem acento que chegam ao cliente, como {@code "linha: \"texto\" (palavra)"}.
   *
   * @param classe nome qualificado da classe do arquivo
   * @param fonte  código-fonte
   * @return um item por literal com palavra sem acento
   */
  private static List<String> semAcento(String classe, String fonte) {
    boolean pacoteExcecao = classe.startsWith(PACOTE_BASE + "exception.");
    boolean excecaoTecnica = pacoteExcecao && tecnica(classe);
    boolean argumentoInvalidoAoCliente = PACOTES_COM_ARGUMENTO_INVALIDO.stream()
        .anyMatch(classe::startsWith) && !ARGUMENTO_INVALIDO_FORA.contains(classe);
    List<String> problemas = new ArrayList<>();
    for (Literal literal : LiteraisJava.de(fonte)) {
      if (!chegaAoCliente(literal, pacoteExcecao, excecaoTecnica, argumentoInvalidoAoCliente)) {
        continue;
      }
      String palavra = MensagemSemAcento.primeiraPalavraSemAcento(literal.texto());
      if (palavra != null) {
        problemas.add(literal.linha() + ": \"" + literal.texto() + "\" (" + palavra + ")");
      }
    }
    return problemas;
  }

  private static boolean chegaAoCliente(Literal literal, boolean pacoteExcecao,
      boolean excecaoTecnica, boolean argumentoInvalidoAoCliente) {
    String chamador = literal.chamador();
    if (LOG.matcher(chamador).matches()) {
      return false;
    }
    boolean frase = literal.texto().contains(" ");
    if (CONSTRAINTS.contains(chamador) || MENSAGEM_PADRAO.matcher(chamador).matches()
        || CHAMADA_PARA_O_CLIENTE.matcher(chamador).matches()
        || CONSTANTE_DE_MENSAGEM.matcher(chamador).matches()) {
      return frase;
    }
    if (pacoteExcecao) {
      return frase && !excecaoTecnica && !chamador.startsWith("@");
    }
    if (chamador.equals("IllegalArgumentException")) {
      return frase && argumentoInvalidoAoCliente;
    }
    String classe = chamador.split("\\.")[0];
    Optional<Class<?>> excecao = Optional.ofNullable(EXCECOES.get(classe));
    if (excecao.isEmpty()) {
      return false;
    }
    if (excecao.get() == ResourceNotFoundException.class) {
      return true;
    }
    return frase && !tecnica(excecao.get());
  }

  /** Exceção cujo {@code getMessage()} é só log: 5xx ou {@link ComMensagemCliente}. */
  private static boolean tecnica(Class<?> excecao) {
    for (Class<?> atual = excecao; atual != null && atual != DomainException.class;
        atual = atual.getSuperclass()) {
      if (ComMensagemCliente.class.isAssignableFrom(atual) || STATUS_5XX.matcher(
          fonte(atual)).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Classe do pacote {@code exception} cujo {@code getMessage()} é só log. A que não é exceção de
   * domínio (o {@code GlobalExceptionHandler}) não é técnica: todo texto dela conta.
   */
  private static boolean tecnica(String classe) {
    Class<?> carregada = EXCECOES.get(classe.substring(classe.lastIndexOf('.') + 1));
    return carregada != null && tecnica(carregada);
  }

  private static String fonte(Class<?> classe) {
    Path arquivo = FONTES.resolve(classe.getName().replace('.', '/') + ".java");
    try {
      return Files.readString(arquivo, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  private static String nomeDaClasse(Path arquivo) {
    String relativo = FONTES.relativize(arquivo).toString().replace('\\', '/');
    return relativo.substring(0, relativo.length() - ".java".length()).replace('/', '.');
  }
}
