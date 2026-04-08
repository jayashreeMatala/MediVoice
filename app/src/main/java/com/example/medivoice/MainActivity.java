package com.example.medivoice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String TAG = "MainActivity";

    // UI Elements
    private TextView tvOutput, tvLocation;
    private FloatingActionButton btnMic, btnSendText;
    private MaterialCardView cardAmbulance, cardHospital, cardPolice, cardDoctor, bottomInputCard;
    private Chip chipHeadache, chipFever, chipChestPain;
    private EditText etSymptom;
    private ImageView btnHistory;
    private ProgressBar aiProgress;

    // SERVICES
    private TextToSpeech tts;
    private FusedLocationProviderClient fusedLocationClient;
    private AppDatabase db;
    private Location lastLocation;
    private String lastAddress = "";

    // MODE FLAG
    private boolean isDoctorMode = false;

    // CODES
    private static final int SPEECH_REQUEST_CODE = 101;
    private static final int MIC_PERMISSION_CODE = 103;
    private static final int CALL_PERMISSION_CODE = 104;
    private static final int LOCATION_PERMISSION_CODE = 105;

    private final String GEMINI_API_KEY = "AIzaSyDajbWZbNcS6X3xeb4mZDqeCPfNTciTGig";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // UI Initialization
        tvOutput = findViewById(R.id.tvOutput);
        tvLocation = findViewById(R.id.tvLocation);
        btnMic = findViewById(R.id.btnMic);
        btnSendText = findViewById(R.id.btnSendText);
        etSymptom = findViewById(R.id.etSymptom);
        btnHistory = findViewById(R.id.btnHistory);
        bottomInputCard = findViewById(R.id.bottomInputCard);
        aiProgress = findViewById(R.id.aiProgress);

        cardAmbulance = findViewById(R.id.cardAmbulance);
        cardHospital = findViewById(R.id.cardHospital);
        cardPolice = findViewById(R.id.cardPolice);
        cardDoctor = findViewById(R.id.cardDoctor);

        chipHeadache = findViewById(R.id.chipHeadache);
        chipFever = findViewById(R.id.chipFever);
        chipChestPain = findViewById(R.id.chipChestPain);

        // Services
        tts = new TextToSpeech(this, this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        db = AppDatabase.getDatabase(this);

        updateLocationDisplay();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isDoctorMode) {
                    exitDoctorMode();
                } else {
                    setEnabled(false);
                    finish();
                }
            }
        });

        // Click Listeners
        btnMic.setOnClickListener(v -> {
            if (checkPermission(Manifest.permission.RECORD_AUDIO))
                startVoiceInput();
            else
                requestPermission(Manifest.permission.RECORD_AUDIO, MIC_PERMISSION_CODE);
        });

        btnSendText.setOnClickListener(v -> {
            String text = etSymptom.getText().toString().trim();
            if (!text.isEmpty()) {
                callGeminiAI(text);
                etSymptom.setText("");
            } else {
                Toast.makeText(this, "Please type a symptom", Toast.LENGTH_SHORT).show();
            }
        });

        chipHeadache.setOnClickListener(v -> callGeminiAI("Headache"));
        chipFever.setOnClickListener(v -> callGeminiAI("Fever"));
        chipChestPain.setOnClickListener(v -> callGeminiAI("Chest Pain"));

        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, HistoryActivity.class)));

        cardAmbulance.setOnClickListener(v -> tryEmergencyCall("108"));
        cardHospital.setOnClickListener(v -> openGoogleMaps("hospital"));
        cardPolice.setOnClickListener(v -> tryEmergencyCall("100"));
        cardDoctor.setOnClickListener(v -> openGoogleMaps("doctors"));

        // Tap location text to refresh
        tvLocation.setOnClickListener(v -> updateLocationDisplay());
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
            return capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        }
        return false;
    }

    private void callGeminiAI(String symptom) {
        if (!isNetworkAvailable()) {
            tvOutput.setText("No internet connection. Please check your network and try again.");
            Toast.makeText(this, "Consultation requires internet", Toast.LENGTH_SHORT).show();
            return;
        }

        enterDoctorMode();

        tvOutput.setText("Doctor AI is analyzing your symptoms... 🩺");
        if (aiProgress != null) aiProgress.setVisibility(View.VISIBLE);

        Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
        btnMic.startAnimation(pulse);

        GenerativeModel gm = new GenerativeModel("gemini-2.5-flash", GEMINI_API_KEY);
        GenerativeModelFutures model = GenerativeModelFutures.from(gm);

        // Include location context for better advice
        String locationInfo = (lastAddress != null && !lastAddress.isEmpty()) ? " The patient is currently at: " + lastAddress + "." : "";

        // Updated prompt to suggest common OTC medicines like Crocin, Paracetamol, Saradon
        String prompt = "Act as a professional Medical Doctor." + locationInfo + " Provide a detailed, clear, and concise clinical advice for the following symptom: " + symptom + ". " +
                "The response should be 4-5 natural sentences. " +
                "Suggest common and safe over-the-counter medicines available in India when appropriate, such as Paracetamol, Crocin, Saradon for pain or fever, or relevant care steps. " +
                "Always advise the user to consult a doctor if symptoms persist. " +
                "Mention if the condition might be an emergency. " +
                "NO asterisks, NO hashtags, NO bold marks. " +
                "If the input is in an Indian language (like Hindi), respond naturally in that language. Otherwise, respond in English.";

        Content content = new Content.Builder().addText(prompt).build();
        ListenableFuture<GenerateContentResponse> response = model.generateContent(content);

        Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                String rawText = result.getText();
                final String cleanText = (rawText != null) ? rawText.replaceAll("[*#_]", "") : "No response from AI.";

                runOnUiThread(() -> {
                    if (aiProgress != null) aiProgress.setVisibility(View.GONE);
                    btnMic.clearAnimation();
                    tvOutput.setText(cleanText);
                    speakText(cleanText);

                    Executors.newSingleThreadExecutor().execute(() -> {
                        double lat = (lastLocation != null) ? lastLocation.getLatitude() : 0.0;
                        double lon = (lastLocation != null) ? lastLocation.getLongitude() : 0.0;
                        db.symptomDao().insert(new SymptomHistory(symptom, cleanText, System.currentTimeMillis(), lat, lon));
                    });
                });
            }

            @Override
            public void onFailure(Throwable t) {
                Log.e(TAG, "Gemini AI call failed", t);
                runOnUiThread(() -> {
                    if (aiProgress != null) aiProgress.setVisibility(View.GONE);
                    btnMic.clearAnimation();
                    tvOutput.setText("Consultation failed: " + t.getMessage());
                });
            }
        }, getMainExecutor());
    }

    private void enterDoctorMode() {
        isDoctorMode = true;
        cardAmbulance.setVisibility(View.GONE);
        cardHospital.setVisibility(View.GONE);
        cardPolice.setVisibility(View.GONE);
        cardDoctor.setVisibility(View.GONE);
        chipHeadache.setVisibility(View.GONE);
        chipFever.setVisibility(View.GONE);
        chipChestPain.setVisibility(View.GONE);
        bottomInputCard.setVisibility(View.GONE);
        tvOutput.setTextSize(16);
    }

    private void exitDoctorMode() {
        isDoctorMode = false;
        if (tts != null) tts.stop();
        cardAmbulance.setVisibility(View.VISIBLE);
        cardHospital.setVisibility(View.VISIBLE);
        cardPolice.setVisibility(View.VISIBLE);
        cardDoctor.setVisibility(View.VISIBLE);
        chipHeadache.setVisibility(View.VISIBLE);
        chipFever.setVisibility(View.VISIBLE);
        chipChestPain.setVisibility(View.VISIBLE);
        bottomInputCard.setVisibility(View.VISIBLE);
        tvOutput.setText("How can I help you today?");
        tvOutput.setTextSize(20);
    }

    private void updateLocationDisplay() {
        if (checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            lastLocation = location;
                            getAddressFromLocation(location);
                        } else {
                            tvLocation.setText("Location: Detecting...");
                        }
                    });
        } else {
            requestPermission(Manifest.permission.ACCESS_FINE_LOCATION, LOCATION_PERMISSION_CODE);
        }
    }

    private void getAddressFromLocation(Location location) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    lastAddress = addresses.get(0).getAddressLine(0);
                    runOnUiThread(() -> tvLocation.setText(lastAddress));
                } else {
                    runOnUiThread(() -> tvLocation.setText("Location: " + location.getLatitude() + "," + location.getLongitude()));
                }
            } catch (Exception e) {
                runOnUiThread(() -> tvLocation.setText("Location: " + location.getLatitude() + "," + location.getLongitude()));
            }
        });
    }

    private boolean checkPermission(String p) {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission(String p, int c) {
        ActivityCompat.requestPermissions(this, new String[]{p}, c);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == MIC_PERMISSION_CODE) startVoiceInput();
            if (requestCode == LOCATION_PERMISSION_CODE) updateLocationDisplay();
        }
    }

    private void tryEmergencyCall(String number) {
        if (checkPermission(Manifest.permission.CALL_PHONE)) {
            startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number)));
        } else {
            requestPermission(Manifest.permission.CALL_PHONE, CALL_PERMISSION_CODE);
        }
    }

    private void openGoogleMaps(String query) {
        if (checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "Fetching current location...", Toast.LENGTH_SHORT).show();
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            lastLocation = location;
                        }
                        launchMapsIntent(query);
                    })
                    .addOnFailureListener(this, e -> launchMapsIntent(query));
        } else {
            requestPermission(Manifest.permission.ACCESS_FINE_LOCATION, LOCATION_PERMISSION_CODE);
            launchMapsIntent(query);
        }
    }

    private void launchMapsIntent(String query) {
        String uri;
        if (lastLocation != null) {
            uri = String.format(Locale.ENGLISH, "geo:%f,%f?q=%s+nearby", lastLocation.getLatitude(), lastLocation.getLongitude(), query);
        } else {
            uri = "geo:0,0?q=" + query + "+nearby";
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        intent.setPackage("com.google.android.apps.maps");
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        }
    }

    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE);
        } catch (Exception e) {
            Toast.makeText(this, "Voice engine missing", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) callGeminiAI(result.get(0));
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
            tts.setSpeechRate(0.85f);
        }
    }

    private void speakText(String text) {
        if (tts != null) {
            // Detect if text contains Hindi (Devanagari) characters
            boolean isHindi = text.matches(".*[\\u0900-\\u097F]+.*");
            if (isHindi) {
                tts.setLanguage(new Locale("hi", "IN"));
                tts.setSpeechRate(1.0f); // Natural speed for Hindi
            } else {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.9f);
            }

            // Keep letters, numbers, punctuation, and Hindi characters (\u0900-\u097F)
            String cleanForSpeech = text.replaceAll("[^a-zA-Z0-9.,?!: \\u0900-\\u097F]", "");
            tts.speak(cleanForSpeech, TextToSpeech.QUEUE_FLUSH, null, "MediVoice");
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}