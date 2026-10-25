package br.com.agrocontabil.config;

import br.com.agrocontabil.service.ClamdCliente;
import br.com.agrocontabil.service.VerificacaoAntivirus;
import br.com.agrocontabil.service.VerificacaoAntivirus.ResultadoVerificacao;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Escolhe o antivírus dos anexos das pendências (issue #227).
 *
 * <ul>
 *   <li>{@code CLAMAV} (padrão): {@link ClamdCliente} em {@code host:porta}. Não conecta na
 *       subida: com o ClamAV fora do ar a aplicação sobe, e os anexos ficam em verificação até
 *       ele voltar (falha fechada);
 *   <li>{@code SEM_VERIFICACAO}: todo anexo sai limpo, sem verificar. Para a máquina do
 *       desenvolvedor sem o container do ClamAV — <strong>a aplicação só sobe com ele nos
 *       profiles {@code dev} e {@code test}</strong>.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AntivirusProperties.class)
public class AntivirusConfig {

  private static final Logger log = LoggerFactory.getLogger(AntivirusConfig.class);

  /** Profiles em que o anexo pode sair sem verificação. */
  static final List<String> PERFIS_SEM_VERIFICACAO = List.of("dev", "test");

  /**
   * Implementação da porta conforme o modo.
   *
   * @param propriedades configuração do antivírus
   * @param ambiente     para recusar {@code SEM_VERIFICACAO} fora de dev/test
   * @return a porta
   */
  @Bean
  public VerificacaoAntivirus verificacaoAntivirus(AntivirusProperties propriedades,
      Environment ambiente) {
    if (propriedades.modo() == AntivirusProperties.Modo.CLAMAV) {
      return new ClamdCliente(propriedades.host(), propriedades.porta(),
          propriedades.connectTimeout(), propriedades.readTimeout());
    }
    // Lista de permissao, como o modo LOG do e-mail (#209): homolog, prod ou um profile novo
    // nunca liberam anexo sem antivirus.
    List<String> perfis = Arrays.asList(ambiente.getActiveProfiles());
    if (perfis.stream().noneMatch(PERFIS_SEM_VERIFICACAO::contains)) {
      throw new IllegalStateException("app.pendencias.antivirus.modo=SEM_VERIFICACAO libera "
          + "anexo sem antivirus: permitido so nos profiles " + PERFIS_SEM_VERIFICACAO
          + ". Use CLAMAV (ANTIVIRUS_MODO).");
    }
    log.warn("Antivirus dos anexos DESLIGADO (modo SEM_VERIFICACAO): todo anexo sai como limpo.");
    return conteudo -> ResultadoVerificacao.limpo();
  }
}
