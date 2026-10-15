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
}
