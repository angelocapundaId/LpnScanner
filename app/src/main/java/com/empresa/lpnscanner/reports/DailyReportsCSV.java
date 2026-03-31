package com.empresa.lpnscanner.reports;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * DailyReportsCSV
 *
 * CSV orientado para BI / Dashboard.
 *
 * Header:
 * DATA_ISO;DATA_BR;HORA;ANO;MES;DIA;HORA_NUMERICA;OPERADOR_ID;OPERADOR;POSICAO;
 * FONTE_POSICAO;METODO;SCAN_TYPE;KIND;KIND_GROUP;VALID_STATUS;IS_VALID;IS_INVALID;
 * LPN_RAW;LPN_NORMALIZED;LPN_CANONICAL;SESSION_ID;SESSION_START;SESSION_END;
 * SESSION_DURATION_SECONDS;SESSION_DURATION_MINUTES;SESSION_SCAN_COUNT
 */
public class DailyReportsCSV {

    private static final String TAG = "DailyReportsCSV";

    private final Context context;
    private final FirebaseFirestore db;

    private final SimpleDateFormat dateFormatBr = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
    private final SimpleDateFormat dateFormatIso = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat hourFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public DailyReportsCSV(Context context) {
        this.context = context;
        this.db = FirebaseFirestore.getInstance();

        // 1) Padronização de timezone
        TimeZone tz = TimeZone.getDefault();
        dateFormatBr.setTimeZone(tz);
        dateFormatIso.setTimeZone(tz);
        hourFormat.setTimeZone(tz);
    }

    public void gerarCsvDiario() {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = Calendar.getInstance();
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59);
        end.set(Calendar.MILLISECOND, 999);

