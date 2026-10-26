package br.com.agrocontabil.model;

/**
 * Resultado da verificação de antivírus de um anexo de pendência (issue #227).
 *
 * <p>Falha fechada: o anexo só pode ser baixado {@code LIMPO}. Com o antivírus fora do ar ele
 * fica {@code EM_VERIFICACAO} até o job conseguir verificar; {@code INFECTADO} guarda o hash e o
 * nome da assinatura, nunca o arquivo.
 */
public enum SituacaoAnexo {
  /** Recebido, ainda não verificado (antivírus fora do ar, ou anexo anterior à #227). */
  EM_VERIFICACAO,
  /** Verificado sem ameaça: disponível para download. */
  LIMPO,
  /** O antivírus achou ameaça: o conteúdo foi descartado. */
  INFECTADO
}
