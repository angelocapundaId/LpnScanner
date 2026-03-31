package com.empresa.lpnscanner;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etOperatorId, etPin;
    private View btnLogin;

    private FirebaseFirestore db;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etOperatorId = findViewById(R.id.etOperatorId);
        etPin        = findViewById(R.id.etPin);
        btnLogin     = findViewById(R.id.btnLogin);

        FirebaseApp.initializeApp(this);
        db = FirebaseFirestore.getInstance();
        sp = getSharedPreferences("session", MODE_PRIVATE);

        // dispara auth anônima – não bloqueia UI
        FirebaseAuth.getInstance().signInAnonymously();

        btnLogin.setOnClickListener(v -> realizarLoginComPin());
        configurarOlhoSenha();
    }

    private void configurarOlhoSenha() {
        ViewParent parent = etPin.getParent();
        if (parent != null) {
            ViewParent gp = parent.getParent();
            if (gp instanceof TextInputLayout) {
                TextInputLayout til = (TextInputLayout) gp;
                til.setEndIconOnClickListener(v -> {
                    int sel = etPin.getSelectionEnd();
                    if (etPin.getTransformationMethod() instanceof PasswordTransformationMethod) {
                        etPin.setTransformationMethod(null);
                        til.setEndIconDrawable(R.drawable.ic_visibility);
                    } else {
                        etPin.setTransformationMethod(PasswordTransformationMethod.getInstance());
                        til.setEndIconDrawable(R.drawable.ic_visibility_off);
                    }
                    etPin.setSelection(sel);
                });
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        String id = sp.getString("op_id", null);
        String name = sp.getString("op_name", null);
        if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(name)) openMain();
    }

    private void realizarLoginComPin() {
        esconderTeclado();

        String user = getTextOrEmpty(etOperatorId);
        String pin  = getTextOrEmpty(etPin);

        if (TextUtils.isEmpty(user)) { showFieldError(etOperatorId, "Informe o ID do Operador"); return; }
        if (TextUtils.isEmpty(pin)  || pin.length() < 4) { showFieldError(etPin, "Senha deve ter pelo menos 4 dígitos"); return; }

        if (isAdminCredentials(user, pin)) {
            Toast.makeText(this, "Modo administrador", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, AdminActivity.class));
            return;
        }

        setLoading(true);

        // garante auth antes de consultar
        ensureAuthThen(() -> tentarLoginNasColecoes(user, pin));
    }

    /** Tenta primeiro em 'operadores' (pinHash). Se não achar, tenta 'operators' (pin texto). */
    private void tentarLoginNasColecoes(String userId, String pin) {
        String inputHash = sha256Hex(pin);

        // 1) Coleção segura 'operadores'
        db.collection("operadores").document(userId).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        validarDocEEntrar(doc, inputHash, true);
                    } else {
                        // 2) Fallback: coleção antiga 'operators'
                        db.collection("operators").document(userId).get()
                                .addOnSuccessListener(doc2 -> {
                                    if (doc2.exists()) {
                                        validarDocEEntrar(doc2, pin, false);
                                    } else {
                                        setLoading(false);
                                        showError("Operador não cadastrado.");
                                    }
                                })
                                .addOnFailureListener(e -> {
                                    setLoading(false);
                                    showError("Falha ao autenticar: " + e.getMessage());
                                });
                    }
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    showError("Falha ao autenticar: " + e.getMessage());
                });
    }

    /** Valida doc: se usarHash=true compara 'pinHash'; senão compara 'pin' texto. */
    private void validarDocEEntrar(DocumentSnapshot doc, String input, boolean usarHash) {
        Boolean active = doc.getBoolean(usarHash ? "ativo" : "active");
        String  name   = doc.getString(usarHash ? "nome"  : "name");

        if (active != null && !active) {
            setLoading(false);
            showError("Operador inativo.");
            return;
        }

        String salvo = doc.getString(usarHash ? "pinHash" : "pin");
        boolean ok = (salvo != null) && (
                usarHash ? salvo.equalsIgnoreCase(input) : salvo.equals(input)
        );

        if (ok) {
            saveSession(doc.getId(), name);
            setLoading(false);
            openMain();
        } else {
            setLoading(false);
            showError("PIN incorreto.");
        }
    }

    /** Garante usuário autenticado no Firebase Auth. */
    private void ensureAuthThen(Runnable action) {
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            action.run();
        } else {
            FirebaseAuth.getInstance().signInAnonymously()
                    .addOnSuccessListener(r -> action.run())
                    .addOnFailureListener(e -> {
                        setLoading(false);
                        showError("Falha na autenticação anônima: " + e.getMessage());
                    });
        }
    }

    private boolean isAdminCredentials(String user, String pin) {
        if (user == null) user = "";
        if (pin  == null) pin  = "";
        return user.trim().equalsIgnoreCase("admin") && pin.trim().equals("0000");
    }

    private void saveSession(String operadorId, String operadorName) {
        sp.edit()
                .putString("op_id", operadorId == null ? "" : operadorId)
                .putString("op_name", operadorName == null ? "" : operadorName)
                .apply();
    }

    private void openMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private void showFieldError(View field, String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        field.requestFocus();
    }

    private void showError(String msg) {
        new AlertDialog.Builder(this)
                .setTitle("Erro")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private void esconderTeclado() {
        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private String getTextOrEmpty(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void setLoading(boolean loading) { btnLogin.setEnabled(!loading); }

    // ========= Utilitário HASH =========
    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
