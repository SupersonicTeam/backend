package br.com.agrocontabil.config;

import br.com.agrocontabil.common.MascaraDadosPessoais;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * Conversor do Logback que mascara dados pessoais no stack trace (LGPD, issue #148).
 *
 * <p>O {@link MensagemLogMascaradaConverter} cobre só a mensagem. A exceção logada junto traz o
 * texto de quem a lançou — e o do banco numa violação de constraint traz a linha recusada
 * ({@code Key (email)=(...)}, {@code Failing row contains (...)}). Este conversor é o mesmo
 * {@code %wEx} do Spring Boot com a {@link MascaraDadosPessoais} aplicada ao resultado; entra no
 * padrão pela propriedade {@code LOG_EXCEPTION_CONVERSION_WORD} em {@code logback-spring.xml}.
 */
public class ExcecaoLogMascaradaConverter extends ExtendedWhitespaceThrowableProxyConverter {

  @Override
  public String convert(ILoggingEvent evento) {
    return MascaraDadosPessoais.mascarar(super.convert(evento));
  }
}
