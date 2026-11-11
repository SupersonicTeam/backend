package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.CadastroProperties;
import br.com.agrocontabil.config.TermosProperties;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroRequest.EscritorioDoCadastro;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.dto.EnderecoPostalRequest;
import br.com.agrocontabil.exception.AceiteTermosObrigatorioException;
import br.com.agrocontabil.exception.CadastroConflitoException;
import br.com.agrocontabil.exception.SenhaFracaException;
import br.com.agrocontabil.exception.SlugIndisponivelException;
import br.com.agrocontabil.mapper.CredencialMapper;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoEscritorio;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("CadastroService — conta com credencial (#205, #519)")
class CadastroServiceTest {

  private static final String CPF = "52998224725";
  private static final String SENHA = "cavalo bateria grampo correto";
  private static final Instant AGORA = Instant.parse("2026-11-11T15:00:00Z");

  @Mock private ProdutorRepository produtorRepository;
  @Mock private ContadorRepository contadorRepository;
  @Mock private EscritorioRepository escritorioRepository;
  @Mock private CredencialUsuarioRepository credencialRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private EnderecoEscritorioService enderecoService;

  private CadastroService service;

  @BeforeEach
  void setUp() {
    service = servico(false);
  }

  private CadastroService servico(boolean exigirAceiteTermos) {
    return new CadastroService(produtorRepository, contadorRepository, escritorioRepository,
        credencialRepository, passwordEncoder, new PoliticaSenha(), new CredencialMapper(),
        enderecoService, new CadastroProperties(exigirAceiteTermos),
        new TermosProperties("2026-11-11"), Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo")),
        transactionManager);
  }

  private void comTransacao() {
    TransactionStatus status = new SimpleTransactionStatus();
    when(transactionManager.getTransaction(any())).thenReturn(status);
  }

  private CadastroRequest pedido(PapelUsuario papel, String documento) {
    return new CadastroRequest(papel, "  Fazenda Boa Vista ", documento,
        "  Joaquim@Fazenda.COM.br ", SENHA);
  }

  @Test
  @DisplayName("produtor: grava produtor PF e credencial com hash e e-mail normalizado")
  void cadastraProdutor() {
    comTransacao();
    UUID produtorId = UUID.randomUUID();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.saveAndFlush(any(Produtor.class))).thenAnswer(inv -> {
      Produtor p = inv.getArgument(0);
      p.setId(produtorId);
      return p;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CadastroResponse resposta = service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF));

    assertThat(resposta.usuarioId()).isEqualTo(produtorId);
    assertThat(resposta.papel()).isEqualTo(PapelUsuario.PRODUTOR);
    assertThat(resposta.email()).isEqualTo("joaquim@fazenda.com.br");

    ArgumentCaptor<Produtor> produtor = ArgumentCaptor.forClass(Produtor.class);
    verify(produtorRepository).saveAndFlush(produtor.capture());
    assertThat(produtor.getValue().getNomeCompleto()).isEqualTo("Fazenda Boa Vista");
    assertThat(produtor.getValue().getTipoProdutor()).isEqualTo(TipoProdutor.PESSOA_FISICA);
    assertThat(produtor.getValue().getCpfCnpj()).isEqualTo(CPF);

