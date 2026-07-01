package br.com.agrocontabil.service;

import br.com.agrocontabil.danfe.DanfePdfService;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.exception.DanfeIndisponivelException;
import br.com.agrocontabil.exception.NfaInvalidaParaEmissaoException;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.exception.NotaFiscalNaoEditavelException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.XmlGenerationException;
import br.com.agrocontabil.mapper.NotaFiscalMapper;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servico de regras de negocio para {@link NotaFiscal}.
 *
 * <p>Todas as operacoes verificam o produtor autenticado via
 * {@link AuthenticatedProdutorProvider}, garantindo que um produtor nunca acesse
 * ou modifique notas de outro produtor (anti-IDOR).
 *
 * <p>Regras de negocio:
 * <ul>
 *   <li>Criacao: status inicial RASCUNHO; totais calculados no mapper.</li>
 *   <li>Listagem: somente notas do produtor autenticado, paginada.</li>
 *   <li>Busca por ID: 404 se inexistente OU se pertencer a outro produtor.</li>
 *   <li>Atualizacao: somente se status == RASCUNHO; caso contrario, 409 Conflict.</li>
 *   <li>Exclusao: somente se status == RASCUNHO; caso contrario, 409 Conflict.</li>
 *   <li>Geracao de XML: somente se status == RASCUNHO; persiste XML, versao, data
 *       e transiciona status para XML_GERADO.</li>
 *   <li>Obtencao do XML: retorna o XML armazenado; 404 se ainda nao gerado.</li>
 * </ul>
 */
@Service
@Transactional
public class NotaFiscalService {

  /** Versao do layout NF-e gerado por esta implementacao. */
  private static final String VERSAO_LAYOUT_NFE = "4.00";

  private static final Logger log = LoggerFactory.getLogger(NotaFiscalService.class);

  /**
   * Status em que a nota ainda é considerada "rascunho" editável: além de
   * {@link StatusNotaFiscal#RASCUNHO}, uma nota {@link StatusNotaFiscal#COM_INCONSISTENCIAS}
   * (issue #22) pode ser editada e ter o XML gerado novamente após a correção dos campos.
   */
  private static final Set<StatusNotaFiscal> STATUS_EDITAVEIS =
      EnumSet.of(StatusNotaFiscal.RASCUNHO, StatusNotaFiscal.COM_INCONSISTENCIAS);

  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final CalculoIbsCbsService calculoIbsCbsService;
  private final GeracaoNotaFiscalService geracaoNotaFiscalService;
  private final GeracaoNfaService geracaoNfaService;
  private final DanfePdfService danfePdfService;
  private final NotaFiscalValidacaoService validacaoService;

