package br.com.agrocontabil.assistente.rag;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Avisa na subida quando a base de legislação está vazia (issue #175).
 *
 * <p>A ingestão é comando de manutenção (profile {@code ingestao}), não roda na subida. Com
 * {@code legislacao_trecho} vazia a Ceres não inventa nada — a {@link
 * br.com.agrocontabil.assistente.RotaLegislacao} responde "não encontrei fundamento legal" sem
 * chamar o modelo —, mas também nunca cita lei, e quem recria o ambiente não tinha como saber que
 * faltava esse passo.
 *
 * <p>Só avisa: não derruba a subida nem o health (o resto do backend funciona sem a base). Roda só
 * com a Ceres ligada ({@code spring.ai.model.chat} diferente de {@code none}) e fora do próprio
 * comando de ingestão, que começa justamente com a base vazia.
 */
@Component
@Profile("!ingestao")
@ConditionalOnExpression("!'${spring.ai.model.chat:ollama}'.equalsIgnoreCase('none')")
public class VerificacaoBaseLegislacao {

  private static final Logger log = LoggerFactory.getLogger(VerificacaoBaseLegislacao.class);

  private static final String SQL = "SELECT EXISTS (SELECT 1 FROM legislacao_trecho)";

  private static final String COMO_INGERIR =
      "rode a ingestao uma vez: ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,ingestao "
          + "(ver docs/ceres-assistente.md)";

  private final NamedParameterJdbcTemplate jdbc;

  /** Constrói a verificação sobre o banco da aplicação. */
  public VerificacaoBaseLegislacao(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Confere a base depois da subida. */
  @EventListener(ApplicationReadyEvent.class)
  public void aoSubir() {
    verificar();
  }

  /**
   * Confere se há ao menos um trecho de legislação.
   *
   * @return {@code true} se a base tem trechos; {@code false} se está vazia ou não pôde ser lida
   */
  boolean verificar() {
    try {
      if (Boolean.TRUE.equals(jdbc.queryForObject(SQL, Map.of(), Boolean.class))) {
        return true;
      }
      log.warn("Base de legislacao vazia (legislacao_trecho sem trechos): a Ceres nao vai citar "
          + "lei e responde 'nao encontrei fundamento legal' a toda pergunta legal; {}",
          COMO_INGERIR);
    } catch (DataAccessException e) {
      // So o tipo: a mensagem do driver pode trazer URL ou usuario do banco.
      log.warn("Nao foi possivel conferir a base de legislacao (legislacao_trecho) na subida "
          + "({}); se ela estiver vazia, {}", e.getClass().getSimpleName(), COMO_INGERIR);
    }
    return false;
  }
}
