package br.com.agrocontabil.service;

import br.com.agrocontabil.common.validation.CpfUtils;
import br.com.agrocontabil.exception.NfaEmitenteInvalidoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoProdutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquestra a geracao da Nota Fiscal Avulsa (NFA-e) do produtor rural pessoa fisica.
 *
 * <p>A NFA-e reusa o layout NF-e 4.00 modelo 55: este servico nao duplica o pipeline
 * de marshalling/validacao XSD — ele <strong>compoe</strong> o
 * {@link GeracaoNotaFiscalService}, adicionando apenas as pre-condicoes especificas
 * da NFA antes de delegar:
 * <ol>
 *   <li>O emitente (produtor) deve ser {@link TipoProdutor#PESSOA_FISICA}.</li>
 *   <li>O CPF do emitente deve ser estruturalmente valido (modulo 11).</li>
 * </ol>
 *
 * <p>Qualquer violacao lanca {@link NfaEmitenteInvalidoException} (HTTP 422). O
 * {@link br.com.agrocontabil.nfe.NotaFiscalNfeMapper} ja emite {@code <CPF>} para
 * emitente PF, portanto o XML resultante esta estruturalmente correto para a NFA.
 */
@Service
public class GeracaoNfaService {

  private static final Logger log = LoggerFactory.getLogger(GeracaoNfaService.class);

  private final GeracaoNotaFiscalService geracaoNotaFiscalService;

  public GeracaoNfaService(GeracaoNotaFiscalService geracaoNotaFiscalService) {
    this.geracaoNotaFiscalService = geracaoNotaFiscalService;
  }

  /**
   * Valida o emitente PF + CPF e gera o XML da NFA delegando ao pipeline NF-e.
   *
   * @param nota nota fiscal com {@code tipoDocumento = NFA} e produtor PF associado
   * @return XML da NFA-e (layout 4.00, modelo 55), validado contra o XSD
   * @throws NfaEmitenteInvalidoException se o produtor nao for PF ou o CPF for invalido
   */
  public String gerarXml(NotaFiscal nota) {
    Produtor produtor = nota.getProdutor();

    if (produtor == null || produtor.getTipoProdutor() != TipoProdutor.PESSOA_FISICA) {
      log.warn("Geracao de NFA rejeitada: emitente nao e pessoa fisica. notaId={}",
          nota.getId());
      throw new NfaEmitenteInvalidoException(
          "NFA exige emitente pessoa fisica (produtor rural PF).");
    }

    if (!CpfUtils.isValid(produtor.getCpfCnpj())) {
      log.warn("Geracao de NFA rejeitada: CPF do emitente invalido. notaId={}",
          nota.getId());
      throw new NfaEmitenteInvalidoException(
          "CPF do produtor emitente da NFA e invalido.");
    }

    log.info("Gerando XML da NFA: notaId={} produtor={}", nota.getId(), produtor.getId());
    return geracaoNotaFiscalService.gerarXml(nota);
  }
}
