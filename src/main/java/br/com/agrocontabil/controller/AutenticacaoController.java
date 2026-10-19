package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.service.CadastroService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cadastro e autenticação (Sprint 8). O controller só delega; as regras ficam nos services. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticacao", description = "Cadastro de conta e credenciais de acesso")
public class AutenticacaoController {

  private final CadastroService cadastroService;

  public AutenticacaoController(CadastroService cadastroService) {
    this.cadastroService = cadastroService;
  }

  /**
   * Cria a conta de um produtor ou contador com e-mail e senha (issue #205).
   *
   * @param request dados do cadastro
   * @return 201 com o id da identidade criada
   */
  @PostMapping("/cadastro")
  @SecurityRequirements
  @Operation(summary = "Cria uma conta de produtor ou contador",
      description = "Publico. CPF/CNPJ obrigatorio (so digitos, com digito verificador). Senha de "
          + "10 caracteres a 72 bytes, fora da lista de senhas comuns e sem conter o e-mail ou o "
          + "documento. E-mail ou documento ja cadastrados respondem 409 sem dizer qual.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Conta criada"),
      @ApiResponse(responseCode = "400", description = "Dados invalidos ou senha fraca",
          content = @Content(schema = @Schema(implementation = ApiError.class))),
      @ApiResponse(responseCode = "409", description = "E-mail ou CPF/CNPJ ja cadastrado",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<CadastroResponse> cadastrar(@Valid @RequestBody CadastroRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(cadastroService.cadastrar(request));
  }
}
