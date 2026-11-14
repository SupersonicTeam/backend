package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.dto.ModeloPedidoRequest;
import br.com.agrocontabil.dto.ModeloPedidoResponse;
import br.com.agrocontabil.exception.DomainException;
import br.com.agrocontabil.exception.FuncaoNaoAutorizadaException;
import br.com.agrocontabil.exception.ModeloPedidoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.ModeloPedido;
import br.com.agrocontabil.model.TipoDocumentoPendencia;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ModeloPedidoRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModeloPedidoService — catalogo de modelos de pedido (#517)")
class ModeloPedidoServiceTest {

  private static final UUID ESCRITORIO = UUID.randomUUID();

  @Mock private MembroEscritorioProvider membroProvider;
  @Mock private ModeloPedidoRepository modeloRepository;
  @Mock private EscritorioRepository escritorioRepository;

  private ModeloPedidoService service;

  @BeforeEach
  void setUp() {
    service = new ModeloPedidoService(membroProvider, modeloRepository, escritorioRepository);
  }

  private void administrador() {
    when(membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR))
        .thenReturn(Contador.builder().escritorioId(ESCRITORIO)
            .funcao(FuncaoEscritorio.ADMINISTRADOR).build());
    when(escritorioRepository.findByIdForUpdate(ESCRITORIO))
        .thenReturn(Optional.of(Escritorio.builder().id(ESCRITORIO).build()));
  }

  private static ModeloPedidoRequest request(String titulo) {
    return new ModeloPedidoRequest(titulo, "Extrato de todas as contas",
        List.of(TipoDocumentoPendencia.EXTRATO), 10, "Pode mandar em PDF");
  }

  private static ModeloPedido modelo(UUID id, String titulo) {
    return ModeloPedido.builder().id(id).escritorioId(ESCRITORIO).titulo(titulo)
        .tituloNormalizado(ModeloPedido.normalizarTitulo(titulo))
        .tiposDocumento(EnumSet.of(TipoDocumentoPendencia.CONTRATO)).build();
  }

  @Test
  @DisplayName("listar: qualquer membro, modelos do escritorio dele na ordem do repositorio")
  void listar() {
    when(membroProvider.membro()).thenReturn(Contador.builder().escritorioId(ESCRITORIO)
        .funcao(FuncaoEscritorio.ASSISTENTE).build());
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(modeloRepository.findByEscritorioIdOrderByTituloNormalizadoAsc(ESCRITORIO))
        .thenReturn(List.of(modelo(a, "Ágio"), modelo(b, "contrato")));

    assertThat(service.listar()).extracting(ModeloPedidoResponse::id, ModeloPedidoResponse::titulo)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(a, "Ágio"),
            org.assertj.core.groups.Tuple.tuple(b, "contrato"));
  }

  @Test
  @DisplayName("criar: titulo sem as pontas, normalizado, branco vira nulo e tipos sem repeticao")
  void criar() {
    administrador();
    when(modeloRepository.countByEscritorioId(ESCRITORIO)).thenReturn(3L);
    when(modeloRepository.findByEscritorioIdAndTituloNormalizado(ESCRITORIO,
        "extrato bancario do mes")).thenReturn(Optional.empty());
    UUID id = UUID.randomUUID();
    when(modeloRepository.save(any(ModeloPedido.class))).thenAnswer(inv -> {
      ModeloPedido m = inv.getArgument(0);
      m.setId(id);
      return m;
    });

    ModeloPedidoResponse resposta = service.criar(new ModeloPedidoRequest(
        "  Extrato  Bancário do Mês ", " ",
        List.of(TipoDocumentoPendencia.EXTRATO, TipoDocumentoPendencia.CONTRATO,
            TipoDocumentoPendencia.EXTRATO), null, ""));

    ArgumentCaptor<ModeloPedido> gravado = ArgumentCaptor.forClass(ModeloPedido.class);
    verify(modeloRepository).save(gravado.capture());
    assertThat(gravado.getValue().getEscritorioId()).isEqualTo(ESCRITORIO);
    // O titulo normalizado (consultado acima no repetido) a entidade calcula ao gravar.
    assertThat(gravado.getValue().getTitulo()).isEqualTo("Extrato  Bancário do Mês");
    assertThat(resposta.id()).isEqualTo(id);
    assertThat(resposta.recursoAuditado()).isEqualTo(id);
    assertThat(resposta.status()).isNull();
    assertThat(resposta.descricao()).isNull();
    assertThat(resposta.observacoes()).isNull();
    assertThat(resposta.prazoEmDias()).isNull();
    // Na ordem do enum, sem a repeticao.
    assertThat(resposta.tiposDocumento())
        .containsExactly(TipoDocumentoPendencia.EXTRATO, TipoDocumentoPendencia.CONTRATO);
  }

  @Test
  @DisplayName("criar no teto: 409 LIMITE_MODELOS_PEDIDO, nada gravado")
  void criarNoTeto() {
    administrador();
    when(modeloRepository.countByEscritorioId(ESCRITORIO))
        .thenReturn((long) ModeloPedidoService.MAXIMO_POR_ESCRITORIO);

    assertThatThrownBy(() -> service.criar(request("Extrato")))
        .isInstanceOfSatisfying(ModeloPedidoException.class, e -> {
          assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(e.getErrors().get(0).code())
              .isEqualTo(ErrorCode.LIMITE_MODELOS_PEDIDO.code());
          assertThat(e.getErrors().get(0).expectedValue()).isEqualTo("50");
        });
    verify(modeloRepository, never()).save(any());
  }

  @Test
  @DisplayName("criar com titulo repetido: 409 MODELO_PEDIDO_DUPLICADO no campo titulo, sem eco")
  void criarDuplicado() {
    administrador();
    when(modeloRepository.countByEscritorioId(ESCRITORIO)).thenReturn(1L);
    when(modeloRepository.findByEscritorioIdAndTituloNormalizado(ESCRITORIO, "extrato"))
        .thenReturn(Optional.of(modelo(UUID.randomUUID(), "Extrato")));

    assertThatThrownBy(() -> service.criar(request("EXTRATO")))
        .isInstanceOfSatisfying(ModeloPedidoException.class, e -> {
          assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(e.getErrors().get(0).code())
              .isEqualTo(ErrorCode.MODELO_PEDIDO_DUPLICADO.code());
          assertThat(e.getErrors().get(0).field()).isEqualTo("titulo");
          assertThat(e.getErrors().get(0).rejectedValue()).isNull();
          assertThat(e.getMessage()).doesNotContain("EXTRATO");
        });
    verify(modeloRepository, never()).save(any());
  }

  @Test
  @DisplayName("quem nao e administrador para no provider: nenhum acesso ao catalogo")
  void soAdministradorAltera() {
    when(membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR))
        .thenThrow(new FuncaoNaoAutorizadaException(FuncaoEscritorio.CONTADOR,
            List.of(FuncaoEscritorio.ADMINISTRADOR)));

    assertThatThrownBy(() -> service.criar(request("Extrato")))
        .isInstanceOf(FuncaoNaoAutorizadaException.class);
    assertThatThrownBy(() -> service.atualizar(UUID.randomUUID(), request("Extrato")))
        .isInstanceOf(FuncaoNaoAutorizadaException.class);
    assertThatThrownBy(() -> service.apagar(UUID.randomUUID()))
        .isInstanceOf(FuncaoNaoAutorizadaException.class);
    verifyNoInteractions(modeloRepository, escritorioRepository);
  }

  @Test
  @DisplayName("atualizar: o proprio titulo com outra caixa passa; o de outro modelo e 409")
  void atualizar() {
    administrador();
    UUID id = UUID.randomUUID();
    ModeloPedido proprio = modelo(id, "Extrato");
    when(modeloRepository.findByIdAndEscritorioId(id, ESCRITORIO))
        .thenReturn(Optional.of(proprio));
    when(modeloRepository.findByEscritorioIdAndTituloNormalizado(ESCRITORIO, "extrato"))
        .thenReturn(Optional.of(proprio));
    when(modeloRepository.save(proprio)).thenReturn(proprio);

    ModeloPedidoResponse resposta = service.atualizar(id, request("EXTRATO"));

    assertThat(resposta.titulo()).isEqualTo("EXTRATO");
    assertThat(resposta.tiposDocumento()).containsExactly(TipoDocumentoPendencia.EXTRATO);
    assertThat(resposta.prazoEmDias()).isEqualTo(10);

    when(modeloRepository.findByEscritorioIdAndTituloNormalizado(ESCRITORIO, "contrato"))
        .thenReturn(Optional.of(modelo(UUID.randomUUID(), "Contrato")));
    assertThatThrownBy(() -> service.atualizar(id, request("Contrato")))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getErrors().get(0)
            .code()).isEqualTo(ErrorCode.MODELO_PEDIDO_DUPLICADO.code()));
  }

  @Test
  @DisplayName("atualizar e apagar modelo de outro escritorio ou inexistente: 404")
  void foraDoEscritorio() {
    administrador();
    UUID id = UUID.randomUUID();
    when(modeloRepository.findByIdAndEscritorioId(id, ESCRITORIO)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.atualizar(id, request("Extrato")))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.apagar(id))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(modeloRepository, never()).save(any());
    verify(modeloRepository, never()).delete(any());
  }

  @Test
  @DisplayName("apagar: o modelo do escritorio sai")
  void apagar() {
    administrador();
    UUID id = UUID.randomUUID();
    ModeloPedido proprio = modelo(id, "Extrato");
    when(modeloRepository.findByIdAndEscritorioId(id, ESCRITORIO))
        .thenReturn(Optional.of(proprio));

    service.apagar(id);

    verify(modeloRepository).delete(proprio);
  }

  @Test
  @DisplayName("escritorio do administrador sumiu no meio: 404, nada gravado")
  void escritorioAusente() {
    when(membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR))
        .thenReturn(Contador.builder().escritorioId(ESCRITORIO)
            .funcao(FuncaoEscritorio.ADMINISTRADOR).build());
    when(escritorioRepository.findByIdForUpdate(ESCRITORIO)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.criar(request("Extrato")))
        .isInstanceOf(ResourceNotFoundException.class);
    verifyNoInteractions(modeloRepository);
  }

  @Test
  @DisplayName("titulo normalizado sem acento, caixa nem espaco extra; textos fora do toString")
  void textosForaDoLog() {
    assertThat(ModeloPedido.normalizarTitulo("  Ágio   do ARRENDAMENTO ")).isEqualTo(
        "agio do arrendamento");
    ModeloPedido m = ModeloPedido.builder().escritorioId(ESCRITORIO).titulo("Cliente Fulano")
        .descricao("Fulano").observacoes("Fulano").build();
    assertThat(m.toString()).doesNotContain("Fulano").contains(ESCRITORIO.toString());
    assertThat(request("Cliente Fulano").toString()).doesNotContain("Fulano");
  }
}
