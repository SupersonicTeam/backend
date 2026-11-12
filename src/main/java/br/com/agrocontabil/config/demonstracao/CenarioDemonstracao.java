package br.com.agrocontabil.config.demonstracao;

import br.com.agrocontabil.model.EnderecoPostal;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.PorteEmpresa;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.TipoDocumentoPendencia;
import java.math.BigDecimal;
import java.util.List;

/**
 * Os dados da demonstração da banca: um escritório de contabilidade rural com equipe e carteira
 * diversa, compradores e fornecedores (issue #315).
 *
 * <p>Tudo é <strong>fictício</strong>, mas com a forma de dado real: CPF e CNPJ com dígito
 * verificador válido (calculado por {@link #cpf}/{@link #cnpj}), município com o código IBGE de
 * verdade, NCM da referência, CFOP pela UF das partes e preço de mercado de 2026. Nenhum nome é de
 * empresa real: uma razão social conhecida com CNPJ inventado seria uma nota falsa de alguém.
 *
 * <p>Os clientes novos são de <strong>regime normal</strong> (CRT 3), coerentes com a premissa do
 * regime regular do IBS/CBS (issue #280). Pessoa física nunca fica em {@code SIMPLES} nem
 * {@code MEI} (issue #298): o {@code Produtor} recusa a gravação.
 */
final class CenarioDemonstracao {

  private CenarioDemonstracao() {}

  /** Endereço com o código IBGE real do município. */
  record Endereco(String logradouro, String numero, String bairro, String codigoIbge,
      String municipio, String uf, String cep) {}

  /** Comprador ou fornecedor: pessoa jurídica com IE, ou pessoa física sem IE. */
  record Parte(String nome, String documento, String ie, Endereco endereco) {}

  /** Membro da equipe do escritório. */
  record Membro(String nome, String cpf, String crc, String email, String telefone,
      FuncaoEscritorio funcao) {}

  /**
   * Produto ou insumo, com o preço de referência por unidade e, quando o NCM não decide o
   * tratamento, o {@code cClassTrib} informado no item (o leite de consumo direto, #284).
   */
  record Produto(String codigo, String descricao, String ncm, String unidade, BigDecimal preco,
      String cClassTrib) {

    /** Sem cClassTrib informado: vale a regra por NCM. */
    Produto(String codigo, String descricao, String ncm, String unidade, BigDecimal preco) {
      this(codigo, descricao, ncm, unidade, preco, null);
    }
  }

  /**
   * Linha de venda recorrente: o produto sai para os compradores, em rodízio, nos meses indicados.
   *
   * @param meses       meses atrás em que há venda (0 = mês corrente)
   * @param notasPorMes notas emitidas por mês ativo
   * @param quantidade  quantidade de referência por nota (varia ±15% de forma determinística)
   */
  record LinhaVenda(Produto produto, List<Parte> compradores, List<Integer> meses,
      int notasPorMes, BigDecimal quantidade) {}

  /** Compra de insumo (NF-e do fornecedor, importada pelo produtor). */
  record LinhaCompra(Produto produto, Parte fornecedor, List<Integer> meses, BigDecimal quantidade) {}

  /**
   * Cliente do escritório.
   *
   * @param chave        apelido estável usado nos testes e nas notas especiais
   * @param responsavel  índice do membro responsável em {@link #EQUIPE}, ou {@code -1} para nenhum
   * @param numeroInicial primeiro número de NF-e da série 1 (a numeração já vem de antes do sistema)
   */
  record Cliente(String chave, String nome, String documento, boolean pessoaJuridica,
      RegimeTributario regime, PorteEmpresa porte, List<String> culturas, BigDecimal faturamento,
      String ie, Endereco endereco, String email, String telefone, SituacaoVinculo situacao,
      int responsavel, List<String> etiquetas, String observacoes, long numeroInicial,
      List<LinhaVenda> vendas, List<LinhaCompra> compras) {}

  // ---------------------------------------------------------------------------------------------
  // Escritório e equipe
  // ---------------------------------------------------------------------------------------------

  static final String ESCRITORIO_NOME = "Contabilidade Rural Oeste";
  static final String ESCRITORIO_CNPJ = cnpj("483152760001");

