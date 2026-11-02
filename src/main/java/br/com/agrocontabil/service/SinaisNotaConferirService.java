package br.com.agrocontabil.service;

import br.com.agrocontabil.common.NormalizacaoBusca;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ItemParaSinal;
import br.com.agrocontabil.repository.NotaParaDuplicidade;
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
import java.util.EnumSet;
import java.util.List;
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
 * número da outra nota.
 *
 * <p><strong>{@link CodigoSinalNota#VALOR_UNITARIO_FORA_PADRAO}:</strong> valor unitário do item
 * mais de {@value #FATOR_FORA_PADRAO} vezes acima ou abaixo da mediana do mesmo NCM e da mesma
 * unidade nas vendas autorizadas do produtor nos últimos 12 meses, com no mínimo
 * {@value #MINIMO_NOTAS_REFERENCIA} notas de referência (lidos no máximo
 * {@value #MAXIMO_VALORES_REFERENCIA} itens, os mais recentes). Canceladas e rejeitadas ficam fora
 * da mediana.
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

  /** Situações que não contam como a "outra" nota de uma duplicidade. */
  static final Set<StatusNotaFiscal> FORA_DA_DUPLICIDADE = EnumSet.of(StatusNotaFiscal.CANCELADA,
      StatusNotaFiscal.CANCELANDO, StatusNotaFiscal.REJEITADA);

  static final int FATOR_FORA_PADRAO = 3;
  static final int MINIMO_NOTAS_REFERENCIA = 5;
  static final int MAXIMO_VALORES_REFERENCIA = 200;
  static final int MESES_REFERENCIA = 12;

  private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
  private static final Pattern NAO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

  private final NotaFiscalRepository notaFiscalRepository;
  private final NcmReferenciaRepository ncmReferenciaRepository;
  private final Clock clock;
  private final Pattern termosServico;
  private final List<String> excecoes;

  /** Constrói o serviço com as listas versionadas em {@code sinais-nota/}. */
  @Autowired
  public SinaisNotaConferirService(NotaFiscalRepository notaFiscalRepository,
      NcmReferenciaRepository ncmReferenciaRepository, Clock clock) {
    this(notaFiscalRepository, ncmReferenciaRepository, clock,
        lista("sinais-nota/termos-servico.txt"), lista("sinais-nota/excecoes-servico.txt"));
  }

  SinaisNotaConferirService(NotaFiscalRepository notaFiscalRepository,
      NcmReferenciaRepository ncmReferenciaRepository, Clock clock, List<String> termos,
      List<String> excecoes) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.ncmReferenciaRepository = ncmReferenciaRepository;
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
    for (ItemParaSinal item : itens) {
      motivoServico(item).ifPresent(motivo -> sinais.add(new SinalNota(
          CodigoSinalNota.PRODUTO_PARECE_SERVICO, motivo, momento, item.numeroItem(),
          item.descricao(), item.cfop(), prazo)));
    }
    for (ItemParaSinal item : itens) {
      valorForaDoPadrao(produtorId, nota, item, momento, prazo).ifPresent(sinais::add);
    }
    duplicidade(produtorId, nota, itens, momento, prazo).ifPresent(sinais::add);
    return List.copyOf(sinais);
  }

  /** Outra nota do mesmo produtor com o mesmo destinatário, valor, itens e dia. */
  private Optional<SinalNota> duplicidade(UUID produtorId, NotaParaSinal nota,
      List<ItemParaSinal> itens, Momento momento, OffsetDateTime prazo) {
    if (nota.dataEmissao() == null) {
      return Optional.empty();
    }
    Optional<NotaParaDuplicidade> esta =
        notaFiscalRepository.findParaDuplicidade(nota.id(), produtorId);
    if (esta.isEmpty() || esta.get().destinatarioCpfCnpj() == null
        || esta.get().valorTotal() == null) {
      return Optional.empty();
    }
    LocalDate dia = nota.dataEmissao().atZoneSameInstant(FUSO).toLocalDate();
    List<NotaParaDuplicidade> candidatas = notaFiscalRepository.findPossiveisDuplicadas(
        produtorId, nota.id(), esta.get().destinatarioCpfCnpj(), esta.get().valorTotal(),
        dia.atStartOfDay(FUSO).toOffsetDateTime(),
        dia.plusDays(1).atStartOfDay(FUSO).toOffsetDateTime(), FORA_DA_DUPLICIDADE);
    Map<String, Long> estesItens = assinatura(itens);
    for (NotaParaDuplicidade outra : candidatas) {
      if (estesItens.equals(
          assinatura(notaFiscalRepository.findItensParaSinais(outra.id(), produtorId)))) {
        return Optional.of(new SinalNota(CodigoSinalNota.POSSIVEL_DUPLICIDADE, Motivo.MESMA_VENDA,
            momento, null, null, null, prazo, null, null, null, outra.numero()));
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

  /** Valor unitário mais de 3 vezes fora da mediana do mesmo NCM e unidade, com 5+ notas. */
  private Optional<SinalNota> valorForaDoPadrao(UUID produtorId, NotaParaSinal nota,
      ItemParaSinal item, Momento momento, OffsetDateTime prazo) {
    if (item.ncm() == null || item.unidade() == null || item.valorUnitario() == null
        || item.valorUnitario().signum() <= 0) {
      return Optional.empty();
    }
    OffsetDateTime desde = OffsetDateTime.now(clock).minusMonths(MESES_REFERENCIA);
    List<ValorUnitarioReferencia> referencia =
        notaFiscalRepository.findValoresUnitariosDeReferencia(produtorId, nota.id(),
            item.ncm(), item.unidade(), desde, PageRequest.of(0, MAXIMO_VALORES_REFERENCIA));
    long notas = referencia.stream().map(ValorUnitarioReferencia::notaId).distinct().count();
    if (notas < MINIMO_NOTAS_REFERENCIA) {
      return Optional.empty();
    }
    BigDecimal mediana = mediana(referencia.stream()
        .map(ValorUnitarioReferencia::valorUnitario).sorted().toList());
    if (mediana.signum() <= 0) {
      return Optional.empty();
    }
    BigDecimal fator = BigDecimal.valueOf(FATOR_FORA_PADRAO);
    Motivo motivo = null;
    if (item.valorUnitario().compareTo(mediana.multiply(fator)) > 0) {
      motivo = Motivo.ACIMA_DO_PADRAO;
    } else if (item.valorUnitario().multiply(fator).compareTo(mediana) < 0) {
      motivo = Motivo.ABAIXO_DO_PADRAO;
    }
    return Optional.ofNullable(motivo).map(m -> new SinalNota(
        CodigoSinalNota.VALOR_UNITARIO_FORA_PADRAO, m, momento, item.numeroItem(),
        item.descricao(), item.cfop(), prazo, item.unidade(), item.valorUnitario(), mediana,
        null));
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
   * @param id          nota
   * @param origem      própria ou importada
   * @param status      situação
   * @param dataEmissao emissão
   */
  public record NotaParaSinal(UUID id, OrigemNotaFiscal origem, StatusNotaFiscal status,
      OffsetDateTime dataEmissao) {}
}
