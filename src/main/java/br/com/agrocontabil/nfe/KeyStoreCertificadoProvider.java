package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Carrega o certificado digital (PKCS#12) e expõe a chave privada e o certificado X.509.
 *
 * <p>Cache lazy thread-safe; valida a validade temporal do certificado no carregamento.
 * Falhas viram {@link AssinaturaDigitalException} (HTTP 500) com o código apropriado.
 *
 * <p>Na subida (#225) o certificado configurado é carregado uma vez só para avisar: um certificado
 * autoassinado ou emitido fora da ICP-Brasil — o de teste do repositório, default do profile
 * {@code dev}, ou um de AC de teste — vira WARN, porque o
 * XML assinado com ele não tem validade jurídica e a chave privada é pública. Falha na subida
 * também só avisa: a aplicação sobe e a emissão responde o erro quando for pedida.
 */
@Component
public class KeyStoreCertificadoProvider {

  private static final Logger log = LoggerFactory.getLogger(KeyStoreCertificadoProvider.class);

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

  /** Carrega o certificado na subida só para avisar (#225); nunca impede a subida. */
  @EventListener(ApplicationReadyEvent.class)
  public void conferirNaSubida() {
    if (props.path() == null || props.path().isBlank()) {
      log.info("Certificado digital da NF-e nao configurado: a emissao falha ate definir "
          + "NFE_CERT_PATH.");
      return;
    }
    try {
      ensureLoaded();
    } catch (AssinaturaDigitalException e) {
      log.warn("Certificado digital da NF-e nao carregou na subida: {}. A emissao vai falhar ate "
          + "corrigir NFE_CERT_PATH/NFE_CERT_PASSWORD.", e.getMessage());
    }
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
      if (autoassinado(cert) || !emitidoPelaIcpBrasil(cert)) {
        // A1 de verdade e sempre emitido por AC da ICP-Brasil: este e o de teste (autoassinado)
        // ou um de AC de teste gerado a mao.
        log.warn("Certificado digital da NF-e de teste ({}, emitido por {}): autoassinado ou fora "
            + "da ICP-Brasil, uso so em dev/test. O XML assinado com ele nao tem validade "
            + "juridica; em homolog/prod configure um A1 ICP-Brasil (NFE_CERT_PATH).",
            cert.getSubjectX500Principal().getName(), cert.getIssuerX500Principal().getName());
      }

      this.privateKey = key;
      this.certificate = cert;
    } catch (java.security.cert.CertificateExpiredException e) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_EXPIRADO,
          "Certificado expirado: " + e.getMessage(), e);
    } catch (java.security.cert.CertificateNotYetValidException e) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_INVALIDO,
          "Certificado ainda nao valido (data de inicio futura): " + e.getMessage(), e);
    } catch (AssinaturaDigitalException e) {
      throw e;
    } catch (Exception e) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_INVALIDO,
          "Falha ao carregar o certificado: " + e.getMessage(), e);
    }
  }

  /** As ACs da ICP-Brasil levam {@code O=ICP-Brasil} no nome (DOC-ICP-04). */
  private static boolean emitidoPelaIcpBrasil(X509Certificate cert) {
    return cert.getIssuerX500Principal().getName().contains("O=ICP-Brasil");
  }

  private static boolean autoassinado(X509Certificate cert) {
    if (!cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) {
      return false;
    }
    try {
      cert.verify(cert.getPublicKey());
      return true;
    } catch (Exception e) {
      return false;
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
