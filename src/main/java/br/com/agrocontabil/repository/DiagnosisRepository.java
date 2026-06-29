package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Diagnosis;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisRepository extends JpaRepository<Diagnosis, UUID> {

  List<Diagnosis> findByProducerIdOrderByCreatedAtDesc(UUID producerId);
}
