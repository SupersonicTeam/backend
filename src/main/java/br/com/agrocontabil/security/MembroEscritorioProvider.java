package br.com.agrocontabil.security;

import br.com.agrocontabil.exception.FuncaoNaoAutorizadaException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.FuncaoEscritorio;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * O contador autenticado como membro de um escritório (issue #228) — ponto único das checagens
 * de papel e de função das rotas do escritório.
 *
 * <p>A identidade é relida a cada requisição (JWT → credencial → contador), então a troca de
 * função ou a remoção da equipe valem já na próxima chamada.
 */
@Component
public class MembroEscritorioProvider {

  /** Todas as funções: basta ser membro. */
  private static final List<FuncaoEscritorio> QUALQUER = List.of(FuncaoEscritorio.values());

  private final UsuarioAutenticadoProvider usuarioProvider;

  public MembroEscritorioProvider(UsuarioAutenticadoProvider usuarioProvider) {
    this.usuarioProvider = usuarioProvider;
  }

  /**
   * Contador autenticado, com ou sem escritório (aceite de convite de equipe).
   *
   * @return o contador
   * @throws PapelNaoAutorizadoException se quem pede for produtor (403)
   */
  public Contador contador() {
    UsuarioAutenticado usuario = usuarioProvider.get();
    if (!usuario.isContador()) {
      throw new PapelNaoAutorizadoException(usuario.papel(), PapelUsuario.CONTADOR);
    }
    return usuario.contador();
  }

  /**
   * Contador membro de um escritório, com qualquer função.
   *
   * @return o contador, com {@code escritorioId} e {@code funcao}
   * @throws PapelNaoAutorizadoException se quem pede for produtor (403)
   * @throws FuncaoNaoAutorizadaException se o contador não tiver escritório (403)
   */
  public Contador membro() {
    return exigirFuncao(QUALQUER);
  }

  /**
   * Contador membro com uma das funções.
   *
   * @param permitidas funções que fazem a operação
   * @return o contador
   * @throws PapelNaoAutorizadoException se quem pede for produtor (403)
   * @throws FuncaoNaoAutorizadaException sem escritório ou com outra função (403)
   */
  public Contador exigirFuncao(FuncaoEscritorio... permitidas) {
    return exigirFuncao(Arrays.asList(permitidas));
  }

  private Contador exigirFuncao(List<FuncaoEscritorio> permitidas) {
    Contador contador = contador();
    if (contador.getEscritorioId() == null || contador.getFuncao() == null) {
      throw new FuncaoNaoAutorizadaException(null, permitidas);
    }
    if (!permitidas.contains(contador.getFuncao())) {
      throw new FuncaoNaoAutorizadaException(contador.getFuncao(), permitidas);
    }
    return contador;
  }
}