  /**
   * Endereço próprio do escritório, {@code ruraloeste.agroconia.com.br} (issue #464). Na VPS só
   * chega pelo reset dos dados da demonstração: o seed não toca num escritório já gravado.
   */
  static final String ESCRITORIO_SLUG = "ruraloeste";

  /**
   * Telefone e endereço postal do escritório, que é empresarial (issue #519). Como o slug, na VPS
   * só chegam pelo reset dos dados da demonstração: o escritório já gravado lá vira EMPRESARIAL pelo
   * changeSet 0062 (tem CNPJ), mas fica sem telefone e endereço até o reset.
   */
  static final String ESCRITORIO_TELEFONE = "4532264100";

  /** Endereço postal do escritório; um objeto novo por chamada (a entidade é mutável). */
  static EnderecoPostal escritorioEndereco() {
    return EnderecoPostal.builder()
        .cep("85810000")
        .logradouro("Rua Parana")
        .numero("1200")
        .complemento("Sala 3")
        .bairro("Centro")
        .codigoMunicipioIbge("4104808")
        .municipio("Cascavel")
        .uf("PR")
        .build();
  }

  /** Administradora, contador e assistente: as três funções da #228. */
  static final List<Membro> EQUIPE = List.of(
      new Membro("Patricia Moreira Lopes", cpf("318452906"), "PR-061245/O",
          "patricia.moreira@example.com", "(45) 3226-4101", FuncaoEscritorio.ADMINISTRADOR),
      new Membro("Rafael Augusto Costa", cpf("427180635"), "PR-072318/O",
          "rafael.costa@example.com", "(45) 3226-4102", FuncaoEscritorio.CONTADOR),
      new Membro("Juliana Prado Ferreira", cpf("605914273"), null,
          "juliana.prado@example.com", "(45) 3226-4103", FuncaoEscritorio.ASSISTENTE));

  /**
   * Modelo de pedido de documento do escritório (issue #517).
   *
   * @param prazoEmDias prazo sugerido, em dias a partir do pedido ({@code null} = sem prazo)
   */
  record ModeloDePedido(String titulo, String descricao, List<TipoDocumentoPendencia> tipos,
      Integer prazoEmDias, String observacoes) {}

  /**
   * Modelos de pedido do escritório (issue #517): os pedidos que ele repete todo mês, para o
   * catálogo não aparecer vazio na banca. Textos com acento (#408): nenhum script de reset nem XML
   * depende deles.
   */
  static final List<ModeloDePedido> MODELOS_PEDIDO = List.of(
      new ModeloDePedido("Extrato bancário do mês",
          "Extrato de todas as contas da fazenda no mês anterior, para conciliar as vendas e as "
              + "compras.",
          List.of(TipoDocumentoPendencia.EXTRATO), 10,
          "Pode mandar em PDF, um arquivo por conta."),
      new ModeloDePedido("Comprovante de pagamento do Funrural",
          "Comprovante de pagamento da guia do Funrural do mês anterior.",
          List.of(TipoDocumentoPendencia.COMPROVANTE_PAGAMENTO), 5, null),
      new ModeloDePedido("Contrato de arrendamento",
          "Contrato de arrendamento vigente e o último recibo pago, para o livro caixa.",
          List.of(TipoDocumentoPendencia.CONTRATO, TipoDocumentoPendencia.COMPROVANTE_PAGAMENTO),
          15, "Se o contrato foi renovado, mande a versão nova, assinada."));

  static final String OPTANTE = "Optante pelo regime regular do IBS/CBS (LC 214/2025, art. 165): "
      + "receita abaixo do limite do art. 164, inscrito como contribuinte por opção.";
  static final String ACIMA_DO_LIMITE = "Contribuinte do IBS/CBS pelo regime regular: receita "
      + "acima do limite do art. 164 da LC 214/2025.";

  // ---------------------------------------------------------------------------------------------
  // Compradores
  // ---------------------------------------------------------------------------------------------

