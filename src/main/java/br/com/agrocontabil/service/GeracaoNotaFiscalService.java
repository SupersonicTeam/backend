package br.com.agrocontabil.service;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.nfe.AssinaturaValidator;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.nfe.NfeAssinador;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.nfe.XsdValidator;
import br.com.agrocontabil.nfe.model.NFe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquestra o pipeline de geração e validação do XML da NF-e modelo 55.
 *
 * <p>Pipeline executado em {@link #gerarXml(NotaFiscal)}:
 * <ol>
 *   <li>{@link FiscalRulesValidator#validar(NotaFiscal)} — valida pré-condições de emissão;
 *       lança {@link br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException}
 *       com lista de campos inválidos em caso de violação.</li>
 *   <li>{@link NotaFiscalNfeMapper#toNfe(NotaFiscal)} — mapeia a entidade para o grafo JAXB;
 *       aplica toda formatação de decimais, datas, zero-padding e gera a chave de acesso.</li>
 *   <li>{@link XmlGenerator#marshal(NFe)} — serializa o grafo para XML UTF-8 sem indentação.</li>
 *   <li>{@link NfeAssinador#assinar(String, String)} — assina digitalmente o XML
 *       (assinatura enveloped no {@code infNFe}, padrão SEFAZ).</li>
 *   <li>{@link AssinaturaValidator#validar(String)} — confere criptograficamente a
 *       assinatura gerada antes da validação estrutural.</li>
 *   <li>{@link XsdValidator#validar(String)} — valida o XML <strong>assinado</strong> contra
 *       {@code nfe_v4.00.xsd}; lança {@link br.com.agrocontabil.exception.XmlGenerationException}
 *       com os erros de validação em caso de invalidade.</li>
 * </ol>
 *
 * <p><strong>Escopo deste serviço:</strong> gerar, assinar e validar o XML localmente.
 * Ele <em>não</em> transmite à SEFAZ (issue #15) e <em>não</em> persiste o XML — a
 * persistência e a atualização do status ({@code XML_GERADO}) são responsabilidade do
 * serviço de orquestração que chama este serviço. A assinatura usa o certificado
 * configurado em {@code app.nfe.certificado} (sandbox/autoassinado em dev/test).
 */
@Service
public class GeracaoNotaFiscalService {

  private static final Logger log = LoggerFactory.getLogger(GeracaoNotaFiscalService.class);

  private final FiscalRulesValidator fiscalRulesValidator;
  private final NotaFiscalNfeMapper notaFiscalNfeMapper;
  private final XmlGenerator xmlGenerator;
  private final NfeAssinador nfeAssinador;
  private final AssinaturaValidator assinaturaValidator;
  private final XsdValidator xsdValidator;

  /** Constrói o serviço com todas as dependências do pipeline. */
  public GeracaoNotaFiscalService(
      FiscalRulesValidator fiscalRulesValidator,
      NotaFiscalNfeMapper notaFiscalNfeMapper,
      XmlGenerator xmlGenerator,
      NfeAssinador nfeAssinador,
      AssinaturaValidator assinaturaValidator,
      XsdValidator xsdValidator) {
    this.fiscalRulesValidator = fiscalRulesValidator;
    this.notaFiscalNfeMapper = notaFiscalNfeMapper;
    this.xmlGenerator = xmlGenerator;
    this.nfeAssinador = nfeAssinador;
    this.assinaturaValidator = assinaturaValidator;
    this.xsdValidator = xsdValidator;
  }

  /**
   * Gera e valida o XML da NF-e para a nota fiscal informada.
   *
   * <p>O XML retornado está em conformidade com o layout 4.00 e validado contra o
   * XSD oficial ({@code nfe_v4.00.xsd}), pronto para assinatura digital e transmissão
   * à SEFAZ (etapas de sprints futuras).
   *
   * <p>O ambiente é sempre {@code tpAmb=2} (homologação) nesta sprint. Em produção,
   * o ambiente deve ser configurável por propriedade.
   *
   * @param nota nota fiscal completa, com produtor, itens e totais calculados
   * @return XML da NF-e como string UTF-8, validado contra o XSD
   * @throws br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException
   *         se dados obrigatórios estiverem ausentes (HTTP 422)
   * @throws br.com.agrocontabil.exception.XmlGenerationException
   *         se o XML gerado for inválido segundo o XSD (HTTP 422)
   */
  public String gerarXml(NotaFiscal nota) {
    log.info(
        "Iniciando geracao do XML da NF-e: notaId={} numero={} serie={}",
        nota.getId(),
        nota.getNumero(),
        nota.getSerie());

    fiscalRulesValidator.validar(nota);

    NFe nfe = notaFiscalNfeMapper.toNfe(nota);
    String xml = xmlGenerator.marshal(nfe);
    String xmlAssinado = nfeAssinador.assinar(xml, nfe.getInfNFe().getId());
    assinaturaValidator.validar(xmlAssinado);
    xsdValidator.validar(xmlAssinado);

    log.info(
        "XML da NF-e gerado, assinado e validado com sucesso: notaId={} chave={}",
        nota.getId(),
        nfe.getInfNFe().getId());

    return xmlAssinado;
  }
}
