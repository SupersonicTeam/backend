package br.com.agrocontabil.service;

import br.com.agrocontabil.config.CadastroProperties;
import br.com.agrocontabil.config.TermosProperties;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.exception.AceiteTermosObrigatorioException;
import br.com.agrocontabil.exception.CadastroConflitoException;
import br.com.agrocontabil.exception.SlugIndisponivelException;
import br.com.agrocontabil.mapper.CredencialMapper;
import br.com.agrocontabil.mapper.EscritorioMapper;
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
import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cadastro de conta: cria o produtor ou o contador e a credencial de acesso (issue #205).
 *
 * <p><strong>Nunca reivindica identidade existente.</strong> Se o CPF/CNPJ já pertence a um
 * produtor ou contador — com ou sem credencial —, o cadastro é recusado com o mesmo 409 genérico
 * do e-mail repetido. Vincular a credencial ao registro existente entregaria a conta (e os dados
 * fiscais) a qualquer um que soubesse o CPF do titular.
 *
 * <p><strong>Hash fora da transação.</strong> O BCrypt de custo 12 leva centenas de milissegundos
 * de CPU; calculado dentro da transação, cada cadastro seguraria uma conexão do pool esse tempo
 * todo — o mesmo problema que a Onda 3 tirou da emissão. Por isso o método não é
 * {@code @Transactional}: valida e gera o hash antes, e só a checagem de duplicidade e as duas
 * gravações rodam num {@link TransactionTemplate} curto.
 *
 * <p>A checagem de duplicidade antes de gravar dá a resposta certa no caso comum; a corrida entre
 * dois cadastros simultâneos do mesmo e-mail ou documento é barrada pelas restrições únicas da
 * {@code credencial_usuario} e vira o mesmo 409.
 *
 * <p><strong>Conta pessoal ou empresarial (issue #519).</strong> O contador que se cadastra sozinho
 * ganha um escritório de que é administrador: pessoal, com o nome dele, ou empresarial, com os
 * dados do escritório e o endereço próprio, tudo na mesma transação (slug indisponível desfaz o
 * cadastro inteiro). O aceite dos termos vai para a credencial, com a data e a versão.
 */
@Service
public class CadastroService {

  private static final Logger log = LoggerFactory.getLogger(CadastroService.class);

  /** Nome do campo da senha no request, para o {@code field} dos erros da política. */
  static final String CAMPO_SENHA = "senha";

  /** Campo do slug no request do cadastro empresarial (issue #519). */
  static final String CAMPO_SLUG = "escritorio.slug";

  /** Índice único do slug: dois cadastros empresariais simultâneos podem disputá-lo (#519). */
  private static final String RESTRICAO_SLUG = "uq_escritorio_slug";

  /** Restrições únicas que dois cadastros simultâneos podem disputar. */
  private static final List<String> RESTRICOES_UNICAS_DO_CADASTRO = List.of(
      "uk_credencial_email", "uk_credencial_papel_cpf_cnpj", "uk_credencial_produtor",
      "uk_credencial_contador", "uk_contador_cpf_cnpj");

  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;
  private final EscritorioRepository escritorioRepository;
  private final CredencialUsuarioRepository credencialRepository;
  private final PasswordEncoder passwordEncoder;
  private final PoliticaSenha politicaSenha;
  private final CredencialMapper credencialMapper;
  private final EnderecoEscritorioService enderecoService;
  private final CadastroProperties propriedades;
  private final TermosProperties termos;
  private final Clock clock;
  private final TransactionTemplate transacao;

  public CadastroService(
      ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository,
      EscritorioRepository escritorioRepository,
      CredencialUsuarioRepository credencialRepository,
      PasswordEncoder passwordEncoder,
      PoliticaSenha politicaSenha,
      CredencialMapper credencialMapper,
      EnderecoEscritorioService enderecoService,
      CadastroProperties propriedades,
      TermosProperties termos,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
    this.escritorioRepository = escritorioRepository;
    this.credencialRepository = credencialRepository;
    this.passwordEncoder = passwordEncoder;
    this.politicaSenha = politicaSenha;
    this.credencialMapper = credencialMapper;
    this.enderecoService = enderecoService;
    this.propriedades = propriedades;
    this.termos = termos;
    this.clock = clock;
    this.transacao = new TransactionTemplate(transactionManager);
  }

  /**
   * Cria a conta.
   *
   * @param request dados do cadastro, já validados por Bean Validation
   * @return id da identidade criada, papel e e-mail
   * @throws br.com.agrocontabil.exception.SenhaFracaException se a senha não atender a política
   * @throws AceiteTermosObrigatorioException sem o aceite dos termos, quando ele é exigido (400)
   * @throws CadastroConflitoException se o e-mail ou o CPF/CNPJ já estiver cadastrado
   * @throws SlugIndisponivelException endereço próprio indisponível no cadastro empresarial (409)
   */
  public CadastroResponse cadastrar(CadastroRequest request) {
    return cadastrar(request, null, null);
  }

  /**
   * Como o contador entra num escritório no cadastro (issue #228): roda na transação do cadastro,
   * com o contador já gravado. Uma exceção desfaz o cadastro inteiro.
   */
  @FunctionalInterface
  interface IngressoEscritorio {

    /**
     * Põe o contador num escritório (define {@code escritorioId} e {@code funcao}).
     *
     * @param contador contador recém-gravado, gerenciado
     */
    void aplicar(Contador contador);
  }

  /**
   * Cria a conta; o contador entra no escritório que o {@code ingresso} decidir — sem ele, no
   * escritório que cria como administrador (issue #228): pessoal, com o nome dele, ou empresarial,
   * com o {@code escritorio} do request (issue #519). Com {@code ingresso}, o tipo de conta e o
   * {@code escritorio} do request são ignorados.
   *
   * <p>O {@code escritorioDoIngresso} é o escritório em que o {@code ingresso} põe o contador: o CNPJ
   * e o telefone dele também não podem estar na senha (issue #521). Ele só é lido; quem decide o
   * ingresso continua sendo o {@code ingresso}. Um caminho novo de ingresso precisa passá-lo.
   *
   * @param request              dados do cadastro, já validados por Bean Validation
   * @param escritorioDoIngresso escritório do convite (cadastro pela equipe, #228); nulo sem ele
   * @param ingresso             como o contador entra num escritório; nulo = unipessoal
   * @return id da identidade criada, papel e e-mail
   */
  CadastroResponse cadastrar(CadastroRequest request, Escritorio escritorioDoIngresso,
      IngressoEscritorio ingresso) {
    // O false ja e 400 pelo @AssertTrue do DTO; repetido aqui para quem chama o service direto.
    if (Boolean.FALSE.equals(request.aceiteTermos())
        || (request.aceiteTermos() == null && propriedades.exigirAceiteTermos())) {
      throw new AceiteTermosObrigatorioException();
    }
    String email = normalizarEmail(request.email());
    String cpfCnpj = request.cpfCnpj();
    String senha = PoliticaSenha.normalizar(request.senha());

    politicaSenha.validar(senha, dadosDoTitular(request, email, escritorioDoIngresso),
        CAMPO_SENHA);
    String senhaHash = passwordEncoder.encode(senha);

    CredencialUsuario credencial;
    try {
      credencial = transacao.execute(
          status -> gravar(request, email, cpfCnpj, senhaHash, ingresso));
    } catch (DataIntegrityViolationException e) {
      String restricao = restricaoViolada(e);
      if (restricao != null && restricao.contains(RESTRICAO_SLUG)) {
        // Dois cadastros empresariais com o mesmo slug ao mesmo tempo: o indice unico decidiu, e
        // quem perde recebe a mesma resposta de um slug em uso (#519).
        throw new SlugIndisponivelException(CAMPO_SLUG);
      }
      if (!violouUnicidadeDeCadastro(e)) {
        // Outra restricao (coluna nova esquecida, FK): e bug, nao duplicidade — nao mascarar.
        throw e;
      }
      // Corrida entre dois cadastros do mesmo e-mail/documento: a restricao unica decidiu.
      log.info("Cadastro recusado pela restricao unica (corrida de cadastro simultaneo).");
      throw new CadastroConflitoException();
    }

    log.info("Conta criada: papel={}, usuarioId={}", credencial.getPapel(), credencial.usuarioId());
    return credencialMapper.toCadastroResponse(credencial);
  }

  /**
   * O que a senha não pode conter (issue #521): o e-mail, o CPF/CNPJ e o telefone da pessoa e o CNPJ
   * e o telefone do escritório — o do request, no cadastro empresarial, ou o do convite, no
   * cadastro pela equipe (#228).
   */
  private static PoliticaSenha.DadosDoTitular dadosDoTitular(CadastroRequest request,
      String email, Escritorio escritorioDoIngresso) {
    CadastroRequest.EscritorioDoCadastro escritorio = request.escritorio();
    return new PoliticaSenha.DadosDoTitular(email,
        Arrays.asList(request.cpfCnpj(), escritorio == null ? null : escritorio.cnpj(),
            escritorioDoIngresso == null ? null : escritorioDoIngresso.getCnpj()),
        Arrays.asList(request.telefone(), escritorio == null ? null : escritorio.telefone(),
            escritorioDoIngresso == null ? null : escritorioDoIngresso.getTelefone()));
  }

  private CredencialUsuario gravar(CadastroRequest request, String email, String cpfCnpj,
      String senhaHash, IngressoEscritorio ingresso) {
    PapelUsuario papel = request.papel();
    if (credencialRepository.existsByEmail(email) || documentoJaCadastrado(papel, cpfCnpj)) {
      throw new CadastroConflitoException();
    }

    CredencialUsuario.CredencialUsuarioBuilder credencial = CredencialUsuario.builder()
        .email(email)
        .senhaHash(senhaHash)
        .papel(papel)
        .cpfCnpj(cpfCnpj);
    if (Boolean.TRUE.equals(request.aceiteTermos())) {
      credencial.termosAceitosEm(OffsetDateTime.now(clock)).termosVersao(termos.versao());
    }

    UUID usuarioId;
    String telefone = informado(request.telefone());
    if (papel == PapelUsuario.CONTADOR) {
      Contador contador = contadorRepository.saveAndFlush(Contador.builder()
          .nomeCompleto(request.nomeCompleto().strip())
          .cpfCnpj(cpfCnpj)
          .email(email)
          .crc(informado(request.crc()))
          .telefone(telefone)
          .build());
      if (ingresso == null) {
        criarEscritorio(contador, request);
      } else {
        ingresso.aplicar(contador);
      }
      usuarioId = contador.getId();
      credencial.contadorId(usuarioId);
    } else {
      usuarioId = produtorRepository.saveAndFlush(Produtor.builder()
          .nomeCompleto(request.nomeCompleto().strip())
          .cpfCnpj(cpfCnpj)
          .tipoProdutor(cpfCnpj.length() == 11
              ? TipoProdutor.PESSOA_FISICA : TipoProdutor.PESSOA_JURIDICA)
          .email(email)
          .telefone(telefone)
          .build()).getId();
      credencial.produtorId(usuarioId);
    }

    return credencialRepository.saveAndFlush(credencial.build());
  }

  /**
   * Escritório do contador que se cadastra sozinho: ele é o administrador (issue #228).
   *
   * <p>Pessoal (o tipo sem escolha, como a tela antiga): o nome é o do contador, sem CNPJ, sem
   * equipe e sem endereço próprio. Empresarial: nome, CNPJ, telefone e endereço postal do request,
   * e o endereço próprio pela regra do {@link EnderecoEscritorioService}, sem contar para o
   * intervalo entre trocas (issue #519). O CNPJ não é único nem tem a titularidade conferida:
   * unicidade sem verificação seria um oráculo e permitiria tomar o CNPJ de outro escritório
   * (issue #520).
   *
   * @param contador contador recém-gravado
   * @param request  cadastro, já validado: o empresarial traz o {@code escritorio} completo
   */
  private void criarEscritorio(Contador contador, CadastroRequest request) {
    CadastroRequest.EscritorioDoCadastro dados = request.escritorio();
    Escritorio escritorio;
    if (request.tipoConta() == TipoEscritorio.EMPRESARIAL) {
      if (dados == null) {
        // O request HTTP nunca chega aqui sem ele (CadastroCoerenteComPapel): so um bug de quem
        // chama o service direto.
        throw new IllegalArgumentException("Cadastro empresarial sem os dados do escritório");
      }
      escritorio = escritorioRepository.save(Escritorio.builder()
          .tipo(TipoEscritorio.EMPRESARIAL)
          .nome(dados.nome().strip())
          .cnpj(dados.cnpj())
          .telefone(dados.telefone())
          .endereco(EscritorioMapper.paraEndereco(dados.endereco()))
          .build());
      enderecoService.gravarNoCadastro(escritorio, dados.slug(), CAMPO_SLUG);
    } else {
      escritorio = escritorioRepository.save(Escritorio.builder()
          .tipo(TipoEscritorio.PESSOAL)
          .nome(contador.getNomeCompleto())
          .build());
    }
    contador.setEscritorioId(escritorio.getId());
    contador.setFuncao(FuncaoEscritorio.ADMINISTRADOR);
  }

  /** Campo opcional do cadastro: sem espaços nas pontas; vazio ou só espaços = não informado. */
  private static String informado(String valor) {
    return valor == null || valor.isBlank() ? null : valor.strip();
  }

  /**
   * O documento já pertence a alguém neste papel — na tabela da identidade (em qualquer grafia)
   * ou numa credencial.
   */
  private boolean documentoJaCadastrado(PapelUsuario papel, String cpfCnpj) {
    List<String> grafias = List.of(cpfCnpj, comMascara(cpfCnpj));
    boolean naIdentidade = papel == PapelUsuario.CONTADOR
        ? contadorRepository.existsByCpfCnpjIn(grafias)
        : produtorRepository.existsByCpfCnpjIn(grafias);
    return naIdentidade || credencialRepository.existsByPapelAndCpfCnpj(papel, cpfCnpj);
  }

  /**
   * Normaliza o e-mail de login: NFKC, sem espaços nas pontas e em minúsculas.
   *
   * <p>A parte local de um e-mail é tecnicamente sensível a maiúsculas (RFC 5321), mas nenhum
   * provedor real a trata assim; aceitar {@code Joao@} e {@code joao@} como contas diferentes
   * abriria espaço para uma conta-sombra com o e-mail de outra pessoa. Pelo mesmo motivo o NFKC:
   * {@code josé@} com o acento composto e com o acento combinante são o mesmo endereço na tela e
   * precisam ser a mesma conta.
   *
   * @param email e-mail como digitado
   * @return e-mail normalizado
   */
  public static String normalizarEmail(String email) {
    return email == null
        ? null
        : Normalizer.normalize(email, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
  }

  /**
   * A violação veio de uma das restrições únicas que o cadastro disputa (changeSets 0024/0031)?
   *
   * <p>Pelo nome da restrição, que o Hibernate extrai da mensagem do driver; o PostgreSQL o
   * devolve em minúsculas e o H2 em maiúsculas, daí a comparação sem caixa.
   */
  public static boolean violouUnicidadeDeCadastro(DataIntegrityViolationException e) {
    String nome = restricaoViolada(e);
    return nome != null && RESTRICOES_UNICAS_DO_CADASTRO.stream().anyMatch(nome::contains);
  }

  /** Nome da restrição violada, em minúsculas; nulo se o driver não o informou. */
  private static String restricaoViolada(DataIntegrityViolationException e) {
    Throwable causa = e;
    while (causa != null) {
      if (causa instanceof ConstraintViolationException violacao
          && violacao.getConstraintName() != null) {
        return violacao.getConstraintName().toLowerCase(Locale.ROOT);
      }
      causa = causa.getCause();
    }
    return null;
  }

  /** CPF {@code 000.000.000-00} ou CNPJ {@code 00.000.000/0000-00}, a grafia do seed. */
  static String comMascara(String digitos) {
    if (digitos.length() == 11) {
      return "%s.%s.%s-%s".formatted(digitos.substring(0, 3), digitos.substring(3, 6),
          digitos.substring(6, 9), digitos.substring(9));
    }
    return "%s.%s.%s/%s-%s".formatted(digitos.substring(0, 2), digitos.substring(2, 5),
        digitos.substring(5, 8), digitos.substring(8, 12), digitos.substring(12));
  }
}
