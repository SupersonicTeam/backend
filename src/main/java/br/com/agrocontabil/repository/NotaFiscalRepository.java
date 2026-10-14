package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório Spring Data JPA para {@link NotaFiscal}.
 *
 * <p>Fornece acesso paginado às notas de um produtor específico e busca segura
 * por ID vinculada ao produtor, evitando acesso cruzado entre produtores.
 */
public interface NotaFiscalRepository extends JpaRepository<NotaFiscal, UUID> {

  /**
   * Retorna todas as notas fiscais de um produtor de forma paginada.
   *
   * @param produtorId identificador do {@link br.com.agrocontabil.model.Produtor}
   * @param pageable   parâmetros de paginação e ordenação
   * @return página de notas fiscais do produtor
   */
  Page<NotaFiscal> findByProdutorId(UUID produtorId, Pageable pageable);

  /**
   * Busca uma nota fiscal pelo seu ID e pelo ID do produtor proprietário.
   *
   * <p>Retorna {@link Optional#empty()} se a nota não existir ou se pertencer
   * a um produtor diferente do informado, impedindo acesso cruzado.
   *
   * @param id         identificador da nota fiscal
   * @param produtorId identificador do produtor esperado como proprietário
   * @return {@link Optional} com a nota fiscal, ou vazio se não encontrada
   */
  Optional<NotaFiscal> findByIdAndProdutorId(UUID id, UUID produtorId);

  /**
   * Igual a {@link #findByIdAndProdutorId(UUID, UUID)}, porém carregando de uma vez o grafo que os
   * pipelines de NF-e e DANFE consomem: os <strong>itens</strong> e o <strong>produtor</strong>
   * (issue #99).
   *
   * <p>Serve às operações que rodam <strong>fora de transação</strong> — assinatura digital,
   * validação XSD e renderização da DANFE. Elas custam CPU e tempo, e mantê-las dentro da transação
   * prendia uma conexão do pool durante todo o processamento. A entidade devolvida por esta query
   * sai da transação <em>detached</em>, mas com {@code itens} e {@code produtor} já materializados,
   * então o pipeline a percorre sem tocar em nenhum proxy: nada de
   * {@code LazyInitializationException} com {@code open-in-view: false}.
   *
   * <p>Os endereços não aparecem no {@code JOIN FETCH} porque são {@code @Embedded} — vêm nas
   * colunas da própria linha, sempre carregados.
   *
   * <p><strong>Ao acrescentar acesso a uma relação LAZY no pipeline de emissão ou de DANFE,
   * inclua-a aqui.</strong> Fora da sessão o acesso falha de forma explícita, e o teste de
   * integração que cobre a nota completa existe para pegar exatamente isso (issue #99).
   *
   * <p>{@code LEFT JOIN} nos dois: uma nota sem itens (rascunho recém-criado) continua sendo
   * encontrada — quem recusa a emissão é o {@code FiscalRulesValidator}, com 422 e mensagem de
   * negócio, não uma query que devolveria vazio e viraria 404 enganoso.
   *
   * @param id         identificador da nota fiscal
   * @param produtorId identificador do produtor esperado como proprietário
   * @return {@link Optional} com a nota e seu grafo de emissão, ou vazio se não encontrada
   */
  @Query("SELECT DISTINCT n FROM NotaFiscal n "
      + "LEFT JOIN FETCH n.itens "
      + "LEFT JOIN FETCH n.produtor "
      + "WHERE n.id = :id AND n.produtor.id = :produtorId")
  Optional<NotaFiscal> findByIdAndProdutorIdComGrafoDeEmissao(
      @Param("id") UUID id, @Param("produtorId") UUID produtorId);

