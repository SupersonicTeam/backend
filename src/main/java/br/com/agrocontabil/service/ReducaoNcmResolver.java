package br.com.agrocontabil.service;

import br.com.agrocontabil.model.ClassificacaoTributaria;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Resolve a redução de IBS/CBS aplicável a um item de nota fiscal (Sprint 4 — issue
 * Agrocontabil#30; simetria débito × crédito — issue #52; tabela oficial — issue #272).
 *
 * <p>Componente <strong>único</strong> da regra de redução, consumido pelo cálculo de
 * <em>débito</em> ({@link TributacaoService}) e de <em>crédito</em>
 * ({@link CreditoTributarioService}): os dois saem do mesmo ponto e a apuração fica simétrica.
 *
 * <p><strong>Precedência:</strong>
 * <ol>
 *   <li>se o item informa {@code percentualReducao} (não nulo), esse valor prevalece, com o
 *       {@code cClassTrib} informado (pode ser nulo);</li>
 *   <li>se informa só o {@code cClassTrib}, vale a redução oficial desse código;</li>
 *   <li>senão, o tratamento do NCM na tabela oficial ({@link ClassificacaoTributariaService}):
 *       anexo de alíquota zero (100%), in natura (60%), anexo de 60% ou integral (0%).</li>
 * </ol>
 * A vigência é a da data de emissão da nota em {@code America/Sao_Paulo} (hoje, sem data).
 *
 * <p>A redução de 100% (alíquota zero do Anexo I e do Anexo XV) é válida: a base fica zero.
 *
 * <p>Consulta tabelas de referência; deve ser chamado dentro de uma transação (read-only).
 */
@Component
public class ReducaoNcmResolver {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final ClassificacaoTributariaService classificacaoService;

  /**
   * Constrói o resolver.
   *
   * @param classificacaoService consulta da tabela oficial de classificação tributária
   */
  public ReducaoNcmResolver(ClassificacaoTributariaService classificacaoService) {
    this.classificacaoService = classificacaoService;
  }

  /**
   * Percentual de redução efetivo do item.
   *
   * @param item item da nota fiscal
   * @return percentual de 0 a 100, nunca nulo
   */
  public BigDecimal resolver(NotaFiscalItem item) {
    return tratamento(item).percentualReducao();
  }

  /**
   * Redução e {@code cClassTrib} aplicados ao item, pela precedência da classe.
   *
   * @param item item da nota fiscal
   * @return tratamento aplicado, nunca nulo
   */
  public ReducaoAplicada tratamento(NotaFiscalItem item) {
    if (item.getPercentualReducao() != null) {
      return new ReducaoAplicada(item.getPercentualReducao(), item.getCClassTrib());
    }
    LocalDate data = dataReferencia(item.getNotaFiscal());
    if (item.getCClassTrib() != null) {
      // Sobrescrita manual so do cClassTrib: a reducao e a oficial do codigo, para o XML nao
      // declarar uma classificacao e calcular outra. Codigo inexistente fica sem reducao.
      BigDecimal oficial = classificacaoService.classificacao(item.getCClassTrib(), data)
          .map(ClassificacaoTributaria::getPercentualReducaoIbs)
          .orElse(BigDecimal.ZERO);
      return new ReducaoAplicada(oficial, item.getCClassTrib());
    }
    String ncm = item.getNcm();
    if (ncm == null || ncm.isBlank()) {
      return new ReducaoAplicada(BigDecimal.ZERO, null);
    }
    TratamentoTributarioNcm tratamento = classificacaoService.tratamentoPorNcm(ncm, data);
    return new ReducaoAplicada(tratamento.percentualReducao(), tratamento.cClassTrib());
  }

  private static LocalDate dataReferencia(NotaFiscal nota) {
    if (nota == null || nota.getDataEmissao() == null) {
      return LocalDate.now(ZONE);
    }
    return nota.getDataEmissao().atZoneSameInstant(ZONE).toLocalDate();
  }

  /**
   * Redução aplicada a um item.
   *
   * @param percentualReducao de 0 a 100
   * @param cClassTrib classificação tributária da regra ou a informada; nula quando o cliente
   *     informou só o percentual
   */
  public record ReducaoAplicada(BigDecimal percentualReducao, String cClassTrib) {
  }
}
