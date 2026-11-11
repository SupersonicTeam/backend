package br.com.agrocontabil.validation;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.model.TipoEscritorio;
import br.com.agrocontabil.security.PapelUsuario;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validador de {@link CadastroCoerenteComPapel} (issue #519).
 *
 * <p>As mensagens são fixas (nunca o valor recebido): o texto de uma violação passa pela
 * interpolação do Bean Validation, e um valor do cliente ali seria interpretado como expressão.
 */
public class CadastroCoerenteComPapelValidator
    implements ConstraintValidator<CadastroCoerenteComPapel, CadastroRequest> {

  static final String MENSAGEM_ESCRITORIO_NO_PRODUTOR =
      "O escritório só vai no cadastro de contador.";
  static final String MENSAGEM_CRC_NO_PRODUTOR = "O CRC só vai no cadastro de contador.";
  static final String MENSAGEM_TIPO_NO_PRODUTOR =
      "O tipo de conta só vai no cadastro de contador.";
  static final String MENSAGEM_ESCRITORIO_NO_PESSOAL = "A conta pessoal não leva os dados do"
      + " escritório: ele nasce com o seu nome. Para informar o escritório, escolha a conta"
      + " empresarial.";
  static final String MENSAGEM_SEM_ESCRITORIO_NO_EMPRESARIAL = "A conta empresarial exige os dados"
      + " do escritório: nome, CNPJ, telefone, endereço e endereço próprio.";

  @Override
  public boolean isValid(CadastroRequest request, ConstraintValidatorContext context) {
    if (request == null || request.papel() == null) {
      // O @NotNull do papel responde; sem papel, nenhum campo daqui pode ser julgado.
      return true;
    }
    context.disableDefaultConstraintViolation();
    return request.papel() == PapelUsuario.PRODUTOR
        ? produtorValido(request, context)
        : contadorValido(request, context);
  }

  private static boolean produtorValido(CadastroRequest request,
      ConstraintValidatorContext context) {
    boolean valido = true;
    if (request.escritorio() != null) {
      violacao(context, "escritorio", MENSAGEM_ESCRITORIO_NO_PRODUTOR);
      valido = false;
    }
    // Vazio = não informado, como no contador: o formulário pode mandar o campo em branco.
    if (request.crc() != null && !request.crc().isBlank()) {
      violacao(context, "crc", MENSAGEM_CRC_NO_PRODUTOR);
      valido = false;
    }
    if (request.tipoConta() != null) {
      violacao(context, "tipoConta", MENSAGEM_TIPO_NO_PRODUTOR);
      valido = false;
    }
    return valido;
  }

  private static boolean contadorValido(CadastroRequest request,
      ConstraintValidatorContext context) {
    boolean valido = true;
    if (DocumentoDoContador.recusado(request.cpfCnpj())) {
      violacao(context, "cpfCnpj", DocumentoDoContador.MENSAGEM_CADASTRO);
      valido = false;
    }
    if (request.tipoConta() == TipoEscritorio.EMPRESARIAL) {
      if (request.escritorio() == null) {
        violacao(context, "escritorio", MENSAGEM_SEM_ESCRITORIO_NO_EMPRESARIAL);
        valido = false;
      }
    } else if (request.escritorio() != null) {
      // Sem tipoConta vale PESSOAL, o comportamento de antes da #519.
      violacao(context, "escritorio", MENSAGEM_ESCRITORIO_NO_PESSOAL);
      valido = false;
    }
    return valido;
  }

  private static void violacao(ConstraintValidatorContext context, String campo, String mensagem) {
    context.buildConstraintViolationWithTemplate(mensagem)
        .addPropertyNode(campo)
        .addConstraintViolation();
  }
}
