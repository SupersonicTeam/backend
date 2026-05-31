package br.com.agrocontabil;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: garante que o contexto Spring sobe com a fundacao atual.
 *
 * <p>Usa o profile {@code test} para nao depender do Postgres real
 * (ver {@code application-test.yml} quando houver banco em memoria).
 */
@SpringBootTest
@ActiveProfiles("test")
class AgroContabilApplicationTests {

    @Test
    void contextLoads() {
    }
}
