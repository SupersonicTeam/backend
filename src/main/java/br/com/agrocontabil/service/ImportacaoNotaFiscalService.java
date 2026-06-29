package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ImportacaoItemResultado;
import br.com.agrocontabil.dto.ImportacaoLoteResponse;
import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.nfe.NfeImportMapper;
import br.com.agrocontabil.nfe.NfeXmlParser;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.NotaFiscalRepository;
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
 * <p>Para cada arquivo: parse ({@link NfeXmlParser}) → mapeamento
 * ({@link NfeImportMapper}) → dedup por chave → persistência, vinculando ao produtor
 * autenticado. Falhas são capturadas por arquivo; arquivos válidos são importados
 * mesmo que outros falhem. Retorna um relatório consolidado.
 */
@Service
public class ImportacaoNotaFiscalService {

  private static final Logger log = LoggerFactory.getLogger(ImportacaoNotaFiscalService.class);

  private final NfeXmlParser parser;
  private final NfeImportMapper mapper;
  private final NotaFiscalRepository repository;
  private final AuthenticatedProdutorProvider produtorProvider;

  public ImportacaoNotaFiscalService(
      NfeXmlParser parser,
      NfeImportMapper mapper,
      NotaFiscalRepository repository,
      AuthenticatedProdutorProvider produtorProvider) {
    this.parser = parser;
    this.mapper = mapper;
    this.repository = repository;
    this.produtorProvider = produtorProvider;
  }

  /**
   * Importa cada arquivo do lote, devolvendo o relatório por arquivo.
   *
   * @param arquivos arquivos XML enviados
   * @return relatório consolidado (total/importados/falhas/resultados)
   */
  @Transactional
  public ImportacaoLoteResponse importar(List<MultipartFile> arquivos) {
    Produtor dono = produtorProvider.get();
    List<ImportacaoItemResultado> resultados = new ArrayList<>();

    for (MultipartFile arquivo : arquivos) {
      String nome = arquivo.getOriginalFilename() != null
          ? arquivo.getOriginalFilename() : "arquivo.xml";
      resultados.add(importarUm(arquivo, nome, dono));
    }
    return ImportacaoLoteResponse.de(resultados);
  }

  private ImportacaoItemResultado importarUm(MultipartFile arquivo, String nome, Produtor dono) {
    try {
      byte[] bytes = arquivo.getBytes();
      NFe nfe = parser.parse(bytes);
      NotaFiscal nota = mapper.toEntity(nfe, new String(bytes, StandardCharsets.UTF_8), dono);

      if (repository.existsByChaveAcesso(nota.getChaveAcesso())) {
        return ImportacaoItemResultado.erro(nome,
            "Nota ja importada (chave " + nota.getChaveAcesso() + ").");
      }

      NotaFiscal salva = repository.save(nota);
      log.info("XML importado: arquivo={} notaId={} chave={} produtor={}",
          nome, salva.getId(), salva.getChaveAcesso(), dono.getId());
      return ImportacaoItemResultado.sucesso(nome, salva.getId(), salva.getChaveAcesso());

    } catch (XmlImportacaoException e) {
      log.warn("Falha ao importar {}: {}", nome, e.getMessage());
      return ImportacaoItemResultado.erro(nome, e.getMessage());
    } catch (DataIntegrityViolationException e) {
      log.warn("Chave duplicada ao importar {}", nome);
      return ImportacaoItemResultado.erro(nome, "Nota ja importada (chave duplicada).");
    } catch (IOException e) {
      log.warn("Erro de leitura ao importar {}: {}", nome, e.getMessage());
      return ImportacaoItemResultado.erro(nome, "Falha ao ler o arquivo: " + e.getMessage());
    }
  }
}
