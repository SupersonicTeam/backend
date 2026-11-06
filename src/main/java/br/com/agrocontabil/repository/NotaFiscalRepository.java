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
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório Spring Data JPA para {@link NotaFiscal}.
 *
 * <p>Fornece acesso paginado às notas de um produtor específico e busca segura
 * por ID vinculada ao produtor, evitando acesso cruzado entre produtores.
 */
public interface NotaFiscalRepository
    extends JpaRepository<NotaFiscal, UUID>, JpaSpecificationExecutor<NotaFiscal> {

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
   * Indica se o produtor ja tem uma nota com a chave de acesso e a origem informadas (issue #223).
   *
   * <p>Deduplicacao da importacao <em>dentro da conta do produtor</em>: a chave e unica por
   * (produtor, chave, origem), entao o emitente (PROPRIA) e o destinatario (IMPORTADA) tem a mesma
   * NF-e. Ate a #223 a checagem era no sistema inteiro: o destinatario nao importava a NF-e que
   * outro usuario emitiu para ele, e "duplicada" revelava a chave em outra conta.
   *
   * @param produtorId  produtor autenticado
   * @param chaveAcesso chave de acesso de 44 digitos
   * @param origem      origem da nota ({@code IMPORTADA} na importacao)
   * @return {@code true} se o produtor ja tiver essa nota
   */
  boolean existsByProdutorIdAndChaveAcessoAndOrigem(
      UUID produtorId, String chaveAcesso, OrigemNotaFiscal origem);

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
   * Débito, crédito e quantidade de notas do produtor <strong>por mês</strong>, numa consulta só
   * (issue #179 — faixa de {@code GET /api/apuracao}). Mesmo recorte de
   * {@link #somarDebitosPorPeriodo} (PROPRIA + AUTORIZADA) e {@link #somarCreditosPorPeriodo}
   * (IMPORTADA + AUTORIZADA), para a faixa somar exatamente o que a consulta mês a mês somaria.
   *
   * <p>SQL nativo de propósito: o mês é o de Brasília ({@code AT TIME ZONE}), como os limites
   * {@code [início, fim)} da apuração. O {@code year()}/{@code month()} do HQL extrairia no fuso da
   * sessão do banco, e uma nota das 22h do dia 31 cairia no mês seguinte. H2 e PostgreSQL aceitam a
   * mesma sintaxe.
   *
   * @param produtorId produtor da sessão
   * @param inicio     início do intervalo (inclusivo)
   * @param fim        fim do intervalo (exclusivo)
   * @return uma linha por mês com nota: {@code [ano, mês, débito IBS, débito CBS, crédito IBS,
   *     crédito CBS, quantidade]}; somas nulas quando o mês só tem um dos lados
   */
  @Query(nativeQuery = true, value = """
      SELECT EXTRACT(YEAR FROM (n.data_emissao AT TIME ZONE 'America/Sao_Paulo')) AS ano,
             EXTRACT(MONTH FROM (n.data_emissao AT TIME ZONE 'America/Sao_Paulo')) AS mes,
             SUM(CASE WHEN n.origem = 'PROPRIA' THEN n.valor_total_ibs END) AS debito_ibs,
             SUM(CASE WHEN n.origem = 'PROPRIA' THEN n.valor_total_cbs END) AS debito_cbs,
             SUM(CASE WHEN n.origem = 'IMPORTADA' THEN n.valor_total_credito_ibs END)
                 AS credito_ibs,
             SUM(CASE WHEN n.origem = 'IMPORTADA' THEN n.valor_total_credito_cbs END)
                 AS credito_cbs,
             COUNT(*) AS quantidade
      FROM nota_fiscal n
      WHERE n.produtor_id = :produtorId
        AND n.status = 'AUTORIZADA'
        AND n.origem IN ('PROPRIA', 'IMPORTADA')
        AND n.data_emissao >= :inicio
        AND n.data_emissao < :fim
      GROUP BY EXTRACT(YEAR FROM (n.data_emissao AT TIME ZONE 'America/Sao_Paulo')),
               EXTRACT(MONTH FROM (n.data_emissao AT TIME ZONE 'America/Sao_Paulo'))
      """)
  List<Object[]> agregarApuracaoPorMes(
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
   * {@code dataEmissao} em {@code [inicio, fim)}, agrupando por ano/mês de Brasília e por
   * {@code origem} (PROPRIA = saída, IMPORTADA = entrada). Meses sem notas simplesmente não
   * retornam linha — o serviço preenche a lacuna com zero. Notas em rascunho, rejeitadas ou
   * canceladas não entram, mantendo a série coerente com os KPIs de apuração.
   *
   * @param produtorId identificador do produtor autenticado
   * @param inicio     início do intervalo (inclusivo)
   * @param fim        fim do intervalo (exclusivo)
   * @return contagens por (ano, mês, origem) das notas autorizadas no intervalo
   */
  default List<NotasPorMesAgregado> contarNotasPorMesEOrigem(
      UUID produtorId, OffsetDateTime inicio, OffsetDateTime fim) {
    return contarNotasPorMesEOrigemNativo(produtorId, inicio, fim).stream()
        .map(linha -> new NotasPorMesAgregado(
            ((Number) linha[0]).intValue(),
            ((Number) linha[1]).intValue(),
            OrigemNotaFiscal.valueOf((String) linha[2]),
            ((Number) linha[3]).longValue()))
        .toList();
  }

  /**
   * Consulta de {@link #contarNotasPorMesEOrigem}; use aquele, que devolve o record.
   *
   * <p>SQL nativo de propósito: o mês é o de Brasília ({@code AT TIME ZONE}), como os limites
   * {@code [início, fim)} que o {@code DashboardService} monta. O {@code year()}/{@code month()} do
   * HQL extrairia no fuso da sessão do banco (o da JVM, que o pgjdbc envia na conexão; o
   * {@code hibernate.jdbc.time_zone} não o muda), e com a JVM em UTC uma nota das 22h do último dia
   * do mês contaria no mês seguinte. H2 e PostgreSQL aceitam a mesma sintaxe.
   *
   * @param produtorId identificador do produtor autenticado
   * @param inicio     início do intervalo (inclusivo)
   * @param fim        fim do intervalo (exclusivo)
   * @return uma linha por (ano, mês, origem): {@code [ano, mês, origem, quantidade]}
   */
  @Query(nativeQuery = true, value = """
      SELECT EXTRACT(YEAR FROM (n.data_emissao AT TIME ZONE 'America/Sao_Paulo')) AS ano,
             EXTRACT(MONTH FROM (n.data_emissao AT TIME ZONE 'America/Sao_Paulo')) AS mes,
             n.origem AS origem,
             COUNT(*) AS quantidade
      FROM nota_fiscal n
      WHERE n.produtor_id = :produtorId
        AND n.status = 'AUTORIZADA'
        AND n.data_emissao >= :inicio
        AND n.data_emissao < :fim
      GROUP BY EXTRACT(YEAR FROM (n.data_emissao AT TIME ZONE 'America/Sao_Paulo')),
               EXTRACT(MONTH FROM (n.data_emissao AT TIME ZONE 'America/Sao_Paulo')),
               n.origem
      """)
  List<Object[]> contarNotasPorMesEOrigemNativo(
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

  /**
   * Notas do produtor com um número (issue #256, Ceres): as próprias primeiro (a pergunta "a nota
   * 118" quase sempre é sobre uma nota que o produtor emitiu), depois as recebidas, cada grupo da
   * mais recente para a mais antiga. O número não é único: repete entre séries e nas notas de
   * fornecedor. Só do produtor: nota de outro com o mesmo número não existe aqui (anti-IDOR).
   * Projeção: nada de XML nem de destinatário.
   *
   * @param produtorId produtor da sessão
   * @param numero     número da nota
   * @param pagina     limite
   * @return as notas com esse número
   */
  @Query(value = """
      SELECT new br.com.agrocontabil.repository.NotaPorNumeroLinha(
          n.id, n.numero, n.serie, n.dataEmissao, n.origem, n.status,
          n.valorTotalNota, n.valorTotalIbs, n.valorTotalCbs, n.destinatarioCpfCnpj)
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId AND n.numero = :numero
      ORDER BY CASE WHEN n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
          THEN 0 ELSE 1 END, n.dataEmissao DESC, n.id DESC
      """,
      countQuery = """
      SELECT COUNT(n) FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId AND n.numero = :numero
      """)
  Page<NotaPorNumeroLinha> findDoProdutorPorNumero(
      @Param("produtorId") UUID produtorId, @Param("numero") Long numero, Pageable pagina);

  /**
   * Notas próprias candidatas a sinal a conferir (#276) no resumo da Ceres (#266): as não emitidas
   * e as autorizadas desde {@code autorizadasDesde}, da mais recente para a mais antiga. Projeção
   * sem XML.
   *
   * @param produtorId       produtor da sessão
   * @param naoEmitidas      situações antes da emissão
   * @param autorizadasDesde início do prazo de cancelamento
   * @param pagina           limite
   * @return as candidatas
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.NotaPorNumeroLinha(
          n.id, n.numero, n.serie, n.dataEmissao, n.origem, n.status,
          n.valorTotalNota, n.valorTotalIbs, n.valorTotalCbs, n.destinatarioCpfCnpj)
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND (n.status IN :naoEmitidas
          OR (n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
            AND n.dataEmissao >= :autorizadasDesde))
      ORDER BY n.dataEmissao DESC, n.id DESC
      """)
  List<NotaPorNumeroLinha> findCandidatasASinal(@Param("produtorId") UUID produtorId,
      @Param("naoEmitidas") Collection<StatusNotaFiscal> naoEmitidas,
      @Param("autorizadasDesde") OffsetDateTime autorizadasDesde, Pageable pagina);

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
   * Vendas de um produto por NCM (issue #248): itens de notas de <strong>saída</strong> próprias
   * ({@code PROPRIA}) autorizadas no período, agregados por unidade. Só o grupo de vendas do CFOP
   * ({@code x1xx}: 5101, 6102...): remessa para armazém, industrialização ou "outra saída"
   * ({@code x9xx}) não é venda e contaria a mesma soja duas vezes.
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CompraInsumoAgregado(
          i.unidade, SUM(i.quantidade), SUM(i.valorTotal))
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND SUBSTRING(i.cfop, 2, 1) = '1'
        AND i.ncm LIKE :prefixoNcm
      GROUP BY i.unidade
      ORDER BY SUM(i.valorTotal) DESC
      """)
  List<CompraInsumoAgregado> somarVendasPorNcm(
      @Param("produtorId") UUID produtorId,
      @Param("prefixoNcm") String prefixoNcm,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /** Valor total e notas distintas das vendas por NCM — mesmo recorte de {@link #somarVendasPorNcm}. */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CompraInsumoTotal(
          SUM(i.valorTotal), COUNT(DISTINCT n.id))
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND SUBSTRING(i.cfop, 2, 1) = '1'
        AND i.ncm LIKE :prefixoNcm
      """)
  CompraInsumoTotal totalizarVendasPorNcm(
      @Param("produtorId") UUID produtorId,
      @Param("prefixoNcm") String prefixoNcm,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /** Vendas de um produto pela descrição (#248); padrão em minúsculas, escapado com {@code !}. */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CompraInsumoAgregado(
          i.unidade, SUM(i.quantidade), SUM(i.valorTotal))
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND SUBSTRING(i.cfop, 2, 1) = '1'
        AND LOWER(i.descricao) LIKE :padraoDescricao ESCAPE '!'
      GROUP BY i.unidade
      ORDER BY SUM(i.valorTotal) DESC
      """)
  List<CompraInsumoAgregado> somarVendasPorDescricao(
      @Param("produtorId") UUID produtorId,
      @Param("padraoDescricao") String padraoDescricao,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /** Valor total e notas distintas das vendas pela descrição. */
  @Query("""
      SELECT new br.com.agrocontabil.repository.CompraInsumoTotal(
          SUM(i.valorTotal), COUNT(DISTINCT n.id))
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND SUBSTRING(i.cfop, 2, 1) = '1'
        AND LOWER(i.descricao) LIKE :padraoDescricao ESCAPE '!'
      """)
  CompraInsumoTotal totalizarVendasPorDescricao(
      @Param("produtorId") UUID produtorId,
      @Param("padraoDescricao") String padraoDescricao,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /**
   * NCMs comprados de um insumo por prefixo de NCM — mesmo recorte de {@link #somarComprasPorNcm},
   * maior valor primeiro (issue #170: a Ceres busca na lei os itens de anexo desses NCMs).
   *
   * @param pagina limite (top-N)
   */
  @Query("""
      SELECT i.ncm
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND i.ncm LIKE :prefixoNcm
      GROUP BY i.ncm
      ORDER BY SUM(i.valorTotal) DESC, i.ncm
      """)
  List<String> listarNcmsCompradosPorNcm(
      @Param("produtorId") UUID produtorId,
      @Param("prefixoNcm") String prefixoNcm,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim,
      Pageable pagina);

  /**
   * NCMs mais vendidos pelo produtor — mesmo recorte das vendas ({@link #totalizarVendasPorNcm}:
   * saídas próprias autorizadas, CFOP de venda), maior valor primeiro (issue #347: o atalho da
   * Ceres explica o tratamento dos produtos que ele vende). Só o código: nenhum valor nem comprador.
   *
   * @param pagina limite (top-N)
   */
  @Query("""
      SELECT i.ncm
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND SUBSTRING(i.cfop, 2, 1) = '1'
        AND i.ncm IS NOT NULL
      GROUP BY i.ncm
      ORDER BY SUM(i.valorTotal) DESC, i.ncm
      """)
  List<String> listarNcmsVendidos(
      @Param("produtorId") UUID produtorId,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim,
      Pageable pagina);

  /**
   * NCMs comprados de um insumo pela descrição — mesmo recorte de
   * {@link #somarComprasPorDescricao}, maior valor primeiro (issue #170).
   *
   * @param padraoDescricao padrão {@code LIKE} em minúsculas, já escapado com {@code !}
   * @param pagina          limite (top-N)
   */
  @Query("""
      SELECT i.ncm
      FROM NotaFiscalItem i JOIN i.notaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
        AND LOWER(i.descricao) LIKE :padraoDescricao ESCAPE '!'
      GROUP BY i.ncm
      ORDER BY SUM(i.valorTotal) DESC, i.ncm
      """)
  List<String> listarNcmsCompradosPorDescricao(
      @Param("produtorId") UUID produtorId,
      @Param("padraoDescricao") String padraoDescricao,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim,
      Pageable pagina);

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
   * Quantidade e soma do valor total de <em>todas</em> as notas do filtro — mesmo recorte de
   * {@link #listarResumos}, sem o limite da página (issue #169).
   *
   * @return quantidade e soma (soma {@code null} quando nenhuma nota atende ao filtro)
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.TotalAgregado(COUNT(n), SUM(n.valorTotalNota))
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem IN :origens
        AND n.status IN :status
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      """)
  TotalAgregado totalizarResumos(
      @Param("produtorId") UUID produtorId,
      @Param("origens") Collection<OrigemNotaFiscal> origens,
      @Param("status") Collection<StatusNotaFiscal> status,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

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

  /**
   * Produtores com nota autorizada (de entrada ou de saída) emitida em {@code [inicio, fim)} — os
   * que têm apuração a fechar no mês (issue #140). O mês corrente não tem linha em
   * {@code apuracao_mensal} (é projeção ao vivo, #53): a linha só nasce no fechamento.
   */
  @Query("""
      SELECT DISTINCT n.produtor.id FROM NotaFiscal n
      WHERE n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      """)
  List<UUID> findProdutoresComNotaAutorizadaEntre(
      @Param("inicio") OffsetDateTime inicio, @Param("fim") OffsetDateTime fim);

  /**
   * Vendas próprias autorizadas do produtor em {@code [inicio, fim)}, somadas por tipo de
   * comprador (RN009, issue #145): é o comprador que decide se o Funrural é retido. O documento do
   * destinatário é guardado só com dígitos (14 = CNPJ); UF {@code EX} é venda ao exterior.
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.VendaPorAdquirenteAgregado(
          CASE WHEN n.destinatarioEndereco.uf = 'EX' THEN 'EXTERIOR'
               WHEN LENGTH(n.destinatarioCpfCnpj) = 14 THEN 'PESSOA_JURIDICA'
               ELSE 'PESSOA_FISICA' END,
          COUNT(n), SUM(n.valorTotalProdutos),
          SUM(COALESCE(n.valorTotalIbs, 0)), SUM(COALESCE(n.valorTotalCbs, 0)))
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND n.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND n.dataEmissao >= :inicio
        AND n.dataEmissao < :fim
      GROUP BY CASE WHEN n.destinatarioEndereco.uf = 'EX' THEN 'EXTERIOR'
               WHEN LENGTH(n.destinatarioCpfCnpj) = 14 THEN 'PESSOA_JURIDICA'
               ELSE 'PESSOA_FISICA' END
      """)
  List<VendaPorAdquirenteAgregado> agregarVendasPorAdquirente(
      @Param("produtorId") UUID produtorId,
      @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim);

  /**
   * Só o status da nota, para o "antes" da auditoria (issue #149) — sem carregar a entidade.
   *
   * @param id id da nota
   * @return o status, se a nota existir
   */
  @Query("SELECT n.status FROM NotaFiscal n WHERE n.id = :id")
  Optional<StatusNotaFiscal> findStatusById(@Param("id") UUID id);

  /**
   * Itens de uma nota do produtor, só os campos dos sinais de nota a conferir (issue #276). Nota de
   * outro produtor = lista vazia.
   *
   * @param notaId     nota
   * @param produtorId dono esperado
   * @return itens na ordem do número
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.ItemParaSinal(
          i.numeroItem, i.descricao, i.cfop, i.ncm, i.unidade, i.quantidade, i.valorUnitario,
          i.cstIcms, p.estado, n.destinatarioEndereco.uf, n.destinatarioIe)
      FROM NotaFiscalItem i JOIN i.notaFiscal n JOIN n.produtor p
      WHERE n.id = :notaId AND p.id = :produtorId
      ORDER BY i.numeroItem
      """)
  List<ItemParaSinal> findItensParaSinais(
      @Param("notaId") UUID notaId, @Param("produtorId") UUID produtorId);

  /**
   * Outras notas próprias do mesmo produtor, não canceladas nem rejeitadas, com o mesmo
   * destinatário e o mesmo valor total, emitidas no mesmo dia (issue #276). Os itens são
   * comparados no service. A lista positiva de situações deixa o índice
   * {@code (produtor_id, status, data_emissao)} (0019) buscar o dia em cada situação.
   *
   * @param produtorId   produtor da sessão
   * @param notaId       a nota consultada (fica de fora)
   * @param destinatario documento do destinatário
   * @param valorTotal   valor total
   * @param inicio       começo do dia (BRT), inclusivo
   * @param fim          começo do dia seguinte, exclusivo
   * @param situacoes    situações que contam (todas menos cancelada e rejeitada)
   * @return candidatas, a mais recente primeiro
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.OutraNota(n.id, n.numero, n.serie)
      FROM NotaFiscal n
      WHERE n.produtor.id = :produtorId
        AND n.id <> :notaId
        AND n.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND n.status IN :situacoes
        AND n.destinatarioCpfCnpj = :destinatario
        AND n.valorTotalNota = :valorTotal
        AND n.dataEmissao >= :inicio AND n.dataEmissao < :fim
      ORDER BY n.dataEmissao DESC, n.id DESC
      """)
  List<OutraNota> findPossiveisDuplicadas(@Param("produtorId") UUID produtorId,
      @Param("notaId") UUID notaId, @Param("destinatario") String destinatario,
      @Param("valorTotal") java.math.BigDecimal valorTotal, @Param("inicio") OffsetDateTime inicio,
      @Param("fim") OffsetDateTime fim, @Param("situacoes") Collection<StatusNotaFiscal> situacoes);

  /**
   * Valores unitários do mesmo NCM e unidade (sem caixa nem espaços) nas <strong>vendas</strong>
   * autorizadas do produtor (CFOP de venda, {@code x1xx}; remessa não é venda) numa janela, fora a
   * nota consultada (issue #276), das mais recentes para as mais antigas.
   *
   * @param produtorId produtor da sessão
   * @param notaId     a nota consultada (fica de fora)
   * @param ncm        NCM do item
   * @param unidade    unidade do item, já em maiúsculas e sem espaços nas pontas
   * @param desde      emissão mínima, inclusiva
   * @param ate        emissão máxima, inclusiva
   * @param pagina     teto de itens lidos
   * @return valores com a nota de cada um
   */
  @Query("""
      SELECT new br.com.agrocontabil.repository.ValorUnitarioReferencia(
          i.notaFiscal.id, i.valorUnitario)
      FROM NotaFiscalItem i
      WHERE i.notaFiscal.produtor.id = :produtorId
        AND i.notaFiscal.id <> :notaId
        AND i.notaFiscal.origem = br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA
        AND i.notaFiscal.status = br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA
        AND i.ncm = :ncm AND UPPER(TRIM(i.unidade)) = :unidade
        AND i.cfop LIKE '_1__'
        AND i.valorUnitario IS NOT NULL
        AND i.notaFiscal.dataEmissao >= :desde AND i.notaFiscal.dataEmissao <= :ate
      ORDER BY i.notaFiscal.dataEmissao DESC, i.id DESC
      """)
  List<ValorUnitarioReferencia> findValoresUnitariosDeReferencia(
      @Param("produtorId") UUID produtorId, @Param("notaId") UUID notaId,
      @Param("ncm") String ncm, @Param("unidade") String unidade,
      @Param("desde") OffsetDateTime desde, @Param("ate") OffsetDateTime ate, Pageable pagina);
}
