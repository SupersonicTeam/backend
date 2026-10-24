package br.com.agrocontabil.service;

import br.com.agrocontabil.danfe.DanfePdfService;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.exception.DanfeIndisponivelException;
import br.com.agrocontabil.exception.DanfePreviewIndisponivelException;
import br.com.agrocontabil.exception.NfaInvalidaParaEmissaoException;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.exception.NotaFiscalNaoEditavelException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.XmlGenerationException;
import br.com.agrocontabil.exception.XmlIndisponivelException;
import br.com.agrocontabil.mapper.NotaFiscalMapper;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

  /**
   * Status com preview de DANFE (issue #89): os anteriores à emissão. A aprovação do contador
   * (#178) acrescenta {@code AGUARDANDO_APROVACAO}, {@code APROVADA} e {@code DEVOLVIDA} aqui.
   */
  private static final Set<StatusNotaFiscal> STATUS_PREVIEW =
      EnumSet.of(StatusNotaFiscal.RASCUNHO, StatusNotaFiscal.COM_INCONSISTENCIAS);

  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final TributacaoService tributacaoService;
  private final GeracaoNotaFiscalService geracaoNotaFiscalService;
  private final GeracaoNfaService geracaoNfaService;
  private final DanfePdfService danfePdfService;
  private final NotaFiscalValidacaoService validacaoService;
  private final TransactionTemplate transactionTemplate;
  private final TransactionTemplate readOnlyTransactionTemplate;

  public NotaFiscalService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      TributacaoService tributacaoService,
      GeracaoNotaFiscalService geracaoNotaFiscalService,
      GeracaoNfaService geracaoNfaService,
      DanfePdfService danfePdfService,
      NotaFiscalValidacaoService validacaoService,
      TransactionTemplate transactionTemplate) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.tributacaoService = tributacaoService;
    this.geracaoNotaFiscalService = geracaoNotaFiscalService;
    this.geracaoNfaService = geracaoNfaService;
    this.danfePdfService = danfePdfService;
    this.validacaoService = validacaoService;
    this.transactionTemplate = transactionTemplate;
    // Variante somente-leitura derivada do template autoconfigurado: a fase de leitura
    // nao escreve nada, e marca-la readOnly permite ao Hibernate dispensar dirty checking.
    // Nunca usar esta variante junto de lock pessimista de escrita.
    TransactionTemplate somenteLeitura = new TransactionTemplate(
        transactionTemplate.getTransactionManager(), transactionTemplate);
    somenteLeitura.setReadOnly(true);
    this.readOnlyTransactionTemplate = somenteLeitura;
  }

  /**
   * Cria uma nova nota fiscal vinculada ao produtor autenticado.
   *
   * @param request dados validados da nota fiscal
   * @return DTO de resposta com ID gerado e totais calculados
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.NOTA_CRIADA, recurso = RecursoAuditado.NOTA_FISCAL)
  public NotaFiscalResponse create(NotaFiscalRequest request) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);
    // Calcula IBS/CBS após montagem dos itens e cálculo dos totais de produtos.
    tributacaoService.calcular(nota);
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
  @Transactional
  @Auditado(acao = AcaoAuditada.NOTA_ATUALIZADA, recurso = RecursoAuditado.NOTA_FISCAL)
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

    // Substitui itens — orphanRemoval remove os anteriores automaticamente.
    // O flush apos o clear() e obrigatorio: a ActionQueue do Hibernate executa INSERTs
    // antes de DELETEs dentro do mesmo flush, entao sem forcar a exclusao dos itens antigos
    // primeiro, um item novo que reaproveita o mesmo numero_item viola a constraint unica
    // uk_nota_fiscal_item_nota_numero antes do DELETE do item antigo rodar.
    List<NotaFiscalItem> novosItens = NotaFiscalMapper.toItemEntityList(request.itens());

    nota.getItens().clear();
    notaFiscalRepository.flush();
    novosItens.forEach(nota::addItem);

    BigDecimal totalProdutos = nota.getItens().stream()
        .map(NotaFiscalItem::getValorTotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    nota.setValorTotalProdutos(totalProdutos);
    nota.setValorTotalNota(totalProdutos);

    // Recalcula IBS/CBS após substituição dos itens e recálculo dos totais de produtos.
    tributacaoService.calcular(nota);

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
  @Transactional
  @Auditado(acao = AcaoAuditada.NOTA_EXCLUIDA, recurso = RecursoAuditado.NOTA_FISCAL)
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
  @Auditado(acao = AcaoAuditada.NOTA_XML_GERADO, recurso = RecursoAuditado.NOTA_FISCAL)
  public NotaFiscalResponse gerarXml(UUID id) {
    Produtor produtor = produtorProvider.get();

    // Fase 1 (transacao curta, somente leitura): valida a pre-condicao e materializa o grafo de
    // emissao (itens + produtor) via JOIN FETCH. A entidade sai daqui destacada, mas completa —
    // o pipeline a percorre sem tocar em proxy nenhum.
    NotaFiscal nota = emTransacaoDeLeitura(() -> {
      NotaFiscal encontrada = notaFiscalRepository
          .findByIdAndProdutorIdComGrafoDeEmissao(id, produtor.getId())
          .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));
      if (!STATUS_EDITAVEIS.contains(encontrada.getStatus())) {
        throw new NotaFiscalNaoEditavelException(id, encontrada.getStatus());
      }
      return encontrada;
    });

    // Fase 2: FORA de transacao. Validacao fiscal, JAXB, assinatura RSA e XSD custam CPU e tempo;
    // dentro da transacao prendiam uma conexao do pool do inicio ao fim (issue #99). O registro de
    // inconsistencias tambem acontece aqui, e e o que permite trocar o REQUIRES_NEW por uma
    // transacao unica e independente (issue #97).
    String xml = gerarXmlRegistrandoInconsistencias(nota);

    // Extrai a chave de acesso do atributo Id da infNFe: "NFe" + 44 digitos numericos.
    // O GeracaoNotaFiscalService garante que o Id esta no formato correto apos geracao
    // bem-sucedida — fazemos a extracao direto do XML para nao expor o grafo JAXB.
    String chaveAcesso = extrairChaveAcessoDoXml(xml);

    // Fase 3 (transacao curta de escrita): recarrega sob lock, RECHECA status E versao, e persiste.
    // Duas checagens, porque a janela aberta pela fase 2 tem dois riscos distintos:
    //
    //  - outra GERACAO venceu a corrida -> o status ja e XML_GERADO -> 409;
    //  - outra EDICAO alterou a nota (update e permitido em RASCUNHO/COM_INCONSISTENCIAS) -> o
    //    status continua editavel, mas o XML assinado na fase 2 descreve dados que nao existem
    //    mais. Persistir aqui gravaria um XML divergente da propria linha — inclusive uma chave
    //    de acesso cujo nNF nao corresponde ao numero atual —, e a nota ficaria imutavel nesse
    //    estado, pronta para ser transmitida assim a SEFAZ.
    //
    // Comparar a versao lida na fase 1 com a atual detecta a segunda situacao: e para isso que o
    // @Version (issue #95) existe. Nao da para confiar no lock otimista do save, porque a
    // entidade gravada aqui e uma instancia recem-carregada, com a versao ja atualizada.
    Long versaoLida = nota.getVersion();
    return emTransacao(() -> {
      NotaFiscal atual = notaFiscalRepository
          .findByIdAndProdutorIdForUpdate(id, produtor.getId())
          .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));
      if (!STATUS_EDITAVEIS.contains(atual.getStatus())) {
        throw new NotaFiscalNaoEditavelException(id, atual.getStatus());
      }
      if (!Objects.equals(versaoLida, atual.getVersion())) {
        log.warn("Nota alterada durante a geracao do XML: id={} versaoLida={} versaoAtual={}",
            id, versaoLida, atual.getVersion());
        throw new OptimisticLockingFailureException(
            "NotaFiscal " + id + " foi alterada durante a geracao do XML");
      }

      atual.setXmlGerado(xml);
      atual.setVersaoLayoutNfe(VERSAO_LAYOUT_NFE);
      atual.setDataGeracaoXml(OffsetDateTime.now());
      atual.setStatus(StatusNotaFiscal.XML_GERADO);
      atual.setChaveAcesso(chaveAcesso);

      NotaFiscal saved = notaFiscalRepository.save(atual);
      log.info("XML da NF-e gerado e persistido: id={} produtor={} chave={}",
          saved.getId(), produtor.getId(), chaveAcesso);
      return NotaFiscalMapper.toResponse(saved);
    });
  }

  /**
   * Retorna o XML da NF-e armazenado para a nota indicada.
   *
   * <p>Ownership: busca via {@code findByIdAndProdutorId} — 404 se a nota nao pertencer
   * ao produtor autenticado (anti-IDOR).
   *
   * @param id identificador da nota fiscal
   * @return XML da NF-e como string UTF-8
   * @throws ResourceNotFoundException  se a nota nao existir ou pertencer a outro produtor
   * @throws XmlIndisponivelException   se a nota existir mas o XML ainda nao tiver sido
   *                                    gerado (HTTP 409, simetrico a DANFE)
   */
  @Transactional(readOnly = true)
  public String obterXml(UUID id) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (nota.getXmlGerado() == null) {
      throw new XmlIndisponivelException(id, nota.getStatus());
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
  public byte[] gerarPdf(UUID id) {
    Produtor produtor = produtorProvider.get();

    // Fase 1 (transacao curta, somente leitura): ownership, pre-condicao e materializacao do grafo
    // que o relatorio percorre (itens + produtor; os enderecos sao @Embedded).
    NotaFiscal nota = emTransacaoDeLeitura(() -> {
      NotaFiscal encontrada = notaFiscalRepository
          .findByIdAndProdutorIdComGrafoDeEmissao(id, produtor.getId())
          .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));
      if (encontrada.getXmlGerado() == null) {
        throw new DanfeIndisponivelException(id, encontrada.getStatus());
      }
      return encontrada;
    });

    // Fase 2: FORA de transacao. A renderizacao do JasperReports e a operacao mais longa do fluxo
    // de leitura; dentro da transacao, N downloads concorrentes ocupavam o pool inteiro enquanto
    // montavam PDF (issue #99). Nao ha fase de escrita: gerar DANFE nao altera a nota.
    return danfePdfService.gerarPdf(nota);
  }

  /**
   * Gera a pré-visualização da DANFE de uma nota ainda não emitida (issue #89).
   *
   * <p>Só para os status de {@link #STATUS_PREVIEW}; fora deles vale o {@link #gerarPdf(UUID)} de
   * verdade (409). Não roda o {@code FiscalRulesValidator} — conferir uma nota incompleta é
   * justamente o caso de uso —, não assina, não gera chave e não grava nada: a nota sai da
   * transação de leitura e o PDF é montado fora dela, como no {@link #gerarPdf(UUID)} (#99).
   *
   * @param id identificador da nota
   * @return bytes do PDF com a marca d'água "RASCUNHO — SEM VALOR FISCAL"
   * @throws ResourceNotFoundException          nota inexistente ou de outro produtor (404)
   * @throws DanfePreviewIndisponivelException nota já emitida (409)
   */
  public byte[] gerarPreviewDanfe(UUID id) {
    Produtor produtor = produtorProvider.get();

    NotaFiscal nota = emTransacaoDeLeitura(() -> {
      NotaFiscal encontrada = notaFiscalRepository
          .findByIdAndProdutorIdComGrafoDeEmissao(id, produtor.getId())
          .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));
      if (!STATUS_PREVIEW.contains(encontrada.getStatus())) {
        throw new DanfePreviewIndisponivelException(id, encontrada.getStatus());
      }
      return encontrada;
    });

    return danfePdfService.gerarPreview(nota);
  }

  // -------------------------------------------------------------------------
  // Suporte transacional das fases de emissao (issue #99)
  // -------------------------------------------------------------------------

  /**
   * Executa um bloco numa transação curta de escrita.
   *
   * <p>Usa {@link TransactionTemplate} em vez de {@code @Transactional} por método porque as fases
   * de {@link #gerarXml(UUID)} são chamadas de dentro do próprio serviço: uma chamada direta entre
   * métodos da mesma classe <strong>não passa pelo proxy do Spring</strong> e a anotação seria
   * ignorada <em>em silêncio</em> — a assinatura RSA e o XSD voltariam a rodar dentro da transação,
   * exatamente o defeito que esta issue remove, e sem nenhum sintoma visível. Mesmo desenho já
   * usado em {@link ImportacaoNotaFiscalService} e {@link SefazService}.
   */
  private <T> T emTransacao(Supplier<T> acao) {
    return transactionTemplate.execute(status -> acao.get());
  }

  /** Executa um bloco numa transação curta somente-leitura. Ver {@link #emTransacao(Supplier)}. */
  private <T> T emTransacaoDeLeitura(Supplier<T> acao) {
    return readOnlyTransactionTemplate.execute(status -> acao.get());
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
