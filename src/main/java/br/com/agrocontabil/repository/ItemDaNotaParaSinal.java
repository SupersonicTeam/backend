package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@link ItemParaSinal} com a nota de onde veio, para ler os itens de várias notas numa consulta só
 * (issue #579, avaliação dos sinais em lote). Os campos são os de {@link ItemParaSinal}.
 *
 * @param notaId         nota do item
 * @param numeroItem     número do item
 * @param descricao      descrição digitada
 * @param cfop           CFOP
 * @param ncm            NCM
 * @param unidade        unidade comercial
 * @param quantidade     quantidade
 * @param valorUnitario  valor unitário
 * @param cstIcms        CST do ICMS informado (nulo = não informado)
 * @param tipoEmitente   tipo do emitente (CRT, regra do ICMS)
 * @param regimeEmitente regime gravado do emitente (CRT, regra do ICMS)
 * @param ufEmitente     UF do emitente (regra do CST padrão do ICMS)
 * @param ufDestinatario UF do destinatário
 * @param ieDestinatario IE do destinatário (só se há ou não; nunca sai do service)
 * @param cClassTrib     cClassTrib informado no item (nulo = o do NCM)
 */
public record ItemDaNotaParaSinal(UUID notaId, Integer numeroItem, String descricao, String cfop,
    String ncm, String unidade, BigDecimal quantidade, BigDecimal valorUnitario, String cstIcms,
    TipoProdutor tipoEmitente, RegimeTributario regimeEmitente, String ufEmitente,
    String ufDestinatario, String ieDestinatario, String cClassTrib) {

  /** O item sem a nota, como as regras o leem. */
  public ItemParaSinal item() {
    return new ItemParaSinal(numeroItem, descricao, cfop, ncm, unidade, quantidade, valorUnitario,
        cstIcms, tipoEmitente, regimeEmitente, ufEmitente, ufDestinatario, ieDestinatario,
        cClassTrib);
  }
}
