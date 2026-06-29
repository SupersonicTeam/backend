package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NcmAgropecuario;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositório da tabela de referência de NCMs agropecuários. */
public interface NcmAgropecuarioRepository extends JpaRepository<NcmAgropecuario, String> {

  /**
   * Indica se o NCM informado está cadastrado para agropecuária.
   *
   * @param codigo código NCM (8 dígitos)
   * @return {@code true} se existir na tabela de referência
   */
  boolean existsByCodigo(String codigo);
}
