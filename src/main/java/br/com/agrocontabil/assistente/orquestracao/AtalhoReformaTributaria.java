package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PerguntaInterrompidaException;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resposta do atalho "O que muda para mim com o IBS e o CBS?" (#343, explicada na #347): o texto
 * explicado do {@link TextoReformaTributaria}, com o dispositivo de cada afirmação, personalizado
 * com o tipo do produtor, o escritório e o tratamento dos produtos que ele mais vendeu no ano,
 * pela tabela oficial.
 *
 * <p>A pergunta é ampla demais para a busca por similaridade (na VPS em 2026-11-04 saía "não
 * encontrei fundamento legal"), e um parágrafo do modelo sobre trechos fixos foi testado na #347 e
 * descartado: com o {@code qwen3:4b-instruct} toda rodada trazia uma frase errada que os
 * validadores não pegam. A resposta é do backend, correta por construção, e responde com o modelo
 * fora do ar: zero chamadas ao modelo, fora da cota da Ceres (o {@code AssistenteService} não
 * reserva o teto para atalho).
 */
@Component
public class AtalhoReformaTributaria {

  /** Quantos produtos mais vendidos entram no texto. */
  static final int MAX_PRODUTOS = 3;

  private static final Logger log = LoggerFactory.getLogger(AtalhoReformaTributaria.class);

  private final BuscaTextualLegislacaoService busca;
  private final DadosProdutorPorta dados;
  private final TratamentoProdutoPorta tratamento;

  /** Constrói sobre a busca por dispositivo exato e as portas de dados e de tratamento. */
  public AtalhoReformaTributaria(BuscaTextualLegislacaoService busca, DadosProdutorPorta dados,
      TratamentoProdutoPorta tratamento) {
    this.busca = busca;
    this.dados = dados;
    this.tratamento = tratamento;
  }

  /**
   * A resposta do atalho: o texto e as citações com o texto da base (sem a base, o texto segue
   * sozinho: ele já nomeia cada dispositivo).
   *
   * @param contexto contexto da pergunta (produtor, "hoje" da vigência, trechos da auditoria)
   * @return resposta de legislação aceita, sem fontes de dados
   */
  public ResultadoOrquestracao responder(ContextoAssistente contexto) {
    TextoReformaTributaria.Texto texto = TextoReformaTributaria.montar(perfil(contexto));
    return new ResultadoOrquestracao(Intencao.LEGISLACAO, texto.texto(),
        DesfechoInteracao.RESPONDIDA,
        CitacoesFixas.buscar(busca, texto.dispositivos(), contexto, "Atalho IBS/CBS"), false);
  }

  /**
   * Os dispositivos fixos do texto do atalho (#499): a reconferência de um par de atalho reanexa
   * as citações deles, sem montar o texto de novo.
   *
   * @return {@code "norma|dispositivo"}
   */
  public static List<String> dispositivosFixos() {
    return TextoReformaTributaria.DISPOSITIVOS_FIXOS;
  }

  /**
   * O que o texto sabe do produtor. Cada parte que falhar fica de fora, sem derrubar o atalho;
   * interrupção da pergunta sobe.
   */
  TextoReformaTributaria.Perfil perfil(ContextoAssistente contexto) {
    contexto.verificarAtiva();
    LocalDate hoje = contexto.hoje();
    DadosProdutorPorta.PerfilAtalho perfil;
    try {
      perfil = dados.perfilDoAtalho(
          contexto.produtorId(), hoje.withDayOfYear(1), hoje, MAX_PRODUTOS);
    } catch (PerguntaInterrompidaException e) {
      throw e;
    } catch (RuntimeException e) {
      // So o tipo: a mensagem pode trazer dados da consulta.
      log.warn("Ceres: perfil do atalho IBS/CBS indisponivel: {}", e.getClass().getName());
      return TextoReformaTributaria.Perfil.DESCONHECIDO;
    }
    return new TextoReformaTributaria.Perfil(perfil.tipo(), perfil.atendidoPorEscritorio(),
        produtos(perfil.ncmsMaisVendidos(), contexto));
  }

  /** Tratamento de cada NCM pela tabela oficial; o que falhar ou não for conhecido fica de fora. */
  private List<Produto> produtos(List<String> ncms, ContextoAssistente contexto) {
    List<Produto> produtos = new ArrayList<>();
    for (String ncm : ncms) {
      contexto.verificarAtiva();
      try {
        tratamento.consultar(ncm, contexto.hoje()).produtos().stream()
            .filter(p -> p.ncmConhecido() && ncm.equals(p.ncm()))
            .findFirst()
            .ifPresent(produtos::add);
      } catch (PerguntaInterrompidaException e) {
        throw e;
      } catch (RuntimeException e) {
        log.warn("Ceres: tratamento de produto do atalho IBS/CBS indisponivel: {}",
            e.getClass().getName());
      }
    }
    return produtos;
  }
}
