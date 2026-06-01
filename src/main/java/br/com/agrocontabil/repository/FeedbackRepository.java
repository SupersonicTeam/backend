package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.Feedback;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {}
