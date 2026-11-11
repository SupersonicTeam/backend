package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ResultadoAuditavel;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.MetodoVerificacaoCnpj;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verificação do CNPJ do escritório (issue #520): a GWNext confere fora do sistema que o CNPJ
 * gravado é do escritório e marca aqui. Só o escritório verificado tem o endereço próprio servido
 * ({@link EnderecoEscritorioService}).
 *
 * <p><strong>Sem endpoint.</strong> Na v1 a verificação é manual, pelo comando do profile
 * {@code verificacao-cnpj} ({@code config/VerificacaoCnpjRunner}); o seed da demonstração marca o
 * escritório fictício da banca. Nenhuma rota da API chama este serviço.
 *
 * <p><strong>O CNPJ conferido vem junto.</strong> O operador informa o CNPJ que conferiu, e ele
 * tem de ser o gravado no momento da marca, sob lock: se o administrador trocou o CNPJ entre a
 * conferência e o comando, a marca é recusada (senão um CNPJ não conferido sairia verificado).
 *
 * <p><strong>Um CNPJ verificado em um escritório só.</strong> O segundo é recusado aqui e, no
 * PostgreSQL, também pelo índice {@code uq_escritorio_cnpj_verificado}. Quem decide entre os dois
 * é a contestação, fora do sistema: revogar um deles e verificar o outro. O cadastro e o
 * {@code PATCH} nunca recusam CNPJ repetido, para não virar oráculo de CNPJ alheio.
 *
 * <p><strong>Auditado, sem o CNPJ.</strong> A auditoria guarda o escritório e o método (antes e
 * depois); o CNPJ não vai ao registro nem ao log.
 */
@Service
public class VerificacaoCnpjService {

  private static final Logger log = LoggerFactory.getLogger(VerificacaoCnpjService.class);

  /** Estado auditado do escritório sem verificação. */
  static final String NAO_VERIFICADO = "NAO_VERIFICADO";

  private final EscritorioRepository escritorioRepository;
  private final Clock clock;
  private final TransactionTemplate transacao;

  public VerificacaoCnpjService(EscritorioRepository escritorioRepository, Clock clock,
      PlatformTransactionManager transactionManager) {
    this.escritorioRepository = escritorioRepository;
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
  }

  /**
   * Marca como verificado o CNPJ do escritório. Já verificado com o mesmo CNPJ: nada muda.
   *
   * @param escritorioId  escritório
   * @param cnpjConferido CNPJ que o operador conferiu (com ou sem máscara)
   * @param metodo        como foi conferido
   * @return o escritório e o método antes e depois, para a auditoria
   * @throws VerificacaoRecusadaException escritório inexistente, sem CNPJ, com outro CNPJ ou com o
   *                                      CNPJ já verificado em outro escritório
   */
  @Auditado(acao = AcaoAuditada.ESCRITORIO_CNPJ_VERIFICADO, recurso = RecursoAuditado.ESCRITORIO)
  public ResultadoVerificacao verificar(UUID escritorioId, String cnpjConferido,
      MetodoVerificacaoCnpj metodo) {
    Objects.requireNonNull(metodo, "metodo");
    String conferido = cnpjConferido == null ? "" : cnpjConferido.replaceAll("\\D", "");
    try {
      return transacao.execute(status -> {
        Escritorio escritorio = travado(escritorioId);
        String cnpj = escritorio.getCnpj();
        if (cnpj == null || cnpj.isBlank()) {
          throw new VerificacaoRecusadaException(Motivo.SEM_CNPJ);
        }
        if (!cnpj.equals(conferido)) {
          throw new VerificacaoRecusadaException(Motivo.CNPJ_DIFERENTE_DO_CONFERIDO);
        }
        String antes = estado(escritorio);
        if (escritorio.cnpjVerificado()) {
          return new ResultadoVerificacao(escritorioId, antes, antes);
        }
        if (escritorioRepository.existsByCnpjAndCnpjVerificadoEmIsNotNullAndIdNot(cnpj,
            escritorioId)) {
          throw new VerificacaoRecusadaException(Motivo.CNPJ_VERIFICADO_EM_OUTRO_ESCRITORIO);
        }
        escritorio.verificarCnpj(metodo, OffsetDateTime.now(clock));
        escritorioRepository.saveAndFlush(escritorio);
        log.info("CNPJ do escritorio verificado: escritorio={}, metodo={}", escritorioId, metodo);
        return new ResultadoVerificacao(escritorioId, antes, metodo.name());
      });
    } catch (DataIntegrityViolationException e) {
      // Duas marcas do mesmo CNPJ ao mesmo tempo: o indice unico parcial (PostgreSQL) decide.
      throw new VerificacaoRecusadaException(Motivo.CNPJ_VERIFICADO_EM_OUTRO_ESCRITORIO);
    }
  }

  /**
   * Tira a verificação do CNPJ (contestação, CNPJ que não era do escritório): o endereço próprio
   * deixa de ser servido na hora. Sem verificação: nada muda.
   *
   * @param escritorioId escritório
   * @return o escritório e o método antes e depois, para a auditoria
   * @throws VerificacaoRecusadaException escritório inexistente
   */
  @Auditado(acao = AcaoAuditada.ESCRITORIO_CNPJ_VERIFICACAO_REVOGADA,
      recurso = RecursoAuditado.ESCRITORIO)
  public ResultadoVerificacao revogar(UUID escritorioId) {
    return transacao.execute(status -> {
      Escritorio escritorio = travado(escritorioId);
      String antes = estado(escritorio);
      if (escritorio.cnpjVerificado()) {
        escritorio.revogarVerificacaoCnpj();
        escritorioRepository.saveAndFlush(escritorio);
        log.info("Verificacao do CNPJ do escritorio revogada: escritorio={}", escritorioId);
      }
      return new ResultadoVerificacao(escritorioId, antes, NAO_VERIFICADO);
    });
  }

  private Escritorio travado(UUID escritorioId) {
    return escritorioRepository.findByIdForUpdate(escritorioId)
        .orElseThrow(() -> new VerificacaoRecusadaException(Motivo.ESCRITORIO_INEXISTENTE));
  }

  private static String estado(Escritorio escritorio) {
    return escritorio.cnpjVerificado() ? escritorio.getCnpjVerificacaoMetodo().name()
        : NAO_VERIFICADO;
  }

  /** Por que a marca foi recusada; o texto vai só ao log do operador, nunca ao cliente. */
  public enum Motivo {
    ESCRITORIO_INEXISTENTE("escritorio inexistente: confira o id"),
    SEM_CNPJ("o escritorio nao tem CNPJ gravado"),
    CNPJ_DIFERENTE_DO_CONFERIDO("o CNPJ gravado nao e o conferido: o administrador pode ter "
        + "trocado o CNPJ; confira de novo"),
    CNPJ_VERIFICADO_EM_OUTRO_ESCRITORIO("o CNPJ ja esta verificado em outro escritorio: "
        + "contestacao (revogue um antes de verificar o outro)");

    private final String descricao;

    Motivo(String descricao) {
      this.descricao = descricao;
    }

    /**
     * Texto para o log do operador, sem o CNPJ.
     *
     * @return a descrição
     */
    public String descricao() {
      return descricao;
    }
  }

  /**
   * Marca recusada. Vai à auditoria pelo nome da classe e ao log do operador pelo
   * {@link Motivo}; o comando termina com código de saída diferente de zero.
   */
  public static class VerificacaoRecusadaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Motivo motivo;

    /**
     * Recusa pelo motivo dado.
     *
     * @param motivo por que a marca foi recusada
     */
    public VerificacaoRecusadaException(Motivo motivo) {
      super(motivo.descricao());
      this.motivo = motivo;
    }

    /**
     * Por que foi recusada.
     *
     * @return o motivo
     */
    public Motivo motivo() {
      return motivo;
    }
  }

  /**
   * Resultado da marca ou da revogação: para a auditoria, o escritório e o estado antes e depois
   * ({@code NAO_VERIFICADO} ou o método), nunca o CNPJ.
   *
   * @param escritorioId escritório
   * @param antes        estado antes
   * @param depois       estado depois
   */
  public record ResultadoVerificacao(UUID escritorioId, String antes, String depois)
      implements ResultadoAuditavel {

    @Override
    public Object status() {
      return depois;
    }

    @Override
    public Object statusAnterior() {
      return antes;
    }

    @Override
    public UUID recursoAuditado() {
      return escritorioId;
    }

    /**
     * A operação mudou o escritório?
     *
     * @return {@code true} se o estado mudou
     */
    public boolean mudou() {
      return !antes.equals(depois);
    }
  }
}
