package br.com.agrocontabil.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Sinal de nota possivelmente emitida errada (issue #276): uma suspeita, nunca uma certeza. A
 * Ceres o mostra com frase fixa ("confira com seu contador"), montada pelo backend.
 *
 * @param codigo            qual sinal
 * @param motivo            por que disparou (a frase explica pelo motivo, sem refazer a regra)
 * @param momento           em que ponto da vida da nota o sinal aparece
 * @param numeroItem        item da nota (nulo num sinal da nota inteira)
 * @param descricaoItem     descrição do item, como digitada (vai ao usuário, nunca ao modelo)
 * @param cfop              CFOP do item
 * @param prazoCancelamento instante-limite para cancelar (RN006), só em
 *                          {@link Momento#AUTORIZADA_NO_PRAZO}
 * @param unidade           unidade do item (valor fora do padrão)
 * @param valor             valor unitário do item (valor fora do padrão)
 * @param referencia        mediana dos valores unitários de referência (valor fora do padrão)
 * @param outraNota         número da outra nota (duplicidade)
 */
public record SinalNota(CodigoSinalNota codigo, Motivo motivo, Momento momento,
    Integer numeroItem, String descricaoItem, String cfop, OffsetDateTime prazoCancelamento,
    String unidade, BigDecimal valor, BigDecimal referencia, Long outraNota) {

  /** Sinal de item sem valores (produto que parece serviço). */
  public SinalNota(CodigoSinalNota codigo, Motivo motivo, Momento momento, Integer numeroItem,
      String descricaoItem, String cfop, OffsetDateTime prazoCancelamento) {
    this(codigo, motivo, momento, numeroItem, descricaoItem, cfop, prazoCancelamento, null, null,
        null, null);
  }

  /** Sinais conhecidos. Sinal novo = código aqui + regra no service + frase fixa na Ceres. */
  public enum CodigoSinalNota {
    /** Item de NF-e de produto com cara de serviço (colheita, frete, aluguel...). */
    PRODUTO_PARECE_SERVICO,
    /** Outra nota própria com o mesmo destinatário, valor, itens e dia. */
    POSSIVEL_DUPLICIDADE,
    /** Valor unitário mais de 3 vezes acima ou abaixo da mediana do mesmo NCM e unidade. */
    VALOR_UNITARIO_FORA_PADRAO
  }

  /** Por que o sinal disparou. */
  public enum Motivo {
    /** CFOP "outra saída não especificada" (5949/6949) com termo de serviço na descrição. */
    CFOP_GENERICO,
    /** NCM ausente ou fora da referência (nota antiga, de antes da troca de código) e termo de
     * serviço. */
    NCM_FORA_DA_REFERENCIA,
    /** Mesmo destinatário, valor total, itens (NCM e quantidade) e dia de outra nota. */
    MESMA_VENDA,
    /** Valor unitário acima de 3 vezes a mediana. */
    ACIMA_DO_PADRAO,
    /** Valor unitário abaixo de um terço da mediana. */
    ABAIXO_DO_PADRAO
  }

  /** Em que ponto a nota está, para a frase dizer o que ainda dá para fazer. */
  public enum Momento {
    /** Ainda não autorizada (rascunho, revisão, XML gerado, rejeitada): dá para corrigir. */
    ANTES_DE_EMITIR,
    /** Autorizada, dentro dos 30 dias do cancelamento. */
    AUTORIZADA_NO_PRAZO,
    /** Autorizada, fora do prazo de cancelamento. */
    AUTORIZADA_FORA_DO_PRAZO
  }
}
