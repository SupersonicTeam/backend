package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.dto.SefazStatusResponse;
import br.com.agrocontabil.service.NotaFiscalService;
import br.com.agrocontabil.service.NotaFiscalValidacaoService;
import br.com.agrocontabil.service.SefazService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Endpoints REST para gerenciamento de notas fiscais.
 *
 * <p>Todas as operacoes sao restritas ao produtor autenticado — um produtor nunca
 * visualiza ou altera notas de outro produtor. A logica de verificacao de propriedade
 * esta na camada de servico.
 *
 * <p>O controller nao contem regras de negocio: apenas recebe o DTO validado,
 * delega ao {@link NotaFiscalService} e mapeia o resultado para a resposta HTTP.
 */
@RestController
@RequestMapping("/api/notas")
@Tag(name = "Notas Fiscais", description = "CRUD de notas fiscais eletronicas (NF-e modelo 55)")
public class NotaFiscalController {

  private final NotaFiscalService notaFiscalService;
  private final SefazService sefazService;
  private final NotaFiscalValidacaoService validacaoService;

  public NotaFiscalController(
      NotaFiscalService notaFiscalService,
      SefazService sefazService,
      NotaFiscalValidacaoService validacaoService) {
    this.notaFiscalService = notaFiscalService;
    this.sefazService = sefazService;
    this.validacaoService = validacaoService;
  }

