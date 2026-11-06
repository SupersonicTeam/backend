package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

/**
 * Consultas das notas da carteira inteira do escritório (issue #341): a listagem paginada e a
 * contagem por situação, ambas sobre uma {@link Specification} que o chamador monta com o escopo
 * (a carteira ativa) <strong>e</strong> os filtros.
 *
 * <p>Por que não o {@code NotaFiscalRepository.findAll(spec, pageable)}: ele devolve a entidade
 * inteira, com o XML assinado (coluna grande) de cada nota da página, e o nome do produtor exigiria
 * uma consulta por nota ou um {@code JOIN FETCH} que o count não aceita. Aqui a lista é uma
 * projeção ({@link NotaDaCarteira}) com o nome do produtor numa junção só: uma consulta para a
 * página e uma para o total (que nem roda quando a página já mostra o total), seja qual for o
 * tamanho da página.
 */
@Repository
public class NotaFiscalCarteiraRepository {

  @PersistenceContext
  private EntityManager em;

  /**
   * Página de notas que atendem à especificação.
   *
   * @param especificacao escopo e filtros, sempre combinados por {@code and} pelo chamador
   * @param pageable      página e ordenação (propriedades de {@link NotaFiscal}; o chamador já
   *                      validou a lista permitida e garante uma ordenação estável)
   * @return página da projeção, com o total
   */
  public Page<NotaDaCarteira> listar(Specification<NotaFiscal> especificacao,
      Pageable pageable) {
    if (pageable.getOffset() > Integer.MAX_VALUE) {
      // setFirstResult e int: um offset maior viraria negativo (500) ou uma pagina errada. Alem
      // do fim de qualquer lista real, e uma pagina vazia com o total.
      return new PageImpl<>(List.of(), pageable, contar(especificacao));
    }
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<NotaDaCarteira> consulta = cb.createQuery(NotaDaCarteira.class);
    Root<NotaFiscal> nota = consulta.from(NotaFiscal.class);
    Join<NotaFiscal, Produtor> produtor = nota.join("produtor");
    consulta.select(cb.construct(NotaDaCarteira.class,
        nota.get("id"), produtor.get("id"), produtor.get("nomeCompleto"), nota.get("numero"),
        nota.get("serie"), nota.get("naturezaOperacao"), nota.get("status"),
        nota.get("tipoDocumento"), nota.get("dataEmissao"), nota.get("destinatarioNome"),
        nota.get("valorTotalProdutos"), nota.get("valorTotalNota"), nota.get("valorTotalIbs"),
        nota.get("valorTotalCbs"), nota.get("chaveAcesso"), nota.get("createdAt"),
        nota.get("updatedAt")));
    Predicate onde = especificacao.toPredicate(nota, consulta, cb);
    if (onde != null) {
      consulta.where(onde);
    }
    consulta.orderBy(ordens(pageable.getSort(), nota, cb));
    List<NotaDaCarteira> conteudo = em.createQuery(consulta)
        .setFirstResult((int) pageable.getOffset())
        .setMaxResults(pageable.getPageSize())
        .getResultList();
    return PageableExecutionUtils.getPage(conteudo, pageable, () -> contar(especificacao));
  }

  /**
   * Quantas notas há em cada situação entre as que atendem à especificação, numa única consulta
   * ({@code GROUP BY status}).
   *
   * @param especificacao escopo e filtros
   * @return contagem por situação (só as situações que têm nota)
   */
  public Map<StatusNotaFiscal, Long> contarPorStatus(Specification<NotaFiscal> especificacao) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Object[]> consulta = cb.createQuery(Object[].class);
    Root<NotaFiscal> nota = consulta.from(NotaFiscal.class);
    consulta.multiselect(nota.get("status"), cb.count(nota));
    Predicate onde = especificacao.toPredicate(nota, consulta, cb);
    if (onde != null) {
      consulta.where(onde);
    }
    consulta.groupBy(nota.get("status"));
    Map<StatusNotaFiscal, Long> contagem = new EnumMap<>(StatusNotaFiscal.class);
    for (Object[] linha : em.createQuery(consulta).getResultList()) {
      contagem.put((StatusNotaFiscal) linha[0], (Long) linha[1]);
    }
    return contagem;
  }

  private long contar(Specification<NotaFiscal> especificacao) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Long> consulta = cb.createQuery(Long.class);
    Root<NotaFiscal> nota = consulta.from(NotaFiscal.class);
    consulta.select(cb.count(nota));
    Predicate onde = especificacao.toPredicate(nota, consulta, cb);
    if (onde != null) {
      consulta.where(onde);
    }
    return em.createQuery(consulta).getSingleResult();
  }

  private static List<Order> ordens(Sort sort, Root<NotaFiscal> nota, CriteriaBuilder cb) {
    List<Order> ordens = new ArrayList<>();
    for (Sort.Order ordem : sort) {
      ordens.add(ordem.isAscending() ? cb.asc(nota.get(ordem.getProperty()))
          : cb.desc(nota.get(ordem.getProperty())));
    }
    return ordens;
  }
}
