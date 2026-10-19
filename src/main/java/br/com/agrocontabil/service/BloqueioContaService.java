package br.com.agrocontabil.service;

import br.com.agrocontabil.config.ForcaBrutaProperties;
import br.com.agrocontabil.model.CredencialUsuario;
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
 * <p>Regra: {@code max-falhas} erros seguidos (5) travam o login por {@code bloqueio} (15 min).
 * Durante o bloqueio a senha não é conferida — a adivinhação para, mesmo que a próxima tentativa
 * fosse a certa. Quando o bloqueio vence, o próximo erro recomeça a contagem do zero; login com
 * sucesso também zera.
 *
 * <p><strong>Resposta igual à de senha errada.</strong> O bloqueio não tem resposta própria: um
 * {@code 423} só aconteceria para e-mail com conta (e-mail inexistente nunca bloqueia) e viraria um
 * oráculo de enumeração. Quem chama responde o mesmo {@code CREDENCIAIS_INVALIDAS}.
 *
 * <p><strong>Contagem sob lock.</strong> Cada falha relê a credencial com lock pessimista numa
 * transação curta: dez tentativas paralelas contam dez. Fora do lock só o BCrypt, que nunca roda em
 * transação.
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
   * A conta está travada agora?
   *
   * @param credencial credencial lida no login
   * @return {@code true} se o bloqueio ainda não venceu
   */
  public boolean bloqueada(CredencialUsuario credencial) {
    OffsetDateTime ate = credencial.getBloqueadaAte();
    return ate != null && ate.isAfter(OffsetDateTime.now(clock));
  }

  /**
   * Conta uma senha errada e trava a conta ao atingir o limite.
   *
   * @param credencialId credencial
   */
  public void registrarFalha(UUID credencialId) {
    transacao.executeWithoutResult(status ->
        credencialRepository.findByIdForUpdate(credencialId).ifPresent(credencial -> {
          OffsetDateTime agora = OffsetDateTime.now(clock);
          boolean bloqueioVencido = credencial.getBloqueadaAte() != null
              && !credencial.getBloqueadaAte().isAfter(agora);
          int falhas = (bloqueioVencido ? 0 : credencial.getFalhasLogin()) + 1;
          credencial.setFalhasLogin(falhas);
          if (falhas >= propriedades.maxFalhas()) {
            credencial.setBloqueadaAte(agora.plus(propriedades.bloqueio()));
            log.warn("Conta bloqueada por {} senhas erradas seguidas: credencial={}, ate={}",
                falhas, credencialId, credencial.getBloqueadaAte());
          } else if (bloqueioVencido) {
            credencial.setBloqueadaAte(null);
          }
        }));
  }

  /**
   * Zera a contagem depois de um login com sucesso. Não grava nada se já estava zerada (o caso
   * comum), para o login feliz não pagar uma transação a mais.
   *
   * @param credencial credencial que acabou de entrar
   */
  public void zerarFalhas(CredencialUsuario credencial) {
    if (credencial.getFalhasLogin() == 0 && credencial.getBloqueadaAte() == null) {
      return;
    }
    transacao.executeWithoutResult(status ->
        credencialRepository.findByIdForUpdate(credencial.getId()).ifPresent(c -> {
          c.setFalhasLogin(0);
          c.setBloqueadaAte(null);
        }));
  }
}
