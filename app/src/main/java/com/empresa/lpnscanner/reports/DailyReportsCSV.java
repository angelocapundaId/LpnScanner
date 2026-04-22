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

            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
            bw.write(buildHeader());
            bw.newLine();

            int rows = 0;

            for (SessionData sess : sessions) {
                if (sess.scans == null || sess.scans.isEmpty()) continue;

                String operadorId = safeOr(sess.operatorId, "SEM_OPERADOR_ID");
                String operador = !isBlank(sess.operatorName)
                        ? sess.operatorName
                        : "OPERADOR_" + operadorId;

                String sessionStart = formatDateTime(sess.startedAt);
                String sessionEnd = formatDateTime(sess.finishedAt);
                long sessionDurationSeconds = getDurationSeconds(sess.startedAt, sess.finishedAt);
                long sessionDurationMinutes = TimeUnit.SECONDS.toMinutes(sessionDurationSeconds);
                int sessionScanCount = sess.scans.size();

                for (ScanData sc : sess.scans) {
                    Date d = sc.timestamp != null
                            ? sc.timestamp.toDate()
                            : (sess.startedAt != null ? sess.startedAt.toDate() : null);

                    String dataIso = d != null ? dateFormatIso.format(d) : "";
                    String dataBr = d != null ? dateFormatBr.format(d) : "";
                    String hora = !isBlank(sc.localTime)
                            ? sc.localTime
                            : (d != null ? hourFormat.format(d) : "");

                    Calendar cal = Calendar.getInstance();
                    if (d != null) cal.setTime(d);

                    String ano = d != null ? String.valueOf(cal.get(Calendar.YEAR)) : "";
                    String mes = d != null ? String.format(Locale.ROOT, "%02d", cal.get(Calendar.MONTH) + 1) : "";
                    String dia = d != null ? String.format(Locale.ROOT, "%02d", cal.get(Calendar.DAY_OF_MONTH)) : "";
                    String horaNumerica = d != null
                            ? String.format(Locale.ROOT, "%02d", cal.get(Calendar.HOUR_OF_DAY))
                            : "";

                    String metodo = sc.manual ? "MANUAL" : "CAMERA";
                    String validStatus = sc.validPair ? "VALIDO" : "INVALIDO";
                    int isValid = sc.validPair ? 1 : 0;
                    int isInvalid = sc.validPair ? 0 : 1;

                    String motivo = inferInvalidReason(sc);
                    String posicao = safeOr(sc.positionNormalized, "SEM_POSICAO");
                    String ssccRaw = safeOr(sc.lpnRaw, "");
                    String ssccNormalizado = safeOr(sc.lpnNormalized, "");
                    String ssccCanonico = sc.ssccValid ? canonicalSscc(sc.lpnNormalized) : "";

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
                                    csv(posicao) + ";" +
                                    csv(metodo) + ";" +
                                    csv(validStatus) + ";" +
                                    csv(String.valueOf(isValid)) + ";" +
                                    csv(String.valueOf(isInvalid)) + ";" +
                                    csv(motivo) + ";" +
                                    csv(ssccRaw) + ";" +
                                    csv(ssccNormalizado) + ";" +
                                    csv(ssccCanonico) + ";" +
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
                "METODO;VALID_STATUS;IS_VALID;IS_INVALID;INVALID_REASON;SSCC_RAW;SSCC_NORMALIZED;" +
                "SSCC_CANONICAL;SESSION_ID;SESSION_START;SESSION_END;SESSION_DURATION_SECONDS;" +
                "SESSION_DURATION_MINUTES;SESSION_SCAN_COUNT";
    }

    private String csv(String s) {
        if (s == null) return "";
        boolean mustQuote = s.contains(";") || s.contains("\"") || s.contains("\n");
        String out = s.replace("\"", "\"\"");
        return mustQuote ? ("\"" + out + "\"") : out;
    }

    private Uri criarArquivoCSV(String periodoLabel) {
        String safe = periodoLabel.replace("/", "-").replace(" ", "_");
        String name = "Relatorio_Operacoes_" + safe + ".csv";

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

            s.positionRaw = safeStr(doc.getString("position"));
            s.positionNormalized = normalizePosition(s.positionRaw);

            s.localTime = safeStr(doc.getString("localTime"));

            Timestamp ts = doc.getTimestamp("timestamp");
            if (ts == null) ts = doc.getTimestamp("createdAt");
            s.timestamp = ts;

            Boolean manual = doc.getBoolean("manual");
            s.manual = manual != null && manual;

            s.ssccValid = isSsccOk(s.lpnNormalized);
            s.positionValid = isPositionOk(s.positionNormalized);
            s.validPair = s.ssccValid && s.positionValid;

            out.add(s);
        }

        out.sort(Comparator.comparing(a -> a.timestamp != null ? a.timestamp.toDate() : new Date(0)));
        return out;
    }

    private String normalizeScan(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(Locale.ROOT);
        s = s.replace("\u001D", "");
        s = s.replaceAll("\\s+", "");
        s = s.replace("(", "").replace(")", "");
        s = s.replace("<", "").replace(">", "");
        return s;
    }

    private String normalizePosition(String raw) {
        if (raw == null) return "";
        return raw.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
    }

    private boolean isSsccOk(String s) {
        if (isBlank(s)) return false;
        String digits = onlyDigits(s);
        return digits.length() == 20 && digits.startsWith("00");
    }

    private boolean isPositionOk(String s) {
        if (isBlank(s)) return false;
        return s.matches("^[A-Z]{2}[0-9]{7}$");
    }

    private String canonicalSscc(String normalized) {
        String d = onlyDigits(normalized);
        if (d.length() >= 20 && d.startsWith("00")) {
            return d.substring(0, 20);
        }
        return d;
    }

    private String onlyDigits(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D+", "");
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

    private String inferInvalidReason(ScanData sc) {
        if (isBlank(sc.positionNormalized) && isBlank(sc.lpnNormalized)) {
            return "POSICAO_E_SSCC_VAZIOS";
        }
        if (!sc.positionValid && !sc.ssccValid) {
            return "POSICAO_E_SSCC_INVALIDOS";
        }
        if (!sc.positionValid) {
            return "POSICAO_INVALIDA";
        }
        if (!sc.ssccValid) {
            return "SSCC_INVALIDO";
        }
        return "";
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
        String positionRaw;
        String positionNormalized;
        String localTime;
        Timestamp timestamp;
        boolean manual;

        boolean ssccValid;
        boolean positionValid;
        boolean validPair;
    }
}