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
import java.util.*;

/**
 * DailyReportsPDF
 *
 * ✅ Reestruturação do relatório (mais enxuto e hierárquico)
 * ✅ Nova regra de validação: agora considera OK:
 *    - SSCC (AI 00): "00" + 18 dígitos (20 chars) OU SSCC puro com 18 dígitos
 *    - GS1 do "SECOND": cadeia que começa com 90 e contém AIs 37 e 10 (com ou sem separador GS)
 * ✅ Exceções agrupadas por tipo (em vez de listar tudo)
 * ✅ Resumo por posição e operador ignora itens com 0 leituras válidas
 * ✅ Anexo detalhado opcional e limitado (para não explodir páginas)
 */
public class DailyReportsPDF {

    private static final String TAG = "DailyReportsPDF";

    // ===== Ajustes de "boas práticas" do relatório =====
    private static final boolean INCLUDE_ANEXO_DETALHADO = true;   // se quiser PDF bem enxuto, coloque false
    private static final int MAX_ANEXO_SCANS_TOTAL = 500;          // limita linhas no anexo

    private final Context context;
    private final FirebaseFirestore db;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
    private final SimpleDateFormat hourFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public DailyReportsPDF(Context context) {
        this.context = context;
        this.db = FirebaseFirestore.getInstance();
    }

    // =========================
    //  API pública
    // =========================

    // ✅ Mantém como está: diário
    public void gerarRelatorioDiario() {
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

        String dataHoje = dateFormat.format(start.getTime());

        Timestamp startTs = new Timestamp(start.getTime());
        Timestamp endTs = new Timestamp(end.getTime());

        buscarDadosEGerarPdf(dataHoje, startTs, endTs);
    }

    // ✅ NOVO: por período (ex: 28/01/2026 até 03/02/2026)
    public void gerarRelatorioPeriodo(String dataInicio, String dataFim) {
        try {
            Date di = dateFormat.parse(dataInicio);
            Date df = dateFormat.parse(dataFim);

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

            String labelPeriodo = dataInicio + " a " + dataFim;
            buscarDadosEGerarPdf(labelPeriodo, new Timestamp(start.getTime()), new Timestamp(end.getTime()));

        } catch (Exception e) {
            Toast.makeText(context, "Erro nas datas. Use dd/MM/yyyy", Toast.LENGTH_LONG).show();
        }
    }

    // =========================
    //  Core: busca -> agrega -> PDF
    // =========================
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

    // =========================
    //  Parsing Firestore -> Models
    // =========================
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

            Long ts = doc.getLong("totalScans");
            if (ts == null) ts = doc.getLong("total");
            s.totalScans = ts != null ? ts.intValue() : null;

