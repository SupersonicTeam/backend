package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ApuracaoMensal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA para {@link ApuracaoMensal} (issue Agrocontabil#41 — RN004).
 *
 * <p>Fornece a busca por produtor + período (recorte anti-IDOR) usada tanto na consolidação sob
 * demanda quanto no fechamento agendado.
 */
public interface ApuracaoMensalRepository extends JpaRepository<ApuracaoMensal, UUID> {

  /**
   * Busca a apuração de um produtor em um período específico.
   *
   * <p><strong>Recorte de segurança (anti-IDOR):</strong> o filtro por {@code produtorId} é parte
   * da consulta — um produtor nunca acessa a apuração de outro. O par
   * {@code (produtor_id, periodo)} é único, logo o resultado é no máximo um.
   *
   * @param produtorId identificador do produtor autenticado
   * @param periodo    período no formato {@code YYYY-MM}
   * @return {@link Optional} com a apuração, ou vazio se ainda não consolidada
   */
  Optional<ApuracaoMensal> findByProdutorIdAndPeriodo(UUID produtorId, String periodo);
}
