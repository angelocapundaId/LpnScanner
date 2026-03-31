package com.empresa.lpnscanner.reports;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;

import com.empresa.lpnscanner.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class ReportsActivity extends AppCompatActivity {

    private MaterialButton btnPickRange;

    private MaterialButton btnGeneratePdfPeriod, btnGeneratePdfToday;
    private MaterialButton btnGenerateCsvPeriod, btnGenerateCsvToday;

    private TextView tvRange;

    private String dataInicio; // dd/MM/yyyy
    private String dataFim;    // dd/MM/yyyy

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());

    private DailyReportsPDF reportsPdf;
    private DailyReportsCSV reportsCsv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reports);

        btnPickRange = findViewById(R.id.btnPickRange);

        btnGeneratePdfPeriod = findViewById(R.id.btnGeneratePdfPeriod);
        btnGeneratePdfToday  = findViewById(R.id.btnGeneratePdfToday);

        btnGenerateCsvPeriod = findViewById(R.id.btnGenerateCsvPeriod);
        btnGenerateCsvToday  = findViewById(R.id.btnGenerateCsvToday);

        tvRange = findViewById(R.id.tvRange);

        // Evita “um dia antes” (timezone)
        dateFormat.setTimeZone(TimeZone.getDefault());

        reportsPdf = new DailyReportsPDF(this);
        reportsCsv = new DailyReportsCSV(this);

        updateRangeText();

        btnPickRange.setOnClickListener(v -> openDateRangePicker());

        // ===== PDF =====
        btnGeneratePdfPeriod.setOnClickListener(v -> {
            if (!hasRange()) return;
            reportsPdf.gerarRelatorioPeriodo(dataInicio, dataFim);
        });

        btnGeneratePdfToday.setOnClickListener(v -> reportsPdf.gerarRelatorioDiario());

        // ===== CSV =====
        btnGenerateCsvPeriod.setOnClickListener(v -> {
            if (!hasRange()) return;
            reportsCsv.gerarCsvPeriodo(dataInicio, dataFim);
        });

        btnGenerateCsvToday.setOnClickListener(v -> reportsCsv.gerarCsvDiario());
    }

    private boolean hasRange() {
        if (dataInicio == null || dataFim == null) {
            Toast.makeText(this, "Selecione o período primeiro.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void updateRangeText() {
        if (dataInicio == null || dataFim == null) {
            tvRange.setText("Período: (não selecionado)");
        } else {
            tvRange.setText("Período: " + dataInicio + " a " + dataFim);
        }
    }

    private void openDateRangePicker() {
        MaterialDatePicker.Builder<Pair<Long, Long>> builder =
                MaterialDatePicker.Builder.dateRangePicker()
                        .setTitleText("Selecione o período do relatório");

        if (dataInicio != null && dataFim != null) {
            try {
                long start = dateFormat.parse(dataInicio).getTime();
                long end = dateFormat.parse(dataFim).getTime();
                builder.setSelection(new Pair<>(start, end));
            } catch (Exception ignored) {}
        }

        MaterialDatePicker<Pair<Long, Long>> picker = builder.build();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null || selection.first == null || selection.second == null) return;

            dataInicio = dateFormat.format(new Date(selection.first));
            dataFim = dateFormat.format(new Date(selection.second));
            updateRangeText();
        });

        picker.show(getSupportFragmentManager(), "DATE_RANGE_PICKER");
    }
}