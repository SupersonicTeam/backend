package br.com.agrocontabil.assistente.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faixas de artigos de caso especial da LC 214/2025 e as palavras que mostram que a pergunta é do
 * setor de cada uma (issues #269 e #436). Tabela única da {@code GuardaRegimeEspecifico} (depois do
 * modelo) e da partição da {@link BuscaLegislacaoService} (antes dele). Função pura, sem Spring.
 *
 * <ul>
 *   <li>{@link #REGIMES_ESPECIFICOS}: os capítulos do Título dos regimes específicos do IBS e da
 *       CBS (arts. 172 a 307). Os limites vêm do texto da lei (o título abre depois do art. 171 e
 *       fecha no 307; o 308 abre os regimes diferenciados da CBS); as palavras do setor, do nome de
 *       cada capítulo, dos objetos que a lei lista nele e das palavras do produtor rural para o
 *       mesmo assunto (propriedade, fazenda, terra e arrendamento para bens imóveis; CPR, cédula,
 *       financiamento e empréstimo para serviços financeiros). Palavra a mais só deixa um trecho
 *       no lugar dele, nunca o rebaixa;</li>
 *   <li>{@link #ampliada()}: os regimes específicos mais as faixas da tabela
 *       {@code assistente/caso-especial-ampliada-2026-11-07.json} (importação, regimes aduaneiros,
 *       ZPE, bens de capital portuários e de infraestrutura, devolução personalizada, resíduos,
 *       usados, regime automotivo, benefícios do ICMS, Imposto Seletivo, Zona Franca, áreas de
 *       livre comércio, turista estrangeiro e a transição dos imóveis), escrita por quem só leu a
 *       lei e copiada sem mudar faixa nem regex.</li>
 * </ul>
 *
 * <p><strong>Artigo com sufixo</strong> ({@code art. 98-A}) entra pelo número inteiro, exceto numa
 * faixa de um artigo só: os arts. 471-A a 471-F (conformidade do split payment) não são a devolução
 * ao turista do art. 471 ({@code observacoes} da tabela ampliada).
 *
 * <p><strong>Redação dada pela LC 227/2026.</strong> Para a partição, o trecho da LC 227/2026 com a
 * nova redação de um artigo da LC 214/2025 ({@code art. 174 (redação do art. M, ...)}) conta como o
 * art. M da LC 214 ({@link #artigoParaParticao}). A guarda não faz essa troca e olha só as citações
 * da própria LC 214 ({@link #artigoDaLc214}): uma resposta que cita a redação da LC 227 passa por
 * ela como antes.
 */
public final class CasoEspecial {

  /** Norma das faixas. */
  public static final String NORMA = "LC 214/2025";

  /** Norma que dá nova redação a artigos da LC 214/2025. */
  static final String NORMA_ALTERADORA = "LC 227/2026";

  /**
   * Artigo da LC 227/2026 que altera a LC 214/2025 ("A Lei Complementar nº 214 ... passa a vigorar
   * com as seguintes alterações"). Os outros artigos de redação da LC 227 alteram outras leis (o
   * CTN no art. 165, a LC 123 no 168, a Lei 1.079 no 172) e não entram na troca.
   */
  static final int ARTIGO_QUE_ALTERA_A_LC_214 = 174;

  /** Recurso com a tabela ampliada (sha256 conferido no teste). */
  static final String RECURSO_AMPLIADA = "/assistente/caso-especial-ampliada-2026-11-07.json";

  private static final Pattern ARTIGO =
      Pattern.compile("^art\\. (\\d+)(?:º)?(?:-([A-Z]))?");

  private static final Pattern REDACAO_DA_LC_214 = Pattern.compile(
      "^art\\. " + ARTIGO_QUE_ALTERA_A_LC_214 + " \\(redação do art\\. (\\d+)(?:º)?(?:-([A-Z]))?");

  /** Artigo da LC 214/2025: número e sufixo ({@code ""}, {@code "A"}, ...). */
  public record Artigo(int numero, String sufixo) {}

  /** Faixa de artigos de caso especial, com as palavras do setor (pergunta já normalizada). */
  public record Faixa(int primeiro, int ultimo, String nome, Pattern setor) {

    /** {@code true} se o artigo está na faixa (sufixo só em faixa de mais de um artigo). */
    public boolean contem(Artigo artigo) {
      return artigo.numero() >= primeiro && artigo.numero() <= ultimo
          && (artigo.sufixo().isEmpty() || primeiro < ultimo);
    }

    /** {@code true} se a pergunta normalizada cita o setor da faixa. */
    public boolean setorCitado(String perguntaNormalizada) {
      return setor.matcher(perguntaNormalizada).find();
    }
  }

  /** Capítulos do Título dos regimes específicos (arts. 172 a 307). */
  public static final List<Faixa> REGIMES_ESPECIFICOS = List.of(
      faixa(172, 180, "combustíveis",
          "combust|gasolina|diesel|etanol|biodiesel|petroleo|gas natural|lubrificante|querosene|abastec"),
      faixa(181, 233, "serviços financeiros",
          "financ|banc|segur|consorcio|previdencia|capitaliz|cambio|fundo|emprest|juro|cedula"
              + "|\\bcpr\\b|cartao|\\bpix\\b|credito rural|custeio|debenture"),
      faixa(234, 243, "planos de assistência à saúde", "saude|medic|hospital|assistencia"),
      faixa(244, 250, "concursos de prognósticos",
          "prognost|loteria|aposta|sorteio|bingo|rifa|premio"),
      faixa(251, 270, "bens imóveis",
          "imovel|imoveis|imobiliar|terreno|\\blote|loteamento|\\bsolo\\b|incorporac|locac|alug"
              + "|arrend|cessao|constru|propriedade|fazenda|sitio|gleba|\\bterras?\\b|apartamento"
              + "|\\bcasa\\b"),
      faixa(271, 272, "sociedades cooperativas", "cooperativ"),
      faixa(273, 291, "bares, restaurantes, hotelaria, transporte e turismo",
          "\\bbar\\b|\\bbares\\b|restaurante|lanchonete|hotel|hospedagem|pousada|turism|viagem"
              + "|parque|transporte|passageiro"),
      faixa(292, 296, "sociedade anônima do futebol", "futebol|\\bsaf\\b|clube"),
      faixa(297, 299, "missões diplomáticas", "diplomat|consular|embaixada|tratado"),
      faixa(300, 307, "disposições comuns aos regimes específicos",
          "regime especifico|regimes especificos"));

  private CasoEspecial() {}

  /**
   * Regimes específicos mais as faixas da tabela ampliada. A tabela é lida do recurso só na
   * primeira chamada: a guarda e a partição desligada ou de regimes específicos nunca dependem dele.
   */
  public static List<Faixa> ampliada() {
    return Ampliada.FAIXAS;
  }

  /** Carga preguiçosa da tabela ampliada (idiom do holder: a classe só inicia no primeiro uso). */
  private static final class Ampliada {
    static final List<Faixa> FAIXAS = carregarAmpliada();
  }

  private static Faixa faixa(int primeiro, int ultimo, String nome, String setor) {
    return new Faixa(primeiro, ultimo, nome, Pattern.compile(setor));
  }

  private static List<Faixa> carregarAmpliada() {
    List<Faixa> faixas = new ArrayList<>(REGIMES_ESPECIFICOS);
    try (InputStream entrada = CasoEspecial.class.getResourceAsStream(RECURSO_AMPLIADA)) {
      if (entrada == null) {
        throw new IllegalStateException("Tabela ampliada ausente: " + RECURSO_AMPLIADA);
      }
      JsonNode tabela = new ObjectMapper().readTree(entrada);
      for (JsonNode f : tabela.get("faixas")) {
        faixas.add(faixa(f.get("inicio").asInt(), f.get("fim").asInt(), f.get("titulo").asText(),
            f.get("regex").asText()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Tabela ampliada ilegível: " + RECURSO_AMPLIADA, e);
    }
    return List.copyOf(faixas);
  }

  /** O artigo, se o dispositivo é de um artigo da própria LC 214/2025 (a regra da guarda). */
  public static Optional<Artigo> artigoDaLc214(String norma, String dispositivo) {
    if (!NORMA.equals(norma) || dispositivo == null) {
      return Optional.empty();
    }
    return artigo(ARTIGO.matcher(dispositivo));
  }

  /**
   * O artigo da LC 214/2025 a que o trecho se refere, para a partição: o da própria LC 214 ou o
   * art. M de que a LC 227/2026 dá a redação ({@code art. 174 (redação do art. M}).
   */
  public static Optional<Artigo> artigoParaParticao(String norma, String dispositivo) {
    if (NORMA_ALTERADORA.equals(norma) && dispositivo != null) {
      return artigo(REDACAO_DA_LC_214.matcher(dispositivo));
    }
    return artigoDaLc214(norma, dispositivo);
  }

  private static Optional<Artigo> artigo(Matcher m) {
    if (!m.find()) {
      return Optional.empty();
    }
    return Optional.of(new Artigo(Integer.parseInt(m.group(1)),
        m.group(2) == null ? "" : m.group(2)));
  }

  /** A primeira faixa da lista que contém o artigo, se alguma. */
  public static Optional<Faixa> faixaDe(List<Faixa> faixas, Artigo artigo) {
    return faixas.stream().filter(f -> f.contem(artigo)).findFirst();
  }

  /**
   * {@code true} se o trecho é de caso especial e a pergunta não cita o setor <em>da faixa dele</em>:
   * é o trecho que a partição manda para o fim.
   *
   * @param faixas              faixas da variante
   * @param norma               norma do trecho
   * @param dispositivo         dispositivo do trecho
   * @param perguntaNormalizada pergunta já normalizada ({@code NormalizacaoBusca.normalizar})
   */
  public static boolean casoEspecialSemSetor(List<Faixa> faixas, String norma, String dispositivo,
      String perguntaNormalizada) {
    return artigoParaParticao(norma, dispositivo)
        .flatMap(a -> faixaDe(faixas, a))
        .map(f -> !f.setorCitado(perguntaNormalizada))
        .orElse(false);
  }
}
