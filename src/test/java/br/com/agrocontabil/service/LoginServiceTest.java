package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoginService — e-mail e senha por JWT (#206)")
class LoginServiceTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL = "joaquim@fazenda.com.br";

  @Mock private CredencialUsuarioRepository credencialRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private SessaoService sessaoService;
  @Mock private UsuarioAutenticadoProvider usuarioProvider;

  private LoginService service;

  @BeforeEach
  void setUp() {
    when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}ficticio");
    service = new LoginService(credencialRepository, passwordEncoder, sessaoService,
        usuarioProvider);
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
    verify(sessaoService, never()).abrir(any());
  }

  @Test
  @DisplayName("senha errada: 401 e nenhum token")
  void senhaErrada() {
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial(true)));
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(false);

    assertThatThrownBy(() -> service.entrar(new LoginRequest(EMAIL, SENHA)))
        .isInstanceOf(CredenciaisInvalidasException.class);
  }

  @Test
  @DisplayName("credencial desativada com a senha certa: o mesmo 401 (nao revela que existe)")
  void desativada() {
    when(credencialRepository.findByEmail(EMAIL)).thenReturn(Optional.of(credencial(false)));
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
    when(passwordEncoder.matches(SENHA, "{bcrypt}real")).thenReturn(true);
    when(passwordEncoder.upgradeEncoding("{bcrypt}real")).thenReturn(true);
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}novo");
    when(sessaoService.abrir(credencial)).thenReturn(new SessaoEmitida(
        new LoginResponse("jwt", "Bearer", Instant.now(), PapelUsuario.PRODUTOR, UUID.randomUUID()),
        "refresh", Instant.now()));

    service.entrar(new LoginRequest(EMAIL, SENHA));

    verify(credencialRepository).atualizarSenhaHash(credencial.getId(), "{bcrypt}novo");
    verify(credencialRepository, never()).save(any());
  }
}
