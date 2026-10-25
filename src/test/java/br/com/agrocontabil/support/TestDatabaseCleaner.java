package br.com.agrocontabil.support;

import br.com.agrocontabil.repository.AlertaTributarioRepository;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.AssistenteInteracaoRepository;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ConviteCarteiraRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.EtiquetaCarteiraRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.RedefinicaoSenhaRepository;
import br.com.agrocontabil.repository.RegistroAuditoriaRepository;
import br.com.agrocontabil.repository.SessaoRefreshRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import java.time.OffsetDateTime;
import org.springframework.boot.test.context.TestComponent;

/**
 * Limpa as tabelas de domínio do H2 de teste na ordem que respeita as foreign keys.
 *
 * <p>Desde a issue #90 o schema de teste replica as FKs de produção ({@code RESTRICT} em
 * {@code produtores}). O H2 em memória é compartilhado entre contextos Spring
 * ({@code DB_CLOSE_DELAY=-1}), então um teste não transacional pode deixar apurações, guias ou
 * notas do produtor mock para trás — e o {@code produtorRepository.deleteAll()} do teste seguinte
 * passa a falhar por integridade referencial. Este helper centraliza a ordem correta em vez de
 * cada teste repetir (e esquecer) a sequência.
 *
 * <p>É um {@link TestComponent}: não entra no component scan da aplicação; importe explicitamente
 * com {@code @Import(TestDatabaseCleaner.class)}.
 */
@TestComponent
public class TestDatabaseCleaner {

  private final AlertaTributarioRepository alertaRepository;
  private final AssistenteInteracaoRepository interacaoRepository;
  private final NotaFiscalValidacaoRepository validacaoRepository;
  private final ApuracaoMensalRepository apuracaoRepository;
  private final GuiaFiscalRepository guiaFiscalRepository;
  private final NotaFiscalRepository notaFiscalRepository;
  private final SessaoRefreshRepository sessaoRefreshRepository;
  private final RedefinicaoSenhaRepository redefinicaoSenhaRepository;
  private final CredencialUsuarioRepository credencialRepository;
  private final ContadorRepository contadorRepository;
  private final ProdutorRepository produtorRepository;
  private final RegistroAuditoriaRepository auditoriaRepository;
  private final ConviteCarteiraRepository conviteRepository;
  private final VinculoCarteiraRepository vinculoRepository;
  private final EtiquetaCarteiraRepository etiquetaRepository;

  /** Construtor com todos os repositórios das tabelas dependentes de {@code produtores}. */
  public TestDatabaseCleaner(
      AlertaTributarioRepository alertaRepository,
      AssistenteInteracaoRepository interacaoRepository,
      NotaFiscalValidacaoRepository validacaoRepository,
      ApuracaoMensalRepository apuracaoRepository,
      GuiaFiscalRepository guiaFiscalRepository,
      NotaFiscalRepository notaFiscalRepository,
      SessaoRefreshRepository sessaoRefreshRepository,
      RedefinicaoSenhaRepository redefinicaoSenhaRepository,
      CredencialUsuarioRepository credencialRepository,
      ContadorRepository contadorRepository,
      ProdutorRepository produtorRepository,
      RegistroAuditoriaRepository auditoriaRepository,
      ConviteCarteiraRepository conviteRepository,
      VinculoCarteiraRepository vinculoRepository,
      EtiquetaCarteiraRepository etiquetaRepository) {
    this.alertaRepository = alertaRepository;
    this.interacaoRepository = interacaoRepository;
    this.validacaoRepository = validacaoRepository;
    this.apuracaoRepository = apuracaoRepository;
    this.guiaFiscalRepository = guiaFiscalRepository;
    this.notaFiscalRepository = notaFiscalRepository;
    this.sessaoRefreshRepository = sessaoRefreshRepository;
    this.redefinicaoSenhaRepository = redefinicaoSenhaRepository;
    this.credencialRepository = credencialRepository;
    this.contadorRepository = contadorRepository;
    this.produtorRepository = produtorRepository;
    this.auditoriaRepository = auditoriaRepository;
    this.conviteRepository = conviteRepository;
    this.vinculoRepository = vinculoRepository;
    this.etiquetaRepository = etiquetaRepository;
  }

  /**
   * Apaga filhos antes dos pais: alertas tributários → auditoria da Ceres → validações e itens
   * (cascade da entidade) → apurações → guias → notas → carteira (convites, vínculos,
   * etiquetas — #177) → contadores → produtores.
   *
   * <p>Os contadores (issue #93) entram antes dos produtores porque o vínculo N:N referencia os
   * dois: deixá-los para trás faria o {@code produtorRepository.deleteAll()} falhar por integridade
   * referencial no schema de teste, que replica as FKs de produção. A auditoria da Ceres (issue
   * #144) referencia o produtor com {@code RESTRICT} pelo mesmo motivo.
   */
  public void limparTudo() {
    // Alertas (#140) antes de tudo: CASCADE no banco, mas no mesmo flush o Hibernate anularia a
    // FK NOT NULL do alerta para remover o produtor.
    alertaRepository.deleteAll();
    interacaoRepository.deleteAll();
    validacaoRepository.deleteAll();
    apuracaoRepository.deleteAll();
    guiaFiscalRepository.deleteAll();
    notaFiscalRepository.deleteAll();
    // Credenciais (#205): CASCADE no banco, mas nem todo contexto de teste roda o script que cria
    // as FKs; sem elas a credencial de um produtor apagado sobraria e o e-mail ficaria ocupado.
    sessaoRefreshRepository.deleteAll();
    redefinicaoSenhaRepository.deleteAll();
    credencialRepository.deleteAll();
    // Carteira (#177): o vinculo leva as linhas de etiqueta (lado dono do N:N); depois o catalogo.
    conviteRepository.deleteAll();
    vinculoRepository.deleteAll();
    etiquetaRepository.deleteAll();
    contadorRepository.deleteAll();
    produtorRepository.deleteAll();
    // Auditoria (#149): sem FK (sobrevive ao que audita); limpa para um teste nao ver o do outro.
    auditoriaRepository.apagarAnterioresA(OffsetDateTime.now().plusYears(100));
  }
}
