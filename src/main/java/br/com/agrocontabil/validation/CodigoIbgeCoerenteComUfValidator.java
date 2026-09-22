package br.com.agrocontabil.validation;

import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.dto.NotaFiscalRequest.EnderecoDestinatarioRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validador de {@link CodigoIbgeCoerenteComUf} (issue #88).
 *
 * <p>Os 2 primeiros dígitos do código IBGE de um município são o cUF da sua UF, então a coerência
 * é verificável sem a tabela completa dos 5.570 municípios (débito da Sprint 7). A violação é
 * reportada no campo {@code codigoMunicipioIbge} para que o cliente saiba o que corrigir.
 */
public class CodigoIbgeCoerenteComUfValidator
    implements ConstraintValidator<CodigoIbgeCoerenteComUf, EnderecoDestinatarioRequest> {

  @Override
  public boolean isValid(EnderecoDestinatarioRequest endereco, ConstraintValidatorContext context) {
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
    context
        .buildConstraintViolationWithTemplate(
            "codigoMunicipioIbge nao pertence a UF " + uf.trim().toUpperCase())
        .addPropertyNode("codigoMunicipioIbge")
        .addConstraintViolation();
    return false;
  }
}
