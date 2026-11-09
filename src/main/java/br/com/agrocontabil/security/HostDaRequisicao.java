package br.com.agrocontabil.security;

import br.com.agrocontabil.config.EnderecoEscritorioProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Ponto único da leitura do host da requisição (issue #464): diz se ela chegou por um endereço de
 * escritório, {@code {slug}.agroconia.com.br}, e com qual rótulo.
 *
 * <p><strong>De onde vem o host.</strong> Do {@code getServerName()}: o {@code Host} da
 * requisição, ou o {@code X-Forwarded-Host} quando quem conecta é um proxy interno. Essa troca é
 * do {@code RemoteIpValve} do Tomcat, ligado por {@code server.forward-headers-strategy=native}
 * (na VPS e em dev), que só aceita o cabeçalho de proxy interno. Nunca ler o
 * {@code X-Forwarded-Host} direto aqui: qualquer cliente o forjaria.
 *
 * <p><strong>O host só restringe.</strong> Ele decide se o login e o refresh exigem um membro do
 * escritório do endereço; nunca amplia acesso. Um host forjado, no pior caso, faz o próprio login
 * ser recusado.
 *
 * <p>Host principal: o domínio base, {@code www.} + domínio base e qualquer host fora do domínio
 * base ({@code localhost}, IP). Host de escritório: qualquer outro sob o domínio base; o rótulo
 * devolvido pode não ser um slug válido (dois rótulos, maiúsculas já tratadas), e aí nenhum
 * escritório casa.
 */
@Component
public class HostDaRequisicao {

  private final EnderecoEscritorioProperties propriedades;

  public HostDaRequisicao(EnderecoEscritorioProperties propriedades) {
    this.propriedades = propriedades;
  }

  /**
   * Rótulo do endereço de escritório pelo qual a requisição chegou.
   *
   * @param request requisição
   * @return o rótulo antes do domínio base; vazio no host principal
   */
  public Optional<String> rotuloDeEscritorio(HttpServletRequest request) {
    return rotuloDeEscritorio(request.getServerName());
  }

  /**
   * Rótulo de escritório de um host.
   *
   * @param serverName host, sem porta
   * @return o rótulo antes do domínio base; vazio no host principal
   */
  public Optional<String> rotuloDeEscritorio(String serverName) {
    String host = normalizar(serverName);
    String base = propriedades.dominioBase();
    String sufixo = "." + base;
    if (host == null || host.equals(base) || host.equals("www" + sufixo)
        || !host.endsWith(sufixo) || host.length() == sufixo.length()) {
      return Optional.empty();
    }
    return Optional.of(host.substring(0, host.length() - sufixo.length()));
  }

  /** Minúsculas e sem o ponto final do nome absoluto ({@code exemplo.com.br.}). */
  static String normalizar(String host) {
    if (host == null || host.isBlank()) {
      return null;
    }
    String semPonto = host.strip().toLowerCase(Locale.ROOT);
    return semPonto.endsWith(".") ? semPonto.substring(0, semPonto.length() - 1) : semPonto;
  }
}
