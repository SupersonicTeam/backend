package br.com.agrocontabil.nfe;

import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.common.UfIbge;
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

  /** UF convencional de destinatário no exterior (exportação) — layout NF-e. */
  private static final String UF_EXTERIOR = "EX";

  /**
   * Valida a nota fiscal para emissão, agregando todas as violações encontradas.
   *
   * @param nota nota fiscal a ser validada
   * @throws NotaFiscalInvalidaParaEmissaoException se houver uma ou mais violações
   */
  public void validar(NotaFiscal nota) {
    List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations = new ArrayList<>();

    validarCamposDaNota(nota, violations);
    validarEmitente(nota, violations);
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
      NotaFiscal nota,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {

    Produtor produtor = nota.getProdutor();

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

    // A IE e obrigatoria para a NF-e modelo 55. Na NFA (produtor rural pessoa fisica), a ausencia
    // e legitima e o mapper emite "ISENTO" — e justamente o caso de uso do documento (issue #18).
    if (isBlank(produtor.getInscricaoEstadual())
        && nota.getTipoDocumento() != TipoDocumentoFiscal.NFA) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.inscricaoEstadual",
              "Inscricao Estadual do emitente e obrigatoria para emissao da NF-e modelo 55"));
    }

    if (isBlank(produtor.getEstado())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.estado",
              "UF do emitente e obrigatoria para determinar o codigo IBGE da UF na chave de acesso"));
    }

    validarEnderecoEmitente(produtor, violations);
  }

  /**
   * Valida o endereço do emitente (issue #88): antes desta issue o mapper preenchia o grupo
   * {@code <enderEmit>} com placeholders, e o XML — válido no XSD — seria rejeitado pela SEFAZ.
   */
  private void validarEnderecoEmitente(
      Produtor produtor,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {

    EnderecoProdutor endereco = produtor.getEndereco();
    if (endereco == null) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.endereco",
              "Endereco do emitente e obrigatorio para emissao da NF-e"));
      return;
    }

    exigir(endereco.getLogradouro(), "produtor.endereco.logradouro", "Logradouro", violations);
    exigir(endereco.getNumero(), "produtor.endereco.numero",
        "Numero (use S/N quando nao houver)", violations);
    exigir(endereco.getBairro(), "produtor.endereco.bairro", "Bairro", violations);
    exigir(endereco.getMunicipio(), "produtor.endereco.municipio", "Municipio", violations);
    exigir(endereco.getCep(), "produtor.endereco.cep", "CEP", violations);

    if (isBlank(endereco.getCodigoMunicipioIbge())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.endereco.codigoMunicipioIbge",
              "Codigo IBGE do municipio do emitente e obrigatorio para o layout da NF-e"));
    } else if (!isBlank(produtor.getEstado())
        && !UfIbge.prefixoCoerente(endereco.getCodigoMunicipioIbge(), produtor.getEstado())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.endereco.codigoMunicipioIbge",
              "Codigo IBGE do municipio do emitente nao pertence a UF "
                  + produtor.getEstado().toUpperCase()));
    }
  }

  private void exigir(
      String valor,
      String campo,
      String rotulo,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {
    if (isBlank(valor)) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              campo, rotulo + " do emitente e obrigatorio para emissao da NF-e"));
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
      } else if (!isBlank(end.getUf())
          && !UF_EXTERIOR.equalsIgnoreCase(end.getUf().trim())
          && !UfIbge.prefixoCoerente(end.getCodigoMunicipioIbge(), end.getUf())) {
        // UF "EX" identifica destinatario no exterior (exportacao): o codigo de municipio nao
        // pertence a nenhuma UF brasileira, entao a coerencia de prefixo nao se aplica.
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.codigoMunicipioIbge",
                "Codigo IBGE do municipio do destinatario nao pertence a UF "
                    + end.getUf().toUpperCase()));
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
      } else {
        validarAmbitoCfop(nota, item, prefixo, violations);
      }

      if (item.getValorTotal() == null
          || item.getValorTotal().signum() <= 0) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                prefixo + ".valorTotal", "Valor total do item deve ser maior que zero"));
      }
    }
  }

  /**
   * Valida a compatibilidade entre o âmbito codificado no 1.º dígito do CFOP e a relação real
   * de UFs (emitente × destinatário) — RN008, issue #20.
   *
   * <p>O 1.º dígito de um CFOP de saída codifica o âmbito da operação:
   * <ul>
   *   <li>{@code 5} → operação <strong>interna</strong> (mesma UF do emitente);</li>
   *   <li>{@code 6} → operação <strong>interestadual</strong> (UF diferente);</li>
   *   <li>{@code 7} → operação com o <strong>exterior</strong> (UF do destinatário {@code EX}).</li>
   * </ul>
   *
   * <p>Esta validação só roda quando há UF de emitente e de destinatário; a ausência de UF já é
   * sinalizada por outras regras ({@code validarEmitente}/{@code validarDestinatario}) — não
   * duplicamos a violação aqui.
   */
  private void validarAmbitoCfop(
      NotaFiscal nota,
      NotaFiscalItem item,
      String prefixo,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {

    char primeiroDigito = item.getCfop().charAt(0);

    Produtor produtor = nota.getProdutor();
    EnderecoDestinatario endereco = nota.getDestinatarioEndereco();

    String ufEmitente = produtor == null ? null : produtor.getEstado();
    String ufDestinatario = endereco == null ? null : endereco.getUf();

    // Sem alguma das UFs não há como avaliar o âmbito — outras regras já cobrem a ausência.
    if (isBlank(ufEmitente) || isBlank(ufDestinatario)) {
      return;
    }

    String emitente = ufEmitente.trim().toUpperCase();
    String destinatario = ufDestinatario.trim().toUpperCase();

    boolean operacaoExterior = "EX".equals(destinatario);
    boolean mesmaUf = emitente.equals(destinatario);

    switch (primeiroDigito) {
      case '5' -> {
        if (!mesmaUf) {
          violations.add(
              new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                  prefixo + ".cfop",
                  "CFOP de operacao interna (iniciado em 5) e incompativel com operacao "
                      + "interestadual (" + emitente + " -> " + destinatario
                      + "). Use um CFOP iniciado em 6."));
        }
      }
      case '6' -> {
        if (mesmaUf) {
          violations.add(
              new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                  prefixo + ".cfop",
                  "CFOP de operacao interestadual (iniciado em 6) e incompativel com operacao "
                      + "interna (mesma UF " + emitente
                      + "). Use um CFOP iniciado em 5."));
        }
      }
      case '7' -> {
        if (!operacaoExterior) {
          violations.add(
              new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                  prefixo + ".cfop",
                  "CFOP de operacao com o exterior (iniciado em 7) exige destinatario no "
                      + "exterior (UF 'EX'). Para operacao nacional use CFOP iniciado em 5 ou 6."));
        }
      }
      default -> {
        // Outros 1.os dígitos (1,2,3 = entradas) não são esperados na saída do produtor;
        // não bloqueamos aqui para não restringir cenários futuros (ex.: importação — issue #19).
      }
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
