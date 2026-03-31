package com.empresa.lpnscanner;

/** Modelo simples para um item de LPN mostrado na lista. */
public class LpnItem {
    /** Código LPN normalizado (string que aparece na lista) */
    public final String lpn;

    /** Horário HH:mm:ss de quando foi adicionada */
    public final String time;

    /** true se foi digitada manualmente, false se veio do scanner */
    public final boolean manual;

    public LpnItem(String lpn, String time, boolean manual) {
        this.lpn = lpn;
        this.time = time;
        this.manual = manual;
    }
}
