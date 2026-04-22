package com.empresa.lpnscanner;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.empresa.lpnscanner.reports.ReportsActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_SCAN_MODE = "EXTRA_SCAN_MODE";
    public static final String EXTRA_POSITION = "EXTRA_POSITION";
    public static final String EXTRA_LPNS = "EXTRA_LPNS";

    public static final String MODE_POSITION = "MODE_POSITION";
    public static final String MODE_LPN = "MODE_LPN";

    private TextInputEditText etManualLpn;
    private MaterialButton btnReadPosition, btnOpenCamera, btnAddManual, btnLogout, btnFinishCollection;
    private Button btnGerarRelatorio;
    private RecyclerView rvLpns;
    private TextView tvOperator, tvStatus, tvLpnCount;

    private LpnListAdapter adapter;
    private final ArrayList<LpnItem> items = new ArrayList<>();
    private final Set<String> scannedSet = new LinkedHashSet<>();
    private final List<Map<String, Object>> pendingScans = new ArrayList<>();

    private FirebaseFirestore db;
    private String sessionId;
    private String operatorId = "";
    private String operatorName = "";
    private SharedPreferences sp;
    private SharedPreferences spCleanup;
    private boolean navigatingAway = false;

    private boolean operationActive = false;
    private String expectedMode = MODE_POSITION;
    private String currentPosition = "";

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                navigatingAway = false;

                if (!operationActive) {
                    return;
                }

                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    Toast.makeText(this, "Leitura cancelada.", Toast.LENGTH_SHORT).show();
                    updateStatus();
                    return;
                }

                Intent data = result.getData();
                String mode = data.getStringExtra(EXTRA_SCAN_MODE);

                if (MODE_POSITION.equals(mode)) {
                    String scannedPosition = data.getStringExtra(EXTRA_POSITION);
                    handleScannedPosition(scannedPosition);

                    if (operationActive) {
                        expectedMode = MODE_LPN;
                        updateStatus();
                        launchScanner(expectedMode);
                    }
                    return;
                }

                if (MODE_LPN.equals(mode)) {
                    ArrayList<String> lpns = data.getStringArrayListExtra(EXTRA_LPNS);

                    if (lpns != null && !lpns.isEmpty()) {
                        String sscc = lpns.get(0);
                        handleScannedLpn(sscc);
                    }

                    if (operationActive) {
                        expectedMode = MODE_POSITION;
                        updateStatus();
                        launchScanner(expectedMode);
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etManualLpn = findViewById(R.id.etManualLpn);
        btnReadPosition = findViewById(R.id.btnReadPosition);
        btnOpenCamera = findViewById(R.id.btnOpenCamera);
        btnAddManual = findViewById(R.id.btnAddManual);
        btnLogout = findViewById(R.id.btnLogout);
        btnFinishCollection = findViewById(R.id.btnFinishCollection);
        rvLpns = findViewById(R.id.rvLpns);
        tvOperator = findViewById(R.id.tvOperator);
        tvStatus = findViewById(R.id.tvStatus);
        tvLpnCount = findViewById(R.id.tvLpnCount);
        btnGerarRelatorio = findViewById(R.id.btnGerarRelatorio);

        FirebaseApp.initializeApp(this);
        db = FirebaseFirestore.getInstance();

        sp = getSharedPreferences("session", MODE_PRIVATE);
        spCleanup = getSharedPreferences("session_cleanup", MODE_PRIVATE);

        operatorId = sp.getString("op_id", getIntent().getStringExtra("EXTRA_OPERATOR_ID"));
        operatorName = sp.getString("op_name", getIntent().getStringExtra("EXTRA_OPERATOR_NAME"));

        if (operatorId == null) operatorId = "";
        if (operatorName == null) operatorName = "";

        renderOperatorHeader();
        configureNewOperationUI();
        configureList();
        configureActions();
        updateStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (sp == null) {
            sp = getSharedPreferences("session", MODE_PRIVATE);
        }

        String sid = sp.getString("op_id", null);
        String sname = sp.getString("op_name", null);

        if ((sid == null || sid.isEmpty()) && (sname == null || sname.isEmpty())) {
            navigatingAway = true;
            Intent it = new Intent(MainActivity.this, LoginActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        navigatingAway = false;
    }

    private void configureNewOperationUI() {
        btnReadPosition.setText("Iniciar operação");
        btnFinishCollection.setText("Terminar / Fechar operação");

        btnOpenCamera.setVisibility(View.GONE);
        btnAddManual.setVisibility(View.GONE);
        etManualLpn.setVisibility(View.GONE);
    }

    private void configureList() {
        rvLpns.setLayoutManager(new LinearLayoutManager(this));

        adapter = new LpnListAdapter(items, (position, item) -> {
            if (operationActive) {
                Toast.makeText(this, "Não remova leituras com a operação em andamento.", Toast.LENGTH_SHORT).show();
                adapter.notifyItemChanged(position);
                return;
            }

            if (position >= 0 && position < items.size()) {
                items.remove(position);
                scannedSet.remove(normalize(item.lpn));
                if (position < pendingScans.size()) {
                    pendingScans.remove(position);
                }
                adapter.notifyItemRemoved(position);
                updateStatus();
                persistSessionSnapshot();
                Toast.makeText(MainActivity.this, "Leitura removida", Toast.LENGTH_SHORT).show();
            }
        });
        rvLpns.setAdapter(adapter);

        ItemTouchHelper swipeHelper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
                    @Override
                    public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                        return false;
                    }

                    @Override
                    public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                        int pos = viewHolder.getBindingAdapterPosition();

                        if (operationActive) {
                            adapter.notifyItemChanged(pos);
                            Toast.makeText(MainActivity.this, "Não remova leituras com a operação em andamento.", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        if (pos >= 0 && pos < items.size()) {
                            LpnItem removed = items.remove(pos);
                            scannedSet.remove(normalize(removed.lpn));
                            if (pos < pendingScans.size()) {
                                pendingScans.remove(pos);
                            }
                            adapter.notifyItemRemoved(pos);
                            updateStatus();
                            persistSessionSnapshot();
                            Toast.makeText(MainActivity.this, "Leitura removida", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        swipeHelper.attachToRecyclerView(rvLpns);
    }

    private void configureActions() {
        btnReadPosition.setOnClickListener(v -> startOperation());
        btnFinishCollection.setOnClickListener(v -> finishOperation());

        btnLogout.setOnClickListener(v -> {
            navigatingAway = true;

            if (operationActive) {
                Toast.makeText(this, "Finalize a operação antes de sair.", Toast.LENGTH_SHORT).show();
                navigatingAway = false;
                return;
            }

            deleteEmptySessionIfNeeded();
            clearSessionSnapshot();
            sp.edit().clear().apply();

            Intent it = new Intent(MainActivity.this, LoginActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            finish();
        });

        btnGerarRelatorio.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, ReportsActivity.class))
        );
    }

    private void renderOperatorHeader() {
        if (tvOperator == null) return;

        if (!operatorName.isEmpty() || !operatorId.isEmpty()) {
            String label = "Operador: "
                    + (operatorName.isEmpty() ? "" : operatorName)
                    + (operatorId.isEmpty() ? "" : " (" + operatorId + ")");
            tvOperator.setText(label.trim());
        } else {
            tvOperator.setText("Operador: ---");
        }
    }

    private void startOperation() {
        if (operationActive) {
            Toast.makeText(this, "A operação já está em andamento.", Toast.LENGTH_SHORT).show();
            return;
        }

        resetScreenState();

        expectedMode = MODE_POSITION;
        operationActive = true;

        createSession();
        updateStatus();
        persistSessionSnapshot();

        launchScanner(expectedMode);
    }

    private void launchScanner(String mode) {
        navigatingAway = true;
        Intent it = new Intent(this, CameraActivity.class);
        it.putExtra(EXTRA_SCAN_MODE, mode);
        cameraLauncher.launch(it);
    }

    private void handleScannedPosition(String rawPosition) {
        String normalized = normalizePosition(rawPosition);

        if (normalized.isEmpty()) {
            Toast.makeText(this, "Código de barras da posição inválido.", Toast.LENGTH_SHORT).show();
            return;
        }

        currentPosition = normalized;
        persistSessionSnapshot();
        updateStatus();

        Toast.makeText(this, "Posição lida: " + currentPosition, Toast.LENGTH_SHORT).show();
    }

    private void handleScannedLpn(String lpn) {
        String norm = normalize(lpn);

        if (norm.isEmpty()) {
            Toast.makeText(this, "SSCC inválido.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentPosition.isEmpty()) {
            Toast.makeText(this, "Nenhuma posição ativa para vincular o SSCC.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (scannedSet.contains(norm)) {
            Toast.makeText(this, "SSCC já coletado nesta operação.", Toast.LENGTH_SHORT).show();
            return;
        }

        scannedSet.add(norm);

        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        items.add(0, new LpnItem(norm, currentPosition, time, false));
        adapter.notifyItemInserted(0);
        rvLpns.scrollToPosition(0);

        Map<String, Object> scan = new HashMap<>();
        scan.put("lpn", norm);
        scan.put("position", currentPosition);
        scan.put("manual", false);
        scan.put("timestamp", FieldValue.serverTimestamp());
        scan.put("localTime", time);

        pendingScans.add(scan);

        updateStatus();
        persistSessionSnapshot();

        Toast.makeText(this, "SSCC vinculado à posição " + currentPosition, Toast.LENGTH_SHORT).show();

        currentPosition = "";
    }

    private void finishOperation() {
        if (!operationActive) {
            Toast.makeText(this, "Nenhuma operação em andamento.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (pendingScans.isEmpty()) {
            operationActive = false;
            deleteEmptySessionIfNeeded();
            expectedMode = MODE_POSITION;
            clearSessionSnapshot();
            resetScreenState();
            updateStatus();
            Toast.makeText(this, "Operação encerrada sem leituras.", Toast.LENGTH_SHORT).show();
            return;
        }

        saveScansAndCloseSession();
    }

    private void saveScansAndCloseSession() {
        if (sessionId == null) {
            Toast.makeText(this, "Sessão não encontrada para salvar.", Toast.LENGTH_LONG).show();
            return;
        }

        WriteBatch batch = db.batch();
        DocumentReference sessionRef = db.collection("sessions").document(sessionId);

        for (Map<String, Object> scan : pendingScans) {
            DocumentReference scanRef = sessionRef.collection("scans").document();
            batch.set(scanRef, scan);
        }

        Map<String, Object> end = new HashMap<>();
        end.put("finishedAt", FieldValue.serverTimestamp());
        end.put("total", pendingScans.size());
        end.put("status", "CLOSED");
        batch.set(sessionRef, end, SetOptions.merge());

        batch.commit()
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, "Operação finalizada e salva com sucesso.", Toast.LENGTH_SHORT).show();

                    operationActive = false;
                    sessionId = null;
                    expectedMode = MODE_POSITION;

                    clearSessionSnapshot();
                    resetScreenState();
                    updateStatus();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Falha ao salvar operação: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }

    private void createSession() {
        if (operatorId == null || operatorName == null) {
            Toast.makeText(this, "Operador não identificado", Toast.LENGTH_LONG).show();
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("operatorId", operatorId);
        data.put("operatorName", operatorName);
        data.put("position", "N/D");
        data.put("startedAt", FieldValue.serverTimestamp());
        data.put("finishedAt", null);
        data.put("totalScans", 0);
        data.put("createdAt", FieldValue.serverTimestamp());
        data.put("status", "OPEN");

        DocumentReference ref = db.collection("sessions").document();
        sessionId = ref.getId();

        ref.set(data)
                .addOnSuccessListener(unused -> Log.d("SESSION", "Sessão criada: " + sessionId))
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Falha ao criar sessão: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );

        persistSessionSnapshot();
    }

    private void deleteEmptySessionIfNeeded() {
        if (sessionId == null) return;

        db.collection("sessions")
                .document(sessionId)
                .delete()
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Falha ao remover sessão vazia: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );

        sessionId = null;
    }

    private void resetScreenState() {
        currentPosition = "";
        items.clear();
        pendingScans.clear();
        scannedSet.clear();

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizePosition(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private void updateStatus() {
        int count = pendingScans.size();

        if (tvLpnCount != null) {
            tvLpnCount.setText(String.valueOf(count));
        }

        if (tvStatus == null) return;

        if (!operationActive) {
            tvStatus.setText("Operação parada");
            return;
        }

        if (MODE_POSITION.equals(expectedMode)) {
            tvStatus.setText("Aguardando leitura da posição");
        } else {
            tvStatus.setText("Aguardando leitura do SSCC");
        }
    }

    private void persistSessionSnapshot() {
        if (spCleanup == null) {
            spCleanup = getSharedPreferences("session_cleanup", MODE_PRIVATE);
        }

        spCleanup.edit()
                .putString("session_id", sessionId)
                .putInt("total", pendingScans.size())
                .putString("position", currentPosition)
                .putString("expected_mode", expectedMode)
                .putBoolean("operation_active", operationActive)
                .apply();
    }

    private void clearSessionSnapshot() {
        if (spCleanup == null) {
            spCleanup = getSharedPreferences("session_cleanup", MODE_PRIVATE);
        }
        spCleanup.edit().clear().apply();
    }
}