package br.com.agrocontabil.support;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta, em mensagem de erro que chega ao usuário, palavra escrita sem o acento que o português
 * exige (issue #476). Mais larga que o {@link TextoSemAcento} (catálogo, #408): lista de palavras e
 * terminações que sem acento são sempre erro ({@code nao}, {@code -cao}, {@code -ivel}...).
 *
 * <p>Fica de fora o que é nome técnico, que tem de sair como está:
 * <ul>
 *   <li>trecho entre aspas simples ({@code 'periodo'}, {@code 'ate'}: nome de parâmetro);</li>
 *   <li>palavra com barra ({@code /api/escritorio/endereco}, {@code de/ate}) ou com ponto no
 *       meio ({@code app.apuracao.lote}, {@code item[1].ncm});</li>
 *   <li>palavra com maiúscula no meio ou toda em maiúsculas ({@code cClassTrib},
 *       {@code naturezaOperacao}, {@code COM_INCONSISTENCIAS}).</li>
 * </ul>
 * Palavra que existe com e sem acento ({@code e}/{@code é}, {@code esta}/{@code está},
 * {@code valida}/{@code válida}) também fica de fora: só a revisão pega.
 */
public final class MensagemSemAcento {

  /** Palavras que, sem acento, são sempre erro de escrita. */
  private static final Set<String> PALAVRAS = Set.of(
      "nao", "voce", "voces", "ja", "ate", "apos", "alem", "atraves", "porem", "tambem",
      "entao", "sera", "serao", "estao", "so", "sao", "mes", "nivel", "util", "facil", "dificil",
      "ninguem", "alguem", "tera", "podera", "devera", "numero", "numeros", "codigo", "codigos",
      "periodo", "periodos", "pagina", "paginas", "metodo", "maximo", "maxima", "minimo",
      "minima", "valido", "validos", "validas", "invalido", "invalida", "invalidos", "invalidas",
      "proximo", "proxima", "proximos", "proximas", "ultimo", "ultima", "ultimos", "ultimas",
      "unico", "unica", "proprio", "propria", "proprios", "proprias", "credito", "creditos",
      "debito", "debitos", "digito", "digitos", "indice", "municipio", "municipios", "servico",
      "servicos", "endereco", "enderecos", "preco", "precos", "conteudo", "saida", "saidas",
      "titulo", "modulo", "simbolo", "parametro", "parametros", "criterio", "criterios",
      "historico", "analise", "hifen", "orgao", "aliquota", "aliquotas", "vinculo", "vinculos",
      "obrigatorio", "obrigatoria", "obrigatorios", "obrigatorias", "necessaria", "contabil",
      "publico", "especifico", "tecnico", "tecnica", "juridica", "juridico",
      "fisica", "fisico", "automatico", "automatica", "numerico", "numerica", "numericos",
      "seguranca", "cobranca", "mudanca", "lancamento", "lancamentos", "comeca", "area",
      "areas", "negocio", "agronegocio", "inicio", "calculo", "peca", "pecas", "antivirus",
      "ameaca", "varias", "varios", "politica", "hipotese", "escritorio", "escritorios",
      "relatorio", "relatorios", "padrao", "serie", "baixa-lo", "tira-la");

  /**
   * Terminações que, sem acento, são sempre erro: {@code -ao} e {@code -oes} ({@code emissao},
   * {@code padrao}, {@code operacoes}; o "ao" sozinho, contração, não casa), {@code -ivel},
   * {@code -avel}, {@code -encia}, {@code -ancia} e {@code -ario}.
   */
  private static final Pattern TERMINACAO = Pattern.compile(
      "\\p{L}+(ao|oes|ivel|iveis|avel|aveis|encia|encias|ancia|ancias|ario|arios)");

  /**
   * Nomes técnicos: entre aspas simples, palavra com barra ({@code /api/x}, {@code de/ate}) ou com
   * ponto no meio ({@code app.apuracao.lote}, {@code item[1].ncm}, {@code {slug}.agroconia}).
   */
  private static final Pattern TECNICO = Pattern.compile(
      "'[^']*'|[^\\s'\"(]*/[^\\s'\")]*|[^\\s'\"(]*[\\p{L}\\d}\\]]\\.[\\p{L}\\d{\\[][^\\s'\")]*");

  private static final Pattern PALAVRA = Pattern.compile("[\\p{L}-]+");

  private MensagemSemAcento() {
  }

  /**
   * A primeira palavra sem acento da mensagem, ou {@code null} se não houver.
   *
   * @param texto mensagem (pode ser um pedaço de uma mensagem concatenada)
   * @return a palavra, como está no texto
   */
  public static String primeiraPalavraSemAcento(String texto) {
    if (texto == null) {
      return null;
    }
    Matcher palavra = PALAVRA.matcher(TECNICO.matcher(texto).replaceAll(" "));
    while (palavra.find()) {
      String original = palavra.group().replaceAll("^-+|-+$", "");
      if (original.isEmpty() || !original.substring(1).equals(original.substring(1).toLowerCase(
          Locale.ROOT))) {
        continue;
      }
      String minuscula = original.toLowerCase(Locale.ROOT);
      if (PALAVRAS.contains(minuscula) || TERMINACAO.matcher(minuscula).matches()) {
        return original;
      }
    }
    return null;
  }
}