  /**
   * Igual a {@link #findByIdAndProdutorId(UUID, UUID)}, porém adquirindo <strong>lock pessimista
   * de escrita</strong> ({@code SELECT ... FOR UPDATE}) sobre a linha (issue #96).
   *
   * <p>Serve às transições de estado que disparam efeito externo irreversível — transmitir e
   * cancelar NF-e na SEFAZ. Ler o status sem lock deixa uma janela entre a checagem da
   * pré-condição e a gravação do novo status: duas requisições simultâneas passariam as duas pela
   * checagem, transmitiriam duas vezes ao fisco e publicariam dois eventos de domínio, apurando o
   * débito de IBS/CBS em dobro. Com o lock, a segunda requisição espera, encontra o status já
   * transicionado e recebe 409.
   *
   * <p>O {@code @Version} da issue #95 protege contra <em>lost update</em>, mas só falha no
   * commit — tarde demais aqui, porque a essa altura as duas já teriam falado com a SEFAZ. Por
   * isso o lock, e não apenas o controle otimista.
   *
   * <p>O filtro por produtor faz parte da query, então o 404 anti-IDOR continua vindo antes de
   * qualquer 409. <strong>Só pode ser usado dentro de uma transação de escrita</strong> — nunca
   * combinar com {@code readOnly}. Mantenha a transação curta: o lock só é liberado no commit.
   *
   * @param id         identificador da nota fiscal
   * @param produtorId identificador do produtor esperado como proprietário
   * @return {@link Optional} com a nota fiscal bloqueada, ou vazio se não encontrada
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT n FROM NotaFiscal n WHERE n.id = :id AND n.produtor.id = :produtorId")
  Optional<NotaFiscal> findByIdAndProdutorIdForUpdate(
      @Param("id") UUID id, @Param("produtorId") UUID produtorId);

  /**
   * Retorna as notas presas num estado transitório de transmissão desde antes do instante
   * informado (issue #96).
   *
   * <p>Uso restrito ao job de sistema ({@link br.com.agrocontabil.sefaz.SefazStatusSyncJob}), sem
   * filtro de produtor: se o processo cair entre a marcação de
   * {@link br.com.agrocontabil.model.StatusNotaFiscal#ENVIANDO}/{@code CANCELANDO} e o desfecho,
   * a nota ficaria parada nesse estado para sempre, bloqueando toda operação sobre ela.
   *
   * <p>O corte usa {@code updatedAt}, já mantido pelo {@code @PreUpdate} da entidade — a
   * transição para o estado transitório o atualiza, então ele marca o início da transmissão sem
   * precisar de coluna nova.
   *
   * @param status  estados transitórios a varrer
   * @param anteriorA instante limite: só notas paradas desde antes dele são reconciliadas
   * @return notas presas em transmissão além do tempo tolerado
   */
  List<NotaFiscal> findByStatusInAndUpdatedAtBefore(
      List<StatusNotaFiscal> status, OffsetDateTime anteriorA);

  /**
   * Primeiro lote de IDs de notas num status, sem filtro de produtor (issue #83).
   *
   * <p>Uso restrito ao job de sincronização com a SEFAZ. Devolve só os IDs, sem materializar as
   * entidades: antes, {@code findByStatus} carregava todas as notas pendentes de uma vez. Use
   * {@code PageRequest.ofSize(n)}: o {@link Pageable} aqui só limita o lote, sem contagem.
   *
   * @param status status a filtrar
   * @param lote   tamanho do lote
   * @return até {@code lote} IDs, em ordem crescente
   */
  @Query("SELECT n.id FROM NotaFiscal n WHERE n.status = :status ORDER BY n.id")
  List<UUID> findIdsPorStatus(@Param("status") StatusNotaFiscal status, Pageable lote);

  /**
   * Lote seguinte de IDs de notas num status, estritamente depois de {@code apos} (paginação por
   * keyset, issue #83 — mesmo desenho de {@code ProdutorRepository.findIdsOrdenadosApos}).
   *
   * <p>Keyset em vez de offset: as notas sincronizadas saem do status durante a varredura, e com
   * offset as páginas seguintes se deslocariam e pulariam notas.
   *
   * @param status status a filtrar
   * @param apos   último ID do lote anterior
   * @param lote   tamanho do lote
   * @return até {@code lote} IDs maiores que {@code apos}, em ordem crescente
   */
  @Query("SELECT n.id FROM NotaFiscal n WHERE n.status = :status AND n.id > :apos ORDER BY n.id")
  List<UUID> findIdsPorStatusApos(
      @Param("status") StatusNotaFiscal status, @Param("apos") UUID apos, Pageable lote);

