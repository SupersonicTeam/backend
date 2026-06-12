package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Carrega o certificado digital (PKCS#12) e expõe a chave privada e o certificado X.509.
 *
 * <p>Cache lazy thread-safe; valida a validade temporal do certificado no carregamento.
 * Falhas viram {@link AssinaturaDigitalException} (HTTP 500) com o código apropriado.
 */
@Component
public class KeyStoreCertificadoProvider {

  private final CertificadoDigitalProperties props;
  private final ResourceLoader resourceLoader = new DefaultResourceLoader();

  private volatile PrivateKey privateKey;
  private volatile X509Certificate certificate;
  private final Object lock = new Object();

  public KeyStoreCertificadoProvider(CertificadoDigitalProperties props) {
    this.props = props;
  }

  /** Chave privada do certificado, carregada sob demanda. */
  public PrivateKey getPrivateKey() {
    ensureLoaded();
    return privateKey;
  }

  /** Certificado X.509 do emitente, carregado sob demanda. */
  public X509Certificate getCertificate() {
    ensureLoaded();
    return certificate;
  }

  private void ensureLoaded() {
    if (certificate != null) {
      return;
    }
    synchronized (lock) {
      if (certificate != null) {
        return;
      }
      load();
    }
  }

  private void load() {
    if (props.path() == null || props.path().isBlank()) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_AUSENTE,
          "Caminho do certificado (app.nfe.certificado.path) nao configurado");
    }
    Resource resource = resourceLoader.getResource(props.path());
    if (!resource.exists()) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_AUSENTE,
          "Arquivo de certificado nao encontrado: " + props.path());
    }
    try (InputStream in = resource.getInputStream()) {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      char[] pwd = props.password() == null ? new char[0] : props.password().toCharArray();
      ks.load(in, pwd);

      String alias = resolveAlias(ks);
      PrivateKey key = (PrivateKey) ks.getKey(alias, pwd);
      X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
      if (key == null || cert == null) {
        throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_INVALIDO,
            "Alias '" + alias + "' nao contem chave privada e certificado");
      }
      cert.checkValidity(); // lanca CertificateExpiredException/NotYetValid

      this.privateKey = key;
      this.certificate = cert;
    } catch (java.security.cert.CertificateExpiredException
        | java.security.cert.CertificateNotYetValidException e) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_EXPIRADO,
          "Certificado fora do periodo de validade: " + e.getMessage(), e);
    } catch (AssinaturaDigitalException e) {
      throw e;
    } catch (Exception e) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_INVALIDO,
          "Falha ao carregar o certificado: " + e.getMessage(), e);
    }
  }

  private String resolveAlias(KeyStore ks) throws Exception {
    if (props.alias() != null && !props.alias().isBlank()) {
      return props.alias();
    }
    Enumeration<String> aliases = ks.aliases();
    if (aliases.hasMoreElements()) {
      return aliases.nextElement();
    }
    throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_INVALIDO,
        "KeyStore nao possui nenhum alias");
  }
}
