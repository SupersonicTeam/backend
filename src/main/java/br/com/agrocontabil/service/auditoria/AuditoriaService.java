package br.com.agrocontabil.service.auditoria;

import br.com.agrocontabil.dto.RegistroAuditoriaResponse;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.mapper.RegistroAuditoriaMapper;
import br.com.agrocontabil.model.RegistroAuditoria;
import br.com.agrocontabil.repository.RegistroAuditoriaRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Auditoria das operações críticas (issue #149, RNF012): grava e consulta.
 *
 * <p><strong>Gravação nunca derruba a operação.</strong> O {@link AuditoriaAspect} grava depois que
 * a operação terminou — no caminho feliz, depois do commit dela. Uma falha aqui (banco fora no
 * instante seguinte) devolveria erro ao cliente por uma nota já emitida, e ele tentaria de novo;
 * por isso ela é logada em {@code ERROR} e engolida.
 *
 * <p><strong>Consulta só do contador</strong>, e só dos produtores da carteira
 * ({@link ProdutorEmContextoResolver}: fora dela, 404).
 */
@Service
public class AuditoriaService {

  private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);

  /** Padrão de itens por página. */
  public static final int TAMANHO_PAGINA_PADRAO = 50;

  /** Teto de itens por página. */
  public static final int TAMANHO_PAGINA_MAXIMO = 200;

  /** Teto da página: {@code page × size} precisa caber num {@code int} (mesmo motivo das guias). */
  public static final int PAGINA_MAXIMA = 10_000;

  /** Mais recentes primeiro; o id desempata (nenhum registro pulado ou repetido entre páginas). */
  private static final Sort ORDEM = Sort.by(Sort.Order.desc("ocorridoEm"), Sort.Order.desc("id"));

  private final RegistroAuditoriaRepository repository;
  private final UsuarioAutenticadoProvider usuarioProvider;
  private final ProdutorEmContextoResolver produtorEmContexto;
  private final TransactionTemplate transacao;

  public AuditoriaService(RegistroAuditoriaRepository repository,
      UsuarioAutenticadoProvider usuarioProvider, ProdutorEmContextoResolver produtorEmContexto,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.usuarioProvider = usuarioProvider;
    this.produtorEmContexto = produtorEmContexto;
    this.transacao = new TransactionTemplate(transactionManager);
  }

  /**
   * Grava um registro. Não lança: a falha vira log.
   *
   * @param registro o registro montado pelo aspecto
   */
  public void registrar(RegistroAuditoria registro) {
    try {
      transacao.executeWithoutResult(status -> repository.save(registro));
    } catch (RuntimeException e) {
      log.error("Falha ao gravar auditoria: acao={}, recurso={}, recursoId={}", registro.getAcao(),
          registro.getRecurso(), registro.getRecursoId(), e);
    }
  }

  /**
   * Registros de auditoria de um produtor da carteira do contador autenticado.
   *
   * @param produtorId produtor
   * @param pagina     página, a partir de 0
   * @param tamanho    itens por página
   * @return a página, mais recentes primeiro
   * @throws PapelNaoAutorizadoException se quem pede não for contador (403)
   * @throws br.com.agrocontabil.exception.ResourceNotFoundException se o produtor não for da
   *     carteira (404)
   */
  @Transactional(readOnly = true)
  public Page<RegistroAuditoriaResponse> listar(UUID produtorId, int pagina, int tamanho) {
    UsuarioAutenticado usuario = usuarioProvider.get();
    if (!usuario.isContador()) {
      throw new PapelNaoAutorizadoException(usuario.papel(), PapelUsuario.CONTADOR);
    }
    produtorEmContexto.exigirAcesso(produtorId);
    return repository.findByProdutorId(produtorId, PageRequest.of(pagina, tamanho, ORDEM))
        .map(RegistroAuditoriaMapper::toResponse);
  }
}
