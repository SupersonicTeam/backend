package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.AssistenteInteracao;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Auditoria das perguntas à Ceres (issue #144). */
public interface AssistenteInteracaoRepository extends JpaRepository<AssistenteInteracao, UUID> {

  /**
   * Interações de um produtor, da mais recente para a mais antiga.
   *
   * @param produtorId produtor
   * @return interações do produtor
   */
  List<AssistenteInteracao> findByProdutorIdOrderByCriadoEmDesc(UUID produtorId);
}
