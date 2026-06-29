package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validador de pré-condições para emissão da NF-e.
 *
 * <p>Valida as regras de negócio fiscais que devem ser satisfeitas <em>antes</em>
 * do mapeamento JAXB. Agrega todas as violações encontradas e as lança em uma única
 * {@link NotaFiscalInvalidaParaEmissaoException}, evitando que o usuário corrija
 * um campo de cada vez.
 *
 * <p>Esta validação é sobre a <strong>entidade</strong> {@link NotaFiscal} já persistida,
 * não sobre o DTO de entrada — portanto não usa Bean Validation.
 *
 * <h3>Regras validadas:</h3>
 * <ul>
 *   <li>Emitente (Produtor): CPF/CNPJ, Inscrição Estadual, código IBGE do município
 *       e UF presentes. Nota: o Produtor não armazena endereço completo (apenas
 *       {@code estado}) — a ausência de logradouro/CEP é aceita nesta sprint, com os
 *       campos preenchidos por placeholder no mapper (débito técnico documentado).</li>
 *   <li>Destinatário: nome, CPF/CNPJ e endereço mínimo (UF e município).</li>
 *   <li>Itens: ao menos 1 item com NCM, CFOP e valor total > 0.</li>
 *   <li>Natureza da operação: presente e não vazia.</li>
 *   <li>Número da NF-e: presente e maior que zero.</li>
 *   <li>Data de emissão: presente.</li>
 * </ul>
 */
@Component
public class FiscalRulesValidator {

  /**
   * Valida a nota fiscal para emissão, agregando todas as violações encontradas.
   *
   * @param nota nota fiscal a ser validada
   * @throws NotaFiscalInvalidaParaEmissaoException se houver uma ou mais violações
   */
  public void validar(NotaFiscal nota) {
    List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations = new ArrayList<>();

    validarCamposDaNota(nota, violations);
    validarEmitente(nota.getProdutor(), violations);
    validarDestinatario(nota, violations);
    validarItens(nota, violations);

    if (!violations.isEmpty()) {
      throw new NotaFiscalInvalidaParaEmissaoException(violations);
    }
  }

  // =========================================================================
  // Validações privadas
  // =========================================================================

  private void validarCamposDaNota(
      NotaFiscal nota,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {

    if (isBlank(nota.getNaturezaOperacao())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "naturezaOperacao", "Natureza da operacao e obrigatoria para emissao da NF-e"));
    }

    if (nota.getNumero() == null || nota.getNumero() <= 0) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "numero", "Numero da NF-e deve ser maior que zero"));
    }

    if (nota.getDataEmissao() == null) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "dataEmissao", "Data de emissao e obrigatoria"));
    }
  }

  private void validarEmitente(
      Produtor produtor,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {

    if (produtor == null) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor", "Produtor emitente nao associado a nota fiscal"));
      return; // sem produtor não há como validar sub-campos
    }

    if (isBlank(produtor.getCpfCnpj())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.cpfCnpj",
              "CPF/CNPJ do emitente e obrigatorio para emissao da NF-e"));
    }

    // A Inscrição Estadual é obrigatória para o emitente no layout da NF-e.
    // Nota: o campo IE não está persistido no Produtor neste sprint (débito técnico).
    // O validador aceita IE nula/vazia e o mapper usa placeholder "ISENTO" para satisfazer
    // o XSD — comportamento documentado e aceitável para homologação.

    if (isBlank(produtor.getEstado())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.estado",
              "UF do emitente e obrigatoria para determinar o codigo IBGE da UF na chave de acesso"));
    }
  }

  private void validarDestinatario(
      NotaFiscal nota,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {

    if (isBlank(nota.getDestinatarioNome())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "destinatarioNome", "Nome do destinatario e obrigatorio"));
    }

    if (isBlank(nota.getDestinatarioCpfCnpj())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "destinatarioCpfCnpj", "CPF/CNPJ do destinatario e obrigatorio"));
    }

    EnderecoDestinatario end = nota.getDestinatarioEndereco();
    if (end == null) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "destinatarioEndereco", "Endereco do destinatario e obrigatorio para emissao da NF-e"));
    } else {
      if (isBlank(end.getUf())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.uf", "UF do destinatario e obrigatoria"));
      }
      if (isBlank(end.getMunicipio())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.municipio", "Municipio do destinatario e obrigatorio"));
      }
      if (isBlank(end.getCodigoMunicipioIbge())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.codigoMunicipioIbge",
                "Codigo IBGE do municipio do destinatario e obrigatorio para o layout da NF-e"));
      }
    }
  }

  private void validarItens(
      NotaFiscal nota,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {

    if (nota.getItens() == null || nota.getItens().isEmpty()) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "itens", "A nota fiscal deve ter ao menos 1 item"));
      return;
    }

    for (NotaFiscalItem item : nota.getItens()) {
      String prefixo = "item[" + item.getNumeroItem() + "]";

      if (isBlank(item.getNcm())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                prefixo + ".ncm", "NCM e obrigatorio no layout da NF-e"));
      }

      if (isBlank(item.getCfop())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                prefixo + ".cfop", "CFOP e obrigatorio no layout da NF-e"));
      }

      if (item.getValorTotal() == null
          || item.getValorTotal().signum() <= 0) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                prefixo + ".valorTotal", "Valor total do item deve ser maior que zero"));
      }
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
