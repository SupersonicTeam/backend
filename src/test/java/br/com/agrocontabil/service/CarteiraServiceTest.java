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
import br.com.agrocontabil.exception.FuncaoNaoAutorizadaException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.VinculoSituacaoInvalidaException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.EncerradoPor;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.ContagemPorProdutor;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
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
  @Mock private EscritorioRepository escritorioRepository;
  @Mock private br.com.agrocontabil.repository.PendenciaRepository pendenciaRepository;

  private final Clock clock =
      Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
  private final Contador contador = Contador.builder().id(UUID.randomUUID())
      .nomeCompleto("Contadora").escritorioId(UUID.randomUUID())
      .funcao(FuncaoEscritorio.ADMINISTRADOR).build();
  private CarteiraService service;

  @BeforeEach
  void setUp() {
    service = new CarteiraService(new MembroEscritorioProvider(usuarioProvider),
        new AuthenticatedProdutorProvider(usuarioProvider),
        new ProdutorEmContextoResolver(usuarioProvider, produtorRepository, vinculoRepository),
        vinculoRepository, produtorRepository, contadorRepository, escritorioRepository,
        pendenciaRepository, clock);
  }

  private static FiltroCarteiraRequest filtro(String busca, String sort) {
    return new FiltroCarteiraRequest(busca, null, null, null, null, null, null, null, sort, null,
        null);
  }

  private VinculoCarteira vinculo(String nome) {
    Produtor produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto(nome)
        .cpfCnpj("52998224725").estado("MT").build();
    return VinculoCarteira.builder().id(UUID.randomUUID()).escritorioId(contador.getEscritorioId())
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
    when(vinculoRepository.findDaCarteira(contador.getEscritorioId(), SituacaoVinculo.ATIVO))
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
    when(vinculoRepository.findDaCarteira(contador.getEscritorioId(), SituacaoVinculo.ATIVO))
        .thenReturn(List.of(ana, bruno, carla));
    when(vinculoRepository.contarNotasDaCarteira(
        contador.getEscritorioId(), StatusNotaFiscal.COM_INCONSISTENCIAS))
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
  @DisplayName("urgencia (#407): nota aguardando aprovacao e documento enviado passam o cliente em dia")
  void urgenciaContaAprovacaoEDocumentoEnviado() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));
    VinculoCarteira ana = vinculo("Ana");
    VinculoCarteira bruno = vinculo("Bruno");
    VinculoCarteira carla = vinculo("Carla");
    VinculoCarteira dora = vinculo("Dora");
    VinculoCarteira eva = vinculo("Eva");
    when(vinculoRepository.findDaCarteira(contador.getEscritorioId(), SituacaoVinculo.ATIVO))
        .thenReturn(List.of(ana, bruno, carla, dora, eva));
    when(vinculoRepository.contarNotasDaCarteira(
        contador.getEscritorioId(), StatusNotaFiscal.COM_INCONSISTENCIAS))
        .thenReturn(List.of(new ContagemPorProdutor(eva.getProdutorId(), 1)));
    when(vinculoRepository.contarNotasDaCarteira(
        contador.getEscritorioId(), StatusNotaFiscal.AGUARDANDO_APROVACAO))
        .thenReturn(List.of(new ContagemPorProdutor(bruno.getProdutorId(), 1)));
    when(pendenciaRepository.contarDaCarteira(any(), any()))
        .thenReturn(List.of(new ContagemPorProdutor(carla.getProdutorId(), 1)));
    when(vinculoRepository.contarGuiasVencidasDaCarteira(any(), any())).thenReturn(List.of());
    when(vinculoRepository.contarGuiasNaFaixaDaCarteira(any(), any(), any(), any()))
        .thenReturn(List.of(new ContagemPorProdutor(dora.getProdutorId(), 1)));

    List<ProdutorCarteiraResponse> clientes =
        service.listar(filtro(null, "urgencia")).getContent();

    // guias vencidas > inconsistencias > aguardando aprovacao > documento enviado > vencimento
    // proximo > nome: Ana, em dia e primeira no alfabeto, fica por ultimo.
    assertThat(clientes).extracting(ProdutorCarteiraResponse::nomeCompleto)
        .containsExactly("Eva", "Bruno", "Carla", "Dora", "Ana");
  }

  @Test
  @DisplayName("termo curto demais e ignorado; documento incompleto nao consulta o banco")
  void buscaCurtaIgnorada() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));
    when(vinculoRepository.findDaCarteira(contador.getEscritorioId(), SituacaoVinculo.ATIVO))
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
    when(vinculoRepository.findAbertoForUpdate(contador.getEscritorioId(), suspenso.getProdutorId()))
        .thenReturn(Optional.of(suspenso));
    when(vinculoRepository.findAbertoForUpdate(contador.getEscritorioId(), semVinculo))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.suspender(suspenso.getProdutorId()))
        .isInstanceOf(VinculoSituacaoInvalidaException.class);
    assertThatThrownBy(() -> service.suspender(semVinculo))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("escritorio encerra: grava ESCRITORIO, o administrador e o motivo (#402)")
  void escritorioEncerraComMotivo() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));
    VinculoCarteira ativo = vinculo("Joao");
    when(vinculoRepository.findAbertoForUpdate(contador.getEscritorioId(), ativo.getProdutorId()))
        .thenReturn(Optional.of(ativo));

    var resposta = service.encerrar(ativo.getProdutorId(), "Mudou de escritorio");

    assertThat(resposta.situacao()).isEqualTo(SituacaoVinculo.ENCERRADO);
    assertThat(ativo.getEncerradoPor()).isEqualTo(EncerradoPor.ESCRITORIO);
    assertThat(ativo.getEncerradoPorMembroId()).isEqualTo(contador.getId());
    assertThat(ativo.getMotivoEncerramento()).isEqualTo("Mudou de escritorio");
    assertThat(ativo.getFim()).isEqualTo(OffsetDateTime.now(clock));
  }

  @Test
  @DisplayName("escritorio encerra sem motivo: ESCRITORIO registrado, motivo nulo (#402)")
  void escritorioEncerraSemMotivo() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));
    VinculoCarteira ativo = vinculo("Joao");
    when(vinculoRepository.findAbertoForUpdate(contador.getEscritorioId(), ativo.getProdutorId()))
        .thenReturn(Optional.of(ativo));

    service.encerrar(ativo.getProdutorId(), null);

    assertThat(ativo.getEncerradoPor()).isEqualTo(EncerradoPor.ESCRITORIO);
    assertThat(ativo.getMotivoEncerramento()).isNull();
  }

  @Test
  @DisplayName("so administrador encerra; assistente cai antes de qualquer consulta (#402)")
  void encerrarSoAdministrador() {
    Contador assistente = Contador.builder().id(UUID.randomUUID()).nomeCompleto("Assistente")
        .escritorioId(contador.getEscritorioId()).funcao(FuncaoEscritorio.ASSISTENTE).build();
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(assistente));

    assertThatThrownBy(() -> service.encerrar(UUID.randomUUID(), "x"))
        .isInstanceOf(FuncaoNaoAutorizadaException.class);
    verifyNoInteractions(vinculoRepository);
  }

  @Test
  @DisplayName("produtor encerra: grava PRODUTOR, sem membro, com o motivo; vinculo de outro e 404 (#402)")
  void produtorEncerra() {
    Produtor produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Ana").build();
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deProdutor(produtor));
    VinculoCarteira meu = vinculo("Ana");
    UUID deOutro = UUID.randomUUID();
    when(vinculoRepository.findByIdAndProdutorIdForUpdate(meu.getId(), produtor.getId()))
        .thenReturn(Optional.of(meu));
    when(vinculoRepository.findByIdAndProdutorIdForUpdate(deOutro, produtor.getId()))
        .thenReturn(Optional.empty());

    var resposta = service.encerrarPeloProdutor(meu.getId(), "Prefiro outro escritorio");

    assertThat(resposta.encerradoPor()).isEqualTo(EncerradoPor.PRODUTOR);
    assertThat(resposta.motivoEncerramento()).isEqualTo("Prefiro outro escritorio");
    assertThat(meu.getEncerradoPorMembroId()).isNull();
    assertThat(meu.getSituacao()).isEqualTo(SituacaoVinculo.ENCERRADO);
    assertThatThrownBy(() -> service.encerrarPeloProdutor(deOutro, "x"))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("produtor nao reencerra vinculo ja encerrado, e o registro de quem encerrou nao muda (#402)")
  void produtorNaoReencerra() {
    Produtor produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Ana").build();
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deProdutor(produtor));
    VinculoCarteira encerrado = vinculo("Ana");
    encerrado.setSituacao(SituacaoVinculo.ENCERRADO);
    encerrado.setEncerradoPor(EncerradoPor.ESCRITORIO);
    encerrado.setMotivoEncerramento("original");
    when(vinculoRepository.findByIdAndProdutorIdForUpdate(encerrado.getId(), produtor.getId()))
        .thenReturn(Optional.of(encerrado));

    assertThatThrownBy(() -> service.encerrarPeloProdutor(encerrado.getId(), "novo"))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(encerrado.getEncerradoPor()).isEqualTo(EncerradoPor.ESCRITORIO);
    assertThat(encerrado.getMotivoEncerramento()).isEqualTo("original");
  }

  @Test
  @DisplayName("assistente e contador atendem, mas nao suspendem cliente (so administrador)")
  void suspenderSoAdministrador() {
    Contador assistente = Contador.builder().id(UUID.randomUUID()).nomeCompleto("Assistente")
        .escritorioId(contador.getEscritorioId()).funcao(FuncaoEscritorio.ASSISTENTE).build();
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(assistente));

    assertThatThrownBy(() -> service.suspender(UUID.randomUUID()))
        .isInstanceOf(FuncaoNaoAutorizadaException.class);
    verifyNoInteractions(vinculoRepository);
  }

  @Test
  @DisplayName("contador sem escritorio (removido da equipe) nao lista carteira nenhuma")
  void semEscritorioNaoLista() {
    Contador removido = Contador.builder().id(UUID.randomUUID()).nomeCompleto("Ex").build();
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(removido));

    assertThatThrownBy(() -> service.listar(filtro(null, null)))
        .isInstanceOf(FuncaoNaoAutorizadaException.class);
    verifyNoInteractions(vinculoRepository);
  }

  @Test
  @DisplayName("filtro responsavel: eu, nenhum, id; outro valor e 400")
  void filtroResponsavel() {
    when(usuarioProvider.get()).thenReturn(UsuarioAutenticado.deContador(contador));
    VinculoCarteira meu = vinculo("Meu");
    meu.setResponsavelId(contador.getId());
    VinculoCarteira semNinguem = vinculo("Sem");
    VinculoCarteira deOutro = vinculo("Outro");
    UUID outro = UUID.randomUUID();
    deOutro.setResponsavelId(outro);
    when(vinculoRepository.findDaCarteira(contador.getEscritorioId(), SituacaoVinculo.ATIVO))
        .thenReturn(List.of(meu, semNinguem, deOutro));

    assertThat(nomes(comResponsavel("eu"))).containsExactly("Meu");
    assertThat(nomes(comResponsavel("nenhum"))).containsExactly("Sem");
    assertThat(nomes(comResponsavel(outro.toString()))).containsExactly("Outro");
    assertThatThrownBy(() -> comResponsavel("fulano"))
        .isInstanceOf(FiltroCarteiraInvalidoException.class);
  }

  private List<ProdutorCarteiraResponse> comResponsavel(String responsavel) {
    return service.listar(new FiltroCarteiraRequest(null, null, null, null, null, null, null,
        responsavel, null, null, null)).getContent();
  }

  private static List<String> nomes(List<ProdutorCarteiraResponse> clientes) {
    return clientes.stream().map(ProdutorCarteiraResponse::nomeCompleto).toList();
  }

  @Test
  @DisplayName("aceite reativa o vinculo aberto em vez de criar outro")
  void aceiteReativaAberto() {
    VinculoCarteira suspenso = vinculo("Joao");
    suspenso.setSituacao(SituacaoVinculo.SUSPENSO);
    when(vinculoRepository.findAbertoForUpdate(contador.getEscritorioId(), suspenso.getProdutorId()))
        .thenReturn(Optional.of(suspenso));

    service.ativarPorConvite(contador.getEscritorioId(), suspenso.getProdutorId(), null);

    assertThat(suspenso.getSituacao()).isEqualTo(SituacaoVinculo.ATIVO);
    verify(vinculoRepository, never()).save(any());
  }
}
