package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import br.com.agrocontabil.exception.AceiteTermosObrigatorioException;
import br.com.agrocontabil.model.TipoEscritorio;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.validation.CadastroCoerenteComPapel;
import br.com.agrocontabil.validation.SlugEscritorio;
import br.com.agrocontabil.validation.ValidCpfCnpj;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
 * <p><strong>Contador (#519):</strong> o documento é só o CPF da pessoa, e o {@code tipoConta}
 * escolhe o escritório. {@code PESSOAL} (ou sem o campo, como a tela antiga) cria o escritório com
 * o nome da pessoa e não leva {@code escritorio}; {@code EMPRESARIAL} exige o {@code escritorio}
 * completo. {@code escritorio}, {@code crc} e {@code tipoConta} são recusados no produtor. As regras
 * que cruzam campos ficam no {@link CadastroCoerenteComPapel}. Em {@code crc} e {@code telefone},
 * vazio é o mesmo que não informado.
 *
 * @param papel        PRODUTOR ou CONTADOR
 * @param nomeCompleto nome do produtor (pessoa ou empresa) ou da pessoa do contador
 * @param cpfCnpj      só dígitos, com dígito verificador válido, obrigatório: CPF (11) ou CNPJ (14)
 *                     no produtor; só CPF no contador (#519)
 * @param email        e-mail de login
 * @param senha        senha escolhida
 * @param escritorio   escritório do contador empresarial; proibido no pessoal e no produtor
 * @param crc          registro no Conselho Regional de Contabilidade, sem formato fixo; só contador
 * @param telefone     telefone da pessoa: DDD e número, só dígitos (10 ou 11), nos dois papéis
 * @param tipoConta    {@code PESSOAL} ou {@code EMPRESARIAL}, só no contador; nulo = pessoal
 * @param aceiteTermos aceite dos termos de uso; {@code false} é recusado, nulo = não registrado
 *                     (recusado com {@code app.cadastro.exigir-aceite-termos})
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

    // @Sensivel: recusado no produtor ou no pessoal, o objeto inteiro não volta no rejectedValue
    // (só o toString mascarado voltaria); os campos aninhados seguem com o próprio rejectedValue.
    @Valid
    @Sensivel
    @Schema(description = "Obrigatório no CONTADOR EMPRESARIAL; proibido no PESSOAL e no PRODUTOR"
        + " (400)")
    EscritorioDoCadastro escritorio,

    @Size(max = 20, message = "O CRC tem no máximo 20 caracteres.")
    @Schema(description = "Registro no CRC, sem formato fixo. Só no papel CONTADOR (no PRODUTOR,"
        + " 400); vazio = não informado", example = "PR-012345/O")
    String crc,

    @Pattern(regexp = "\\d{10,11}|",
        message = "Informe o telefone só com dígitos: DDD e número (10 ou 11 dígitos).")
    @Schema(description = "Telefone da pessoa: DDD e número, só dígitos (10 ou 11); vazio = não"
        + " informado", example = "45999990000")
    String telefone,

    @Schema(description = "Só no papel CONTADOR (no PRODUTOR, 400). Sem o campo, PESSOAL",
        example = "EMPRESARIAL")
    TipoEscritorio tipoConta,

    @AssertTrue(message = AceiteTermosObrigatorioException.MENSAGEM)
    @Schema(description = "Aceite dos termos de uso: true grava a data e a versão; false é 400;"
        + " sem o campo, o aceite fica não registrado (400 quando o aceite for exigido)",
        example = "true")
    Boolean aceiteTermos) {

  /**
   * Cadastro sem os campos da #519: o contador ganha o escritório pessoal com o nome dele, nenhum
   * dos dois papéis grava CRC ou telefone, e o aceite dos termos fica não registrado.
   *
   * @param papel        PRODUTOR ou CONTADOR
   * @param nomeCompleto nome do produtor ou da pessoa do contador
   * @param cpfCnpj      CPF ou CNPJ, só dígitos
   * @param email        e-mail de login
   * @param senha        senha escolhida
   */
  public CadastroRequest(PapelUsuario papel, String nomeCompleto, String cpfCnpj, String email,
      String senha) {
    this(papel, nomeCompleto, cpfCnpj, email, senha, null, null, null, null, null);
  }

  /**
   * Sem senha, e-mail, documento, CNPJ, CRC, telefone nem endereço: o {@code toString} padrão do
   * record os poria em qualquer log do request (LGPD).
   */
  @Override
  public String toString() {
    return "CadastroRequest[papel=" + papel + ", tipoConta=" + tipoConta + "]";
  }

  /**
   * Escritório do contador empresarial informado no cadastro (issue #519): vira o escritório de
   * que ele é administrador, já com o endereço próprio. Todos os campos são obrigatórios.
   *
   * @param nome                   nome do escritório
   * @param cnpj                   CNPJ, 14 dígitos com DV válido (não é único, #520)
   * @param telefone               telefone do escritório, só dígitos (10 ou 11)
   * @param endereco               endereço postal completo
   * @param slug                   endereço próprio, {@code {slug}.agroconia.com.br} (#464)
   * @param cienteExposicaoPublica ciência de que o endereço próprio fica público; precisa ser true
   */
  @Schema(description = "Escritório do contador empresarial no cadastro")
  public record EscritorioDoCadastro(
      // Exemplos diferentes do escritório da demonstração: o reset da banca o acha por nome e
      // CNPJ (docs/demonstracao/reset-*.sql), e o CNPJ não é único (#520).
      @NotBlank(message = "Informe o nome do escritório.")
      @Size(max = 255, message = "O nome do escritório tem no máximo 255 caracteres.")
      @Schema(description = "Nome do escritório", example = "Contabilidade Campo Verde")
      String nome,

      @NotNull(message = "Informe o CNPJ do escritório.")
      @Pattern(regexp = "\\d{14}", message = "Informe o CNPJ do escritório só com dígitos (14).")
      @ValidCpfCnpj
      @Schema(description = "CNPJ só com dígitos", example = "11444777000161")
      String cnpj,

      @NotNull(message = "Informe o telefone do escritório.")
      @Pattern(regexp = "\\d{10,11}",
          message = "Informe o telefone do escritório só com dígitos: DDD e número (10 ou 11).")
      @Schema(description = "DDD e número, só dígitos (10 ou 11)", example = "4533330000")
      String telefone,

      @NotNull(message = "Informe o endereço do escritório.")
      @Valid
      @Schema(description = "Endereço postal completo")
      EnderecoPostalRequest endereco,

      @NotNull(message = "Informe o endereço próprio do escritório.")
      @SlugEscritorio
      @Schema(description = "Endereço próprio, {slug}.agroconia.com.br: de 3 a 32 caracteres,"
          + " letras minúsculas sem acento, números e hífen", example = "campoverde")
      String slug,

      @NotNull(message = "Confirme que leu o aviso: o endereço fica público e permanente.")
      @AssertTrue(message = "Confirme que leu o aviso: o endereço fica público e permanente.")
      @Schema(description = "Ciência de que o endereço próprio fica público e permanente nos"
          + " registros de certificados da internet (não use o nome de uma pessoa); precisa ser"
          + " true", example = "true")
      Boolean cienteExposicaoPublica) {

    /** Sem os dados do escritório: o nome e o endereço podem identificar uma pessoa (LGPD). */
    @Override
    public String toString() {
      return "EscritorioDoCadastro[***]";
    }
  }
}
