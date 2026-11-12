package br.com.agrocontabil.assistente.fidelidade;

import br.com.agrocontabil.assistente.GlossarioProdutor;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.model.DesfechoFidelidadeLei;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Conferência de fidelidade da resposta de lei (#269), atrás da flag
 * {@code app.assistente.fidelidade-lei.enabled}. Em código, sem chamada ao modelo: o modelo aponta
 * a passagem do trecho que sustenta cada frase, e esta classe decide.
 *
 * <p>Frase a frase, na ordem (a primeira falha reprova a resposta inteira):
 * <ol>
 *   <li><strong>evidência:</strong> toda frase tem ao menos uma, com marca de trecho existente e
 *       cópia não vazia ({@code REPROVADA_SEM_EVIDENCIA});</li>
 *   <li><strong>literalidade:</strong> a cópia está escrita no trecho marcado, palavra por palavra
 *       (sem acento, caixa nem pontuação; fragmentos separados por reticências, cada um literal e
 *       na ordem). Se está escrita só em outro trecho, a marca vai para ele
 *       ({@code REPROVADA_EVIDENCIA_NAO_LITERAL});</li>
 *   <li><strong>número:</strong> todo número da frase está nas cópias dela
 *       ({@code REPROVADA_NUMERO});</li>
 *   <li><strong>polaridade:</strong> negação ou restrição da frase está nas cópias, e negação das
 *       cópias está na frase ({@code REPROVADA_POLARIDADE}) — o sinal de sentido invertido;</li>
 *   <li><strong>cobertura:</strong> a fração das palavras de conteúdo da frase que aparecem nas
 *       cópias (pela raiz; sigla do glossário = nome por extenso) atinge a mínima
 *       ({@code REPROVADA_COBERTURA}) — o sinal de afirmação além do trecho.</li>
 * </ol>
 *
 * <p>Frase sem nenhuma palavra de conteúdo ("Sim.", "Isso vale.") não tem o que a cópia sustente:
 * passa pelas conferências de número e polaridade, mas sai do texto (não é afirmação conferida). Sem
 * nenhuma frase com conteúdo, a resposta reprova pela cobertura.
 *
 * <p>Aprovada, cada cópia é situada no nível do dispositivo em que está escrita
 * ({@link NivelDispositivo}): cópia só no caput passa a citar o artigo, só no parágrafo, o
 * parágrafo. O texto devolvido são as frases com as marcas, sem as cópias, para seguir pelos
 * validadores de sempre ({@code ValidadorCitacao}, {@code ValidadorNumeros}...). Função pura, sem
 * estado.
 */
@Component
public class ConferenciaFidelidadeLei {

  private static final Pattern MARCAS = Pattern.compile("\\[\\s*T\\d+(?:\\s*[,;]\\s*T\\d+)*\\s*\\]");
  private static final Pattern PONTUACAO_FINAL = Pattern.compile("[.!?;:]+$");
  private static final Pattern RETICENCIAS =
      Pattern.compile("\\[\\s*(?:\\.\\.\\.|…)\\s*\\]|\\(\\s*(?:\\.\\.\\.|…)\\s*\\)|\\.\\.\\.|…");
  /** Marca da evidência: "T1", "[T1]", "t1" ou "1"; numa lista ("T1, T3"), a primeira. */
  private static final Pattern MARCA = Pattern.compile(
      "^\\s*\\[?\\s*(?:T\\s*)?(\\d{1,4})\\s*(?:[,;].*)?\\]?\\s*$", Pattern.CASE_INSENSITIVE);

  private final GlossarioProdutor glossario;

  /** Constrói a conferência sobre o glossário do produtor (sigla = nome por extenso). */
  public ConferenciaFidelidadeLei(GlossarioProdutor glossario) {
    this.glossario = glossario;
  }

  /**
   * Resultado da conferência.
   *
   * @param desfecho      aprovada (com ou sem citação corrigida) ou o motivo da reprovação
   * @param texto         aprovada: as frases com as marcas, prontas para o {@code ValidadorCitacao};
   *                      reprovada: nulo
   * @param trechos       aprovada: os trechos recebidos mais os ancestrais citados (caput,
   *                      parágrafo), com as marcas na ordem da lista; reprovada: os recebidos
   * @param marcasCitadas marcas existentes que o modelo usou nas evidências, na ordem da primeira
   *                      menção (os trechos mostrados quando a resposta reprova)
   * @param frases        aprovada: as frases entregues (sem marcas), cada uma com as cópias que a
   *                      sustentam, na ordem do texto, para o juiz (camada 2); reprovada: vazia
   */
  public record Resultado(DesfechoFidelidadeLei desfecho, String texto,
      List<TrechoRecuperado> trechos, List<String> marcasCitadas, List<FraseConferida> frases) {

    /** Cópias defensivas. */
    public Resultado {
      trechos = List.copyOf(trechos);
      marcasCitadas = List.copyOf(marcasCitadas);
      frases = List.copyOf(frases);
    }

    /** {@code true} se a resposta pode seguir para o produtor. */
    public boolean aprovada() {
      return desfecho == DesfechoFidelidadeLei.APROVADA
          || desfecho == DesfechoFidelidadeLei.APROVADA_CITACAO_CORRIGIDA;
    }
  }

  /**
   * Frase aprovada pela camada 1, com as cópias que a sustentam (o que o juiz lê).
   *
   * @param texto  a frase, sem as marcas
   * @param copias as passagens copiadas dos trechos, na ordem do modelo
   */
  public record FraseConferida(String texto, List<String> copias) {

    /** Cópia defensiva. */
    public FraseConferida {
      copias = List.copyOf(copias);
    }
  }

  /**
   * Confere a resposta do modelo contra os trechos.
   *
   * @param resposta        texto do modelo, no formato de {@link RespostaComEvidencia}
   * @param trechos         trechos recuperados, na ordem das marcas ({@code T1} = primeiro)
   * @param coberturaMinima fração mínima (0 a 1) das palavras de conteúdo de cada frase que têm de
   *                        estar nas cópias dela
   * @return o resultado; nunca nulo
   */
  public Resultado conferir(String resposta, List<TrechoRecuperado> trechos,
      double coberturaMinima) {
    Objects.requireNonNull(trechos, "trechos");
    RespostaComEvidencia lida = RespostaComEvidencia.ler(resposta).orElse(null);
    if (lida == null) {
      return reprovada(DesfechoFidelidadeLei.REPROVADA_FORMATO, trechos, List.of());
    }
    List<String> marcasCitadas = marcasCitadas(lida, trechos);
    Citacoes citacoes = new Citacoes(trechos);
    List<String> frases = new ArrayList<>();
    List<FraseConferida> conferidas = new ArrayList<>();
    for (RespostaComEvidencia.Frase frase : lida.frases()) {
      if (frase.evidencias().isEmpty()) {
        return reprovada(DesfechoFidelidadeLei.REPROVADA_SEM_EVIDENCIA, trechos, marcasCitadas);
      }
      Set<String> marcasDaFrase = new LinkedHashSet<>();
      StringBuilder copias = new StringBuilder();
      List<String> copiasDaFrase = new ArrayList<>();
      for (RespostaComEvidencia.Evidencia evidencia : frase.evidencias()) {
        int indice = indice(evidencia.marca(), trechos.size());
        if (indice < 0 || evidencia.copia() == null || evidencia.copia().isBlank()) {
          return reprovada(DesfechoFidelidadeLei.REPROVADA_SEM_EVIDENCIA, trechos, marcasCitadas);
        }
        String marca = citacoes.citar(indice, evidencia.copia());
        if (marca == null) {
          return reprovada(
              DesfechoFidelidadeLei.REPROVADA_EVIDENCIA_NAO_LITERAL, trechos, marcasCitadas);
        }
        marcasDaFrase.add(marca);
        copias.append(evidencia.copia()).append(' ');
        copiasDaFrase.add(evidencia.copia().strip());
      }
      String textoFrase = MARCAS.matcher(frase.texto()).replaceAll(" ").replaceAll("\\s+", " ")
          .strip();
      DesfechoFidelidadeLei motivo = conferirSentido(textoFrase, copias.toString(),
          coberturaMinima);
      if (motivo != null) {
        return reprovada(motivo, trechos, marcasCitadas);
      }
      if (TextoLei.formas(textoFrase).stream().anyMatch(TextoLei::conteudo)) {
        frases.add(comMarcas(textoFrase, marcasDaFrase));
        conferidas.add(new FraseConferida(textoFrase, copiasDaFrase));
      }
    }
    if (frases.isEmpty()) {
      return reprovada(DesfechoFidelidadeLei.REPROVADA_COBERTURA, trechos, marcasCitadas);
    }
    DesfechoFidelidadeLei desfecho = citacoes.corrigiu
        ? DesfechoFidelidadeLei.APROVADA_CITACAO_CORRIGIDA
        : DesfechoFidelidadeLei.APROVADA;
    return new Resultado(desfecho, String.join(" ", frases), citacoes.lista, marcasCitadas,
        conferidas);
  }

  /** Número, polaridade e cobertura; nulo se a frase passa. */
  private DesfechoFidelidadeLei conferirSentido(String frase, String copias,
      double coberturaMinima) {
    List<String> palavrasFrase = TextoLei.formas(frase);
    List<String> palavrasCopias = TextoLei.formas(copias);
    Set<String> formasCopias = new HashSet<>(palavrasCopias);
    for (String forma : palavrasFrase) {
      if (TextoLei.numero(forma) && !formasCopias.contains(forma)) {
        return DesfechoFidelidadeLei.REPROVADA_NUMERO;
      }
    }
    if (algum(palavrasFrase, TextoLei.NEGACAO) && !algum(palavrasCopias, TextoLei.NEGACAO_AMPLA)
        || algum(palavrasCopias, TextoLei.NEGACAO) && !algum(palavrasFrase, TextoLei.NEGACAO_AMPLA)
        || algum(palavrasFrase, TextoLei.RESTRICAO) && !algum(palavrasCopias, TextoLei.RESTRICAO)) {
      return DesfechoFidelidadeLei.REPROVADA_POLARIDADE;
    }
    List<String> conteudo = palavrasFrase.stream().filter(TextoLei::conteudo).toList();
    if (conteudo.isEmpty()) {
      return null;
    }
    Set<String> raizes = raizesDasCopias(copias, palavrasCopias);
    long cobertas = conteudo.stream().filter(p -> raizes.contains(TextoLei.raiz(p))).count();
    return (double) cobertas / conteudo.size() + 1e-9 < coberturaMinima
        ? DesfechoFidelidadeLei.REPROVADA_COBERTURA
        : null;
  }

  /** Raízes das cópias, com a sigla do glossário valendo pelo nome por extenso e vice-versa. */
  private Set<String> raizesDasCopias(String copias, List<String> palavrasCopias) {
    Set<String> raizes = new HashSet<>();
    palavrasCopias.forEach(p -> raizes.add(TextoLei.raiz(p)));
    String normalizadas = String.join(" ", palavrasCopias);
    for (GlossarioProdutor.Termo termo : glossario.termos()) {
      if (!termo.sigla() || termo.nome() == null) {
        continue;
      }
      List<String> nome = TextoLei.formas(termo.nome());
      if (termo.apareceEm(copias)) {
        nome.forEach(p -> raizes.add(TextoLei.raiz(p)));
      }
      if ((" " + normalizadas + " ").contains(" " + String.join(" ", nome) + " ")) {
        TextoLei.formas(termo.termo()).forEach(p -> raizes.add(TextoLei.raiz(p)));
      }
    }
    return raizes;
  }

  private static boolean algum(List<String> palavras, Set<String> conjunto) {
    return palavras.stream().anyMatch(conjunto::contains);
  }

  /** A frase com as marcas antes da pontuação final ("... operação [T1]."). */
  private static String comMarcas(String frase, Set<String> marcas) {
    String marcasTexto = "[" + String.join(", ", marcas) + "]";
    Matcher m = PONTUACAO_FINAL.matcher(frase);
    if (m.find()) {
      String corpo = frase.substring(0, m.start()).stripTrailing();
      return corpo + " " + marcasTexto + m.group();
    }
    return frase + " " + marcasTexto + ".";
  }

  private static List<String> marcasCitadas(RespostaComEvidencia lida,
      List<TrechoRecuperado> trechos) {
    Set<String> marcas = new LinkedHashSet<>();
    for (RespostaComEvidencia.Frase frase : lida.frases()) {
      for (RespostaComEvidencia.Evidencia evidencia : frase.evidencias()) {
        int indice = indice(evidencia.marca(), trechos.size());
        if (indice >= 0) {
          marcas.add(trechos.get(indice).marca());
        }
      }
    }
    return List.copyOf(marcas);
  }

  /** Posição (0..n-1) da marca na lista; -1 se não existe. */
  private static int indice(String marca, int quantidade) {
    if (marca == null) {
      return -1;
    }
    Matcher m = MARCA.matcher(marca);
    if (!m.matches()) {
      return -1;
    }
    int numero = Integer.parseInt(m.group(1));
    return numero >= 1 && numero <= quantidade ? numero - 1 : -1;
  }

  private static Resultado reprovada(DesfechoFidelidadeLei motivo, List<TrechoRecuperado> trechos,
      List<String> marcasCitadas) {
    return new Resultado(motivo, null, trechos, marcasCitadas, List.of());
  }

  /**
   * Posição, no texto do trecho, da última palavra da cópia; -1 se a cópia não está escrita nele.
   * Fragmentos separados por reticências têm de estar, cada um, escritos e na ordem.
   */
  static int posicaoDaCopia(String copia, List<TextoLei.Palavra> texto) {
    int cursor = 0;
    int ultima = -1;
    boolean algum = false;
    for (String fragmento : RETICENCIAS.split(copia)) {
      List<String> palavras = TextoLei.formas(fragmento);
      if (palavras.isEmpty()) {
        continue;
      }
      int achado = procurar(texto, palavras, cursor);
      if (achado < 0) {
        return -1;
      }
      algum = true;
      cursor = achado + palavras.size();
      ultima = texto.get(cursor - 1).inicio();
    }
    return algum ? ultima : -1;
  }

  private static int procurar(List<TextoLei.Palavra> texto, List<String> palavras, int desde) {
    for (int i = desde; i + palavras.size() <= texto.size(); i++) {
      boolean igual = true;
      for (int j = 0; j < palavras.size() && igual; j++) {
        igual = texto.get(i + j).forma().equals(palavras.get(j));
      }
      if (igual) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Trechos citáveis: os recebidos e os ancestrais acrescentados (marcas {@code Tn+1}…), e se
   * alguma citação foi corrigida.
   */
  private static final class Citacoes {
    final List<TrechoRecuperado> recebidos;
    final List<TrechoRecuperado> lista;
    final Map<String, String> marcaPorDispositivo = new LinkedHashMap<>();
    /** Palavras e níveis de cada trecho recebido, calculados uma vez por resposta. */
    final Map<Integer, List<TextoLei.Palavra>> palavras = new HashMap<>();
    final Map<Integer, Optional<NivelDispositivo>> niveis = new HashMap<>();
    boolean corrigiu;

    Citacoes(List<TrechoRecuperado> recebidos) {
      this.recebidos = recebidos;
      this.lista = new ArrayList<>(recebidos);
      for (TrechoRecuperado t : recebidos) {
        marcaPorDispositivo.putIfAbsent(chave(t.norma(), t.dispositivo()), t.marca());
      }
    }

    /**
     * Marca a citar para a cópia que o modelo atribuiu ao trecho {@code indice}; nulo se a cópia não
     * está escrita em nenhum trecho, ou se está em vários que levariam a citações diferentes.
     */
    String citar(int indice, String copia) {
      int posicao = posicaoDaCopia(copia, palavrasDe(indice));
      if (posicao >= 0) {
        return situar(indice, posicao);
      }
      Set<String> destinos = new LinkedHashSet<>();
      for (int outro = 0; outro < recebidos.size(); outro++) {
        int p = posicaoDaCopia(copia, palavrasDe(outro));
        if (p >= 0) {
          destinos.add(situar(outro, p));
        }
      }
      if (destinos.size() != 1) {
        return null;
      }
      corrigiu = true;
      return destinos.iterator().next();
    }

    private List<TextoLei.Palavra> palavrasDe(int indice) {
      return palavras.computeIfAbsent(indice,
          i -> TextoLei.palavras(recebidos.get(i).texto()));
    }

    /** Marca do nível do dispositivo em que a cópia está escrita. */
    private String situar(int indice, int posicao) {
      TrechoRecuperado trecho = recebidos.get(indice);
      NivelDispositivo nivel = niveis.computeIfAbsent(indice, i -> Optional.ofNullable(
          NivelDispositivo.de(trecho.dispositivo(), trecho.texto()))).orElse(null);
      NivelDispositivo.Ancestral ancestral = nivel == null ? null : nivel.ancestralDe(posicao);
      if (ancestral == null) {
        return trecho.marca();
      }
      corrigiu = true;
      String chave = chave(trecho.norma(), ancestral.dispositivo());
      String existente = marcaPorDispositivo.get(chave);
      if (existente != null) {
        return existente;
      }
      String marca = "T" + (lista.size() + 1);
      // Sem o trecho do ancestral na busca, o id e o do trecho em que a copia foi achada: a
      // auditoria guarda de onde veio o texto.
      lista.add(new TrechoRecuperado(marca, trecho.id(), trecho.norma(), ancestral.dispositivo(),
          trecho.url(), ancestral.texto(), trecho.similaridade()));
      marcaPorDispositivo.put(chave, marca);
      return marca;
    }

    private static String chave(String norma, String dispositivo) {
      return norma + "|" + dispositivo;
    }
  }
}
