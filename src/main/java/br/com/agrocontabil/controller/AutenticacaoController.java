package br.com.agrocontabil.controller;

import br.com.agrocontabil.common.ApiError;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.dto.LoginRequest;
import br.com.agrocontabil.dto.LoginResponse;
import br.com.agrocontabil.dto.UsuarioLogadoResponse;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.LoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro e autenticação (Sprint 8). O controller só delega; as regras ficam nos services.
 *
 * <p>Só com servidor web, como o {@code LoginService} e o {@code JwtService}: os comandos sem web
 * (ingestão da Ceres, avaliação) não atendem requisição nem emitem token.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/auth")
@Tag(name = "Autenticacao", description = "Cadastro de conta, login e identidade autenticada")
public class AutenticacaoController {

  private final CadastroService cadastroService;
  private final LoginService loginService;

  public AutenticacaoController(CadastroService cadastroService, LoginService loginService) {
    this.cadastroService = cadastroService;
    this.loginService = loginService;
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

  /**
   * Troca e-mail e senha pelo token de acesso (issue #206).
   *
   * <p>{@code Cache-Control: no-store} (RFC 6749 §5.1): resposta com credencial não pode ficar em
   * cache de navegador nem de proxy.
   *
   * @param request e-mail e senha
   * @return token de acesso
   */
  @PostMapping("/login")
  @SecurityRequirements
  @Operation(summary = "Login por e-mail e senha",
      description = "Publico. Devolve o JWT de acesso (validade de 15 minutos) a enviar em "
          + "Authorization: Bearer. E-mail sem conta, senha errada e conta desativada respondem o "
          + "mesmo 401 CREDENCIAIS_INVALIDAS.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Token emitido"),
      @ApiResponse(responseCode = "401", description = "E-mail ou senha incorretos",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<LoginResponse> entrar(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(loginService.entrar(request));
  }

  /**
   * Quem está autenticado nesta requisição (issue #206).
   *
   * @return id, papel e nome
   */
  @GetMapping("/me")
  @Operation(summary = "Identidade autenticada",
      description = "Id, papel e nome de quem fez a requisicao. Util para o frontend conferir o "
          + "token na abertura da aplicacao.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Identidade da requisicao"),
      @ApiResponse(responseCode = "401", description = "Sem identidade valida",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<UsuarioLogadoResponse> usuarioLogado() {
    return ResponseEntity.ok(loginService.usuarioLogado());
  }
}
