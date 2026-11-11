package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import br.com.agrocontabil.exception.AceiteTermosObrigatorioException;
import br.com.agrocontabil.validation.CpfDoContador;
import br.com.agrocontabil.validation.ValidCpfCnpj;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cadastro de contador pelo link do convite de equipe (issue #228). O e-mail é o do convite: não
 * vem no request. CRC, telefone e o aceite dos termos seguem as regras do cadastro (#519).
 *
 * @param token        token do fragmento do link
 * @param nomeCompleto nome
 * @param cpfCnpj      CPF da pessoa, só dígitos (desde a #519, CNPJ é recusado: o do escritório
 *                     fica nos dados do escritório)
 * @param senha        senha (mesma política do cadastro)
 * @param crc          registro no CRC, opcional, sem formato fixo; vazio = não informado
 * @param telefone     telefone da pessoa, opcional: só dígitos (10 ou 11); vazio = não informado
 * @param aceiteTermos aceite dos termos de uso; {@code false} é recusado, nulo = não registrado
 *                     (recusado com {@code app.cadastro.exigir-aceite-termos})
 */
@Schema(description = "Cadastro de contador pelo link do convite de equipe; o e-mail é o do convite")
public record CadastroEquipeRequest(
    @NotBlank @Size(max = 100) @Sensivel String token,
    @NotBlank @Size(max = 255) String nomeCompleto,
    @NotBlank
    @Pattern(regexp = "\\d{11}|\\d{14}", message = "Informe o CPF (11) ou CNPJ (14) só com dígitos")
    @ValidCpfCnpj
    @CpfDoContador
    @Schema(description = "CPF da pessoa, só dígitos", example = "52998224725")
    String cpfCnpj,
    @NotNull @Sensivel
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
    String senha,
    @Size(max = 20, message = "O CRC tem no máximo 20 caracteres.")
    @Schema(description = "Registro no CRC, sem formato fixo; vazio = não informado",
        example = "PR-012345/O")
    String crc,
    @Pattern(regexp = "\\d{10,11}|",
        message = "Informe o telefone só com dígitos: DDD e número (10 ou 11 dígitos).")
    @Schema(description = "DDD e número, só dígitos (10 ou 11); vazio = não informado",
        example = "45999990000")
    String telefone,
    @AssertTrue(message = AceiteTermosObrigatorioException.MENSAGEM)
    @Schema(description = "Aceite dos termos de uso: true grava a data e a versão; false é 400;"
        + " sem o campo, o aceite fica não registrado (400 quando o aceite for exigido)",
        example = "true")
    Boolean aceiteTermos) {

  /**
   * Cadastro pelo convite sem os campos da #519 (CRC, telefone e aceite dos termos).
   *
   * @param token        token do fragmento do link
   * @param nomeCompleto nome
   * @param cpfCnpj      CPF da pessoa, só dígitos
   * @param senha        senha
   */
  public CadastroEquipeRequest(String token, String nomeCompleto, String cpfCnpj, String senha) {
    this(token, nomeCompleto, cpfCnpj, senha, null, null, null);
  }

  @Override
  public String toString() {
    return "CadastroEquipeRequest[***]";
  }
}