            out.add(s);
        }

        out.sort(Comparator.comparing(a -> a.startedAt != null ? a.startedAt.toDate() : new Date(0)));
        return out;
    }

    private List<ScanData> parseScans(QuerySnapshot scansSnap) {
        List<ScanData> out = new ArrayList<>();
        for (QueryDocumentSnapshot doc : scansSnap) {
            ScanData s = new ScanData();

            // Normaliza mantendo GS1 quando existir
            s.lpnRaw = safeStr(doc.getString("lpn"));
            s.lpnNormalized = normalizeScan(s.lpnRaw);

            Timestamp ts = doc.getTimestamp("timestamp");
            if (ts == null) ts = doc.getTimestamp("createdAt");
            s.timestamp = ts;

            Boolean manual = doc.getBoolean("manual");
            s.manual = manual != null && manual;

            s.position = safeStr(doc.getString("position"));
            s.scanType = safeStr(doc.getString("scanType")); // SECOND | LAST | LEGACY

            // Classifica conforme nova regra
            s.kind = classifyLpn(s.lpnNormalized);

            out.add(s);
        }

        out.sort(Comparator.comparing(a -> a.timestamp != null ? a.timestamp.toDate() : new Date(0)));
        return out;
    }

    // =========================
    //  Aggregação (regras novas)
    // =========================
    private DayReport buildReport(String periodoLabel, List<SessionData> sessions) {
        DayReport r = new DayReport();
        r.reportDate = periodoLabel;
        r.sessions = sessions;

        Set<String> positionsVisited = new HashSet<>();
        Set<String> operatorsVisited = new HashSet<>();

        Map<String, PositionSummary> byPosition = new LinkedHashMap<>();
        Map<String, OperatorSummary> byOperator = new LinkedHashMap<>();

        int sessionsVazias = 0;
        int totalScans = 0;
        int totalManual = 0;

        int okScans = 0;
        int invalidScans = 0;

        // Tipos que você disse que agora captura: SECOND e "4º" (que na prática é SSCC/LAST)
        int okSscc = 0;
        int okSecondGs1 = 0;

        Map<String, Integer> lpnOccurrences = new HashMap<>();

        // Agrupamento de exceções
        Map<InvalidReason, Integer> invalidByReason = new LinkedHashMap<>();
        for (InvalidReason ir : InvalidReason.values()) invalidByReason.put(ir, 0);

        // Unicidade:
        // - SSCC: canonicaliza para "00"+18 dígitos (20 chars)
        // - GS1 SECOND: usa normalized inteiro (para estatística, mas não mistura com SSCC)
        Set<String> uniqueSscc = new HashSet<>();
        Set<String> uniqueGs1 = new HashSet<>();

        for (SessionData sess : sessions) {
            String opName = isBlank(sess.operatorName) ? "SEM_OPERADOR" : sess.operatorName;
            operatorsVisited.add(opName);

            String posKey = isBlank(sess.position) ? "SEM_POSICAO" : sess.position;
            if (!"SEM_POSICAO".equals(posKey)) positionsVisited.add(posKey);

            int scansCount = (sess.scans != null) ? sess.scans.size() : 0;
            if (scansCount == 0) sessionsVazias++;

            Date sessStart = sess.startedAt != null ? sess.startedAt.toDate() : null;
            Date sessEnd = sess.finishedAt != null ? sess.finishedAt.toDate() : null;

            PositionSummary ps = byPosition.computeIfAbsent(posKey, PositionSummary::new);
            ps.sessions++;
            ps.operators.add(opName);
            ps.updateMinMax(sessStart, sessEnd);

            OperatorSummary os = byOperator.computeIfAbsent(opName, OperatorSummary::new);
            os.sessions++;
            if (!"SEM_POSICAO".equals(posKey)) os.positions.add(posKey);

            if (sess.scans == null) continue;

            for (ScanData sc : sess.scans) {
                totalScans++;
                if (sc.manual) totalManual++;

                if (sc.kind == LpnKind.OK_SSCC) {
                    okScans++;
                    okSscc++;

                    String canonical = canonicalSscc(sc.lpnNormalized);
                    uniqueSscc.add(canonical);

                    // duplicidade (para SSCC e GS1 separadamente, mas aqui a duplicidade principal é SSCC)
                    lpnOccurrences.put(canonical, lpnOccurrences.getOrDefault(canonical, 0) + 1);

                    ps.okSscc++;
                    os.okSscc++;
                } else if (sc.kind == LpnKind.OK_GS1_SECOND) {
                    okScans++;
                    okSecondGs1++;

                    uniqueGs1.add(sc.lpnNormalized);
                    lpnOccurrences.put("GS1:" + sc.lpnNormalized, lpnOccurrences.getOrDefault("GS1:" + sc.lpnNormalized, 0) + 1);

                    ps.okGs1++;
                    os.okGs1++;
                } else {
                    invalidScans++;
                    ps.invalid++;
                    os.invalid++;

                    InvalidReason reason = inferInvalidReason(sc.lpnNormalized);
                    invalidByReason.put(reason, invalidByReason.getOrDefault(reason, 0) + 1);

                    if (!isBlank(sc.lpnNormalized) && r.invalidSamples.size() < 12) {
                        r.invalidSamples.add(sc.lpnNormalized);
                    }
                }
            }
        }

        // Básicos
        r.totalSessions = sessions.size();
        r.sessionsVazias = sessionsVazias;
        r.totalScans = totalScans;
        r.totalManual = totalManual;

        r.okScans = okScans;
        r.invalidScans = invalidScans;

        r.uniqueSscc = uniqueSscc.size();
        r.uniqueGs1 = uniqueGs1.size();

        r.positionsCount = positionsVisited.size();
        r.operatorsCount = operatorsVisited.size();

        r.okSscc = okSscc;
        r.okGs1Second = okSecondGs1;

        // Duplicadas (mostra só as mais relevantes)
        for (Map.Entry<String, Integer> e : lpnOccurrences.entrySet()) {
            if (e.getValue() != null && e.getValue() > 1) {
                r.duplicatedLpns.put(e.getKey(), e.getValue());
            }
        }

        // Exceções agrupadas
        r.invalidByReason = invalidByReason;

        // Listas
        r.byPosition = new ArrayList<>(byPosition.values());
        // ✅ Ordena por produtividade (OK total)
        r.byPosition.sort((a, b) -> Integer.compare(b.okTotal(), a.okTotal()));

        r.byOperator = new ArrayList<>(byOperator.values());
        r.byOperator.sort((a, b) -> Integer.compare(b.okTotal(), a.okTotal()));

        // Alertas (agora mais úteis)
        if (r.sessionsVazias > 0) r.alerts.add("Sessões sem coleta: " + r.sessionsVazias);
        if (!r.duplicatedLpns.isEmpty()) r.alerts.add("Itens duplicados (SSCC/GS1): " + r.duplicatedLpns.size());
        if (r.invalidScans > 0) r.alerts.add("Scans fora do padrão: " + r.invalidScans);

        return r;
    }

    // =========================
    //  PDF render (mais enxuto)
    // =========================
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

            // 1) EXECUTIVO: painel + alertas (1 página em geral)
            renderPainelExecutivo(document, report);

            // 2) OPERACIONAL: posições e operadores (sem lixo: SEM_POSICAO, etc)
            renderResumoPorPosicao(document, report);
            renderResumoPorOperador(document, report);

            // 3) EXCEÇÕES agrupadas
            renderExcecoes(document, report);

            // 4) ANEXO (opcional e limitado)
            if (INCLUDE_ANEXO_DETALHADO) {
                renderAnexoDetalhes(document, report);
            }

            document.close();

            Toast.makeText(context, "Relatório salvo em Downloads", Toast.LENGTH_LONG).show();
            Log.d(TAG, "PDF OK | sessões=" + report.totalSessions + " | okScans=" + report.okScans);

        } catch (Exception e) {
            Log.e(TAG, "Erro ao gerar PDF", e);
            Toast.makeText(context, "Erro ao gerar PDF", Toast.LENGTH_LONG).show();
            try { if (document != null) document.close(); } catch (Exception ignored) {}
        }
    }

    private void renderCabecalho(Document doc, String periodo) {
        doc.add(new Paragraph("RELATÓRIO DE LPNs")
                .setBold().setFontSize(18)
                .setTextAlignment(TextAlignment.CENTER));

        doc.add(new Paragraph("Período: " + periodo)
                .setTextAlignment(TextAlignment.CENTER));

        doc.add(new Paragraph(" "));
        doc.add(new LineSeparator(new SolidLine()));
        doc.add(new Paragraph(" "));
    }

    /**
     * Painel executivo: métricas úteis + eficiência
     */
    private void renderPainelExecutivo(Document doc, DayReport r) {
        doc.add(tituloSecao("PAINEL DO PERÍODO"));

        int camera = Math.max(0, r.totalScans - r.totalManual);
        double eficiencia = r.totalScans > 0 ? (r.okScans * 100.0 / r.totalScans) : 0.0;

        doc.add(new Paragraph("Scans totais: " + r.totalScans).setBold());
        doc.add(new Paragraph("Scans válidos: " + r.okScans + " | Inválidos: " + r.invalidScans));
        doc.add(new Paragraph("Eficiência: " + String.format(Locale.getDefault(), "%.1f", eficiencia) + "%"));
        doc.add(new Paragraph("Sessões: " + r.totalSessions + " (vazias: " + r.sessionsVazias + ")"));
        doc.add(new Paragraph("Operadores ativos: " + r.operatorsCount + " | Posições atendidas: " + r.positionsCount));
        doc.add(new Paragraph("Método: Manual: " + r.totalManual + " | Câmera: " + camera));

        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("Tipos válidos :").setBold());
        doc.add(new Paragraph("SSCC (AI00): " + r.okSscc + " | Únicos: " + r.uniqueSscc));
        doc.add(new Paragraph("GS1 SECOND (90/37/10): " + r.okGs1Second + " | Únicos: " + r.uniqueGs1));

        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("ALERTAS").setBold());
        if (r.alerts.isEmpty()) {
            doc.add(new Paragraph("Nenhum alerta relevante no período."));
        } else {
            for (String a : r.alerts) doc.add(new Paragraph("• " + a));
        }

        separador(doc);
    }

    /**
     * Resumo por posição:
     * ✅ ignora posições sem scans válidos (evita poluição)
     * ✅ ignora SEM_POSICAO (por padrão)
     */
    private void renderResumoPorPosicao(Document doc, DayReport r) {
        doc.add(tituloSecao("RESUMO POR POSIÇÃO (apenas com leituras válidas)"));

        boolean any = false;

        for (PositionSummary ps : r.byPosition) {
            if ("SEM_POSICAO".equals(ps.position)) continue;
            if (ps.okTotal() <= 0) continue;

            any = true;

            doc.add(new Paragraph(ps.position).setBold());
            doc.add(new Paragraph("OK total: " + ps.okTotal()
                    + " | SSCC: " + ps.okSscc
                    + " | GS1: " + ps.okGs1
                    + " | Inválidos: " + ps.invalid));
            doc.add(new Paragraph("Sessões: " + ps.sessions + " | Operadores: " + ps.operators.size()));
            doc.add(new Paragraph("Início: " + fmtTime(ps.minStart) + " | Fim: " + fmtTime(ps.maxEnd)));
            doc.add(new Paragraph(" "));
        }

        if (!any) {
            doc.add(new Paragraph("Sem posições com leituras válidas no período."));
        }

        separador(doc);
    }

    /**
     * Resumo por operador:
     * ✅ mostra produtividade e taxa de erro
     */
    private void renderResumoPorOperador(Document doc, DayReport r) {
        doc.add(tituloSecao("RESUMO POR OPERADOR"));

        if (r.byOperator.isEmpty()) {
            doc.add(new Paragraph("Sem dados."));
            separador(doc);
            return;
        }

        for (OperatorSummary os : r.byOperator) {
            int okTotal = os.okTotal();
            int total = okTotal + os.invalid;
            double erro = total > 0 ? (os.invalid * 100.0 / total) : 0.0;
            double mediaOkPorSessao = os.sessions > 0 ? (okTotal * 1.0 / os.sessions) : 0.0;

            doc.add(new Paragraph(os.operatorName).setBold());
            doc.add(new Paragraph("OK total: " + okTotal
                    + " | SSCC: " + os.okSscc
                    + " | GS1: " + os.okGs1
                    + " | Inválidos: " + os.invalid
                    + " | Erro: " + String.format(Locale.getDefault(), "%.1f", erro) + "%"));
            doc.add(new Paragraph("Sessões: " + os.sessions
                    + " | Posições: " + os.positions.size()
                    + " | OK/sessão: " + String.format(Locale.getDefault(), "%.2f", mediaOkPorSessao)));
            doc.add(new Paragraph(" "));
        }

        separador(doc);
    }

    /**
     * Exceções:
     * ✅ duplicadas limitadas
     * ✅ inválidos por motivo (agrupado)
     */
    private void renderExcecoes(Document doc, DayReport r) {
        doc.add(tituloSecao("EXCEÇÕES"));

        doc.add(new Paragraph("Sessões sem coleta: " + r.sessionsVazias).setBold());

        doc.add(new Paragraph("Itens duplicados (SSCC/GS1): " + r.duplicatedLpns.size()).setBold());
        if (!r.duplicatedLpns.isEmpty()) {
            int shown = 0;
            for (Map.Entry<String, Integer> e : r.duplicatedLpns.entrySet()) {
                doc.add(new Paragraph("• " + e.getKey() + " (" + e.getValue() + "x)"));
                if (++shown >= 12) {
                    int rest = r.duplicatedLpns.size() - shown;
                    if (rest > 0) doc.add(new Paragraph("... (mais " + rest + ")"));
                    break;
                }
            }
        }

        doc.add(new Paragraph("Scans fora do padrão: " + r.invalidScans).setBold());
        if (r.invalidScans > 0) {

            doc.add(new Paragraph("Por motivo:").setBold());
            for (Map.Entry<InvalidReason, Integer> e : r.invalidByReason.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                doc.add(new Paragraph("• " + e.getKey().label + ": " + e.getValue()));
            }

            if (!r.invalidSamples.isEmpty()) {
                doc.add(new Paragraph(" "));
                doc.add(new Paragraph("Amostras (até 12):").setBold());
                for (String s : r.invalidSamples) doc.add(new Paragraph("• " + s));
            }
        }

        separador(doc);
    }

    /**
     * Anexo:
     * ✅ só entra se flag ligada
     * ✅ limita total de linhas no PDF
     */
    private void renderAnexoDetalhes(Document doc, DayReport r) {
        doc.add(tituloSecao("ANEXO - DETALHAMENTO (limitado)"));

        int printed = 0;

        for (SessionData sess : r.sessions) {
            if (sess.scans == null || sess.scans.isEmpty()) continue;
            if (printed >= MAX_ANEXO_SCANS_TOTAL) break;

            doc.add(new Paragraph("Operador: " + safeOr(sess.operatorName, "SEM_OPERADOR")).setBold());
            doc.add(new Paragraph("Posição: " + safeOr(sess.position, "SEM_POSICAO")));
            doc.add(new Paragraph("Início: " + fmtTime(sess.startedAt != null ? sess.startedAt.toDate() : null)
                    + " | Fim: " + fmtTime(sess.finishedAt != null ? sess.finishedAt.toDate() : null)));

            doc.add(new Paragraph("Scans:").setBold());

            for (ScanData sc : sess.scans) {
                if (printed >= MAX_ANEXO_SCANS_TOTAL) break;

                String hora = sc.timestamp != null ? hourFormat.format(sc.timestamp.toDate()) : "--";
                String metodo = sc.manual ? "MANUAL" : "CAMERA";
                String status = (sc.kind == LpnKind.INVALID) ? "FORA_PADRAO" : "OK";
                String tipo = isBlank(sc.scanType) ? "" : (" | " + sc.scanType);

                String kindLabel = sc.kind == LpnKind.OK_SSCC ? "SSCC" :
                        (sc.kind == LpnKind.OK_GS1_SECOND ? "GS1" : "INVALID");

                doc.add(new Paragraph("• " + hora + " | " + metodo + " | " + status
                        + " | " + kindLabel + tipo + " | " + sc.lpnNormalized));

                printed++;
            }

            doc.add(new Paragraph(" "));
        }

        if (printed >= MAX_ANEXO_SCANS_TOTAL) {
            doc.add(new Paragraph("... anexo truncado (limite de " + MAX_ANEXO_SCANS_TOTAL + " linhas).")
                    .setItalic());
        }
    }

    // =========================
    //  Helpers PDF/Storage
    // =========================
    private Uri criarArquivoPDF(String periodoLabel) {
        String safe = periodoLabel.replace("/", "-").replace(" ", "_");
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Relatorio_LPN_" + safe + ".pdf");
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

    /**
     * Normalização:
     * - remove espaços e parênteses
     * - converte separador GS real (ASCII 29) para [GS] (pra ficar legível no PDF)
     * - mantém dígitos e marcas de GS1 quando existirem
     */
    private String normalizeScan(String raw) {
        if (raw == null) return "";
        String s = raw.trim();

        // GS (FNC1) costuma virar ASCII 29 no texto
        s = s.replace("\u001D", "[GS]");

        s = s.replaceAll("\\s+", "");
        s = s.replace("(", "").replace(")", "");
        s = s.replace("<", "").replace(">", "");

        // alguns casos do seu log tinham prefixos como "BC2:" e pipes
        // mantemos para diagnosticar, mas a validação vai reprovar
        return s.toUpperCase(Locale.ROOT);
    }

    // =========================
    //  Nova regra de validação (SSCC + GS1 SECOND)
    // =========================
    private enum LpnKind { OK_SSCC, OK_GS1_SECOND, INVALID }

    /**
     * SSCC válido:
     * - 18 dígitos (SSCC puro)
     * - OU 20 dígitos começando com "00" (AI 00 + SSCC)
     */
    private boolean isSsccOk(String s) {
        if (isBlank(s)) return false;

        String digits = onlyDigits(s);
        if (digits.length() == 18) return true;
        if (digits.length() == 20 && digits.startsWith("00")) return true;

        // se tem letras/prefixos, já não é SSCC "limpo"
        return false;
    }

    /**
     * GS1 SECOND (sua "2ª leitura"):
     * Aqui a ideia é aceitar a cadeia GS1 que começa em 90 e contém AIs 37 e 10.
     * Pode vir com [GS] ou sem (concatenada).
     *
     * Exemplos do seu relatório antigo:
     *  - 9069799215[GS]37000013[GS]1000000000000811521115
     *  - 9069799215370000131000000000000811521115
     */
    private boolean isGs1SecondOk(String s) {
        if (isBlank(s)) return false;

        // forma "bonita" com separador
        String normalized = s.replace("[GS]", "\u001D"); // só pra checar presença lógica (não obrigatório)

        // remove separadores pra checar cadeia
        String compact = s.replace("[GS]", "");
        compact = compact.replace("\u001D", "");

        // deve ser digits-only após compactar (caso contrário é inválido)
        if (!compact.matches("^\\d+$")) return false;

        // regra mínima: começa com 90 e contém 37 e 10 depois
        if (!compact.startsWith("90")) return false;

        int idx37 = compact.indexOf("37", 2);
        int idx10 = compact.indexOf("10", 2);

        // tem que existir e estar na ordem
        if (idx37 < 0 || idx10 < 0) return false;
        if (!(idx37 < idx10)) return false;

        // sanity: pelo menos algo depois do 10 (lote)
        if (idx10 + 2 >= compact.length()) return false;

        return true;
    }

    private LpnKind classifyLpn(String normalized) {
        if (isSsccOk(normalized)) return LpnKind.OK_SSCC;
        if (isGs1SecondOk(normalized)) return LpnKind.OK_GS1_SECOND;
        return LpnKind.INVALID;
    }

    /**
     * Canonical SSCC:
     * - se veio 18 dígitos: retorna "00"+sscc
     * - se veio 20 com "00": mantém
     */
    private String canonicalSscc(String normalized) {
        String d = onlyDigits(normalized);
        if (d.length() == 18) return "00" + d;
        if (d.length() == 20 && d.startsWith("00")) return d;
        return normalized; // fallback (não deveria acontecer se classificado como OK_SSCC)
    }

    private String onlyDigits(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D+", "");
    }

    private enum InvalidReason {
        VAZIO("Vazio"),
        TEM_LETRAS_OU_PREFIXO("Contém letras/prefixos"),
        TAMANHO_INVALIDO("Tamanho inválido"),
        NAO_BATE_REGRA_GS1("Não bate regra GS1 (90/37/10)");

        final String label;
        InvalidReason(String label) { this.label = label; }
    }

    private InvalidReason inferInvalidReason(String normalized) {
        if (isBlank(normalized)) return InvalidReason.VAZIO;

        // se tem coisas tipo "BC2:" ou letras
        if (!normalized.matches("^[0-9\\[\\]GS\\u001D:|]+$")) {
            return InvalidReason.TEM_LETRAS_OU_PREFIXO;
        }
        if (normalized.contains("BC") || normalized.contains(":") || normalized.contains("|")) {
            return InvalidReason.TEM_LETRAS_OU_PREFIXO;
        }

        // se parece digits-only (tirando separador) mas tamanho não encaixa
        String compact = normalized.replace("[GS]", "").replace("\u001D", "");
        if (compact.matches("^\\d+$")) {
            // SSCC?
            String d = compact;
            if (!(d.length() == 18 || (d.length() == 20 && d.startsWith("00")))) {
                // talvez fosse GS1, mas não bateu regra
                if (d.startsWith("90")) return InvalidReason.NAO_BATE_REGRA_GS1;
                return InvalidReason.TAMANHO_INVALIDO;
            }
        }

        // se começa com 90 e não bateu a regra do GS1
        if (compact.startsWith("90")) return InvalidReason.NAO_BATE_REGRA_GS1;

        return InvalidReason.TAMANHO_INVALIDO;
    }

    // =========================
    //  Models internos
    // =========================
    private static class SessionData {
        String sessionId;
        String operatorId;
        String operatorName;
        String position;
        Timestamp startedAt;
        Timestamp finishedAt;
        Integer totalScans;
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

    private static class PositionSummary {
        final String position;
        int sessions = 0;
        final Set<String> operators = new HashSet<>();
        Date minStart = null;
        Date maxEnd = null;

        int okSscc = 0;
        int okGs1 = 0;
        int invalid = 0;

        PositionSummary(String position) {
            this.position = position;
        }

        int okTotal() { return okSscc + okGs1; }

        void updateMinMax(@Nullable Date start, @Nullable Date end) {
            if (start != null && (minStart == null || start.before(minStart))) minStart = start;
            if (end != null && (maxEnd == null || end.after(maxEnd))) maxEnd = end;
            if (end == null && start != null && (maxEnd == null || start.after(maxEnd))) maxEnd = start;
        }
    }

    private static class OperatorSummary {
        final String operatorName;
        int sessions = 0;
        final Set<String> positions = new HashSet<>();

        int okSscc = 0;
        int okGs1 = 0;
        int invalid = 0;

        OperatorSummary(String operatorName) {
            this.operatorName = operatorName;
        }

        int okTotal() { return okSscc + okGs1; }
    }

    private static class DayReport {
        String reportDate;

        int totalSessions;
        int sessionsVazias;
        int totalScans;
        int totalManual;

        int okScans;
        int invalidScans;

        int uniqueSscc;
        int uniqueGs1;

        int positionsCount;
        int operatorsCount;

        int okSscc;
        int okGs1Second;

        final List<String> invalidSamples = new ArrayList<>();
        final Map<String, Integer> duplicatedLpns = new LinkedHashMap<>();
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
            r.okScans = 0;
            r.invalidScans = 0;
            r.uniqueSscc = 0;
            r.uniqueGs1 = 0;
            r.positionsCount = 0;
            r.operatorsCount = 0;
            r.okSscc = 0;
            r.okGs1Second = 0;

            for (InvalidReason ir : InvalidReason.values()) r.invalidByReason.put(ir, 0);
            return r;
        }
    }
}