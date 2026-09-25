package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.exception.SefazRespostaIndeterminadaException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.sefaz.SefazAmbiente;
import br.com.agrocontabil.sefaz.SefazClient;
import br.com.agrocontabil.sefaz.SefazRetorno;
import br.com.agrocontabil.sefaz.SituacaoSefaz;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sincronização em lote das notas pendentes (issue #83): keyset, isolamento por nota e disjuntor.
 *
 * <p>Antes, {@code sincronizarPendentes} carregava todas as notas pendentes de uma vez e, com a
 * SEFAZ fora do ar, gastava o pior caso de retry em cada uma — 100 notas prendiam o job por horas.
 */
@ExtendWith(MockitoExtension.class)
class SefazServiceSincronizacaoTest {

  @Mock private NotaFiscalRepository notaFiscalRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;
  @Mock private SefazClient sefazClient;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final SefazProperties sefazProperties = new SefazProperties();
  private final Map<UUID, NotaFiscal> banco = new HashMap<>();
  private SefazService sefazService;
  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("Murilo Produtor")
        .cpfCnpj("12345678901")
        .build();
    sefazService = new SefazService(
        notaFiscalRepository, produtorProvider, sefazClient, sefazProperties, eventPublisher,
        new TransactionTemplate(mock(PlatformTransactionManager.class)));
  }

  @Test
  @DisplayName("percorre as pendentes por keyset: o lote seguinte comeca depois do ultimo ID")
  void percorreLotesPorKeyset() {
    sefazProperties.getSync().setLoteTamanho(2);
    List<UUID> ids = pendentes(3);
    when(notaFiscalRepository.findIdsPorStatus(eq(StatusNotaFiscal.PENDENTE_SEFAZ), any()))
        .thenReturn(ids.subList(0, 2));
    when(notaFiscalRepository.findIdsPorStatusApos(
        eq(StatusNotaFiscal.PENDENTE_SEFAZ), eq(ids.get(1)), any()))
        .thenReturn(ids.subList(2, 3));
    when(sefazClient.consultarStatus(any(), any())).thenReturn(autorizada());
    permitirGravacao();

    assertThat(sefazService.sincronizarPendentes()).isEqualTo(3);

    // Lote incompleto (1 < 2) encerra a varredura: sem terceira leitura.
    verify(notaFiscalRepository, times(1)).findIdsPorStatusApos(any(), any(), any());
    ids.forEach(id ->
        assertThat(banco.get(id).getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA));
  }

  @Test
  @DisplayName("lote cheio exato: le mais um lote, que volta vazio, e encerra")
  void loteCheioExatoLeUmLoteVazioEEncerra() {
    sefazProperties.getSync().setLoteTamanho(2);
    List<UUID> ids = pendentes(2);
    when(notaFiscalRepository.findIdsPorStatus(eq(StatusNotaFiscal.PENDENTE_SEFAZ), any()))
        .thenReturn(ids);
    when(notaFiscalRepository.findIdsPorStatusApos(
        eq(StatusNotaFiscal.PENDENTE_SEFAZ), eq(ids.get(1)), any()))
        .thenReturn(List.of());
    when(sefazClient.consultarStatus(any(), any())).thenReturn(autorizada());
    permitirGravacao();

    assertThat(sefazService.sincronizarPendentes()).isEqualTo(2);
  }

  @Test
  @DisplayName("usa o tamanho de lote configurado na consulta de IDs")
  void usaTamanhoDeLoteConfigurado() {
    sefazProperties.getSync().setLoteTamanho(7);
    when(notaFiscalRepository.findIdsPorStatus(
        eq(StatusNotaFiscal.PENDENTE_SEFAZ), any(Pageable.class)))
        .thenAnswer(inv -> {
          assertThat(inv.getArgument(1, Pageable.class).getPageSize()).isEqualTo(7);
          return List.of();
        });

    assertThat(sefazService.sincronizarPendentes()).isZero();
  }

  @Test
  @DisplayName("disjuntor: N falhas de comunicacao seguidas encerram a execucao")
  void disjuntorEncerraAposFalhasSeguidas() {
    sefazProperties.getSync().setMaxFalhasConsecutivas(2);
    List<UUID> ids = pendentes(4);
    when(notaFiscalRepository.findIdsPorStatus(eq(StatusNotaFiscal.PENDENTE_SEFAZ), any()))
        .thenReturn(ids);
    when(sefazClient.consultarStatus(any(), any()))
        .thenThrow(new SefazRespostaIndeterminadaException("SEFAZ fora do ar"));

    assertThat(sefazService.sincronizarPendentes()).isZero();

    // Parou na segunda falha: as duas ultimas notas nem foram consultadas. Com a SEFAZ fora,
    // cada consulta custa o pior caso de retry; insistir so prenderia o job.
    verify(sefazClient, times(2)).consultarStatus(any(), any());
    ids.forEach(id ->
        assertThat(banco.get(id).getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ));
  }

  @Test
  @DisplayName("disjuntor: um sucesso zera a contagem de falhas seguidas")
  void sucessoZeraAsFalhasSeguidas() {
    sefazProperties.getSync().setMaxFalhasConsecutivas(2);
    List<UUID> ids = pendentes(4);
    when(notaFiscalRepository.findIdsPorStatus(eq(StatusNotaFiscal.PENDENTE_SEFAZ), any()))
        .thenReturn(ids);
    SefazRespostaIndeterminadaException fora = new SefazRespostaIndeterminadaException("caiu");
    when(sefazClient.consultarStatus(any(), any()))
        .thenThrow(fora)
        .thenReturn(autorizada())
        .thenThrow(fora)
        .thenReturn(autorizada());
    permitirGravacao();

    // falha, sucesso, falha, sucesso: nunca duas seguidas, entao percorre as quatro.
    assertThat(sefazService.sincronizarPendentes()).isEqualTo(2);
    verify(sefazClient, times(4)).consultarStatus(any(), any());
  }

  @Test
  @DisplayName("falha que nao e de comunicacao isola a nota e nao aciona o disjuntor")
  void falhaDeDadoIsolaANotaSemAcionarDisjuntor() {
    sefazProperties.getSync().setMaxFalhasConsecutivas(1);
    List<UUID> ids = pendentes(3);
    when(notaFiscalRepository.findIdsPorStatus(eq(StatusNotaFiscal.PENDENTE_SEFAZ), any()))
        .thenReturn(ids);
    when(sefazClient.consultarStatus(any(), any()))
        .thenThrow(new IllegalStateException("dado inconsistente nesta nota"))
        .thenReturn(autorizada());
    permitirGravacao();

    // Com limite 1, se a falha de dado contasse para o disjuntor o lote pararia na primeira nota.
    assertThat(sefazService.sincronizarPendentes()).isEqualTo(2);
    assertThat(banco.get(ids.get(0)).getStatus()).isEqualTo(StatusNotaFiscal.PENDENTE_SEFAZ);
    assertThat(banco.get(ids.get(1)).getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(banco.get(ids.get(2)).getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
  }

  @Test
  @DisplayName("nota que saiu de PENDENTE_SEFAZ depois da listagem nao e consultada")
  void notaQueSaiuDePendenteNaoEhConsultada() {
    List<UUID> ids = pendentes(1);
    banco.get(ids.get(0)).setStatus(StatusNotaFiscal.AUTORIZADA);
    when(notaFiscalRepository.findIdsPorStatus(eq(StatusNotaFiscal.PENDENTE_SEFAZ), any()))
        .thenReturn(ids);

    assertThat(sefazService.sincronizarPendentes()).isZero();

    verify(sefazClient, never()).consultarStatus(any(), any());
    verify(notaFiscalRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Cria N notas pendentes num "banco" em memória, com IDs em ordem crescente. */
  private List<UUID> pendentes(int quantidade) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < quantidade; i++) {
      UUID id = new UUID(0L, i + 1L);
      String chave = "412626123456780001955500100000000110000000" + String.format("%02d", i);
      banco.put(id, NotaFiscal.builder()
          .id(id)
          .numero(1L)
          .serie(1)
          .modelo("55")
          .naturezaOperacao("Venda de producao rural")
          .dataEmissao(OffsetDateTime.now())
          .produtor(produtor)
          .destinatarioNome("Jose Comprador")
          .destinatarioCpfCnpj("12345678901")
          .valorTotalProdutos(new BigDecimal("1000.00"))
          .valorTotalNota(new BigDecimal("1000.00"))
          .status(StatusNotaFiscal.PENDENTE_SEFAZ)
          .sefazAmbiente(SefazAmbiente.HOMOLOGACAO)
          .chaveAcesso(chave)
          .build());
      ids.add(id);
    }
    when(notaFiscalRepository.findById(any()))
        .thenAnswer(inv -> Optional.ofNullable(banco.get(inv.getArgument(0, UUID.class))));
    return ids;
  }

  private void permitirGravacao() {
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private static SefazRetorno autorizada() {
    return new SefazRetorno(SituacaoSefaz.AUTORIZADA, "100", "Autorizado o uso da NF-e",
        "141260000000017", OffsetDateTime.now());
  }
}
