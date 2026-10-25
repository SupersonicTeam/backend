package br.com.agrocontabil.service;

import br.com.agrocontabil.config.AntivirusProperties;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Pendencia;
import br.com.agrocontabil.model.PendenciaAnexo;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.RegistroAuditoria;
import br.com.agrocontabil.model.SituacaoAnexo;
import br.com.agrocontabil.model.StatusPendencia;
import br.com.agrocontabil.repository.AnexoEmVerificacao;
import br.com.agrocontabil.repository.PendenciaAnexoRepository;
import br.com.agrocontabil.repository.PendenciaRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.VerificacaoAntivirus.ResultadoVerificacao;
import br.com.agrocontabil.service.auditoria.AuditoriaService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Antivírus dos anexos das pendências (issue #227): o veredito, o que ele faz com o anexo e o
 * reprocessamento dos que ficaram em verificação.
 *
 * <p><strong>Falha fechada.</strong> Sem veredito (ClamAV fora do ar, resposta sem
 * {@code OK}/{@code FOUND}) o anexo fica {@code EM_VERIFICACAO} e não pode ser baixado; o
 * {@link VerificacaoAnexoJob} tenta de novo. {@code LIMPO} libera o download e leva a pendência
 * de {@code PENDENTE} a {@code ENVIADA}. {@code INFECTADO} descarta o conteúdo (o CHECK
 * {@code ck_pendencia_anexo_conteudo} garante) e fica só o hash e a assinatura, com registro de
 * auditoria {@code PENDENCIA_ANEXO_INFECTADO}.
 *
 * <p>A chamada ao antivírus é sempre fora de transação: até 5 MB por TCP, sem conexão do pool
 * presa (a lição da #97/#99).
 */
@Service
public class VerificacaoAnexoService {

  private static final Logger log = LoggerFactory.getLogger(VerificacaoAnexoService.class);

  private final VerificacaoAntivirus antivirus;
  private final PendenciaAnexoRepository anexoRepository;
  private final PendenciaRepository pendenciaRepository;
  private final AuditoriaService auditoria;
  private final TransactionTemplate transacao;
  private final TransactionTemplate leitura;
  private final Clock clock;
  private final int loteTamanho;

  public VerificacaoAnexoService(VerificacaoAntivirus antivirus,
      PendenciaAnexoRepository anexoRepository, PendenciaRepository pendenciaRepository,
      AuditoriaService auditoria, PlatformTransactionManager transactionManager, Clock clock,
      AntivirusProperties propriedades) {
    this.antivirus = antivirus;
    this.anexoRepository = anexoRepository;
    this.pendenciaRepository = pendenciaRepository;
    this.auditoria = auditoria;
    this.transacao = new TransactionTemplate(transactionManager);
    this.leitura = new TransactionTemplate(transactionManager);
    this.leitura.setReadOnly(true);
    this.clock = clock;
    this.loteTamanho = propriedades.loteTamanho();
  }

  /** Resultado de uma execução do reprocessamento. */
  public record ResumoVerificacao(int limpos, int infectados, int semVeredito,
      boolean antivirusForaDoAr) {
  }

  /**
   * Veredito do antivírus para um arquivo novo. Fora de transação.
   *
   * @param conteudo bytes
   * @return o veredito, ou vazio quando o antivírus não deu um (o anexo fica em verificação)
   */
  public Optional<ResultadoVerificacao> verificar(byte[] conteudo) {
    try {
      return Optional.of(antivirus.verificar(conteudo));
    } catch (AntivirusIndisponivelException e) {
      log.warn("Anexo fica EM_VERIFICACAO (o job tenta de novo): {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Aplica o veredito ao anexo e à pendência, na transação de quem chama (com a pendência sob
   * lock).
   *
   * @param anexo     anexo (novo ou em verificação)
   * @param pendencia pendência do anexo
   * @param resultado veredito
   */
  void aplicar(PendenciaAnexo anexo, Pendencia pendencia, ResultadoVerificacao resultado) {
    anexo.setVerificadoEm(OffsetDateTime.now(clock));
    if (resultado.infectado()) {
      anexo.setSituacao(SituacaoAnexo.INFECTADO);
      anexo.setAssinaturaVirus(resultado.assinatura());
      anexo.setConteudo(null);
      return;
    }
    anexo.setSituacao(SituacaoAnexo.LIMPO);
    liberar(pendencia);
  }

  /**
   * Anexo limpo: a pendência {@code PENDENTE} vai a {@code ENVIADA}. Concluída, cancelada ou já
   * enviada segue como está — o anexo só fica disponível.
   */
  private static void liberar(Pendencia pendencia) {
    if (pendencia.getStatus() == StatusPendencia.PENDENTE) {
      pendencia.setStatus(StatusPendencia.ENVIADA);
    }
  }

  /**
   * Registra a ameaça na auditoria (fora da transação do anexo). O usuário é quem enviou o
   * arquivo; o produtor afetado, o dono da pendência — o escritório vê em {@code /api/auditoria}.
   *
   * @param anexoId    anexo infectado
   * @param enviadoPor quem enviou
   * @param assinatura nome da assinatura
   * @param pendencia  pendência
   */
  void auditarInfectado(UUID anexoId, UUID enviadoPor, String assinatura, Pendencia pendencia) {
    log.warn("Antivirus achou ameaca no anexo {} da pendencia {}: {} (conteudo descartado)",
        anexoId, pendencia.getId(), assinatura);
    auditoria.registrar(RegistroAuditoria.builder()
        .ocorridoEm(OffsetDateTime.now(clock))
        .acao(AcaoAuditada.PENDENCIA_ANEXO_INFECTADO)
        .recurso(RecursoAuditado.PENDENCIA)
        .recursoId(pendencia.getId())
        .usuarioId(enviadoPor)
        .papel(enviadoPor == null ? null : PapelUsuario.PRODUTOR.name())
        .produtorId(pendencia.getProdutorId())
        .sucesso(true)
        .estadoDepois(SituacaoAnexo.INFECTADO.name())
        .build());
  }

  /**
   * Verifica um lote de anexos em verificação, do nunca tentado ao tentado há mais tempo. Para no
   * primeiro sinal de antivírus fora do ar (os outros falhariam igual); uma recusa de um arquivo
   * só ({@code ERROR} do clamd) passa para o próximo. Cada anexo em transações curtas próprias.
   *
   * @return o que houve
   */
  public ResumoVerificacao reprocessarPendentes() {
    List<UUID> ids = anexoRepository.idsEmVerificacao(PageRequest.of(0, loteTamanho));
    int limpos = 0;
    int infectados = 0;
    int semVeredito = 0;
    for (UUID id : ids) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
      Optional<AnexoEmVerificacao> lido = leitura.execute(
          status -> anexoRepository.findEmVerificacao(id));
      if (lido == null || lido.isEmpty()) {
        continue;
      }
      AnexoEmVerificacao anexo = lido.get();
      ResultadoVerificacao resultado;
      try {
        resultado = antivirus.verificar(anexo.conteudo());
      } catch (AntivirusIndisponivelException e) {
        semVeredito++;
        transacao.executeWithoutResult(status ->
            anexoRepository.registrarTentativa(id, OffsetDateTime.now(clock)));
        if (e.isForaDoAr()) {
          log.warn("Antivirus fora do ar; reverificacao dos anexos para ate a proxima execucao: "
              + "{}", e.getMessage());
          return new ResumoVerificacao(limpos, infectados, semVeredito, true);
        }
        log.error("Antivirus sem veredito para o anexo {} (segue em verificacao): {}", id,
            e.getMessage());
        continue;
      }
      Optional<Pendencia> pendencia = transacao.execute(status -> concluir(anexo, resultado));
      if (pendencia == null || pendencia.isEmpty()) {
        continue;
      }
      if (resultado.infectado()) {
        infectados++;
        auditarInfectado(id, anexo.enviadoPor(), resultado.assinatura(), pendencia.get());
      } else {
        limpos++;
      }
    }
    return new ResumoVerificacao(limpos, infectados, semVeredito, false);
  }

  /**
   * Grava o veredito sob o lock da pendência. UPDATE condicional: o anexo pode ter saído (LGPD,
   * retenção) ou já ter veredito, e aí nada muda.
   */
  private Optional<Pendencia> concluir(AnexoEmVerificacao anexo, ResultadoVerificacao resultado) {
    Optional<Pendencia> pendencia = pendenciaRepository.findByIdForUpdate(anexo.pendenciaId());
    if (pendencia.isEmpty()) {
      return Optional.empty();
    }
    OffsetDateTime agora = OffsetDateTime.now(clock);
    int alterados = resultado.infectado()
        ? anexoRepository.marcarInfectado(anexo.id(), resultado.assinatura(), agora)
        : anexoRepository.marcarLimpo(anexo.id(), agora);
    if (alterados == 0) {
      return Optional.empty();
    }
    if (!resultado.infectado()) {
      liberar(pendencia.get());
    }
    return pendencia;
  }
}
