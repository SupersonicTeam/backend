package br.com.agrocontabil.service;

import br.com.agrocontabil.common.MascaraDadosPessoais;
import br.com.agrocontabil.config.ForcaBrutaProperties;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bloqueio temporário da conta por senhas erradas seguidas (issue #208).
 *
 * <p>Regra: {@code max-falhas} tentativas sem sucesso (5) travam o login por {@code bloqueio}
 * (15 min). Durante o bloqueio a senha não é conferida — a adivinhação para, mesmo que a próxima
 * tentativa fosse a certa. Quando o bloqueio vence, a contagem recomeça do zero; login com sucesso
 * também zera.
 *
 * <p><strong>Reserva antes do BCrypt.</strong> Cada tentativa é <em>contada antes</em> de a senha
 * ser conferida, sob lock pessimista, numa transação curta. Contar depois (o desenho inicial) tinha
 * uma corrida apontada no review de segurança: N requisições paralelas liam "não bloqueada" antes
 * de qualquer falha ser gravada, e todas tinham a senha conferida — o teto de 5 virava o teto de
 * concorrência do servidor. Com a reserva, as paralelas consomem a cota na fila do lock, e a sexta
 * em diante encontra a conta travada sem chegar ao BCrypt. O sucesso devolve a cota
 * ({@link #liberar}).
 *
 * <p><strong>Resposta igual à de senha errada.</strong> O bloqueio não tem resposta própria: um
 * {@code 423} só aconteceria para e-mail com conta (e-mail inexistente nunca bloqueia) e viraria um
 * oráculo de enumeração. Pelo mesmo motivo o e-mail sem conta faz uma reserva fictícia
 * ({@link #reservarFicticia}) com o mesmo custo de banco.
 *
 * <p>O bloqueio de conta permite travar a conta de outra pessoa errando a senha dela de propósito.
 * O efeito é temporário e o limite por IP ({@code LimitadorTaxa}) restringe quantas contas um
 * mesmo cliente consegue travar.
 */
@Service
public class BloqueioContaService {

  private static final Logger log = LoggerFactory.getLogger(BloqueioContaService.class);

  private final CredencialUsuarioRepository credencialRepository;
  private final ForcaBrutaProperties propriedades;
  private final Clock clock;
  private final TransactionTemplate transacao;

  public BloqueioContaService(
      CredencialUsuarioRepository credencialRepository,
      ForcaBrutaProperties propriedades,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.credencialRepository = credencialRepository;
    this.propriedades = propriedades;
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
  }

  /**
   * Reserva uma tentativa de login: conta-a como falha e trava a conta ao atingir o limite.
   *
   * @param credencialId credencial
   * @return {@code true} se a senha pode ser conferida; {@code false} se a conta está travada
   */
  public boolean reservarTentativa(UUID credencialId) {
    Boolean liberada = transacao.execute(status ->
        credencialRepository.findByIdForUpdate(credencialId).map(credencial -> {
          OffsetDateTime agora = OffsetDateTime.now(clock);
          OffsetDateTime ate = credencial.getBloqueadaAte();
          if (ate != null && ate.isAfter(agora)) {
            return false;
          }
          // Bloqueio vencido: recomeca do zero, sem travar de novo no primeiro erro.
          int tentativas = (ate != null ? 0 : credencial.getFalhasLogin()) + 1;
          credencial.setFalhasLogin(tentativas);
          credencial.setBloqueadaAte(null);
          if (tentativas >= propriedades.maxFalhas()) {
            credencial.setBloqueadaAte(agora.plus(propriedades.bloqueio()));
            log.warn("Conta bloqueada apos {} tentativas sem sucesso: credencial={}, ate={}",
                tentativas, MascaraDadosPessoais.referencia(credencialId),
                credencial.getBloqueadaAte());
          }
          return true;
        }).orElse(false));
    return Boolean.TRUE.equals(liberada);
  }

  /**
   * Mesmo custo de banco de {@link #reservarTentativa} para o e-mail que não tem conta: sem isso,
   * a transação a mais do caminho com conta seria mensurável e denunciaria quem tem cadastro.
   */
  public void reservarFicticia() {
    transacao.executeWithoutResult(status ->
        credencialRepository.findByIdForUpdate(UUID.randomUUID()));
  }

  /**
   * Devolve a cota depois de um login com sucesso: zera a contagem e o bloqueio.
   *
   * @param credencialId credencial que acabou de entrar
   */
  public void liberar(UUID credencialId) {
    transacao.executeWithoutResult(status ->
        credencialRepository.findByIdForUpdate(credencialId).ifPresent(c -> {
          c.setFalhasLogin(0);
          c.setBloqueadaAte(null);
        }));
  }
}
