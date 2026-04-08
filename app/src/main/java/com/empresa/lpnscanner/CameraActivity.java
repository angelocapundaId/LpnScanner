package com.empresa.lpnscanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {

    public static final String EXTRA_LPNS = "EXTRA_LPNS";

    private PreviewView previewView;
    private MaterialButton btnBack, btnFlash;
    private TextView tvHint;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageAnalysis analysis;

    private Executor mainExecutor;
    private ExecutorService cameraExecutor;

    private BarcodeScanner scanner;

    private final ArrayList<String> collected = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    private boolean finishing = false;
    private boolean torchOn = false;

    private final ActivityResultLauncher<String> camPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else {
                    Toast.makeText(this, "Permissão de câmera negada", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView = findViewById(R.id.previewView);
        btnBack = findViewById(R.id.btnBack);
        btnFlash = findViewById(R.id.btnFlash);
        tvHint = findViewById(R.id.tvHint);

        mainExecutor = ContextCompat.getMainExecutor(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128)
                .build();
        scanner = BarcodeScanning.getClient(options);

        btnBack.setOnClickListener(v -> finishAndReturn());
        btnFlash.setOnClickListener(v -> toggleTorch());

        tvHint.setText("Aponte a câmera para o código SSCC");

        checkAndRequestPermission();
    }

    private void checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            camPermLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Erro iniciando câmera: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, mainExecutor);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void bindUseCases() {
        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(this, selector, preview, analysis);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (finishing) {
            imageProxy.close();
            return;
        }

        try {
            if (imageProxy.getImage() == null) {
                imageProxy.close();
                return;
            }

            InputImage image = InputImage.fromMediaImage(
                    imageProxy.getImage(),
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            scanner.process(image)
                    .addOnSuccessListener(this::handleBarcodes)
                    .addOnCompleteListener(task -> imageProxy.close());

        } catch (Exception e) {
            imageProxy.close();
        }
    }

    /**
     * NOVA REGRA:
     * - Captura somente o SSCC (AI 00)
     * - Ignora os demais códigos
     * - Finaliza assim que encontrar o primeiro SSCC válido
     */
    private void handleBarcodes(List<Barcode> barcodes) {
        if (finishing || barcodes == null) return;

        for (Barcode bc : barcodes) {
            if (bc.getRawValue() == null) continue;

            String norm = normalize(bc.getRawValue());

            if (norm.isEmpty()) continue;
            if (isAllZerosDigits(norm)) continue;

            String sscc = extractAi00(norm);
            if (sscc == null) continue;

            addIfNew(sscc);
            finishAndReturn();
            return;
        }
    }

    private String normalize(String value) {
        value = value.replace("\u001D", "");
        if (value.startsWith("]C1")) value = value.substring(3);
        return value.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z()<>]", "").trim();
    }

    /**
     * Extrai somente o AI 00 (SSCC).
     * Exemplos aceitos:
     * (00)378911507000035230
     * 00378911507000035230
     * <00>378911507000035230
     */
    private String extractAi00(String s) {
        String digitsOnly = s.replaceAll("\\D+", "");

        // Caso venha no formato puro: 00 + 18 dígitos
        if (digitsOnly.length() >= 20 && digitsOnly.startsWith("00")) {
            return digitsOnly.substring(0, 20);
        }

        // Caso venha com marcador (00)
        int idx = s.indexOf("(00)");
        if (idx >= 0) {
            String tail = s.substring(idx + 4).replaceAll("\\D+", "");
            if (tail.length() >= 18) {
                return "00" + tail.substring(0, 18);
            }
        }

        // Caso venha com marcador <00>
        idx = s.indexOf("<00>");
        if (idx >= 0) {
            String tail = s.substring(idx + 4).replaceAll("\\D+", "");
            if (tail.length() >= 18) {
                return "00" + tail.substring(0, 18);
            }
        }

        return null;
    }

    private boolean isAllZerosDigits(String s) {
        String digits = s.replaceAll("\\D+", "");
        if (digits.isEmpty()) return true;
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) != '0') return false;
        }
        return true;
    }

    private void addIfNew(String code) {
        if (seen.add(code)) {
            collected.clear(); // garante que só haverá 1 retorno
            collected.add(code);
            Toast.makeText(this, "SSCC coletado: " + code, Toast.LENGTH_SHORT).show();
        }
    }

    private void finishAndReturn() {
        if (finishing) return;
        finishing = true;

        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_LPNS, collected);
        setResult(RESULT_OK, data);
        finish();
    }

    private void toggleTorch() {
        if (camera == null) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) scanner.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}