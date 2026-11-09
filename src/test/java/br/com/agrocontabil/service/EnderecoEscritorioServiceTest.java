package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.config.EnderecoEscritorioProperties;
import br.com.agrocontabil.dto.EnderecoEscritorioRequest;
import br.com.agrocontabil.exception.DomainException;
import br.com.agrocontabil.exception.EnderecoExigeCnpjException;
import br.com.agrocontabil.exception.EnderecoTrocaRecenteException;
import br.com.agrocontabil.exception.SlugIndisponivelException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.EscritorioSlugAnterior;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.EscritorioSlugAnteriorRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.service.EnderecoEscritorioService.EnderecoAlterado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("Endereco proprio do escritorio: regras do slug (#464)")
class EnderecoEscritorioServiceTest {

  private static final Instant AGORA = Instant.parse("2026-11-09T15:00:00Z");
  private static final Clock CLOCK = Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo"));
  private static final OffsetDateTime AGORA_ODT = OffsetDateTime.now(CLOCK);

  @Mock private MembroEscritorioProvider membroProvider;
  @Mock private EscritorioRepository escritorioRepository;
  @Mock private EscritorioSlugAnteriorRepository anteriorRepository;
  @Mock private ContadorRepository contadorRepository;
  @Mock private PlatformTransactionManager transactionManager;

  private EnderecoEscritorioService service;
  private Contador admin;
  private Escritorio escritorio;

  @BeforeEach
  void setUp() {
    EnderecoEscritorioProperties propriedades = new EnderecoEscritorioProperties(
        "agroconia.com.br",
        new EnderecoEscritorioProperties.Endereco(Duration.ofDays(30), Duration.ofDays(90)));
    service = new EnderecoEscritorioService(membroProvider, escritorioRepository,
        anteriorRepository, contadorRepository, new SlugsReservados(), propriedades, CLOCK,
        transactionManager);
    escritorio = Escritorio.builder().id(UUID.randomUUID()).nome("Contabilidade Rural Oeste")
        .cnpj("48315276000105").build();
    admin = Contador.builder().id(UUID.randomUUID()).escritorioId(escritorio.getId())
        .funcao(FuncaoEscritorio.ADMINISTRADOR).build();
  }

