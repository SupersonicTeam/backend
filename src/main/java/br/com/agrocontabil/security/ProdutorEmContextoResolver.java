package br.com.agrocontabil.security;

import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Responde quem é o <strong>produtor em contexto</strong> de uma requisição que nomeia um
 * {@code produtorId} — issue #93.
 *
 * <p>É o ponto único de autorização dos endpoints que recebem o produtor-alvo explícito
 * ({@code /api/contador/guias}, {@code /api/calendario-fiscal/{produtorId}/...}). A regra depende
 * do papel:
 *
 * <ul>
 *   <li><strong>Produtor</strong> — o alvo precisa ser ele mesmo. É o guard provisório que a issue
 *       #94 colocou dentro de cada serviço, agora num lugar só;
 *   <li><strong>Contador</strong> — o alvo precisa estar na carteira dele
 *       ({@code contador_produtor}). O vínculo é a única autorização que o contador tem sobre os
 *       dados de um produtor.
 * </ul>
 *
 * <p><strong>Sempre 404, nunca 403.</strong> Aqui a pergunta é "este produtor é acessível por
 * você?", e responder 403 confirmaria que aquele produtor existe — mesmo raciocínio que o resto da
 * API já aplica a notas e guias. O 403 fica reservado para "seu papel não faz este tipo de
 * operação" ({@code PapelNaoAutorizadoException}), que não revela dado de terceiro.
 */
@Component
public class ProdutorEmContextoResolver {

  private final UsuarioAutenticadoProvider usuarioProvider;
  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;

  public ProdutorEmContextoResolver(
      UsuarioAutenticadoProvider usuarioProvider,
      ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository) {
    this.usuarioProvider = usuarioProvider;
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
  }

  /**
   * Exige que a identidade autenticada possa agir sobre o produtor informado.
   *
   * <p>Não carrega o produtor: para listagens e consultas basta a autorização, e o {@code
   * produtorId} já é o filtro da query.
   *
   * @param produtorId produtor-alvo declarado na requisição
   * @throws ResourceNotFoundException se o alvo não for o próprio produtor autenticado, ou não
   *     estiver na carteira do contador autenticado (404, nunca 403)
   */
  @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
  public void exigirAcesso(UUID produtorId) {
    UsuarioAutenticado usuario = usuarioProvider.get();

    boolean autorizado =
        usuario.isContador()
            ? contadorRepository.existsByIdAndProdutoresId(usuario.contador().getId(), produtorId)
            : usuario.produtor().getId().equals(produtorId);

    if (!autorizado) {
      throw new ResourceNotFoundException("Produtor", produtorId);
    }
  }

  /**
   * Resolve o produtor-alvo da requisição, já autorizado, como entidade.
   *
   * <p>Use quando a operação precisa do produtor em si (uma escrita que o referencia). Para
   * consultas filtradas por id, prefira {@link #exigirAcesso(UUID)} e poupe a busca.
   *
   * <p>Busca sempre no repositório, mesmo quando o alvo é o próprio produtor autenticado: o
   * principal foi carregado noutro contexto de persistência (o filtro de identidade) e está
   * <em>detached</em>. Devolver a instância gerenciada é o que permite usá-la numa escrita sem
   * depender de o Hibernate reaproveitar só o id.
   *
   * @param produtorId produtor-alvo declarado na requisição
   * @return o produtor correspondente
   * @throws ResourceNotFoundException se o acesso não for autorizado <em>ou</em> se o produtor não
   *     existir — 404 nos dois casos, para não distinguir um do outro
   */
  @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
  public Produtor resolver(UUID produtorId) {
    exigirAcesso(produtorId);
    return produtorRepository
        .findById(produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("Produtor", produtorId));
  }
}