  /**
   * Indica se ja existe uma nota com a chave de acesso informada (issue #19).
   *
   * <p>Usado na deduplicacao de notas importadas: a {@code chave_acesso} e globalmente unica
   * (44 digitos), entao a existencia previa sinaliza um XML ja importado.
   *
   * @param chaveAcesso chave de acesso de 44 digitos
   * @return {@code true} se ja houver uma nota com esta chave
   */
  boolean existsByChaveAcesso(String chaveAcesso);

  /**
   * Agrega o crédito tributário de IBS/CBS das notas de <strong>entrada</strong> de um produtor
   * emitidas dentro de um intervalo de tempo (issue Agrocontabil#30 — RN003).
   *
   * <p><strong>Recorte de segurança (anti-IDOR):</strong> o filtro por {@code produtorId} é
   * parte da própria query — um produtor nunca soma créditos de notas de outro produtor.
   *
   * <p>Considera apenas notas {@link br.com.agrocontabil.model.OrigemNotaFiscal#IMPORTADA}
   * (as únicas que geram crédito de entrada) e {@link br.com.agrocontabil.model.StatusNotaFiscal#AUTORIZADA}
   * (o XML de origem foi autorizado na SEFAZ, cStat=100), com {@code dataEmissao} em
   * {@code [inicio, fim)}. O filtro de status espelha o de {@link #somarDebitosPorPeriodo}
   * (simetria débito × crédito — issue Agrocontabil#51): uma nota importada não autorizada
   * (ex.: {@link br.com.agrocontabil.model.StatusNotaFiscal#XML_GERADO}) não gera crédito.
   * O {@code SUM} retorna nulo quando não há notas no intervalo — o serviço trata como
   * {@code 0.00}.
   *
   * @param produtorId identificador do produtor autenticado
   * @param inicio     início do intervalo (inclusivo)
   * @param fim        fim do intervalo (exclusivo)
   * @return projeção com os somatórios de crédito e a contagem de notas do período
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CreditoSaldoAgregado(
          SUM(n.valorTotalCreditoIbs),
          SUM(n.valorTotalCreditoCbs),
          COUNT(n))
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      """)
  CreditoSaldoAgregado somarCreditosPorPeriodo(
      @Param("produtorId") UUID produtorId,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /**
   * Agrega o débito de IBS/CBS das notas de <strong>saída autorizadas</strong> de um produtor
   * emitidas dentro de um intervalo de tempo (issue Agrocontabil#32 — UC004).
   *
   * <p><strong>Recorte de segurança (anti-IDOR):</strong> o filtro por {@code produtorId} é
   * parte da própria query.
   *
   * <p>Considera apenas notas {@link br.com.agrocontabil.model.OrigemNotaFiscal#PROPRIA}
   * (emitidas pelo produtor = saída) com status
   * {@link br.com.agrocontabil.model.StatusNotaFiscal#AUTORIZADA} e {@code dataEmissao} em
   * {@code [inicio, fim)}. O {@code SUM} retorna nulo quando não há notas — o serviço trata
   * como {@code 0.00}.
   *
   * @param produtorId identificador do produtor autenticado
   * @param inicio     início do intervalo (inclusivo)
   * @param fim        fim do intervalo (exclusivo)
   * @return projeção com os somatórios de débito e a contagem de notas do período
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.DebitoSaldoAgregado(
          SUM(n.valorTotalIbs),
          SUM(n.valorTotalCbs),
          COUNT(n))
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      """)
  DebitoSaldoAgregado somarDebitosPorPeriodo(
      @Param("produtorId") UUID produtorId,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /**
   * Conta as notas <strong>autorizadas</strong> de um produtor por mês e origem, dentro de um
   * intervalo de tempo, para a série mensal do dashboard (issue Agrocontabil#65 — Sprint 5).
   *
   * <p><strong>Recorte de segurança (anti-IDOR):</strong> o filtro por {@code produtorId} é parte
   * da própria query — um produtor nunca vê o volume de notas de outro.
   *
   * <p>Considera apenas notas {@link br.com.agrocontabil.model.StatusNotaFiscal#AUTORIZADA} com
   * {@code dataEmissao} em {@code [inicio, fim)}, agrupando por ano/mês (extração {@code year()}/
   * {@code month()} do HQL, portável entre H2 e PostgreSQL) e por {@code origem}
   * (PROPRIA = saída, IMPORTADA = entrada). Meses sem notas simplesmente não retornam linha — o
   * serviço preenche a lacuna com zero. Notas em rascunho, rejeitadas ou canceladas não entram,
   * mantendo a série coerente com os KPIs de apuração.
   *
   * @param produtorId identificador do produtor autenticado
   * @param inicio     início do intervalo (inclusivo)
   * @param fim        fim do intervalo (exclusivo)
   * @return contagens por (ano, mês, origem) das notas autorizadas no intervalo
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.NotasPorMesAgregado(
          year(n.dataEmissao),
          month(n.dataEmissao),
          n.origem,
          COUNT(n))
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      GROUP BY year(n.dataEmissao), month(n.dataEmissao), n.origem
      """)
  List<NotasPorMesAgregado> contarNotasPorMesEOrigem(
      @Param("produtorId") UUID produtorId,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /**
   * Agrega o valor total (R$) das notas <strong>autorizadas</strong> de um produtor por origem,
   * dentro de um intervalo de tempo, para os KPIs financeiros do dashboard (issue
   * Agrocontabil#71).
   *
   * <p><strong>Recorte de segurança (anti-IDOR):</strong> o filtro por {@code produtorId} é parte
   * da própria query — um produtor nunca soma o valor de notas de outro produtor.
   *
   * <p>Considera apenas notas {@link br.com.agrocontabil.model.StatusNotaFiscal#AUTORIZADA} com
   * {@code dataEmissao} em {@code [inicio, fim)}, mesmo recorte usado por
   * {@link #somarDebitosPorPeriodo} e {@link #somarCreditosPorPeriodo} (débito/crédito de
   * IBS/CBS), garantindo que o valor em R$ e os tributos apurados no dashboard se refiram
   * exatamente às mesmas notas.
   *
   * @param produtorId identificador do produtor autenticado
   * @param inicio     início do intervalo (inclusivo)
   * @param fim        fim do intervalo (exclusivo)
   * @return projeção com o valor total autorizado por origem (PROPRIA/IMPORTADA) do período
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.ValorNotasAgregado(
          SUM(CASE WHEN n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
              THEN n.valorTotalNota ELSE 0 END),
          SUM(CASE WHEN n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA
              THEN n.valorTotalNota ELSE 0 END))
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      """)
  ValorNotasAgregado somarValorNotasPorOrigem(
      @Param("produtorId") UUID produtorId,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /**
   * Retorna as cinco notas mais recentes de um produtor (por {@code dataEmissao} decrescente), para
   * a lista de últimas notas do dashboard (issue Agrocontabil#65 — Sprint 5).
   *
   * <p><strong>Recorte de segurança (anti-IDOR):</strong> o filtro por {@code produtorId} está no
   * nome do método derivado — um produtor nunca lê notas de outro. Considera todas as origens e
   * status (a lista reflete a atividade recente do produtor, não apenas as autorizadas).
   *
   * @param produtorId identificador do produtor autenticado
   * @return até cinco notas do produtor, da mais recente para a mais antiga
   */
  List<NotaFiscal> findTop5ByProdutorIdOrderByDataEmissaoDesc(UUID produtorId);