  private static final Parte GRANELEIRA_TELES_PIRES = new Parte(
      "Graneleira Vale do Teles Pires Ltda", cnpj("274813950001"), "133518276",
      new Endereco("Rodovia BR-163", "KM 742", "Distrito Industrial", "5107925", "Sorriso", "MT",
          "78890000"));
  // Trading de MT, com IE: a venda da soja fica interna a contribuinte (5101, ICMS diferido, CST
  // 51, confirmado pelo escritorio na #284, pergunta 9). Antes era um terminal exportador no PR,
  // venda interestadual sem CST defensavel (a exportacao indireta traria a suspensao do IBS/CBS do
  // art. 82 da LC 214 e a imunidade do Funrural, que o sistema nao modela).
  private static final Parte TRADING_LUCAS = new Parte(
      "Trading de Graos Chapadao do Norte Ltda", cnpj("361904570001"), "134072958",
      new Endereco("Rodovia BR-163", "KM 690", "Distrito Industrial", "5105259",
          "Lucas do Rio Verde", "MT", "78455000"));
  private static final Parte ALGODOEIRA_PRIMAVERA = new Parte(
      "Algodoeira Primavera do Leste Ltda", cnpj("509127330001"), "132904185",
      new Endereco("Avenida Sao Joao", "3150", "Parque Industrial", "5107040",
          "Primavera do Leste", "MT", "78850000"));
  private static final Parte PECUARISTA_PF = new Parte(
      "Antonio Carlos Pereira", cpf("731468209"), null,
      new Endereco("Estrada Vicinal Gleba Celeste", "S/N", "Zona Rural", "5107925", "Sorriso",
          "MT", "78890000"));
  private static final Parte TORREFACAO_UBERLANDIA = new Parte(
      "Torrefacao Cerrado Mineiro Ltda", cnpj("618230490001"), "7024618350091",
      new Endereco("Avenida Joao Naves de Avila", "4410", "Santa Monica", "3170206", "Uberlandia",
          "MG", "38408100"));
  // Cerealista de MG, com IE: venda interna do cafe a contribuinte (5101, CST 51 pelo padrao). O
  // diferimento do cafe cru em MG e de demonstracao, a confirmar com o contador. Antes era uma
  // exportadora em Santos (interestadual, sem CST defensavel).
  private static final Parte CEREALISTA_PATROCINIO = new Parte(
      "Cerealista Alto Paranaiba Ltda", cnpj("725419860001"), "4801736290084",
      new Endereco("Avenida Faria Pereira", "2100", "Distrito Industrial", "3148103",
          "Patrocinio", "MG", "38740000"));
  /**
   * Comprador do leite UHT do Luiz: uma rede de supermercados (revenda ao consumidor), não um
   * laticínio. Desde o #374/#380, leite vendido a laticínio é o leite cru, in natura (60%); o UHT,
   * de consumo direto, vai ao varejo, e o par produto × comprador fica coerente com a regra.
   */
  private static final Parte SUPERMERCADO_TOLEDO = new Parte(
      "Supermercados Bom Dia Oeste Ltda", cnpj("847136250001"), "9071324586",
      new Endereco("Avenida Parigot de Souza", "2380", "Centro", "4127700", "Toledo", "PR",
          "85900040"));
  private static final Parte DISTRIBUIDORA_SP = new Parte(
      "Distribuidora Hortifruti Paulistana Ltda", cnpj("941736280001"), "117405829110",
      new Endereco("Avenida Doutor Gastao Vidigal", "1946", "Vila Leopoldina", "3550308",
          "Sao Paulo", "SP", "05314000"));
  private static final Parte FRIGORIFICO_TRES_LAGOAS = new Parte(
      "Frigorifico Costa Leste Ltda", cnpj("152849370001"), "283746159",
      new Endereco("Rodovia BR-262", "KM 12", "Zona Industrial", "5008305", "Tres Lagoas", "MS",
          "79601970"));
  private static final Parte PECUARIA_UBERABA = new Parte(
      "Central de Genetica Bovina Triangulo Ltda", cnpj("263950480001"), "7012846390188",
      new Endereco("Avenida Filomena Cartafina", "2150", "Parque das Americas", "3170107",
          "Uberaba", "MG", "38045000"));
  private static final Parte ATACADO_PORTO_ALEGRE = new Parte(
      "Atacado Gaucho de Alimentos Ltda", cnpj("374061590001"), "0961847253",
      new Endereco("Avenida dos Estados", "3700", "Anchieta", "4314902", "Porto Alegre", "RS",
          "90200001"));
  private static final Parte CEREALISTA_RIO_VERDE = new Parte(
      "Cerealista Sudoeste Goiano Ltda", cnpj("485172600001"), "104829376",
      new Endereco("Avenida Presidente Vargas", "2880", "Jardim Goias", "5218805", "Rio Verde",
          "GO", "75903290"));
  private static final Parte ATACADO_CASCAVEL = new Parte(
      "Mercado Atacadista do Oeste Ltda", cnpj("596283710001"), "9087123456",
      new Endereco("Avenida Brasil", "9850", "Centro", "4104808", "Cascavel", "PR", "85816000"));
  private static final Parte MOINHO_PONTA_GROSSA = new Parte(
      "Moinho Campos Gerais Ltda", cnpj("607394820001"), "9034567812",
      new Endereco("Avenida Souza Naves", "3600", "Chapada", "4119905", "Ponta Grossa", "PR",
          "84063000"));

