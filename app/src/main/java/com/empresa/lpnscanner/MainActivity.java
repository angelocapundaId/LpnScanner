package com.empresa.lpnscanner;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** Sessão ativa ao entrar. "Finalizar Coleta" encerra a sessão atual e abre outra. */
public class MainActivity extends AppCompatActivity {

    private TextInputEditText etPosition, etManualLpn;
    private MaterialButton btnOpenCamera, btnAddManual, btnLogout, btnFinishCollection;
    private RecyclerView rvLpns;
    private TextView tvOperator, tvStatus, tvLpnCount;
    private LpnListAdapter adapter;
    private final ArrayList<LpnItem> items = new ArrayList<>();

    // Impede duplicadas
    private final Set<String> scannedSet = new LinkedHashSet<>();

    // Firestore
    private FirebaseFirestore db;
    private String sessionId;
    private String operatorId = "";
    private String operatorName = "";

    private SharedPreferences sp;              // sessão do operador
    private SharedPreferences spCleanup;       // snapshot p/ o service

    // Se true, estamos trocando de Activity (não devemos limpar a sessão em callbacks errados)
    private boolean navigatingAway = false;

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                // Voltou da câmera
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    @SuppressWarnings("unchecked")
                    ArrayList<String> lpns = result.getData().getStringArrayListExtra(CameraActivity.EXTRA_LPNS);
                    if (lpns != null && !lpns.isEmpty()) {
                        for (String lpn : lpns) addLpn(lpn, false);
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // UI
        etPosition          = findViewById(R.id.etPosition);
        etManualLpn         = findViewById(R.id.etManualLpn);
        btnOpenCamera       = findViewById(R.id.btnOpenCamera);
        btnAddManual        = findViewById(R.id.btnAddManual);
        btnLogout           = findViewById(R.id.btnLogout);
        btnFinishCollection = findViewById(R.id.btnFinishCollection);
        rvLpns              = findViewById(R.id.rvLpns);
        tvOperator          = findViewById(R.id.tvOperator);
        tvStatus            = findViewById(R.id.tvStatus);
        tvLpnCount          = findViewById(R.id.tvLpnCount);

        rvLpns.setLayoutManager(new LinearLayoutManager(this));

        // Adapter com callback de remover item
        adapter = new LpnListAdapter(items, (position, item) -> {
            if (position >= 0 && position < items.size()) {
                items.remove(position);
                scannedSet.remove(normalize(item.lpn));
                adapter.notifyItemRemoved(position);
                updateStatus();
                persistSessionSnapshot(); // atualiza total no snapshot p/ o serviço
                Toast.makeText(MainActivity.this, "LPN removida", Toast.LENGTH_SHORT).show();
            }
        });
        rvLpns.setAdapter(adapter);

        // Swipe para excluir
        ItemTouchHelper swipeHelper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
                    @Override public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) { return false; }
                    @Override public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                        int pos = viewHolder.getBindingAdapterPosition();
                        if (pos >= 0 && pos < items.size()) {
                            LpnItem removed = items.remove(pos);
                            scannedSet.remove(normalize(removed.lpn));
                            adapter.notifyItemRemoved(pos);
                            updateStatus();
                            persistSessionSnapshot(); // atualiza total no snapshot p/ o serviço
                            Toast.makeText(MainActivity.this, "LPN removida", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        swipeHelper.attachToRecyclerView(rvLpns);

        // Firebase
        FirebaseApp.initializeApp(this);
        db = FirebaseFirestore.getInstance();

        // Sessões
        sp = getSharedPreferences("session", MODE_PRIVATE);
        spCleanup = getSharedPreferences("session_cleanup", MODE_PRIVATE);

        operatorId   = sp.getString("op_id",   getIntent().getStringExtra("EXTRA_OPERATOR_ID"));
        operatorName = sp.getString("op_name", getIntent().getStringExtra("EXTRA_OPERATOR_NAME"));
        if (operatorId == null) operatorId = "";
        if (operatorName == null) operatorName = "";

        renderOperatorHeader();

        // Cria sessão ao entrar
        createSession();

        // Ações
        btnOpenCamera.setOnClickListener(v -> {
            String pos = etPosition.getText() == null ? "" : etPosition.getText().toString().trim();
            if (pos.isEmpty()) {
                Toast.makeText(this, "Informe a Posição antes de escanear.", Toast.LENGTH_SHORT).show();
                return;
            }
            navigatingAway = true; // indo para a CameraActivity
            Intent it = new Intent(this, CameraActivity.class);
            it.putExtra(CameraActivity.EXTRA_CONTINUOUS, true);
            cameraLauncher.launch(it);
        });

        btnAddManual.setOnClickListener(v -> {
            String txt = etManualLpn.getText() != null ? etManualLpn.getText().toString().trim() : "";
            if (txt.isEmpty()) {
                Toast.makeText(this, "Digite a LPN manualmente", Toast.LENGTH_SHORT).show();
                return;
            }
            addLpn(txt, true);
            etManualLpn.setText("");
        });

        btnFinishCollection.setOnClickListener(v -> finishCollection());

        // Logout: limpar sessão e voltar ao Login
        btnLogout.setOnClickListener(v -> {
            navigatingAway = true;
            closeOrDeleteSessionIfNeeded(); // apaga sessão vazia, senão finaliza
            clearSessionSnapshot();         // limpa cache usado pelo serviço
            sp.edit().clear().apply();
            Intent it = new Intent(MainActivity.this, LoginActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            finish();
        });

        updateStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (sp == null) sp = getSharedPreferences("session", MODE_PRIVATE);
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
        navigatingAway = false; // voltamos para esta Activity
    }

    /** Não limpamos nada em onStop/onDestroy para não apagar ao minimizar. */

    /** Exibe "Operador: Nome (ID)" ou '---' */
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

    /** Cria uma nova sessão no Firestore. */
    private void createSession() {
        String pos = etPosition.getText() != null ? etPosition.getText().toString().trim() : "";

        Map<String, Object> data = new HashMap<>();
        data.put("operatorId", operatorId);
        data.put("operatorName", operatorName);
        data.put("position", pos);
        data.put("startedAt", FieldValue.serverTimestamp());
        data.put("finishedAt", null);
        data.put("total", 0);

        DocumentReference ref = db.collection("sessions").document();
        sessionId = ref.getId();
        ref.set(data)
                .addOnSuccessListener(unused -> {})
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Falha ao criar sessão: " + e.getMessage(), Toast.LENGTH_LONG).show());

        // Snapshot inicial para o serviço
        persistSessionSnapshot();
    }

    /** Fecha sessão aberta: deleta se vazia; senão finaliza com finishedAt/total. */
    private void closeOrDeleteSessionIfNeeded() {
        if (sessionId == null) return;

        if (items.isEmpty()) {
            // Nenhuma LPN coletada -> apaga a sessão para não "sujar" o Firestore
            db.collection("sessions").document(sessionId)
                    .delete()
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Falha ao remover sessão vazia: " + e.getMessage(), Toast.LENGTH_LONG).show());
        } else {
            // Há LPNs -> finaliza
            Map<String, Object> end = new HashMap<>();
            end.put("finishedAt", FieldValue.serverTimestamp());
            end.put("total", items.size());
            String pos = etPosition.getText() != null ? etPosition.getText().toString().trim() : "";
            end.put("position", pos);

            db.collection("sessions").document(sessionId)
                    .set(end, SetOptions.merge())
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Falha ao finalizar: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }

        // evita repetir finalização/remoção depois
        sessionId = null;
        clearSessionSnapshot();
    }

    /** Botão 'Finalizar Coleta' */
    private void finishCollection() {
        if (items.isEmpty()) {
            Toast.makeText(this, "Nenhuma LPN para finalizar.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Atualiza sessão
        if (sessionId != null) {
            String pos = etPosition.getText() != null ? etPosition.getText().toString().trim() : "";
            Map<String, Object> end = new HashMap<>();
            end.put("finishedAt", FieldValue.serverTimestamp());
            end.put("total", items.size());
            end.put("position", pos);

            db.collection("sessions").document(sessionId)
                    .set(end, SetOptions.merge())
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Falha ao finalizar: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }

        Toast.makeText(this, "Coleta finalizada. Nova sessão iniciada.", Toast.LENGTH_SHORT).show();

        // Limpa e recomeça
        items.clear();
        scannedSet.clear();
        adapter.notifyDataSetChanged();
        updateStatus();

        // Cria nova sessão e atualiza snapshot
        createSession();
    }

    /** Normaliza LPN (trim + maiúsculas) para comparação/duplicata */
    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** Adiciona LPN na lista e salva no Firestore, prevenindo duplicadas. */
    private void addLpn(String lpn, boolean manual) {
        String norm = normalize(lpn);
        if (norm.isEmpty()) return;

        if (scannedSet.contains(norm)) {
            Toast.makeText(this, "LPN já coletada", Toast.LENGTH_SHORT).show();
            return;
        }

        scannedSet.add(norm);

        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        items.add(0, new LpnItem(norm, time, manual));
        adapter.notifyItemInserted(0);
        rvLpns.scrollToPosition(0);
        updateStatus();

        // Atualiza snapshot para o serviço saber o total atual
        persistSessionSnapshot();

        if (sessionId == null) return;

        String pos = etPosition.getText() != null ? etPosition.getText().toString().trim() : "";

        Map<String, Object> scan = new HashMap<>();
        scan.put("lpn", norm);
        scan.put("manual", manual);
        scan.put("position", pos);
        scan.put("timestamp", FieldValue.serverTimestamp());

        db.collection("sessions").document(sessionId)
                .collection("scans").add(scan);

        Map<String, Object> inc = new HashMap<>();
        inc.put("position", pos);
        inc.put("total", FieldValue.increment(1));
        db.collection("sessions").document(sessionId).set(inc, SetOptions.merge());
    }

    private void updateStatus() {
        int count = items.size();
        if (tvStatus != null) tvStatus.setText("LPNs: " + count);
        if (tvLpnCount != null) tvLpnCount.setText(String.valueOf(count));
    }

    /** Salva um snapshot da sessão atual para o Service usar no onTaskRemoved */
    private void persistSessionSnapshot() {
        if (spCleanup == null) spCleanup = getSharedPreferences("session_cleanup", MODE_PRIVATE);
        String pos = etPosition.getText() != null ? etPosition.getText().toString().trim() : "";
        spCleanup.edit()
                .putString("session_id", sessionId)
                .putInt("total", items.size())
                .putString("position", pos)
                .apply();
    }

    /** Limpa o snapshot quando sessão encerra ou no logout */
    private void clearSessionSnapshot() {
        if (spCleanup == null) spCleanup = getSharedPreferences("session_cleanup", MODE_PRIVATE);
        spCleanup.edit().clear().apply();
    }
}