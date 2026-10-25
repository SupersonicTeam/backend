package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NotaFiscalRevisao;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Histórico de revisão das notas (issue #178). */
public interface NotaFiscalRevisaoRepository extends JpaRepository<NotaFiscalRevisao, UUID> {

  /**
   * Histórico da nota, do mais recente ao mais antigo.
   *
   * @param notaFiscalId nota
   * @return ações
   */
  List<NotaFiscalRevisao> findByNotaFiscalIdOrderByOcorridoEmDesc(UUID notaFiscalId);
}
