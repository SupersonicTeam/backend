package br.com.agrocontabil.assistente.adaptador;

import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.service.TratamentoProdutoService;
import br.com.agrocontabil.service.TratamentoProdutoService.ConsultaTratamentoProduto;
import br.com.agrocontabil.service.TratamentoTributarioNcm.Origem;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementa a {@link TratamentoProdutoPorta} sobre o {@link TratamentoProdutoService} (issue
 * #283). Só traduz; a consulta roda numa transação curta com o timeout por função da Ceres, como
 * no {@code DadosProdutorAdaptador}.
 */
@Component
public class TratamentoProdutoAdaptador implements TratamentoProdutoPorta {

  private final TratamentoProdutoService service;
  private final TransactionTemplate transacao;

  /** Constrói o adaptador. */
  public TratamentoProdutoAdaptador(TratamentoProdutoService service,
      PlatformTransactionManager transactionManager, AssistenteProperties propriedades) {
    this.service = service;
    this.transacao = new TransactionTemplate(transactionManager);
    this.transacao.setReadOnly(true);
    this.transacao.setTimeout((int) propriedades.getTimeoutFuncao().toSeconds());
  }

  @Override
  public TratamentoConsultado consultar(String termo, LocalDate data) {
    ConsultaTratamentoProduto c = transacao.execute(status -> service.consultar(termo, data));
    return new TratamentoConsultado(c.termo(),
        c.produtos().stream()
            .map(p -> new Produto(p.ncm(), p.descricao(), p.quantidadeNcms(), p.cClassTrib(),
                p.percentualReducao(), p.origem() == Origem.IN_NATURA, p.anexo(), p.artigo(),
                p.condicao(), p.ncmConhecido()))
            .toList(),
        c.versaoTabela());
  }
}
