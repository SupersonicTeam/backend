package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ImportacaoNotaResultado;
import br.com.agrocontabil.exception.XmlImportacaoInvalidaException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.nfe.NfeToNotaFiscalMapper;
import br.com.agrocontabil.nfe.NfeXmlImporter;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquestra a importacao de XMLs de nota fiscal (issue #19 — UC002).
 *
 * <p>Fluxo por arquivo: parse XXE-safe → validacao XSD → dedup por chave de acesso →
 * mapeamento reverso → persistencia. O produtor autenticado e o dono/destinatario da nota
 * importada (nota de entrada/compra; emitente = terceiro).
 *
 * <p><strong>Resiliencia do lote:</strong> {@link #importar(List)} nunca propaga excecao por
 * arquivo ruim — cada arquivo produz um {@link ImportacaoNotaResultado} (sucesso, duplicada ou
 * invalida). Assim, 1 arquivo valido + 1 invalido resultam em sucesso parcial, sem HTTP 500.
 * O metodo do lote nao e {@code @Transactional}: cada {@code save} do repositorio roda em sua
 * propria transacao, de modo que a falha de um arquivo nao faz rollback dos ja importados.
 *
 * <p><strong>LGPD:</strong> nunca loga CPF/CNPJ completo — usa {@link #mascarar(String)}.
 */
@Service
public class ImportacaoNotaFiscalService {

  private static final Logger log = LoggerFactory.getLogger(ImportacaoNotaFiscalService.class);

  private final NfeXmlImporter nfeXmlImporter;
  private final NfeToNotaFiscalMapper nfeToNotaFiscalMapper;
  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;

  /** Constroi o servico com as dependencias de parse, mapeamento e persistencia. */
  public ImportacaoNotaFiscalService(
      NfeXmlImporter nfeXmlImporter,
      NfeToNotaFiscalMapper nfeToNotaFiscalMapper,
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider) {
    this.nfeXmlImporter = nfeXmlImporter;
    this.nfeToNotaFiscalMapper = nfeToNotaFiscalMapper;
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
  }

  /**
   * Importa um lote de arquivos XML, devolvendo um resultado por arquivo.
   *
   * @param arquivos lista de arquivos (nome + conteudo bruto) a importar
   * @return lista de resultados na mesma ordem dos arquivos recebidos
   */
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

      NotaFiscal salva = notaFiscalRepository.save(nota);
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
