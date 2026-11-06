package br.com.agrocontabil.support;

import java.util.regex.Pattern;

/**
 * Detecta, em texto que chega à tela do produtor, palavras do catálogo fiscal grafadas sem acento
 * (issue #408). A lista é de radicais que, em português, sempre levam acento ou cedilha; sigla,
 * norma e nome próprio não entram.
 */
public final class TextoSemAcento {

  private static final Pattern PADRAO = Pattern.compile(
      "(?iu)\b(apuracao|declaracao|escrituracao|contribuicao|comercializacao|informacoes|"
          + "retencoes|socioeconomicas|debitos|creditos|tributarios|previdenciarios|imovel|"
          + "contabil|fisica|ultimo|util|mes|competencia|bancario|serie)\b");

  private TextoSemAcento() {
  }

  /** A primeira palavra sem acento do texto, ou {@code null} se não houver. */
  public static String primeiraPalavraSemAcento(String texto) {
    if (texto == null) {
      return null;
    }
    var m = PADRAO.matcher(texto);
    return m.find() ? m.group() : null;
  }
}
