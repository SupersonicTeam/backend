package br.com.agrocontabil.validation;

import br.com.agrocontabil.repository.CfopReferenciaRepository;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validador de existência/aplicabilidade de CFOP (issue #20, RN008).
 *
 * <p>Carrega em cache apenas os CFOPs marcados como aplicáveis ao produtor rural. CFOPs
 * inexistentes ou existentes-porém-inaplicáveis são rejeitados. Mesma estratégia de cache e
 * resiliência (fail-open quando o cache não carrega) do {@link NcmValidator}.
 */
@Component
public class CfopValidator implements ConstraintValidator<ValidCfop, String> {

  private static final Logger log = LoggerFactory.getLogger(CfopValidator.class);

  private final CfopReferenciaRepository repository;

  /** Conjunto de códigos válidos; {@code null} enquanto o cache não foi carregado. */
  private volatile Set<String> codigosCache;

  public CfopValidator(CfopReferenciaRepository repository) {
    this.repository = repository;
  }

  /**
   * Carrega (ou recarrega) o cache de CFOPs aplicáveis ao produtor rural. Em caso de falha de
   * infraestrutura, mantém o cache {@code null} (fail-open) e registra um aviso.
   */
  @PostConstruct
  public void carregarCache() {
    try {
      Set<String> codigos = Set.copyOf(repository.findCodigosAplicaveisProdutorRural());
      if (codigos.isEmpty()) {
        // Tabela de referência curada nunca é legitimamente vazia: vazio = não provisionada.
        log.warn("Tabela de referencia de CFOP vazia; validacao operara em fail-open.");
        return;
      }
      this.codigosCache = codigos;
      log.info("Cache de CFOP de referencia carregado: {} codigos aplicaveis ao produtor rural",
          codigosCache.size());
    } catch (RuntimeException ex) {
      log.warn("Falha ao carregar o cache de CFOP de referencia; validacao operara em fail-open. "
          + "Causa: {}", ex.getMessage());
    }
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      return true;
    }

    Set<String> cache = this.codigosCache;
    if (cache == null) {
      return true;
    }

    return cache.contains(value);
  }
}
