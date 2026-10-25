package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Produtor;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProdutorRepository extends JpaRepository<Produtor, UUID> {

  Optional<Produtor> findByCpfCnpj(String cpfCnpj);

  /**
   * Diz se algum produtor tem um destes documentos (issue #205).
   *
   * <p>Recebe mais de uma grafia porque a coluna guarda o documento como chegou: o seed grava com
   * máscara ({@code 123.456.789-00}), o cadastro grava só dígitos.
   *
   * @param grafias o mesmo documento em cada formato possível
   * @return {@code true} se algum produtor tem um deles
   */
  boolean existsByCpfCnpjIn(Collection<String> grafias);

  /**
   * Ids dos produtores com um destes documentos — busca da carteira por CPF/CNPJ (issue #177).
   *
   * <p>Só por igualdade: a coluna é cifrada de forma determinística (#147), e o Hibernate cifra
   * também as grafias do parâmetro.
   *
   * @param grafias o mesmo documento com e sem máscara
   * @return ids encontrados
   */
  @Query("SELECT p.id FROM Produtor p WHERE p.cpfCnpj IN :grafias")
  List<UUID> findIdsByCpfCnpjIn(@Param("grafias") Collection<String> grafias);

  /**
   * Primeiro lote de IDs de produtor, em ordem de ID, para varreduras em lote dos jobs
   * (issue #100).
   *
   * <p>Devolve só os IDs, sem materializar as entidades. Use {@code PageRequest.ofSize(n)}: o
   * {@link Pageable} aqui só limita o lote, sem contagem.
   *
   * @param lote tamanho do lote
   * @return até {@code lote} IDs, em ordem crescente
   */
  @Query("SELECT p.id FROM Produtor p ORDER BY p.id")
  List<UUID> findIdsOrdenados(Pageable lote);

  /**
   * Lote seguinte de IDs de produtor, estritamente depois de {@code apos} (paginação por keyset,
   * issue #100).
   *
   * <p>Keyset em vez de offset: um produtor cadastrado durante a varredura não desloca as páginas
   * nem faz um produtor ser pulado ou processado duas vezes.
   *
   * @param apos último ID do lote anterior
   * @param lote tamanho do lote
   * @return até {@code lote} IDs maiores que {@code apos}, em ordem crescente
   */
  @Query("SELECT p.id FROM Produtor p WHERE p.id > :apos ORDER BY p.id")
  List<UUID> findIdsOrdenadosApos(@Param("apos") UUID apos, Pageable lote);
}
