package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.SecaoResposta.Assunto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compõe a resposta da rota de dados com as seções das funções chamadas (issue #266).
 *
 * <ul>
 *   <li>Uma seção por função, na <strong>ordem de prioridade fixa</strong>: guias em atraso →
 *       prazos dos próximos dias → notas paradas → pedidos do escritório → notas a conferir →
 *       informativos (apuração, créditos, vendas). Dentro do mesmo assunto, a ordem das
 *       chamadas.</li>
 *   <li>Com duas ou mais seções de atenção consultadas, a <strong>primeira linha é o resumo</strong>
 *       do backend: "Encontrei 3 pontos de atencao: 2 guias em atraso e 1 nota devolvida." ou, sem
 *       nenhum, "Nao encontrei pontos de atencao em: ..." com o recorte de cada consulta.</li>
 *   <li><strong>Seção vazia</strong> só aparece se a pergunta pediu aquele assunto (a função foi
 *       chamada direto); a do resumo do produtor entra só no resumo, como "sem pendencias
 *       em".</li>
 *   <li>Teto de {@value #MAXIMO_SECOES} seções (o teto de itens por seção é do template); a
 *       mesma seção repetida (duas chamadas iguais) sai uma vez.</li>
 *   <li>Cada sigla do glossário ganha a explicação na primeira vez que aparece (#264: nenhuma
 *       sigla sem explicação).</li>
 * </ul>
 * Função pura, sem Spring: testada pela tabela de casos.
 */
public final class ComposicaoRespostaDados {

  /** Máximo de seções numa resposta. */
  static final int MAXIMO_SECOES = 6;

  /** Sigla e a explicação que entra entre parênteses na primeira ocorrência. */
  private record Sigla(Pattern padrao, String explicacao) {}

  private static final List<Sigla> GLOSSARIO = List.of(
      new Sigla(Pattern.compile("\\bIBS\\b"), "imposto sobre bens e servicos"),
      new Sigla(Pattern.compile("\\bCBS\\b"), "contribuicao sobre bens e servicos"),
      new Sigla(Pattern.compile("\\bFunrural\\b", Pattern.CASE_INSENSITIVE),
          "contribuicao previdenciaria do produtor rural"),
      new Sigla(Pattern.compile("\\bSEFAZ\\b"), "secretaria da fazenda"),
      new Sigla(Pattern.compile("\\bNCM\\b"), "codigo do produto"),
      new Sigla(Pattern.compile("\\bCFOP\\b"), "codigo da operacao"),
      new Sigla(Pattern.compile("\\bNF-e\\b"), "nota fiscal eletronica"));

  private ComposicaoRespostaDados() {}

  /**
   * Monta o texto da resposta.
   *
   * @param secoes seções registradas pelas funções, na ordem das chamadas
   * @return o texto composto; vazio se não houver seção
   */
  public static String compor(List<SecaoResposta> secoes) {
    List<SecaoResposta> unicas = new ArrayList<>(new LinkedHashSet<>(secoes));
    unicas.sort(Comparator.comparing(SecaoResposta::assunto));
    List<String> partes = new ArrayList<>();
    resumo(unicas).ifPresent(partes::add);
    unicas.stream()
        .filter(s -> !s.vazia() || s.pedida())
        .limit(MAXIMO_SECOES)
        .map(SecaoResposta::texto)
        .distinct()
        .forEach(partes::add);
    return explicarSiglas(String.join("\n\n", partes));
  }

  /** Linha do template com contagem calculada pelo backend ("E mais 7, na tela de Notas."). */
  private static final Pattern MAIS_NA_TELA =
      Pattern.compile("(?m)^E mais \\d+, na tela de \\w+\\.$");

  /**
   * Fontes do {@code ValidadorNumeros} que não estão nos resultados das funções: os pedaços de
   * texto livre das seções e as contagens que a própria composição calcula (a linha de resumo e o
   * "E mais N" das listas).
   */
  public static List<String> textosLivres(List<SecaoResposta> secoes) {
    List<String> livres = new ArrayList<>();
    secoes.forEach(s -> {
      livres.addAll(s.textosLivres());
      Matcher m = MAIS_NA_TELA.matcher(s.texto());
      while (m.find()) {
        livres.add(m.group());
      }
    });
    List<SecaoResposta> unicas = new ArrayList<>(new LinkedHashSet<>(secoes));
    unicas.sort(Comparator.comparing(SecaoResposta::assunto));
    resumo(unicas).ifPresent(livres::add);
    return livres;
  }

  /**
   * Linha de resumo: só com duas ou mais seções de atenção consultadas. Guias em atraso contam uma
   * vez: {@code guiasEmAtraso} e {@code vencimentosDoMes} chamadas juntas falam das mesmas guias, e
   * vale a seção de maior contagem.
   */
  static java.util.Optional<String> resumo(List<SecaoResposta> secoes) {
    List<SecaoResposta> atencao = new ArrayList<>(
        secoes.stream().filter(s -> s.assunto().deAtencao()).toList());
    List<SecaoResposta> atrasos = atencao.stream()
        .filter(s -> s.assunto() == Assunto.GUIAS_EM_ATRASO).toList();
    if (atrasos.size() > 1) {
      SecaoResposta maior = atrasos.stream()
          .max(Comparator.comparingInt(SecaoResposta::pontos)).orElseThrow();
      atencao.removeIf(s -> s.assunto() == Assunto.GUIAS_EM_ATRASO && s != maior);
    }
    Set<Assunto> assuntos = new LinkedHashSet<>();
    atencao.forEach(s -> assuntos.add(s.assunto()));
    if (assuntos.size() < 2) {
      return java.util.Optional.empty();
    }
    List<SecaoResposta> comPontos = atencao.stream().filter(s -> s.pontos() > 0).toList();
    List<String> semPendencia = atencao.stream()
        .filter(s -> s.pontos() == 0 && s.semPendencia() != null)
        .map(SecaoResposta::semPendencia).distinct().toList();
    if (comPontos.isEmpty()) {
      // Todas pedidas e vazias: as frases de cada uma ja dizem o recorte; o resumo so repetiria.
      boolean algumaSoNoResumo = atencao.stream().anyMatch(s -> !s.pedida());
      return algumaSoNoResumo
          ? java.util.Optional.of("Nao encontrei pontos de atencao em: " + lista(semPendencia)
              + ".")
          : java.util.Optional.empty();
    }
    int total = comPontos.stream().mapToInt(SecaoResposta::pontos).sum();
    StringBuilder linha = new StringBuilder("Encontrei ").append(total)
        .append(total == 1 ? " ponto de atencao: " : " pontos de atencao: ")
        .append(lista(comPontos.stream().map(SecaoResposta::resumo).distinct().toList()))
        .append('.');
    if (!semPendencia.isEmpty()) {
      linha.append(" Sem pendencias em: ").append(lista(semPendencia)).append('.');
    }
    return java.util.Optional.of(linha.toString());
  }

  /** "a", "a e b", "a, b e c". */
  static String lista(List<String> itens) {
    if (itens.size() <= 1) {
      return itens.isEmpty() ? "" : itens.get(0);
    }
    return String.join(", ", itens.subList(0, itens.size() - 1)) + " e "
        + itens.get(itens.size() - 1);
  }

  /** Explica cada sigla do glossário na primeira ocorrência, se o texto ainda não a explica. */
  static String explicarSiglas(String texto) {
    String resultado = texto;
    for (Sigla sigla : GLOSSARIO) {
      if (resultado.toLowerCase(java.util.Locale.ROOT).contains(sigla.explicacao())) {
        continue;
      }
      Matcher m = sigla.padrao().matcher(resultado);
      if (m.find()) {
        resultado = resultado.substring(0, m.end()) + " (" + sigla.explicacao() + ")"
            + resultado.substring(m.end());
      }
    }
    return resultado;
  }
}
