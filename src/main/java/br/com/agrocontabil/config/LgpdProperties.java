package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Dados da política de privacidade (LGPD, issue #148), em {@code app.lgpd}.
 *
 * @param controlador         quem decide sobre o tratamento (LGPD art. 5º, VI): a GWNext, dona
 *                            do AgroConIA
 * @param contatoEncarregado  canal do encarregado (DPO, art. 41) para o titular exercer direitos
 * @param versaoPolitica      versão publicada da política (muda quando o texto muda)
 */
@ConfigurationProperties(prefix = "app.lgpd")
public record LgpdProperties(
    @DefaultValue("GWNext") String controlador,
    @DefaultValue("contato@gwnext.com.br") String contatoEncarregado,
    @DefaultValue("2026-11-03") String versaoPolitica) {
}
