package br.com.agrocontabil.nfe;

import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.ClassificacaoTributaria;
import br.com.agrocontabil.service.ClassificacaoTributariaService;
import br.com.agrocontabil.validation.NcmValidator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
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

  /** Maior nNF representavel nos 9 digitos do layout e da chave de acesso (issue #88). */
  private static final long NUMERO_MAXIMO = 999_999_999L;

  /** Maior serie representavel nos 3 digitos do layout e da chave de acesso (issue #88). */
  private static final int SERIE_MAXIMA = 999;

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  /**
   * CSTs do IBS/CBS que a emissão monta (#278): 000 (integral) e 200 (redução de alíquota, com
   * {@code gRed}).
   */
  static final java.util.Set<String> CST_SUPORTADOS = java.util.Set.of("000", "200");

  /**
   * Existência do NCM na tabela de referência (RN008) na emissão — issue #282. O
   * {@code @ValidNcm} só confere o request; nota gravada antes de um código sair da referência
   * (os inexistentes removidos pelo changeSet 0046) chegaria à SEFAZ com NCM recusado. Nulo =
   * sem conferência (testes unitários que montam o validador sem Spring).
   */
  private final NcmValidator ncmValidator;

  /**
   * Tabela oficial de classificação tributária, para conferir o cClassTrib aplicado do item
   * (issue #278). Nulo = sem conferência (testes unitários que montam o validador sem Spring).
   */
  private final ClassificacaoTributariaService classificacaoService;

  /** Validador sem as conferências contra tabelas (testes unitários sem Spring). */
  public FiscalRulesValidator() {
    this(null, null);
  }

  /**
   * Validador com a conferência do NCM e sem a da classificação tributária.
   *
   * @param ncmValidator conferência do NCM contra a tabela de referência (RN008)
   */
  public FiscalRulesValidator(NcmValidator ncmValidator) {
    this(ncmValidator, null);
  }

  /**
   * Validador completo.
   *
   * @param ncmValidator conferência do NCM contra a tabela de referência (RN008)
   * @param classificacaoService tabela oficial de cClassTrib (issue #278)
   */
  @Autowired
  public FiscalRulesValidator(NcmValidator ncmValidator,
      ClassificacaoTributariaService classificacaoService) {
    this.ncmValidator = ncmValidator;
    this.classificacaoService = classificacaoService;
  }

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
    } else if (nota.getNumero() > NUMERO_MAXIMO) {
      // Defesa em profundidade: o DTO ja barra com 400, mas notas importadas/legadas nao passam
      // pelo Bean Validation. Acima deste limite o nNF nao cabe nos 9 digitos da chave.
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "numero", "Numero da NF-e deve ser no maximo " + NUMERO_MAXIMO
                  + " (9 digitos no layout e na chave de acesso)"));
    }

    if (nota.getSerie() != null && nota.getSerie() > SERIE_MAXIMA) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "serie", "Serie da NF-e deve ser no maximo " + SERIE_MAXIMA
                  + " (3 digitos no layout e na chave de acesso)"));
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
    } else if (UfIbge.codigo(produtor.getEstado()).isEmpty()) {
      // Sem cUF nao ha como montar a chave de acesso: barrar aqui (422) em vez de deixar o mapper
      // lancar IllegalStateException, que viraria 500 generico sem registro de inconsistencia.
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.estado",
              "UF do emitente invalida: '" + produtor.getEstado()
                  + "' nao corresponde a nenhuma das 27 unidades federativas"));
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
      if (isBlank(end.getLogradouro())) {
        // O XSD exige xLgr com 2 a 60 caracteres; sem o fallback removido na issue #88, a
        // ausencia geraria XML invalido com mensagem opaca em vez de um erro por campo.
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.logradouro", "Logradouro do destinatario e obrigatorio"));
      }
      if (isBlank(end.getCodigoMunicipioIbge())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.codigoMunicipioIbge",
                "Codigo IBGE do municipio do destinatario e obrigatorio para o layout da NF-e"));
      } else if (!isBlank(end.getUf())
          && !UfIbge.isExterior(end.getUf())
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
      } else if (ncmValidator != null && !ncmValidator.isValid(item.getNcm(), null)) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                prefixo + ".ncm", "NCM " + item.getNcm() + " nao consta na tabela de referencia"
                    + " (nomenclatura oficial); edite o item e escolha o codigo correto"));
      }

      if (isBlank(item.getCfop())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                prefixo + ".cfop", "CFOP e obrigatorio no layout da NF-e"));
      } else {
        validarAmbitoCfop(nota, item, prefixo, violations);
      }

      validarTributacaoIbsCbs(nota, item, prefixo, violations);

      if (item.getValorTotal() == null
          || item.getValorTotal().signum() <= 0) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                prefixo + ".valorTotal", "Valor total do item deve ser maior que zero"));
      }
    }
  }

  /**
   * CST e cClassTrib do IBS/CBS que vão ao XML (issue #278): sem padrão fixo, o cClassTrib
   * aplicado tem de existir na tabela oficial vigente na emissão, o CST informado tem de ser o do
   * cClassTrib e a redução aplicada, a oficial do código. Item sem IBS/CBS calculado não entra.
   */
  private void validarTributacaoIbsCbs(NotaFiscal nota, NotaFiscalItem item, String prefixo,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {
    if (item.getValorIbs() == null && item.getValorCbs() == null) {
      return;
    }
    String cClassTrib = NotaFiscalNfeMapper.cClassTrib(item);
    if (cClassTrib == null) {
      violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          prefixo + ".cClassTrib", "Informe o cClassTrib do IBS/CBS: o item tem percentual de"
              + " reducao informado e nenhuma classificacao tributaria"));
      return;
    }
    String cst = NotaFiscalNfeMapper.cst(item);
    if (!CST_SUPORTADOS.contains(cClassTrib.substring(0, 3))) {
      // Outros CSTs pedem grupos que o XML nao monta (gDif no 510/515, gIBSCBSMono no 620,
      // sem gIBSCBS no 410) e calculo proprio: barrar antes de emitir errado.
      violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          prefixo + ".cClassTrib", "cClassTrib " + cClassTrib + " (CST "
              + cClassTrib.substring(0, 3) + ") nao e suportado na emissao: so tributacao"
              + " integral (CST 000) e reducao de aliquota (CST 200)"));
      return;
    }
    if (!cClassTrib.startsWith(cst)) {
      violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          prefixo + ".cstIbsCbs", "CST " + cst + " nao corresponde ao cClassTrib " + cClassTrib
              + " (os tres primeiros digitos sao o CST)"));
    }
    if (classificacaoService == null || nota.getDataEmissao() == null) {
      return;
    }
    LocalDate data = nota.getDataEmissao().atZoneSameInstant(ZONE).toLocalDate();
    Optional<ClassificacaoTributaria> oficial =
        classificacaoService.classificacao(cClassTrib, data);
    if (oficial.isEmpty()) {
      violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          prefixo + ".cClassTrib", "cClassTrib " + cClassTrib + " nao existe na tabela oficial"
              + " de classificacao tributaria vigente em " + data));
      return;
    }
    BigDecimal reducao = NotaFiscalNfeMapper.reducao(item);
    if (oficial.get().getPercentualReducaoIbs().compareTo(reducao) != 0) {
      violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          prefixo + ".percentualReducao", "Reducao de " + reducao.stripTrailingZeros()
              .toPlainString() + "% nao corresponde ao cClassTrib " + cClassTrib + " ("
              + oficial.get().getPercentualReducaoIbs().stripTrailingZeros().toPlainString()
              + "% na tabela oficial)"));
    }
    validarAnexoDoInformado(nota, item, prefixo, data, violations);
  }

  /**
   * cClassTrib <strong>informado</strong> tem de valer para o NCM do item (issue #375):
   * {@link ClassificacaoTributariaService#codigoNaoAdmitido}. Sem isso, qualquer produto teria
   * alíquota zero informando o 200003, o 200014 ou outro código de redução. O tratamento da regra
   * por NCM é coerente por construção e não passa aqui. A nota importada nunca é recusada por isso
   * (é o documento do fornecedor; o crédito segue a regra, #374): hoje este validador só roda na
   * emissão e no envio à revisão, e a guarda deixa a decisão explícita se isso mudar.
   */
  private void validarAnexoDoInformado(NotaFiscal nota, NotaFiscalItem item, String prefixo,
      LocalDate data, List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {
    if (item.getCClassTrib() == null || nota.getOrigem() == OrigemNotaFiscal.IMPORTADA) {
      return;
    }
    classificacaoService.codigoNaoAdmitido(item.getCClassTrib(), item.getNcm(), data)
        .ifPresent(nao -> {
          String admitidos = String.join(", ", nao.admitidos());
          violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              prefixo + ".cClassTrib", "cClassTrib " + item.getCClassTrib() + " nao vale para o"
                  + " NCM " + item.getNcm() + ": a hipotese de reducao dele nao inclui esse NCM"
                  + " na tabela oficial (LC 214/2025). Para este NCM valem: " + admitidos
                  + " (alem de 000001 e 200036)",
              "Informe o cClassTrib " + nao.regra().cClassTrib() + " ("
                  + nao.regra().percentualReducao().stripTrailingZeros().toPlainString()
                  + "% de reducao, a regra do NCM), outro da lista ou retire o cClassTrib do"
                  + " item",
              item.getCClassTrib(), admitidos));
        });
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

    boolean operacaoExterior = UfIbge.isExterior(destinatario);
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
