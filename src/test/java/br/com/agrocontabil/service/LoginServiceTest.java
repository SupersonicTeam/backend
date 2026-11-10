package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.common.MascaraDadosPessoais;
import br.com.agrocontabil.dto.LoginRequest;
import br.com.agrocontabil.dto.LoginResponse;
import br.com.agrocontabil.exception.CredenciaisInvalidasException;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import br.com.agrocontabil.service.SessaoService.SessaoEmitida;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("LoginService — e-mail e senha por JWT (#206)")
class LoginServiceTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL = "joaquim@fazenda.com.br";

  @Mock private CredencialUsuarioRepository credencialRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private SessaoService sessaoService;
  @Mock private BloqueioContaService bloqueioConta;
  @Mock private UsuarioAutenticadoProvider usuarioProvider;
  @Mock private EnderecoEscritorioService enderecoService;

  private LoginService service;

  @BeforeEach
  void setUp() {
    when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}ficticio");
    service = new LoginService(credencialRepository, passwordEncoder, sessaoService, bloqueioConta,
        usuarioProvider, enderecoService);
  }

  private CredencialUsuario credencial(boolean ativo) {
    return CredencialUsuario.builder()
        .id(UUID.randomUUID()).email(EMAIL).senhaHash("{bcrypt}real")
        .papel(PapelUsuario.PRODUTOR).cpfCnpj("52998224725").produtorId(UUID.randomUUID())
        .ativo(ativo).versaoSessao(2).build();
  }

  @Test
  @DisplayName("credencial certa: abre a sessao da propria credencial, com e-mail normalizado")
  void loginValido() {
    CredencialUsuario credencial = credencial(true);
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial));
    when(bloqueioConta.reservarTentativa(any())).thenReturn(true);
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(true);
    SessaoEmitida sessao = new SessaoEmitida(new LoginResponse("jwt", "Bearer",
        Instant.parse("2026-10-19T12:15:00Z"), PapelUsuario.PRODUTOR, credencial.getProdutorId()),
        "refresh-bruto", Instant.parse("2026-10-26T12:00:00Z"));
    when(sessaoService.abrir(credencial)).thenReturn(sessao);

    SessaoEmitida resposta =
        service.entrar(new LoginRequest(" Joaquim@Fazenda.com.br ", SENHA));

    assertThat(resposta).isSameAs(sessao);
    assertThat(resposta.toString()).doesNotContain("jwt", "refresh-bruto");
  }

  @Test
  @DisplayName("e-mail sem conta: confere a senha contra o hash ficticio (mesmo custo) e da 401")
  void emailInexistenteGastaOMesmoTempo() {
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA)))
        .isInstanceOf(CredenciaisInvalidasException.class);
    verify(passwordEncoder).matches(SENHA, "{bcrypt}ficticio");
    verify(bloqueioConta).reservarFicticia();
    verify(sessaoService, never()).abrir(any());
  }

  @Test
  @DisplayName("senha errada: 401, falha contada e nenhum token")
  void senhaErrada() {
    CredencialUsuario credencial = credencial(true);
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial));
    when(bloqueioConta.reservarTentativa(any())).thenReturn(true);
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(false);

    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA)))
        .isInstanceOf(CredenciaisInvalidasException.class);
    // A falha ja foi contada na reserva, antes do BCrypt: nada a liberar.
    verify(bloqueioConta).reservarTentativa(credencial.getId());
    verify(bloqueioConta, never()).liberar(any());
    verify(sessaoService, never()).abrir(any());
  }

  @Test
  @DisplayName("conta bloqueada: nem a senha certa entra, a senha real nao e conferida, mesmo 401")
  void contaBloqueada() {
    CredencialUsuario credencial = credencial(true);
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial));
    when(bloqueioConta.reservarTentativa(credencial.getId())).thenReturn(false);

    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA)))
        .isInstanceOf(CredenciaisInvalidasException.class);
    verify(passwordEncoder, never()).matches(SENHA, "{bcrypt}real");
    verify(passwordEncoder).matches(SENHA, "{bcrypt}ficticio");
    verify(bloqueioConta, never()).reservarFicticia();
    verify(bloqueioConta, never()).liberar(any());
    verify(sessaoService, never()).abrir(any());
  }

  @Test
  @DisplayName("login recusado: o log leva uma referencia curta, nunca o UUID da credencial (#222)")
  void logSemUuidDaCredencial(CapturedOutput saida) {
    // O UUID da credencial e o "sub" do JWT: com ele (e o segredo vazado) forja-se um token.
    CredencialUsuario errada = credencial(true);
    CredencialUsuario bloqueada = credencial(true);
    CredencialUsuario desativada = credencial(false);
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(errada),
        Optional.of(bloqueada), Optional.of(desativada));
    when(bloqueioConta.reservarTentativa(errada.getId())).thenReturn(true);
    when(bloqueioConta.reservarTentativa(bloqueada.getId())).thenReturn(false);
    when(bloqueioConta.reservarTentativa(desativada.getId())).thenReturn(true);
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(false, true);

    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA)))
          .isInstanceOf(CredenciaisInvalidasException.class);
    }

    for (CredencialUsuario c : new CredencialUsuario[] {errada, bloqueada, desativada}) {
      assertThat(saida.getOut()).doesNotContain(c.getId().toString())
          .contains(MascaraDadosPessoais.referencia(c.getId()));
    }
  }

  @Test
  @DisplayName("login com sucesso zera as falhas")
  void sucessoZeraFalhas() {
    CredencialUsuario credencial = credencial(true);
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial));
    when(bloqueioConta.reservarTentativa(any())).thenReturn(true);
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(true);

    service.entrar(new LoginRequest(EMAIL, SENHA));

    verify(bloqueioConta).liberar(credencial.getId());
  }

  @Test
  @DisplayName("credencial desativada com a senha certa: o mesmo 401 (nao revela que existe)")
  void desativada() {
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial(false)));
    when(bloqueioConta.reservarTentativa(any())).thenReturn(true);
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(true);

    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA)))
        .isInstanceOf(CredenciaisInvalidasException.class);
  }

  @Test
  @DisplayName("senha acima de 72 bytes: o mesmo 401, sem buscar a conta nem passar a senha ao BCrypt")
  void senhaLongaDemais() {
    String longa = "é".repeat(40); // 80 bytes

    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, longa)))
        .isInstanceOf(CredenciaisInvalidasException.class);
    verify(passwordEncoder, never()).matches(eq(longa), anyString());
    verify(passwordEncoder).matches(anyString(), eq("{bcrypt}ficticio"));
    verify(credencialRepository, never()).findByEmail(anyString());
  }

  @Test
  @DisplayName("hash defasado (custo antigo): regravado so na coluna do hash")
  void rehashNoLogin() {
    CredencialUsuario credencial = credencial(true);
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial));
    when(bloqueioConta.reservarTentativa(any())).thenReturn(true);
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(true);
    when(passwordEncoder.upgradeEncoding("{bcrypt}real")).thenReturn(true);
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}novo");
    when(sessaoService.abrir(credencial)).thenReturn(new SessaoEmitida(
        new LoginResponse("jwt", "Bearer", Instant.now(), PapelUsuario.PRODUTOR, UUID.randomUUID()),
        "refresh", Instant.now()));

    service.entrar(new LoginRequest(EMAIL, SENHA));

    verify(credencialRepository)
        .atualizarSenhaHashSeIgual(credencial.getId(), "{bcrypt}real", "{bcrypt}novo");
    verify(credencialRepository, never()).save(any());
  }

  // ---------------------------------------------------------------------------------------------
  // Endereco do escritorio (#464): o host so restringe
  // ---------------------------------------------------------------------------------------------

  private CredencialUsuario contador() {
    return CredencialUsuario.builder()
        .id(UUID.randomUUID()).email(EMAIL).senhaHash("{bcrypt}real")
        .papel(PapelUsuario.CONTADOR).cpfCnpj("52998224725").contadorId(UUID.randomUUID())
        .ativo(true).versaoSessao(2).build();
  }

  private void senhaCerta(CredencialUsuario credencial) {
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial));
    when(bloqueioConta.reservarTentativa(credencial.getId())).thenReturn(true);
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(true);
  }

  @Test
  @DisplayName("subdominio: membro do escritorio do endereco entra")
  void membroEntraNoSubdominio() {
    CredencialUsuario credencial = contador();
    senhaCerta(credencial);
    when(enderecoService.membroDoEndereco(credencial, "ruraloeste")).thenReturn(true);
    SessaoEmitida sessao = new SessaoEmitida(new LoginResponse("jwt", "Bearer",
        Instant.parse("2026-11-09T12:15:00Z"), PapelUsuario.CONTADOR, credencial.getContadorId()),
        "refresh", Instant.parse("2026-11-16T12:00:00Z"));
    when(sessaoService.abrir(credencial)).thenReturn(sessao);

    assertThat(service.entrar(new LoginRequest(EMAIL, SENHA, "RuralOeste"),
        Optional.of("ruraloeste"))).isSameAs(sessao);
    verify(bloqueioConta).liberar(credencial.getId());
  }

  @Test
  @DisplayName("subdominio: senha certa fora do escritorio = o mesmo 401, falhas zeradas, sem sessao")
  void foraDoEscritorioRecebeO401() {
    CredencialUsuario credencial = contador();
    senhaCerta(credencial);
    when(enderecoService.membroDoEndereco(credencial, "ruraloeste")).thenReturn(false);

    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA, null),
        Optional.of("ruraloeste")))
        .isInstanceOf(CredenciaisInvalidasException.class);
    verify(bloqueioConta).liberar(credencial.getId());
    verify(sessaoService, never()).abrir(any());
  }

  @Test
  @DisplayName("corpo com escritorio diferente do host, ou escritorio pelo www: 401 sem consultar o membro")
  void corpoEHostPrecisamBater() {
    CredencialUsuario credencial = contador();
    senhaCerta(credencial);

    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA, "outro"),
        Optional.of("ruraloeste"))).isInstanceOf(CredenciaisInvalidasException.class);
    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA, "ruraloeste"),
        Optional.empty())).isInstanceOf(CredenciaisInvalidasException.class);
    verify(enderecoService, never()).membroDoEndereco(any(), any());
    verify(sessaoService, never()).abrir(any());
  }

  @Test
  @DisplayName("subdominio com senha errada: conta a falha como sempre, sem olhar o escritorio")
  void senhaErradaNoSubdominio() {
    CredencialUsuario credencial = contador();
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial));
    when(bloqueioConta.reservarTentativa(credencial.getId())).thenReturn(true);
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(false);

    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA, "ruraloeste"),
        Optional.of("ruraloeste"))).isInstanceOf(CredenciaisInvalidasException.class);
    verify(bloqueioConta, never()).liberar(any());
    verify(enderecoService, never()).membroDoEndereco(any(), any());
  }

  @Test
  @DisplayName("host principal com escritorio em branco: o login de sempre")
  void escritorioEmBrancoNoPrincipal() {
    CredencialUsuario credencial = credencial(true);
    senhaCerta(credencial);
    SessaoEmitida sessao = new SessaoEmitida(new LoginResponse("jwt", "Bearer",
        Instant.parse("2026-11-09T12:15:00Z"), PapelUsuario.PRODUTOR, credencial.getProdutorId()),
        "refresh", Instant.parse("2026-11-16T12:00:00Z"));
    when(sessaoService.abrir(credencial)).thenReturn(sessao);

    assertThat(service.entrar(new LoginRequest(EMAIL, SENHA, "  "), Optional.empty()))
        .isSameAs(sessao);
    verify(enderecoService, never()).membroDoEndereco(any(), any());
  }
}
