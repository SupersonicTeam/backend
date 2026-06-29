package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Producer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProducerRepository extends JpaRepository<Producer, UUID> {

  boolean existsByTaxId(String taxId);

  Optional<Producer> findByTaxId(String taxId);
}
