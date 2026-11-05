package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Fundamento legal obrigatório das respostas sobre retenção (RN009, issue #145).
 *
 * <p>Decisão do Murillo de 2026-10-14: quando o assunto é retenção, a Ceres menciona e cita as
 * <strong>duas</strong> — o Funrural retido pelo comprador (Lei 8.212/1991, arts. 25 e 30; SENAR
 * na Lei 9.528/1997, art. 6º) e o split payment do IBS/CBS (LC 214/2025, arts. 31 e 32). Deixar
 * isso a cargo do modelo seria torcer para que ele lembrasse das duas; aqui o backend acrescenta
 * o parágrafo e as citações, com o texto oficial vindo da base de legislação.
 *
 * <p>O assunto é retenção quando a função {@code consultarRetencoes} foi usada ou quando a
 * pergunta fala de retenção, Funrural, SENAR ou split payment. Fora de escopo nunca recebe nada.
 */
@Component
public class FundamentoRetencao {

  /** Função de dados da RN009. */
  static final String FUNCAO_RETENCOES = "consultarRetencoes";

  /** Parágrafo fixo, com as duas regras e os dispositivos. */
  public static final String TEXTO = "Fundamento das retencoes: o Funrural do produtor pessoa"
      + " fisica e retido pelo comprador empresa ou cooperativa (Lei 8.212/1991, art. 30, IV), a"
      + " 1,2% mais 0,1% da receita bruta (art. 25, I e II) e 0,2% do SENAR (Lei 9.528/1997, art."
      + " 6º); e o IBS e a CBS destacados na nota sao separados no pagamento pelo split payment"
      + " (LC 214/2025, arts. 31 e 32).";

  /** Dispositivos citados, na ordem do parágrafo ({@code norma|dispositivo}). */
  static final List<String> DISPOSITIVOS = List.of(
      "Lei 8.212/1991|art. 30, inciso IV",
      "Lei 8.212/1991|art. 25, inciso I",
      "Lei 8.212/1991|art. 25, inciso II",
      "Lei 9.528/1997|art. 6º",
      "LC 214/2025|art. 31",
      "LC 214/2025|art. 32");

  /** Fala de retenção; "retido" sozinho também vale (no agro, é o Funrural). */
  private static final Pattern ASSUNTO = Pattern.compile(
      "\\b(retenc|retid|reter|retem|funrural|senar|split\\s*payment)");
  /** Nomeia uma das duas regras da RN009: aí não há dúvida de qual retenção é. */
  private static final Pattern REGRA_DA_RN009 =
      Pattern.compile("\\b(funrural|senar|split\\s*payment|ibs|cbs)\\b");
  /**
   * Outra retenção, que a RN009 não cobre ("ICMS retido por substituição", "IR retido na
   * fonte"): sem nomear Funrural, SENAR, IBS/CBS ou split payment, o parágrafo não entra.
   */
  private static final Pattern OUTRA_RETENCAO = Pattern.compile(
      "\\b(icms|irrf|irpj|imposto de renda|ir|iss|csll|pis|cofins)\\b");
  private static final Pattern ACENTOS = Pattern.compile("\\p{M}");

  private final BuscaTextualLegislacaoService busca;

  /** Constrói sobre a busca por dispositivo exato. */
  public FundamentoRetencao(BuscaTextualLegislacaoService busca) {
    this.busca = busca;
  }

  /**
   * Acrescenta o fundamento das duas retenções, se a pergunta é sobre retenção.
   *
   * @param resultado resposta já validada pela rota
   * @param pergunta  pergunta do usuário
   * @param contexto  contexto da pergunta (fontes e "hoje")
   * @return a resposta com o parágrafo e as citações, ou a mesma resposta
   */
  public ResultadoOrquestracao aplicar(
      ResultadoOrquestracao resultado, String pergunta, ContextoAssistente contexto) {
    if (resultado.rota() == Intencao.FORA_DE_ESCOPO || !trataDeRetencao(pergunta, contexto)) {
      return resultado;
    }
    // "Nao encontrei fundamento" (da rota de lei, da parte legal do MISTO ou do fallback sem
    // trecho) ao lado do fundamento
    // seria contraditorio: a frase sai (mesma lista do TextosDoBackend). Com os trechos
    // relacionados (#343), a frase que os apresenta e eles tambem saem; ficam as citacoes fixas.
    // As citacoes que o modelo marcou certo (#258) ficam.
    boolean relacionados = TextosDoBackend.comTrechosRelacionados(resultado.texto(), contexto);
    String texto = TextosDoBackend.semRecusaDaLei(resultado.texto(), relacionados);
    texto = texto.isEmpty() ? TEXTO : texto + "\n\n" + TEXTO;
    List<CitacaoLegal> daRota = relacionados ? List.of() : resultado.citacoes();
    return new ResultadoOrquestracao(resultado.rota(), texto, resultado.desfecho(),
        CitacoesFixas.juntar(daRota, citacoes(contexto)), resultado.exibirFontes(),
        resultado.modoFallback(), resultado.atalhos());
  }

  /** O assunto é retenção: a função foi usada ou a pergunta fala dela. */
  static boolean trataDeRetencao(String pergunta, ContextoAssistente contexto) {
    if (contexto.fontes().stream().anyMatch(f -> FUNCAO_RETENCOES.equals(f.funcao()))) {
      return true;
    }
    String normalizada = ACENTOS.matcher(Normalizer.normalize(
        pergunta == null ? "" : pergunta, Normalizer.Form.NFD)).replaceAll("");
    String texto = normalizada.toLowerCase(Locale.ROOT);
    if (!ASSUNTO.matcher(texto).find()) {
      return false;
    }
    return REGRA_DA_RN009.matcher(texto).find() || !OUTRA_RETENCAO.matcher(texto).find();
  }

  /** Citações com o texto da base; sem banco, o parágrafo segue sozinho (ele já nomeia a lei). */
  private List<CitacaoLegal> citacoes(ContextoAssistente contexto) {
    return CitacoesFixas.buscar(busca, DISPOSITIVOS, contexto, "Fundamento de retencao");
  }
}
