package br.com.agrocontabil.service;

import br.com.agrocontabil.common.DocumentoFiscalValidator;
import br.com.agrocontabil.exception.NfaInvalidaParaEmissaoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoProdutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fluxo de emissao da NFA — Nota Fiscal Avulsa do produtor rural pessoa fisica (issue #18).
 *
 * <p>A NFA reaproveita integralmente o pipeline de emissao da NF-e modelo 55
 * ({@link GeracaoNotaFiscalService}); a diferenca esta nas pre-condicoes especificas validadas
 * aqui antes da delegacao:
 * <ol>
 *   <li>o produtor emitente deve ser {@link TipoProdutor#PESSOA_FISICA};</li>
 *   <li>o documento do emitente deve ser um <strong>CPF</strong> (11 digitos) com digito
 *       verificador valido — o {@link br.com.agrocontabil.nfe.NotaFiscalNfeMapper} ja emite
 *       {@code <CPF>} no {@code emit} quando o documento tem 11 digitos.</li>
 * </ol>
 *
 * <p>Violacoes resultam em {@link NfaInvalidaParaEmissaoException} (HTTP 422). A validacao do
 * CPF antecipa parte do debito tecnico de validacao de digito verificador previsto para a
 * Sprint 8.
 *
 * <p><strong>LGPD:</strong> o CPF/CNPJ do emitente nunca e logado em claro — apenas mascarado.
 */
@Service
public class GeracaoNfaService {

  private static final Logger log = LoggerFactory.getLogger(GeracaoNfaService.class);

  private final GeracaoNotaFiscalService geracaoNotaFiscalService;

  public GeracaoNfaService(GeracaoNotaFiscalService geracaoNotaFiscalService) {
    this.geracaoNotaFiscalService = geracaoNotaFiscalService;
  }

  /**
   * Valida as pre-condicoes da NFA e delega a geracao do XML ao pipeline padrao.
   *
   * @param nota nota fiscal do tipo {@code NFA}, com produtor e itens carregados
   * @return XML da NF-e (modelo 55) gerado, assinado e validado, com {@code <CPF>} no emitente
   * @throws NfaInvalidaParaEmissaoException se o produtor nao for PF ou o CPF do emitente for
   *     invalido (HTTP 422)
   */
  public String gerarXml(NotaFiscal nota) {
    Produtor produtor = nota.getProdutor();

    if (produtor.getTipoProdutor() != TipoProdutor.PESSOA_FISICA) {
      log.warn("Emissao de NFA bloqueada: produtor {} nao e pessoa fisica (tipo={}).",
          produtor.getId(), produtor.getTipoProdutor());
      throw NfaInvalidaParaEmissaoException.produtorNaoPessoaFisica();
    }

    String documento = somenteDigitos(produtor.getCpfCnpj());
    // NFA exige CPF de pessoa fisica (11 digitos com DV valido). Um CNPJ, mesmo valido,
    // nao e aceito no fluxo NFA.
    if (documento.length() != 11 || !DocumentoFiscalValidator.isCpfValido(documento)) {
      log.warn("Emissao de NFA bloqueada: CPF do emitente invalido para o produtor {} (cpf={}).",
          produtor.getId(), mascarar(documento));
      throw NfaInvalidaParaEmissaoException.cpfEmitenteInvalido();
    }

    log.info("Emitindo NFA para produtor pessoa fisica {} (cpf={}).",
        produtor.getId(), mascarar(documento));
    return geracaoNotaFiscalService.gerarXml(nota);
  }

  private static String somenteDigitos(String documento) {
    return documento == null ? "" : documento.replaceAll("\\D", "");
  }

  /**
   * Mascara um documento para log (LGPD): mantem apenas os 2 ultimos digitos.
   *
   * @param documento documento com apenas digitos (pode ser vazio)
   * @return documento mascarado, ex.: {@code "*********25"}; {@code "***"} se muito curto
   */
  private static String mascarar(String documento) {
    if (documento == null || documento.length() < 3) {
      return "***";
    }
    int visiveis = 2;
    return "*".repeat(documento.length() - visiveis)
        + documento.substring(documento.length() - visiveis);
  }
}
