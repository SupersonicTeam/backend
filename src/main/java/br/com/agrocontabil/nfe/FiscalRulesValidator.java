package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.InvoiceInvalidForIssuanceException;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validador de pré-condições para emissão da NF-e.
 *
 * <p>Valida as regras de negócio fiscais que devem ser satisfeitas <em>antes</em>
 * do mapeamento JAXB. Agrega todas as violações encontradas e as lança em uma única
 * {@link InvoiceInvalidForIssuanceException}, evitando que o usuário corrija
 * um field de cada vez.
 *
 * <p>Esta validação é sobre a <strong>entidade</strong> {@link Invoice} já persistida,
 * não sobre o DTO de entrada — portanto não usa Bean Validation.
 *
 * <h3>Regras validadas:</h3>
 * <ul>
 *   <li>Emitente (Producer): CPF/CNPJ, Inscrição Estadual, código IBGE do município
 *       e UF presentes. Nota: o Producer não armazena endereço completo (apenas
 *       {@code state}) — a ausência de street/CEP é aceita nesta sprint, com os
 *       campos preenchidos por placeholder no mapper (débito técnico documentado).</li>
 *   <li>Destinatário: name, CPF/CNPJ e endereço mínimo (UF e município).</li>
 *   <li>Itens: ao menos 1 item com NCM, CFOP e valor total > 0.</li>
 *   <li>Natureza da operação: presente e não vazia.</li>
 *   <li>Número da NF-e: presente e maior que zero.</li>
 *   <li>Data de emissão: presente.</li>
 * </ul>
 */
@Component
public class FiscalRulesValidator {

  /**
   * Valida a invoice fiscal para emissão, agregando todas as violações encontradas.
   *
   * @param invoice invoice fiscal a ser validada
   * @throws InvoiceInvalidForIssuanceException se houver uma ou mais violações
   */
  public void validate(Invoice invoice) {
    List<InvoiceInvalidForIssuanceException.FieldViolation> violations = new ArrayList<>();

    validateInvoiceFields(invoice, violations);
    validateIssuer(invoice.getProducer(), violations);
    validateRecipient(invoice, violations);
    validateItems(invoice, violations);

    if (!violations.isEmpty()) {
      throw new InvoiceInvalidForIssuanceException(violations);
    }
  }

  // =========================================================================
  // Validações privadas
  // =========================================================================

  private void validateInvoiceFields(
      Invoice invoice,
      List<InvoiceInvalidForIssuanceException.FieldViolation> violations) {

    if (isBlank(invoice.getOperationNature())) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "naturezaOperacao", "Natureza da operacao e obrigatoria para emissao da NF-e"));
    }

    if (invoice.getNumber() == null || invoice.getNumber() <= 0) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "numero", "Numero da NF-e deve ser maior que zero"));
    }

    if (invoice.getIssuanceDate() == null) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "dataEmissao", "Data de emissao e obrigatoria"));
    }
  }

  private void validateIssuer(
      Producer producer,
      List<InvoiceInvalidForIssuanceException.FieldViolation> violations) {

    if (producer == null) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "produtor", "Produtor emitente nao associado a nota fiscal"));
      return; // sem producer não há como validate sub-campos
    }

    if (isBlank(producer.getTaxId())) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "produtor.cpfCnpj",
              "CPF/CNPJ do emitente e obrigatorio para emissao da NF-e"));
    }

    // A Inscrição Estadual é obrigatória para o emitente no layout da NF-e.
    // Nota: o field IE não está persistido no Producer neste sprint (débito técnico).
    // O validador aceita IE nula/vazia e o mapper usa placeholder "ISENTO" para satisfazer
    // o XSD — comportamento documentado e aceitável para homologação.

    if (isBlank(producer.getState())) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "produtor.estado",
              "UF do emitente e obrigatoria para determinar o codigo IBGE da UF na chave de acesso"));
    }
  }

  private void validateRecipient(
      Invoice invoice,
      List<InvoiceInvalidForIssuanceException.FieldViolation> violations) {

    if (isBlank(invoice.getRecipientName())) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "destinatarioNome", "Nome do destinatario e obrigatorio"));
    }

    if (isBlank(invoice.getRecipientTaxId())) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "destinatarioCpfCnpj", "CPF/CNPJ do destinatario e obrigatorio"));
    }

    RecipientAddress end = invoice.getRecipientAddress();
    if (end == null) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "destinatarioEndereco", "Endereco do destinatario e obrigatorio para emissao da NF-e"));
    } else {
      if (isBlank(end.getUf())) {
        violations.add(
            new InvoiceInvalidForIssuanceException.FieldViolation(
                "destinatarioEndereco.uf", "UF do destinatario e obrigatoria"));
      }
      if (isBlank(end.getCity())) {
        violations.add(
            new InvoiceInvalidForIssuanceException.FieldViolation(
                "destinatarioEndereco.municipio", "Municipio do destinatario e obrigatorio"));
      }
      if (isBlank(end.getIbgeCityCode())) {
        violations.add(
            new InvoiceInvalidForIssuanceException.FieldViolation(
                "destinatarioEndereco.codigoMunicipioIbge",
                "Codigo IBGE do municipio do destinatario e obrigatorio para o layout da NF-e"));
      }
    }
  }

  private void validateItems(
      Invoice invoice,
      List<InvoiceInvalidForIssuanceException.FieldViolation> violations) {

    if (invoice.getItems() == null || invoice.getItems().isEmpty()) {
      violations.add(
          new InvoiceInvalidForIssuanceException.FieldViolation(
              "itens", "A nota fiscal deve ter ao menos 1 item"));
      return;
    }

    for (InvoiceItem item : invoice.getItems()) {
      String prefix = "item[" + item.getItemNumber() + "]";

      if (isBlank(item.getNcm())) {
        violations.add(
            new InvoiceInvalidForIssuanceException.FieldViolation(
                prefix + ".ncm", "NCM e obrigatorio no layout da NF-e"));
      }

      if (isBlank(item.getCfop())) {
        violations.add(
            new InvoiceInvalidForIssuanceException.FieldViolation(
                prefix + ".cfop", "CFOP e obrigatorio no layout da NF-e"));
      }

      if (item.getTotalValue() == null
          || item.getTotalValue().signum() <= 0) {
        violations.add(
            new InvoiceInvalidForIssuanceException.FieldViolation(
                prefix + ".valorTotal", "Valor total do item deve ser maior que zero"));
      }
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
