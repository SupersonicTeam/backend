package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.SefazEnvioResponse;
import br.com.agrocontabil.service.NotaFiscalService;
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

  public NotaFiscalController(
      NotaFiscalService notaFiscalService, SefazService sefazService) {
    this.notaFiscalService = notaFiscalService;
    this.sefazService = sefazService;
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
      description = "Somente notas em status RASCUNHO podem ser atualizadas. "
          + "Retorna 409 se o status nao for RASCUNHO.")
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
      description = "Somente notas em status RASCUNHO podem ser excluidas. "
          + "Retorna 409 se o status nao for RASCUNHO.")
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
   * <p>Idempotencia: somente notas em RASCUNHO podem ter o XML gerado. Chamadas
   * subsequentes com status XML_GERADO retornam 409 — use GET /{id}/xml para
   * obter o XML ja gerado.
   */
  @PostMapping("/{id}/gerar-xml")
  @Operation(
      summary = "Gera o XML da NF-e",
      description = "Gera, valida (XSD) e persiste o XML da NF-e. "
          + "Transiciona o status de RASCUNHO para XML_GERADO. "
          + "Preenche chaveAcesso e dataGeracaoXml na entidade. "
          + "Somente notas em RASCUNHO podem ser processadas; "
          + "retorna 409 se o status nao for RASCUNHO.")
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
}
