package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Trava a configuração do pool de conexões e o timeout padrão de transação (issue #98).
 *
 * <p>Antes desta issue o HikariCP rodava inteiramente nos defaults: sem
 * {@code leak-detection-threshold} (0 = desligado), sem {@code connection-timeout} próprio e sem
 * limite de duração de transação. Uma conexão vazada só aparecia quando o pool esgotava, e uma
 * transação presa ficava indefinidamente segurando a conexão.
 *
 * <p>Os valores aqui são os <strong>defaults</strong> do {@code application.yml}; todos são
 * sobrescritíveis por variável de ambiente (ver {@code .env.example}). Este teste existe para
 * que uma alteração acidental desses defaults quebre o build em vez de passar despercebida.
 *
 * <p>O {@code statement_timeout} do PostgreSQL não é verificado aqui: ele vive apenas nos
 * profiles {@code dev}/{@code prod}, porque o driver H2 usado nos testes rejeita propriedades
 * JDBC desconhecidas. A verificação daquele é manual, documentada no PR.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataSourceConfigTest {

  /** Timeout padrão de transação, em segundos ({@code spring.transaction.default-timeout}). */
  private static final int TIMEOUT_TRANSACAO_SEGUNDOS = 30;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("pool HikariCP deve usar os limites e timeouts parametrizados")
  void poolDeveUsarLimitesEtimeoutsParametrizados() throws SQLException {
    HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

    assertThat(hikari.getPoolName()).isEqualTo("agrocontabil-pool");
    assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
    assertThat(hikari.getMinimumIdle()).isEqualTo(2);
    assertThat(hikari.getConnectionTimeout()).isEqualTo(5_000);
    assertThat(hikari.getIdleTimeout()).isEqualTo(600_000);
    assertThat(hikari.getMaxLifetime()).isEqualTo(1_800_000);
    assertThat(hikari.getValidationTimeout()).isEqualTo(3_000);
  }

  @Test
  @DisplayName("leak-detection deve estar ativo e abaixo do max-lifetime")
  void leakDetectionDeveEstarAtivoEabaixoDoMaxLifetime() throws SQLException {
    HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

    // 0 = desligado (default do Hikari). O minimo aceito pelo Hikari e 2000 ms: abaixo disso ele
    // ignora silenciosamente o valor.
    assertThat(hikari.getLeakDetectionThreshold())
        .as("leak-detection deve estar ligado e acima do minimo de 2s do Hikari")
        .isEqualTo(30_000);

    // Se o limiar fosse >= max-lifetime, o Hikari desligaria a deteccao — a conexao seria
    // aposentada antes de ser reportada como vazada.
    assertThat(hikari.getLeakDetectionThreshold())
        .as("leak-detection precisa ser menor que max-lifetime para ter efeito")
        .isLessThan(hikari.getMaxLifetime());
  }

  @Test
  @DisplayName("transacao deve ter timeout padrao de 30s")
  void transacaoDeveTerTimeoutPadrao() {
    assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);

    JpaTransactionManager jpa = (JpaTransactionManager) transactionManager;
    assertThat(jpa.getDefaultTimeout()).isEqualTo(TIMEOUT_TRANSACAO_SEGUNDOS);
  }
}
