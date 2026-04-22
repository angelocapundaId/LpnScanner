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

    private MaterialButton btnGeneratePdfPeriod;
    private MaterialButton btnGeneratePdfToday;

    private MaterialButton btnGenerateCsvPeriod;
    private MaterialButton btnGenerateCsvToday;

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

        initViews();
        initServices();
        configureActions();
        updateRangeText();
    }

    private void initViews() {
        btnPickRange = findViewById(R.id.btnPickRange);

        btnGeneratePdfPeriod = findViewById(R.id.btnGeneratePdfPeriod);
        btnGeneratePdfToday = findViewById(R.id.btnGeneratePdfToday);

        btnGenerateCsvPeriod = findViewById(R.id.btnGenerateCsvPeriod);
        btnGenerateCsvToday = findViewById(R.id.btnGenerateCsvToday);

        tvRange = findViewById(R.id.tvRange);
    }

    private void initServices() {
        // Evita problema de um dia anterior por timezone
        dateFormat.setTimeZone(TimeZone.getDefault());

        reportsPdf = new DailyReportsPDF(this);
        reportsCsv = new DailyReportsCSV(this);
    }

    private void configureActions() {
        btnPickRange.setOnClickListener(v -> openDateRangePicker());

        btnGeneratePdfToday.setOnClickListener(v ->
                reportsPdf.gerarRelatorioDiario()
        );

        btnGeneratePdfPeriod.setOnClickListener(v -> {
            if (!hasRange()) return;
            reportsPdf.gerarRelatorioPeriodo(dataInicio, dataFim);
        });

        btnGenerateCsvToday.setOnClickListener(v ->
                reportsCsv.gerarCsvDiario()
        );

        btnGenerateCsvPeriod.setOnClickListener(v -> {
            if (!hasRange()) return;
            reportsCsv.gerarCsvPeriodo(dataInicio, dataFim);
        });
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
            tvRange.setText("Período do relatório: não selecionado");
        } else {
            tvRange.setText("Período do relatório: " + dataInicio + " a " + dataFim);
        }
    }

    private void openDateRangePicker() {
        MaterialDatePicker.Builder<Pair<Long, Long>> builder =
                MaterialDatePicker.Builder.dateRangePicker()
                        .setTitleText("Selecione o período da operação");

        if (dataInicio != null && dataFim != null) {
            try {
                Date startDate = dateFormat.parse(dataInicio);
                Date endDate = dateFormat.parse(dataFim);

                if (startDate != null && endDate != null) {
                    builder.setSelection(new Pair<>(startDate.getTime(), endDate.getTime()));
                }
            } catch (Exception ignored) {
            }
        }

        MaterialDatePicker<Pair<Long, Long>> picker = builder.build();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null || selection.first == null || selection.second == null) {
                return;
            }

            dataInicio = dateFormat.format(new Date(selection.first));
            dataFim = dateFormat.format(new Date(selection.second));
            updateRangeText();
        });

        picker.show(getSupportFragmentManager(), "DATE_RANGE_PICKER");
    }
}