  // ---------------------------------------------------------------------------------------------
  // Fornecedores de insumo
  // ---------------------------------------------------------------------------------------------

  private static final Parte INSUMOS_CERRADO = new Parte(
      "Agro Insumos Cerrado Ltda", cnpj("718405930001"), "133627481",
      new Endereco("Avenida Blumenau", "1585", "Centro", "5107925", "Sorriso", "MT", "78890000"));
  private static final Parte POSTO_LUCAS = new Parte(
      "Posto Rodoviario Chapadao Ltda", cnpj("829516040001"), "133795102",
      new Endereco("Rodovia BR-163", "KM 685", "Area Industrial", "5105259",
          "Lucas do Rio Verde", "MT", "78455000"));
  private static final Parte SEMENTES_PRIMAVERA = new Parte(
      "Sementes Planalto Matogrossense Ltda", cnpj("930627150001"), "132468017",
      new Endereco("Rodovia MT-130", "KM 4", "Zona Rural", "5107040", "Primavera do Leste", "MT",
          "78850000"));
  private static final Parte FERTILIZANTES_UBERLANDIA = new Parte(
      "Fertiminas Distribuidora de Fertilizantes Ltda", cnpj("141738260001"), "7023184650047",
      new Endereco("Avenida Comendador Alexandrino Garcia", "2900", "Distrito Industrial",
          "3170206", "Uberlandia", "MG", "38402350"));
  private static final Parte RACOES_CASCAVEL = new Parte(
      "Nutricao Animal Oeste Ltda", cnpj("252849370001"), "9076452381",
      new Endereco("Rua Carlos Gomes", "2200", "Parque Sao Paulo", "4104808", "Cascavel", "PR",
          "85803400"));
  private static final Parte PECAS_RIO_VERDE = new Parte(
      "Horizonte Maquinas Agricolas Ltda", cnpj("363950480001"), "105738264",
      new Endereco("Avenida Joao Belo", "1450", "Setor Industrial", "5218805", "Rio Verde", "GO",
          "75905360"));
  private static final Parte AGROPECUARIA_PELOTAS = new Parte(
      "Agropecuaria Zona Sul Insumos Ltda", cnpj("474061590001"), "0934781526",
      new Endereco("Avenida Fernando Osorio", "4100", "Tres Vendas", "4314407", "Pelotas", "RS",
          "96065000"));

  // ---------------------------------------------------------------------------------------------
  // Produtos (preços de referência de 2026)
  // ---------------------------------------------------------------------------------------------

