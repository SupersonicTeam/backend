package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.FuncaoEscritorio;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * Membros do escritório, por nome.
   *
   * @param escritorioId escritório
   * @return contadores do escritório
   */
  List<Contador> findByEscritorioIdOrderByNomeCompletoAsc(UUID escritorioId);

  /**
   * Membro do escritório por id (anti-IDOR: o de outro escritório não existe).
   *
   * @param id           contador
   * @param escritorioId escritório
   * @return o membro, se for daquele escritório
   */
  Optional<Contador> findByIdAndEscritorioId(UUID id, UUID escritorioId);

  /**
   * Quantos membros do escritório têm a função — o último administrador não sai.
   *
   * @param escritorioId escritório
   * @param funcao       função
   * @return quantidade
   */
  long countByEscritorioIdAndFuncao(UUID escritorioId, FuncaoEscritorio funcao);

  /**
   * Quantos membros o escritório tem.
   *
   * @param escritorioId escritório
   * @return quantidade
   */
  long countByEscritorioId(UUID escritorioId);

  /**
   * Membros do escritório sob lock pessimista: serializa troca de função e remoção, para dois
   * administradores não se rebaixarem ao mesmo tempo e deixarem o escritório sem nenhum.
   *
   * @param escritorioId escritório
   * @return membros
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM Contador c WHERE c.escritorioId = :escritorioId ORDER BY c.id")
  List<Contador> findByEscritorioIdForUpdate(@Param("escritorioId") UUID escritorioId);

  /**
   * Contador sob lock pessimista: serializa os convites de um escritório para o teto diário
   * (issue #177) não ser furado por requisições simultâneas.
   *
   * @param id contador
   * @return o contador, se existir
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM Contador c WHERE c.id = :id")
  Optional<Contador> findByIdForUpdate(@Param("id") UUID id);
}
