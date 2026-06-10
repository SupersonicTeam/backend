package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.NotaFiscalItemRequest;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.exception.NotaFiscalNaoEditavelException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitarios do {@link NotaFiscalService}.
 *
 * <p>Usa Mockito puro (sem contexto Spring) — o {@link AuthenticatedProdutorProvider}
 * e o {@link NotaFiscalRepository} sao mockados.
 *
 * <p>Cenarios cobertos:
 * <ul>
 *   <li>create — happy path: nota criada com totais calculados e status RASCUNHO</li>
 *   <li>findById — happy path: nota encontrada retorna DTO correto</li>
 *   <li>findById — nota nao encontrada lanca ResourceNotFoundException</li>
 *   <li>update — bloqueado quando status nao e RASCUNHO (lanca NotaFiscalNaoEditavelException)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NotaFiscalServiceTest {

  @Mock
  private NotaFiscalRepository notaFiscalRepository;

  @Mock
  private AuthenticatedProdutorProvider produtorProvider;

  @InjectMocks
  private NotaFiscalService notaFiscalService;

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
  // create — happy path
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("create deve persistir nota com totais calculados e retornar response com id")
  void createShouldPersistNotaAndReturnResponse() {
    when(produtorProvider.get()).thenReturn(produtor);

    NotaFiscalRequest request = buildRequest();
    NotaFiscal notaSalva = buildNotaSalva();

    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenReturn(notaSalva);

    NotaFiscalResponse response = notaFiscalService.create(request);

    assertThat(response).isNotNull();
    assertThat(response.id()).isEqualTo(notaSalva.getId());
    assertThat(response.status()).isEqualTo(StatusNotaFiscal.RASCUNHO);
    assertThat(response.valorTotalProdutos()).isEqualByComparingTo("1000.00");
    assertThat(response.valorTotalNota()).isEqualByComparingTo("1000.00");
    assertThat(response.produtorId()).isEqualTo(produtorId);

    verify(notaFiscalRepository).save(any(NotaFiscal.class));
  }

  // -------------------------------------------------------------------------
  // findById — happy path e not found
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("findById deve retornar DTO quando nota existe e pertence ao produtor")
  void findByIdShouldReturnResponseWhenFound() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaSalva();
    nota.setId(notaId);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    NotaFiscalResponse response = notaFiscalService.findById(notaId);

    assertThat(response.id()).isEqualTo(notaId);
    assertThat(response.numero()).isEqualTo(1L);
    assertThat(response.status()).isEqualTo(StatusNotaFiscal.RASCUNHO);
  }

  @Test
  @DisplayName("findById deve lancar ResourceNotFoundException quando nota nao encontrada")
  void findByIdShouldThrowWhenNotFound() {
    UUID notaId = UUID.randomUUID();

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> notaFiscalService.findById(notaId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(notaId.toString());
  }

  // -------------------------------------------------------------------------
  // update — bloqueado quando nao e RASCUNHO
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("update deve lancar NotaFiscalNaoEditavelException quando status nao e RASCUNHO")
  void updateShouldThrowWhenStatusIsNotRascunho() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal notaAutorizada = buildNotaSalva();
    notaAutorizada.setId(notaId);
    notaAutorizada.setStatus(StatusNotaFiscal.AUTORIZADA);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(notaAutorizada));

    NotaFiscalRequest request = buildRequest();

    assertThatThrownBy(() -> notaFiscalService.update(notaId, request))
        .isInstanceOf(NotaFiscalNaoEditavelException.class)
        .hasMessageContaining("AUTORIZADA");
  }

  @Test
  @DisplayName("update deve lancar NotaFiscalNaoEditavelException quando status e XML_GERADO")
  void updateShouldThrowWhenStatusIsXmlGerado() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal notaXmlGerado = buildNotaSalva();
    notaXmlGerado.setId(notaId);
    notaXmlGerado.setStatus(StatusNotaFiscal.XML_GERADO);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(notaXmlGerado));

    NotaFiscalRequest request = buildRequest();

    assertThatThrownBy(() -> notaFiscalService.update(notaId, request))
        .isInstanceOf(NotaFiscalNaoEditavelException.class)
        .hasMessageContaining(notaId.toString());
  }

  // -------------------------------------------------------------------------
  // findAll — retorna somente notas do produtor autenticado
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("findAll deve retornar somente notas do produtor autenticado")
  void findAllShouldReturnOnlyNotasOfAuthenticatedProdutor() {
    NotaFiscal nota = buildNotaSalva();
    Page<NotaFiscal> page = new PageImpl<>(List.of(nota));
    var pageable = PageRequest.of(0, 20);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByProdutorId(produtorId, pageable)).thenReturn(page);

    Page<NotaFiscalResponse> result = notaFiscalService.findAll(pageable);

    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent().get(0).produtorId()).isEqualTo(produtorId);
    verify(notaFiscalRepository).findByProdutorId(produtorId, pageable);
  }

  // -------------------------------------------------------------------------
  // findById — nota de outro produtor deve retornar 404 (anti-IDOR)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("findById deve lancar ResourceNotFoundException quando nota pertence a outro produtor")
  void findByIdShouldThrowWhenNotaBelongsToAnotherProdutor() {
    UUID notaId = UUID.randomUUID();

    when(produtorProvider.get()).thenReturn(produtor);
    // findByIdAndProdutorId retorna vazio — simula nota de outro produtor
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> notaFiscalService.findById(notaId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining(notaId.toString());
  }

  // -------------------------------------------------------------------------
  // delete — happy path e bloqueado quando nao e RASCUNHO
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("delete deve excluir nota quando status e RASCUNHO")
  void deleteShouldDeleteNotaWhenStatusIsRascunho() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaSalva();
    nota.setId(notaId);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    notaFiscalService.delete(notaId);

    verify(notaFiscalRepository).delete(nota);
  }

  @Test
  @DisplayName("delete deve lancar NotaFiscalNaoEditavelException quando status nao e RASCUNHO")
  void deleteShouldThrowWhenStatusIsNotRascunho() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal nota = buildNotaSalva();
    nota.setId(notaId);
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(nota));

    assertThatThrownBy(() -> notaFiscalService.delete(notaId))
        .isInstanceOf(NotaFiscalNaoEditavelException.class)
        .hasMessageContaining("AUTORIZADA");

    verify(notaFiscalRepository, never()).delete(any(NotaFiscal.class));
  }

  // -------------------------------------------------------------------------
  // update — recalculo de totais apos troca de itens
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("update deve recalcular totais quando itens sao substituidos")
  void updateShouldRecalculateTotalsAfterItemReplacement() {
    UUID notaId = UUID.randomUUID();
    NotaFiscal notaExistente = buildNotaSalva(); // total original = 1000.00
    notaExistente.setId(notaId);

    // Novo item: 10 unidades a 50.00 = 500.00
    NotaFiscalItemRequest novoItem = new NotaFiscalItemRequest(
        1,
        null,
        "Milho em graos",
        "10059010",
        "6101",
        "KG",
        new BigDecimal("10.0000"),
        new BigDecimal("50.0000"));

    NotaFiscalRequest requestAtualizado = new NotaFiscalRequest(
        1L,
        1,
        "Venda de producao rural",
        OffsetDateTime.now(),
        null,
        "Jose Comprador",
        "12345678901",
        null,
        null,
        List.of(novoItem));

    when(produtorProvider.get()).thenReturn(produtor);
    when(notaFiscalRepository.findByIdAndProdutorId(notaId, produtorId))
        .thenReturn(Optional.of(notaExistente));
    when(notaFiscalRepository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    NotaFiscalResponse response = notaFiscalService.update(notaId, requestAtualizado);

    assertThat(response.valorTotalProdutos()).isEqualByComparingTo("500.00");
    assertThat(response.valorTotalNota()).isEqualByComparingTo("500.00");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private NotaFiscalRequest buildRequest() {
    NotaFiscalItemRequest item = new NotaFiscalItemRequest(
        1,
        null,
        "Soja em graos",
        "12010090",
        "6101",
        "KG",
        new BigDecimal("500.0000"),
        new BigDecimal("2.0000"));

    return new NotaFiscalRequest(
        1L,
        1,
        "Venda de producao rural",
        OffsetDateTime.now(),
        null,
        "Jose Comprador",
        "12345678901",
        null,
        null,
        List.of(item));
  }

  private NotaFiscal buildNotaSalva() {
    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .descricao("Soja em graos")
        .ncm("12010090")
        .cfop("6101")
        .unidade("KG")
        .quantidade(new BigDecimal("500.0000"))
        .valorUnitario(new BigDecimal("2.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
        .build();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Jose Comprador")
        .destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(EnderecoDestinatario.builder().build())
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(StatusNotaFiscal.RASCUNHO)
        .createdAt(OffsetDateTime.now())
        .updatedAt(OffsetDateTime.now())
        .build();

    nota.addItem(item);
    return nota;
  }
}
