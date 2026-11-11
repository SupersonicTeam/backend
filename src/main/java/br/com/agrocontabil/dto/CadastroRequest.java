package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.validation.CadastroCoerenteComPapel;
import br.com.agrocontabil.validation.ValidCpfCnpj;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Pedido de cadastro de um produtor ou contador com credencial de acesso (issues #205 e #519).
 *
 * <p>A senha só é checada aqui quanto à presença. As regras de força ficam na
 * {@code PoliticaSenha}, que responde sem ecoar o valor; uma anotação {@code @Size} aqui faria o
 * erro de validação devolver a senha no {@code rejectedValue}.
 *
 * <p>Os campos do contador (#519) são opcionais. {@code escritorio} e {@code crc} só valem no papel
 * {@code CONTADOR}, e o documento do contador é só o CPF da pessoa
 * ({@link CadastroCoerenteComPapel}). Sem {@code escritorio}, o contador ganha o escritório
 * unipessoal como antes: com o nome dele e sem CNPJ. Em {@code crc}, {@code telefone} e
 * {@code escritorio.cnpj}, vazio é o mesmo que não informado.
 *
 * @param papel        PRODUTOR ou CONTADOR
 * @param nomeCompleto nome do produtor (pessoa ou empresa) ou da pessoa do contador
 * @param cpfCnpj      só dígitos, com dígito verificador válido, obrigatório: CPF (11) ou CNPJ (14)
 *                     no produtor; só CPF no contador (#519)
 * @param email        e-mail de login
 * @param senha        senha escolhida
 * @param escritorio   nome e CNPJ do escritório do contador; nulo = unipessoal com o nome dele
 * @param crc          registro no Conselho Regional de Contabilidade, sem formato fixo; só contador
 * @param telefone     DDD e número, só dígitos (10 ou 11), nos dois papéis
 */
@CadastroCoerenteComPapel
@Schema(description = "Cadastro de produtor ou contador com credencial de acesso")
public record CadastroRequest(
    @NotNull
    @Schema(description = "Papel da conta", example = "PRODUTOR")
    PapelUsuario papel,

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Nome do produtor ou da pessoa do contador",
        example = "Fazenda Boa Vista")
    String nomeCompleto,

    @NotBlank
    @Pattern(regexp = "\\d{11}|\\d{14}", message = "Informe o CPF (11) ou CNPJ (14) só com dígitos")
    @ValidCpfCnpj
    @Schema(description = "Só dígitos. Produtor: CPF ou CNPJ. Contador: só o CPF da pessoa; o CNPJ"
        + " do escritório vai em 'escritorio.cnpj'", example = "52998224725")
    String cpfCnpj,

    @NotBlank
    @Email
    @Size(max = 254)
    @Schema(example = "produtor@exemplo.com.br")
    String email,

    @NotNull
    @Sensivel
    @Schema(description = "Minimo de 10 caracteres; no maximo 72 bytes", format = "password",
        accessMode = Schema.AccessMode.WRITE_ONLY)
    String senha,

    // @Sensivel: recusado no produtor, o objeto inteiro não volta no rejectedValue (só o
    // toString mascarado voltaria); os campos aninhados seguem com o próprio rejectedValue.
    @Valid
    @Sensivel
    @Schema(description = "Só no papel CONTADOR (no PRODUTOR, 400). Sem ele, o escritório nasce com"
        + " o nome do contador e sem CNPJ")
    EscritorioDoCadastro escritorio,

    @Size(max = 20, message = "O CRC tem no máximo 20 caracteres.")
    @Schema(description = "Registro no CRC, sem formato fixo. Só no papel CONTADOR (no PRODUTOR,"
        + " 400); vazio = não informado", example = "PR-012345/O")
    String crc,

    @Pattern(regexp = "\\d{10,11}|",
        message = "Informe o telefone só com dígitos: DDD e número (10 ou 11 dígitos).")
    @Schema(description = "DDD e número, só dígitos (10 ou 11); vazio = não informado",
        example = "45999990000")
    String telefone) {

  /**
   * Cadastro sem os campos da #519: o contador ganha o escritório unipessoal com o nome dele, e
   * nenhum dos dois papéis grava CRC ou telefone. É o que o cadastro pelo convite da equipe usa.
   *
   * @param papel        PRODUTOR ou CONTADOR
   * @param nomeCompleto nome do produtor ou da pessoa do contador
   * @param cpfCnpj      CPF ou CNPJ, só dígitos
   * @param email        e-mail de login
   * @param senha        senha escolhida
   */
  public CadastroRequest(PapelUsuario papel, String nomeCompleto, String cpfCnpj, String email,
      String senha) {
    this(papel, nomeCompleto, cpfCnpj, email, senha, null, null, null);
  }

  /**
   * Sem senha, e-mail, documento, CNPJ, CRC nem telefone: o {@code toString} padrão do record os
   * poria em qualquer log do request (LGPD).
   */
  @Override
  public String toString() {
    return "CadastroRequest[papel=" + papel + "]";
  }

  /**
   * Escritório do contador informado no cadastro (issue #519). Vira o escritório unipessoal de que
   * ele é administrador.
   *
   * @param nome nome do escritório (o autônomo pode usar o próprio nome)
   * @param cnpj CNPJ só com dígitos; vazio ou nulo = sem CNPJ (o endereço próprio, #464, exige)
   */
  @Schema(description = "Escritório do contador no cadastro")
  public record EscritorioDoCadastro(
      @NotBlank(message = "Informe o nome do escritório.")
      @Size(max = 255, message = "O nome do escritório tem no máximo 255 caracteres.")
      // Exemplos diferentes do escritório da demonstração: o reset da banca o acha por nome e CNPJ
      // (docs/demonstracao/reset-*.sql), e o CNPJ não é único (#520).
      @Schema(description = "Nome do escritório; o autônomo pode usar o próprio nome",
          example = "Contabilidade Campo Verde")
      String nome,

      @Pattern(regexp = "\\d{14}|",
          message = "Informe o CNPJ do escritório com 14 dígitos (ou deixe vazio).")
      @ValidCpfCnpj
      @Schema(description = "CNPJ só com dígitos, opcional; necessário para o endereço próprio"
          + " do escritório. Vazio = sem CNPJ", example = "11444777000161")
      String cnpj) {

    /** Sem o nome nem o CNPJ: o nome do unipessoal costuma ser o da pessoa (LGPD). */
    @Override
    public String toString() {
      return "EscritorioDoCadastro[***]";
    }
  }
}
