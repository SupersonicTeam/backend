package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Contador;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA para {@link Contador} — issue #93.
 *
 * <p>Além do CRUD herdado, expõe a busca por documento (usada pelo seed e, na sequência, pelo login
 * do contador) e a consulta de vínculo, que é a autorização do contador sobre um produtor.
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
   * Responde se o contador atende o produtor informado.
   *
   * <p>É a pergunta de autorização do papel Contador: sem vínculo, o produtor deve ser tratado como
   * inexistente (404, nunca 403 — o padrão anti-IDOR do projeto). Consulta a tabela de vínculo
   * diretamente, sem carregar a carteira do contador na memória.
   *
   * @param contadorId identificador do contador
   * @param produtorId identificador do produtor
   * @return {@code true} se houver vínculo entre os dois
   */
  boolean existsByIdAndProdutoresId(UUID contadorId, UUID produtorId);
}
