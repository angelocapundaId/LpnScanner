package com.empresa.lpnscanner;

import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AdminActivity extends AppCompatActivity {

    private TextInputEditText etOpId, etOpName, etOpPin, etOpPinConfirm;
    private TextInputLayout tilPin, tilPinConfirm;
    private SwitchMaterial swActive;
    private MaterialButton btnSave, btnBack;
    private RecyclerView rvOps;

    private FirebaseFirestore db;
    private final List<OperatorItem> list = new ArrayList<>();
    private OpsAdapter adapter;
    private boolean isEditing = false;
    private String currentEditingId = "";
    private String currentPin = "";

    // Interface para os callbacks - definida fora da classe OpsAdapter
    interface AdapterCallbacks {
        void onEditClicked(OperatorItem operator);
        void onRemoveClicked(OperatorItem operator);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin);

        FirebaseApp.initializeApp(this);
        db = FirebaseFirestore.getInstance();

        etOpId = findViewById(R.id.etOpId);
        etOpName = findViewById(R.id.etOpName);
        etOpPin = findViewById(R.id.etOpPin);
        etOpPinConfirm = findViewById(R.id.etOpPinConfirm);
        tilPin = findViewById(R.id.tilPin);
        tilPinConfirm = findViewById(R.id.tilPinConfirm);
        swActive = findViewById(R.id.swActive);
        btnSave = findViewById(R.id.btnSave);
        btnBack = findViewById(R.id.btnBack);
        rvOps = findViewById(R.id.rvOps);

        rvOps.setLayoutManager(new LinearLayoutManager(this));

        // Criar uma instância da interface de callbacks
        AdapterCallbacks callbacks = new AdapterCallbacks() {
            @Override
            public void onEditClicked(OperatorItem operator) {
                loadOperatorForEditing(operator);
            }

            @Override
            public void onRemoveClicked(OperatorItem operator) {
                deleteOperator(operator.id);
            }
        };

        adapter = new OpsAdapter(list, callbacks);
        rvOps.setAdapter(adapter);

        btnSave.setOnClickListener(v -> saveOperator());
        btnBack.setOnClickListener(v -> finish());

        // Configurar os ícones de olho manualmente
        setupPasswordToggle();

        loadOperators();
    }

    private void setupPasswordToggle() {
        // Configurar toggle para o campo PIN
        tilPin.setEndIconOnClickListener(v -> togglePasswordVisibility(etOpPin, tilPin));

        // Configurar toggle para o campo Confirmar PIN
        tilPinConfirm.setEndIconOnClickListener(v -> togglePasswordVisibility(etOpPinConfirm, tilPinConfirm));
    }

    private void togglePasswordVisibility(TextInputEditText editText, TextInputLayout textInputLayout) {
        if (editText.getTransformationMethod() instanceof PasswordTransformationMethod) {
            // Mostrar texto - mudar para ícone de olho fechado
            editText.setTransformationMethod(null);
            textInputLayout.setEndIconDrawable(R.drawable.ic_visibility);
        } else {
            // Ocultar texto - mudar para ícone de olho aberto
            editText.setTransformationMethod(new PasswordTransformationMethod());
            textInputLayout.setEndIconDrawable(R.drawable.ic_visibility_off);
        }
        // Mover cursor para o final
        editText.setSelection(editText.getText().length());
    }

    private void loadOperatorForEditing(OperatorItem operator) {
        // Carregar dados completos do operador do Firestore
        db.collection("operators").document(operator.id).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        String pin = documentSnapshot.getString("pin");
                        startEditing(operator, pin);
                    } else {
                        toast("Erro: Operador não encontrado");
                    }
                })
                .addOnFailureListener(e -> {
                    toast("Erro ao carregar dados do operador: " + e.getMessage());
                });
    }

    private void startEditing(OperatorItem operator, String currentPin) {
        isEditing = true;
        currentEditingId = operator.id;
        this.currentPin = currentPin != null ? currentPin : "";

        // Preencher os campos com os dados do operador
        etOpId.setText(operator.id);
        etOpName.setText(operator.name);
        swActive.setChecked(operator.active);

        // Preencher os campos de PIN com a senha atual
        etOpPin.setText(currentPin);
        etOpPinConfirm.setText(currentPin);

        // Bloquear edição do ID
        etOpId.setEnabled(false);
        etOpId.setAlpha(0.6f); // Visualmente indicar que está desabilitado

        // Mudar texto do botão para "Atualizar Operador"
        btnSave.setText("Atualizar Operador");

        // Resetar ícones para o estado padrão (olho fechado)
        tilPin.setEndIconDrawable(R.drawable.ic_visibility_off);
        tilPinConfirm.setEndIconDrawable(R.drawable.ic_visibility_off);

        // Focar no campo de nome para facilitar edição
        etOpName.requestFocus();

        toast("Editando operador: " + operator.name + ". Modifique a senha se desejar alterar.");
    }

    private void cancelEditing() {
        isEditing = false;
        currentEditingId = "";
        currentPin = "";

        // Reativar campo ID
        etOpId.setEnabled(true);
        etOpId.setAlpha(1.0f);

        // Voltar texto do botão para "Salvar Operador"
        btnSave.setText("Salvar Operador");

        clearInputs();
    }

    private void saveOperator() {
        String id = text(etOpId);
        String name = text(etOpName);
        String pin = text(etOpPin);
        String pin2 = text(etOpPinConfirm);
        boolean active = swActive != null && swActive.isChecked();

        if (TextUtils.isEmpty(id)) {
            toast("Informe o ID do operador");
            etOpId.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(name)) {
            toast("Informe o nome do operador");
            etOpName.requestFocus();
            return;
        }

        // Se estiver editando
        if (isEditing) {
            // Verificar se a senha foi alterada
            boolean pinChanged = !pin.equals(currentPin);

            if (pinChanged) {
                // Se a senha foi alterada, validar a nova senha
                if (TextUtils.isEmpty(pin) || pin.length() < 4) {
                    toast("PIN deve ter pelo menos 4 dígitos");
                    etOpPin.requestFocus();
                    return;
                }
                if (!pin.equals(pin2)) {
                    toast("PIN e confirmação não conferem");
                    etOpPinConfirm.requestFocus();
                    return;
                }
            }
        } else {
            // Se for novo operador, validar senha obrigatoriamente
            if (TextUtils.isEmpty(pin) || pin.length() < 4) {
                toast("PIN deve ter pelo menos 4 dígitos");
                etOpPin.requestFocus();
                return;
            }
            if (!pin.equals(pin2)) {
                toast("PIN e confirmação não conferem");
                etOpPinConfirm.requestFocus();
                return;
            }
        }

        // Preparar dados para salvar
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("active", active);
        data.put("updatedAt", FieldValue.serverTimestamp());

        // Se estiver editando
        if (isEditing) {
            boolean pinChanged = !pin.equals(currentPin);

            if (pinChanged) {
                // Se a senha foi alterada, atualizar o hash e manter o PIN legível
                String pinHash = hashPin(pin);
                data.put("pinHash", pinHash);
                data.put("pin", pin); // Manter o PIN legível para futuras edições
            }
            // Se a senha não foi alterada, não atualiza os campos de PIN
        } else {
            // Se for novo operador, sempre criar PIN
            String pinHash = hashPin(pin);
            data.put("pinHash", pinHash);
            data.put("pin", pin); // Salvar PIN legível para futuras edições
            data.put("createdAt", FieldValue.serverTimestamp());
        }

        db.collection("operators").document(id)
                .set(data)
                .addOnSuccessListener(unused -> {
                    if (isEditing) {
                        toast("Operador atualizado com sucesso");
                    } else {
                        toast("Operador salvo com sucesso");
                    }
                    cancelEditing();
                    loadOperators();
                })
                .addOnFailureListener(e -> toast("Falha ao salvar: " + e.getMessage()));
    }

    private void deleteOperator(String id) {
        // Confirmação antes de deletar com botões personalizados
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Confirmar Exclusão")
                .setMessage("Tem certeza que deseja excluir o operador " + id + "?")
                .setPositiveButton("Sim", null) // Vamos customizar depois
                .setNegativeButton("Não", null) // Vamos customizar depois
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            // Botão SIM - Cor vermelha com texto branco
            android.widget.Button positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            positiveButton.setTextColor(getResources().getColor(android.R.color.white));
            positiveButton.setBackgroundColor(getResources().getColor(R.color.colorError));
            positiveButton.setPadding(32, 16, 32, 16);

            // Botão NÃO - Cor cinza com texto branco
            android.widget.Button negativeButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
            negativeButton.setTextColor(getResources().getColor(android.R.color.white));
            negativeButton.setBackgroundColor(getResources().getColor(android.R.color.darker_gray));
            negativeButton.setPadding(32, 16, 32, 16);

            // Adicionar os listeners após a customização
            positiveButton.setOnClickListener(v -> {
                db.collection("operators").document(id).delete()
                        .addOnSuccessListener(unused -> {
                            toast("Operador removido");
                            // Se estava editando o operador removido, cancelar edição
                            if (isEditing && currentEditingId.equals(id)) {
                                cancelEditing();
                            }
                            loadOperators();
                            dialog.dismiss();
                        })
                        .addOnFailureListener(e -> {
                            toast("Falha ao remover: " + e.getMessage());
                            dialog.dismiss();
                        });
            });

            negativeButton.setOnClickListener(v -> dialog.dismiss());
        });

        dialog.show();
    }

    private void loadOperators() {
        db.collection("operators").get()
                .addOnSuccessListener(qs -> {
                    list.clear();
                    for (DocumentSnapshot d : qs) {
                        String id = d.getId();
                        String name = d.getString("name");
                        Boolean active = d.getBoolean("active");
                        list.add(new OperatorItem(
                                id,
                                name == null ? "" : name,
                                active != null && active
                        ));
                    }
                    adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> toast("Erro ao carregar operadores: " + e.getMessage()));
    }

    private void clearInputs() {
        etOpId.setText("");
        etOpName.setText("");
        etOpPin.setText("");
        etOpPinConfirm.setText("");
        if (swActive != null) swActive.setChecked(true);

        // Reativar campo ID se estiver desabilitado
        etOpId.setEnabled(true);
        etOpId.setAlpha(1.0f);

        // Resetar ícones para o estado padrão
        tilPin.setEndIconDrawable(R.drawable.ic_visibility_off);
        tilPinConfirm.setEndIconDrawable(R.drawable.ic_visibility_off);

        // Voltar texto do botão para "Salvar Operador"
        btnSave.setText("Salvar Operador");

        // Resetar estado de edição
        isEditing = false;
        currentEditingId = "";
        currentPin = "";

        // Focar no primeiro campo após limpar
        etOpId.requestFocus();
    }

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String hashPin(String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(pin.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return pin;
        }
    }

    static class OperatorItem {
        final String id;
        final String name;
        final boolean active;

        OperatorItem(String id, String name, boolean active) {
            this.id = id;
            this.name = name;
            this.active = active;
        }
    }

    // Classe do Adapter - agora recebe a interface como parâmetro
    static class OpsAdapter extends RecyclerView.Adapter<OpsAdapter.H> {
        private final List<OperatorItem> data;
        private final AdapterCallbacks callbacks;

        OpsAdapter(List<OperatorItem> data, AdapterCallbacks callbacks) {
            this.data = data;
            this.callbacks = callbacks;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_operator, parent, false);
            return new H(view);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            OperatorItem it = data.get(position);
            String status = it.active ? "ativo" : "inativo";
            h.tv.setText(it.id + " • " + it.name + " (" + status + ")");

            h.btnEdit.setOnClickListener(v -> {
                if (callbacks != null) callbacks.onEditClicked(it);
            });

            h.btnDel.setOnClickListener(v -> {
                if (callbacks != null) callbacks.onRemoveClicked(it);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class H extends RecyclerView.ViewHolder {
            final TextView tv;
            final ImageButton btnEdit;
            final ImageButton btnDel;

            H(@NonNull View itemView) {
                super(itemView);
                tv = itemView.findViewById(R.id.tvOp);
                btnEdit = itemView.findViewById(R.id.btnEdit);
                btnDel = itemView.findViewById(R.id.btnDel);
            }
        }
    }
}