  /**
   * Lista as notas fiscais do produtor autenticado, paginadas.
   */
  @GetMapping
  @Operation(
      summary = "Lista notas fiscais paginadas",
      description = "Retorna todas as notas do produtor autenticado. "
          + "Suporta paginacao e ordenacao via parametros do Pageable.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Lista retornada com sucesso")
  })
  public ResponseEntity<Page<NotaFiscalResponse>> listAll(
      @ParameterObject Pageable pageable) {
    return ResponseEntity.ok(notaFiscalService.findAll(pageable));
  }

  /**
   * Busca uma nota fiscal pelo ID.
   */
  @GetMapping("/{id}")
  @Operation(
      summary = "Busca uma nota fiscal por ID",
      description = "Retorna a nota somente se pertencer ao produtor autenticado. "
          + "Retorna 404 se inexistente ou pertencer a outro produtor (anti-IDOR).")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Nota encontrada"),
      @ApiResponse(responseCode = "404", description = "Nota nao encontrada",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<NotaFiscalResponse> findById(@PathVariable UUID id) {
    return ResponseEntity.ok(notaFiscalService.findById(id));
  }

  /**
   * Cria uma nova nota fiscal para o produtor autenticado.
   */
  @PostMapping
  @Operation(
      summary = "Cria uma nova nota fiscal",
      description = "Cria a nota com status RASCUNHO. Totais sao calculados automaticamente. "
          + "O campo modelo e fixado como '55' (NF-e). "
          + "Retorna 201 com header Location apontando para o recurso criado.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "201",
          description = "Nota criada com sucesso",
          headers = @Header(
              name = "Location",
              description = "URI do recurso criado",
              schema = @Schema(type = "string", format = "uri"))),
      @ApiResponse(responseCode = "400", description = "Dados invalidos",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<NotaFiscalResponse> create(
      @RequestBody @Valid NotaFiscalRequest request) {
    NotaFiscalResponse response = notaFiscalService.create(request);
    URI location = ServletUriComponentsBuilder
        .fromCurrentRequest()
        .path("/{id}")
        .buildAndExpand(response.id())
        .toUri();
    return ResponseEntity.created(location).body(response);
  }

  /**
   * Atualiza uma nota fiscal existente.
   */
  @PutMapping("/{id}")
  @Operation(
      summary = "Atualiza uma nota fiscal",
      description = "Somente notas em status RASCUNHO ou COM_INCONSISTENCIAS podem ser "
          + "atualizadas (a nota com inconsistencias e corrigivel). "
          + "Retorna 409 nos demais status.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Nota atualizada com sucesso"),
      @ApiResponse(responseCode = "400", description = "Dados invalidos",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "404", description = "Nota nao encontrada",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409",
          description = "Nota nao pode ser editada no status atual",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<NotaFiscalResponse> update(
      @PathVariable UUID id,
      @RequestBody @Valid NotaFiscalRequest request) {
    return ResponseEntity.ok(notaFiscalService.update(id, request));
  }

  /**
   * Exclui uma nota fiscal.
   */
  @DeleteMapping("/{id}")
  @Operation(
      summary = "Exclui uma nota fiscal",
      description = "Somente notas em status RASCUNHO ou COM_INCONSISTENCIAS podem ser "
          + "excluidas. Retorna 409 nos demais status.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Nota excluida com sucesso"),
      @ApiResponse(responseCode = "404", description = "Nota nao encontrada",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409",
          description = "Nota nao pode ser excluida no status atual",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    notaFiscalService.delete(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Gera o XML da NF-e para a nota indicada, persiste-o e transiciona o status.
   *
   * <p>Idempotencia: somente notas em RASCUNHO ou COM_INCONSISTENCIAS podem ter o XML
   * gerado. Chamadas subsequentes com status XML_GERADO retornam 409 — use GET /{id}/xml
   * para obter o XML ja gerado. Se a validacao falhar, a nota vai para COM_INCONSISTENCIAS
   * e o historico fica em GET /{id}/validacoes (issue #22).
   */
  @PostMapping("/{id}/gerar-xml")
  @Operation(
      summary = "Gera o XML da NF-e",
      description = "Gera, valida (XSD) e persiste o XML da NF-e. "
          + "Transiciona o status de RASCUNHO (ou COM_INCONSISTENCIAS, apos correcao) "
          + "para XML_GERADO. Preenche chaveAcesso e dataGeracaoXml na entidade. "
          + "Se a validacao falhar, a nota vai para COM_INCONSISTENCIAS (422) e as "
          + "inconsistencias ficam em GET /{id}/validacoes. "
          + "Retorna 409 se o status nao permitir geracao (ex.: XML ja gerado).")
  @ApiResponses({
      @ApiResponse(responseCode = "200",
          description = "XML gerado com sucesso; nota atualizada com status XML_GERADO"),
      @ApiResponse(responseCode = "404", description = "Nota nao encontrada ou nao pertence ao produtor",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409",
          description = "Nota nao esta em status RASCUNHO — XML ja foi gerado ou nota cancelada",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "422",
          description = "Dados da nota invalidos para emissao ou falha na validacao XSD",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<NotaFiscalResponse> gerarXml(@PathVariable UUID id) {
    return ResponseEntity.ok(notaFiscalService.gerarXml(id));
  }

  /**
   * Retorna o XML da NF-e gerado e armazenado para a nota indicada.
   *
   * <p>Retorna 404 se o XML ainda nao foi gerado (nota ainda em RASCUNHO).
   * O Content-Type da resposta e {@code application/xml}.
   */
  @GetMapping(value = "/{id}/xml", produces = MediaType.APPLICATION_XML_VALUE)
  @Operation(
      summary = "Obtem o XML da NF-e",
      description = "Retorna o XML da NF-e gerado e armazenado para a nota. "
          + "Retorna 404 se a nota nao existir, nao pertencer ao produtor autenticado "
          + "ou se o XML ainda nao tiver sido gerado (use POST /{id}/gerar-xml primeiro).")
  @ApiResponses({
      @ApiResponse(responseCode = "200",
          description = "XML retornado com sucesso",
          content = @Content(mediaType = MediaType.APPLICATION_XML_VALUE)),
      @ApiResponse(responseCode = "404",
          description = "Nota nao encontrada ou XML ainda nao gerado",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<String> obterXml(@PathVariable UUID id) {
    String xml = notaFiscalService.obterXml(id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_XML)
        .body(xml);
  }

  /**
   * Gera e retorna o PDF da DANFE da nota indicada (issue #14 — RF011).
   *
   * <p>Disponível apenas para notas com XML gerado (status XML_GERADO em diante);
   * retorna 409 caso contrário e 404 para nota inexistente ou de outro produtor.
   */
  @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  @Operation(
      summary = "Baixa a DANFE (PDF) da NF-e",
      description = "Gera e retorna o PDF da DANFE (Documento Auxiliar da NF-e) com os dados "
          + "da nota. Disponivel somente apos a geracao do XML; retorna 409 caso a nota "
          + "ainda esteja em RASCUNHO e 404 se nao existir ou nao pertencer ao produtor.")
  @ApiResponses({
      @ApiResponse(responseCode = "200",
          description = "PDF da DANFE retornado com sucesso",
          content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
      @ApiResponse(responseCode = "404",
          description = "Nota nao encontrada ou nao pertence ao produtor",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409",
          description = "DANFE indisponivel — XML ainda nao gerado",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<byte[]> baixarPdf(@PathVariable UUID id) {
    byte[] pdf = notaFiscalService.gerarPdf(id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"danfe-" + id + ".pdf\"")
        .body(pdf);
  }

  /**
   * Transmite a NF-e indicada à SEFAZ (ambiente de homologação) e registra o retorno.
   *
   * <p>Somente notas em {@code XML_GERADO} (XML gerado e assinado) podem ser enviadas;
   * caso contrário, retorna 409. O status da nota transita conforme o retorno do fisco
   * (AUTORIZADA, REJEITADA ou PENDENTE_SEFAZ). Na Sprint 3 o transporte é mock/sandbox.
   */
  @PostMapping("/{id}/enviar-sefaz")
  @Operation(
      summary = "Envia a NF-e para a SEFAZ",
      description = "Transmite o XML assinado da NF-e ao ambiente de homologacao da SEFAZ "
          + "e persiste o retorno (status, cStat, motivo, protocolo). "
          + "Somente notas em status XML_GERADO podem ser enviadas; retorna 409 caso contrario. "
          + "Na Sprint 3 o transporte e mock/sandbox (envio real e debito da Sprint 7).")
  @ApiResponses({
      @ApiResponse(responseCode = "200",
          description = "Nota enviada; retorno da SEFAZ registrado"),
      @ApiResponse(responseCode = "404",
          description = "Nota nao encontrada ou nao pertence ao produtor",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409",
          description = "Nota nao esta em status XML_GERADO",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "502",
          description = "Falha de comunicacao com a SEFAZ apos esgotar as tentativas",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<SefazEnvioResponse> enviarSefaz(@PathVariable UUID id) {
    return ResponseEntity.ok(sefazService.enviar(id));
  }

  /**
   * Consulta o status da NF-e na SEFAZ e sincroniza o status local (issue #16 — RF013).
   *
   * <p>Só é permitido para notas já transmitidas (com chave de acesso); caso contrário,
   * retorna 409. O status local é atualizado conforme o retorno do fisco.
   */
  @GetMapping("/{id}/status-sefaz")
  @Operation(
      summary = "Consulta o status da NF-e na SEFAZ",
      description = "Consulta a situacao da NF-e na SEFAZ pela chave de acesso e sincroniza "
          + "o status local. Somente notas ja transmitidas podem ser consultadas; "
          + "retorna 409 caso contrario. Na Sprint 3 o transporte e mock/sandbox.")
  @ApiResponses({
      @ApiResponse(responseCode = "200",
          description = "Status consultado e sincronizado"),
      @ApiResponse(responseCode = "404",
          description = "Nota nao encontrada ou nao pertence ao produtor",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409",
          description = "Nota ainda nao foi transmitida (sem chave de acesso)",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "502",
          description = "Falha de comunicacao com a SEFAZ apos esgotar as tentativas",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<SefazStatusResponse> consultarStatusSefaz(@PathVariable UUID id) {
    return ResponseEntity.ok(sefazService.sincronizarStatus(id));
  }

  /**
   * Lista o histórico de inconsistências de validação da nota indicada (issue #22 — RF014/C).
   *
   * <p>Retorna as inconsistências registradas nas tentativas de geração do XML, da mais
   * recente para a mais antiga, agrupáveis por {@code loteValidacao}. Respeita o ownership
   * anti-IDOR (404 para nota inexistente ou de outro produtor). Retorna lista vazia se a nota
   * nunca apresentou inconsistências.
   */
  @GetMapping("/{id}/validacoes")
  @Operation(
      summary = "Lista o historico de inconsistencias de validacao da NF-e",
      description = "Retorna as inconsistencias registradas ao validar/gerar o XML da nota, "
          + "ordenadas da tentativa mais recente para a mais antiga e agrupaveis por "
          + "loteValidacao. Retorna 404 se a nota nao existir ou nao pertencer ao produtor "
          + "autenticado (anti-IDOR); lista vazia se nunca houve inconsistencias.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Historico de validacoes retornado"),
      @ApiResponse(responseCode = "404",
          description = "Nota nao encontrada ou nao pertence ao produtor",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<List<NotaFiscalValidacaoResponse>> listarValidacoes(
      @PathVariable UUID id) {
    return ResponseEntity.ok(validacaoService.listarValidacoes(id));
  }
}
