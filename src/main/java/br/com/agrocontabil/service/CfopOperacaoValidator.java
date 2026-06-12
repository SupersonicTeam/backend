package br.com.agrocontabil.service;

import br.com.agrocontabil.exception.CfopIncompativelException;
import br.com.agrocontabil.model.AmbitoOperacao;
import br.com.agrocontabil.model.CfopProdutorRural;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoOperacao;
import br.com.agrocontabil.repository.CfopProdutorRuralRepository;
import org.springframework.stereotype.Component;

/**
 * Valida a compatibilidade do CFOP de cada item com o tipo de operação da nota.
 *
 * <p>O modelo atual emite sempre <strong>saída</strong> ({@code tpNF=1}); o âmbito é
 * derivado da UF do emitente (produtor) versus a UF do destinatário:
 * {@link AmbitoOperacao#INTERNA} se iguais, {@link AmbitoOperacao#INTERESTADUAL} se
 * diferentes. Para cada item, carrega os metadados do CFOP e exige:
 * <ul>
 *   <li>{@code tipoOperacao == SAIDA} (rejeita CFOP de entrada — 1xxx/2xxx — numa saída);</li>
 *   <li>{@code ambito} igual ao esperado, quando determinável.</li>
 * </ul>
 *
 * <p>Se a UF do emitente ou do destinatário estiver ausente (endereço opcional em
 * rascunho), o âmbito é indeterminável e a checagem de âmbito é ignorada (mantém-se a
 * de tipo de operação). A existência do CFOP na referência é responsabilidade de
 * {@code @ValidCfop} no request; aqui, CFOP ausente é ignorado.
 *
 * <p>Violações lançam {@link CfopIncompativelException} (HTTP 400) com mensagem descritiva.
 */
@Component
public class CfopOperacaoValidator {

  private final CfopProdutorRuralRepository repository;

  public CfopOperacaoValidator(CfopProdutorRuralRepository repository) {
    this.repository = repository;
  }

  /**
   * Valida todos os itens da nota quanto à compatibilidade CFOP × operação.
   *
   * @param nota nota fiscal com produtor, endereço do destinatário e itens
   * @throws CfopIncompativelException se algum item tiver CFOP incompatível
   */
  public void validar(NotaFiscal nota) {
    AmbitoOperacao ambitoEsperado = resolverAmbito(nota);

    for (NotaFiscalItem item : nota.getItens()) {
      CfopProdutorRural cfop = repository.findByCodigo(item.getCfop()).orElse(null);
      if (cfop == null) {
        continue; // existência tratada por @ValidCfop no request
      }

      if (cfop.getTipoOperacao() != TipoOperacao.SAIDA) {
        throw new CfopIncompativelException(
            "Item " + item.getNumeroItem() + ": CFOP " + item.getCfop()
                + " e de operacao de ENTRADA, incompativel com a saida da nota fiscal. "
                + "Use um CFOP de saida (5xxx interna / 6xxx interestadual).");
      }

      if (ambitoEsperado != null && cfop.getAmbito() != ambitoEsperado) {
        String sugestao = ambitoEsperado == AmbitoOperacao.INTERNA ? "5xxx" : "6xxx";
        throw new CfopIncompativelException(
            "Item " + item.getNumeroItem() + ": CFOP " + item.getCfop()
                + " (" + cfop.getAmbito() + ") incompativel com operacao " + ambitoEsperado
                + ". Use um CFOP " + sugestao + ".");
      }
    }
  }

  /**
   * Determina o âmbito esperado a partir das UFs do emitente e do destinatário.
   *
   * @return {@code INTERNA}/{@code INTERESTADUAL}, ou {@code null} se indeterminável
   */
  private AmbitoOperacao resolverAmbito(NotaFiscal nota) {
    Produtor produtor = nota.getProdutor();
    String ufEmit = produtor != null ? produtor.getEstado() : null;
    String ufDest = nota.getDestinatarioEndereco() != null
        ? nota.getDestinatarioEndereco().getUf() : null;

    if (ufEmit == null || ufEmit.isBlank() || ufDest == null || ufDest.isBlank()) {
      return null;
    }
    return ufEmit.equalsIgnoreCase(ufDest)
        ? AmbitoOperacao.INTERNA : AmbitoOperacao.INTERESTADUAL;
  }
}
