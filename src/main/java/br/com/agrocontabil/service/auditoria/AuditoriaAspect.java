package br.com.agrocontabil.service.auditoria;

import br.com.agrocontabil.dto.ResultadoAuditavel;
import br.com.agrocontabil.exception.DomainException;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.RegistroAuditoria;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Grava a auditoria dos métodos {@link Auditado} (issue #149, RNF012), com sucesso ou falha.
 *
 * <p><strong>Por fora da transação.</strong> A ordem fica acima da do advice transacional
 * ({@code LOWEST_PRECEDENCE}): o registro é gravado depois do commit da operação, numa transação
 * própria e curta. Assim há uma conexão do pool por vez (a lição da #97), e o registro de falha
 * sobrevive ao rollback da operação. Quando o método auditado roda dentro da transação de quem o
 * chamou, o registro entra nela.
 *
 * <p><strong>Nunca muda o resultado.</strong> A exceção da operação sobe intacta, o retorno volta
 * igual, e erro da própria auditoria (leitura do estado, gravação) vira log.
 *
 * <p><strong>Na falha, só o código.</strong> O {@code ErrorCode} da {@link DomainException}, ou o
 * nome da classe da exceção — nunca a mensagem, que pode carregar o valor rejeitado.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AuditoriaAspect {

  private static final Logger log = LoggerFactory.getLogger(AuditoriaAspect.class);

  private static final int LIMITE_ESTADO = 40;
  private static final int LIMITE_CODIGO = 60;

  private final AuditoriaService auditoria;
  private final EstadoRecursoLeitor estadoLeitor;
  private final UsuarioAutenticadoProvider usuarioProvider;
  private final Clock clock;

  public AuditoriaAspect(AuditoriaService auditoria, EstadoRecursoLeitor estadoLeitor,
      UsuarioAutenticadoProvider usuarioProvider, Clock clock) {
    this.auditoria = auditoria;
    this.estadoLeitor = estadoLeitor;
    this.usuarioProvider = usuarioProvider;
    this.clock = clock;
  }

  /**
   * Executa o método auditado e grava o registro.
   *
   * @param chamada  a chamada interceptada
   * @param auditado a anotação do método
   * @return o retorno do método, intacto
   * @throws Throwable a exceção do método, intacta
   */
  @Around("@annotation(auditado)")
  public Object auditar(ProceedingJoinPoint chamada, Auditado auditado) throws Throwable {
    Optional<UsuarioAutenticado> usuario = usuarioProvider.tentar();
    UUID recursoArgumento = primeiroUuid(chamada.getArgs());
    String estadoAntes = lerEstado(auditado.recurso(), recursoArgumento);

    Object resultado;
    try {
      resultado = chamada.proceed();
    } catch (Throwable erro) {
      // Sem o estado lido antes: na falha (ex.: 404 de nota alheia) ele seria de recurso que a
      // operacao recusou.
      auditoria.registrar(base(auditado, usuario, recursoArgumento, null)
          .sucesso(false)
          .codigoErro(codigo(erro))
          .build());
      throw erro;
    }

    ResultadoAuditavel auditavel = resultado instanceof ResultadoAuditavel r ? r : null;
    UUID recurso = recursoArgumento == null && auditavel != null
        ? auditavel.recursoAuditado() : recursoArgumento;
    auditoria.registrar(base(auditado, usuario, recurso, auditavel)
        .sucesso(true)
        .estadoAntes(estadoAntes)
        .estadoDepois(estadoDepois(resultado, auditavel))
        .build());
    return resultado;
  }

  private RegistroAuditoria.RegistroAuditoriaBuilder base(Auditado auditado,
      Optional<UsuarioAutenticado> usuario, UUID recurso, ResultadoAuditavel auditavel) {
    UUID usuarioId = usuario.map(UsuarioAutenticado::id).orElse(null);
    return RegistroAuditoria.builder()
        .ocorridoEm(OffsetDateTime.now(clock))
        .acao(auditado.acao())
        .recurso(auditado.recurso())
        // Operacao sobre a propria conta: o recurso e o titular.
        .recursoId(recurso == null && auditado.recurso() == RecursoAuditado.CONTA
            ? usuarioId : recurso)
        .usuarioId(usuarioId)
        .papel(usuario.map(u -> u.papel().name()).orElse(null))
        .produtorId(produtorAfetado(usuario, auditavel));
  }

  /** O produtor que agiu; para o contador, o dono do recurso (quando o retorno informa). */
  private static UUID produtorAfetado(Optional<UsuarioAutenticado> usuario,
      ResultadoAuditavel auditavel) {
    if (usuario.isPresent() && !usuario.get().isContador()) {
      return usuario.get().id();
    }
    return auditavel == null ? null : auditavel.produtorAuditado();
  }

  private String lerEstado(RecursoAuditado recurso, UUID id) {
    if (id == null) {
      return null;
    }
    try {
      return estadoLeitor.estado(recurso, id).map(AuditoriaAspect::limitarEstado).orElse(null);
    } catch (RuntimeException e) {
      // A operacao ainda vai rodar e dizer o que houve; so o "antes" fica de fora.
      log.warn("Auditoria sem o estado anterior: recurso={}, id={}", recurso, id, e);
      return null;
    }
  }

  private static String estadoDepois(Object resultado, ResultadoAuditavel auditavel) {
    if (auditavel != null && auditavel.status() != null) {
      return limitarEstado(auditavel.status().toString());
    }
    if (resultado instanceof Collection<?> itens) {
      return "itens=" + itens.size();
    }
    return null;
  }

  private static String codigo(Throwable erro) {
    String codigo = erro instanceof DomainException dominio && !dominio.getErrors().isEmpty()
        ? String.valueOf(dominio.getErrors().get(0).code())
        : erro.getClass().getSimpleName();
    return codigo.length() > LIMITE_CODIGO ? codigo.substring(0, LIMITE_CODIGO) : codigo;
  }

  private static UUID primeiroUuid(Object[] argumentos) {
    for (Object argumento : argumentos) {
      if (argumento instanceof UUID id) {
        return id;
      }
    }
    return null;
  }

  private static String limitarEstado(String estado) {
    return estado.length() > LIMITE_ESTADO ? estado.substring(0, LIMITE_ESTADO) : estado;
  }
}
