package br.com.agrocontabil.service;

import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.nfe.SignatureValidator;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.nfe.NfeSigner;
import br.com.agrocontabil.nfe.InvoiceNfeMapper;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.nfe.XsdValidator;
import br.com.agrocontabil.nfe.model.NFe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquestra o pipeline de geração e validação do XML da NF-e model 55.
 *
 * <p>Pipeline executado em {@link #generateXml(Invoice)}:
 * <ol>
 *   <li>{@link FiscalRulesValidator#validate(Invoice)} — valida pré-condições de emissão;
 *       lança {@link br.com.agrocontabil.exception.InvoiceInvalidForIssuanceException}
 *       com lista de campos inválidos em caso de violação.</li>
 *   <li>{@link InvoiceNfeMapper#toNfe(Invoice)} — mapeia a entidade para o grafo JAXB;
 *       aplica toda formatação de decimais, datas, zero-padding e gera a key de acesso.</li>
 *   <li>{@link XmlGenerator#marshal(NFe)} — serializa o grafo para XML UTF-8 sem indentação.</li>
 *   <li>{@link NfeSigner#sign(String, String)} — assina digitalmente o XML
 *       (assinatura enveloped no {@code infNFe}, padrão SEFAZ).</li>
 *   <li>{@link SignatureValidator#validate(String)} — confere criptograficamente a
 *       assinatura gerada antes da validação estrutural.</li>
 *   <li>{@link XsdValidator#validate(String)} — valida o XML <strong>assinado</strong> contra
 *       {@code nfe_v4.00.xsd}; lança {@link br.com.agrocontabil.exception.XmlGenerationException}
 *       com os erros de validação em caso de invalidade.</li>
 * </ol>
 *
 * <p><strong>Escopo deste serviço:</strong> generate, sign e validate o XML localmente.
 * Ele <em>não</em> transmite à SEFAZ (issue #15) e <em>não</em> persiste o XML — a
 * persistência e a atualização do status ({@code XML_GERADO}) são responsabilidade do
 * serviço de orquestração que chama este serviço. A assinatura usa o certificado
 * configurado em {@code app.nfe.certificado} (sandbox/autoassinado em dev/test).
 */
@Service
public class InvoiceXmlService {

  private static final Logger log = LoggerFactory.getLogger(InvoiceXmlService.class);

  private final FiscalRulesValidator fiscalRulesValidator;
  private final InvoiceNfeMapper invoiceNfeMapper;
  private final XmlGenerator xmlGenerator;
  private final NfeSigner nfeAssinador;
  private final SignatureValidator assinaturaValidator;
  private final XsdValidator xsdValidator;

  /** Constrói o serviço com todas as dependências do pipeline. */
  public InvoiceXmlService(
      FiscalRulesValidator fiscalRulesValidator,
      InvoiceNfeMapper invoiceNfeMapper,
      XmlGenerator xmlGenerator,
      NfeSigner nfeAssinador,
      SignatureValidator assinaturaValidator,
      XsdValidator xsdValidator) {
    this.fiscalRulesValidator = fiscalRulesValidator;
    this.invoiceNfeMapper = invoiceNfeMapper;
    this.xmlGenerator = xmlGenerator;
    this.nfeAssinador = nfeAssinador;
    this.assinaturaValidator = assinaturaValidator;
    this.xsdValidator = xsdValidator;
  }

  /**
   * Gera e valida o XML da NF-e para a invoice fiscal informada.
   *
   * <p>O XML retornado está em conformidade com o layout 4.00 e validado contra o
   * XSD oficial ({@code nfe_v4.00.xsd}), pronto para assinatura digital e transmissão
   * à SEFAZ (etapas de sprints futuras).
   *
   * <p>O ambiente é sempre {@code tpAmb=2} (homologação) nesta sprint. Em produção,
   * o ambiente deve ser configurável por propriedade.
   *
   * @param invoice invoice fiscal completa, com producer, items e totais calculados
   * @return XML da NF-e como string UTF-8, validado contra o XSD
   * @throws br.com.agrocontabil.exception.InvoiceInvalidForIssuanceException
   *         se dados obrigatórios estiverem ausentes (HTTP 422)
   * @throws br.com.agrocontabil.exception.XmlGenerationException
   *         se o XML gerado for inválido segundo o XSD (HTTP 422)
   */
  public String generateXml(Invoice invoice) {
    log.info(
        "Iniciando geracao do XML da NF-e: notaId={} numero={} serie={}",
        invoice.getId(),
        invoice.getNumber(),
        invoice.getSeries());

    fiscalRulesValidator.validate(invoice);

    NFe nfe = invoiceNfeMapper.toNfe(invoice);
    String xml = xmlGenerator.marshal(nfe);
    String xmlAssinado = nfeAssinador.sign(xml, nfe.getInfNFe().getId());
    assinaturaValidator.validate(xmlAssinado);
    xsdValidator.validate(xmlAssinado);

    log.info(
        "XML da NF-e gerado, assinado e validado com sucesso: notaId={} chave={}",
        invoice.getId(),
        nfe.getInfNFe().getId());

    return xmlAssinado;
  }
}
