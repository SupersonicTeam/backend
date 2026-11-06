package br.com.agrocontabil.service;

import br.com.agrocontabil.nfe.TributacaoIcms;
import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ItemParaSinal;
import br.com.agrocontabil.repository.OutraNota;
import br.com.agrocontabil.repository.ValorUnitarioReferencia;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.SinalNota.CodigoSinalNota;
import br.com.agrocontabil.service.SinalNota.Momento;
import br.com.agrocontabil.service.SinalNota.Motivo;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sinais de nota possivelmente emitida errada (issue #276): o que a validação fiscal não pega
 * porque a nota é válida no formato, mas talvez errada no propósito ou no conteúdo. Regra de
 * domínio, determinística, fora do módulo da Ceres — que a lê pela porta e pode ser reaproveitada
 * na emissão ou na revisão, se a equipe decidir.
 *
 * <p>Só olha as notas do próprio produtor (anti-IDOR: os itens são lidos pelo produtor; de outro
 * = nenhum) e só as que ele emitiu ({@link OrigemNotaFiscal#PROPRIA}): a nota de compra foi o
 * fornecedor quem emitiu. Nota em trânsito, pendente na SEFAZ ou cancelada não recebe sinal: não
 * há o que o produtor faça com ela agora.
 *
 * <p><strong>{@link CodigoSinalNota#PRODUTO_PARECE_SERVICO}:</strong> descrição do item com um
 * termo de serviço ({@code sinais-nota/termos-servico.txt}, palavra ou expressão inteira, sem
 * acento nem caixa, depois de tirar as expressões de {@code sinais-nota/excecoes-servico.txt},
 * como "para plantio") <em>e</em> CFOP genérico ({@code 5949}/{@code 6949}, "outra saída não
 * especificada") ou NCM ausente ou fora da referência. Este último só alcança nota antiga, de antes
 * de o código sair da referência (#282): a API e a emissão já barram NCM desconhecido. As listas
 * passam pela revisão do avaliador externo (#275) e do contador (#284, perguntas 13 e 14).
 *
 * <p><strong>{@link CodigoSinalNota#POSSIVEL_DUPLICIDADE}:</strong> outra nota própria do mesmo
 * produtor, não cancelada nem rejeitada, com o mesmo destinatário, o mesmo valor total, os mesmos
 * itens (NCM e quantidade, em qualquer ordem) e emitida no mesmo dia (Brasília). A frase cita o
 * número e a série da outra nota. A nota consultada rejeitada também fica de fora: a regra é
 * simétrica (rejeitada nunca é uma das duas).
 *
 * <p><strong>{@link CodigoSinalNota#VALOR_UNITARIO_FORA_PADRAO}:</strong> item de venda (CFOP
 * {@code x1xx}; remessa não é venda) com valor unitário mais de {@value #FATOR_FORA_PADRAO} vezes
 * acima ou abaixo da mediana do mesmo NCM e da mesma unidade (sem caixa nem espaços) nas vendas
 * autorizadas do produtor nos 12 meses até a emissão da nota consultada, com no mínimo
 * {@value #MINIMO_NOTAS_REFERENCIA} notas de referência (lidos no máximo
 * {@value #MAXIMO_VALORES_REFERENCIA} itens, os mais recentes). A mediana é das notas (cada nota
 * vale pela mediana dos itens dela), para uma nota de muitas linhas não pesar mais que as outras.
 * Canceladas e rejeitadas ficam fora. Uma consulta por par (NCM, unidade), não por item.
 *
 * <p><strong>{@link CodigoSinalNota#ICMS_A_DEFINIR}:</strong> item de nota ainda sem XML cujo
 * grupo ICMS barraria a emissão ({@link TributacaoIcms#problema}: sem CST fora da venda interna de
 * produção própria a contribuinte, CST fora da lista, exportação sem o 41, emitente do Simples).
 * O sinal avisa antes, para o produtor combinar o CST com o contador.
 *
 * <p><strong>{@link CodigoSinalNota#ICMS_PADRAO_A_CONFIRMAR}:</strong> o item emite com o CST 51
 * do padrão, mas o par (UF do emitente; NCM) não está na lista que o contador confirmou
 * ({@code icms/diferimento-confirmado.csv}). Não bloqueia; só antes do XML.
 *
 * <p><strong>{@link CodigoSinalNota#CCLASSTRIB_NAO_ADMITIDO}:</strong> item de nota ainda sem XML
 * com cClassTrib informado que o NCM não admite (a regra do #375, que barra o {@code gerar-xml}
 * com 422). O rascunho mostra imposto zero com esse código; o aviso chega no create/update da nota
 * ({@code NotaFiscalResponse.avisos}, #389) e na Ceres.
 *
 * <p>Nenhuma regra compara com nota de outro produtor: toda consulta filtra pelo produtor da
 * sessão.
 */
@Service
public class SinaisNotaConferirService {

  /** CFOPs de "outra saída não especificada", que aceitam qualquer coisa. */
  static final Set<String> CFOPS_GENERICOS = Set.of("5949", "6949");

  /** Situações sem sinal: em trânsito, aguardando a SEFAZ ou já cancelada. */
  static final Set<StatusNotaFiscal> SEM_SINAL = EnumSet.of(StatusNotaFiscal.ENVIANDO,
      StatusNotaFiscal.PENDENTE_SEFAZ, StatusNotaFiscal.CANCELANDO, StatusNotaFiscal.CANCELADA);

  /**
   * Situações em que o CST do ICMS ainda pode ser combinado: antes de gerar o XML (com o XML
   * gerado, a nota não é editável e o aviso "antes de emitir" não teria o que fazer).
   */
  static final Set<StatusNotaFiscal> ANTES_DO_XML = EnumSet.of(StatusNotaFiscal.RASCUNHO,
      StatusNotaFiscal.COM_INCONSISTENCIAS, StatusNotaFiscal.DEVOLVIDA,
      StatusNotaFiscal.AGUARDANDO_APROVACAO, StatusNotaFiscal.APROVADA);

  /** Situações que não entram na duplicidade, nem como a consultada nem como a outra. */
  static final Set<StatusNotaFiscal> FORA_DA_DUPLICIDADE = EnumSet.of(StatusNotaFiscal.CANCELADA,
      StatusNotaFiscal.CANCELANDO, StatusNotaFiscal.REJEITADA);

  /** Situações que contam como a outra nota (lista positiva, para o índice da 0019). */
  static final Set<StatusNotaFiscal> NA_DUPLICIDADE = EnumSet.complementOf(
      EnumSet.copyOf(FORA_DA_DUPLICIDADE));

  static final int FATOR_FORA_PADRAO = 3;
  static final int MINIMO_NOTAS_REFERENCIA = 5;
  static final int MAXIMO_VALORES_REFERENCIA = 200;
  static final int MESES_REFERENCIA = 12;

  private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
  private static final Pattern NAO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

  private final NotaFiscalRepository notaFiscalRepository;
  private final NcmReferenciaRepository ncmReferenciaRepository;
  private final ClassificacaoTributariaService classificacaoService;
  private final Clock clock;
  private final Pattern termosServico;
  private final List<String> excecoes;

  /** Constrói o serviço com as listas versionadas em {@code sinais-nota/}. */
  @Autowired
  public SinaisNotaConferirService(NotaFiscalRepository notaFiscalRepository,
      NcmReferenciaRepository ncmReferenciaRepository,
      ClassificacaoTributariaService classificacaoService, Clock clock) {
    this(notaFiscalRepository, ncmReferenciaRepository, classificacaoService, clock,
        lista("sinais-nota/termos-servico.txt"), lista("sinais-nota/excecoes-servico.txt"));
  }

  SinaisNotaConferirService(NotaFiscalRepository notaFiscalRepository,
      NcmReferenciaRepository ncmReferenciaRepository,
      ClassificacaoTributariaService classificacaoService, Clock clock, List<String> termos,
      List<String> excecoes) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.ncmReferenciaRepository = ncmReferenciaRepository;
    this.classificacaoService = classificacaoService;
    this.clock = clock;
    List<String> normalizados = termos.stream().map(SinaisNotaConferirService::texto)
        .filter(t -> !t.isEmpty()).distinct().toList();
    if (normalizados.isEmpty()) {
      // Lista vazia viraria \b()\b, que casa qualquer descricao: todo item 5949 seria "servico".
      throw new IllegalStateException("Lista de termos de servico vazia (sinais-nota)");
    }
    this.termosServico = Pattern.compile("\\b(" + normalizados.stream().map(Pattern::quote)
        .collect(Collectors.joining("|")) + ")\\b");
    this.excecoes = excecoes.stream().map(SinaisNotaConferirService::texto)
        .filter(t -> !t.isEmpty()).toList();
  }

  /**
   * Sinais da nota, do primeiro item ao último.
   *
   * @param produtorId produtor da sessão
   * @param nota       nota já lida pelo chamador (origem, situação e emissão)
   * @return sinais; vazia sem sinal, para nota de compra, sem sinal pela situação ou de outro
   *     produtor
   */
  @Transactional(readOnly = true)
  public List<SinalNota> sinais(UUID produtorId, NotaParaSinal nota) {
    if (nota.origem() != OrigemNotaFiscal.PROPRIA || SEM_SINAL.contains(nota.status())) {
      return List.of();
    }
    Momento momento = momento(nota);
    OffsetDateTime prazo = momento == Momento.AUTORIZADA_NO_PRAZO ? limite(nota) : null;
    List<ItemParaSinal> itens = notaFiscalRepository.findItensParaSinais(nota.id(), produtorId);
    if (itens.isEmpty()) {
      // Sem itens: nota de outro produtor (a leitura filtra pelo dono) ou ainda vazia.
      return List.of();
    }
    List<SinalNota> sinais = new ArrayList<>();
    Map<String, Boolean> cClassTribJaConferido = new HashMap<>();
    for (ItemParaSinal item : itens) {
      motivoServico(item).ifPresent(motivo -> sinais.add(new SinalNota(
          CodigoSinalNota.PRODUTO_PARECE_SERVICO, motivo, momento, item.numeroItem(),
          item.descricao(), item.cfop(), prazo)));
      if (ANTES_DO_XML.contains(nota.status())) {
        if (cClassTribNaoAdmitido(item.cClassTrib(), item.ncm(), nota.dataEmissao(),
            cClassTribJaConferido)) {
          sinais.add(new SinalNota(CodigoSinalNota.CCLASSTRIB_NAO_ADMITIDO,
              Motivo.CODIGO_NAO_VALE_PARA_O_NCM, momento, item.numeroItem(), item.descricao(),
              item.cfop(), null));
        }
        if (icmsADefinir(item)) {
          sinais.add(new SinalNota(CodigoSinalNota.ICMS_A_DEFINIR, Motivo.SEM_CST_ICMS, momento,
              item.numeroItem(), item.descricao(), item.cfop(), null));
        } else if (icmsPadraoAConfirmar(item)) {
          sinais.add(new SinalNota(CodigoSinalNota.ICMS_PADRAO_A_CONFIRMAR,
              Motivo.PADRAO_NAO_CONFIRMADO, momento, item.numeroItem(), item.descricao(),
              item.cfop(), null));
        }
      }
    }
    sinais.addAll(valoresForaDoPadrao(produtorId, nota, itens, momento, prazo));
    // Do primeiro item ao ultimo; o sinal da nota inteira (duplicidade) por ultimo.
    sinais.sort(Comparator.comparing(SinalNota::numeroItem,
        Comparator.nullsLast(Comparator.naturalOrder())));
    duplicidade(produtorId, nota, itens, momento, prazo).ifPresent(sinais::add);
    return List.copyOf(sinais);
  }

  /**
   * Avisos de uma nota própria recém-gravada, sem o resto da validação fiscal (issue #389): só o
   * cClassTrib informado que o NCM do item não admite, a mesma regra que barra o {@code gerar-xml}
   * (422). Sem as demais regras (duplicidade, valor fora do padrão, ICMS), só o cClassTrib
   * informado, e uma conferência por par (cClassTrib, NCM) da nota; roda no create/update do
   * rascunho. Não bloqueia; a nota importada nunca recebe aviso.
   *
   * @param nota nota já com os itens, ainda gerenciada ou completa
   * @return um sinal por item com código não admitido, na ordem dos itens
   */
  @Transactional(readOnly = true)
  public List<SinalNota> avisosDoRascunho(NotaFiscal nota) {
    if (nota.getOrigem() == OrigemNotaFiscal.IMPORTADA) {
      return List.of();
    }
    List<SinalNota> sinais = new ArrayList<>();
    Map<String, Boolean> jaConferido = new HashMap<>();
    for (NotaFiscalItem item : nota.getItens()) {
      if (cClassTribNaoAdmitido(item.getCClassTrib(), item.getNcm(), nota.getDataEmissao(),
          jaConferido)) {
        sinais.add(new SinalNota(CodigoSinalNota.CCLASSTRIB_NAO_ADMITIDO,
            Motivo.CODIGO_NAO_VALE_PARA_O_NCM, Momento.ANTES_DE_EMITIR, item.getNumeroItem(),
            item.getDescricao(), item.getCfop(), null));
      }
    }
    return List.copyOf(sinais);
  }

  /** cClassTrib informado que o NCM não alcança: a regra do {@code FiscalRulesValidator}. */
  private boolean cClassTribNaoAdmitido(String cClassTrib, String ncm, OffsetDateTime emissao,
      Map<String, Boolean> jaConferido) {
    if (cClassTrib == null || cClassTrib.isBlank() || ncm == null || ncm.isBlank()) {
      return false;
    }
    // A data e a mesma para todos os itens da nota: a chave e o par (cClassTrib, NCM).
    return jaConferido.computeIfAbsent(cClassTrib.strip() + "|" + ncm.strip(), chave -> {
      LocalDate data = (emissao == null ? OffsetDateTime.now(clock) : emissao)
          .atZoneSameInstant(FUSO).toLocalDate();
      return classificacaoService.codigoNaoAdmitido(cClassTrib.strip(), ncm.strip(), data)
          .isPresent();
    });
  }

  /** Outra nota do mesmo produtor com o mesmo destinatário, valor, itens e dia. */
  private Optional<SinalNota> duplicidade(UUID produtorId, NotaParaSinal nota,
      List<ItemParaSinal> itens, Momento momento, OffsetDateTime prazo) {
    if (nota.dataEmissao() == null || nota.destinatarioCpfCnpj() == null
        || nota.valorTotal() == null || FORA_DA_DUPLICIDADE.contains(nota.status())) {
      return Optional.empty();
    }
    LocalDate dia = nota.dataEmissao().atZoneSameInstant(FUSO).toLocalDate();
    List<OutraNota> candidatas = notaFiscalRepository.findPossiveisDuplicadas(
        produtorId, nota.id(), nota.destinatarioCpfCnpj(), nota.valorTotal(),
        dia.atStartOfDay(FUSO).toOffsetDateTime(),
        dia.plusDays(1).atStartOfDay(FUSO).toOffsetDateTime(), NA_DUPLICIDADE);
    Map<String, Long> estesItens = assinatura(itens);
    for (OutraNota outra : candidatas) {
      if (estesItens.equals(
          assinatura(notaFiscalRepository.findItensParaSinais(outra.id(), produtorId)))) {
        return Optional.of(new SinalNota(CodigoSinalNota.POSSIVEL_DUPLICIDADE, Motivo.MESMA_VENDA,
            momento, null, null, null, prazo, null, null, null, outra.numero(), outra.serie()));
      }
    }
    return Optional.empty();
  }

  /** Itens como multiconjunto de (NCM, quantidade sem zeros à direita). */
  private static Map<String, Long> assinatura(List<ItemParaSinal> itens) {
    return itens.stream().collect(Collectors.groupingBy(
        i -> (i.ncm() == null ? "" : i.ncm().strip()) + "|"
            + (i.quantidade() == null ? "" : i.quantidade().stripTrailingZeros().toPlainString()),
        TreeMap::new, Collectors.counting()));
  }

  /**
   * Itens de venda com valor unitário mais de 3 vezes fora da mediana do mesmo NCM e unidade, com
   * 5+ notas de referência na janela de 12 meses até a emissão da nota.
   */
  private List<SinalNota> valoresForaDoPadrao(UUID produtorId, NotaParaSinal nota,
      List<ItemParaSinal> itens, Momento momento, OffsetDateTime prazo) {
    OffsetDateTime ate = nota.dataEmissao() == null ? OffsetDateTime.now(clock)
        : nota.dataEmissao();
    OffsetDateTime desde = ate.minusMonths(MESES_REFERENCIA);
    Map<String, Optional<BigDecimal>> medianas = new HashMap<>();
    List<SinalNota> sinais = new ArrayList<>();
    for (ItemParaSinal item : itens) {
      if (!deVenda(item) || item.ncm() == null || item.unidade() == null
          || item.valorUnitario() == null || item.valorUnitario().signum() <= 0) {
        continue;
      }
      String ncm = item.ncm().strip();
      String unidade = unidade(item.unidade());
      Optional<BigDecimal> mediana = medianas.computeIfAbsent(ncm + "|" + unidade,
          k -> medianaDeReferencia(produtorId, nota.id(), ncm, unidade, desde, ate));
      if (mediana.isEmpty()) {
        continue;
      }
      BigDecimal fator = BigDecimal.valueOf(FATOR_FORA_PADRAO);
      Motivo motivo = null;
      if (item.valorUnitario().compareTo(mediana.get().multiply(fator)) > 0) {
        motivo = Motivo.ACIMA_DO_PADRAO;
      } else if (item.valorUnitario().multiply(fator).compareTo(mediana.get()) < 0) {
        motivo = Motivo.ABAIXO_DO_PADRAO;
      }
      if (motivo != null) {
        sinais.add(new SinalNota(CodigoSinalNota.VALOR_UNITARIO_FORA_PADRAO, motivo, momento,
            item.numeroItem(), item.descricao(), item.cfop(), prazo, item.unidade(),
            item.valorUnitario(), mediana.get(), null, null));
      }
    }
    return sinais;
  }

  /** Mediana das notas de referência (cada nota pela mediana dos itens dela); vazia com < 5. */
  private Optional<BigDecimal> medianaDeReferencia(UUID produtorId, UUID notaId, String ncm,
      String unidade, OffsetDateTime desde, OffsetDateTime ate) {
    Map<UUID, List<BigDecimal>> porNota = new LinkedHashMap<>();
    notaFiscalRepository.findValoresUnitariosDeReferencia(produtorId, notaId, ncm, unidade,
            desde, ate, PageRequest.of(0, MAXIMO_VALORES_REFERENCIA))
        .forEach(r -> porNota.computeIfAbsent(r.notaId(), k -> new ArrayList<>())
            .add(r.valorUnitario()));
    if (porNota.size() < MINIMO_NOTAS_REFERENCIA) {
      return Optional.empty();
    }
    BigDecimal mediana = mediana(porNota.values().stream()
        .map(valores -> mediana(valores.stream().sorted().toList()))
        .sorted().toList());
    return mediana.signum() > 0 ? Optional.of(mediana) : Optional.empty();
  }

  /**
   * O grupo ICMS do item barraria a emissão: a mesma regra ({@link TributacaoIcms#problema}) do
   * 422 do {@code FiscalRulesValidator}, mostrada antes, na revisão.
   */
  static boolean icmsADefinir(ItemParaSinal item) {
    return TributacaoIcms.problema(item.cstIcms(), item.cfop(), new TributacaoIcms.DadosOperacao(
        item.tipoEmitente(), item.regimeEmitente(), item.ufEmitente(), item.ufDestinatario(),
        item.ieDestinatario())).isPresent();
  }

  /** CST 51 pelo padrão fora dos pares (UF; NCM) confirmados pelo contador: não bloqueia. */
  static boolean icmsPadraoAConfirmar(ItemParaSinal item) {
    return TributacaoIcms.padraoAConfirmar(item.cstIcms(), item.cfop(), item.ncm(),
        new TributacaoIcms.DadosOperacao(item.tipoEmitente(), item.regimeEmitente(),
            item.ufEmitente(), item.ufDestinatario(), item.ieDestinatario()));
  }

  /** CFOP de venda: {@code x1xx} (5101, 6102, 7101...). Remessa ({@code x9xx}) não é venda. */
  static boolean deVenda(ItemParaSinal item) {
    String cfop = item.cfop() == null ? "" : item.cfop().strip();
    return cfop.length() == 4 && cfop.charAt(1) == '1';
  }

  /** Unidade comparável: maiúsculas, sem espaços nas pontas ("kg " = "KG"). */
  static String unidade(String unidade) {
    return unidade.strip().toUpperCase(Locale.ROOT);
  }

  /** Mediana de valores já ordenados (média dos dois do meio com quantidade par). */
  static BigDecimal mediana(List<BigDecimal> ordenados) {
    int n = ordenados.size();
    if (n % 2 == 1) {
      return ordenados.get(n / 2);
    }
    return ordenados.get(n / 2 - 1).add(ordenados.get(n / 2))
        .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
  }

  /**
   * Por que o item parece serviço; vazio se não parece. O termo é conferido primeiro (sem
   * consulta) e a referência de NCM só quando o CFOP não é genérico.
   */
  Optional<Motivo> motivoServico(ItemParaSinal item) {
    if (!temTermoDeServico(item.descricao())) {
      return Optional.empty();
    }
    if (item.cfop() != null && CFOPS_GENERICOS.contains(item.cfop().strip())) {
      return Optional.of(Motivo.CFOP_GENERICO);
    }
    boolean foraDaReferencia = item.ncm() == null || item.ncm().isBlank()
        || !ncmReferenciaRepository.existsByCodigo(item.ncm().strip());
    return foraDaReferencia ? Optional.of(Motivo.NCM_FORA_DA_REFERENCIA) : Optional.empty();
  }

  /** Termo de serviço na descrição, depois de tirar as exceções ("para plantio"). */
  boolean temTermoDeServico(String descricao) {
    String normalizada = " " + texto(descricao) + " ";
    for (String excecao : excecoes) {
      normalizada = normalizada.replace(" " + excecao + " ", " ");
    }
    return termosServico.matcher(normalizada).find();
  }

  private Momento momento(NotaParaSinal nota) {
    if (nota.status() != StatusNotaFiscal.AUTORIZADA) {
      return Momento.ANTES_DE_EMITIR;
    }
    OffsetDateTime limite = limite(nota);
    return limite != null && !OffsetDateTime.now(clock).isAfter(limite)
        ? Momento.AUTORIZADA_NO_PRAZO : Momento.AUTORIZADA_FORA_DO_PRAZO;
  }

  /** O mesmo limite do {@code SefazService.cancelar}: a emissão mais 30 dias, com a hora. */
  private static OffsetDateTime limite(NotaParaSinal nota) {
    return nota.dataEmissao() == null ? null
        : nota.dataEmissao().plusDays(SefazService.PRAZO_CANCELAMENTO_DIAS);
  }

  /** Normaliza descrição e listas do mesmo jeito: sem acento, minúsculas, pontuação = espaço. */
  private static String texto(String valor) {
    return NAO_ALFANUMERICO.matcher(NormalizacaoBusca.normalizar(valor)).replaceAll(" ").strip();
  }

  private static List<String> lista(String recurso) {
    try (InputStream in = new ClassPathResource(recurso).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
          .map(String::strip)
          .filter(l -> !l.isEmpty() && !l.startsWith("#"))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Lista de sinais ausente: " + recurso, e);
    }
  }

  /**
   * O que os sinais precisam da nota, já lido por quem chama (a consulta da Ceres tem tudo isso
   * na projeção da #256).
   *
   * @param id                  nota
   * @param origem              própria ou importada
   * @param status              situação
   * @param dataEmissao         emissão
   * @param destinatarioCpfCnpj documento do destinatário (só compara, nunca sai do service)
   * @param valorTotal          valor total da nota
   */
  public record NotaParaSinal(UUID id, OrigemNotaFiscal origem, StatusNotaFiscal status,
      OffsetDateTime dataEmissao, String destinatarioCpfCnpj, BigDecimal valorTotal) {}
}
