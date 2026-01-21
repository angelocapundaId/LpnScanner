package com.empresa.lpnscanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
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

    public static final String EXTRA_LPN = "EXTRA_LPN";
    public static final String EXTRA_LPNS = "EXTRA_LPNS";
    public static final String EXTRA_CONTINUOUS = "EXTRA_CONTINUOUS";

    private PreviewView previewView;
    private MaterialButton btnBack, btnFlash;
    private TextView tvHint;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageAnalysis analysis;

    private boolean torchOn = false;
    private Executor mainExecutor;
    private ExecutorService cameraExecutor;

    // ML Kit
    private BarcodeScanner scanner;

    // Coleta contínua
    private final ArrayList<String> collected = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    // Permission launcher
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
        btnBack = findViewById(R.id.btnBack);   // “Concluir”
        btnFlash = findViewById(R.id.btnFlash);
        tvHint = findViewById(R.id.tvHint);

        mainExecutor = ContextCompat.getMainExecutor(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Apenas CODE_128 (GS1-128)
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128)
                .build();
        scanner = BarcodeScanning.getClient(options);

        // Concluir: devolve TODAS as LPNs
        btnBack.setOnClickListener(v -> {
            Intent data = new Intent();
            data.putStringArrayListExtra(EXTRA_LPNS, collected);
            setResult(RESULT_OK, data);
            finish();
        });

        btnFlash.setOnClickListener(v -> toggleTorch());
        tvHint.setText("Aponte a câmera para a etiqueta LPN • 0 coletadas");

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
        if (cameraProvider == null) return;

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

        boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
        btnFlash.setEnabled(hasFlash);
        if (!hasFlash) btnFlash.setText("Sem lanterna");
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        try {
            if (imageProxy.getImage() == null) { imageProxy.close(); return; }
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), rotation);

            scanner.process(image)
                    .addOnSuccessListener(this::handleBarcodes)
                    .addOnFailureListener(e -> { /* ignora e segue lendo */ })
                    .addOnCompleteListener(t -> imageProxy.close());

        } catch (Exception e) {
            imageProxy.close();
        }
    }

    /** Mantém leitura contínua e acumula SSCCs únicos (AI 00). */
    private void handleBarcodes(List<Barcode> barcodes) {
        if (barcodes == null || barcodes.isEmpty()) return;

        String best = null;
        float bestScore = -1f;

        for (Barcode bc : barcodes) {
            String raw = bc.getRawValue();
            String normalized = normalizeGs1(raw);
            String sscc = extractSsccAi00(normalized);
            if (sscc == null) continue;

            float score = 0f;
            if (bc.getBoundingBox() != null) {
                score = bc.getBoundingBox().width() * bc.getBoundingBox().height();
            }
            if (score > bestScore) { bestScore = score; best = sscc; }
        }

        if (best != null) addIfNew(best);
    }

    private void addIfNew(String lpn) {
        String key = lpn == null ? "" : lpn.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) return;

        // Se já foi lida nesta sessão de câmera: avisa "já coletada"
        if (!seen.add(key)) {
            runOnUiThread(() ->
                    Toast.makeText(CameraActivity.this, "LPN já coletada: " + key, Toast.LENGTH_SHORT).show());
            return;
        }

        // Nova leitura: adiciona, atualiza contador e avisa "coletada"
        collected.add(0, key);
        runOnUiThread(() -> {
            tvHint.setText("Aponte a câmera para a etiqueta LPN • " + collected.size() + " coletadas");
            Toast.makeText(CameraActivity.this, "LPN coletada: " + key, Toast.LENGTH_SHORT).show();
        });

        // Feedback sonoro/tátil
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(60);
                }
            }
        } catch (Exception ignored) {}
    }

    /** Remove FNC1 (\u001D), prefixos e lixo; mantém A-Z0-9() */
    private String normalizeGs1(String value) {
        if (value == null) return null;
        value = value.replace("\u001D", ""); // FNC1
        if (value.startsWith("]C1") || value.startsWith("]E0") || value.startsWith("]d2")) {
            value = value.substring(3);
        }
        value = value.toUpperCase().replaceAll("[^0-9A-Z()]", "");
        return value.trim();
    }

    /** Extrai SSCC do AI (00): 18 dígitos. */
    private String extractSsccAi00(String gs1) {
        if (gs1 == null) return null;

        var m1 = java.util.regex.Pattern.compile("\\(00\\)(\\d{18})").matcher(gs1);
        if (m1.find()) return m1.group(1);

        var m2 = java.util.regex.Pattern.compile("^00(\\d{18})$").matcher(gs1);
        if (m2.find()) return m2.group(1);

        return null;
    }

    private void toggleTorch() {
        if (camera == null) return;
        boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
        if (!hasFlash) {
            Toast.makeText(this, "Dispositivo sem lanterna", Toast.LENGTH_SHORT).show();
            return;
        }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        btnFlash.setText(torchOn ? "Lanterna: ON" : "Lanterna");
    }

    @Override protected void onPause() {
        super.onPause();
        if (camera != null) camera.getCameraControl().enableTorch(false);
        torchOn = false;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { if (scanner != null) scanner.close(); } catch (Exception ignored) {}
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
