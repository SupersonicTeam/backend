package br.com.agrocontabil.common.validation;

/**
 * Utilitario puro de validacao de CPF (cadastro de pessoa fisica) pelo
 * algoritmo dos digitos verificadores (modulo 11).
 *
 * <p>Sem dependencia de Spring ou Jakarta — testavel isoladamente e reutilizavel
 * tanto pela camada de Bean Validation ({@link CpfValidator}) quanto pela camada
 * de servico (validacao do CPF do producer emitente da NFA).
 */
public final class CpfUtils {

  private CpfUtils() {}

  /**
   * Valida um CPF. Aceita o valor com ou sem formatacao (pontos e traco sao
   * removidos antes da validacao).
   *
   * @param cpf CPF a validate (pode ser null)
   * @return {@code true} se for um CPF estruturalmente valido; {@code false} caso
   *         contrario (null, tamanho != 11, digitos repetidos ou DV invalido)
   */
  public static boolean isValid(String cpf) {
    if (cpf == null) {
      return false;
    }
    String digits = cpf.replaceAll("\\D", "");
    if (digits.length() != 11) {
      return false;
    }
    if (allDigitsEqual(digits)) {
      return false;
    }
    int dv1 = checkDigit(digits, 9);
    int dv2 = checkDigit(digits, 10);
    return dv1 == (digits.charAt(9) - '0') && dv2 == (digits.charAt(10) - '0');
  }

  private static boolean allDigitsEqual(String digits) {
    char first = digits.charAt(0);
    for (int i = 1; i < digits.length(); i++) {
      if (digits.charAt(i) != first) {
        return false;
      }
    }
    return true;
  }

  /**
   * Calcula um digit verificador do CPF.
   *
   * @param digits  string com os 11 digitos do CPF
   * @param length  number de digitos considerados no calculo (9 para o 1o DV,
   *                10 para o 2o DV)
   * @return o digit verificador esperado (0-9)
   */
  private static int checkDigit(String digits, int length) {
    int sum = 0;
    int weight = length + 1;
    for (int i = 0; i < length; i++) {
      sum += (digits.charAt(i) - '0') * weight;
      weight--;
    }
    int remainder = sum % 11;
    return remainder < 2 ? 0 : 11 - remainder;
  }
}
