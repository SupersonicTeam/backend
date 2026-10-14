package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Pattern ASSUNTO = Pattern.compile(
      "\\b(retenc|retid|reter|retem|funrural|senar|split\\s*payment)");
  private static final Pattern ACENTOS = Pattern.compile("\\p{M}");
  private static final Logger log = LoggerFactory.getLogger(FundamentoRetencao.class);

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
    // "Nao encontrei fundamento" ao lado do fundamento seria contraditorio: a frase sai.
    String texto = resultado.texto().replace(ValidadorCitacao.SEM_FUNDAMENTO, "").strip();
    texto = texto.isEmpty() ? TEXTO : texto + "\n\n" + TEXTO;
    return new ResultadoOrquestracao(resultado.rota(), texto, resultado.desfecho(),
        juntar(resultado.citacoes(), citacoes(contexto)), resultado.exibirFontes(),
        resultado.modoFallback(), resultado.atalhos());
  }

  /** O assunto é retenção: a função foi usada ou a pergunta fala dela. */
  static boolean trataDeRetencao(String pergunta, ContextoAssistente contexto) {
    if (contexto.fontes().stream().anyMatch(f -> FUNCAO_RETENCOES.equals(f.funcao()))) {
      return true;
    }
    String normalizada = ACENTOS.matcher(Normalizer.normalize(
        pergunta == null ? "" : pergunta, Normalizer.Form.NFD)).replaceAll("");
    return ASSUNTO.matcher(normalizada.toLowerCase(Locale.ROOT)).find();
  }

  /** Citações com o texto da base; sem banco, o parágrafo segue sozinho (ele já nomeia a lei). */
  private List<CitacaoLegal> citacoes(ContextoAssistente contexto) {
    contexto.verificarAtiva();
    try {
      List<TrechoRecuperado> trechos = busca.buscarDispositivos(DISPOSITIVOS, contexto.hoje());
      if (trechos.size() < DISPOSITIVOS.size()) {
        log.warn("Fundamento de retencao: {} de {} dispositivos na base de legislacao",
            trechos.size(), DISPOSITIVOS.size());
      }
      return trechos.stream().map(TrechoRecuperado::comoCitacao).toList();
    } catch (AssistenteIndisponivelException e) {
      log.warn("Fundamento de retencao sem citacoes: base de legislacao indisponivel");
      return List.of();
    }
  }

  /** Citações da rota primeiro, sem repetir a mesma referência. */
  private static List<CitacaoLegal> juntar(List<CitacaoLegal> daRota, List<CitacaoLegal> fixas) {
    Map<String, CitacaoLegal> porReferencia = new LinkedHashMap<>();
    for (CitacaoLegal c : daRota) {
      porReferencia.putIfAbsent(c.referencia(), c);
    }
    for (CitacaoLegal c : fixas) {
      porReferencia.putIfAbsent(c.referencia(), c);
    }
    return new ArrayList<>(porReferencia.values());
  }
}
