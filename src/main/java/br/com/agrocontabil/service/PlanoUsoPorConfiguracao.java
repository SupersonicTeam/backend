package br.com.agrocontabil.service;

import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.TipoTitularCota;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Plano padrão por tipo de titular, lido de {@code app.assistente.cota.*} (issue #166, etapa A).
 * Todo produtor tem o mesmo plano; todo escritório também.
 */
@Component
public class PlanoUsoPorConfiguracao implements PlanoUsoProvider {

  private final AssistenteProperties.Cota cota;

  public PlanoUsoPorConfiguracao(AssistenteProperties propriedades) {
    this.cota = propriedades.getCota();
  }

  @Override
  public PlanoUso planoDe(TipoTitularCota tipo, UUID titularId) {
    long creditos = tipo == TipoTitularCota.ESCRITORIO
        ? cota.getCreditosPorSemanaEscritorio()
        : cota.getCreditosPorSemanaProdutor();
    return new PlanoUso(cota.getPlano(), creditos);
  }
}
