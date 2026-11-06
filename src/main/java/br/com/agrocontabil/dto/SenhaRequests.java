package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Requests da recuperação e troca de senha (issue #209). As senhas novas passam pela
 * {@code PoliticaSenha} no service, que não ecoa o valor; aqui só presença e teto de tamanho.
 */
@Schema(description = "Requests da recuperação e da troca de senha")
public final class SenhaRequests {

  private SenhaRequests() {
  }

  /**
   * Pedido de link de redefinição.
   *
   * <p>Sem {@code @Email}: um e-mail malformado recebe o mesmo 202 de qualquer outro.
   *
   * @param email e-mail da conta
   */
  @Schema(description = "Pedido de link para redefinir a senha")
  public record Esqueci(@NotBlank @Size(max = 254) String email) {

    /** Sem o e-mail (LGPD). */
    @Override
    public String toString() {
      return "Esqueci[]";
    }
  }

  /**
   * Senha nova com o token do link.
   *
   * @param token     token do fragmento do link
   * @param novaSenha senha nova
   */
  @Schema(description = "Nova senha com o token do link de redefinição")
  public record Redefinir(
      @NotBlank @Size(max = 100) @Sensivel String token,
      @NotNull @Size(max = 1024) @Sensivel
      @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String novaSenha) {

    /** Sem token nem senha. */
    @Override
    public String toString() {
      return "Redefinir[]";
    }
  }

  /**
   * Troca de senha de quem está autenticado.
   *
   * @param senhaAtual senha em uso
   * @param novaSenha  senha nova
   */
  @Schema(description = "Troca de senha de quem já está autenticado")
  public record Alterar(
      @NotNull @Size(max = 1024) @Sensivel
      @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String senhaAtual,
      @NotNull @Size(max = 1024) @Sensivel
      @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String novaSenha) {

    /** Sem as senhas. */
    @Override
    public String toString() {
      return "Alterar[]";
    }
  }
}