  private static final Produto SOJA = new Produto("SOJA-GR",
      "Soja em grao a granel, safra 2025/26", "12019000", "KG", new BigDecimal("2.12"));
  private static final Produto MILHO = new Produto("MILHO-GR",
      "Milho em grao a granel, segunda safra 2026", "10059010", "KG", new BigDecimal("0.96"));
  private static final Produto ALGODAO = new Produto("ALG-CAR",
      "Algodao em caroco, nao cardado nem penteado", "52010020", "KG", new BigDecimal("4.35"));
  private static final Produto CAFE = new Produto("CAFE-AR",
      "Cafe arabica cru em grao, tipo 6, bebida dura, saca 60 kg", "09011110", "SC",
      new BigDecimal("1890.00"));
  /**
   * Leite UHT integral em caixa de 1 litro (0401.20.10, o UHT da nomenclatura): consumo direto
   * pela população, alíquota zero pelo Anexo I, item 2, já pela regra (#380). O item ainda informa
   * o cClassTrib 200003, que não atrapalha e deixa a nota sem depender do NCM.
   */
  private static final Produto LEITE = new Produto("LEITE-UHT",
      "Leite UHT integral, caixa de 1 litro", "04012010", "L",
      new BigDecimal("2.48"), "200003");
  private static final Produto TOMATE = new Produto("TOM-ITA",
      "Tomate italiano extra AA, caixa 20 kg", "07020000", "CX", new BigDecimal("86.00"));
  private static final Produto OVOS = new Produto("OVO-BR",
      "Ovos brancos de galinha tipo grande, caixa 30 duzias", "04072100", "CX",
      new BigDecimal("178.00"));
  private static final Produto BOVINO = new Produto("BOV-NEL",
      "Touro Nelore PO reprodutor, com registro genealogico", "01022110", "CAB",
      new BigDecimal("14500.00"));
  private static final Produto ARROZ = new Produto("ARROZ-T1",
      "Arroz branco polido tipo 1, fardo 30 kg", "10063021", "FD", new BigDecimal("142.00"));
  private static final Produto TRIGO = new Produto("TRIGO-PH",
      "Trigo em grao tipo pao, PH 78", "10011900", "KG", new BigDecimal("1.42"));

  private static final Produto NPK = new Produto("NPK-02-20-20",
      "Fertilizante mineral misto NPK 02-20-20, big bag", "31052000", "KG",
      new BigDecimal("3.85"));
  private static final Produto UREIA = new Produto("UREIA-46",
      "Ureia agricola 46% N, big bag", "31021010", "KG", new BigDecimal("3.18"));
  private static final Produto KCL = new Produto("KCL-60",
      "Cloreto de potassio granulado 60% K2O", "31042010", "KG", new BigDecimal("2.92"));
  private static final Produto HERBICIDA = new Produto("HERB-GLI480",
      "Herbicida glifosato 480 g/L SL, embalagem 20 L", "38089329", "L",
      new BigDecimal("27.90"));
  private static final Produto FUNGICIDA = new Produto("FUNG-TRIAZ",
      "Fungicida triazol + estrobilurina SC, embalagem 5 L", "38089219", "L",
      new BigDecimal("96.50"));
  private static final Produto INSETICIDA = new Produto("INS-PIRET",
      "Inseticida piretroide EC, embalagem 5 L", "38089199", "L", new BigDecimal("118.00"));
  private static final Produto DIESEL = new Produto("DIESEL-S10",
      "Oleo diesel B S10 rodoviario", "27101921", "L", new BigDecimal("6.19"));
  private static final Produto SEMENTE_SOJA = new Produto("SEM-SOJA",
      "Semente de soja certificada C1, bag 40 kg", "12011000", "KG", new BigDecimal("7.40"));
  private static final Produto RACAO = new Produto("RACAO-LEITE",
      "Racao concentrada para vacas em lactacao 22% PB", "23099010", "KG",
      new BigDecimal("2.06"));
  private static final Produto PECAS = new Produto("PECA-PLAT",
      "Kit de facas e dedos para plataforma de colhedora", "84339090", "UN",
      new BigDecimal("18450.00"));

  // ---------------------------------------------------------------------------------------------
  // Carteira
  // ---------------------------------------------------------------------------------------------

  /** Produtor principal da demonstração: grande, acima do limite do art. 164, muitas notas. */
  static final String CHAVE_PRINCIPAL = "carlos";
  /** Produtor com a cota semanal da Ceres quase no fim (issue #166). */
  static final String CHAVE_COTA_NO_FIM = "rogerio";

