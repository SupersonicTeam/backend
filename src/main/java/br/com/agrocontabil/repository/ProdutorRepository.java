package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Produtor;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProdutorRepository extends JpaRepository<Produtor, UUID> {

  boolean existsByCpfCnpj(String cpfCnpj);

  Optional<Produtor> findByCpfCnpj(String cpfCnpj);
}
