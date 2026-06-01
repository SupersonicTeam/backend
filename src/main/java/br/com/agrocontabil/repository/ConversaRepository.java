package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Conversa;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversaRepository extends JpaRepository<Conversa, UUID> {

  List<Conversa> findByProdutorIdOrderByCreatedAtDesc(UUID produtorId);
}