  public NotaFiscalService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      CalculoIbsCbsService calculoIbsCbsService,
      GeracaoNotaFiscalService geracaoNotaFiscalService,
      GeracaoNfaService geracaoNfaService,
      DanfePdfService danfePdfService,
      NotaFiscalValidacaoService validacaoService) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.calculoIbsCbsService = calculoIbsCbsService;
    this.geracaoNotaFiscalService = geracaoNotaFiscalService;
    this.geracaoNfaService = geracaoNfaService;
    this.danfePdfService = danfePdfService;
    this.validacaoService = validacaoService;
  }

  /**
   * Cria uma nova nota fiscal vinculada ao produtor autenticado.
   *
   * @param request dados validados da nota fiscal
   * @return DTO de resposta com ID gerado e totais calculados
   */
  public NotaFiscalResponse create(NotaFiscalRequest request) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);
    // Calcula IBS/CBS após montagem dos itens e cálculo dos totais de produtos.
    calculoIbsCbsService.calcular(nota);
    NotaFiscal saved = notaFiscalRepository.save(nota);
    log.info("NotaFiscal criada: id={} produtor={} numero={}",
        saved.getId(), produtor.getId(), saved.getNumero());
    return NotaFiscalMapper.toResponse(saved);
  }

  /**
   * Lista todas as notas fiscais do produtor autenticado, paginadas.
   *
   * @param pageable parametros de paginacao e ordenacao
   * @return pagina de notas do produtor autenticado
   */
  @Transactional(readOnly = true)
  public Page<NotaFiscalResponse> findAll(Pageable pageable) {
    Produtor produtor = produtorProvider.get();
    return notaFiscalRepository
        .findByProdutorId(produtor.getId(), pageable)
        .map(NotaFiscalMapper::toResponse);
  }

  /**
   * Busca uma nota fiscal por ID, somente se pertencer ao produtor autenticado.
   *
   * <p>Retorna 404 tanto se a nota nao existir quanto se pertencer a outro produtor
   * (anti-IDOR: nunca vazar 403 com informacoes de existencia do recurso).
   *
   * @param id identificador da nota fiscal
   * @return DTO de resposta
   * @throws ResourceNotFoundException se nao encontrada ou se pertencer a outro produtor
   */
  @Transactional(readOnly = true)
  public NotaFiscalResponse findById(UUID id) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));
    return NotaFiscalMapper.toResponse(nota);
  }

  /**
   * Atualiza uma nota fiscal existente, somente se estiver em status RASCUNHO ou
   * {@link StatusNotaFiscal#COM_INCONSISTENCIAS} (issue #22 — nota com inconsistencias
   * permanece corrigivel).
   *
   * <p>Os itens antigos sao substituidos pelos novos (orphanRemoval cuida da exclusao
   * dos itens removidos). Os totais sao recalculados.
   *
   * @param id      identificador da nota fiscal
   * @param request novos dados validados
   * @return DTO de resposta atualizado
   * @throws ResourceNotFoundException        se nao encontrada ou pertencer a outro produtor
   * @throws NotaFiscalNaoEditavelException   se o status nao for editavel (409 Conflict)
   */
  public NotaFiscalResponse update(UUID id, NotaFiscalRequest request) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (!STATUS_EDITAVEIS.contains(nota.getStatus())) {
      throw new NotaFiscalNaoEditavelException(id, nota.getStatus());
    }

    nota.setNumero(request.numero());
    nota.setSerie(request.serie() != null ? request.serie() : 1);
    if (request.tipoDocumento() != null) {
      nota.setTipoDocumento(request.tipoDocumento());
    }
    nota.setNaturezaOperacao(request.naturezaOperacao());
    nota.setDataEmissao(request.dataEmissao());
    nota.setDataSaida(request.dataSaida());
    nota.setDestinatarioNome(request.destinatarioNome());
    nota.setDestinatarioCpfCnpj(request.destinatarioCpfCnpj());
    nota.setDestinatarioIe(request.destinatarioIe());

    if (request.destinatarioEndereco() != null) {
      nota.setDestinatarioEndereco(buildEndereco(request.destinatarioEndereco()));
    } else {
      nota.setDestinatarioEndereco(null);
    }

    // Substitui itens — orphanRemoval remove os anteriores automaticamente
    List<NotaFiscalItem> novosItens = NotaFiscalMapper.toItemEntityList(request.itens());

    nota.getItens().clear();
    novosItens.forEach(nota::addItem);

    BigDecimal totalProdutos = nota.getItens().stream()
        .map(NotaFiscalItem::getValorTotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    nota.setValorTotalProdutos(totalProdutos);
    nota.setValorTotalNota(totalProdutos);

    // Recalcula IBS/CBS após substituição dos itens e recálculo dos totais de produtos.
    calculoIbsCbsService.calcular(nota);

    NotaFiscal saved = notaFiscalRepository.save(nota);
    log.info("NotaFiscal atualizada: id={} produtor={}", saved.getId(), produtor.getId());
    return NotaFiscalMapper.toResponse(saved);
  }

  /**
   * Exclui uma nota fiscal, somente se estiver em status RASCUNHO ou
   * {@link StatusNotaFiscal#COM_INCONSISTENCIAS} (issue #22).
   *
   * @param id identificador da nota fiscal
   * @throws ResourceNotFoundException      se nao encontrada ou pertencer a outro produtor
   * @throws NotaFiscalNaoEditavelException se o status nao for editavel (409 Conflict)
   */
  public void delete(UUID id) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (!STATUS_EDITAVEIS.contains(nota.getStatus())) {
      throw new NotaFiscalNaoEditavelException(id, nota.getStatus());
    }

    notaFiscalRepository.delete(nota);
    log.info("NotaFiscal excluida: id={} produtor={}", id, produtor.getId());
  }

  // -------------------------------------------------------------------------
  // Geracao e obtencao do XML da NF-e (Sprint 2, Sessao 6)
  // -------------------------------------------------------------------------

  /**
   * Gera o XML da NF-e para a nota indicada, persiste-o e transiciona o status.
   *
   * <p>Regras:
   * <ul>
   *   <li>Ownership: busca via {@code findByIdAndProdutorId} — 404 se a nota nao pertencer
   *       ao produtor autenticado (anti-IDOR).</li>
   *   <li>Idempotencia/transicao: somente notas em {@link StatusNotaFiscal#RASCUNHO} ou
   *       {@link StatusNotaFiscal#COM_INCONSISTENCIAS} (issue #22) podem ter o XML gerado;
   *       qualquer outro status resulta em {@link NotaFiscalNaoEditavelException} (HTTP 409).</li>
   *   <li>Inconsistencias (issue #22): se a validacao fiscal ou XSD falhar, as inconsistencias
   *       sao persistidas, a nota e marcada como {@link StatusNotaFiscal#COM_INCONSISTENCIAS}
   *       e o erro 422 e propagado ao cliente. O historico fica em
   *       {@code GET /api/notas/{id}/validacoes}.</li>
   *   <li>Apos a geracao: {@code xmlGerado}, {@code versaoLayoutNfe} e
   *       {@code dataGeracaoXml} sao preenchidos; o status transita para
   *       {@link StatusNotaFiscal#XML_GERADO}; a chave de acesso e extraida do atributo
   *       {@code Id} da {@code infNFe} ({@code "NFe" + 44 digitos}) via
   *       {@link GeracaoNotaFiscalService#gerarXml(NotaFiscal)}.</li>
   *   <li>Imutabilidade: apos a transicao o status nao e RASCUNHO, portanto os endpoints
   *       de update e delete ja bloqueiam sobrescrita — o XML e a chave sao imutaveis.</li>
   * </ul>
   *
   * @param id identificador da nota fiscal
   * @return DTO de resposta atualizado com status XML_GERADO e chave de acesso preenchida
   * @throws ResourceNotFoundException        se a nota nao existir ou pertencer a outro produtor
   * @throws NotaFiscalNaoEditavelException   se o status nao for RASCUNHO (409 Conflict)
   * @throws br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException
   *         se dados obrigatorios da NF-e estiverem ausentes (422)
   * @throws br.com.agrocontabil.exception.XmlGenerationException
   *         se o XML gerado nao for valido segundo o XSD (422)
   */
  public NotaFiscalResponse gerarXml(UUID id) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (!STATUS_EDITAVEIS.contains(nota.getStatus())) {
      throw new NotaFiscalNaoEditavelException(id, nota.getStatus());
    }

    String xml = gerarXmlRegistrandoInconsistencias(nota);

    // Extrai a chave de acesso do atributo Id da infNFe: "NFe" + 44 digitos numericos.
    // O GeracaoNotaFiscalService garante que o Id esta no formato correto apos geracao
    // bem-sucedida — fazemos a extracao direto do XML para nao expor o grafo JAXB.
    String chaveAcesso = extrairChaveAcessoDoXml(xml);

    nota.setXmlGerado(xml);
    nota.setVersaoLayoutNfe(VERSAO_LAYOUT_NFE);
    nota.setDataGeracaoXml(OffsetDateTime.now());
    nota.setStatus(StatusNotaFiscal.XML_GERADO);
    nota.setChaveAcesso(chaveAcesso);

    NotaFiscal saved = notaFiscalRepository.save(nota);
    log.info("XML da NF-e gerado e persistido: id={} produtor={} chave={}",
        saved.getId(), produtor.getId(), chaveAcesso);
    return NotaFiscalMapper.toResponse(saved);
  }

  /**
   * Retorna o XML da NF-e armazenado para a nota indicada.
   *
   * <p>Ownership: busca via {@code findByIdAndProdutorId} — 404 se a nota nao pertencer
   * ao produtor autenticado (anti-IDOR).
   *
   * @param id identificador da nota fiscal
   * @return XML da NF-e como string UTF-8
   * @throws ResourceNotFoundException se a nota nao existir, pertencer a outro produtor
   *                                   ou o XML ainda nao tiver sido gerado
   */
  @Transactional(readOnly = true)
  public String obterXml(UUID id) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (nota.getXmlGerado() == null) {
      throw new ResourceNotFoundException(
          "XML da NF-e ainda nao gerado para a nota '" + id + "'. "
              + "Chame POST /api/notas/{id}/gerar-xml primeiro.");
    }

    return nota.getXmlGerado();
  }

  /**
   * Gera o PDF da DANFE da nota indicada (issue #14 — RF011).
   *
   * <p>Ownership anti-IDOR: busca via {@code findByIdAndProdutorId} — 404 se a nota não
   * pertencer ao produtor autenticado. A DANFE só está disponível após a geração do XML
   * (status XML_GERADO em diante); caso contrário, lança {@link DanfeIndisponivelException}
   * (HTTP 409).
   *
   * @param id identificador da nota fiscal
   * @return bytes do PDF da DANFE
   * @throws ResourceNotFoundException     se a nota não existir ou pertencer a outro produtor
   * @throws DanfeIndisponivelException    se o XML ainda não foi gerado (HTTP 409)
   * @throws br.com.agrocontabil.exception.DanfeGenerationException
   *         se a geração do PDF falhar (HTTP 500)
   */
  @Transactional(readOnly = true)
  public byte[] gerarPdf(UUID id) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (nota.getXmlGerado() == null) {
      throw new DanfeIndisponivelException(id, nota.getStatus());
    }

    return danfePdfService.gerarPdf(nota);
  }

  // -------------------------------------------------------------------------
  // Helper privado de geracao de XML com registro de inconsistencias (issue #22)
  // -------------------------------------------------------------------------

  /**
   * Executa o pipeline de geração do XML, persistindo o histórico de inconsistências antes
   * de propagar o erro ao cliente (issue #22).
   *
   * <p>Quando a validação fiscal ({@link NotaFiscalInvalidaParaEmissaoException}) ou a
   * validação do XML gerado ({@link XmlGenerationException}) falha, as inconsistências são
   * registradas via {@link NotaFiscalValidacaoService#registrarInconsistencias(UUID, List)} —
   * numa transação independente, que sobrevive ao rollback desta — e a nota é marcada como
   * {@link StatusNotaFiscal#COM_INCONSISTENCIAS}. A exceção é então relançada para que o
   * cliente continue recebendo o HTTP 422 com o envelope {@code errors[]}.
   *
   * @param nota nota fiscal a ter o XML gerado
   * @return XML gerado, assinado e validado em caso de sucesso
   */
  private String gerarXmlRegistrandoInconsistencias(NotaFiscal nota) {
    try {
      // Roteia por tipo de documento (issue #18): NFA passa pelas pre-condicoes especificas
      // do produtor rural pessoa fisica; NFE segue direto ao pipeline padrao.
      if (nota.getTipoDocumento() == TipoDocumentoFiscal.NFA) {
        return geracaoNfaService.gerarXml(nota);
      }
      return geracaoNotaFiscalService.gerarXml(nota);
    } catch (NfaInvalidaParaEmissaoException
        | NotaFiscalInvalidaParaEmissaoException
        | XmlGenerationException e) {
      validacaoService.registrarInconsistencias(nota.getId(), e.getErrors());
      throw e;
    }
  }

  // -------------------------------------------------------------------------
  // Helper privado de extracao da chave de acesso
  // -------------------------------------------------------------------------

  /**
   * Extrai os 44 digitos da chave de acesso do atributo {@code Id} da infNFe no XML.
   *
   * <p>O formato e {@code Id="NFe&lt;44 digitos&gt;"} — remove o prefixo "NFe" e retorna
   * apenas os 44 digitos numericos. Nao faz parse completo do XML para evitar overhead;
   * usa busca por substring confiavel dado que o GeracaoNotaFiscalService garante o formato.
   *
   * @param xml XML valido da NF-e
   * @return chave de acesso com 44 digitos numericos
   * @throws XmlGenerationException se a chave nao puder ser extraida (XML sem o atributo
   *         {@code Id} no formato esperado) — evita persistir o XML com chave nula
   */
  private String extrairChaveAcessoDoXml(String xml) {
    // Formato: Id="NFe<44 digitos>"
    String prefix = "Id=\"NFe";
    int start = xml.indexOf(prefix);
    if (start == -1) {
      throw new XmlGenerationException(
          "Falha ao extrair a chave de acesso: atributo Id da infNFe nao encontrado no XML gerado.");
    }
    int chaveStart = start + prefix.length();
    // Chave tem exatamente 44 digitos
    if (chaveStart + 44 > xml.length()) {
      throw new XmlGenerationException(
          "Falha ao extrair a chave de acesso: XML truncado ou malformado.");
    }
    String chave = xml.substring(chaveStart, chaveStart + 44);
    if (!chave.matches("\\d{44}")) {
      throw new XmlGenerationException(
          "Falha ao extrair a chave de acesso: valor extraido nao possui 44 digitos numericos.");
    }
    return chave;
  }

  // -------------------------------------------------------------------------
  // Helper privado de endereco (evita duplicar logica inline no update)
  // -------------------------------------------------------------------------

  /**
   * Converte o DTO de endereco do destinatario em um embedded {@code EnderecoDestinatario}.
   *
   * <p>Centraliza a construcao do endereco para evitar duplicacao de logica entre
   * {@link #update(UUID, NotaFiscalRequest)} e quaisquer futuros metodos que precisem
   * instanciar o endereco a partir do request.
   *
   * @param req DTO de endereco validado pelo Bean Validation
   * @return entidade embutida pronta para ser associada a uma {@code NotaFiscal}
   */
  private br.com.agrocontabil.model.EnderecoDestinatario buildEndereco(
      NotaFiscalRequest.EnderecoDestinatarioRequest req) {
    return br.com.agrocontabil.model.EnderecoDestinatario.builder()
        .logradouro(req.logradouro())
        .numero(req.numero())
        .municipio(req.municipio())
        .codigoMunicipioIbge(req.codigoMunicipioIbge())
        .uf(req.uf())
        .cep(req.cep())
        .build();
  }
}
