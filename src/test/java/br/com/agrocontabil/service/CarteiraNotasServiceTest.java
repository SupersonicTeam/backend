package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.FiltroNotasCarteiraRequest;
import br.com.agrocontabil.dto.ResumoNotasCarteiraResponse;
import br.com.agrocontabil.exception.FiltroCarteiraInvalidoException;
import br.com.agrocontabil.exception.FiltroNotasInvalidoException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaDaCarteira;
import br.com.agrocontabil.repository.NotaFiscalCarteiraRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Regras do service da listagem da carteira inteira (issue #341), isoladas do banco: a ordem das
 * checagens (identidade antes de qualquer consulta), o parse do {@code responsavel}, a ordenação
 * padrão e a soma das caixas do resumo. O escopo de verdade, contra o banco, está em
 * {@code CarteiraTodasNotasIntegrationTest}.
 */
class CarteiraNotasServiceTest {

  private ProdutorEmContextoResolver produtorEmContexto;
  private MembroEscritorioProvider membroProvider;
  private NotaFiscalCarteiraRepository carteiraRepository;
  private CarteiraNotasService service;
  private Contador membro;

  @BeforeEach
  void setUp() {
    produtorEmContexto = mock(ProdutorEmContextoResolver.class);
    membroProvider = mock(MembroEscritorioProvider.class);
    carteiraRepository = mock(NotaFiscalCarteiraRepository.class);
    service = new CarteiraNotasService(produtorEmContexto, mock(NotaFiscalService.class),
        mock(NotaFiscalValidacaoService.class), membroProvider, carteiraRepository);
    membro = Contador.builder().id(UUID.randomUUID()).escritorioId(UUID.randomUUID()).build();
  }

  @Test
  @DisplayName("quem nao e membro de escritorio (produtor, sem escritorio) para antes de qualquer consulta")
  void semIdentidadeNaoConsulta() {
    when(membroProvider.membro()).thenThrow(new PapelNaoAutorizadoException(PapelUsuario.PRODUTOR, PapelUsuario.CONTADOR));

    assertThatThrownBy(() -> service.listarDaCarteira(filtro(null, null), PageRequest.of(0, 20)))
        .isInstanceOf(PapelNaoAutorizadoException.class);
    assertThatThrownBy(() -> service.resumoDaCarteira(filtro(null, null)))
        .isInstanceOf(PapelNaoAutorizadoException.class);

    verifyNoInteractions(carteiraRepository);
  }

  @Test
  @DisplayName("responsavel invalido e 400 antes da consulta; ordenacao fora da lista e 400 antes da identidade")
  void filtrosInvalidos() {
    when(membroProvider.membro()).thenReturn(membro);

    assertThatThrownBy(() -> service.listarDaCarteira(filtro(null, "ninguem"),
        PageRequest.of(0, 20)))
        .isInstanceOf(FiltroCarteiraInvalidoException.class);
    verifyNoInteractions(carteiraRepository);

    assertThatThrownBy(() -> service.listarDaCarteira(filtro(null, null),
        PageRequest.of(0, 20, Sort.by("destinatarioNome"))))
        .isInstanceOf(FiltroNotasInvalidoException.class);
    verify(membroProvider, never()).contador();
  }

  @Test
  @DisplayName("produtorId fora da carteira: o 404 do resolvedor sobe e nada e consultado")
  void produtorForaDaCarteira() {
    UUID fora = UUID.randomUUID();
    when(membroProvider.membro()).thenReturn(membro);
    org.mockito.Mockito.doThrow(new ResourceNotFoundException("Produtor", fora))
        .when(produtorEmContexto).exigirContadorComAcesso(fora);

    assertThatThrownBy(() -> service.listarDaCarteira(filtro(fora, null), PageRequest.of(0, 20)))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.resumoDaCarteira(filtro(fora, null)))
        .isInstanceOf(ResourceNotFoundException.class);

    verifyNoInteractions(carteiraRepository);
  }

  @Test
  @DisplayName("sem ordenacao pedida, a mais recente primeiro, com o id para desempatar")
  void ordenacaoPadrao() {
    when(membroProvider.membro()).thenReturn(membro);
    when(carteiraRepository.listar(any(), any())).thenReturn(new PageImpl<NotaDaCarteira>(List.of()));

    service.listarDaCarteira(filtro(null, null), PageRequest.of(2, 15));

    ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Specification<NotaFiscal>> especificacao =
        ArgumentCaptor.forClass(Specification.class);
    verify(carteiraRepository).listar(especificacao.capture(), pagina.capture());
    assertThat(pagina.getValue().getPageNumber()).isEqualTo(2);
    assertThat(pagina.getValue().getPageSize()).isEqualTo(15);
    assertThat(pagina.getValue().getSort()).containsExactly(Sort.Order.desc("dataEmissao"),
        Sort.Order.asc("id"));
    assertThat(especificacao.getValue()).isNotNull();
  }

  @Test
  @DisplayName("a lista leva origem e fornecedor: compra IMPORTADA com emitente, nota PROPRIA sem")
  void origemEEmitenteNaLista() {
    when(membroProvider.membro()).thenReturn(membro);
    NotaDaCarteira compra = linha(br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA,
        "Posto Rota 163");
    NotaDaCarteira propria = linha(br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA, null);
    when(carteiraRepository.listar(any(), any()))
        .thenReturn(new PageImpl<>(List.of(compra, propria)));

    var pagina = service.listarDaCarteira(filtro(null, null), PageRequest.of(0, 20));

    assertThat(pagina.getContent()).extracting("origem", "emitenteNome").containsExactly(
        org.assertj.core.groups.Tuple.tuple(br.com.agrocontabil.model.OrigemNotaFiscal.IMPORTADA,
            "Posto Rota 163"),
        org.assertj.core.groups.Tuple.tuple(br.com.agrocontabil.model.OrigemNotaFiscal.PROPRIA,
            null));
  }

  private static NotaDaCarteira linha(br.com.agrocontabil.model.OrigemNotaFiscal origem,
      String emitente) {
    return new NotaDaCarteira(UUID.randomUUID(), UUID.randomUUID(), "Fazenda A", 1L, 1,
        "Venda", br.com.agrocontabil.model.StatusNotaFiscal.AUTORIZADA, null, null, "Dest",
        origem, emitente, null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("resumo soma as caixas; 'todas' inclui os estados que nenhuma caixa mostra")
  void resumoSomaAsCaixas() {
    when(membroProvider.membro()).thenReturn(membro);
    Map<StatusNotaFiscal, Long> contagem = new EnumMap<>(StatusNotaFiscal.class);
    contagem.put(StatusNotaFiscal.AGUARDANDO_APROVACAO, 4L);
    contagem.put(StatusNotaFiscal.COM_INCONSISTENCIAS, 2L);
    contagem.put(StatusNotaFiscal.REJEITADA, 3L);
    contagem.put(StatusNotaFiscal.AUTORIZADA, 10L);
    contagem.put(StatusNotaFiscal.APROVADA, 1L);
    contagem.put(StatusNotaFiscal.ENVIANDO, 1L);
    when(carteiraRepository.contarPorStatus(any())).thenReturn(contagem);

    ResumoNotasCarteiraResponse resumo = service.resumoDaCarteira(filtro(null, null));

    assertThat(resumo).isEqualTo(new ResumoNotasCarteiraResponse(4, 5, 0, 0, 10, 0, 21));
  }

  private static FiltroNotasCarteiraRequest filtro(UUID produtorId, String responsavel) {
    return new FiltroNotasCarteiraRequest(null, null, null, null, null, produtorId,
        responsavel);
  }
}
