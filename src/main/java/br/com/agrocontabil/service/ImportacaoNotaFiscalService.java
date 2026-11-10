package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ImportacaoNotaResultado;
import br.com.agrocontabil.event.NotaImportadaEvent;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import br.com.agrocontabil.exception.SefazComunicacaoException;
import br.com.agrocontabil.exception.XmlImportacaoInvalidaException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.nfe.AssinaturaValidator;
import br.com.agrocontabil.nfe.DigestAssinatura;
import br.com.agrocontabil.nfe.NfeToNotaFiscalMapper;
import br.com.agrocontabil.nfe.NfeXmlImporter;
import br.com.agrocontabil.nfe.model.Dest;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orquestra a importacao de XMLs de nota fiscal (issue #19 — UC002).
 *
 * <p>Fluxo por arquivo: parse XXE-safe → validacao XSD → destinatario = produtor autenticado →
 * assinatura digital → mapeamento reverso → dedup por chave <em>do produtor</em> → confirmacao da
 * autorizacao na SEFAZ pela chave → calculo do credito tributario (RN003 — issue Agrocontabil#30)
 * → persistencia. O produtor autenticado e o dono/destinatario da nota importada (nota de
 * entrada/compra; emitente = terceiro).
 *
 * <p><strong>Nada do XML vale como autorizacao (issue #223).</strong> Antes, a nota entrava como
 * {@code AUTORIZADA} pelo {@code cStat=100} do {@code protNFe} que o proprio usuario enviava, sem
 * conferir destinatario nem assinatura: qualquer produtor importava NF-e de outra pessoa, ou
 * forjava uma, e ganhava credito de IBS/CBS. Agora:
 * <ul>
 *   <li>o CPF/CNPJ do {@code dest} precisa ser o do produtor autenticado;</li>
 *   <li>a assinatura e conferida pelo {@link AssinaturaValidator} (XML alterado depois de assinado
 *       e recusado);</li>
 *   <li>a SEFAZ e consultada pela chave, fora de transacao, e o {@code digVal} do protocolo tem de
 *       ser o DigestValue do XML — prova que o conteudo e o autorizado, e nao um XML com a mesma
 *       chave assinado por qualquer certificado. Autorizada: {@code AUTORIZADA}, com o protocolo da
 *       SEFAZ (o do upload e ignorado). Nao encontrada, rejeitada, denegada ou cancelada: recusada,
 *       nada gravado. Sem resposta (fora do ar, em processamento): entra {@code PENDENTE_SEFAZ},
 *       sem entrar na apuracao (o credito so conta {@code AUTORIZADA}), e o
 *       {@code SefazStatusSyncJob} confirma depois com as mesmas regras.</li>
 * </ul>
 *
 * <p><strong>Duplicidade por produtor.</strong> A chave de acesso e unica por (produtor, chave,
 * origem), nao no sistema inteiro (changeSet 0045): o produtor A emite (PROPRIA) e o produtor B, o
 * destinatario, importa a mesma chave. Antes, B nao conseguia, e a resposta "duplicada" revelava
 * que a chave existia em outra conta.
 *
 * <p><strong>Resiliencia do lote:</strong> {@link #importar(List)} nunca propaga excecao por
 * arquivo ruim — cada arquivo produz um {@link ImportacaoNotaResultado} (sucesso, duplicada ou
 * invalida). Assim, 1 arquivo valido + 1 invalido resultam em sucesso parcial, sem HTTP 500.
 * O metodo do lote nao e {@code @Transactional}: a consulta a SEFAZ roda fora de transacao, e o
 * calculo do credito + persistencia de cada arquivo roda em sua propria transacao (via
 * {@link TransactionTemplate}), de modo que a falha de um arquivo nao faz rollback dos ja
 * importados.
 *
 * <p><strong>Publicacao do evento dentro da transacao (issue Agrocontabil#64):</strong> o
 * {@link br.com.agrocontabil.event.NotaImportadaEvent} e publicado <em>dentro</em> da mesma
 * transacao do {@code save} (via {@link TransactionTemplate}, nao apos ele) — o listener em
 * {@link ApuracaoService} e {@code @TransactionalEventListener(AFTER_COMMIT)}, que so registra a
 * sincronizacao se houver transacao ativa no momento da publicacao; publicar fora de uma
 * transacao faria o evento ser descartado silenciosamente.
 *
 * <p><strong>LGPD:</strong> nunca loga CPF/CNPJ completo — usa {@link #mascarar(String)}.
 */
@Service
public class ImportacaoNotaFiscalService {

  private static final Logger log = LoggerFactory.getLogger(ImportacaoNotaFiscalService.class);

  /** Fuso de referência do projeto para o período (mês) afetado na apuração. */
  private static final ZoneId FUSO_BRASIL = ZoneId.of("America/Sao_Paulo");

  /** Teto de um XML de NF-e importado (o multipart aceita até 5 MB por causa dos anexos, #180). */
  static final int TAMANHO_MAXIMO_XML = 2 * 1024 * 1024;

  /** {@code tpAmb} de produção no layout; qualquer outro valor é homologação. */
  private static final String TP_AMB_PRODUCAO = "1";

  private final NfeXmlImporter nfeXmlImporter;
  private final NfeToNotaFiscalMapper nfeToNotaFiscalMapper;
  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final CreditoTributarioService creditoTributarioService;
  private final ApplicationEventPublisher eventPublisher;
  private final TransactionTemplate transactionTemplate;
  private final AssinaturaValidator assinaturaValidator;
  private final SefazClient sefazClient;

  /**
   * Constroi o servico com as dependencias de parse, mapeamento, assinatura, consulta a SEFAZ,
   * credito, evento e persistencia.
   */
  public ImportacaoNotaFiscalService(
      NfeXmlImporter nfeXmlImporter,
      NfeToNotaFiscalMapper nfeToNotaFiscalMapper,
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      CreditoTributarioService creditoTributarioService,
      ApplicationEventPublisher eventPublisher,
      TransactionTemplate transactionTemplate,
      AssinaturaValidator assinaturaValidator,
      SefazClient sefazClient) {
    this.nfeXmlImporter = nfeXmlImporter;
    this.nfeToNotaFiscalMapper = nfeToNotaFiscalMapper;
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.creditoTributarioService = creditoTributarioService;
    this.eventPublisher = eventPublisher;
    this.transactionTemplate = transactionTemplate;
    this.assinaturaValidator = assinaturaValidator;
    this.sefazClient = sefazClient;
  }

  /**
   * Importa um lote de arquivos XML, devolvendo um resultado por arquivo.
   *
   * @param arquivos lista de arquivos (nome + conteudo bruto) a importar
   * @return lista de resultados na mesma ordem dos arquivos recebidos
   */
  @Auditado(acao = AcaoAuditada.NOTAS_IMPORTADAS, recurso = RecursoAuditado.NOTA_FISCAL)
  public List<ImportacaoNotaResultado> importar(List<ArquivoXml> arquivos) {
    Produtor produtor = produtorProvider.get();
    List<ImportacaoNotaResultado> resultados = new ArrayList<>();
    Lote lote = new Lote();
    for (ArquivoXml arquivo : arquivos) {
      resultados.add(importarArquivo(arquivo, produtor, lote));
    }
    log.info("Importacao concluida: produtor={} arquivos={} sucessos={}",
        produtor.getId(), arquivos.size(),
        resultados.stream().filter(ImportacaoNotaResultado::sucesso).count());
    return resultados;
  }

  private ImportacaoNotaResultado importarArquivo(ArquivoXml arquivo, Produtor produtor,
      Lote lote) {
    String nome = arquivo.nome();
    try {
      // O multipart aceita 5 MB por arquivo desde os anexos da #180; um XML de NF-e segue com 2 MB.
      if (arquivo.conteudo() != null && arquivo.conteudo().length > TAMANHO_MAXIMO_XML) {
        throw new XmlImportacaoInvalidaException("Arquivo acima de 2 MB: não é um XML de NF-e.");
      }
      NfeXmlImporter.ResultadoImportacao parsed = nfeXmlImporter.importar(arquivo.conteudo());
      exigirDestinatario(parsed.nfe(), produtor);
      exigirAssinatura(parsed.nfeXml());
      NotaFiscal nota = nfeToNotaFiscalMapper.toNotaFiscal(parsed.nfe(), produtor, parsed.nfeXml());

      String chave = nota.getChaveAcesso();
      if (notaFiscalRepository.existsByProdutorIdAndChaveAcessoAndOrigem(
          produtor.getId(), chave, OrigemNotaFiscal.IMPORTADA)) {
        log.info("XML importado ignorado (chave duplicada): arquivo={} produtor={}",
            nome, produtor.getId());
        return ImportacaoNotaResultado.duplicada(nome, chave);
      }

      // Fora de transacao (#96/#99): nenhuma conexao do pool presa durante a rede.
      confirmarNaSefaz(nota, ambienteDe(parsed.nfe()), lote);

      // RN003 (issue Agrocontabil#30): credito de IBS/CBS aproveitavel da nota de entrada,
      // calculado por item elegivel antes da persistencia; so entra na apuracao quando AUTORIZADA.
      // issue Agrocontabil#64: o publishEvent roda DENTRO da transacao (nao depois do save) para
      // que o listener AFTER_COMMIT em ApuracaoService registre a sincronizacao.
      NotaFiscal salva;
      try {
        salva = transactionTemplate.execute(status -> {
          creditoTributarioService.calcular(nota);
          NotaFiscal notaSalva = notaFiscalRepository.save(nota);
          if (notaSalva.getStatus() == StatusNotaFiscal.AUTORIZADA) {
            YearMonth periodo =
                YearMonth.from(notaSalva.getDataEmissao().atZoneSameInstant(FUSO_BRASIL));
            eventPublisher.publishEvent(new NotaImportadaEvent(produtor.getId(), periodo));
          }
          return notaSalva;
        });
      } catch (DataIntegrityViolationException e) {
        // Dois envios simultaneos da mesma chave: a constraint (produtor, chave, origem) barra o
        // segundo depois da consulta a SEFAZ.
        log.info("XML importado ignorado (chave duplicada na gravacao): arquivo={} produtor={}",
            nome, produtor.getId());
        return ImportacaoNotaResultado.duplicada(nome, chave);
      }

      log.info("XML importado com sucesso: arquivo={} notaId={} produtor={} emitente={} status={}",
          nome, salva.getId(), produtor.getId(), mascarar(salva.getEmitenteCpfCnpj()),
          salva.getStatus());

      return salva.getStatus() == StatusNotaFiscal.AUTORIZADA
          ? ImportacaoNotaResultado.importada(nome, salva.getId(), chave)
          : ImportacaoNotaResultado.aguardandoSefaz(nome, salva.getId(), chave);

    } catch (XmlImportacaoInvalidaException e) {
      log.warn("XML importado invalido: arquivo={} produtor={} motivo={}",
          nome, produtor.getId(), e.getMessage());
      return ImportacaoNotaResultado.invalida(nome, primeiroMotivo(e));
    } catch (RuntimeException e) {
      // Rede de seguranca: qualquer falha inesperada em um arquivo nao derruba o lote.
      log.error("Falha inesperada ao importar XML: arquivo={} produtor={}",
          nome, produtor.getId(), e);
      return ImportacaoNotaResultado.invalida(nome,
          "Não foi possível processar o arquivo. Verifique se é um XML de NF-e válido.");
    }
  }

  /**
   * O destinatario do XML e o produtor autenticado (issue #223).
   *
   * <p>Compara so os digitos: o {@code dest} traz {@code CNPJ} ou {@code CPF} sem mascara, e o
   * cadastro pode ter sido gravado com ela. NF-e sem destinatario identificado (consumidor final
   * sem documento) tambem e recusada: nao ha como provar que e do produtor.
   */
  private static void exigirDestinatario(NFe nfe, Produtor produtor) {
    Dest dest = nfe.getInfNFe() == null ? null : nfe.getInfNFe().getDest();
    String documentoDest = dest == null ? null
        : (dest.getCnpj() != null ? dest.getCnpj() : dest.getCpf());
    String documentoProdutor = produtor.getCpfCnpj();
    if (documentoDest == null || documentoProdutor == null
        || !digitos(documentoDest).equals(digitos(documentoProdutor))) {
      // Sem o documento na mensagem (LGPD): o produtor sabe o proprio e nao precisa do alheio.
      throw new XmlImportacaoInvalidaException("O destinatário desta NF-e não é você: só a nota "
          + "emitida para o seu CPF/CNPJ pode ser importada.");
    }
  }

  /** A assinatura confere: o XML nao foi alterado depois de assinado (issue #223). */
  private void exigirAssinatura(String nfeXml) {
    try {
      assinaturaValidator.validar(nfeXml);
    } catch (AssinaturaDigitalException e) {
      log.info("XML importado com assinatura invalida: {}", e.getMessage());
      throw new XmlImportacaoInvalidaException("Assinatura digital da NF-e ausente ou inválida: "
          + "o XML foi alterado depois de assinado. Importe o arquivo original do emitente.");
    }
  }

  /**
   * Confirma a autorizacao na SEFAZ pela chave e aplica o desfecho na nota (issue #223).
   *
   * <p>Disjuntor do lote: depois da primeira falha de comunicacao, os arquivos seguintes entram
   * pendentes sem consultar. Cada consulta com a SEFAZ fora do ar custa o pior caso do retry; num
   * lote de dez XMLs a requisicao ficaria presa por minutos.
   *
   * @param nota     nota mapeada do XML, ainda em {@code PENDENTE_SEFAZ}
   * @param ambiente ambiente do {@code tpAmb} do XML
   * @param lote     estado do lote (SEFAZ ja sem resposta neste lote)
   * @throws XmlImportacaoInvalidaException se a SEFAZ nao autorizou esta NF-e com este conteudo
   */
  private void confirmarNaSefaz(NotaFiscal nota, SefazAmbiente ambiente, Lote lote) {
    nota.setSefazAmbiente(ambiente);
    if (lote.sefazSemResposta) {
      return;
    }
    SefazRetorno retorno;
    try {
      retorno = sefazClient.consultarStatus(nota.getChaveAcesso(), ambiente);
    } catch (SefazComunicacaoException e) {
      lote.sefazSemResposta = true;
      log.warn("SEFAZ sem resposta na importacao; notas do lote entram pendentes de "
          + "confirmacao: {}", e.getMessage());
      return;
    }
    SituacaoSefaz situacao = retorno.situacao();
    if (situacao == SituacaoSefaz.PENDENTE) {
      return;
    }
    if (situacao != SituacaoSefaz.AUTORIZADA) {
      throw new XmlImportacaoInvalidaException("A SEFAZ não confirma esta NF-e como autorizada ("
          + situacao + ", cStat " + retorno.cStat() + "). Só NF-e autorizada pode ser importada.");
    }
    if (!DigestAssinatura.confere(nota.getXmlGerado(), retorno.digestValue())) {
      throw new XmlImportacaoInvalidaException("O conteúdo do XML difere da NF-e autorizada na "
          + "SEFAZ. Importe o arquivo original do emitente.");
    }
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);
    nota.setSefazCstat(retorno.cStat());
    nota.setSefazMotivo(retorno.xMotivo());
    nota.setProtocoloAutorizacao(retorno.protocolo());
    nota.setDataAutorizacao(retorno.dataAutorizacao());
  }

  private static SefazAmbiente ambienteDe(NFe nfe) {
    String tpAmb = nfe.getInfNFe() == null || nfe.getInfNFe().getIde() == null
        ? null : nfe.getInfNFe().getIde().getTpAmb();
    return TP_AMB_PRODUCAO.equals(tpAmb) ? SefazAmbiente.PRODUCAO : SefazAmbiente.HOMOLOGACAO;
  }

  private static String digitos(String documento) {
    return documento.replaceAll("\\D", "");
  }

  private String primeiroMotivo(XmlImportacaoInvalidaException e) {
    if (!e.getErrors().isEmpty() && e.getErrors().get(0).message() != null) {
      return e.getErrors().get(0).message();
    }
    return e.getMessage();
  }

  /** Mascara um CPF/CNPJ para log (LGPD): mantem apenas os 3 ultimos digitos. */
  private String mascarar(String documento) {
    if (documento == null || documento.length() < 4) {
      return "***";
    }
    return "***" + documento.substring(documento.length() - 3);
  }

  /** Estado de um lote de importacao. */
  private static final class Lote {
    /** A SEFAZ ja falhou neste lote: as proximas notas entram pendentes sem consultar. */
    private boolean sefazSemResposta;
  }

  /**
   * Arquivo XML de entrada desacoplado do {@code MultipartFile} do Spring, para manter o
   * servico testavel sem dependencia da API web.
   *
   * @param nome     nome original do arquivo
   * @param conteudo bytes do arquivo
   */
  public record ArquivoXml(String nome, byte[] conteudo) {}
}
