package br.com.agrocontabil.assistente;

import java.util.List;
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
 */
public final class DataSanitizer {

  /** Um padrão e o marcador que o substitui, aplicados em ordem (o mais longo primeiro). */
  private record Regra(Pattern padrao, String marcador) {}

  private static final List<Regra> REGRAS = List.of(
      // Chave de acesso da NF-e: 44 digitos.
      new Regra(Pattern.compile("(?<![\\d])\\d{44}(?![\\d])"), "[CHAVE]"),
      // CNPJ antes do CPF (14 digitos contem 11).
      new Regra(Pattern.compile(
          "(?<![\\d.])\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}(?![\\d])"), "[CNPJ]"),
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
      resultado = regra.padrao().matcher(resultado).replaceAll(regra.marcador());
    }
    return resultado;
  }
}