  // --- Consultas da Ceres (issue #142). Fixas e parametrizadas: o modelo só escolhe a função e
  // preenche os parâmetros, nunca escreve SQL. Todas exigem o produtor (anti-IDOR, RN007).

  /**
   * Compras de um insumo por NCM (código completo ou prefixo): itens de notas de
   * <strong>entrada</strong> ({@code IMPORTADA}, o produtor é o destinatário) autorizadas no
   * período, agregados por unidade. Mesmo recorte do crédito ({@link #somarCreditosPorPeriodo}).
   *
   * @param produtorId produtor da sessão
   * @param prefixoNcm prefixo do NCM já com o curinga final (ex.: {@code 3808%})
   * @param inicio     início do período (inclusivo)
   * @param fim        fim do período (exclusivo)
   * @return uma linha por unidade de medida, maior valor primeiro
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CompraInsumoAgregado(
          i.unidade, SUM(i.quantidade), SUM(i.valorTotal))
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND i.ncm LIKE :prefixoNcm
      GROUP BY i.unidade
      ORDER BY SUM(i.valorTotal) DESC
      """)
  List<CompraInsumoAgregado> somarComprasPorNcm(
      @Param("produtorId") UUID produtorId,
      @Param("prefixoNcm") String prefixoNcm,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /** Valor total e notas distintas das compras por NCM — mesmo recorte de {@link #somarComprasPorNcm}. */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CompraInsumoTotal(
          SUM(i.valorTotal), COUNT(DISTINCT n.id))
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND i.ncm LIKE :prefixoNcm
      """)
  CompraInsumoTotal totalizarComprasPorNcm(
      @Param("produtorId") UUID produtorId,
      @Param("prefixoNcm") String prefixoNcm,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /**
   * Compras de um insumo pela descrição do item (contém, sem diferenciar maiúsculas). O padrão
   * chega pronto e escapado com {@code !}: {@code %} e {@code _} digitados pelo usuário são
   * literais, não curingas.
   *
   * @param padraoDescricao padrão {@code LIKE} em minúsculas, já escapado (ex.: {@code %adubo%})
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CompraInsumoAgregado(
          i.unidade, SUM(i.quantidade), SUM(i.valorTotal))
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND LOWER(i.descricao) LIKE :padraoDescricao ESCAPE '!'
      GROUP BY i.unidade
      ORDER BY SUM(i.valorTotal) DESC
      """)
  List<CompraInsumoAgregado> somarComprasPorDescricao(
      @Param("produtorId") UUID produtorId,
      @Param("padraoDescricao") String padraoDescricao,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /**
   * Valor total e notas distintas das compras pela descrição — mesmo recorte de
   * {@link #somarComprasPorDescricao}.
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CompraInsumoTotal(
          SUM(i.valorTotal), COUNT(DISTINCT n.id))
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND LOWER(i.descricao) LIKE :padraoDescricao ESCAPE '!'
      """)
  CompraInsumoTotal totalizarComprasPorDescricao(
      @Param("produtorId") UUID produtorId,
      @Param("padraoDescricao") String padraoDescricao,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /**
   * Notas do produtor no período, filtradas por origem e status, mais recentes primeiro. Os
   * filtros são coleções nunca vazias: "qualquer origem" é a coleção com todas, o que evita o
   * {@code :param IS NULL} que o PostgreSQL não consegue tipar.
   *
   * @param pagina limite (top-N): a Ceres nunca lista tudo
   */
  @Query(value = """
      SELECT new br.com.agrocontabil.repository.NotaResumo(
          n.numero, n.serie, n.dataEmissao, n.origem, n.status,
          n.valorTotalNota, n.valorTotalIbs, n.valorTotalCbs)
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem IN :origens
        AND n.status IN :status
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      ORDER BY n.dataEmissao DESC, n.id DESC
      """,
      countQuery = """
      SELECT COUNT(n) FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem IN :origens
        AND n.status IN :status
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      """)
  Page<NotaResumo> listarResumos(
      @Param("produtorId") UUID produtorId,
      @Param("origens") Collection<OrigemNotaFiscal> origens,
      @Param("status") Collection<StatusNotaFiscal> status,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim,
      Pageable pagina);

  /**
   * NF-e próprias autorizadas de todos os produtores emitidas em {@code [inicio, fim)} — as que
   * terão o prazo de cancelamento (RN006) vencendo, candidatas a alerta (issue #140).
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.PrazoCancelamentoCandidato(
          n.id, n.produtor.id, n.numero, n.serie, n.tipoDocumento, n.dataEmissao)
      FROM NotaFiscal n
      WHERE n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      """)
  List<PrazoCancelamentoCandidato> findPropriasAutorizadasEmitidasEntre(
      @Param("inicio") OffsetDateTime inicio, @Param("fim") OffsetDateTime fim);
}
