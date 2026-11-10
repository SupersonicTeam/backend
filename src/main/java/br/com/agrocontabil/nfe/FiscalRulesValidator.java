package br.com.agrocontabil.nfe;

import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.common.ErrorCode;
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
              "naturezaOperacao", "Natureza da operação é obrigatória para emissão da NF-e"));
    }

    if (nota.getNumero() == null || nota.getNumero() <= 0) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "numero", "Número da NF-e deve ser maior que zero"));
    } else if (nota.getNumero() > NUMERO_MAXIMO) {
      // Defesa em profundidade: o DTO ja barra com 400, mas notas importadas/legadas nao passam
      // pelo Bean Validation. Acima deste limite o nNF nao cabe nos 9 digitos da chave.
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "numero", "Número da NF-e deve ser no máximo " + NUMERO_MAXIMO
                  + " (9 dígitos no layout e na chave de acesso)"));
    }

    if (nota.getSerie() != null && nota.getSerie() > SERIE_MAXIMA) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "serie", "Série da NF-e deve ser no máximo " + SERIE_MAXIMA
                  + " (3 dígitos no layout e na chave de acesso)"));
    }

    if (nota.getDataEmissao() == null) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "dataEmissao", "Data de emissão é obrigatória"));
    }
  }

  private void validarEmitente(
      NotaFiscal nota,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {

    Produtor produtor = nota.getProdutor();

    if (produtor == null) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor", "Produtor emitente não associado à nota fiscal"));
      return; // sem produtor não há como validar sub-campos
    }

    if (isBlank(produtor.getCpfCnpj())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.cpfCnpj",
              "CPF/CNPJ do emitente é obrigatório para emissão da NF-e"));
    }

    // A IE e obrigatoria para a NF-e modelo 55. Na NFA (produtor rural pessoa fisica), a ausencia
    // e legitima e o mapper emite "ISENTO" — e justamente o caso de uso do documento (issue #18).
    if (isBlank(produtor.getInscricaoEstadual())
        && nota.getTipoDocumento() != TipoDocumentoFiscal.NFA) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.inscricaoEstadual",
              "Inscrição Estadual do emitente é obrigatória para emissão da NF-e modelo 55"));
    }

    if (isBlank(produtor.getEstado())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.estado",
              "UF do emitente é obrigatória para determinar o código IBGE da UF na chave de acesso"));
    } else if (UfIbge.codigo(produtor.getEstado()).isEmpty()) {
      // Sem cUF nao ha como montar a chave de acesso: barrar aqui (422) em vez de deixar o mapper
      // lancar IllegalStateException, que viraria 500 generico sem registro de inconsistencia.
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.estado",
              "UF do emitente inválida: '" + produtor.getEstado()
                  + "' não corresponde a nenhuma das 27 unidades federativas"));
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
              "Endereço do emitente é obrigatório para emissão da NF-e"));
      return;
    }

    exigir(endereco.getLogradouro(), "produtor.endereco.logradouro", "Logradouro", violations);
    exigir(endereco.getNumero(), "produtor.endereco.numero",
        "Número (use S/N quando não houver)", violations);
    exigir(endereco.getBairro(), "produtor.endereco.bairro", "Bairro", violations);
    exigir(endereco.getMunicipio(), "produtor.endereco.municipio", "Municipio", violations);
    exigir(endereco.getCep(), "produtor.endereco.cep", "CEP", violations);

    if (isBlank(endereco.getCodigoMunicipioIbge())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.endereco.codigoMunicipioIbge",
              "Código IBGE do município do emitente é obrigatório para o layout da NF-e"));
    } else if (!isBlank(produtor.getEstado())
        && !UfIbge.prefixoCoerente(endereco.getCodigoMunicipioIbge(), produtor.getEstado())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "produtor.endereco.codigoMunicipioIbge",
              "Código IBGE do município do emitente não pertence à UF "
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
              campo, rotulo + " do emitente é obrigatório para emissão da NF-e"));
    }
  }

  private void validarDestinatario(
      NotaFiscal nota,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {

    if (isBlank(nota.getDestinatarioNome())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "destinatarioNome", "Nome do destinatário é obrigatório"));
    }

    if (isBlank(nota.getDestinatarioCpfCnpj())) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "destinatarioCpfCnpj", "CPF/CNPJ do destinatário é obrigatório"));
    }

    EnderecoDestinatario end = nota.getDestinatarioEndereco();
    if (end == null) {
      violations.add(
          new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
              "destinatarioEndereco", "Endereço do destinatário é obrigatório para emissão da NF-e"));
    } else {
      if (isBlank(end.getUf())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.uf", "UF do destinatário é obrigatória"));
      }
      if (isBlank(end.getMunicipio())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.municipio", "Município do destinatário é obrigatório"));
      }
      if (isBlank(end.getLogradouro())) {
        // O XSD exige xLgr com 2 a 60 caracteres; sem o fallback removido na issue #88, a
        // ausencia geraria XML invalido com mensagem opaca em vez de um erro por campo.
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.logradouro", "Logradouro do destinatário é obrigatório"));
      }
      if (isBlank(end.getCodigoMunicipioIbge())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.codigoMunicipioIbge",
                "Código IBGE do município do destinatário é obrigatório para o layout da NF-e"));
      } else if (!isBlank(end.getUf())
          && !UfIbge.isExterior(end.getUf())
          && !UfIbge.prefixoCoerente(end.getCodigoMunicipioIbge(), end.getUf())) {
        // UF "EX" identifica destinatario no exterior (exportacao): o codigo de municipio nao
        // pertence a nenhuma UF brasileira, entao a coerencia de prefixo nao se aplica.
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                "destinatarioEndereco.codigoMunicipioIbge",
                "Código IBGE do município do destinatário não pertence à UF "
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
                prefixo + ".ncm", "NCM é obrigatório no layout da NF-e"));
      } else if (ncmValidator != null && !ncmValidator.isValid(item.getNcm(), null)) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                prefixo + ".ncm", "NCM " + item.getNcm() + " não consta na tabela de referência"
                    + " (nomenclatura oficial); edite o item e escolha o código correto"));
      }

      if (isBlank(item.getCfop())) {
        violations.add(
            new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                prefixo + ".cfop", "CFOP é obrigatório no layout da NF-e"));
      } else {
        validarAmbitoCfop(nota, item, prefixo, violations);
      }

      validarIcms(nota, item, prefixo, violations);
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
   * Grupo {@code ICMS} do item ({@link TributacaoIcms#problema}): toda NF-e normal precisa dele (NT
   * 2025.002 v1.53, regra B25-90, rejeição 1002). Sem CST informado e fora da venda interna de
   * produção própria a contribuinte, o CST fica a definir com o contador: 422
   * {@code ICMS_A_DEFINIR}, sem o sistema escolher um. O CST informado tem de ser um dos que o
   * leiaute aceita sem o cálculo do ICMS (o DTO já barra os outros; aqui alcança a nota gravada
   * antes) e, no CFOP de exportação (7xxx), o 41 (MOC 7.00, regra N12-10, rejeição 527). Emitente
   * do Simples ou MEI informa CSOSN, que o sistema não monta. Sem CFOP, só a falta do CFOP é
   * apontada.
   */
  private void validarIcms(NotaFiscal nota, NotaFiscalItem item, String prefixo,
      List<NotaFiscalInvalidaParaEmissaoException.FieldViolation> violations) {
    if (isBlank(item.getCfop())) {
      return;
    }
    Optional<TributacaoIcms.Problema> problema = TributacaoIcms.problema(nota, item);
    if (problema.isEmpty()) {
      return;
    }
    String campo = prefixo + ".cstIcms";
    String cst = item.getCstIcms() == null ? "" : item.getCstIcms().strip();
    violations.add(switch (problema.get()) {
      case A_DEFINIR -> new NotaFiscalInvalidaParaEmissaoException.FieldViolation(campo,
          "ICMS a definir com o contador: o CST do ICMS só tem padrão na venda interna de"
              + " produção própria (CFOP " + TributacaoIcms.CFOP_VENDA_PRODUCAO_INTERNA
              + ") a destinatário com inscrição estadual (diferimento, CST 51)",
          ErrorCode.ICMS_A_DEFINIR, "Informe o CST do ICMS combinado com o contador: "
              + TributacaoIcms.CSTS_SEM_CALCULO_TEXTO);
      case EXPORTACAO_A_DEFINIR -> new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          campo, "ICMS a definir: exportação (CFOP " + item.getCfop() + ") sem o CST do ICMS",
          ErrorCode.ICMS_A_DEFINIR, "Na exportação o CST do ICMS é o "
              + TributacaoIcms.CST_NAO_TRIBUTADA + " (rejeição 527 da SEFAZ); confirme com o"
              + " contador e informe-o");
      case FORA_DA_LISTA -> new NotaFiscalInvalidaParaEmissaoException.FieldViolation(campo,
          "CST do ICMS " + cst + " exige o cálculo do ICMS, que o sistema não faz: use "
              + TributacaoIcms.CSTS_SEM_CALCULO_TEXTO);
      case EXPORTACAO_SEM_41 -> new NotaFiscalInvalidaParaEmissaoException.FieldViolation(campo,
          "Exportação (CFOP " + item.getCfop() + ") exige o CST do ICMS "
              + TributacaoIcms.CST_NAO_TRIBUTADA + " (rejeição 527 da SEFAZ); informado " + cst);
      case EMITENTE_SIMPLES -> new NotaFiscalInvalidaParaEmissaoException.FieldViolation(campo,
          "Emitente do Simples Nacional ou MEI informa o ICMS por CSOSN (grupos ICMSSN), que o"
              + " sistema ainda não emite: a SEFAZ rejeita CST para esse emitente (rejeição 590)",
          ErrorCode.ICMS_A_DEFINIR, "Emita esta nota com o contador até o sistema tratar o"
              + " Simples Nacional");
    });
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
              + " redução informado e nenhuma classificação tributária"));
      return;
    }
    String cst = NotaFiscalNfeMapper.cst(item);
    if (!CST_SUPORTADOS.contains(cClassTrib.substring(0, 3))) {
      // Outros CSTs pedem grupos que o XML nao monta (gDif no 510/515, gIBSCBSMono no 620,
      // sem gIBSCBS no 410) e calculo proprio: barrar antes de emitir errado.
      violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          prefixo + ".cClassTrib", "cClassTrib " + cClassTrib + " (CST "
              + cClassTrib.substring(0, 3) + ") não é suportado na emissão: só tributação"
              + " integral (CST 000) e redução de alíquota (CST 200)"));
      return;
    }
    if (!cClassTrib.startsWith(cst)) {
      violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          prefixo + ".cstIbsCbs", "CST " + cst + " não corresponde ao cClassTrib " + cClassTrib
              + " (os três primeiros dígitos são o CST)"));
    }
    if (classificacaoService == null || nota.getDataEmissao() == null) {
      return;
    }
    LocalDate data = nota.getDataEmissao().atZoneSameInstant(ZONE).toLocalDate();
    Optional<ClassificacaoTributaria> oficial =
        classificacaoService.classificacao(cClassTrib, data);
    if (oficial.isEmpty()) {
      violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          prefixo + ".cClassTrib", "cClassTrib " + cClassTrib + " não existe na tabela oficial"
              + " de classificação tributária vigente em " + data));
      return;
    }
    BigDecimal reducao = NotaFiscalNfeMapper.reducao(item);
    if (oficial.get().getPercentualReducaoIbs().compareTo(reducao) != 0) {
      violations.add(new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
          prefixo + ".percentualReducao", "Redução de " + reducao.stripTrailingZeros()
              .toPlainString() + "% não corresponde ao cClassTrib " + cClassTrib + " ("
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
              prefixo + ".cClassTrib", "cClassTrib " + item.getCClassTrib() + " não vale para o"
                  + " NCM " + item.getNcm() + ": a hipótese de redução dele não inclui esse NCM"
                  + " na tabela oficial (LC 214/2025). Para este NCM valem: " + admitidos
                  + " (além de 000001 e 200036)",
              null, "Informe o cClassTrib " + nao.regra().cClassTrib() + " ("
                  + nao.regra().percentualReducao().stripTrailingZeros().toPlainString()
                  + "% de redução, a regra do NCM), outro da lista ou retire o cClassTrib do"
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
                  "CFOP de operação interna (iniciado em 5) é incompatível com operação "
                      + "interestadual (" + emitente + " -> " + destinatario
                      + "). Use um CFOP iniciado em 6."));
        }
      }
      case '6' -> {
        if (mesmaUf) {
          violations.add(
              new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                  prefixo + ".cfop",
                  "CFOP de operação interestadual (iniciado em 6) é incompatível com operação "
                      + "interna (mesma UF " + emitente
                      + "). Use um CFOP iniciado em 5."));
        }
      }
      case '7' -> {
        if (!operacaoExterior) {
          violations.add(
              new NotaFiscalInvalidaParaEmissaoException.FieldViolation(
                  prefixo + ".cfop",
                  "CFOP de operação com o exterior (iniciado em 7) exige destinatário no "
                      + "exterior (UF 'EX'). Para operação nacional use CFOP iniciado em 5 ou 6."));
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
