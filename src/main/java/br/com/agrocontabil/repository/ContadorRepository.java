package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Contador;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA para {@link Contador} — issue #93.
 *
 * <p>Além do CRUD herdado, expõe a busca por documento (seed e cadastro). O vínculo com o produtor
 * mora em {@link VinculoCarteiraRepository} desde a issue #177.
 */
public interface ContadorRepository extends JpaRepository<Contador, UUID> {

  /**
   * Busca o contador pelo CPF/CNPJ do escritório.
   *
   * @param cpfCnpj documento do contador, no mesmo formato gravado
   * @return o contador, se existir
   */
  Optional<Contador> findByCpfCnpj(String cpfCnpj);

  /**
   * Informa se já existe contador com este documento (idempotência do seed).
   *
   * @param cpfCnpj documento do contador
   * @return {@code true} se já existir
   */
  boolean existsByCpfCnpj(String cpfCnpj);

  /**
   * Diz se algum contador tem um destes documentos (issue #205) — mesmas grafias de
   * {@code ProdutorRepository#existsByCpfCnpjIn}.
   *
   * @param grafias o mesmo documento em cada formato possível
   * @return {@code true} se algum contador tem um deles
   */
  boolean existsByCpfCnpjIn(Collection<String> grafias);
}
