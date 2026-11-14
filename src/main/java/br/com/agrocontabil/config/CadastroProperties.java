package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Regras do cadastro de conta que mudam por ambiente, em {@code app.cadastro} (issue #519).
 *
 * @param exigirAceiteTermos o cadastro sem {@code aceiteTermos} é recusado (400). Desligado enquanto
 *                           a tela não manda o campo: a ausência é aceita e o aceite fica "não
 *                           registrado". Liga por variável de ambiente
 *                           ({@code CADASTRO_EXIGIR_ACEITE_TERMOS}) quando a tela nova estiver no
 *                           ar, sem mudar código. {@code aceiteTermos: false} é sempre 400
 */
@ConfigurationProperties(prefix = "app.cadastro")
public record CadastroProperties(@DefaultValue("false") boolean exigirAceiteTermos) {
}
