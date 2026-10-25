package br.com.agrocontabil.security;

import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

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
 *   <li><strong>Contador</strong> — o alvo precisa estar na carteira do escritório dele com
 *       vínculo {@code ATIVO} ({@code carteira_vinculo}, issues #177 e #228). O vínculo é a
 *       única autorização que o contador tem sobre os dados de um produtor; suspenso, encerrado
 *       ou contador sem escritório, o produtor some.
 * </ul>
 *
 * <p>Sem transação própria: é sempre chamado de dentro de um serviço transacional e participa da
 * transação dele. Declarar {@code readOnly} aqui seria decorativo — Spring só aplica esse flag ao
 * <em>iniciar</em> uma transação, não ao entrar numa já aberta.
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
  private final VinculoCarteiraRepository vinculoRepository;

  public ProdutorEmContextoResolver(
      UsuarioAutenticadoProvider usuarioProvider,
      ProdutorRepository produtorRepository,
      VinculoCarteiraRepository vinculoRepository) {
    this.usuarioProvider = usuarioProvider;
    this.produtorRepository = produtorRepository;
    this.vinculoRepository = vinculoRepository;
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
  public void exigirAcesso(UUID produtorId) {
    UsuarioAutenticado usuario = usuarioProvider.get();

    boolean autorizado =
        usuario.isContador()
            ? atendeAtivo(usuario, produtorId)
            : usuario.produtor().getId().equals(produtorId);

    if (!autorizado) {
      throw new ResourceNotFoundException("Produtor", produtorId);
    }
  }

  /**
   * Exige um <strong>contador</strong> com vínculo ativo com o produtor — o padrão das rotas
   * {@code /api/contador/produtores/{produtorId}/...} (issue #177).
   *
   * <p>Ali o produtor não entra nem para os próprios dados: ele tem as rotas sem
   * {@code produtorId}.
   *
   * @param produtorId produtor-alvo do path
   * @return o contador autenticado
   * @throws PapelNaoAutorizadoException se quem pede não for contador (403)
   * @throws ResourceNotFoundException se o produtor não estiver na carteira ativa (404)
   */
  public UsuarioAutenticado exigirContadorComAcesso(UUID produtorId) {
    UsuarioAutenticado usuario = usuarioProvider.get();
    if (!usuario.isContador()) {
      throw new PapelNaoAutorizadoException(usuario.papel(), PapelUsuario.CONTADOR);
    }
    if (!atendeAtivo(usuario, produtorId)) {
      throw new ResourceNotFoundException("Produtor", produtorId);
    }
    return usuario;
  }

  /** O produtor está na carteira ativa do escritório do contador (#228); sem escritório, não. */
  private boolean atendeAtivo(UsuarioAutenticado contador, UUID produtorId) {
    UUID escritorioId = contador.contador().getEscritorioId();
    return escritorioId != null && vinculoRepository.existsByEscritorioIdAndProdutorIdAndSituacao(
        escritorioId, produtorId, SituacaoVinculo.ATIVO);
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
  public Produtor resolver(UUID produtorId) {
    exigirAcesso(produtorId);
    return produtorRepository
        .findById(produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("Produtor", produtorId));
  }
}
