package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.SecaoResposta.Assunto;
import br.com.agrocontabil.common.NormalizacaoBusca;
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
 *       do backend: "Encontrei 3 pontos de atenção: 2 guias em atraso e 1 nota devolvida." ou, sem
 *       nenhum, "Não encontrei pontos de atenção em: ..." com o recorte de cada consulta.</li>
 *   <li><strong>Seção vazia</strong> só aparece se a pergunta pediu aquele assunto (a função foi
 *       chamada direto); a do resumo do produtor entra só no resumo, como "sem pendências
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
      Pattern.compile("(?m)^E mais \\d+, na tela de \\p{L}+\\.$");

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
          ? java.util.Optional.of("Não encontrei pontos de atenção em: " + lista(semPendencia)
              + ".")
          : java.util.Optional.empty();
    }
    int total = comPontos.stream().mapToInt(SecaoResposta::pontos).sum();
    StringBuilder linha = new StringBuilder("Encontrei ").append(total)
        .append(total == 1 ? " ponto de atenção: " : " pontos de atenção: ")
        .append(lista(comPontos.stream().map(SecaoResposta::resumo).distinct().toList()))
        .append('.');
    if (!semPendencia.isEmpty()) {
      linha.append(" Sem pendências em: ").append(lista(semPendencia)).append('.');
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

  /**
   * Explica cada sigla do glossário do produtor ({@link GlossarioProdutor}, as marcadas
   * {@code emLinha}) na primeira ocorrência, se o texto ainda não a explica.
   */
  static String explicarSiglas(String texto) {
    String resultado = texto;
    // A comparação (só ela) ignora acento e caixa: a explicação já escrita por outro texto do
    // backend ("serviços" ou "servicos") conta, e a explicação inserida segue acentuada. O texto
    // é normalizado uma vez e de novo só quando uma explicação é inserida.
    String normalizado = NormalizacaoBusca.normalizar(resultado);
    for (GlossarioProdutor.Termo sigla : GlossarioProdutor.padrao().termos()) {
      if (!sigla.emLinha() || sigla.explicadoEm(normalizado)) {
        continue;
      }
      Matcher m = sigla.padrao().matcher(resultado);
      if (m.find()) {
        resultado = resultado.substring(0, m.end()) + formaPorExtenso(resultado, m.end(), sigla)
            + resultado.substring(m.end());
        normalizado = NormalizacaoBusca.normalizar(resultado);
      }
    }
    return resultado;
  }

  /**
   * A forma por extenso que entra depois da sigla: entre parênteses ("IBS (imposto sobre bens e
   * serviços)") ou, se a sigla já está dentro de parênteses abertos no mesmo parágrafo, entre
   * vírgulas ("(LC 214/2025, lei complementar, arts. 31 e 32)"), sem aninhar.
   */
  private static String formaPorExtenso(String texto, int fim, GlossarioProdutor.Termo sigla) {
    int inicioDoParagrafo = texto.lastIndexOf('\n', fim - 1) + 1;
    int abertos = 0;
    for (int i = inicioDoParagrafo; i < fim; i++) {
      char c = texto.charAt(i);
      if (c == '(') {
        abertos++;
      } else if (c == ')' && abertos > 0) {
        abertos--;
      }
    }
    if (abertos == 0) {
      return " (" + sigla.nome() + ")";
    }
    boolean fechaOuSeparaLogo = fim < texto.length() && ",);.".indexOf(texto.charAt(fim)) >= 0;
    return ", " + sigla.nome() + (fechaOuSeparaLogo ? "" : ",");
  }
}
