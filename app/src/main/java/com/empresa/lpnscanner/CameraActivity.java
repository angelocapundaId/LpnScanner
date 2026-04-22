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
import com.google.common.util.concurrent.ListenableFuture;
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
import java.util.regex.Pattern;

public class CameraActivity extends AppCompatActivity {

    private static final Pattern POSITION_PATTERN = Pattern.compile("^[A-Z]{2}[0-9]{7}$");

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

    private String scanMode = MainActivity.MODE_LPN;

    private final ActivityResultLauncher<String> camPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
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

        scanMode = getIntent().getStringExtra(MainActivity.EXTRA_SCAN_MODE);
        if (scanMode == null || scanMode.trim().isEmpty()) {
            scanMode = MainActivity.MODE_LPN;
        }

        configureScanner();
        configureHintByMode();

        btnBack.setOnClickListener(v -> finishAndReturn());
        btnFlash.setOnClickListener(v -> toggleTorch());

        checkAndRequestPermission();
    }

    private void configureScanner() {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128)
                .build();

        scanner = BarcodeScanning.getClient(options);
    }

    private void configureHintByMode() {
        if (MainActivity.MODE_POSITION.equals(scanMode)) {
            tvHint.setText("Aponte a câmera para o código de barras da posição");
        } else {
            tvHint.setText("Aponte a câmera para o código SSCC");
        }
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
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
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

    private void handleBarcodes(List<Barcode> barcodes) {
        if (finishing || barcodes == null || barcodes.isEmpty()) {
            return;
        }

        if (MainActivity.MODE_POSITION.equals(scanMode)) {
            handlePositionBarcodes(barcodes);
        } else {
            handleLpnBarcodes(barcodes);
        }
    }

    private void handlePositionBarcodes(List<Barcode> barcodes) {
        for (Barcode bc : barcodes) {
            String raw = bc.getRawValue();
            if (raw == null) continue;

            String normalized = normalizePosition(raw);
            if (!isValidPosition(normalized)) continue;

            finishWithPosition(normalized);
            return;
        }
    }

    private void handleLpnBarcodes(List<Barcode> barcodes) {
        for (Barcode bc : barcodes) {
            String raw = bc.getRawValue();
            if (raw == null) continue;

            String normalized = normalizeBarcodeValue(raw);
            if (normalized.isEmpty()) continue;
            if (isAllZerosDigits(normalized)) continue;

            String sscc = extractAi00(normalized);
            if (sscc == null) continue;

            addIfNew(sscc);
            finishAndReturn();
            return;
        }
    }

    private String normalizeBarcodeValue(String value) {
        value = value.replace("\u001D", "");
        if (value.startsWith("]C1")) {
            value = value.substring(3);
        }

        return value.toUpperCase(Locale.ROOT)
                .replaceAll("[^0-9A-Z()<>]", "")
                .trim();
    }

    private String normalizePosition(String value) {
        if (value == null) return "";
        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
    }

    private boolean isValidPosition(String value) {
        if (value == null || value.isEmpty()) return false;

        // Exemplo esperado: AR3010121
        return POSITION_PATTERN.matcher(value).matches();
    }

    private String extractAi00(String value) {
        String digitsOnly = value.replaceAll("\\D+", "");

        if (digitsOnly.length() >= 20 && digitsOnly.startsWith("00")) {
            return digitsOnly.substring(0, 20);
        }

        int idx = value.indexOf("(00)");
        if (idx >= 0) {
            String tail = value.substring(idx + 4).replaceAll("\\D+", "");
            if (tail.length() >= 18) {
                return "00" + tail.substring(0, 18);
            }
        }

        idx = value.indexOf("<00>");
        if (idx >= 0) {
            String tail = value.substring(idx + 4).replaceAll("\\D+", "");
            if (tail.length() >= 18) {
                return "00" + tail.substring(0, 18);
            }
        }

        return null;
    }

    private boolean isAllZerosDigits(String value) {
        String digits = value.replaceAll("\\D+", "");
        if (digits.isEmpty()) return true;

        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) != '0') return false;
        }
        return true;
    }

    private void addIfNew(String code) {
        if (seen.add(code)) {
            collected.clear();
            collected.add(code);
            Toast.makeText(this, "SSCC coletado: " + code, Toast.LENGTH_SHORT).show();
        }
    }

    private void finishWithPosition(String position) {
        if (finishing) return;
        finishing = true;

        Intent data = new Intent();
        data.putExtra(MainActivity.EXTRA_SCAN_MODE, MainActivity.MODE_POSITION);
        data.putExtra(MainActivity.EXTRA_POSITION, position);
        setResult(RESULT_OK, data);
        finish();
    }

    private void finishAndReturn() {
        if (finishing) return;
        finishing = true;

        Intent data = new Intent();

        if (MainActivity.MODE_POSITION.equals(scanMode)) {
            setResult(RESULT_CANCELED, data);
        } else {
            data.putExtra(MainActivity.EXTRA_SCAN_MODE, MainActivity.MODE_LPN);
            data.putStringArrayListExtra(MainActivity.EXTRA_LPNS, collected);
            setResult(RESULT_OK, data);
        }

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