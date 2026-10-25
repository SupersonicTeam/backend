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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Registro de auditoria de uma operação crítica (issue #149, RNF012), guardado por 5 anos.
 *
 * <p><strong>Sem dado pessoal.</strong> Quem fez (id e papel), sobre o quê (tipo e id do recurso,
 * produtor afetado), o estado antes e depois (ex.: o status da nota) e, na falha, o código do
 * erro. Nada de nome, documento, e-mail, senha ou corpo da requisição: os ids são pseudônimos, e é
 * isso que permite guardar 5 anos sem conflitar com a exclusão de dados pessoais (#148).
 *
 * <p><strong>Imutável.</strong> Sem setters e com todas as colunas {@code updatable = false}: um
 * registro só nasce (pelo {@code AuditoriaAspect}) e só sai pela retenção.
 */
@Entity
@Table(name = "registro_auditoria")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@ToString
public class RegistroAuditoria {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "ocorrido_em", nullable = false, updatable = false)
  private OffsetDateTime ocorridoEm;

  @Enumerated(EnumType.STRING)
  @Column(name = "acao", nullable = false, updatable = false, length = 40)
  private AcaoAuditada acao;

  @Enumerated(EnumType.STRING)
  @Column(name = "recurso", nullable = false, updatable = false, length = 20)
  private RecursoAuditado recurso;

  /** Recurso afetado; nulo quando a operação não tem um só (importação em lote). */
  @Column(name = "recurso_id", updatable = false)
  private UUID recursoId;

  /** Id do produtor ou do contador que agiu; nulo sem identidade. */
  @Column(name = "usuario_id", updatable = false)
  private UUID usuarioId;

  @Column(name = "papel", updatable = false, length = 20)
  private String papel;

  /** Produtor cujos dados a operação tocou: é por ele que o contador consulta. */
  @Column(name = "produtor_id", updatable = false)
  private UUID produtorId;

  /** Escritório do contador que agiu (issue #228); nulo para o produtor. */
  @Column(name = "escritorio_id", updatable = false)
  private UUID escritorioId;

  @Column(name = "sucesso", nullable = false, updatable = false)
  private boolean sucesso;

  /** Código do erro ({@code ErrorCode}) ou nome da exceção, na falha. Nunca a mensagem. */
  @Column(name = "codigo_erro", updatable = false, length = 60)
  private String codigoErro;

  @Column(name = "estado_antes", updatable = false, length = 40)
  private String estadoAntes;

  @Column(name = "estado_depois", updatable = false, length = 40)
  private String estadoDepois;
}
