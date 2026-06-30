package br.com.agrocontabil.validation;

import br.com.agrocontabil.repository.NcmReferenciaRepository;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validador de existência de NCM (issue #20, RN008).
 *
 * <p>Carrega os códigos NCM curados em um {@link Set} imutável em cache (no
 * {@link PostConstruct}, uma vez por ciclo de vida) para evitar uma consulta ao banco por item
 * validado. O cache é pequeno (subconjunto curado do agronegócio) e estável durante a execução.
 *
 * <h3>Resiliência (fail-open vs fail-closed)</h3>
 * <ul>
 *   <li><strong>Cache carregado</strong> → comportamento normal: códigos ausentes do conjunto
 *       são inválidos (<em>fail-closed</em>).</li>
 *   <li><strong>Cache não carregado</strong> (falha de infraestrutura na carga, ou contexto sem
 *       JPA como {@code @WebMvcTest}) → <em>fail-open</em>: aceita o valor para não derrubar o
 *       fluxo por indisponibilidade de uma tabela de referência. O formato/obrigatoriedade já
 *       são garantidos por {@code @Pattern}/{@code @NotBlank}.</li>
 * </ul>
 */
@Component
public class NcmValidator implements ConstraintValidator<ValidNcm, String> {

  private static final Logger log = LoggerFactory.getLogger(NcmValidator.class);

  private final NcmReferenciaRepository repository;

  /** Conjunto de códigos válidos; {@code null} enquanto o cache não foi carregado. */
  private volatile Set<String> codigosCache;

  public NcmValidator(NcmReferenciaRepository repository) {
    this.repository = repository;
  }

  /**
   * Carrega (ou recarrega) o cache de códigos NCM a partir do repositório. Em caso de falha de
   * infraestrutura, mantém o cache {@code null} (fail-open) e registra um aviso.
   */
  @PostConstruct
  public void carregarCache() {
    try {
      Set<String> codigos = Set.copyOf(repository.findAllCodigos());
      if (codigos.isEmpty()) {
        // Tabela de referência curada nunca é legitimamente vazia: vazio = não provisionada.
        // Mantém o cache null para operar em fail-open (ex.: @WebMvcTest sem JPA).
        log.warn("Tabela de referencia de NCM vazia; validacao operara em fail-open.");
        return;
      }
      this.codigosCache = codigos;
      log.info("Cache de NCM de referencia carregado: {} codigos", codigosCache.size());
    } catch (RuntimeException ex) {
      // Não derruba o contexto: o validador opera em fail-open enquanto o cache não carregar.
      log.warn("Falha ao carregar o cache de NCM de referencia; validacao operara em fail-open. "
          + "Causa: {}", ex.getMessage());
    }
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    // Convenção Bean Validation: null/blank são responsabilidade de @NotBlank/@Pattern.
    if (value == null || value.isBlank()) {
      return true;
    }

    Set<String> cache = this.codigosCache;
    if (cache == null) {
      // Fail-open: infraestrutura/cache indisponível.
      return true;
    }

    return cache.contains(value);
  }
}
