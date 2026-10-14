package br.com.agrocontabil.sefaz;

import br.com.agrocontabil.common.DocumentoFiscalValidator;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/**
 * CPF ou CNPJ do autor de um evento da NF-e (issue #128): o emitente, que é o titular do
 * certificado que assina o evento.
 *
 * <p>Primeiro o <strong>certificado</strong>: no ICP-Brasil, o CN do e-CNPJ e do e-CPF termina em
 * {@code :} + documento ({@code EMPRESA LTDA:12345678000199}). É a fonte certa, porque a SEFAZ
 * recusa o evento cujo autor não bate com o certificado (rejeição 213). Sem esse padrão (o
 * certificado de teste não o tem), vem da <strong>chave de acesso</strong>: as posições 7 a 20
 * trazem o CNPJ do emitente, ou {@code 000} + CPF. Como há CNPJ válido que começa com {@code 000},
 * a dúvida se resolve pelos dígitos verificadores: CNPJ válido vence; senão, CPF válido.
 */
final class DocumentoEmitente {

  private static final Pattern DOCUMENTO_NO_CN = Pattern.compile(":(\\d{11}|\\d{14})$");

  private DocumentoEmitente() {}

  /**
   * @param certificado certificado do emitente (pode ser nulo)
   * @param chaveAcesso chave de acesso de 44 dígitos
   * @return o CPF (11 dígitos) ou o CNPJ (14 dígitos) do emitente
   * @throws IllegalArgumentException se nem o certificado nem a chave trazem um documento válido
   */
  static String de(X509Certificate certificado, String chaveAcesso) {
    return doCertificado(certificado).orElseGet(() -> daChave(chaveAcesso));
  }

  static Optional<String> doCertificado(X509Certificate certificado) {
    if (certificado == null) {
      return Optional.empty();
    }
    try {
      for (Rdn rdn : new LdapName(certificado.getSubjectX500Principal().getName()).getRdns()) {
        if ("CN".equalsIgnoreCase(rdn.getType())) {
          Matcher m = DOCUMENTO_NO_CN.matcher(String.valueOf(rdn.getValue()).trim());
          if (m.find() && DocumentoFiscalValidator.isCpfCnpjValido(m.group(1))) {
            return Optional.of(m.group(1));
          }
        }
      }
    } catch (InvalidNameException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  static String daChave(String chaveAcesso) {
    MensagensSefaz.exigirChave(chaveAcesso);
    String campo = chaveAcesso.substring(6, 20);
    if (DocumentoFiscalValidator.isCnpjValido(campo)) {
      return campo;
    }
    String cpf = campo.substring(3);
    if (campo.startsWith("000") && DocumentoFiscalValidator.isCpfValido(cpf)) {
      return cpf;
    }
    throw new IllegalArgumentException("Chave de acesso sem CPF/CNPJ valido do emitente");
  }
}