  /** Administrador autenticado, transação e o escritório sob lock. */
  private void administradorNoEscritorio() {
    when(membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR)).thenReturn(admin);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(escritorioRepository.findByIdForUpdate(escritorio.getId()))
        .thenReturn(Optional.of(escritorio));
  }

  private static EnderecoEscritorioRequest pedido(String slug) {
    return new EnderecoEscritorioRequest(slug, true);
  }

  private static String codigo(Throwable erro) {
    return ((DomainException) erro).getErrors().get(0).code();
  }

  @Test
  @DisplayName("primeira definicao: grava o slug normalizado, sem carencia, e o host na resposta")
  void defineOPrimeiroEndereco() {
    administradorNoEscritorio();

    EnderecoAlterado resultado = service.definir(pedido("  RuralOeste "));

    assertThat(escritorio.getSlug()).isEqualTo("ruraloeste");
    assertThat(escritorio.getSlugAlteradoEm()).isEqualTo(AGORA_ODT);
    assertThat(resultado.antes()).isEqualTo("SEM_ENDERECO");
    assertThat(resultado.depois()).isEqualTo("COM_ENDERECO");
    assertThat(resultado.statusAnterior()).isEqualTo("SEM_ENDERECO");
    assertThat(resultado.recursoAuditado()).isEqualTo(escritorio.getId());
    assertThat(resultado.escritorio().enderecoHost()).isEqualTo("ruraloeste.agroconia.com.br");
    assertThat(resultado.escritorio().enderecoAlteravelEm())
        .isEqualTo(AGORA.plus(Duration.ofDays(30)));
    verify(anteriorRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("troca depois do intervalo: o slug antigo entra em carencia de 90 dias, com o escritorio")
  void trocaPoeOAntigoEmCarencia() {
    escritorio.setSlug("ruraloeste");
    escritorio.setSlugAlteradoEm(AGORA_ODT.minusDays(31));
    administradorNoEscritorio();

    service.definir(pedido("oesterural"));

    ArgumentCaptor<EscritorioSlugAnterior> carencia =
        ArgumentCaptor.forClass(EscritorioSlugAnterior.class);
    verify(anteriorRepository).saveAndFlush(carencia.capture());
    assertThat(carencia.getValue().getSlug()).isEqualTo("ruraloeste");
    assertThat(carencia.getValue().getEscritorioId()).isEqualTo(escritorio.getId());
    assertThat(carencia.getValue().getLiberadoEm()).isEqualTo(AGORA_ODT.plusDays(90));
    assertThat(escritorio.getSlug()).isEqualTo("oesterural");
  }

  @Test
  @DisplayName("reenviar o slug atual: nada muda, mesmo dentro do intervalo e sem consultar o banco")
  void mesmoSlugEIdempotente() {
    escritorio.setSlug("ruraloeste");
    escritorio.setSlugAlteradoEm(AGORA_ODT.minusDays(1));
    administradorNoEscritorio();

    EnderecoAlterado resultado = service.definir(pedido("ruraloeste"));

    assertThat(resultado.antes()).isEqualTo("COM_ENDERECO");
    assertThat(escritorio.getSlugAlteradoEm()).isEqualTo(AGORA_ODT.minusDays(1));
    verify(escritorioRepository, never()).saveAndFlush(any());
    verify(escritorioRepository, never()).slugIndisponivel(anyString(), any(), any());
  }

  @Test
  @DisplayName("sem CNPJ: 422 ENDERECO_EXIGE_CNPJ antes de olhar a disponibilidade")
  void exigeCnpj() {
    escritorio.setCnpj(null);
    administradorNoEscritorio();

    assertThatThrownBy(() -> service.definir(pedido("ruraloeste")))
        .isInstanceOf(EnderecoExigeCnpjException.class)
        .satisfies(e -> assertThat(codigo(e)).isEqualTo(ErrorCode.ENDERECO_EXIGE_CNPJ.code()));
    verify(escritorioRepository, never()).slugIndisponivel(anyString(), any(), any());
  }

  @Test
  @DisplayName("troca dentro de 30 dias: 409 com o instante da liberacao, sem revelar a disponibilidade")
  void trocaRecente() {
    escritorio.setSlug("ruraloeste");
    escritorio.setSlugAlteradoEm(AGORA_ODT.minusDays(10));
    administradorNoEscritorio();

    assertThatThrownBy(() -> service.definir(pedido("oesterural")))
        .isInstanceOf(EnderecoTrocaRecenteException.class)
        .satisfies(e -> assertThat(((DomainException) e).getErrors().get(0).expectedValue())
            .isEqualTo(AGORA.plus(Duration.ofDays(20)).toString()));
    verify(escritorioRepository, never()).slugIndisponivel(anyString(), any(), any());
    assertThat(escritorio.getSlug()).isEqualTo("ruraloeste");
  }

  @Test
  @DisplayName("reservado, contendo termo proibido ou em uso: o mesmo 409 SLUG_INDISPONIVEL")
  void indisponivelTemUmaRespostaSo() {
    administradorNoEscritorio();
    when(escritorioRepository.slugIndisponivel("emuso", escritorio.getId(), AGORA_ODT))
        .thenReturn(true);

    for (String slug : new String[] {"www", "ceres", "agroconia-suporte", "emuso"}) {
      assertThatThrownBy(() -> service.definir(pedido(slug)))
          .isInstanceOf(SlugIndisponivelException.class)
          .satisfies(e -> {
            assertThat(codigo(e)).isEqualTo(ErrorCode.SLUG_INDISPONIVEL.code());
            assertThat(((DomainException) e).getErrors().get(0).rejectedValue()).isNull();
          });
    }
    assertThat(escritorio.getSlug()).isNull();
  }

  @Test
  @DisplayName("corrida no indice unico: 409 SLUG_INDISPONIVEL, nunca o 409 generico de integridade")
  void corridaViraIndisponivel() {
    administradorNoEscritorio();
    when(escritorioRepository.saveAndFlush(escritorio))
        .thenThrow(new DataIntegrityViolationException("uq_escritorio_slug"));

    assertThatThrownBy(() -> service.definir(pedido("ruraloeste")))
        .isInstanceOf(SlugIndisponivelException.class);
  }

  @Test
  @DisplayName("recuperar o proprio slug em carencia: a linha da carencia sai antes")
  void recuperaOProprioSlug() {
    escritorio.setSlug("oesterural");
    escritorio.setSlugAlteradoEm(AGORA_ODT.minusDays(40));
    administradorNoEscritorio();

    service.definir(pedido("ruraloeste"));

    InOrder ordem = inOrder(escritorioRepository, anteriorRepository);
    ordem.verify(escritorioRepository).slugIndisponivel("ruraloeste", escritorio.getId(),
        AGORA_ODT);
    ordem.verify(anteriorRepository).apagarPorSlug("ruraloeste");
    ordem.verify(escritorioRepository).saveAndFlush(escritorio);
    assertThat(escritorio.getSlug()).isEqualTo("ruraloeste");
  }

  @Test
  @DisplayName("remover: sempre permitido, mesmo dentro do intervalo; o slug vai para a carencia")
  void removerEntraEmCarencia() {
    escritorio.setSlug("ruraloeste");
    escritorio.setSlugAlteradoEm(AGORA_ODT.minusDays(1));
    administradorNoEscritorio();

    EnderecoAlterado resultado = service.remover();

    assertThat(resultado.antes()).isEqualTo("COM_ENDERECO");
    assertThat(resultado.depois()).isEqualTo("SEM_ENDERECO");
    assertThat(escritorio.getSlug()).isNull();
    assertThat(escritorio.getSlugAlteradoEm()).isEqualTo(AGORA_ODT);
    assertThat(resultado.escritorio().slug()).isNull();
    assertThat(resultado.escritorio().enderecoHost()).isNull();
    ArgumentCaptor<EscritorioSlugAnterior> carencia =
        ArgumentCaptor.forClass(EscritorioSlugAnterior.class);
    verify(anteriorRepository).saveAndFlush(carencia.capture());
    assertThat(carencia.getValue().getSlug()).isEqualTo("ruraloeste");
    assertThat(carencia.getValue().getEscritorioId()).isEqualTo(escritorio.getId());
  }

  @Test
  @DisplayName("remover sem endereco: nada muda e o intervalo nao recomeca")
  void removerSemEndereco() {
    administradorNoEscritorio();

    EnderecoAlterado resultado = service.remover();

    assertThat(resultado.antes()).isEqualTo("SEM_ENDERECO");
    assertThat(escritorio.getSlugAlteradoEm()).isNull();
    verify(escritorioRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("aceite de equipe: slug ativo em carencia sem escritorio; os anteriores perdem o escritorio")
  void reservarAoApagar() {
    when(escritorioRepository.findSlugById(escritorio.getId()))
        .thenReturn(Optional.of("ruraloeste"));

    service.reservarAoApagar(escritorio.getId());

    verify(anteriorRepository).desvincularDoEscritorio(escritorio.getId());
    ArgumentCaptor<EscritorioSlugAnterior> carencia =
        ArgumentCaptor.forClass(EscritorioSlugAnterior.class);
    verify(anteriorRepository).saveAndFlush(carencia.capture());
    assertThat(carencia.getValue().getSlug()).isEqualTo("ruraloeste");
    assertThat(carencia.getValue().getEscritorioId()).isNull();
    assertThat(carencia.getValue().getLiberadoEm()).isEqualTo(AGORA_ODT.plusDays(90));
  }

  @Test
  @DisplayName("aceite de equipe sem endereco: so desvincula os anteriores")
  void reservarAoApagarSemEndereco() {
    when(escritorioRepository.findSlugById(escritorio.getId())).thenReturn(Optional.empty());

    service.reservarAoApagar(escritorio.getId());

    verify(anteriorRepository).desvincularDoEscritorio(escritorio.getId());
    verify(anteriorRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("exclusao de dados (LGPD): os anteriores do escritorio saem sem carencia")
  void apagarSemCarencia() {
    service.apagarSemCarencia(escritorio.getId());

    verify(anteriorRepository).apagarDoEscritorio(escritorio.getId());
    verify(anteriorRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("resposta: alteravel ja quando o intervalo passou; sem endereco, host nulo")
  void respostaSemEndereco() {
    escritorio.setSlugAlteradoEm(AGORA_ODT.minusDays(31));
    when(contadorRepository.countByEscritorioId(escritorio.getId())).thenReturn(3L);

    var resposta = service.resposta(escritorio, admin);

    assertThat(resposta.slug()).isNull();
    assertThat(resposta.enderecoHost()).isNull();
    assertThat(resposta.enderecoAlteravelEm()).isNull();
    assertThat(resposta.membros()).isEqualTo(3L);
    assertThat(resposta.minhaFuncao()).isEqualTo(FuncaoEscritorio.ADMINISTRADOR);
  }
}
