package br.com.agrocontabil.assistente.adaptador;

import br.com.agrocontabil.assistente.porta.CotaUsoPorta;
import br.com.agrocontabil.config.CaminhoCota;
import br.com.agrocontabil.model.TipoTitularCota;
import br.com.agrocontabil.service.CotaUsoAssistenteService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implementa a {@link CotaUsoPorta} sobre o {@link CotaUsoAssistenteService} (issue #166). Só
 * traduz: hoje o titular de toda pergunta é o produtor.
 */
@Component
public class CotaUsoAdaptador implements CotaUsoPorta {

  private final CotaUsoAssistenteService service;

  public CotaUsoAdaptador(CotaUsoAssistenteService service) {
    this.service = service;
  }

  @Override
  public ReservaCota reservar(UUID produtorId, String modelo) {
    CotaUsoAssistenteService.Reserva r =
        service.reservar(TipoTitularCota.PRODUTOR, produtorId, modelo);
    return new ReservaCota(r.titularId(), r.semana(), r.creditos(), r.ativa());
  }

  @Override
  public ReservaCota reservar(UUID produtorId, String modelo, CaminhoCota caminho) {
    CotaUsoAssistenteService.Reserva r =
        service.reservar(TipoTitularCota.PRODUTOR, produtorId, modelo, caminho);
    return new ReservaCota(r.titularId(), r.semana(), r.creditos(), r.ativa());
  }

  @Override
  public ReservaCota reservarLeitura(UUID produtorId, String modelo, long tokensLeitura) {
    CotaUsoAssistenteService.Reserva r = service.reservarLeitura(
        TipoTitularCota.PRODUTOR, produtorId, modelo, tokensLeitura);
    return new ReservaCota(r.titularId(), r.semana(), r.creditos(), r.ativa());
  }

  @Override
  public Optional<ReservaCota> reservarExplicacao(UUID produtorId, String modelo) {
    return service.reservarExplicacao(TipoTitularCota.PRODUTOR, produtorId, modelo)
        .map(r -> new ReservaCota(r.titularId(), r.semana(), r.creditos(), r.ativa()));
  }

  @Override
  public long acertar(ReservaCota reserva, long tokensEntrada, long tokensSaida, String modelo) {
    return service.acertar(paraService(reserva), tokensEntrada, tokensSaida, modelo);
  }

  @Override
  public long acertarExplicacao(ReservaCota reserva, long tokensEntrada, long tokensSaida,
      String modelo) {
    return service.acertarExplicacao(paraService(reserva), tokensEntrada, tokensSaida, modelo);
  }

  @Override
  public void estornar(ReservaCota reserva) {
    service.estornar(paraService(reserva));
  }

  @Override
  public UsoCota consultar(UUID produtorId) {
    CotaUsoAssistenteService.Uso u = service.consultar(TipoTitularCota.PRODUTOR, produtorId);
    return new UsoCota(u.plano(), u.habilitada(), u.creditosSemana(), u.consumidos(),
        u.restantes(), u.renovaEm(), u.percentualUsado());
  }

  private static CotaUsoAssistenteService.Reserva paraService(ReservaCota r) {
    return new CotaUsoAssistenteService.Reserva(
        TipoTitularCota.PRODUTOR, r.titularId(), r.semana(), r.creditos(), r.ativa());
  }
}
