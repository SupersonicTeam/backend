package br.com.agrocontabil.assistente.rag;

/**
 * Um dispositivo de lei (artigo, parágrafo, inciso, alínea) pronto para indexar (issue #156).
 *
 * @param dispositivo referência dentro da norma, montada pelo fatiador — é ela que vira a citação
 *                    (ex.: {@code art. 138, § 1º, inciso IV})
 * @param texto       texto do dispositivo precedido do texto dos dispositivos que o contêm (o caput
 *                    do artigo antes de um inciso): um inciso sozinho não diz do que trata
 */
public record DispositivoLegal(String dispositivo, String texto) {}
