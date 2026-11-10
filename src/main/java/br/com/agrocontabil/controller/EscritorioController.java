package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.AceiteConviteEquipeRequest;
import br.com.agrocontabil.dto.AlterarFuncaoRequest;
import br.com.agrocontabil.dto.CadastroEquipeRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.dto.ConviteMembroRequest;
import br.com.agrocontabil.dto.ConviteMembroResponse;
import br.com.agrocontabil.dto.EnderecoEscritorioRequest;
import br.com.agrocontabil.dto.EscritorioRequest;
import br.com.agrocontabil.dto.EscritorioResponse;
import br.com.agrocontabil.dto.MembroResponse;
import br.com.agrocontabil.security.RotasAutenticacao;
import br.com.agrocontabil.service.EnderecoEscritorioService;
import br.com.agrocontabil.service.EquipeEscritorioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Escritório e equipe (issue #228). Só contador; gerenciar a equipe e o escritório é do
 * {@code ADMINISTRADOR} (outras funções: 403 {@code PAPEL_NAO_AUTORIZADO}); membro de outro
 * escritório é 404.
 */
@RestController
@Tag(name = "Escritorio e equipe",
    description = "Membros, funcoes e convites de equipe do escritorio (issue #228)")
public class EscritorioController {

  private final EquipeEscritorioService equipeService;
  private final EnderecoEscritorioService enderecoService;

  public EscritorioController(EquipeEscritorioService equipeService,
      EnderecoEscritorioService enderecoService) {
    this.equipeService = equipeService;
    this.enderecoService = enderecoService;
  }

  /** Escritório de quem pergunta. */
  @GetMapping("/api/escritorio")
  @Operation(summary = "Escritorio do contador autenticado, com a funcao dele")
  public EscritorioResponse escritorio() {
    return equipeService.escritorio();
  }

  /** Edita nome e CNPJ. */
  @PatchMapping("/api/escritorio")
  @Operation(summary = "Edita nome e CNPJ do escritorio (administrador)",
      description = "Com endereco proprio, apagar o CNPJ responde 422 ENDERECO_EXIGE_CNPJ.")
  @ApiResponses({
      @ApiResponse(responseCode = "422", description = "ENDERECO_EXIGE_CNPJ",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public EscritorioResponse atualizar(@Valid @RequestBody EscritorioRequest request) {
    return equipeService.atualizar(request);
  }

  /** Define ou troca o endereço próprio do escritório (issue #464). */
  @PutMapping("/api/escritorio/endereco")
  @Operation(summary = "Define ou troca o endereco proprio do escritorio (administrador)",
      description = "Endereco {slug}.agroconia.com.br. Exige CNPJ e a ciencia de que o endereco "
          + "fica publico e permanente. Uma gravacao a cada 30 dias; o slug que sai fica 90 dias "
          + "em carencia. Reenviar o slug atual nao muda nada.")
  @ApiResponses({
      @ApiResponse(responseCode = "400", description = "SLUG_INVALIDO ou VALIDATION_ERROR",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409",
          description = "SLUG_INDISPONIVEL ou ENDERECO_TROCA_RECENTE",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "422", description = "ENDERECO_EXIGE_CNPJ",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public EscritorioResponse definirEndereco(
      @Valid @RequestBody EnderecoEscritorioRequest request) {
    return enderecoService.definir(request).escritorio();
  }

  /** Remove o endereço próprio do escritório (issue #464). */
  @DeleteMapping("/api/escritorio/endereco")
  @Operation(summary = "Remove o endereco proprio do escritorio (administrador)",
      description = "Sempre permitido, mesmo antes de 30 dias da ultima troca. O slug fica 90 dias "
          + "em carencia. Sem endereco, tambem 204.")
  public ResponseEntity<Void> removerEndereco() {
    enderecoService.remover();
    return ResponseEntity.noContent().build();
  }

  /** Membros da equipe. */
  @GetMapping("/api/escritorio/membros")
  @Operation(summary = "Membros da equipe (qualquer membro)")
  public ResponseEntity<List<MembroResponse>> membros() {
    List<MembroResponse> membros = equipeService.membros();
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(membros.size()))
        .body(membros);
  }

  /** Troca a função de um membro. */
  @PatchMapping("/api/escritorio/membros/{membroId}")
  @Operation(summary = "Troca a funcao de um membro (administrador)")
  @ApiResponses({
      @ApiResponse(responseCode = "409", description = "ULTIMO_ADMINISTRADOR",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public MembroResponse alterarFuncao(@PathVariable UUID membroId,
      @Valid @RequestBody AlterarFuncaoRequest request) {
    return equipeService.alterarFuncao(membroId, request.funcao());
  }

  /** Remove um membro. */
  @DeleteMapping("/api/escritorio/membros/{membroId}")
  @Operation(summary = "Remove um membro; os clientes dele ficam sem responsavel (administrador)")
  public ResponseEntity<Void> remover(@PathVariable UUID membroId) {
    equipeService.remover(membroId);
    return ResponseEntity.noContent().build();
  }

  /** Convida para a equipe. */
  @PostMapping("/api/escritorio/membros/convites")
  @Operation(summary = "Convida um e-mail para a equipe (administrador)",
      description = "Sempre 202, exista ou nao conta com o e-mail. Link com token de uso unico "
          + "no fragmento, valido por 7 dias.")
  public ResponseEntity<Void> convidar(@Valid @RequestBody ConviteMembroRequest request) {
    equipeService.convidar(request.email(), request.funcao());
    return ResponseEntity.accepted().build();
  }

  /** Convites de equipe pendentes. */
  @GetMapping("/api/escritorio/membros/convites")
  @Operation(summary = "Convites de equipe pendentes (administrador)")
  public ResponseEntity<List<ConviteMembroResponse>> convites() {
    List<ConviteMembroResponse> convites = equipeService.convitesPendentes();
    return ResponseEntity.ok()
        .header(GuiaFiscalController.HEADER_TOTAL, String.valueOf(convites.size()))
        .body(convites);
  }

  /** Aceita o convite com a conta de contador já existente. */
  @PostMapping("/api/escritorio/membros/convites/aceitar")
  @Operation(summary = "Aceita o convite de equipe com a conta logada (mesmo e-mail)")
  @ApiResponses({
      @ApiResponse(responseCode = "400", description = "CONVITE_EQUIPE_INVALIDO",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "ESCRITORIO_ATUAL_EM_USO",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public MembroResponse aceitar(@Valid @RequestBody AceiteConviteEquipeRequest request) {
    return equipeService.aceitar(request.token());
  }

  /** Cadastro pelo link do convite (público). */
  @PostMapping(RotasAutenticacao.CADASTRO_EQUIPE)
  @Operation(summary = "Cria a conta de contador ja na equipe, pelo link do convite",
      description = "Publico. O e-mail e o do convite. Mesma politica de senha do cadastro.")
  @ApiResponses({
      @ApiResponse(responseCode = "400", description = "CONVITE_EQUIPE_INVALIDO ou SENHA_FRACA",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "CADASTRO_CONFLITO",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<CadastroResponse> cadastrar(
      @Valid @RequestBody CadastroEquipeRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(equipeService.cadastrarPorConvite(request));
  }
}
