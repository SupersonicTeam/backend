package br.com.agrocontabil.config.avaliacao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Instrumento da nota humana de clareza (issue #264): CSV <strong>cego</strong> para o avaliador
 * externo (#275), mais a chave que liga cada linha à pergunta e à rodada.
 *
 * <p>O CSV só tem item, pergunta, resposta e a coluna da nota, em ordem embaralhada: sem rota,
 * variante, rodada nem resultado do gabarito, que enviesariam a nota. A chave vai num arquivo
 * separado, que não é entregue ao avaliador. Entram só as respostas que chegaram ao produtor sem
 * falha técnica: modo fallback (trecho de lei ou atalho, sem o modelo), tempo esgotado e erro não
 * medem a redação da Ceres.
 *
 * <p>Formato para o Excel em português: separador {@code ;} e UTF-8 com BOM (sem o BOM ele lê o
 * arquivo como ANSI e os acentos quebram).
 */
public final class CsvCego {

  /** Cabeçalho do CSV entregue ao avaliador. */
  static final String CABECALHO = "item;pergunta;resposta;nota_1_a_5;comentario";

  /** Marca de ordem de bytes do UTF-8, primeiro caractere do arquivo. */
  static final String BOM = "\uFEFF";

  private CsvCego() {}

  /**
   * Uma linha da chave.
   *
   * @param item   número da linha no CSV
   * @param id     id da pergunta no gabarito
   * @param rodada rodada (a partir de 1)
   */
  public record Chave(int item, String id, int rodada) {}

  /**
   * CSV e chave.
   *
   * @param csv   conteúdo do CSV (aspas da RFC 4180, separador {@code ;}, começa com o BOM)
   * @param chave item → pergunta e rodada
   */
  public record Instrumento(String csv, List<Chave> chave) {}

  /**
   * Monta o CSV cego das rodadas.
   *
   * @param repeticoes rodadas da avaliação
   * @param perguntas  id → texto da pergunta
   * @param aleatorio  embaralhamento (semente fixa nos testes)
   * @return CSV e chave
   */
  public static Instrumento gerar(RelatorioRepeticoes repeticoes, Map<String, String> perguntas,
      Random aleatorio) {
    record Linha(String id, int rodada, String texto) {}
    List<Linha> linhas = new ArrayList<>();
    for (int r = 0; r < repeticoes.rodadas().size(); r++) {
      for (ResultadoPergunta resultado : repeticoes.rodadas().get(r).resultados()) {
        String texto = resultado.execucao().texto();
        if (texto != null && !texto.isBlank()
            && !RelatorioAvaliacao.falhaTecnica(resultado.execucao())) {
          linhas.add(new Linha(resultado.id(), r + 1, texto));
        }
      }
    }
    Collections.shuffle(linhas, aleatorio);
    StringBuilder csv = new StringBuilder(BOM).append(CABECALHO).append("\r\n");
    List<Chave> chave = new ArrayList<>();
    for (int i = 0; i < linhas.size(); i++) {
      Linha linha = linhas.get(i);
      csv.append(i + 1).append(';').append(campo(perguntas.getOrDefault(linha.id(), "")))
          .append(';').append(campo(linha.texto())).append(";;\r\n");
      chave.add(new Chave(i + 1, linha.id(), linha.rodada()));
    }
    return new Instrumento(csv.toString(), List.copyOf(chave));
  }

  /**
   * Campo entre aspas, com as aspas internas dobradas (RFC 4180). Texto que começa com
   * {@code = + - @}, tabulação ou retorno de carro ganha um apóstrofo: a planilha do avaliador o
   * leria como fórmula.
   */
  static String campo(String valor) {
    boolean formula = !valor.isEmpty() && "=+-@\t\r".indexOf(valor.charAt(0)) >= 0;
    String seguro = formula ? "'" + valor : valor;
    return "\"" + seguro.replace("\"", "\"\"") + "\"";
  }
}
