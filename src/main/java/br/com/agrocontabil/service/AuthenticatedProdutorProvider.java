package br.com.agrocontabil.service;

import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import org.springframework.stereotype.Component;

/**
 * Fornece o {@link Produtor} autenticado — atalho sobre o {@link UsuarioAutenticadoProvider}.
 *
 * <p>Desde a issue #93 o principal da requisição é um {@link UsuarioAutenticado}, que pode ser um
 * produtor ou um contador. Este componente continua existindo porque a maioria dos serviços
 * (nota fiscal, apuração, dashboard, crédito, SEFAZ…) só faz sentido no contexto de <em>um</em>
 * produtor — eles perguntam "quem é o produtor desta requisição?" e não deveriam saber que existe
 * um segundo papel.
 *
 * <p><strong>Contador cai em 403, não em 401.</strong> O contador está autenticado; o que falta é
 * o produtor-alvo. Enquanto o escopo por papel não chega a estes serviços, responder 403 com
 * {@link PapelNaoAutorizadoException} diz exatamente isso — antes da #93 um contador autenticado
 * viraria 401 ("sem identidade"), que é falso e manda o cliente depurar o lugar errado.
 *
 * <p>Em testes unitários de service (Mockito puro), este componente é mockado, evitando dependência
 * do contexto Spring Security.
 */
@Component
public class AuthenticatedProdutorProvider {

  private final UsuarioAutenticadoProvider usuarioProvider;

  public AuthenticatedProdutorProvider(UsuarioAutenticadoProvider usuarioProvider) {
    this.usuarioProvider = usuarioProvider;
  }

  /**
   * Retorna o produtor autenticado na requisição atual.
   *
   * @return produtor autenticado
   * @throws br.com.agrocontabil.exception.NaoAutenticadoException se não houver identidade válida
   *     no contexto (HTTP 401)
   * @throws PapelNaoAutorizadoException se quem está autenticado for um contador (HTTP 403)
   */
  public Produtor get() {
    UsuarioAutenticado usuario = usuarioProvider.get();
    if (usuario.isContador()) {
      throw new PapelNaoAutorizadoException(usuario.papel(), PapelUsuario.PRODUTOR);
    }
    return usuario.produtor();
  }
}
