package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ImportacaoNotaResultado;
import br.com.agrocontabil.event.NotaImportadaEvent;
import br.com.agrocontabil.exception.XmlImportacaoInvalidaException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.nfe.NfeToNotaFiscalMapper;
import br.com.agrocontabil.nfe.NfeXmlImporter;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orquestra a importacao de XMLs de nota fiscal (issue #19 — UC002).
 *
 * <p>Fluxo por arquivo: parse XXE-safe → validacao XSD → dedup por chave de acesso →
 * mapeamento reverso → calculo do credito tributario (RN003 — issue Agrocontabil#30) →
 * persistencia. O produtor autenticado e o dono/destinatario da nota importada
 * (nota de entrada/compra; emitente = terceiro).
 *
 * <p><strong>Resiliencia do lote:</strong> {@link #importar(List)} nunca propaga excecao por
 * arquivo ruim — cada arquivo produz um {@link ImportacaoNotaResultado} (sucesso, duplicada ou
 * invalida). Assim, 1 arquivo valido + 1 invalido resultam em sucesso parcial, sem HTTP 500.
 * O metodo do lote nao e {@code @Transactional}: o calculo do credito + persistencia de cada
 * arquivo roda em sua propria transacao (via {@link TransactionTemplate}), de modo que a falha de
 * um arquivo nao faz rollback dos ja importados.
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

  private final NfeXmlImporter nfeXmlImporter;
  private final NfeToNotaFiscalMapper nfeToNotaFiscalMapper;
  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final CreditoTributarioService creditoTributarioService;
  private final ApplicationEventPublisher eventPublisher;
  private final TransactionTemplate transactionTemplate;

  /** Constroi o servico com as dependencias de parse, mapeamento, credito, evento e persistencia. */
  public ImportacaoNotaFiscalService(
      NfeXmlImporter nfeXmlImporter,
      NfeToNotaFiscalMapper nfeToNotaFiscalMapper,
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      CreditoTributarioService creditoTributarioService,
      ApplicationEventPublisher eventPublisher,
      TransactionTemplate transactionTemplate) {
    this.nfeXmlImporter = nfeXmlImporter;
    this.nfeToNotaFiscalMapper = nfeToNotaFiscalMapper;
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.creditoTributarioService = creditoTributarioService;
    this.eventPublisher = eventPublisher;
    this.transactionTemplate = transactionTemplate;
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
    for (ArquivoXml arquivo : arquivos) {
      resultados.add(importarArquivo(arquivo, produtor));
    }
    log.info("Importacao concluida: produtor={} arquivos={} sucessos={}",
        produtor.getId(), arquivos.size(),
        resultados.stream().filter(ImportacaoNotaResultado::sucesso).count());
    return resultados;
  }

  private ImportacaoNotaResultado importarArquivo(ArquivoXml arquivo, Produtor produtor) {
    String nome = arquivo.nome();
    try {
      NfeXmlImporter.ResultadoImportacao parsed = nfeXmlImporter.importar(arquivo.conteudo());
      NotaFiscal nota = nfeToNotaFiscalMapper.toNotaFiscal(
          parsed.nfe(), produtor, parsed.nfeXml(), parsed.autorizada());

      String chave = nota.getChaveAcesso();
      if (notaFiscalRepository.existsByChaveAcesso(chave)) {
        log.info("XML importado ignorado (chave duplicada): arquivo={} produtor={}",
            nome, produtor.getId());
        return ImportacaoNotaResultado.duplicada(nome, chave);
      }

      // RN003 (issue Agrocontabil#30): credito de IBS/CBS aproveitavel da nota de entrada,
      // calculado por item elegivel antes da persistencia. issue Agrocontabil#64: o publishEvent
      // roda DENTRO da transacao (nao depois do save) para que o listener AFTER_COMMIT em
      // ApuracaoService registre a sincronizacao — fora de transacao o evento seria descartado.
      NotaFiscal salva = transactionTemplate.execute(status -> {
        creditoTributarioService.calcular(nota);
        NotaFiscal notaSalva = notaFiscalRepository.save(nota);
        if (notaSalva.getStatus() == StatusNotaFiscal.AUTORIZADA) {
          YearMonth periodo =
              YearMonth.from(notaSalva.getDataEmissao().atZoneSameInstant(FUSO_BRASIL));
          eventPublisher.publishEvent(new NotaImportadaEvent(produtor.getId(), periodo));
        }
        return notaSalva;
      });

      log.info("XML importado com sucesso: arquivo={} notaId={} produtor={} emitente={}",
          nome, salva.getId(), produtor.getId(), mascarar(salva.getEmitenteCpfCnpj()));

      return ImportacaoNotaResultado.importada(nome, salva.getId(), chave);

    } catch (XmlImportacaoInvalidaException e) {
      log.warn("XML importado invalido: arquivo={} produtor={} motivo={}",
          nome, produtor.getId(), e.getMessage());
      return ImportacaoNotaResultado.invalida(nome, primeiroMotivo(e));
    } catch (RuntimeException e) {
      // Rede de seguranca: qualquer falha inesperada em um arquivo nao derruba o lote.
      log.error("Falha inesperada ao importar XML: arquivo={} produtor={}",
          nome, produtor.getId(), e);
      return ImportacaoNotaResultado.invalida(nome,
          "Nao foi possivel processar o arquivo. Verifique se e um XML de NF-e valido.");
    }
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

  /**
   * Arquivo XML de entrada desacoplado do {@code MultipartFile} do Spring, para manter o
   * servico testavel sem dependencia da API web.
   *
   * @param nome     nome original do arquivo
   * @param conteudo bytes do arquivo
   */
  public record ArquivoXml(String nome, byte[] conteudo) {}
}
