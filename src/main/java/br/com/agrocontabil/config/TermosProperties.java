package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Termos de uso, em {@code app.termos} (issue #519).
 *
 * <p>O backend só registra o aceite: quando e qual versão. O texto e a página dos termos ficam com
 * a GWNext, junto com o jurídico (#498); mudou o texto, muda a versão ({@code TERMOS_VERSAO}), e o
 * próximo aceite grava a nova.
 *
 * <p><strong>Valor provisório:</strong> enquanto o texto não existe, a versão padrão
 * ({@code 2026-11-11}, a data da decisão da #519) só marca que houve aceite. Os aceites gravados com
 * ela são anteriores ao texto: quando a #498 publicar os termos, a versão muda e quem aceitou a
 * provisória pode ser chamado a aceitar de novo.
 *
 * @param versao versão dos termos que o cadastro grava na credencial junto com o aceite
 */
@ConfigurationProperties(prefix = "app.termos")
public record TermosProperties(@DefaultValue("2026-11-11") String versao) {
}
