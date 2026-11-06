package br.com.agrocontabil.service;

import br.com.agrocontabil.model.StatusNotaFiscal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Grupos de status da tela de Notas do contador (issue #341), a mesma partição das caixas
 * {@code CAIXAS_TRIAGEM} do frontend: o {@code /resumo} conta por eles e o número de cada chip
 * bate com o {@code X-Total-Count} da lista filtrada pelos mesmos status.
 *
 * <p>Uma caixa "Todas" existe além destes: ela soma tudo, inclusive {@link #SOMENTE_EM_TODAS}.
 * Status novo em {@link StatusNotaFiscal} tem de entrar em um grupo ou nessa lista de propósito: o
 * teste da partição quebra se ele sumir da contagem.
 */
public enum GrupoNotasCarteira {
  /** Esperam o escritório. */
  REVISAO(StatusNotaFiscal.AGUARDANDO_APROVACAO),
  /** Não passaram pela validação ou foram recusadas pela SEFAZ. */
  TRAVADAS(StatusNotaFiscal.COM_INCONSISTENCIAS, StatusNotaFiscal.REJEITADA),
  /** Ainda com o produtor. */
  RASCUNHOS(StatusNotaFiscal.RASCUNHO),
  /** O escritório devolveu ao produtor. */
  DEVOLVIDAS(StatusNotaFiscal.DEVOLVIDA),
  /** Autorizadas pela SEFAZ. */
  AUTORIZADAS(StatusNotaFiscal.AUTORIZADA),
  /** Canceladas. */
  CANCELADAS(StatusNotaFiscal.CANCELADA);

  /**
   * Estados que nenhuma caixa mostra, só a "Todas": a nota aprovada que espera o produtor gerar o
   * XML, o XML pronto para enviar, e os estados curtos de transmissão e cancelamento.
   */
  public static final Set<StatusNotaFiscal> SOMENTE_EM_TODAS = EnumSet.of(
      StatusNotaFiscal.APROVADA, StatusNotaFiscal.XML_GERADO, StatusNotaFiscal.ENVIANDO,
      StatusNotaFiscal.PENDENTE_SEFAZ, StatusNotaFiscal.CANCELANDO);

  private final Set<StatusNotaFiscal> status;

  GrupoNotasCarteira(StatusNotaFiscal primeiro, StatusNotaFiscal... demais) {
    this.status = EnumSet.of(primeiro, demais);
  }

  /** Status do grupo. */
  public Set<StatusNotaFiscal> status() {
    return status;
  }
}
