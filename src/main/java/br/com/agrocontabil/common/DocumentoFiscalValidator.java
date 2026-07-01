package br.com.agrocontabil.common;

/**
 * Validacao de digito verificador (modulo 11) de CPF e CNPJ (issue #18).
 *
 * <p>Utilitario stateless (metodos estaticos, sem estado) reaproveitado em dois pontos:
 * <ul>
 *   <li>Pelo {@code CpfCnpjValidator} (Bean Validation) no campo
 *       {@code destinatarioCpfCnpj} do request — violacao vira HTTP 400.</li>
 *   <li>Programaticamente pelo {@code GeracaoNfaService} para validar o documento do
 *       <strong>emitente</strong> (o produtor vem do {@code MockProdutorFilter}, nao do DTO) —
 *       violacao vira HTTP 422.</li>
 * </ul>
 *
 * <p>Antecipa parte do debito tecnico de validacao de DV de CPF/CNPJ previsto para a
 * Sprint 8 (ver CLAUDE.md — "Debitos tecnicos abertos").
 *
 * <p><strong>Escopo:</strong> valida apenas o digito verificador e o formato (comprimento e
 * somente digitos). Nao consulta a Receita Federal nem verifica situacao cadastral.
 *
 * <p><strong>LGPD:</strong> este utilitario nunca loga o documento; quem chama e responsavel
 * por mascarar CPF/CNPJ em logs e em mensagens de erro expostas ao cliente.
 */
public final class DocumentoFiscalValidator {

  private DocumentoFiscalValidator() {}

  /**
   * Valida um CPF ou CNPJ escolhendo o algoritmo pelo comprimento do documento.
   *
   * @param documento string com apenas digitos (11 = CPF, 14 = CNPJ); pode ser {@code null}
   * @return {@code true} se o documento tiver 11 digitos e for um CPF valido, ou 14 digitos e
   *     for um CNPJ valido; {@code false} caso contrario
   */
  public static boolean isCpfCnpjValido(String documento) {
    if (documento == null) {
      return false;
    }
    return switch (documento.length()) {
      case 11 -> isCpfValido(documento);
      case 14 -> isCnpjValido(documento);
      default -> false;
    };
  }

  /**
   * Valida o digito verificador de um CPF (11 digitos, modulo 11).
   *
   * @param cpf string com exatamente 11 digitos; pode ser {@code null}
   * @return {@code true} se o CPF for valido
   */
  public static boolean isCpfValido(String cpf) {
    if (cpf == null || !cpf.matches("\\d{11}") || todosDigitosIguais(cpf)) {
      return false;
    }
    int dv1 = digitoVerificador(cpf, 9, 10);
    int dv2 = digitoVerificador(cpf, 10, 11);
    return dv1 == charDigito(cpf, 9) && dv2 == charDigito(cpf, 10);
  }

  /**
   * Valida o digito verificador de um CNPJ (14 digitos, modulo 11).
   *
   * @param cnpj string com exatamente 14 digitos; pode ser {@code null}
   * @return {@code true} se o CNPJ for valido
   */
  public static boolean isCnpjValido(String cnpj) {
    if (cnpj == null || !cnpj.matches("\\d{14}") || todosDigitosIguais(cnpj)) {
      return false;
    }
    int dv1 = digitoVerificadorCnpj(cnpj, 12);
    int dv2 = digitoVerificadorCnpj(cnpj, 13);
    return dv1 == charDigito(cnpj, 12) && dv2 == charDigito(cnpj, 13);
  }

  // -------------------------------------------------------------------------
  // Algoritmo CPF: pesos decrescentes (10..2 no 1o DV, 11..2 no 2o DV).
  // -------------------------------------------------------------------------

  /**
   * Calcula um digito verificador de CPF por modulo 11.
   *
   * @param cpf         documento (somente digitos)
   * @param tamanhoBase quantidade de digitos considerados no calculo (9 para o 1o DV, 10 para o 2o)
   * @param pesoInicial peso do primeiro digito (10 para o 1o DV, 11 para o 2o)
   * @return digito verificador esperado (0..9)
   */
  private static int digitoVerificador(String cpf, int tamanhoBase, int pesoInicial) {
    int soma = 0;
    for (int i = 0; i < tamanhoBase; i++) {
      soma += charDigito(cpf, i) * (pesoInicial - i);
    }
    int resto = soma % 11;
    return resto < 2 ? 0 : 11 - resto;
  }

  // -------------------------------------------------------------------------
  // Algoritmo CNPJ: pesos ciclicos 2..9 aplicados da direita para a esquerda.
  // -------------------------------------------------------------------------

  /**
   * Calcula um digito verificador de CNPJ por modulo 11 com pesos ciclicos 2..9.
   *
   * @param cnpj        documento (somente digitos)
   * @param tamanhoBase quantidade de digitos considerados (12 para o 1o DV, 13 para o 2o)
   * @return digito verificador esperado (0..9)
   */
  private static int digitoVerificadorCnpj(String cnpj, int tamanhoBase) {
    int soma = 0;
    int peso = 2;
    for (int i = tamanhoBase - 1; i >= 0; i--) {
      soma += charDigito(cnpj, i) * peso;
      peso = peso == 9 ? 2 : peso + 1;
    }
    int resto = soma % 11;
    return resto < 2 ? 0 : 11 - resto;
  }

  private static int charDigito(String documento, int index) {
    return documento.charAt(index) - '0';
  }

  private static boolean todosDigitosIguais(String documento) {
    return documento.chars().distinct().count() == 1;
  }
}
