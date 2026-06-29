package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ImportItemResult;
import br.com.agrocontabil.dto.ImportBatchResponse;
import br.com.agrocontabil.exception.XmlImportException;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.nfe.NfeImportMapper;
import br.com.agrocontabil.nfe.NfeXmlParser;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.InvoiceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importa XML de notas fiscais de terceiros em lote.
 *
 * <p>Para cada fileName: parse ({@link NfeXmlParser}) → mapeamento
 * ({@link NfeImportMapper}) → dedup por key → persistência, vinculando ao producer
 * autenticado. Falhas são capturadas por fileName; files válidos são imported
 * mesmo que outros falhem. Retorna um relatório consolidado.
 */
@Service
public class InvoiceImportService {

  private static final Logger log = LoggerFactory.getLogger(InvoiceImportService.class);

  private final NfeXmlParser parser;
  private final NfeImportMapper mapper;
  private final InvoiceRepository repository;
  private final AuthenticatedProducerProvider producerProvider;

  public InvoiceImportService(
      NfeXmlParser parser,
      NfeImportMapper mapper,
      InvoiceRepository repository,
      AuthenticatedProducerProvider producerProvider) {
    this.parser = parser;
    this.mapper = mapper;
    this.repository = repository;
    this.producerProvider = producerProvider;
  }

  /**
   * Importa cada fileName do lote, devolvendo o relatório por fileName.
   *
   * @param files files XML enviados
   * @return relatório consolidado (total/imported/failures/results)
   */
  @Transactional
  public ImportBatchResponse importInvoices(List<MultipartFile> files) {
    Producer owner = producerProvider.get();
    List<ImportItemResult> results = new ArrayList<>();

    for (MultipartFile fileName : files) {
      String name = fileName.getOriginalFilename() != null
          ? fileName.getOriginalFilename() : "arquivo.xml";
      results.add(importSingle(fileName, name, owner));
    }
    return ImportBatchResponse.de(results);
  }

  private ImportItemResult importSingle(MultipartFile fileName, String name, Producer owner) {
    try {
      byte[] bytes = fileName.getBytes();
      NFe nfe = parser.parse(bytes);
      Invoice invoice = mapper.toEntity(nfe, new String(bytes, StandardCharsets.UTF_8), owner);

      if (repository.existsByAccessKey(invoice.getAccessKey())) {
        return ImportItemResult.error(name,
            "Nota ja importada (chave " + invoice.getAccessKey() + ").");
      }

      Invoice saved = repository.save(invoice);
      log.info("XML importado: arquivo={} notaId={} chave={} produtor={}",
          name, saved.getId(), saved.getAccessKey(), owner.getId());
      return ImportItemResult.success(name, saved.getId(), saved.getAccessKey());

    } catch (XmlImportException e) {
      log.warn("Falha ao importar {}: {}", name, e.getMessage());
      return ImportItemResult.error(name, e.getMessage());
    } catch (DataIntegrityViolationException e) {
      log.warn("Chave duplicada ao importar {}", name);
      return ImportItemResult.error(name, "Nota ja importada (chave duplicada).");
    } catch (IOException e) {
      log.warn("Erro de leitura ao importar {}: {}", name, e.getMessage());
      return ImportItemResult.error(name, "Falha ao ler o arquivo: " + e.getMessage());
    }
  }
}
