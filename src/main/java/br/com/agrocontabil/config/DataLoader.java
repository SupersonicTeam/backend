package br.com.agrocontabil.config;

import br.com.agrocontabil.exception.SenhaFracaException;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.CredencialUsuario;
import br.com.agrocontabil.model.EnderecoPostal;
import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.PorteEmpresa;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.TipoEscritorio;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.CredencialUsuarioRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ObrigacaoFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.ObrigacaoFiscalService;
import br.com.agrocontabil.service.PoliticaSenha;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Semeia os produtores usados no desenvolvimento e na homologacao.
 *
 * <p>Sao <strong>dois</strong> produtores desde a issue #91: com um so, o anti-IDOR aplicado em toda
 * a API ({@code findByIdAndProdutorId} → 404) nao tem como ser exercido — nao existe "o outro
 * produtor" cujo recurso deva ser negado. O produtor A e tambem o <em>semente</em>: e ele que o
 * {@link MockIdentidadeFilter} injeta em dev quando a requisicao nao declara {@code X-Usuario-Id}.
 * Em homolog nao ha mock (#221): as contas semeadas so sao alcancaveis pelo login, com credencial
 * criada quando {@code SEED_SENHA} esta definida.
 *
 * <p>Desde a issue #93 semeia tambem <strong>um contador</strong>, vinculado apenas ao produtor A —
 * o vinculo parcial e o que permite exercitar, em dev e na homologacao, tanto o acesso autorizado
 * (produtor A) quanto a negativa por falta de vinculo (produtor B).
 *
 * <p>Ambos carregam inscricao estadual e endereco completos: desde a issue #88 isso e pre-condicao
 * da emissao, e um produtor sem esses dados geraria XML com placeholder. Os municipios sao
 * diferentes de proposito — assim uma troca acidental de produtor aparece no XML em vez de passar
 * despercebida.
 */
@Component
@Profile({"dev", "homolog"})
public class DataLoader implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

  /**
   * Produtor A — o semente, injetado pelo {@link MockIdentidadeFilter} quando a requisicao nao
   * declara {@code X-Usuario-Id}.
   *
   * <p>Publica de proposito: o filtro procura o semente por este CPF, e duas constantes iguais em
   * arquivos diferentes se separam no primeiro refactor — o build ficaria verde e o fallback
   * silenciosamente pararia de resolver.
   */
  public static final String CPF_PRODUTOR_SEMENTE = "123.456.789-00";

  /** Produtor B — existe para dar um "outro dono" aos cenarios de ownership. */
  private static final String CPF_PRODUTOR_B = "987.654.321-00";

  /**
   * Contador semente (issue #93) — vinculado <strong>apenas</strong> ao produtor A.
   *
   * <p>O vinculo parcial e o ponto: com o contador atendendo os dois produtores, nao existiria
   * "produtor que este contador nao atende" e a negativa de acesso por falta de vinculo nao teria
   * como ser exercida em dev nem na homologacao.
   */
  private static final String CNPJ_CONTADOR = "12.345.678/0001-95";

  /**
   * Premissa da demonstração (issue #280): o produtor semeado é optante pelo regime regular do
   * IBS/CBS (LC 214, art. 165). Não há campo de modelo para a situação do produtor (issue #281);
   * o registro vai nas observações internas do vínculo, que o contador vê na ficha. Só no vínculo
   * criado pelo seed: banco já semeado recebe o texto pela própria ficha
   * ({@code PATCH /api/contador/produtores/{produtorId}/ficha}), nunca por reescrita na subida.
   */
  static final String OBSERVACAO_OPTANTE = "Optante pelo regime regular do IBS/CBS (LC 214/2025, "
      + "art. 165): receita abaixo do limite do art. 164, inscrito como contribuinte por opção. O "
      + "AgroConIA calcula o IBS/CBS do regime regular (premissa da issue #280).";

  /** Modelos de obrigação do changeSet 0040 que o seed personaliza (issue #181). */
  static final UUID MODELO_DAS = UUID.fromString("00000000-0000-4000-8181-000000000001");
  static final UUID MODELO_DEFIS = UUID.fromString("00000000-0000-4000-8181-000000000004");

  private final ProdutorRepository produtorRepository;
  private final ContadorRepository contadorRepository;
  private final VinculoCarteiraRepository vinculoRepository;
  private final EscritorioRepository escritorioRepository;
  private final ObrigacaoFiscalRepository obrigacaoRepository;
  private final CredencialUsuarioRepository credencialRepository;
  private final PasswordEncoder passwordEncoder;
  private final PoliticaSenha politicaSenha;
  private final String senhaSeed;

  public DataLoader(
      ProdutorRepository produtorRepository,
      ContadorRepository contadorRepository,
      VinculoCarteiraRepository vinculoRepository,
      EscritorioRepository escritorioRepository,
      ObrigacaoFiscalRepository obrigacaoRepository,
      CredencialUsuarioRepository credencialRepository,
      PasswordEncoder passwordEncoder,
      PoliticaSenha politicaSenha,
      @Value("${app.seed.senha:}") String senhaSeed) {
    this.produtorRepository = produtorRepository;
    this.contadorRepository = contadorRepository;
    this.vinculoRepository = vinculoRepository;
    this.escritorioRepository = escritorioRepository;
    this.obrigacaoRepository = obrigacaoRepository;
    this.credencialRepository = credencialRepository;
    this.passwordEncoder = passwordEncoder;
    this.politicaSenha = politicaSenha;
    this.senhaSeed = senhaSeed;
  }

  @Override
  public void run(String... args) {
    Produtor produtorA = semear(produtorA());
    Produtor produtorB = semear(produtorB());
    Contador contador = semearContador(produtorA);
    semearCredenciais(produtorA, produtorB, contador);
  }

  /**
   * Dá às três identidades semeadas uma credencial de login (issue #206), com a senha de
   * {@code SEED_SENHA}.
   *
   * <p>Sem a variável, nada é criado e o login das sementes fica indisponível — o
   * {@code X-Usuario-Id} continua funcionando. A senha nunca tem valor padrão no código nem vai
   * para o log, e precisa passar pela mesma política do cadastro: uma senha fraca de homologação é
   * exatamente o tipo de credencial que vaza e é testada em produção.
   *
   * <p>Idempotente pelo e-mail: credencial existente não é tocada (nem a senha, que pode ter sido
   * trocada pelo desenvolvedor).
   */
  void semearCredenciais(Produtor produtorA, Produtor produtorB, Contador contador) {
    if (senhaSeed == null || senhaSeed.isBlank()) {
      log.info("SEED_SENHA vazia: contas semeadas sem credencial (em dev, use X-Usuario-Id; em "
          + "homolog, defina SEED_SENHA: so o login autentica, #221).");
      return;
    }
    String senha = PoliticaSenha.normalizar(senhaSeed);
    try {
      politicaSenha.validar(senha, null, null, "SEED_SENHA");
    } catch (SenhaFracaException e) {
      log.warn("SEED_SENHA nao atende a politica de senha: contas semeadas sem credencial.");
      return;
    }
    String hash = passwordEncoder.encode(senha);
    semearCredencial(produtorA.getEmail(), PapelUsuario.PRODUTOR, produtorA.getCpfCnpj(),
        produtorA.getId(), null, hash);
    semearCredencial(produtorB.getEmail(), PapelUsuario.PRODUTOR, produtorB.getCpfCnpj(),
        produtorB.getId(), null, hash);
    semearCredencial(contador.getEmail(), PapelUsuario.CONTADOR, contador.getCpfCnpj(),
        null, contador.getId(), hash);
  }

  private void semearCredencial(String email, PapelUsuario papel, String cpfCnpj,
      UUID produtorId, UUID contadorId, String hash) {
    String emailNormalizado = CadastroService.normalizarEmail(email);
    if (credencialRepository.existsByEmail(emailNormalizado)) {
      return;
    }
    credencialRepository.save(CredencialUsuario.builder()
        .email(emailNormalizado)
        .senhaHash(hash)
        .papel(papel)
        .cpfCnpj(cpfCnpj.replaceAll("\\D", ""))
        .produtorId(produtorId)
        .contadorId(contadorId)
        .build());
    log.info("Credencial semeada: papel={} (login pelo e-mail do seed, senha de SEED_SENHA)", papel);
  }

  /**
   * Persiste o produtor se ainda nao existir, e loga o id para uso no header {@code X-Usuario-Id}.
   *
   * @param produtor produtor a semear
   * @return o produtor persistido, ou o que ja estava no banco com o mesmo CPF/CNPJ
   */
  private Produtor semear(Produtor produtor) {
    Optional<Produtor> existente = produtorRepository.findByCpfCnpj(produtor.getCpfCnpj());
    if (existente.isPresent()) {
      Produtor salvo = existente.get();
      if (RegimeTributario.incompativel(salvo.getTipoProdutor(), salvo.getRegimeTributario())) {
        // O seed nao reescreve produtor ja gravado; a correcao e manual (issue #298). A NF-e e o
        // calendario ja tratam a pessoa fisica como regime normal.
        log.warn("Produtor semeado {} e pessoa fisica com regime {}: corrija pelo passo de "
            + "docs/demonstracao-banca.md (issue #298).", salvo.getId(),
            salvo.getRegimeTributario());
      }
      return salvo;
    }

    Produtor salvo = produtorRepository.save(produtor);
    // O id nao e segredo — ja viaja na URL de varios endpoints — e e o valor que o
    // desenvolvedor precisa ter em maos para simular este usuario via header.
    log.info(
        "Produtor semeado: {} (id: {})", salvo.getNomeCompleto(), salvo.getId());
    return salvo;
  }

  /**
   * Semeia o contador de dev/homolog vinculado ao produtor A (issue #93).
   *
   * <p>Idempotente pelo CNPJ, como o seed dos produtores. O vinculo e criado junto do contador: se
   * o contador ja existe, nada e alterado — inclusive porque mexer na carteira de um contador ja
   * semeado apagaria vinculos criados a mao durante o desenvolvimento.
   *
   * @param produtorA produtor que este contador atende
   * @return o contador semeado, ou o que ja existia com o mesmo CNPJ
   */
  private Contador semearContador(Produtor produtorA) {
    Optional<Contador> existente = contadorRepository.findByCpfCnpj(CNPJ_CONTADOR);
    if (existente.isPresent()) {
      return existente.get();
    }

    // Escritorio do seed com o contador como administrador (issue #228). Empresarial, como o da
    // demonstracao (#519): com CNPJ, telefone e endereco postal, convida equipe e grava endereco
    // proprio. So em banco novo: no ja semeado o metodo volta antes, e o escritorio fica com a
    // classificacao do changeSet 0062 (sem CNPJ e com um membro, PESSOAL). O documento do
    // contador semeado segue o CNPJ de antes da #519 (conta existente).
    Escritorio escritorio = escritorioRepository.save(Escritorio.builder()
        .tipo(TipoEscritorio.EMPRESARIAL)
        .nome("Escritorio Contabil Campo Certo")
        .cnpj(CNPJ_CONTADOR.replaceAll("\\D", ""))
        .telefone("6533330001")
        .endereco(EnderecoPostal.builder()
            .cep("78005000")
            .logradouro("Avenida Getulio Vargas")
            .numero("500")
            .bairro("Centro")
            .codigoMunicipioIbge("5103403")
            .municipio("Cuiaba")
            .uf("MT")
            .build())
        .build());
    Contador contador =
        Contador.builder()
            .escritorioId(escritorio.getId())
            .funcao(FuncaoEscritorio.ADMINISTRADOR)
            .nomeCompleto("Escritorio Contabil Campo Certo")
            .cpfCnpj(CNPJ_CONTADOR)
            .crc("MT-012345/O")
            .email("contato@campocerto.example.com")
            .telefone("(65) 3333-0001")
            .build();

    Contador salvo = contadorRepository.save(contador);
    personalizarObrigacoes(escritorio.getId());
    // Vinculo ATIVO direto, sem convite: e o seed de dev/homolog (issue #177).
    vinculoRepository.save(VinculoCarteira.builder()
        .escritorioId(escritorio.getId())
        .responsavelId(salvo.getId())
        .produtorId(produtorA.getId())
        .situacao(SituacaoVinculo.ATIVO)
        .inicio(OffsetDateTime.now())
        .observacoes(OBSERVACAO_OPTANTE)
        .build());
    log.info(
        "Contador semeado: {} (id: {}) — atende o produtor {}",
        salvo.getNomeCompleto(),
        salvo.getId(),
        produtorA.getId());
    return salvo;
  }

  /**
   * Personaliza o catálogo de obrigações do escritório semeado (issue #181), para a demonstração
   * mostrar as duas formas: a DEFIS desativada e o DAS com outro dia. Sem os modelos (banco sem a
   * 0040), nada é feito.
   */
  private void personalizarObrigacoes(UUID escritorioId) {
    obrigacaoRepository.findModeloById(MODELO_DEFIS).ifPresent(defis ->
        obrigacaoRepository.save(
            ObrigacaoFiscalService.versaoDoEscritorio(defis, escritorioId).ativa(false).build()));
    obrigacaoRepository.findModeloById(MODELO_DAS).ifPresent(das ->
        obrigacaoRepository.save(ObrigacaoFiscalService.versaoDoEscritorio(das, escritorioId)
            .titulo("DAS / PGDAS-D (conferir guia)")
            .descricao("Lembrete interno: conferir a guia dois dias antes do vencimento")
            .dia(18)
            .build()));
  }

  private Produtor produtorA() {
    return Produtor.builder()
        .nomeCompleto("João da Silva Produtor")
        .cpfCnpj(CPF_PRODUTOR_SEMENTE)
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        // Regime normal (CRT 3): pessoa fisica nao entra no Simples nem no MEI (issue #298).
        .regimeTributario(RegimeTributario.REAL)
        .porteEmpresa(PorteEmpresa.ME)
        .culturas(new String[] {"soja", "milho", "algodao"})
        .faturamentoAnual(new BigDecimal("2500000.00"))
        .estado("MT")
        // Endereco e IE reais sao pre-condicao da emissao desde a issue #88: sem eles o
        // seed geraria XML com placeholders, rejeitado pela SEFAZ. Cuiaba/MT (IBGE 5103403).
        .inscricaoEstadual("1234567890")
        .endereco(
            EnderecoProdutor.builder()
                .logradouro("Avenida Historiador Rubens de Mendonca")
                .numero("1894")
                .bairro("Bosque da Saude")
                .codigoMunicipioIbge("5103403")
                .municipio("Cuiaba")
                .cep("78050000")
                .build())
        .email("joao.produtor@example.com")
        .telefone("(65) 99999-0001")
        .build();
  }

  private Produtor produtorB() {
    return Produtor.builder()
        .nomeCompleto("Maria Agricultora")
        .cpfCnpj(CPF_PRODUTOR_B)
        .tipoProdutor(TipoProdutor.PESSOA_FISICA)
        .regimeTributario(RegimeTributario.REAL)
        .porteEmpresa(PorteEmpresa.ME)
        .culturas(new String[] {"soja", "girassol"})
        .faturamentoAnual(new BigDecimal("1800000.00"))
        .estado("MT")
        // Rondonopolis/MT (IBGE 5107602) — municipio e IE distintos dos do produtor A.
        .inscricaoEstadual("0987654321")
        .endereco(
            EnderecoProdutor.builder()
                .logradouro("Avenida Bandeirantes")
                .numero("742")
                .bairro("Vila Aurora")
                .codigoMunicipioIbge("5107602")
                .municipio("Rondonopolis")
                .cep("78710000")
                .build())
        .email("maria.agricultora@example.com")
        .telefone("(66) 99999-0002")
        .build();
  }
}
