package br.com.agrocontabil.assistente.rag;

/**
 * Trecho de lei recuperado para uma pergunta (issue #156).
 *
 * @param marca       marca que o modelo usa para citá-lo ({@code T1}…{@code Tn})
 * @param id          id do trecho em {@code legislacao_trecho} (vai para a auditoria)
 * @param norma       ex.: {@code LC 214/2025}
 * @param dispositivo ex.: {@code art. 138, § 1º}
 * @param url         link oficial
 * @param texto       texto do dispositivo
 * @param similaridade similaridade de cosseno com a pergunta (0 a 1)
 */
public record TrechoRecuperado(
    String marca, String id, String norma, String dispositivo, String url, String texto,
    double similaridade) {

  /** A citação que este trecho vira quando o modelo o marca. */
  public CitacaoLegal comoCitacao() {
    return new CitacaoLegal(norma, dispositivo, url, texto);
  }
}
