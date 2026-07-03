package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.StatusApuracao;
import java.util.List;
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

  /**
   * Busca uma apuração por ID restringindo ao produtor dono (recorte anti-IDOR).
   *
   * <p>Usada na reabertura (issue Agrocontabil#42): retorna vazio — resultando em 404 — quando a
   * apuração pertence a outro produtor, sem revelar sua existência.
   *
   * @param id         identificador da apuração
   * @param produtorId identificador do produtor autenticado
   * @return {@link Optional} com a apuração, ou vazio se inexistente ou de outro produtor
   */
  Optional<ApuracaoMensal> findByIdAndProdutorId(UUID id, UUID produtorId);

  /**
   * Busca as apurações em um dado status cujo período esteja na faixa {@code [inicio, fim)}.
   *
   * <p>Usada pelo refechamento automático de períodos reabertos (issue Agrocontabil#57 — RN005): o
   * job varre as apurações {@link StatusApuracao#ABERTO} dos meses anteriores dentro da janela de 3
   * meses e as refecha. Como o período é armazenado no formato {@code YYYY-MM}, a comparação
   * lexicográfica de {@code String} coincide com a ordem cronológica, dispensando conversão. A faixa
   * é meio-aberta: {@code inicio} inclusivo, {@code fim} exclusivo (tipicamente o mês corrente, já
   * coberto pelo fechamento normal).
   *
   * @param status status alvo (ex.: {@link StatusApuracao#ABERTO})
   * @param inicio período inicial inclusivo no formato {@code YYYY-MM}
   * @param fim    período final exclusivo no formato {@code YYYY-MM}
   * @return apurações no status e faixa informados (vazia se nenhuma)
   */
  List<ApuracaoMensal> findByStatusAndPeriodoGreaterThanEqualAndPeriodoLessThan(
      StatusApuracao status, String inicio, String fim);
}
