package br.com.agrocontabil.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Auditoria de uma pergunta à Ceres (issue #144, RF020; tabela {@code assistente_interacao},
 * changeSet 0025).
 *
 * <p><strong>Não há campo para o texto da pergunta nem da resposta</strong> — e não deve haver:
 * os dois carregam dados do produtor. A auditoria registra o que o sistema <em>fez</em> (rota,
 * funções com parâmetros sanitizados, trechos citados, modelo, custo, desfecho), não o que o
 * usuário disse. Registro imutável: só getters.
 *
 * <p>O produtor é guardado como UUID (sem {@code @ManyToOne}): a auditoria nunca navega para o
 * produtor, e a FK real vem do Liquibase.
 */
@Entity
@Table(name = "assistente_interacao")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistenteInteracao {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "produtor_id", nullable = false, updatable = false)
  private UUID produtorId;

  /** Camada que respondeu: DADOS, LEGISLACAO, MISTO, FORA_DE_ESCOPO. */
  @Column(name = "rota", length = 20, nullable = false, updatable = false)
  private String rota;

  @Enumerated(EnumType.STRING)
  @Column(name = "desfecho", length = 40, nullable = false, updatable = false,
      columnDefinition = "VARCHAR(40)")
  private DesfechoInteracao desfecho;

  /** Desfecho da parte de dados do MISTO; nulo nas outras rotas (changeSet 0030). */
  @Enumerated(EnumType.STRING)
  @Column(name = "desfecho_dados", length = 40, updatable = false,
      columnDefinition = "VARCHAR(40)")
  private DesfechoInteracao desfechoDados;

  /** Desfecho da parte de lei do MISTO; nulo nas outras rotas (changeSet 0030). */
  @Enumerated(EnumType.STRING)
  @Column(name = "desfecho_legislacao", length = 40, updatable = false,
      columnDefinition = "VARCHAR(40)")
  private DesfechoInteracao desfechoLegislacao;

  /**
   * A parte de lei do MISTO recebeu a pergunta legal separada pelo classificador. Só o fato: o
   * texto da pergunta, reescrita ou não, nunca é gravado.
   */
  @Column(name = "pergunta_lei_reescrita", updatable = false)
  private Boolean perguntaLeiReescrita;

  /**
   * Camada que decidiu a rota (#265, changeSet 0050): {@code REGRA}, {@code EMBEDDING},
   * {@code LLM}... Nula se a pergunta acabou antes da classificação.
   */
  @Column(name = "camada_classificacao", length = 20, updatable = false)
  private String camadaClassificacao;

  /**
   * Desfecho da conferência de fidelidade da resposta de lei (#269, changeSet 0065): aprovada,
   * aprovada com a citação corrigida ou o motivo da reprovação. Só o enum, nunca o texto (#144).
   * Nulo quando a conferência não rodou (flag desligada, outra rota, sem trecho, resposta cortada).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "fidelidade_lei", length = 40, updatable = false,
      columnDefinition = "VARCHAR(40)")
  private DesfechoFidelidadeLei fidelidadeLei;

  /**
   * Tempo da chamada do juiz da fidelidade da lei (#269, camada 2, changeSet 0065); nulo se ele não
   * rodou. Com ele, {@code latenciaMs} é o da pergunta sem o juiz: a soma é o total.
   */
  @Column(name = "latencia_juiz_ms", updatable = false)
  private Long latenciaJuizMs;

  /** Tokens de entrada do juiz (#269); nulo se ele não rodou. Fora de {@code tokensEntrada}. */
  @Column(name = "tokens_entrada_juiz", updatable = false)
  private Integer tokensEntradaJuiz;

  /** Tokens de saída do juiz (#269); nulo se ele não rodou. Fora de {@code tokensSaida}. */
  @Column(name = "tokens_saida_juiz", updatable = false)
  private Integer tokensSaidaJuiz;

  /**
   * Por que a reconferência de lei da continuidade terminou como terminou (#584, changeSet 0066):
   * código de lista fechada, nunca texto (#144). Nulo fora da reconferência de lei.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "reconferencia_lei", length = 40, updatable = false,
      columnDefinition = "VARCHAR(40)")
  private MotivoReconferenciaLei reconferenciaLei;

  /** Dispositivos que a resposta anterior citava (#584); nulo fora da reconferência de lei. */
  @Column(name = "reconferencia_dispositivos", updatable = false)
  private Integer reconferenciaDispositivos;

  /** Desses, quantos trechos vigentes a base textual achou (#584). */
  @Column(name = "reconferencia_trechos_citados", updatable = false)
  private Integer reconferenciaTrechosCitados;

  /** Resultado da busca vetorial da reconferência (#584); nulo se não chegou a ela. */
  @Enumerated(EnumType.STRING)
  @Column(name = "reconferencia_busca", length = 10, updatable = false,
      columnDefinition = "VARCHAR(10)")
  private BuscaReconferenciaLei reconferenciaBusca;

  /** Trechos que a busca vetorial devolveu (#584); nulo se ela não respondeu. */
  @Column(name = "reconferencia_trechos_busca", updatable = false)
  private Integer reconferenciaTrechosBusca;

  /** Trechos dos itens de anexo das compras, só no MISTO (#584). */
  @Column(name = "reconferencia_trechos_compras", updatable = false)
  private Integer reconferenciaTrechosCompras;

  /** Trechos que foram ao prompt da reconferência, depois dos tetos (#584). */
  @Column(name = "reconferencia_trechos_prompt", updatable = false)
  private Integer reconferenciaTrechosPrompt;

  /** Desses, os que a resposta anterior citava (#584). */
  @Column(name = "reconferencia_trechos_prompt_citados", updatable = false)
  private Integer reconferenciaTrechosPromptCitados;

  /** Caracteres de texto de lei no prompt da reconferência (#584). */
  @Column(name = "reconferencia_caracteres_trechos", updatable = false)
  private Integer reconferenciaCaracteresTrechos;

  /** Tokens de entrada da chamada da concordância do CORRIJO, já somados aos da pergunta (#584). */
  @Column(name = "reconferencia_tokens_concordancia", updatable = false)
  private Integer reconferenciaTokensConcordancia;

  /** Desfecho da rota de lei normal da concordância (#584); nulo se ela não rodou. */
  @Enumerated(EnumType.STRING)
  @Column(name = "reconferencia_concordancia", length = 40, updatable = false,
      columnDefinition = "VARCHAR(40)")
  private DesfechoInteracao reconferenciaConcordancia;

  /** Funções usadas e parâmetros (JSON), já sanitizados. */
  @Column(name = "funcoes", columnDefinition = "TEXT", updatable = false)
  private String funcoes;

  /** Quantas funções o modelo pediu, inclusive as que falharam. */
  @Column(name = "chamadas_funcao", nullable = false, updatable = false)
  private int chamadasFuncao;

  /** Ids dos trechos de lei citados (JSON), a partir da issue #156. */
  @Column(name = "trechos_citados", columnDefinition = "TEXT", updatable = false)
  private String trechosCitados;

  /** {@code provedor/modelo}, ex.: {@code ollama/qwen3:4b}. */
  @Column(name = "modelo", length = 120, updatable = false)
  private String modelo;

  @Column(name = "latencia_ms", nullable = false, updatable = false)
  private long latenciaMs;

  @Column(name = "tokens_entrada", updatable = false)
  private Integer tokensEntrada;

  @Column(name = "tokens_saida", updatable = false)
  private Integer tokensSaida;

  /** Tokens estimados por caracteres: o provedor não informou o uso (issue #166). */
  @Column(name = "tokens_estimados", nullable = false, updatable = false)
  private boolean tokensEstimados;

  /** Tamanho da pergunta em caracteres — métrica, não conteúdo. */
  @Column(name = "tamanho_pergunta", nullable = false, updatable = false)
  private int tamanhoPergunta;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private OffsetDateTime criadoEm;

  /**
   * Desfecho da explicação depois da resposta de dados (#268, changeSet 0064); nulo quando ela não
   * se aplica. Gravado depois, pelo {@code AssistenteInteracaoRepository.registrarExplicacao}: a
   * pergunta é auditada antes de a resposta sair, e a explicação vem depois.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "desfecho_explicacao", length = 60, insertable = false, updatable = false,
      columnDefinition = "VARCHAR(60)")
  private DesfechoExplicacao desfechoExplicacao;

  /** Tempo da explicação, do começo dela ao desfecho (#268). */
  @Column(name = "latencia_explicacao_ms", insertable = false, updatable = false)
  private Long latenciaExplicacaoMs;

  /** Tokens de entrada da chamada da explicação; nulo se a chamada não terminou (#268). */
  @Column(name = "tokens_entrada_explicacao", insertable = false, updatable = false)
  private Integer tokensEntradaExplicacao;

  /** Tokens de saída da chamada da explicação; nulo se a chamada não terminou (#268). */
  @Column(name = "tokens_saida_explicacao", insertable = false, updatable = false)
  private Integer tokensSaidaExplicacao;
}
