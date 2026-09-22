package br.com.agrocontabil.support;

import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
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

  private final NotaFiscalValidacaoRepository validacaoRepository;
  private final ApuracaoMensalRepository apuracaoRepository;
  private final GuiaFiscalRepository guiaFiscalRepository;
  private final NotaFiscalRepository notaFiscalRepository;
  private final ProdutorRepository produtorRepository;

  /** Construtor com todos os repositórios das tabelas dependentes de {@code produtores}. */
  public TestDatabaseCleaner(
      NotaFiscalValidacaoRepository validacaoRepository,
      ApuracaoMensalRepository apuracaoRepository,
      GuiaFiscalRepository guiaFiscalRepository,
      NotaFiscalRepository notaFiscalRepository,
      ProdutorRepository produtorRepository) {
    this.validacaoRepository = validacaoRepository;
    this.apuracaoRepository = apuracaoRepository;
    this.guiaFiscalRepository = guiaFiscalRepository;
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorRepository = produtorRepository;
  }

  /**
   * Apaga filhos antes dos pais: validações e itens (cascade da entidade) → apurações → guias →
   * notas → produtores.
   */
  public void limparTudo() {
    validacaoRepository.deleteAll();
    apuracaoRepository.deleteAll();
    guiaFiscalRepository.deleteAll();
    notaFiscalRepository.deleteAll();
    produtorRepository.deleteAll();
  }
}