        String label = dateFormatBr.format(start.getTime());
        buscarDadosEGerarCsv(label, new Timestamp(start.getTime()), new Timestamp(end.getTime()));
    }

    public void gerarCsvPeriodo(String dataInicio, String dataFim) {
        try {
            Date di = dateFormatBr.parse(dataInicio);
            Date df = dateFormatBr.parse(dataFim);

            if (di == null || df == null) {
                Toast.makeText(context, "Datas inválidas. Use dd/MM/yyyy", Toast.LENGTH_LONG).show();
                return;
            }

            Calendar start = Calendar.getInstance();
            start.setTime(di);
            start.set(Calendar.HOUR_OF_DAY, 0);
            start.set(Calendar.MINUTE, 0);
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);

            Calendar end = Calendar.getInstance();
            end.setTime(df);
            end.set(Calendar.HOUR_OF_DAY, 23);
            end.set(Calendar.MINUTE, 59);
            end.set(Calendar.SECOND, 59);
            end.set(Calendar.MILLISECOND, 999);

            if (end.before(start)) {
                Toast.makeText(context, "Data final não pode ser menor que a inicial.", Toast.LENGTH_LONG).show();
                return;
            }

            String label = dataInicio + "_a_" + dataFim;
            buscarDadosEGerarCsv(label, new Timestamp(start.getTime()), new Timestamp(end.getTime()));

        } catch (Exception e) {
            Log.e(TAG, "Erro nas datas do período", e);
            Toast.makeText(context, "Erro nas datas. Use dd/MM/yyyy", Toast.LENGTH_LONG).show();
        }
    }

    private void buscarDadosEGerarCsv(String periodoLabel, Timestamp startOfRange, Timestamp endOfRange) {
        db.collection("sessions")
                .whereGreaterThanOrEqualTo("startedAt", startOfRange)
                .whereLessThanOrEqualTo("startedAt", endOfRange)
                .get()
                .addOnSuccessListener(sessionsSnap -> {

                    List<SessionData> sessions = parseSessions(sessionsSnap);

                    if (sessions.isEmpty()) {
                        gerarCsvVazio(periodoLabel);
                        return;
                    }

                    List<Task<QuerySnapshot>> scanTasks = new ArrayList<>();
                    for (SessionData s : sessions) {
                        scanTasks.add(
                                db.collection("sessions")
                                        .document(s.sessionId)
                                        .collection("scans")
                                        .get()
                        );
                    }

                    Tasks.whenAllSuccess(scanTasks)
                            .addOnSuccessListener(results -> {
                                for (int i = 0; i < results.size(); i++) {
                                    QuerySnapshot scansSnap = (QuerySnapshot) results.get(i);
                                    sessions.get(i).scans = parseScans(scansSnap);
                                }

                                gerarCsv(periodoLabel, sessions);
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Erro ao buscar scans", e);
                                Toast.makeText(context, "Erro ao buscar scans", Toast.LENGTH_LONG).show();
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Erro ao buscar sessões", e);
                    Toast.makeText(context, "Erro ao buscar sessões", Toast.LENGTH_LONG).show();
                });
    }

    private void gerarCsvVazio(String periodoLabel) {
        try {
            Uri uri = criarArquivoCSV(periodoLabel);
            if (uri == null) return;

            OutputStream os = context.getContentResolver().openOutputStream(uri);
            if (os == null) {
                Toast.makeText(context, "Erro ao abrir arquivo CSV", Toast.LENGTH_LONG).show();
                return;
            }

            // 2) Escrita em UTF-8
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));

            bw.write(buildHeader());
            bw.newLine();
            bw.flush();
            bw.close();

            Toast.makeText(context, "CSV vazio salvo em Downloads", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao gerar CSV vazio", e);
            Toast.makeText(context, "Erro ao gerar CSV", Toast.LENGTH_LONG).show();
        }
    }

    private void gerarCsv(String periodoLabel, List<SessionData> sessions) {
        try {
            Uri uri = criarArquivoCSV(periodoLabel);
            if (uri == null) return;

            OutputStream os = context.getContentResolver().openOutputStream(uri);
            if (os == null) {
                Toast.makeText(context, "Erro ao abrir arquivo CSV", Toast.LENGTH_LONG).show();
                return;
            }

            // 2) Escrita em UTF-8
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));

            bw.write(buildHeader());
            bw.newLine();

            int rows = 0;

            for (SessionData sess : sessions) {
                if (sess.scans == null || sess.scans.isEmpty()) continue;

                // 3) Fallback usando operatorId também
                String operadorId = safeOr(sess.operatorId, "SEM_OPERADOR_ID");
                String operador = !isBlank(sess.operatorName)
                        ? sess.operatorName
                        : "OPERADOR_" + operadorId;

                String sessionStart = formatDateTime(sess.startedAt);
                String sessionEnd = formatDateTime(sess.finishedAt);
                long sessionDurationSeconds = getDurationSeconds(sess.startedAt, sess.finishedAt);
                long sessionDurationMinutes = TimeUnit.SECONDS.toMinutes(sessionDurationSeconds);

                // 4) Enriquecimento com contagem de scans por sessão
                int sessionScanCount = sess.scans.size();

                for (ScanData sc : sess.scans) {
                    Date d = sc.timestamp != null
                            ? sc.timestamp.toDate()
                            : (sess.startedAt != null ? sess.startedAt.toDate() : null);

                    String dataIso = d != null ? dateFormatIso.format(d) : "";
                    String dataBr = d != null ? dateFormatBr.format(d) : "";
                    String hora = d != null ? hourFormat.format(d) : "";

                    Calendar cal = Calendar.getInstance();
                    if (d != null) cal.setTime(d);

                    String ano = d != null ? String.valueOf(cal.get(Calendar.YEAR)) : "";
                    String mes = d != null ? String.format(Locale.ROOT, "%02d", cal.get(Calendar.MONTH) + 1) : "";
                    String dia = d != null ? String.format(Locale.ROOT, "%02d", cal.get(Calendar.DAY_OF_MONTH)) : "";
                    String horaNumerica = d != null
                            ? String.format(Locale.ROOT, "%02d", cal.get(Calendar.HOUR_OF_DAY))
                            : "";

                    String metodo = sc.manual ? "MANUAL" : "CAMERA";
                    String scanType = safeOr(sc.scanType, "");

                    String posicaoFinal;
                    String fontePosicao;
                    if (!isBlank(sc.position)) {
                        posicaoFinal = sc.position;
                        fontePosicao = "SCAN";
                    } else if (!isBlank(sess.position)) {
                        posicaoFinal = sess.position;
                        fontePosicao = "SESSION";
                    } else {
                        posicaoFinal = "SEM_POSICAO";
                        fontePosicao = "NONE";
                    }

                    String kind = sc.kind.name();
                    String kindGroup = mapKindGroup(sc.kind);
                    String validStatus = sc.kind == LpnKind.INVALID ? "INVALIDO" : "VALIDO";
                    int isValid = sc.kind == LpnKind.INVALID ? 0 : 1;
                    int isInvalid = sc.kind == LpnKind.INVALID ? 1 : 0;

                    String lpnRaw = safeOr(sc.lpnRaw, "");
                    String lpnNormalized = safeOr(sc.lpnNormalized, "");
                    String lpnCanonical = sc.kind == LpnKind.OK_SSCC
                            ? canonicalSscc(lpnNormalized)
                            : lpnNormalized;

                    bw.write(
                            csv(dataIso) + ";" +
                                    csv(dataBr) + ";" +
                                    csv(hora) + ";" +
                                    csv(ano) + ";" +
                                    csv(mes) + ";" +
                                    csv(dia) + ";" +
                                    csv(horaNumerica) + ";" +
                                    csv(operadorId) + ";" +
                                    csv(operador) + ";" +
                                    csv(posicaoFinal) + ";" +
                                    csv(fontePosicao) + ";" +
                                    csv(metodo) + ";" +
                                    csv(scanType) + ";" +
                                    csv(kind) + ";" +
                                    csv(kindGroup) + ";" +
                                    csv(validStatus) + ";" +
                                    csv(String.valueOf(isValid)) + ";" +
                                    csv(String.valueOf(isInvalid)) + ";" +
                                    csv(lpnRaw) + ";" +
                                    csv(lpnNormalized) + ";" +
                                    csv(lpnCanonical) + ";" +
                                    csv(sess.sessionId) + ";" +
                                    csv(sessionStart) + ";" +
                                    csv(sessionEnd) + ";" +
                                    csv(String.valueOf(sessionDurationSeconds)) + ";" +
                                    csv(String.valueOf(sessionDurationMinutes)) + ";" +
                                    csv(String.valueOf(sessionScanCount))
                    );
                    bw.newLine();

                    rows++;
                }
            }

            bw.flush();
            bw.close();

            Toast.makeText(context, "CSV salvo em Downloads (" + rows + " linhas)", Toast.LENGTH_LONG).show();
            Log.d(TAG, "CSV OK | rows=" + rows);

        } catch (Exception e) {
            Log.e(TAG, "Erro ao gerar CSV", e);
            Toast.makeText(context, "Erro ao gerar CSV", Toast.LENGTH_LONG).show();
        }
    }

    private String buildHeader() {
        return "DATA_ISO;DATA_BR;HORA;ANO;MES;DIA;HORA_NUMERICA;OPERADOR_ID;OPERADOR;POSICAO;" +
                "FONTE_POSICAO;METODO;SCAN_TYPE;KIND;KIND_GROUP;VALID_STATUS;IS_VALID;IS_INVALID;" +
                "LPN_RAW;LPN_NORMALIZED;LPN_CANONICAL;SESSION_ID;SESSION_START;SESSION_END;" +
                "SESSION_DURATION_SECONDS;SESSION_DURATION_MINUTES;SESSION_SCAN_COUNT";
    }

    private String csv(String s) {
        if (s == null) return "";
        boolean mustQuote = s.contains(";") || s.contains("\"") || s.contains("\n");
        String out = s.replace("\"", "\"\"");
        return mustQuote ? ("\"" + out + "\"") : out;
    }

    private Uri criarArquivoCSV(String periodoLabel) {
        String safe = periodoLabel
                .replace("/", "-")
                .replace(" ", "_");

        String name = "Relatorio_LPN_" + safe + ".csv";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        Uri uri = context.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) {
            Toast.makeText(context, "Erro ao criar arquivo CSV", Toast.LENGTH_LONG).show();
        }
        return uri;
    }

    private List<SessionData> parseSessions(QuerySnapshot sessionsSnap) {
        List<SessionData> out = new ArrayList<>();
        for (QueryDocumentSnapshot doc : sessionsSnap) {
            SessionData s = new SessionData();
            s.sessionId = doc.getId();
            s.operatorId = safeStr(doc.getString("operatorId"));
            s.operatorName = safeStr(doc.getString("operatorName"));
            s.position = safeStr(doc.getString("position"));
            s.startedAt = doc.getTimestamp("startedAt");
            s.finishedAt = doc.getTimestamp("finishedAt");
            out.add(s);
        }

        out.sort(Comparator.comparing(a -> a.startedAt != null ? a.startedAt.toDate() : new Date(0)));
        return out;
    }

    private List<ScanData> parseScans(QuerySnapshot scansSnap) {
        List<ScanData> out = new ArrayList<>();
        for (QueryDocumentSnapshot doc : scansSnap) {
            ScanData s = new ScanData();

            s.lpnRaw = safeStr(doc.getString("lpn"));
            s.lpnNormalized = normalizeScan(s.lpnRaw);

            Timestamp ts = doc.getTimestamp("timestamp");
            if (ts == null) ts = doc.getTimestamp("createdAt");
            s.timestamp = ts;

            Boolean manual = doc.getBoolean("manual");
            s.manual = manual != null && manual;

            s.position = safeStr(doc.getString("position"));
            s.scanType = safeStr(doc.getString("scanType"));
            s.kind = classifyLpn(s.lpnNormalized);

            out.add(s);
        }

        out.sort(Comparator.comparing(a -> a.timestamp != null ? a.timestamp.toDate() : new Date(0)));
        return out;
    }

    private String normalizeScan(String raw) {
        if (raw == null) return "";

        String s = raw.trim();
        s = s.replace("\u001D", "[GS]");
        s = s.replaceAll("\\s+", "");
        s = s.replace("(", "").replace(")", "");
        s = s.replace("<", "").replace(">", "");

        return s.toUpperCase(Locale.ROOT);
    }

    private enum LpnKind {
        OK_SSCC,
        OK_GS1_SECOND,
        INVALID
    }

    private LpnKind classifyLpn(String normalized) {
        if (isSsccOk(normalized)) return LpnKind.OK_SSCC;
        if (isGs1SecondOk(normalized)) return LpnKind.OK_GS1_SECOND;
        return LpnKind.INVALID;
    }

    private boolean isSsccOk(String s) {
        if (isBlank(s)) return false;

        String digits = onlyDigits(s);
        if (digits.length() == 18) return true;
        return digits.length() == 20 && digits.startsWith("00");
    }

    private boolean isGs1SecondOk(String s) {
        if (isBlank(s)) return false;

        String compact = s.replace("[GS]", "").replace("\u001D", "");
        if (!compact.matches("^\\d+$")) return false;
        if (!compact.startsWith("90")) return false;

        int idx37 = compact.indexOf("37", 2);
        int idx10 = compact.indexOf("10", 2);

        if (idx37 < 0 || idx10 < 0) return false;
        if (idx37 >= idx10) return false;

        return (idx10 + 2) < compact.length();
    }

    private String canonicalSscc(String normalized) {
        String d = onlyDigits(normalized);

        if (d.length() == 18) return "00" + d;
        if (d.length() == 20 && d.startsWith("00")) return d;

        return normalized;
    }

    private String onlyDigits(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D+", "");
    }

    private String mapKindGroup(LpnKind kind) {
        switch (kind) {
            case OK_SSCC:
                return "SSCC";
            case OK_GS1_SECOND:
                return "GS1_SECOND";
            default:
                return "INVALID";
        }
    }

    private long getDurationSeconds(@Nullable Timestamp start, @Nullable Timestamp end) {
        if (start == null || end == null) return 0L;

        long diffMillis = end.toDate().getTime() - start.toDate().getTime();
        return Math.max(diffMillis / 1000L, 0L);
    }

    private String formatDateTime(@Nullable Timestamp ts) {
        if (ts == null) return "";
        Date d = ts.toDate();
        return dateFormatIso.format(d) + " " + hourFormat.format(d);
    }

    private String safeStr(@Nullable String s) {
        return s == null ? "" : s.trim();
    }

    private String safeOr(@Nullable String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        return t.isEmpty() ? fallback : t;
    }

    private boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    private static class SessionData {
        String sessionId;
        String operatorId;
        String operatorName;
        String position;
        Timestamp startedAt;
        Timestamp finishedAt;
        List<ScanData> scans = new ArrayList<>();
    }

    private static class ScanData {
        String lpnRaw;
        String lpnNormalized;
        Timestamp timestamp;
        boolean manual;
        String position;
        String scanType;
        LpnKind kind = LpnKind.INVALID;
    }
}