package br.com.agrocontabil.assistente.fallback;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.ResultadoRota;
import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.config.AssistenteProperties;
import java.text.Normalizer;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Modo fallback da Ceres: o que responder quando o modelo está indisponível (issue #146).
 *
 * <p><strong>Nenhum texto gerado, nada inventado.</strong> Sem modelo não há quem redija, e a regra
 * da Ceres (o backend garante, o modelo só redige) vale ainda mais aqui:
 * <ul>
 *   <li><strong>legislação:</strong> os trechos mais próximos da pergunta, verbatim e com a
 *       citação montada dos metadados, pela {@link BuscaTextualLegislacaoService} — o embedding
 *       também roda no Ollama, então a busca vetorial cai junto com o chat;</li>
 *   <li><strong>dados:</strong> sem modelo não há como interpretar a pergunta livre nem escolher a
 *       função. A resposta é um texto fixo e os atalhos para as telas onde o dado está.</li>
 * </ul>
 *
 * <p>Quando o classificador do modelo também caiu, a intenção vem de palavras-chave
 * ({@link #classificar}). Erra mais que o modelo, mas o pior caso é mostrar trechos de lei para
 * uma pergunta de dados (ou atalhos para uma de lei) — nunca um número ou uma afirmação falsa.
 */
@Component
public class ModoFallback {

  /** Texto fixo da parte de dados sem modelo. Sem dígitos: nada aqui é dado do produtor. */
  public static final String SEM_MODELO_DADOS =
      "Estou sem o modelo de linguagem no momento e nao consigo interpretar perguntas sobre os "
          + "seus dados. Voce encontra essas informacoes nas telas indicadas.";

  /** Abertura da parte de legislação sem modelo. */
  public static final String SEM_MODELO_LEI =
      "Estou sem o modelo de linguagem no momento, entao nao redijo uma resposta. Estes sao os "
          + "trechos da legislacao mais proximos da sua pergunta:";

  /** Nenhum trecho encontrado pela busca textual. */
  public static final String SEM_MODELO_SEM_TRECHO =
      "Estou sem o modelo de linguagem no momento e nao encontrei na legislacao um trecho que "
          + "trate da sua pergunta. Tente de novo em alguns minutos.";

  private static final Pattern SINAL_DADOS = Pattern.compile(
      "\\b(meu|minha|meus|minhas|eu|comprei|vendi|paguei|emiti|recebi|tenho|devo|tive|gastei)\\b");

  private static final Pattern SINAL_LEI = Pattern.compile(
      "\\b(lei|leis|lc|ec|artigo|art|legislacao|aliquota|aliquotas|reducao|reduzida|isencao|"
          + "isento|isenta|direito|regra|regras|previsto|prevista|permite|reforma|anexo|"
          + "presumido|regime|diferimento|imunidade|como funciona|o que e)\\b");

  private static final Map<TelaAtalho, Pattern> ATALHOS = Map.of(
      TelaAtalho.NOTAS, Pattern.compile(
          "\\b(nota|notas|nf|nfe|venda|vendas|vendi|compra|compras|comprei|insumo|insumos)\\b"),
      TelaAtalho.APURACAO, Pattern.compile(
          "\\b(apuracao|apurar|saldo|recolher|devo|debito|debitos|ibs|cbs)\\b"),
      TelaAtalho.CREDITOS, Pattern.compile("\\bcreditos?\\b"),
      TelaAtalho.CALENDARIO, Pattern.compile(
          "\\b(guia|guias|vencimento|vencimentos|vence|vencem|vencer|venceu|vencida|vencidas|"
              + "prazo|prazos|calendario|pagar)\\b"));

  private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}+");

  private final BuscaTextualLegislacaoService buscaTextual;
  private final int maxTrechos;
  private final int tamanhoMaximoTrecho;

  /** Constrói o modo fallback sobre a busca textual. */
  public ModoFallback(
      BuscaTextualLegislacaoService buscaTextual, AssistenteProperties propriedades) {
    this.buscaTextual = buscaTextual;
    this.maxTrechos = propriedades.getFallback().getMaxTrechos();
    this.tamanhoMaximoTrecho = propriedades.getFallback().getTamanhoMaximoTrecho();
  }

  /**
   * Intenção por palavras-chave, para quando o classificador do modelo também caiu. Nunca devolve
   * {@code FORA_DE_ESCOPO}: na dúvida, tenta a legislação — a busca textual só devolve trecho que
   * casa com a pergunta.
   */
  public Intencao classificar(String pergunta) {
    String texto = normalizar(pergunta);
    boolean dados = SINAL_DADOS.matcher(texto).find();
    boolean lei = SINAL_LEI.matcher(texto).find();
    if (dados && lei) {
      return Intencao.MISTO;
    }
    return dados ? Intencao.DADOS : Intencao.LEGISLACAO;
  }

  /** Parte de dados sem modelo: texto fixo e as telas onde o dado está. */
  public ResultadoRota parteDados(String pergunta) {
    return ResultadoRota.fallback(SEM_MODELO_DADOS, List.of(), atalhos(pergunta));
  }

  /**
   * Parte de legislação sem modelo: os trechos da busca textual, verbatim e citados.
   *
   * @throws br.com.agrocontabil.exception.AssistenteIndisponivelException se a base também falhar
   */
  public ResultadoRota parteLei(String pergunta, ContextoAssistente contexto) {
    contexto.verificarAtiva();
    List<TrechoRecuperado> trechos = buscaTextual.buscar(pergunta, contexto.hoje(), maxTrechos);
    if (trechos.isEmpty()) {
      return ResultadoRota.fallback(SEM_MODELO_SEM_TRECHO, List.of(), List.of());
    }
    StringBuilder texto = new StringBuilder(SEM_MODELO_LEI);
    for (TrechoRecuperado trecho : trechos) {
      CitacaoLegal citacao = trecho.comoCitacao();
      texto.append("\n\n- ").append(citacao.referencia()).append(": ")
          .append(resumir(trecho.texto()));
    }
    contexto.registrarTrechosCitados(trechos.stream().map(TrechoRecuperado::id).toList());
    return ResultadoRota.fallback(texto.toString(),
        trechos.stream().map(TrechoRecuperado::comoCitacao).toList(), List.of());
  }

  /** Telas que a pergunta menciona; nenhuma reconhecida, todas. */
  static List<TelaAtalho> atalhos(String pergunta) {
    String texto = normalizar(pergunta);
    Set<TelaAtalho> telas = EnumSet.noneOf(TelaAtalho.class);
    ATALHOS.forEach((tela, padrao) -> {
      if (padrao.matcher(texto).find()) {
        telas.add(tela);
      }
    });
    return List.copyOf(telas.isEmpty() ? EnumSet.allOf(TelaAtalho.class) : telas);
  }

  /** Corta no último espaço antes do limite; o texto inteiro segue na citação. */
  private String resumir(String texto) {
    String limpo = texto == null ? "" : texto.strip().replaceAll("\\s+", " ");
    if (limpo.length() <= tamanhoMaximoTrecho) {
      return limpo;
    }
    int corte = limpo.lastIndexOf(' ', tamanhoMaximoTrecho);
    return limpo.substring(0, corte > 0 ? corte : tamanhoMaximoTrecho) + "...";
  }

  private static String normalizar(String texto) {
    String semAcento = DIACRITICOS.matcher(
        Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)).replaceAll("");
    return semAcento.toLowerCase(Locale.ROOT);
  }
}
