package com.empresa.lpnscanner;

/** Modelo de uma leitura da operação: posição + SSCC + horário. */
public class LpnItem {

    /** SSCC/LPN lido */
    public final String lpn;

    /** Posição vinculada à leitura */
    public final String position;

    /** Horário HH:mm:ss de quando foi adicionada */
    public final String time;

    /** true se foi digitada manualmente, false se veio do scanner */
    public final boolean manual;

    /**
     * Construtor novo: posição + LPN + horário + origem
     */
    public LpnItem(String lpn, String position, String time, boolean manual) {
        this.lpn = lpn;
        this.position = position;
        this.time = time;
        this.manual = manual;
    }

    /**
     * Construtor antigo mantido por compatibilidade
     */
    public LpnItem(String lpn, String time, boolean manual) {
        this.lpn = lpn;
        this.position = "";
        this.time = time;
        this.manual = manual;
    }
}