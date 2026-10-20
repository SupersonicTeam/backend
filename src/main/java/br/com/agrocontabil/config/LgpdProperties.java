package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Dados da política de privacidade (LGPD, issue #148), em {@code app.lgpd}.
 *
 * @param controlador         quem decide sobre o tratamento (LGPD art. 5º, VI)
 * @param contatoEncarregado  canal do encarregado (DPO, art. 41) para o titular exercer direitos
 * @param versaoPolitica      versão publicada da política (muda quando o texto muda)
 */
@ConfigurationProperties(prefix = "app.lgpd")
public record LgpdProperties(
    @DefaultValue("AgroContabil - Supersonic Team") String controlador,
    @DefaultValue("privacidade@agrocontabil.local") String contatoEncarregado,
    @DefaultValue("2026-10-19") String versaoPolitica) {
}
