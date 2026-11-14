package br.com.agrocontabil.assistente.adaptador;

import br.com.agrocontabil.assistente.porta.AuditoriaPorta;
import br.com.agrocontabil.model.AssistenteInteracao;
import br.com.agrocontabil.service.AssistenteInteracaoService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implementa a {@link AuditoriaPorta} sobre o {@link AssistenteInteracaoService} (issues #141 e
 * #144): converte o registro na entidade e delega a gravação, em transação curta, ao service.
 */
@Component
public class AuditoriaAdaptador implements AuditoriaPorta {

  private final AssistenteInteracaoService interacaoService;

  public AuditoriaAdaptador(AssistenteInteracaoService interacaoService) {
    this.interacaoService = interacaoService;
  }

  @Override
  public UUID registrar(RegistroInteracao r) {
    return interacaoService.registrar(AssistenteInteracao.builder()
        .produtorId(r.produtorId())
        .rota(r.rota())
        .desfecho(r.desfecho())
        .desfechoDados(r.desfechoDados())
        .desfechoLegislacao(r.desfechoLegislacao())
        .perguntaLeiReescrita(r.perguntaLeiReescrita())
        .camadaClassificacao(r.camadaClassificacao())
        .fidelidadeLei(r.fidelidadeLei())
        .latenciaJuizMs(r.latenciaJuizMs())
        .tokensEntradaJuiz(r.tokensEntradaJuiz())
        .tokensSaidaJuiz(r.tokensSaidaJuiz())
        .funcoes(r.funcoesJson())
        .chamadasFuncao(r.chamadasFuncao())
        .trechosCitados(r.trechosCitadosJson())
        .modelo(r.modelo())
        .latenciaMs(r.latenciaMs())
        .tokensEntrada(r.tokensEntrada())
        .tokensSaida(r.tokensSaida())
        .tokensEstimados(r.tokensEstimados())
        .tamanhoPergunta(r.tamanhoPergunta())
        .criadoEm(r.criadoEm())
        .build());
  }

  @Override
  public void registrarExplicacao(RegistroExplicacao r) {
    interacaoService.registrarExplicacao(r.interacaoId(), r.desfecho(), r.latenciaMs(),
        r.tokensEntrada(), r.tokensSaida());
  }
}
