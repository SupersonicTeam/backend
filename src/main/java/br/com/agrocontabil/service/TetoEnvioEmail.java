package br.com.agrocontabil.service;

import br.com.agrocontabil.config.EmailProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Teto de e-mails enviados por SMTP (issue #460, D9 e D10), abaixo da cota do provedor.
 *
 * <p>Sem teto, qualquer um esgota a cota do dia pelo "esqueci minha senha" ou por convites a
 * endereços falsos (o cadastro de contador é público), inclusive no dia da banca, e os bounces
 * levam à suspensão da conta. Ao estourar, o e-mail é descartado, o log registra só o tipo, e a
 * resposta ao cliente não muda (202).
 *
 * <p><strong>Janelas deslizantes</strong> de 1 hora e de 24 horas, não dia do calendário: assim o
 * teto fica abaixo do limite de qualquer provedor, conte ele o dia em UTC (Resend), em outro fuso
 * ou por 24 horas corridas. <strong>Em memória</strong>: a VPS tem uma réplica só, e um reinício
 * zera a contagem (aceito, D10). Com duas ou mais réplicas, a contagem vai para uma tabela.
 */
@Component
public class TetoEnvioEmail {

  private static final Logger log = LoggerFactory.getLogger(TetoEnvioEmail.class);

  static final Duration HORA = Duration.ofHours(1);
  static final Duration DIA = Duration.ofDays(1);

  /** Fração do teto que dispara o aviso no log. */
  private static final double AVISO = 0.8;

  /** Com o teto estourado, um aviso a cada tantos descartes. */
  static final int AVISO_A_CADA_DESCARTES = 100;

  private final int tetoPorHora;
  private final int tetoPorDia;
  private final Clock clock;
  private final Deque<Instant> envios = new ArrayDeque<>();
  private int descartadosSeguidos;

  /**
   * Cria o teto.
   *
   * @param propriedades {@code app.email.teto-por-hora} e {@code teto-por-dia}
   * @param clock        relógio do projeto
   */
  public TetoEnvioEmail(EmailProperties propriedades, Clock clock) {
    this.tetoPorHora = propriedades.tetoPorHora();
    this.tetoPorDia = propriedades.tetoPorDia();
    this.clock = clock;
  }

  /**
   * Reserva um envio. Chamado só quando o e-mail vai de fato ao provedor.
   *
   * @param tipo tipo do e-mail, só para o log
   * @return {@code true} se o e-mail pode sair; {@code false} se estourou um teto (já registrado)
   */
  public synchronized boolean reservar(TipoEmail tipo) {
    if (tetoPorHora == 0 && tetoPorDia == 0) {
      return true;
    }
    Instant agora = clock.instant();
    Duration janelaGuardada = tetoPorDia > 0 ? DIA : HORA;
    while (!envios.isEmpty() && !envios.peekFirst().isAfter(agora.minus(janelaGuardada))) {
      envios.pollFirst();
    }
    int naHora = contarDesde(agora.minus(HORA));
    int noDia = envios.size();
    if (tetoPorDia > 0 && noDia >= tetoPorDia) {
      return descartar("24 horas", tetoPorDia, tipo);
    }
    if (tetoPorHora > 0 && naHora >= tetoPorHora) {
      return descartar("1 hora", tetoPorHora, tipo);
    }
    descartadosSeguidos = 0;
    envios.addLast(agora);
    avisarSeCruzou("24 horas", noDia + 1, tetoPorDia);
    avisarSeCruzou("1 hora", naHora + 1, tetoPorHora);
    return true;
  }

  /**
   * Descarta e registra só o tipo. Sob abuso, um WARN por e-mail encheria o log: avisa o primeiro
   * descarte e depois um a cada {@link #AVISO_A_CADA_DESCARTES}, até um envio passar.
   */
  private boolean descartar(String janela, int teto, TipoEmail tipo) {
    descartadosSeguidos++;
    if (descartadosSeguidos == 1 || descartadosSeguidos % AVISO_A_CADA_DESCARTES == 0) {
      log.warn("Teto de e-mail de {} atingido ({}): e-mail de {} descartado ({} seguido(s)).",
          janela, teto, tipo, descartadosSeguidos);
    }
    return false;
  }

  private int contarDesde(Instant inicio) {
    int quantidade = 0;
    Iterator<Instant> doMaisNovo = envios.descendingIterator();
    while (doMaisNovo.hasNext() && doMaisNovo.next().isAfter(inicio)) {
      quantidade++;
    }
    return quantidade;
  }

  /** Avisa uma vez a cada vez que a contagem cruza 80% do teto, subindo. */
  private static void avisarSeCruzou(String janela, int contagem, int teto) {
    if (teto > 0 && contagem == (int) Math.ceil(teto * AVISO)) {
      log.warn("E-mail: {} de {} envios permitidos em {}. Acima do teto, os e-mails sao "
          + "descartados ate a janela andar.", contagem, teto, janela);
    }
  }
}
