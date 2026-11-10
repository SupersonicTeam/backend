package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.ConviteEnviadoResponse;
import br.com.agrocontabil.dto.ConviteRequest;
import br.com.agrocontabil.dto.EncerramentoRequest;
import br.com.agrocontabil.dto.EtiquetaResponse;
import br.com.agrocontabil.dto.EtiquetasClienteRequest;
import br.com.agrocontabil.dto.FichaClienteRequest;
import br.com.agrocontabil.dto.FichaClienteResponse;
import br.com.agrocontabil.dto.FiltroCarteiraRequest;
import br.com.agrocontabil.dto.ProdutorCarteiraResponse;
import br.com.agrocontabil.dto.ResponsavelClienteRequest;
import br.com.agrocontabil.dto.ResponsavelClienteResponse;
import br.com.agrocontabil.dto.SituacaoVinculoResponse;
import br.com.agrocontabil.service.CarteiraService;
import br.com.agrocontabil.service.ConviteCarteiraService;
import br.com.agrocontabil.service.FichaClienteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Carteira do escritório (issue #177): lista de clientes, ficha, etiquetas, convites e ciclo do
 * vínculo. Tudo só para contador (produtor 403); produtor fora da carteira ativa é 404.
 *
 * <p>Fixa o padrão {@code /api/contador/produtores/{produtorId}/...} das rotas em que o contador
 * age sobre um cliente.
 */
@RestController
@RequestMapping("/api/contador")
@Tag(name = "Carteira do contador",
    description = "Clientes do escritorio, ficha, etiquetas e convites (issue #177)")
public class CarteiraController {

  private final CarteiraService carteiraService;
  private final FichaClienteService fichaService;
  private final ConviteCarteiraService conviteService;

  public CarteiraController(CarteiraService carteiraService, FichaClienteService fichaService,
      ConviteCarteiraService conviteService) {
    this.carteiraService = carteiraService;
    this.fichaService = fichaService;
    this.conviteService = conviteService;
  }

  /** Lista a carteira com os indicadores de atenção. */
  @GetMapping("/produtores")
  @Operation(summary = "Lista os clientes do escritorio",
      description = "Corpo em array e total em X-Total-Count. Busca por trecho do nome (sem "
          + "acento nem caixa) ou CPF/CNPJ completo; filtros regime, tipoProdutor, uf, etiqueta "
          + "(repetivel), situacao (padrao ATIVO) e precisaAtencao; sort nome (padrao) ou "
          + "urgencia; responsavel (eu, nenhum ou id do membro); page/size (padrao 50, maximo "
          + "200). atencao so em vinculo ATIVO.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Clientes",
          headers = @Header(name = GuiaFiscalController.HEADER_TOTAL,
              description = "Total de clientes no filtro")),
      @ApiResponse(responseCode = "400",
          description = "FILTRO_CARTEIRA_INVALIDO ou VALIDATION_ERROR",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "403", description = "Produtor (PAPEL_NAO_AUTORIZADO)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<List<ProdutorCarteiraResponse>> listar(
      @Valid @ParameterObject FiltroCarteiraRequest filtro) {
    Page<ProdutorCarteiraResponse> pagina = carteiraService.listar(filtro);
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(pagina.getTotalElements()))
        .body(pagina.getContent());
  }

  /** Ficha do cliente. */
  @GetMapping("/produtores/{produtorId}/ficha")
  @Operation(summary = "Ficha do cliente (observacoes internas e etiquetas)")
  public FichaClienteResponse ficha(@PathVariable UUID produtorId) {
    return fichaService.ficha(produtorId);
  }

  /** Edita as observações internas do cliente. */
  @PatchMapping("/produtores/{produtorId}/ficha")
  @Operation(summary = "Edita as observacoes internas do cliente (ate 1000 caracteres)")
  public FichaClienteResponse atualizarFicha(@PathVariable UUID produtorId,
      @Valid @RequestBody FichaClienteRequest request) {
    return fichaService.atualizarObservacoes(produtorId, request.observacoes());
  }

  /** Substitui as etiquetas do cliente. */
  @PutMapping("/produtores/{produtorId}/etiquetas")
  @Operation(summary = "Substitui as etiquetas do cliente (ate 10; nome novo entra no catalogo)")
  public FichaClienteResponse definirEtiquetas(@PathVariable UUID produtorId,
      @Valid @RequestBody EtiquetasClienteRequest request) {
    return fichaService.definirEtiquetas(produtorId, request.etiquetas());
  }

  /** Troca o responsável pelo cliente. */
  @PatchMapping("/produtores/{produtorId}/responsavel")
  @Operation(summary = "Troca o responsavel pelo cliente (administrador ou o proprio responsavel)",
      description = "responsavelId nulo deixa o cliente sem responsavel. Membro de outro "
          + "escritorio: 404.")
  public ResponsavelClienteResponse alterarResponsavel(@PathVariable UUID produtorId,
      @RequestBody ResponsavelClienteRequest request) {
    return carteiraService.alterarResponsavel(produtorId, request.responsavelId());
  }

  /** Suspende o cliente. */
  @PostMapping("/produtores/{produtorId}/suspender")
  @Operation(summary = "Suspende o cliente: sai do alcance, a ficha fica")
  public SituacaoVinculoResponse suspender(@PathVariable UUID produtorId) {
    return carteiraService.suspender(produtorId);
  }

  /** Reativa o cliente suspenso. */
  @PostMapping("/produtores/{produtorId}/reativar")
  @Operation(summary = "Reativa um cliente suspenso, sem novo convite")
  public SituacaoVinculoResponse reativar(@PathVariable UUID produtorId) {
    return carteiraService.reativar(produtorId);
  }

  /** Encerra o cliente. */
  @PostMapping("/produtores/{produtorId}/encerrar")
  @Operation(summary = "Encerra o vinculo; voltar exige novo convite",
      description = "Corpo opcional {motivo} (ate 500 caracteres, texto livre). Sem corpo ou com "
          + "motivo em branco encerra sem motivo. O motivo nunca vai a log nem a auditoria.")
  public SituacaoVinculoResponse encerrar(@PathVariable UUID produtorId,
      @RequestBody(required = false) @Valid EncerramentoRequest request) {
    return carteiraService.encerrar(produtorId, EncerramentoRequest.motivoDe(request));
  }

  /** Catálogo de etiquetas do escritório. */
  @GetMapping("/etiquetas")
  @Operation(summary = "Catalogo de etiquetas do escritorio")
  public ResponseEntity<List<EtiquetaResponse>> etiquetas() {
    List<EtiquetaResponse> etiquetas = fichaService.catalogo();
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(etiquetas.size()))
        .body(etiquetas);
  }

  /** Apaga uma etiqueta do catálogo. */
  @DeleteMapping("/etiquetas/{etiquetaId}")
  @Operation(summary = "Apaga a etiqueta do catalogo e de todos os clientes")
  public ResponseEntity<Void> excluirEtiqueta(@PathVariable UUID etiquetaId) {
    fichaService.excluirEtiqueta(etiquetaId);
    return ResponseEntity.noContent().build();
  }

  /** Convida um produtor por e-mail. */
  @PostMapping("/convites")
  @Operation(summary = "Convida um produtor por e-mail",
      description = "Sempre 202, exista ou nao conta com o e-mail. O vinculo so nasce do aceite "
          + "do produtor. Reenviar substitui o convite pendente anterior.")
  @ApiResponses({
      @ApiResponse(responseCode = "202", description = "Convite registrado"),
      @ApiResponse(responseCode = "429", description = "LIMITE_CONVITES (header Retry-After)",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<Void> convidar(@Valid @RequestBody ConviteRequest request) {
    conviteService.convidar(request.email());
    return ResponseEntity.accepted().build();
  }

  /** Convites pendentes do escritório. */
  @GetMapping("/convites")
  @Operation(summary = "Convites pendentes do escritorio")
  public ResponseEntity<List<ConviteEnviadoResponse>> convites() {
    List<ConviteEnviadoResponse> convites = conviteService.listarEnviados();
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(convites.size()))
        .body(convites);
  }
}