  static final List<Cliente> CARTEIRA = List.of(
      new Cliente(CHAVE_PRINCIPAL, "Carlos Eduardo Rossi", cpf("284716530"), false,
          RegimeTributario.REAL, PorteEmpresa.GRANDE, List.of("soja", "milho", "algodao"),
          new BigDecimal("6800000.00"), "13248615720",
          new Endereco("Estrada Gleba Rio Verde", "KM 38", "Zona Rural", "5107925", "Sorriso", "MT",
              "78890000"),
          "carlos.rossi@example.com", "(66) 99631-4087", SituacaoVinculo.ATIVO, 0,
          List.of("Grãos", "Acima do limite do art. 164", "Prioridade"), ACIMA_DO_LIMITE, 3184,
          List.of(
              new LinhaVenda(SOJA, List.of(GRANELEIRA_TELES_PIRES, TRADING_LUCAS),
                  List.of(7, 6, 5, 4, 3, 2, 1, 0), 3, new BigDecimal("37000")),
              new LinhaVenda(MILHO, List.of(GRANELEIRA_TELES_PIRES, PECUARISTA_PF),
                  List.of(5, 4, 3, 2, 1), 2, new BigDecimal("42000")),
              new LinhaVenda(ALGODAO, List.of(ALGODOEIRA_PRIMAVERA), List.of(3, 2, 1), 1,
                  new BigDecimal("24000"))),
          List.of(
              new LinhaCompra(NPK, INSUMOS_CERRADO, List.of(4, 3), new BigDecimal("60000")),
              new LinhaCompra(SEMENTE_SOJA, SEMENTES_PRIMAVERA, List.of(3),
                  new BigDecimal("14000")),
              new LinhaCompra(HERBICIDA, INSUMOS_CERRADO, List.of(5, 2), new BigDecimal("2400")),
              new LinhaCompra(FUNGICIDA, INSUMOS_CERRADO, List.of(6, 1), new BigDecimal("650")),
              new LinhaCompra(INSETICIDA, INSUMOS_CERRADO, List.of(1), new BigDecimal("320")),
              new LinhaCompra(DIESEL, POSTO_LUCAS, List.of(7, 6, 5, 4, 3, 2, 1, 0),
                  new BigDecimal("12000")))),
      new Cliente("santaclara", "Fazenda Santa Clara Agropecuaria Ltda", cnpj("196384270001"),
          true, RegimeTributario.REAL, PorteEmpresa.MEDIO, List.of("cafe"),
          new BigDecimal("4200000.00"), "7031592840073",
          new Endereco("Rodovia MG-230", "KM 21", "Zona Rural", "3148103", "Patrocinio", "MG",
              "38740000"),
          "financeiro.santaclara@example.com", "(34) 3831-2290", SituacaoVinculo.ATIVO, 1,
          List.of("Café", "Acima do limite do art. 164"), ACIMA_DO_LIMITE, 912,
          List.of(new LinhaVenda(CAFE, List.of(TORREFACAO_UBERLANDIA, CEREALISTA_PATROCINIO),
              List.of(6, 5, 4, 3, 2, 1, 0), 2, new BigDecimal("280"))),
          List.of(
              new LinhaCompra(KCL, FERTILIZANTES_UBERLANDIA, List.of(5, 2), new BigDecimal("22000")),
              new LinhaCompra(NPK, FERTILIZANTES_UBERLANDIA, List.of(4, 1),
                  new BigDecimal("30000")),
              new LinhaCompra(FUNGICIDA, FERTILIZANTES_UBERLANDIA, List.of(3),
                  new BigDecimal("420")))),
      new Cliente("luiz", "Luiz Carlos Bertoldo", cpf("519027384"), false, RegimeTributario.REAL,
          PorteEmpresa.EPP, List.of("leite", "milho"), new BigDecimal("1650000.00"), "9061738425",
          new Endereco("Linha Sao Salvador", "S/N", "Zona Rural", "4104808", "Cascavel", "PR",
              "85818899"),
          "luiz.bertoldo@example.com", "(45) 99912-3368", SituacaoVinculo.ATIVO, 1,
          List.of("Leite", "Optante (art. 165)"), OPTANTE, 418,
          List.of(new LinhaVenda(LEITE, List.of(SUPERMERCADO_TOLEDO), List.of(6, 5, 4, 3, 2, 1, 0), 2,
              new BigDecimal("21000"))),
          List.of(
              new LinhaCompra(RACAO, RACOES_CASCAVEL, List.of(6, 5, 4, 3, 2, 1, 0),
                  new BigDecimal("14000")),
              new LinhaCompra(UREIA, RACOES_CASCAVEL, List.of(4), new BigDecimal("6000")))),
      new Cliente("helena", "Helena Takahashi", cpf("630815247"), false, RegimeTributario.REAL,
          PorteEmpresa.EPP, List.of("tomate", "hortalicas"), new BigDecimal("2100000.00"),
          "455183027114",
          new Endereco("Estrada do Taboao", "4120", "Taboao", "3530607", "Mogi das Cruzes", "SP",
              "08770000"),
          "helena.takahashi@example.com", "(11) 99725-0814", SituacaoVinculo.ATIVO, 2,
          List.of("Hortifrúti", "Optante (art. 165)", "Novo cliente"), OPTANTE, 1507,
          List.of(new LinhaVenda(TOMATE, List.of(DISTRIBUIDORA_SP), List.of(4, 3, 2, 1, 0), 3,
              new BigDecimal("780"))),
          List.of()),
      new Cliente("trespontes", "Agropecuaria Tres Pontes Ltda", cnpj("307495380001"), true,
          RegimeTributario.PRESUMIDO, PorteEmpresa.MEDIO, List.of("pecuaria de corte"),
          new BigDecimal("3900000.00"), "283951604",
          new Endereco("Rodovia BR-163", "KM 470", "Zona Rural", "5002704", "Campo Grande", "MS",
              "79115000"),
          "contato.trespontes@example.com", "(67) 3341-7720", SituacaoVinculo.ATIVO, 0,
          List.of("Pecuária", "Acima do limite do art. 164"), ACIMA_DO_LIMITE, 276,
          List.of(new LinhaVenda(BOVINO, List.of(FRIGORIFICO_TRES_LAGOAS, PECUARIA_UBERABA),
              List.of(6, 4, 2, 1), 1, new BigDecimal("18"))),
          List.of()),
      new Cliente(CHAVE_COTA_NO_FIM, "Rogerio Antunes Vasconcelos", cpf("742063915"), false,
          RegimeTributario.REAL, PorteEmpresa.MEDIO, List.of("soja", "milho"),
          new BigDecimal("2900000.00"), "106284735",
          new Endereco("Rodovia GO-174", "KM 18", "Zona Rural", "5218805", "Rio Verde", "GO",
              "75909899"),
          "rogerio.antunes@example.com", "(64) 99654-2201", SituacaoVinculo.ATIVO, -1,
          List.of("Grãos", "Optante (art. 165)"), OPTANTE, 655,
          List.of(
              new LinhaVenda(SOJA, List.of(CEREALISTA_RIO_VERDE), List.of(5, 4, 3, 1), 2,
                  new BigDecimal("35000")),
              new LinhaVenda(MILHO, List.of(CEREALISTA_RIO_VERDE), List.of(2, 0), 1,
                  new BigDecimal("40000"))),
          List.of(
              new LinhaCompra(PECAS, PECAS_RIO_VERDE, List.of(2), BigDecimal.ONE),
              new LinhaCompra(HERBICIDA, PECAS_RIO_VERDE, List.of(4), new BigDecimal("1600")))),
      new Cliente("granjatoledo", "Granja Toledo Ovos e Aves Ltda", cnpj("418506490001"), true,
          RegimeTributario.REAL, PorteEmpresa.EPP, List.of("ovos"), new BigDecimal("2600000.00"),
          "9045812736",
          new Endereco("Estrada Linha Gramado", "1250", "Zona Rural", "4127700", "Toledo", "PR",
              "85919899"),
          "granja.toledo@example.com", "(45) 3277-5612", SituacaoVinculo.ATIVO, 2,
          List.of("Optante (art. 165)"), OPTANTE, 2041,
          List.of(new LinhaVenda(OVOS, List.of(ATACADO_CASCAVEL), List.of(5, 4, 3, 2, 1, 0), 2,
              new BigDecimal("380"))),
          List.of(new LinhaCompra(RACAO, RACOES_CASCAVEL, List.of(5, 3, 1),
              new BigDecimal("26000")))),
      new Cliente("schmitt", "Arrozeira Familia Schmitt Ltda", cnpj("529617500001"), true,
          RegimeTributario.REAL, PorteEmpresa.EPP, List.of("arroz irrigado"),
          new BigDecimal("3100000.00"), "0937154820",
          new Endereco("Estrada do Passo dos Carros", "880", "Zona Rural", "4314407", "Pelotas",
              "RS", "96160000"),
          "arrozeira.schmitt@example.com", "(53) 3228-6409", SituacaoVinculo.SUSPENSO, 1,
          List.of("Grãos"), "Vínculo suspenso a pedido do cliente até a renegociação do contrato de "
              + "honorários.", 733,
          List.of(new LinhaVenda(ARROZ, List.of(ATACADO_PORTO_ALEGRE), List.of(6, 5, 4), 2,
              new BigDecimal("1150"))),
          List.of(new LinhaCompra(UREIA, AGROPECUARIA_PELOTAS, List.of(6),
              new BigDecimal("18000")))),
      new Cliente("marcos", "Marcos Vieira Lacerda", cpf("853174026"), false,
          RegimeTributario.REAL, PorteEmpresa.EPP, List.of("trigo", "soja"),
          new BigDecimal("1200000.00"), "9028461735",
          new Endereco("Colonia Castrolanda", "S/N", "Zona Rural", "4119905", "Ponta Grossa", "PR",
              "84165899"),
          "marcos.lacerda@example.com", "(42) 99815-7740", SituacaoVinculo.ENCERRADO, -1,
          List.of(), null, 120,
          List.of(new LinhaVenda(TRIGO, List.of(MOINHO_PONTA_GROSSA), List.of(7, 6), 1,
              new BigDecimal("30000"))),
          List.of()));

