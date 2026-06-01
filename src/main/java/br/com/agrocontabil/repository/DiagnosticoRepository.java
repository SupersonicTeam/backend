package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Diagnostico;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosticoRepository extends JpaRepository<Diagnostico, UUID> {

  List<Diagnostico> findByProdutorIdOrderByCreatedAtDesc(UUID produtorId);
}
