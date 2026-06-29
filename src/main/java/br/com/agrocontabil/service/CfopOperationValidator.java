package br.com.agrocontabil.service;

import br.com.agrocontabil.exception.IncompatibleCfopException;
import br.com.agrocontabil.model.OperationScope;
import br.com.agrocontabil.model.RuralProducerCfop;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.OperationType;
import br.com.agrocontabil.repository.RuralProducerCfopRepository;
import org.springframework.stereotype.Component;

/**
 * Valida a compatibilidade do CFOP de cada item com o tipo de operação da invoice.
 *
 * <p>O model atual emite sempre <strong>saída</strong> ({@code tpNF=1}); o âmbito é
 * derivado da UF do emitente (producer) versus a UF do destinatário:
 * {@link OperationScope#INTERNA} se iguais, {@link OperationScope#INTERESTADUAL} se
 * diferentes. Para cada item, carrega os metadados do CFOP e exige:
 * <ul>
 *   <li>{@code operationType == SAIDA} (rejeita CFOP de entrada — 1xxx/2xxx — numa saída);</li>
 *   <li>{@code scope} igual ao esperado, quando determinável.</li>
 * </ul>
 *
 * <p>Se a UF do emitente ou do destinatário estiver ausente (endereço opcional em
 * rascunho), o âmbito é indeterminável e a checagem de âmbito é ignorada (mantém-se a
 * de tipo de operação). A existência do CFOP na referência é responsabilidade de
 * {@code @ValidCfop} no request; aqui, CFOP ausente é ignorado.
 *
 * <p>Violações lançam {@link IncompatibleCfopException} (HTTP 400) com mensagem descritiva.
 */
@Component
public class CfopOperationValidator {

  private final RuralProducerCfopRepository repository;

  public CfopOperationValidator(RuralProducerCfopRepository repository) {
    this.repository = repository;
  }

  /**
   * Valida todos os items da invoice quanto à compatibilidade CFOP × operação.
   *
   * @param invoice invoice fiscal com producer, endereço do destinatário e items
   * @throws IncompatibleCfopException se algum item tiver CFOP incompatível
   */
  public void validate(Invoice invoice) {
    OperationScope expectedScope = resolveScope(invoice);

    for (InvoiceItem item : invoice.getItems()) {
      RuralProducerCfop cfop = repository.findByCodigo(item.getCfop()).orElse(null);
      if (cfop == null) {
        continue; // existência tratada por @ValidCfop no request
      }

      if (cfop.getOperationType() != OperationType.SAIDA) {
        throw new IncompatibleCfopException(
            "Item " + item.getItemNumber() + ": CFOP " + item.getCfop()
                + " e de operacao de ENTRADA, incompativel com a saida da nota fiscal. "
                + "Use um CFOP de saida (5xxx interna / 6xxx interestadual).");
      }

      if (expectedScope != null && cfop.getScope() != expectedScope) {
        String suggestion = expectedScope == OperationScope.INTERNA ? "5xxx" : "6xxx";
        throw new IncompatibleCfopException(
            "Item " + item.getItemNumber() + ": CFOP " + item.getCfop()
                + " (" + cfop.getScope() + ") incompativel com operacao " + expectedScope
                + ". Use um CFOP " + suggestion + ".");
      }
    }
  }

  /**
   * Determina o âmbito esperado a partir das UFs do emitente e do destinatário.
   *
   * @return {@code INTERNA}/{@code INTERESTADUAL}, ou {@code null} se indeterminável
   */
  private OperationScope resolveScope(Invoice invoice) {
    Producer producer = invoice.getProducer();
    String issuerUf = producer != null ? producer.getState() : null;
    String recipientUf = invoice.getRecipientAddress() != null
        ? invoice.getRecipientAddress().getUf() : null;

    if (issuerUf == null || issuerUf.isBlank() || recipientUf == null || recipientUf.isBlank()) {
      return null;
    }
    return issuerUf.equalsIgnoreCase(recipientUf)
        ? OperationScope.INTERNA : OperationScope.INTERESTADUAL;
  }
}