  static Cliente cliente(String chave) {
    return CARTEIRA.stream().filter(c -> c.chave().equals(chave)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Cliente da demonstracao: " + chave));
  }

  /** Compradores usados pelas notas especiais do produtor principal. */
  static Parte tradingLucas() {
    return TRADING_LUCAS;
  }

  static Parte graneleiraTelesPires() {
    return GRANELEIRA_TELES_PIRES;
  }

  static Parte distribuidoraSaoPaulo() {
    return DISTRIBUIDORA_SP;
  }

  static Parte torrefacaoUberlandia() {
    return TORREFACAO_UBERLANDIA;
  }

  static Parte supermercadoToledo() {
    return SUPERMERCADO_TOLEDO;
  }

  static Produto soja() {
    return SOJA;
  }

  static Produto tomate() {
    return TOMATE;
  }

  static Produto cafe() {
    return CAFE;
  }

  static Produto leite() {
    return LEITE;
  }

  // ---------------------------------------------------------------------------------------------
  // Documentos com dígito verificador válido
  // ---------------------------------------------------------------------------------------------

  /** CPF (só dígitos) a partir dos 9 primeiros, com os dois dígitos verificadores calculados. */
  static String cpf(String noveDigitos) {
    String base = exigirDigitos(noveDigitos, 9);
    int d1 = digitoModulo11(base, new int[] {10, 9, 8, 7, 6, 5, 4, 3, 2});
    int d2 = digitoModulo11(base + d1, new int[] {11, 10, 9, 8, 7, 6, 5, 4, 3, 2});
    return base + d1 + d2;
  }

  /** CNPJ (só dígitos) a partir dos 12 primeiros, com os dois dígitos verificadores calculados. */
  static String cnpj(String dozeDigitos) {
    String base = exigirDigitos(dozeDigitos, 12);
    int d1 = digitoModulo11(base, new int[] {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
    int d2 = digitoModulo11(base + d1, new int[] {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
    return base + d1 + d2;
  }

  private static int digitoModulo11(String digitos, int[] pesos) {
    int soma = 0;
    for (int i = 0; i < pesos.length; i++) {
      soma += (digitos.charAt(i) - '0') * pesos[i];
    }
    int resto = soma % 11;
    return resto < 2 ? 0 : 11 - resto;
  }

  private static String exigirDigitos(String valor, int tamanho) {
    if (valor == null || !valor.matches("\\d{" + tamanho + "}")) {
      throw new IllegalArgumentException("Esperados " + tamanho + " digitos: " + valor);
    }
    return valor;
  }
}
