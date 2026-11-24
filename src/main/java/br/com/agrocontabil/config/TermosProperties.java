package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Termos de uso, em {@code app.termos} (issue #519).
 *
 * <p>O cadastro registra o aceite (quando e qual versão) e o {@code TermosUsoService} serve o texto
 * em {@code GET /api/termos-de-uso} com esta mesma versão (#498). Mudou o texto, mude a versão
 * ({@code TERMOS_VERSAO} ou o valor padrão) para a data da mudança: o próximo aceite grava a nova.
 *
 * <p>A versão {@code 2026-11-11}, a data da decisão da #519, é <strong>provisória</strong>: foi
 * gravada nos cadastros feitos antes de o texto existir, e o próprio texto diz isso. O sistema não
 * pede reaceite quando a versão muda.
 *
 * @param versao versão dos termos que o cadastro grava na credencial junto com o aceite
 */
@ConfigurationProperties(prefix = "app.termos")
public record TermosProperties(@DefaultValue("2026-11-24") String versao) {
}
