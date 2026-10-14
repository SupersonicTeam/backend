package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.FonteDado;
import br.com.agrocontabil.assistente.PerguntaInterrompidaException;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Itens de anexo da lei que tratam do que o produtor comprou, para a parte de lei do MISTO
 * (issue #170).
 *
 * <p>Na pergunta "os fertilizantes que comprei têm redução?", a busca por similaridade acha o
 * artigo e um item de anexo qualquer (no teste do PR #168, só o item 1, biofertilizantes). O item
 * certo é o do NCM da compra. Então, quando a parte de dados consultou as compras de um insumo,
 * os NCMs dessas compras são buscados nos anexos e os itens entram junto com os outros trechos.
 *
 * <p><strong>O que sai dos dados:</strong> só o NCM, e só para escolher trechos. O modelo da parte
 * de lei recebe apenas texto de lei (público), nenhum valor, nota ou dado pessoal. Mas a
 * <em>escolha</em> dos itens revela ao provedor do modelo as categorias que o produtor comprou —
 * o mesmo que a parte de dados já envia (ela manda os totais de compra) e nada além disso. Com
 * provedor externo, é dado de negócio do produtor indo para fora (ver #148, LGPD). O termo e o
 * período vêm da {@link FonteDado} que a função já registrou, com os parâmetros validados por ela.
 *
 * <p>É um complemento: qualquer falha aqui é registrada e a parte de lei segue só com a busca por
 * similaridade. Interrupção da pergunta, não — ela para tudo.
 */
@Component
public class TrechosDasCompras {

  /** Função de dados cujas consultas indicam o insumo comprado. */
  static final String FUNCAO_COMPRAS = "totalComprasPorInsumo";

  /** NCMs mais comprados considerados (maior valor primeiro). */
  static final int MAX_NCMS = 3;

  /** Itens de anexo acrescentados no máximo. */
  static final int MAX_TRECHOS = 3;

  private static final Logger log = LoggerFactory.getLogger(TrechosDasCompras.class);

  private final DadosProdutorPorta dados;
  private final BuscaTextualLegislacaoService busca;

  /** Constrói sobre a porta de dados e a busca textual. */
  public TrechosDasCompras(DadosProdutorPorta dados, BuscaTextualLegislacaoService busca) {
    this.dados = dados;
    this.busca = busca;
  }

  /**
   * Itens de anexo dos NCMs comprados, se a parte de dados consultou compras de um insumo.
   *
   * @param contexto contexto da pergunta (produtor, data, fontes já registradas)
   * @return itens de anexo, possivelmente vazio; nunca lança, salvo interrupção
   */
  public List<TrechoRecuperado> buscar(ContextoAssistente contexto) {
    contexto.verificarAtiva();
    Optional<FonteDado> compras = contexto.fontes().stream()
        .filter(fonte -> FUNCAO_COMPRAS.equals(fonte.funcao()))
        .findFirst();
    if (compras.isEmpty()) {
      return List.of();
    }
    Map<String, String> parametros = compras.get().parametros();
    try {
      List<String> ncms = dados.ncmsComprados(contexto.produtorId(),
          parametros.get("descricaoOuNcm"), LocalDate.parse(parametros.get("dataInicio")),
          LocalDate.parse(parametros.get("dataFim")), MAX_NCMS);
      if (ncms.isEmpty()) {
        return List.of();
      }
      contexto.verificarAtiva();
      return busca.buscarAnexosPorNcm(ncms, contexto.hoje(), MAX_TRECHOS);
    } catch (PerguntaInterrompidaException e) {
      throw e;
    } catch (RuntimeException e) {
      // So o tipo: a mensagem pode trazer o termo ou parametros da consulta.
      log.warn("Ceres: itens de anexo pelas compras indisponiveis: {}", e.getClass().getName());
      return List.of();
    }
  }
}
