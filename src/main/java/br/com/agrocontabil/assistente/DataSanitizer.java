package br.com.agrocontabil.assistente;

import br.com.agrocontabil.common.DocumentoFiscalValidator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Máscara de dados pessoais no que vai ao modelo da Ceres (issue #144).
 *
 * <p>Tudo que uma função devolve e a pergunta do usuário vão ao provedor do modelo. Com o Ollama
 * ficam na máquina; com um provedor por API <em>saem do servidor</em>. Como o provedor é trocável
 * por configuração, a regra vale igual nos dois casos.
 *
 * <p>Duas camadas:
 * <ol>
 *   <li><strong>Minimização por construção</strong> (a principal): os DTOs das funções
 *       ({@code RespostasFerramentas}) não têm campo de nome, CPF/CNPJ, endereço, e-mail,
 *       telefone, chave de acesso nem observação livre — um teste trava isso. Nome e endereço não
 *       se detectam com confiança por expressão regular; por isso eles simplesmente não entram;</li>
 *   <li><strong>esta máscara</strong> (rede de segurança): o que tem formato reconhecível — CPF,
 *       CNPJ, chave de acesso, e-mail, telefone, CEP — é trocado por um marcador, venha de onde
 *       vier (pergunta, parâmetro ecoado, campo novo esquecido).</li>
 * </ol>
 *
 * <p>Os padrões exigem que o número esteja isolado (sem dígito colado antes ou depois): valores
 * como {@code R$ 123.456.789,01} e datas não são confundidos com documento.
 *
 * <p><strong>CNPJ alfanumérico da RFB (issue #313):</strong> 12 posições com dígito ou letra e 2
 * dígitos verificadores ({@code 12.ABC.345/01DE-35}, IN RFB 2.229/2024), com e sem pontuação, viram
 * {@code [CNPJ]} só quando os dígitos verificadores conferem, pela mesma conta da máscara de log
 * ({@link DocumentoFiscalValidator#isCnpjAlfanumericoValido}, um ponto único): sem isso, todo
 * token de 14 caracteres terminado em 2 dígitos (hash, código) sairia {@code [CNPJ]}. A diferença
 * em relação ao log é a caixa, por decisão do Murillo (2026-11-11, issue #313): o log recusa
 * minúscula porque carrega hash em hex minúsculo; aqui a pergunta é texto do usuário, digitado em
 * qualquer caixa, e deixar um CNPJ passar ao provedor custa mais que mascarar um token por engano
 * (que, com o DV exigido, é raro). O texto é passado a maiúsculas só para conferir o DV.
 */
public final class DataSanitizer {

  /**
   * Um padrão, o marcador que o substitui e a conferência do trecho achado (o que não confere
   * fica como está), aplicados em ordem (o mais longo primeiro).
   */
  private record Regra(Pattern padrao, String marcador, Predicate<String> confere) {

    Regra(Pattern padrao, String marcador) {
      this(padrao, marcador, trecho -> true);
    }
  }

  /** Pontuação do CNPJ, tirada antes de conferir o DV do alfanumérico. */
  private static final Pattern PONTUACAO_CNPJ = Pattern.compile("[./-]");

  private static final List<Regra> REGRAS = List.of(
      // Chave de acesso da NF-e: 44 digitos.
      new Regra(Pattern.compile("(?<![\\d])\\d{44}(?![\\d])"), "[CHAVE]"),
      // CNPJ antes do CPF (14 digitos contem 11).
      new Regra(Pattern.compile(
          "(?<![\\d.])\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}(?![\\d])"), "[CNPJ]"),
      // CNPJ alfanumerico (#313): so com o DV conferido, em qualquer caixa. Depois do numerico, que
      // ja trocou os so de digitos, e antes do CPF.
      new Regra(Pattern.compile("(?<![A-Za-z0-9.])[0-9A-Za-z]{2}\\.?[0-9A-Za-z]{3}\\.?"
          + "[0-9A-Za-z]{3}/?[0-9A-Za-z]{4}-?\\d{2}(?![A-Za-z0-9])"), "[CNPJ]",
          trecho -> DocumentoFiscalValidator.isCnpjAlfanumericoValido(
              PONTUACAO_CNPJ.matcher(trecho).replaceAll("").toUpperCase(Locale.ROOT))),
      new Regra(Pattern.compile(
          "(?<![\\d.])\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?![\\d])"), "[CPF]"),
      new Regra(Pattern.compile(
          "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]"),
      // Telefone com DDD entre parenteses ou com +55.
      new Regra(Pattern.compile(
          "(?:\\+55\\s?)?\\(\\d{2}\\)\\s?9?\\d{4}-?\\d{4}(?![\\d])"), "[TELEFONE]"),
      new Regra(Pattern.compile("\\+55\\s?\\d{2}\\s?9?\\d{4}-?\\d{4}(?![\\d])"), "[TELEFONE]"),
      new Regra(Pattern.compile("(?<![\\d])\\d{5}-\\d{3}(?![\\d])"), "[CEP]"));

  private DataSanitizer() {}

  /**
   * Troca dados pessoais reconhecíveis por marcadores.
   *
   * @param texto texto a enviar ao modelo (ou a gravar na auditoria)
   * @return o texto mascarado; {@code null} se {@code texto} for nulo
   */
  public static String mascarar(String texto) {
    if (texto == null || texto.isEmpty()) {
      return texto;
    }
    String resultado = texto;
    for (Regra regra : REGRAS) {
      // O trecho que nao confere volta como veio, protegido: um "$" ou uma "\" nele seriam lidos
      // como referencia de grupo pela troca.
      resultado = regra.padrao().matcher(resultado).replaceAll(achado ->
          regra.confere().test(achado.group())
              ? regra.marcador()
              : Matcher.quoteReplacement(achado.group()));
    }
    return resultado;
  }
}
