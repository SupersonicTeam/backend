package br.com.agrocontabil.validation;

import br.com.agrocontabil.common.UfIbge;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * Validador de {@link CodigoIbgeCoerenteComUf} (issue #88), para todo {@link EnderecoComCodigoIbge}
 * (o do destinatário da nota e, desde a #519, o postal do escritório).
 *
 * <p>Os 2 primeiros dígitos do código IBGE de um município são o cUF da sua UF, então a coerência
 * é verificável sem a tabela completa dos 5.570 municípios (débito da Sprint 7). A violação é
 * reportada no campo {@code codigoMunicipioIbge} para que o cliente saiba o que corrigir.
 */
public class CodigoIbgeCoerenteComUfValidator
    implements ConstraintValidator<CodigoIbgeCoerenteComUf, EnderecoComCodigoIbge> {

  /** Forma de uma sigla de UF: a única parte do valor do cliente que vai para a mensagem. */
  private static final Pattern SIGLA_UF = Pattern.compile("[A-Z]{2}");

  @Override
  public boolean isValid(EnderecoComCodigoIbge endereco, ConstraintValidatorContext context) {
    if (endereco == null) {
      return true;
    }
    String codigo = endereco.codigoMunicipioIbge();
    String uf = endereco.uf();
    if (codigo == null || codigo.isBlank() || uf == null || uf.isBlank()) {
      // Campos opcionais no rascunho; a emissao exige ambos via FiscalRulesValidator.
      return true;
    }
    if (UfIbge.isExterior(uf)) {
      // Exportacao: o codigo de municipio do exterior nao pertence a UF brasileira. A mesma
      // isencao existe no FiscalRulesValidator — as duas camadas precisam concordar.
      return true;
    }
    if (UfIbge.prefixoCoerente(codigo, uf)) {
      return true;
    }
    context.disableDefaultConstraintViolation();
    // O texto vira template do Bean Validation: so uma sigla de duas letras entra nele. Desde a
    // #519 o validador roda no cadastro publico, e um valor do cliente seria interpolado.
    String sigla = uf.trim().toUpperCase();
    context
        .buildConstraintViolationWithTemplate("'codigoMunicipioIbge' não pertence à UF "
            + (SIGLA_UF.matcher(sigla).matches() ? sigla : "informada"))
        .addPropertyNode("codigoMunicipioIbge")
        .addConstraintViolation();
    return false;
  }
}
