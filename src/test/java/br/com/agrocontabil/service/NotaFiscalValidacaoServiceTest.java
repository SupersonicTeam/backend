package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalValidacao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitarios do {@link NotaFiscalValidacaoService} (issue #22).
 *
 * <p>Mockito puro: o {@link NotaFiscalRepository}, o {@link NotaFiscalValidacaoRepository}
 * e o {@link AuthenticatedProdutorProvider} sao mockados.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>registrarInconsistencias — persiste lote unico e marca status COM_INCONSISTENCIAS</li>
 *   <li>registrarInconsistencias — lista vazia/nula nao toca o banco</li>
 *   <li>registrarInconsistencias — nota inexistente lanca ResourceNotFoundException</li>
 *   <li>listarValidacoes — retorna historico mapeado respeitando ownership</li>
 *   <li>listarValidacoes — nota de outro produtor/inexistente lanca 404 (anti-IDOR)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NotaFiscalValidacaoServiceTest {

  @Mock
  private NotaFiscalRepository notaFiscalRepository;

  @Mock
  private NotaFiscalValidacaoRepository validacaoRepository;

  @Mock
  private AuthenticatedProdutorProvider produtorProvider;

  @InjectMocks
  private NotaFiscalValidacaoService validacaoService;

  @Captor
  private ArgumentCaptor<List<NotaFiscalValidacao>> registrosCaptor;

  private Produtor produtor;
  private UUID produtorId;

  @BeforeEach
  void setUp() {
    produtorId = UUID.randomUUID();
    produtor = Produtor.builder()
        .id(produtorId)
        .nomeCompleto("Murilo Produtor")
        .cpfCnpj("12345678901")
        .build();
  }

  // -------------------------------------------------------------------------
  // registrarInconsistencias
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("registrarInconsistencias deve persistir um unico lote e marcar status COM_INCONSISTENCIAS")
  void registrarInconsistenciasShouldPersistBatchAndUpdateStatus() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = NotaFiscal.builder()
        .id(notaId)
        .status(StatusNotaFiscal.RASCUNHO)
        .build();

    List<ErrorDetail> errors = List.of(
        new ErrorDetail("NOTA_INVALIDA_EMISSAO", "item[1].ncm",
            "NCM e obrigatorio", "Informe o NCM", "1234567", "8 digitos"),
        ErrorDetail.of(ErrorCode.NOTA_INVALIDA_EMISSAO, "destinatarioNome",
            "Nome do destinatario e obrigatorio"));

    when(notaFiscalRepository.findById(notaId)).thenReturn(Optional.of(nota));

    validacaoService.registrarInconsistencias(notaId, errors);

    verify(validacaoRepository).saveAll(registrosCaptor.capture());
    List<NotaFiscalValidacao> registros = registrosCaptor.getValue();

    assertThat(registros).hasSize(2);
    // Mesmo lote e mesmo timestamp para todas as inconsistencias da tentativa
    UUID lote = registros.get(0).getLoteValidacao();
    assertThat(lote).isNotNull();
    assertThat(registros).allSatisfy(r -> {
      assertThat(r.getLoteValidacao()).isEqualTo(lote);
      assertThat(r.getNotaFiscal()).isSameAs(nota);
      assertThat(r.getValidadoEm()).isNotNull();
    });
    // Primeiro registro preserva todos os campos do ErrorDetail
    NotaFiscalValidacao primeiro = registros.get(0);
    assertThat(primeiro.getCode()).isEqualTo("NOTA_INVALIDA_EMISSAO");
    assertThat(primeiro.getField()).isEqualTo("item[1].ncm");
    assertThat(primeiro.getMessage()).isEqualTo("NCM e obrigatorio");
    assertThat(primeiro.getSuggestion()).isEqualTo("Informe o NCM");
    assertThat(primeiro.getRejectedValue()).isEqualTo("1234567");
    assertThat(primeiro.getExpectedValue()).isEqualTo("8 digitos");

    // Status da nota atualizado e persistido
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.COM_INCONSISTENCIAS);
    verify(notaFiscalRepository).save(nota);
  }

  @Test
  @DisplayName("registrarInconsistencias com lista vazia nao deve tocar o banco")
  void registrarInconsistenciasWithEmptyListShouldBeNoOp() {
    validacaoService.registrarInconsistencias(UUID.randomUUID(), List.of());

    verifyNoInteractions(notaFiscalRepository, validacaoRepository);
  }

  @Test
  @DisplayName("registrarInconsistencias com lista nula nao deve tocar o banco")
  void registrarInconsistenciasWithNullListShouldBeNoOp() {
    validacaoService.registrarInconsistencias(UUID.randomUUID(), null);

    verifyNoInteractions(notaFiscalRepository, validacaoRepository);
  }

  @Test
  @DisplayName("registrarInconsistencias deve lancar ResourceNotFoundException quando a nota nao existe")
  void registrarInconsistenciasShouldThrowWhenNotaNotFound() {
    UUID notaId = UUID.randomUUID();
    List<ErrorDetail> errors = List.of(
        ErrorDetail.of(ErrorCode.XML_GENERATION_ERROR, "Falha na geracao"));

    when(notaFiscalRepository.findById(notaId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> validacaoService.registrarInconsistencias(notaId, errors))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(notaId.toString());

    verify(validacaoRepository, never()).saveAll(any());
  }

  // -------------------------------------------------------------------------
  // listarValidacoes
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("listarValidacoes deve retornar o historico mapeado quando a nota pertence ao produtor")
  void listarValidacoesShouldReturnMappedHistory() {
    UUID notaId = UUID.randomUUID();
    UUID lote = UUID.randomUUID();
    NotaFiscal nota = NotaFiscal.builder().id(notaId).build();

    NotaFiscalValidacao validacao = NotaFiscalValidacao.builder()
        .id(UUID.randomUUID())
        .notaFiscal(nota)
        .loteValidacao(lote)
        .code("NOTA_INVALIDA_EMISSAO")
        .field("item[1].ncm")
        .message("NCM e obrigatorio")
        .validadoEm(OffsetDateTime.now())
        .build();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId))
        .thenReturn(List.of(validacao));

    List<NotaFiscalValidacaoResponse> historico = validacaoService.listarValidacoes(notaId);

    assertThat(historico).hasSize(1);
    NotaFiscalValidacaoResponse response = historico.get(0);
    assertThat(response.loteValidacao()).isEqualTo(lote);
    assertThat(response.code()).isEqualTo("NOTA_INVALIDA_EMISSAO");
    assertThat(response.field()).isEqualTo("item[1].ncm");
    assertThat(response.message()).isEqualTo("NCM e obrigatorio");
  }

  @Test
  @DisplayName("listarValidacoes deve retornar lista vazia quando a nota nunca teve inconsistencias")
  void listarValidacoesShouldReturnEmptyListWhenNoInconsistencias() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = NotaFiscal.builder().id(notaId).build();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));
    when(validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId))
        .thenReturn(List.of());

    List<NotaFiscalValidacaoResponse> historico = validacaoService.listarValidacoes(notaId);

    assertThat(historico).isEmpty();
  }

  @Test
  @DisplayName("listarValidacoes deve lancar 404 quando a nota nao existe ou pertence a outro produtor")
  void listarValidacoesShouldThrowWhenNotaNotFound() {
    UUID notaId = UUID.randomUUID();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> validacaoService.listarValidacoes(notaId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(notaId.toString());

    verify(validacaoRepository, never()).findByNotaFiscalIdOrderByValidadoEmDesc(any());
  }
}