    ArgumentCaptor<CredencialUsuario> credencial = ArgumentCaptor.forClass(CredencialUsuario.class);
    verify(credencialRepository).saveAndFlush(credencial.capture());
    assertThat(credencial.getValue().getSenhaHash()).isEqualTo("{bcrypt}hash");
    assertThat(credencial.getValue().getProdutorId()).isEqualTo(produtorId);
    assertThat(credencial.getValue().getContadorId()).isNull();
    assertThat(credencial.getValue().isAtivo()).isTrue();
    verifyNoInteractions(contadorRepository);
  }

  /** Stubs do caminho feliz do contador; devolve o id que o escritório recebe. */
  private UUID comContadorGravavel(UUID contadorId) {
    comTransacao();
    UUID escritorioId = UUID.randomUUID();
    when(escritorioRepository.save(any(Escritorio.class))).thenAnswer(inv -> {
      Escritorio e = inv.getArgument(0);
      e.setId(escritorioId);
      return e;
    });
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(contadorRepository.saveAndFlush(any(Contador.class))).thenAnswer(inv -> {
      Contador c = inv.getArgument(0);
      c.setId(contadorId);
      return c;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    return escritorioId;
  }

  private static EnderecoPostalRequest endereco() {
    return new EnderecoPostalRequest("85810000", " Rua Parana ", "1200", " ", "Centro", "4104808",
        "Cascavel", "PR");
  }

  private static EscritorioDoCadastro escritorioCompleto() {
    return new EscritorioDoCadastro("  Contabilidade Campo Verde ", "11444777000161",
        "4533330000", endereco(), "campoverde", true);
  }

  private CadastroRequest pedidoDeContador(TipoEscritorio tipo, EscritorioDoCadastro escritorio,
      String crc, String telefone, Boolean aceite) {
    return new CadastroRequest(PapelUsuario.CONTADOR, " Patricia Moreira ", CPF,
        "patricia@exemplo.com.br", SENHA, escritorio, crc, telefone, tipo, aceite);
  }

  @Test
  @DisplayName("contador sem tipo de conta (tela antiga): pessoal, com o nome dele, administrador")
  void cadastraContador() {
    UUID contadorId = UUID.randomUUID();
    UUID escritorioId = comContadorGravavel(contadorId);

    CadastroResponse resposta = service.cadastrar(pedido(PapelUsuario.CONTADOR, CPF));

    assertThat(resposta.usuarioId()).isEqualTo(contadorId);
    ArgumentCaptor<Contador> contador = ArgumentCaptor.forClass(Contador.class);
    verify(contadorRepository).saveAndFlush(contador.capture());
    assertThat(contador.getValue().getEscritorioId()).isEqualTo(escritorioId);
    assertThat(contador.getValue().getFuncao()).isEqualTo(FuncaoEscritorio.ADMINISTRADOR);
    assertThat(contador.getValue().getCrc()).isNull();
    assertThat(contador.getValue().getTelefone()).isNull();
    ArgumentCaptor<Escritorio> escritorio = ArgumentCaptor.forClass(Escritorio.class);
    verify(escritorioRepository).save(escritorio.capture());
    assertThat(escritorio.getValue().getTipo()).isEqualTo(TipoEscritorio.PESSOAL);
    assertThat(escritorio.getValue().getNome()).isEqualTo("Fazenda Boa Vista");
    assertThat(escritorio.getValue().getCnpj()).isNull();
    assertThat(escritorio.getValue().getTelefone()).isNull();
    assertThat(escritorio.getValue().getEndereco()).isNull();
    assertThat(resposta.papel()).isEqualTo(PapelUsuario.CONTADOR);
    verify(contadorRepository).existsByCpfCnpjIn(List.of(CPF, "529.982.247-25"));
    verify(produtorRepository, never()).saveAndFlush(any());
    verifyNoInteractions(enderecoService);
  }

  @Test
  @DisplayName("contador pessoal (#519): escritorio com o nome dele; CRC e telefone no contador")
  void cadastraContadorPessoal() {
    UUID contadorId = UUID.randomUUID();
    UUID escritorioId = comContadorGravavel(contadorId);

    service.cadastrar(pedidoDeContador(TipoEscritorio.PESSOAL, null, " PR-012345/O ",
        "45999990000", true));

    ArgumentCaptor<Escritorio> escritorio = ArgumentCaptor.forClass(Escritorio.class);
    verify(escritorioRepository).save(escritorio.capture());
    assertThat(escritorio.getValue().getTipo()).isEqualTo(TipoEscritorio.PESSOAL);
    assertThat(escritorio.getValue().getNome()).isEqualTo("Patricia Moreira");
    assertThat(escritorio.getValue().getCnpj()).isNull();
    ArgumentCaptor<Contador> contador = ArgumentCaptor.forClass(Contador.class);
    verify(contadorRepository).saveAndFlush(contador.capture());
    assertThat(contador.getValue().getNomeCompleto()).isEqualTo("Patricia Moreira");
    assertThat(contador.getValue().getCpfCnpj()).isEqualTo(CPF);
    assertThat(contador.getValue().getCrc()).isEqualTo("PR-012345/O");
    assertThat(contador.getValue().getTelefone()).isEqualTo("45999990000");
    assertThat(contador.getValue().getEscritorioId()).isEqualTo(escritorioId);
    verifyNoInteractions(enderecoService);
  }

  @Test
  @DisplayName("contador empresarial (#519): escritorio completo e slug pela regra do endereco")
  void cadastraContadorEmpresarial() {
    UUID escritorioId = comContadorGravavel(UUID.randomUUID());

    service.cadastrar(pedidoDeContador(TipoEscritorio.EMPRESARIAL, escritorioCompleto(), null,
        null, true));

    ArgumentCaptor<Escritorio> escritorio = ArgumentCaptor.forClass(Escritorio.class);
    verify(escritorioRepository).save(escritorio.capture());
    Escritorio gravado = escritorio.getValue();
    assertThat(gravado.getTipo()).isEqualTo(TipoEscritorio.EMPRESARIAL);
    assertThat(gravado.getNome()).isEqualTo("Contabilidade Campo Verde");
    assertThat(gravado.getCnpj()).isEqualTo("11444777000161");
    assertThat(gravado.getTelefone()).isEqualTo("4533330000");
    assertThat(gravado.getEndereco().getLogradouro()).isEqualTo("Rua Parana");
    assertThat(gravado.getEndereco().getComplemento()).isNull();
    assertThat(gravado.getEndereco().getCodigoMunicipioIbge()).isEqualTo("4104808");
    assertThat(gravado.getEndereco().getUf()).isEqualTo("PR");
    // O slug vai pela regra do endereco proprio, sem contar o intervalo; o service o grava.
    assertThat(gravado.getSlugAlteradoEm()).isNull();
    verify(enderecoService).gravarNoCadastro(gravado, "campoverde", "escritorio.slug");
    assertThat(gravado.getId()).isEqualTo(escritorioId);
  }

  @Test
  @DisplayName("empresarial sem os dados do escritorio (chamada direta ao service): falha antes")
  void empresarialSemEscritorio() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(contadorRepository.saveAndFlush(any(Contador.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThatThrownBy(() -> service.cadastrar(
        pedidoDeContador(TipoEscritorio.EMPRESARIAL, null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(escritorioRepository, enderecoService);
    verify(credencialRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("slug disputado por dois cadastros simultaneos (#519): 409 SLUG_INDISPONIVEL")
  void corridaDoSlug() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(contadorRepository.saveAndFlush(any(Contador.class))).thenAnswer(inv -> inv.getArgument(0));
    when(escritorioRepository.save(any(Escritorio.class))).thenAnswer(inv -> inv.getArgument(0));
    doThrow(violacao("UQ_ESCRITORIO_SLUG_INDEX_9")).when(enderecoService)
        .gravarNoCadastro(any(Escritorio.class), eq("campoverde"), eq("escritorio.slug"));

    assertThatThrownBy(() -> service.cadastrar(pedidoDeContador(TipoEscritorio.EMPRESARIAL,
        escritorioCompleto(), null, null, true)))
        .isInstanceOfSatisfying(SlugIndisponivelException.class,
            e -> assertThat(e.getErrors().get(0).field()).isEqualTo("escritorio.slug"));
  }

  @Test
  @DisplayName("contador (#519): CRC e telefone vazios = nao informados")
  void camposVaziosNaoSaoGravados() {
    comContadorGravavel(UUID.randomUUID());

    service.cadastrar(pedidoDeContador(null, null, "   ", "", null));

    ArgumentCaptor<Contador> contador = ArgumentCaptor.forClass(Contador.class);
    verify(contadorRepository).saveAndFlush(contador.capture());
    assertThat(contador.getValue().getCrc()).isNull();
    assertThat(contador.getValue().getTelefone()).isNull();
  }

  @Test
  @DisplayName("contador pelo convite (#228): tipo e escritorio do request ignorados, vale o ingresso")
  void ingressoIgnoraEscritorioDoRequest() {
    comTransacao();
    UUID escritorioDaEquipe = UUID.randomUUID();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(contadorRepository.saveAndFlush(any(Contador.class))).thenAnswer(inv -> {
      Contador c = inv.getArgument(0);
      c.setId(UUID.randomUUID());
      return c;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.cadastrar(pedidoDeContador(TipoEscritorio.EMPRESARIAL, escritorioCompleto(), null,
        null, null), contador -> {
          contador.setEscritorioId(escritorioDaEquipe);
          contador.setFuncao(FuncaoEscritorio.ASSISTENTE);
        });

    verifyNoInteractions(escritorioRepository, enderecoService);
    ArgumentCaptor<Contador> contador = ArgumentCaptor.forClass(Contador.class);
    verify(contadorRepository).saveAndFlush(contador.capture());
    assertThat(contador.getValue().getEscritorioId()).isEqualTo(escritorioDaEquipe);
    assertThat(contador.getValue().getFuncao()).isEqualTo(FuncaoEscritorio.ASSISTENTE);
  }

  @Test
  @DisplayName("produtor (#519): telefone informado vai para o produtor")
  void produtorComTelefone() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.saveAndFlush(any(Produtor.class))).thenAnswer(inv -> {
      Produtor p = inv.getArgument(0);
      p.setId(UUID.randomUUID());
      return p;
    });
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service.cadastrar(new CadastroRequest(PapelUsuario.PRODUTOR, "Fazenda", CPF,
        "joaquim@fazenda.com.br", SENHA, null, null, "6533330001", null, null));

    ArgumentCaptor<Produtor> produtor = ArgumentCaptor.forClass(Produtor.class);
    verify(produtorRepository).saveAndFlush(produtor.capture());
    assertThat(produtor.getValue().getTelefone()).isEqualTo("6533330001");
    verifyNoInteractions(contadorRepository, escritorioRepository);
  }

  @Test
  @DisplayName("aceite dos termos (#519): true grava a data e a versao na credencial")
  void aceiteDosTermosGravado() {
    comContadorGravavel(UUID.randomUUID());

    service.cadastrar(pedidoDeContador(null, null, null, null, true));

    ArgumentCaptor<CredencialUsuario> credencial = ArgumentCaptor.forClass(CredencialUsuario.class);
    verify(credencialRepository).saveAndFlush(credencial.capture());
    assertThat(credencial.getValue().getTermosAceitosEm())
        .isEqualTo(OffsetDateTime.ofInstant(AGORA, ZoneId.of("America/Sao_Paulo")));
    assertThat(credencial.getValue().getTermosVersao()).isEqualTo("2026-11-11");
  }

  @Test
  @DisplayName("sem o aceite e sem exigencia (#519): a conta nasce, aceite nao registrado")
  void semAceiteNaoRegistrado() {
    comContadorGravavel(UUID.randomUUID());

    service.cadastrar(pedidoDeContador(null, null, null, null, null));

    ArgumentCaptor<CredencialUsuario> credencial = ArgumentCaptor.forClass(CredencialUsuario.class);
    verify(credencialRepository).saveAndFlush(credencial.capture());
    assertThat(credencial.getValue().getTermosAceitosEm()).isNull();
    assertThat(credencial.getValue().getTermosVersao()).isNull();
  }

  @Test
  @DisplayName("aceite false, ou ausente com a exigencia ligada (#519): 400 antes de tudo")
  void aceiteRecusado() {
    assertThatThrownBy(() -> service.cadastrar(
        pedidoDeContador(null, null, null, null, false)))
        .isInstanceOfSatisfying(AceiteTermosObrigatorioException.class,
            e -> assertThat(e.getErrors().get(0).field()).isEqualTo("aceiteTermos"));
    assertThatThrownBy(() -> servico(true).cadastrar(
        pedidoDeContador(null, null, null, null, null)))
        .isInstanceOf(AceiteTermosObrigatorioException.class);
    verifyNoInteractions(passwordEncoder, transactionManager, contadorRepository,
        credencialRepository);
  }

  @Test
  @DisplayName("CPF de produtor ja existente (mesmo com mascara, sem credencial) nunca e reivindicado")
  void documentoExistenteNaoEhReivindicado() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.existsByCpfCnpjIn(List.of(CPF, "529.982.247-25"))).thenReturn(true);

    assertThatThrownBy(() -> service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF)))
        .isInstanceOf(CadastroConflitoException.class);
    verify(produtorRepository, never()).saveAndFlush(any());
    verify(credencialRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("e-mail ja cadastrado responde o mesmo conflito do documento")
  void emailRepetido() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(credencialRepository.existsByEmail("joaquim@fazenda.com.br")).thenReturn(true);

    assertThatThrownBy(() -> service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF)))
        .isInstanceOf(CadastroConflitoException.class);
    verify(produtorRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("corrida barrada pela restricao unica vira o mesmo 409")
  void corridaViraConflito() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.saveAndFlush(any(Produtor.class))).thenAnswer(inv -> {
      Produtor p = inv.getArgument(0);
      p.setId(UUID.randomUUID());
      return p;
    });
    // H2 devolve o nome da restricao em maiusculas; o PostgreSQL, em minusculas.
    when(credencialRepository.saveAndFlush(any(CredencialUsuario.class)))
        .thenThrow(violacao("UK_CREDENCIAL_EMAIL"));

    assertThatThrownBy(() -> service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF)))
        .isInstanceOf(CadastroConflitoException.class);
  }

  @Test
  @DisplayName("violacao que nao e de unicidade do cadastro sobe como esta (nao vira 'ja cadastrado')")
  void outraViolacaoNaoEhMascarada() {
    comTransacao();
    when(passwordEncoder.encode(SENHA)).thenReturn("{bcrypt}hash");
    when(produtorRepository.saveAndFlush(any(Produtor.class)))
        .thenThrow(violacao("fk_qualquer_coisa"));

    assertThatThrownBy(() -> service.cadastrar(pedido(PapelUsuario.PRODUTOR, CPF)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(CadastroConflitoException.class);
    assertThat(CadastroService.violouUnicidadeDeCadastro(
        new DataIntegrityViolationException("sem causa"))).isFalse();
  }

  @Test
  @DisplayName("e-mail normalizado por NFKC: acento composto e combinante sao a mesma conta")
  void emailNfkc() {
    assertThat(CadastroService.normalizarEmail(" José@Fazenda.com "))
        .isEqualTo(CadastroService.normalizarEmail("josé@fazenda.com"))
        .isEqualTo("josé@fazenda.com");
    assertThat(CadastroService.normalizarEmail(null)).isNull();
  }

  private static DataIntegrityViolationException violacao(String restricao) {
    return new DataIntegrityViolationException("violacao", new ConstraintViolationException(
        "violacao", new SQLException("dup"), restricao));
  }

  @Test
  @DisplayName("senha fraca para antes do hash e de qualquer acesso ao banco")
  void senhaFracaNaoTocaBanco() {
    CadastroRequest fraca = new CadastroRequest(PapelUsuario.PRODUTOR, "Fazenda", CPF,
        "joaquim@fazenda.com.br", "curta");

    assertThatThrownBy(() -> service.cadastrar(fraca)).isInstanceOf(SenhaFracaException.class);
    verify(passwordEncoder, never()).encode(anyString());
    verifyNoInteractions(transactionManager, credencialRepository, produtorRepository);
  }

  @Test
  @DisplayName("mascara do documento segue a grafia do seed")
  void mascara() {
    assertThat(CadastroService.comMascara("12345678900")).isEqualTo("123.456.789-00");
    assertThat(CadastroService.comMascara("12345678000195")).isEqualTo("12.345.678/0001-95");
  }

  @Test
  @DisplayName("toString do request sem senha, e-mail, documento, CNPJ, CRC, telefone nem endereco")
  void toStringSemSenha() {
    assertThat(pedido(PapelUsuario.PRODUTOR, CPF).toString())
        .doesNotContain(SENHA, CPF, "Joaquim", "joaquim");

    EscritorioDoCadastro escritorio = escritorioCompleto();
    String contador = pedidoDeContador(TipoEscritorio.EMPRESARIAL, escritorio, "PR-012345/O",
        "45999990000", true).toString();
    assertThat(contador).isEqualTo("CadastroRequest[papel=CONTADOR, tipoConta=EMPRESARIAL]")
        .doesNotContain(CPF, "11444777000161", "PR-012345", "45999990000", "Campo Verde",
            "4533330000", "Parana", "campoverde", "Patricia", "patricia");
    assertThat(escritorio.toString()).doesNotContain("11444777000161", "Campo Verde",
        "4533330000", "Parana", "campoverde");
  }
}
