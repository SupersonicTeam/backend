package br.com.agrocontabil.config;

import br.com.agrocontabil.model.MetodoVerificacaoCnpj;
import br.com.agrocontabil.service.VerificacaoCnpjService;
import br.com.agrocontabil.service.VerificacaoCnpjService.ResultadoVerificacao;
import br.com.agrocontabil.service.VerificacaoCnpjService.VerificacaoRecusadaException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Comando da verificação manual do CNPJ do escritório (issue #520). Só existe no profile
 * {@code verificacao-cnpj}, nunca como endpoint: a plataforma não tem papel de administrador, e quem
 * verifica é a GWNext, depois de conferir a titularidade do CNPJ fora do sistema.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,verificacao-cnpj \
 *   -Dspring-boot.run.arguments="--verificacao.escritorio=&lt;id&gt; --verificacao.cnpj=&lt;cnpj&gt;"
 * </pre>
 *
 * <p>Na VPS, com a imagem do backend (passo a passo no {@code deploy/README.md}):
 *
 * <pre>
 * docker compose run --rm -e SPRING_PROFILES_ACTIVE=homolog,verificacao-cnpj backend \
 *   --verificacao.escritorio=&lt;id&gt; --verificacao.cnpj=&lt;cnpj&gt;
 * </pre>
 *
 * <p>O {@code --verificacao.cnpj} é o CNPJ que o operador conferiu: tem de ser o gravado, senão a
 * marca é recusada. Com {@code --verificacao.revogar=true}, tira a verificação (o CNPJ não é
 * pedido). Termina com código 0 só com sucesso (inclusive quando nada muda), para um script
 * distinguir "verificado" de "recusado". O log e a auditoria nunca levam o CNPJ.
 */
@Component
@Profile("verificacao-cnpj")
public class VerificacaoCnpjRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(VerificacaoCnpjRunner.class);

  private final VerificacaoCnpjService verificacao;
  private final ConfigurableApplicationContext contexto;
  private final String escritorio;
  private final String cnpj;
  private final boolean revogar;

  /** Constrói o comando com os parâmetros {@code verificacao.*}. */
  public VerificacaoCnpjRunner(
      VerificacaoCnpjService verificacao,
      ConfigurableApplicationContext contexto,
      @Value("${verificacao.escritorio:}") String escritorio,
      @Value("${verificacao.cnpj:}") String cnpj,
      @Value("${verificacao.revogar:false}") boolean revogar) {
    this.verificacao = verificacao;
    this.contexto = contexto;
    this.escritorio = escritorio;
    this.cnpj = cnpj;
    this.revogar = revogar;
  }

  /** Verifica (ou revoga) e encerra a aplicação com o código de saída. */
  @Override
  public void run(ApplicationArguments args) {
    int saida = executar();
    System.exit(SpringApplication.exit(contexto, () -> saida));
  }

  /**
   * Faz a marca e diz como terminou, sem encerrar a aplicação.
   *
   * @return 0 com sucesso; 1 com parâmetro errado ou marca recusada
   */
  int executar() {
    UUID id;
    try {
      id = UUID.fromString(escritorio.strip());
    } catch (IllegalArgumentException e) {
      log.error("Verificacao do CNPJ: informe --verificacao.escritorio com o id do escritorio.");
      return 1;
    }
    if (!revogar && cnpj.isBlank()) {
      log.error("Verificacao do CNPJ: informe --verificacao.cnpj com o CNPJ conferido.");
      return 1;
    }
    try {
      ResultadoVerificacao resultado = revogar
          ? verificacao.revogar(id)
          : verificacao.verificar(id, cnpj, MetodoVerificacaoCnpj.MANUAL);
      log.info("Verificacao do CNPJ {}: escritorio={}, antes={}, depois={}",
          resultado.mudou() ? "concluida" : "sem mudanca", id, resultado.antes(),
          resultado.depois());
      return 0;
    } catch (VerificacaoRecusadaException e) {
      log.error("Verificacao do CNPJ recusada: escritorio={}, motivo={} ({})", id, e.motivo(),
          e.motivo().descricao());
      return 1;
    } catch (RuntimeException e) {
      log.error("Verificacao do CNPJ falhou: escritorio={}", id, e);
      return 1;
    }
  }
}
