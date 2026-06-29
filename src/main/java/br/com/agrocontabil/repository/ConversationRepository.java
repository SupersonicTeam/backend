package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Conversation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

  List<Conversation> findByProducerIdOrderByCreatedAtDesc(UUID producerId);
}
