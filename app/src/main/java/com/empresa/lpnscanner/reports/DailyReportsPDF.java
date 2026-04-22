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
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.LineSeparator;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DailyReportsPDF {

    private static final String TAG = "DailyReportsPDF";

    private static final boolean INCLUDE_ANEXO_DETALHADO = true;
    private static final int MAX_ANEXO_SCANS_TOTAL = 700;

    private final Context context;
    private final FirebaseFirestore db;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
    private final SimpleDateFormat hourFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public DailyReportsPDF(Context context) {
        this.context = context;
        this.db = FirebaseFirestore.getInstance();
    }

    public void gerarRelatorioDiario() {
        CalendarRange range = buildTodayRange();
        buscarDadosEGerarPdf(range.label, range.startTs, range.endTs);
    }

    public void gerarRelatorioPeriodo(String dataInicio, String dataFim) {
        try {
            Date di = dateFormat.parse(dataInicio);
            Date df = dateFormat.parse(dataFim);

            if (di == null || df == null) {
                Toast.makeText(context, "Datas inválidas. Use dd/MM/yyyy", Toast.LENGTH_LONG).show();
                return;
            }

            Calendar start = java.util.Calendar.getInstance();
            start.setTime(di);
            start.set(java.util.Calendar.HOUR_OF_DAY, 0);
            start.set(java.util.Calendar.MINUTE, 0);
            start.set(java.util.Calendar.SECOND, 0);
            start.set(java.util.Calendar.MILLISECOND, 0);

            Calendar end = java.util.Calendar.getInstance();
            end.setTime(df);
            end.set(java.util.Calendar.HOUR_OF_DAY, 23);
            end.set(java.util.Calendar.MINUTE, 59);
            end.set(java.util.Calendar.SECOND, 59);
            end.set(java.util.Calendar.MILLISECOND, 999);

            if (end.before(start)) {
                Toast.makeText(context, "Data final não pode ser menor que a inicial.", Toast.LENGTH_LONG).show();
                return;
            }

            String label = dataInicio + " a " + dataFim;
            buscarDadosEGerarPdf(label, new Timestamp(start.getTime()), new Timestamp(end.getTime()));

        } catch (Exception e) {
            Toast.makeText(context, "Erro nas datas. Use dd/MM/yyyy", Toast.LENGTH_LONG).show();
        }
    }

    private void buscarDadosEGerarPdf(String periodoLabel, Timestamp startOfRange, Timestamp endOfRange) {
        db.collection("sessions")
                .whereGreaterThanOrEqualTo("startedAt", startOfRange)
                .whereLessThanOrEqualTo("startedAt", endOfRange)
                .get()
                .addOnSuccessListener(sessionsSnap -> {

                    List<SessionData> sessions = parseSessions(sessionsSnap);

                    if (sessions.isEmpty()) {
                        DayReport report = DayReport.empty(periodoLabel);
                        gerarPdf(report, periodoLabel);
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

                                DayReport report = buildReport(periodoLabel, sessions);
                                gerarPdf(report, periodoLabel);
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

    private List<SessionData> parseSessions(QuerySnapshot sessionsSnap) {
        List<SessionData> out = new ArrayList<>();

        for (QueryDocumentSnapshot doc : sessionsSnap) {
            SessionData s = new SessionData();
            s.sessionId = doc.getId();
            s.operatorId = safeStr(doc.getString("operatorId"));
            s.operatorName = safeStr(doc.getString("operatorName"));
            s.startedAt = doc.getTimestamp("startedAt");
            s.finishedAt = doc.getTimestamp("finishedAt");
            s.status = safeStr(doc.getString("status"));

            Long total = doc.getLong("total");
            if (total == null) total = doc.getLong("totalScans");
            s.totalScans = total != null ? total.intValue() : 0;

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

    private DayReport buildReport(String periodoLabel, List<SessionData> sessions) {
        DayReport r = new DayReport();
        r.reportDate = periodoLabel;
        r.sessions = sessions;

        Set<String> uniqueSsccSet = new HashSet<>();
        Set<String> uniquePositionSet = new HashSet<>();
        Set<String> operatorSet = new HashSet<>();

        Map<String, PositionSummary> byPosition = new LinkedHashMap<>();
        Map<String, OperatorSummary> byOperator = new LinkedHashMap<>();
        Map<String, Integer> duplicatedSsccCounter = new HashMap<>();
        Map<InvalidReason, Integer> invalidByReason = new LinkedHashMap<>();

        for (InvalidReason reason : InvalidReason.values()) {
            invalidByReason.put(reason, 0);
        }

        int sessionsVazias = 0;
        int totalScans = 0;
        int validPairs = 0;
        int invalidScans = 0;
        int totalManual = 0;

        for (SessionData sess : sessions) {
            String operator = isBlank(sess.operatorName) ? "SEM_OPERADOR" : sess.operatorName;
            operatorSet.add(operator);

            if (sess.scans == null || sess.scans.isEmpty()) {
                sessionsVazias++;
                continue;
            }

            OperatorSummary os = byOperator.computeIfAbsent(operator, OperatorSummary::new);
            os.sessions++;

            for (ScanData sc : sess.scans) {
                totalScans++;
                if (sc.manual) totalManual++;

                String pos = isBlank(sc.positionNormalized) ? "SEM_POSICAO" : sc.positionNormalized;
                PositionSummary ps = byPosition.computeIfAbsent(pos, PositionSummary::new);

                ps.sessions++;
                ps.operators.add(operator);
                os.positions.add(pos);

                if (sc.validPair) {
                    validPairs++;

                    String canonicalSscc = canonicalSscc(sc.lpnNormalized);
                    uniqueSsccSet.add(canonicalSscc);
                    uniquePositionSet.add(pos);

                    duplicatedSsccCounter.put(canonicalSscc,
                            duplicatedSsccCounter.getOrDefault(canonicalSscc, 0) + 1);

                    ps.validCount++;
                    os.validCount++;
                } else {
                    invalidScans++;

                    InvalidReason reason = inferInvalidReason(sc);
                    invalidByReason.put(reason, invalidByReason.getOrDefault(reason, 0) + 1);

                    ps.invalidCount++;
                    os.invalidCount++;

                    if (!isBlank(sc.lpnNormalized) && r.invalidSamples.size() < 12) {
                        r.invalidSamples.add(
                                "POS=" + safeOr(sc.positionNormalized, "SEM_POSICAO")
                                        + " | SSCC=" + safeOr(sc.lpnNormalized, "VAZIO")
                        );
                    }
                }
            }
        }

        for (Map.Entry<String, Integer> e : duplicatedSsccCounter.entrySet()) {
            if (e.getValue() > 1) {
                r.duplicatedSscc.put(e.getKey(), e.getValue());
            }
        }

        r.totalSessions = sessions.size();
        r.sessionsVazias = sessionsVazias;
        r.totalScans = totalScans;
        r.totalManual = totalManual;
        r.validPairs = validPairs;
        r.invalidScans = invalidScans;
        r.uniqueSscc = uniqueSsccSet.size();
        r.uniquePositions = uniquePositionSet.size();
        r.operatorsCount = operatorSet.size();
        r.invalidByReason = invalidByReason;

        r.byPosition = new ArrayList<>(byPosition.values());
        r.byPosition.sort((a, b) -> Integer.compare(b.validCount, a.validCount));

        r.byOperator = new ArrayList<>(byOperator.values());
        r.byOperator.sort((a, b) -> Integer.compare(b.validCount, a.validCount));

        if (sessionsVazias > 0) {
            r.alerts.add("Operações sem leituras: " + sessionsVazias);
        }
        if (!r.duplicatedSscc.isEmpty()) {
            r.alerts.add("SSCCs duplicados: " + r.duplicatedSscc.size());
        }
        if (invalidScans > 0) {
            r.alerts.add("Leituras inválidas: " + invalidScans);
        }

        return r;
    }

    private void gerarPdf(DayReport report, String periodoLabel) {
        Uri uri = null;
        Document document = null;

        try {
            uri = criarArquivoPDF(periodoLabel);
            if (uri == null) return;

            OutputStream os = context.getContentResolver().openOutputStream(uri);
            PdfWriter writer = new PdfWriter(os);
            PdfDocument pdf = new PdfDocument(writer);
            document = new Document(pdf);

            renderCabecalho(document, report.reportDate);
            renderPainelExecutivo(document, report);
            renderResumoPorPosicao(document, report);
            renderResumoPorOperador(document, report);
            renderExcecoes(document, report);

            if (INCLUDE_ANEXO_DETALHADO) {
                renderAnexoDetalhes(document, report);
            }

            document.close();

            Toast.makeText(context, "Relatório salvo em Downloads", Toast.LENGTH_LONG).show();
            Log.d(TAG, "PDF OK | sessões=" + report.totalSessions + " | válidos=" + report.validPairs);

        } catch (Exception e) {
            Log.e(TAG, "Erro ao gerar PDF", e);
            Toast.makeText(context, "Erro ao gerar PDF", Toast.LENGTH_LONG).show();
            try {
                if (document != null) document.close();
            } catch (Exception ignored) { }
        }
    }

    private void renderCabecalho(Document doc, String periodo) {
        doc.add(new Paragraph("RELATÓRIO DE OPERAÇÕES - LPN SCANNER")
                .setBold()
                .setFontSize(18)
                .setTextAlignment(TextAlignment.CENTER));

        doc.add(new Paragraph("Período: " + periodo)
                .setTextAlignment(TextAlignment.CENTER));

        doc.add(new Paragraph(" "));
        doc.add(new LineSeparator(new SolidLine()));
        doc.add(new Paragraph(" "));
    }

    private void renderPainelExecutivo(Document doc, DayReport r) {
        doc.add(tituloSecao("PAINEL DO PERÍODO"));

        double eficiencia = r.totalScans > 0 ? (r.validPairs * 100.0 / r.totalScans) : 0.0;
        int camera = Math.max(0, r.totalScans - r.totalManual);

        doc.add(new Paragraph("Operações: " + r.totalSessions).setBold());
        doc.add(new Paragraph("Leituras totais: " + r.totalScans));
        doc.add(new Paragraph("Leituras válidas (posição + SSCC): " + r.validPairs));
        doc.add(new Paragraph("Leituras inválidas: " + r.invalidScans));
        doc.add(new Paragraph("Eficiência: " + String.format(Locale.getDefault(), "%.1f", eficiencia) + "%"));
        doc.add(new Paragraph("SSCCs únicos: " + r.uniqueSscc));
        doc.add(new Paragraph("Posições atendidas: " + r.uniquePositions));
        doc.add(new Paragraph("Operadores ativos: " + r.operatorsCount));
        doc.add(new Paragraph("Método: Manual: " + r.totalManual + " | Câmera: " + camera));

        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("ALERTAS").setBold());

        if (r.alerts.isEmpty()) {
            doc.add(new Paragraph("Nenhum alerta relevante no período."));
        } else {
            for (String a : r.alerts) {
                doc.add(new Paragraph("• " + a));
            }
        }

        separador(doc);
    }

    private void renderResumoPorPosicao(Document doc, DayReport r) {
        doc.add(tituloSecao("RESUMO POR POSIÇÃO"));

        boolean any = false;

        for (PositionSummary ps : r.byPosition) {
            if ("SEM_POSICAO".equals(ps.position)) continue;
            if (ps.validCount <= 0 && ps.invalidCount <= 0) continue;

            any = true;

            doc.add(new Paragraph(ps.position).setBold());
            doc.add(new Paragraph("Leituras válidas: " + ps.validCount + " | Inválidas: " + ps.invalidCount));
            doc.add(new Paragraph("Ocorrências: " + ps.sessions + " | Operadores: " + ps.operators.size()));
            doc.add(new Paragraph(" "));
        }

        if (!any) {
            doc.add(new Paragraph("Sem posições registradas no período."));
        }

        separador(doc);
    }

    private void renderResumoPorOperador(Document doc, DayReport r) {
        doc.add(tituloSecao("RESUMO POR OPERADOR"));

        if (r.byOperator.isEmpty()) {
            doc.add(new Paragraph("Sem dados."));
            separador(doc);
            return;
        }

        for (OperatorSummary os : r.byOperator) {
            int total = os.validCount + os.invalidCount;
            double erro = total > 0 ? (os.invalidCount * 100.0 / total) : 0.0;

            doc.add(new Paragraph(os.operatorName).setBold());
            doc.add(new Paragraph("Leituras válidas: " + os.validCount + " | Inválidas: " + os.invalidCount));
            doc.add(new Paragraph("Operações: " + os.sessions
                    + " | Posições: " + countWithoutSemPosicao(os.positions)
                    + " | Erro: " + String.format(Locale.getDefault(), "%.1f", erro) + "%"));
            doc.add(new Paragraph(" "));
        }

        separador(doc);
    }

    private void renderExcecoes(Document doc, DayReport r) {
        doc.add(tituloSecao("EXCEÇÕES"));

        doc.add(new Paragraph("Operações sem leituras: " + r.sessionsVazias).setBold());

        doc.add(new Paragraph("SSCCs duplicados: " + r.duplicatedSscc.size()).setBold());
        if (!r.duplicatedSscc.isEmpty()) {
            int shown = 0;
            for (Map.Entry<String, Integer> e : r.duplicatedSscc.entrySet()) {
                doc.add(new Paragraph("• " + e.getKey() + " (" + e.getValue() + "x)"));
                shown++;
                if (shown >= 12) {
                    int rest = r.duplicatedSscc.size() - shown;
                    if (rest > 0) {
                        doc.add(new Paragraph("... (mais " + rest + ")"));
                    }
                    break;
                }
            }
        }

        doc.add(new Paragraph("Leituras inválidas: " + r.invalidScans).setBold());
        if (r.invalidScans > 0) {
            doc.add(new Paragraph("Por motivo:").setBold());
            for (Map.Entry<InvalidReason, Integer> e : r.invalidByReason.entrySet()) {
                if (e.getValue() <= 0) continue;
                doc.add(new Paragraph("• " + e.getKey().label + ": " + e.getValue()));
            }

            if (!r.invalidSamples.isEmpty()) {
                doc.add(new Paragraph(" "));
                doc.add(new Paragraph("Amostras (até 12):").setBold());
                for (String s : r.invalidSamples) {
                    doc.add(new Paragraph("• " + s));
                }
            }
        }

        separador(doc);
    }

    private void renderAnexoDetalhes(Document doc, DayReport r) {
        doc.add(tituloSecao("ANEXO - DETALHAMENTO"));

        int printed = 0;

        for (SessionData sess : r.sessions) {
            if (sess.scans == null || sess.scans.isEmpty()) continue;
            if (printed >= MAX_ANEXO_SCANS_TOTAL) break;

            doc.add(new Paragraph("Operador: " + safeOr(sess.operatorName, "SEM_OPERADOR")).setBold());
            doc.add(new Paragraph("Sessão: " + safeOr(sess.sessionId, "--")));
            doc.add(new Paragraph("Início: " + fmtTime(sess.startedAt != null ? sess.startedAt.toDate() : null)
                    + " | Fim: " + fmtTime(sess.finishedAt != null ? sess.finishedAt.toDate() : null)));

            doc.add(new Paragraph("Leituras:").setBold());

            for (ScanData sc : sess.scans) {
                if (printed >= MAX_ANEXO_SCANS_TOTAL) break;

                String hora = !isBlank(sc.localTime)
                        ? sc.localTime
                        : (sc.timestamp != null ? hourFormat.format(sc.timestamp.toDate()) : "--");

                String status = sc.validPair ? "OK" : "INVALIDO";

                doc.add(new Paragraph("• "
                        + hora
                        + " | POS: " + safeOr(sc.positionNormalized, "SEM_POSICAO")
                        + " | SSCC: " + safeOr(sc.lpnNormalized, "VAZIO")
                        + " | " + status));

                printed++;
            }

            doc.add(new Paragraph(" "));
        }

        if (printed >= MAX_ANEXO_SCANS_TOTAL) {
            doc.add(new Paragraph("... anexo truncado (limite de " + MAX_ANEXO_SCANS_TOTAL + " linhas).")
                    .setItalic());
        }
    }

    private Uri criarArquivoPDF(String periodoLabel) {
        String safe = periodoLabel.replace("/", "-").replace(" ", "_");
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Relatorio_Operacoes_" + safe + ".pdf");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        Uri uri = context.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) {
            Toast.makeText(context, "Erro ao criar arquivo PDF", Toast.LENGTH_LONG).show();
        }
        return uri;
    }

    private Paragraph tituloSecao(String t) {
        return new Paragraph(t).setBold().setFontSize(14);
    }

    private void separador(Document doc) {
        doc.add(new Paragraph(" "));
        doc.add(new LineSeparator(new SolidLine()));
        doc.add(new Paragraph(" "));
    }

    private String fmtTime(@Nullable Date d) {
        if (d == null) return "--";
        return hourFormat.format(d);
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

    private CalendarRange buildTodayRange() {
        java.util.Calendar start = java.util.Calendar.getInstance();
        start.set(java.util.Calendar.HOUR_OF_DAY, 0);
        start.set(java.util.Calendar.MINUTE, 0);
        start.set(java.util.Calendar.SECOND, 0);
        start.set(java.util.Calendar.MILLISECOND, 0);

        java.util.Calendar end = java.util.Calendar.getInstance();
        end.set(java.util.Calendar.HOUR_OF_DAY, 23);
        end.set(java.util.Calendar.MINUTE, 59);
        end.set(java.util.Calendar.SECOND, 59);
        end.set(java.util.Calendar.MILLISECOND, 999);

        CalendarRange r = new CalendarRange();
        r.label = dateFormat.format(start.getTime());
        r.startTs = new Timestamp(start.getTime());
        r.endTs = new Timestamp(end.getTime());
        return r;
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
        String digits = onlyDigits(normalized);
        if (digits.length() >= 20 && digits.startsWith("00")) {
            return digits.substring(0, 20);
        }
        return digits;
    }

    private String onlyDigits(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D+", "");
    }

    private int countWithoutSemPosicao(Set<String> positions) {
        int count = 0;
        for (String p : positions) {
            if (!"SEM_POSICAO".equals(p)) count++;
        }
        return count;
    }

    private InvalidReason inferInvalidReason(ScanData sc) {
        if (isBlank(sc.positionNormalized) && isBlank(sc.lpnNormalized)) {
            return InvalidReason.POSICAO_E_SSCC_VAZIOS;
        }
        if (!sc.positionValid && !sc.ssccValid) {
            return InvalidReason.POSICAO_E_SSCC_INVALIDOS;
        }
        if (!sc.positionValid) {
            return InvalidReason.POSICAO_INVALIDA;
        }
        if (!sc.ssccValid) {
            return InvalidReason.SSCC_INVALIDO;
        }
        return InvalidReason.OUTRO;
    }

    private enum InvalidReason {
        POSICAO_E_SSCC_VAZIOS("Posição e SSCC vazios"),
        POSICAO_INVALIDA("Posição inválida"),
        SSCC_INVALIDO("SSCC inválido"),
        POSICAO_E_SSCC_INVALIDOS("Posição e SSCC inválidos"),
        OUTRO("Outro");

        final String label;

        InvalidReason(String label) {
            this.label = label;
        }
    }

    private static class CalendarRange {
        String label;
        Timestamp startTs;
        Timestamp endTs;
    }

    private static class SessionData {
        String sessionId;
        String operatorId;
        String operatorName;
        Timestamp startedAt;
        Timestamp finishedAt;
        String status;
        int totalScans;
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

    private static class PositionSummary {
        final String position;
        int sessions = 0;
        int validCount = 0;
        int invalidCount = 0;
        final Set<String> operators = new HashSet<>();

        PositionSummary(String position) {
            this.position = position;
        }
    }

    private static class OperatorSummary {
        final String operatorName;
        int sessions = 0;
        int validCount = 0;
        int invalidCount = 0;
        final Set<String> positions = new HashSet<>();

        OperatorSummary(String operatorName) {
            this.operatorName = operatorName;
        }
    }

    private static class DayReport {
        String reportDate;

        int totalSessions;
        int sessionsVazias;
        int totalScans;
        int totalManual;
        int validPairs;
        int invalidScans;
        int uniqueSscc;
        int uniquePositions;
        int operatorsCount;

        final List<String> invalidSamples = new ArrayList<>();
        final Map<String, Integer> duplicatedSscc = new LinkedHashMap<>();
        final List<String> alerts = new ArrayList<>();

        Map<InvalidReason, Integer> invalidByReason = new LinkedHashMap<>();

        List<SessionData> sessions = new ArrayList<>();
        List<PositionSummary> byPosition = new ArrayList<>();
        List<OperatorSummary> byOperator = new ArrayList<>();

        static DayReport empty(String data) {
            DayReport r = new DayReport();
            r.reportDate = data;
            r.totalSessions = 0;
            r.sessionsVazias = 0;
            r.totalScans = 0;
            r.totalManual = 0;
            r.validPairs = 0;
            r.invalidScans = 0;
            r.uniqueSscc = 0;
            r.uniquePositions = 0;
            r.operatorsCount = 0;

            for (InvalidReason ir : InvalidReason.values()) {
                r.invalidByReason.put(ir, 0);
            }

            return r;
        }
    }
}