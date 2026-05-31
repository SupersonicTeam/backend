package br.com.agrocontabil.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint simples de status da aplicacao.
 *
 * <p>Prova que a fundacao sobe ponta a ponta. O health check completo
 * (banco, disco, etc.) fica em {@code /actuator/health} via Spring Actuator.
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "Status da aplicacao")
public class HealthController {

    @GetMapping
    @Operation(summary = "Verifica se a API esta no ar")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "agrocontabil-backend",
                "timestamp", OffsetDateTime.now());
    }
}
