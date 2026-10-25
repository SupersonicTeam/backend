package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.FiltroCarteiraRequest;
import br.com.agrocontabil.dto.ProdutorCarteiraResponse;
import br.com.agrocontabil.exception.FiltroCarteiraInvalidoException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.VinculoSituacaoInvalidaException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ContagemPorProdutor;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CarteiraService — lista, busca, ordem e transicoes (#177)")
class CarteiraServiceTest {

  @Mock private UsuarioAutenticadoProvider usuarioProvider;
  @Mock private VinculoCarteiraRepository vinculoRepository;
  @Mock private ProdutorRepository produtorRepository;
  @Mock private ContadorRepository contadorRepository;

  private final Clock clock =
      Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
  private final Contador contador =
      Contador.builder().id(UUID.randomUUID()).nomeCompleto("Escritorio").build();
  private CarteiraService service;

  @BeforeEach
  void setUp() {
    service = new CarteiraService(usuarioProvider, vinculoRepository, produtorRepository,
        contadorRepository, clock);
  }

  private static FiltroCarteiraRequest filtro(String busca, String sort) {
    return new FiltroCarteiraRequest(busca, null, null, null, null, null, null, sort, null, null);
  }

  private VinculoCarteira vinculo(String nome) {
    Produtor produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto(nome)
        .cpfCnpj("52998224725").estado("MT").build();
    return VinculoCarteira.builder().id(UUID.randomUUID()).contadorId(contador.getId())
        .produtorId(produtor.getId()).produtor(produtor).situacao(SituacaoVinculo.ATIVO)
        .inicio(OffsetDateTime.now(clock)).build();
  }

  @Test
  @DisplayName("produtor nao lista carteira (403) e nada e consultado")
  void produtorNaoLista() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deProdutor(
        Produtor.builder().id(UUID.randomUUID()).build()));

    assertThatThrownBy(() -> service.listar(filtro(null, null)))
        .isInstanceOf(PapelNaoAutorizadoException.class);
    verifyNoInteractions(vinculoRepository);
  }

  @Test
  @DisplayName("ordenacao desconhecida e 400 antes de qualquer consulta")
  void ordenacaoInvalida() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));

    assertThatThrownBy(() -> service.listar(filtro(null, "cpfCnpj")))
        .isInstanceOf(FiltroCarteiraInvalidoException.class);
    verifyNoInteractions(vinculoRepository);
  }

  @Test
  @DisplayName("CPF com mascara vira busca por igualdade nas duas grafias, no banco")
  void buscaPorDocumentoPorIgualdade() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));
    VinculoCarteira joao = vinculo("Joao");
    when(vinculoRepository.findDaCarteira(contador.getId(), SituacaoVinculo.ATIVO))
        .thenReturn(List.of(joao, vinculo("Maria")));
    when(produtorRepository.findIdsByCpfCnpjIn(List.of("52998224725", "529.982.247-25")))
        .thenReturn(List.of(joao.getProdutorId()));

    List<ProdutorCarteiraResponse> clientes =
        service.listar(filtro("529.982.247-25", null)).getContent();

    assertThat(clientes).extracting(ProdutorCarteiraResponse::nomeCompleto)
        .containsExactly("Joao");
  }

  @Test
  @DisplayName("urgencia: guias vencidas primeiro, depois inconsistencias, depois nome")
  void ordemDeUrgencia() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));
    VinculoCarteira ana = vinculo("Ana");
    VinculoCarteira bruno = vinculo("Bruno");
    VinculoCarteira carla = vinculo("Carla");
    when(vinculoRepository.findDaCarteira(contador.getId(), SituacaoVinculo.ATIVO))
        .thenReturn(List.of(ana, bruno, carla));
    when(vinculoRepository.contarNotasDaCarteira(
        contador.getId(), StatusNotaFiscal.COM_INCONSISTENCIAS))
        .thenReturn(List.of(new ContagemPorProdutor(bruno.getProdutorId(), 9)));
    when(vinculoRepository.contarGuiasVencidasDaCarteira(any(), any()))
        .thenReturn(List.of(new ContagemPorProdutor(carla.getProdutorId(), 1)));
    when(vinculoRepository.contarGuiasNaFaixaDaCarteira(any(), any(), any(), any()))
        .thenReturn(List.of());

    List<ProdutorCarteiraResponse> clientes =
        service.listar(filtro(null, "urgencia")).getContent();

    assertThat(clientes).extracting(ProdutorCarteiraResponse::nomeCompleto)
        .containsExactly("Carla", "Bruno", "Ana");
    assertThat(clientes.get(2).atencao().precisaAtencao()).isFalse();
  }

  @Test
  @DisplayName("termo curto demais e ignorado; documento incompleto nao consulta o banco")
  void buscaCurtaIgnorada() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));
    when(vinculoRepository.findDaCarteira(contador.getId(), SituacaoVinculo.ATIVO))
        .thenReturn(List.of(vinculo("Joao"), vinculo("Maria")));

    assertThat(service.listar(filtro("jo", null)).getTotalElements()).isEqualTo(2);
    assertThat(service.listar(filtro("5299822", null)).getTotalElements()).isEqualTo(2);
    verify(produtorRepository, never()).findIdsByCpfCnpjIn(any());
  }

  @Test
  @DisplayName("suspender exige vinculo ativo; sem vinculo aberto, 404")
  void suspender() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));
    VinculoCarteira suspenso = vinculo("Joao");
    suspenso.setSituacao(SituacaoVinculo.SUSPENSO);
    UUID semVinculo = UUID.randomUUID();
    when(vinculoRepository.findAbertoForUpdate(contador.getId(), suspenso.getProdutorId()))
        .thenReturn(Optional.of(suspenso));
    when(vinculoRepository.findAbertoForUpdate(contador.getId(), semVinculo))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.suspender(suspenso.getProdutorId()))
        .isInstanceOf(VinculoSituacaoInvalidaException.class);
    assertThatThrownBy(() -> service.suspender(semVinculo))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("aceite reativa o vinculo aberto em vez de criar outro")
  void aceiteReativaAberto() {
    VinculoCarteira suspenso = vinculo("Joao");
    suspenso.setSituacao(SituacaoVinculo.SUSPENSO);
    when(vinculoRepository.findAbertoForUpdate(contador.getId(), suspenso.getProdutorId()))
        .thenReturn(Optional.of(suspenso));

    service.ativarPorConvite(contador.getId(), suspenso.getProdutorId());

    assertThat(suspenso.getSituacao()).isEqualTo(SituacaoVinculo.ATIVO);
    verify(vinculoRepository, never()).save(any());
  }
}
