package com.example.medivoice;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.concurrent.Executors;

public class HistoryActivity extends AppCompatActivity {
    private RecyclerView rv;
    private HistoryAdapter adapter;
    private List<SymptomHistory> historyList;
    private ImageView btnClearAll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        rv = findViewById(R.id.rvHistory);
        btnClearAll = findViewById(R.id.btnClearAll);
        rv.setLayoutManager(new LinearLayoutManager(this));

        loadHistory();

        btnClearAll.setOnClickListener(v -> showDeleteConfirmationDialog());

        // SWIPE TO DELETE LOGIC
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                SymptomHistory itemToDelete = historyList.get(position);

                Executors.newSingleThreadExecutor().execute(() -> {
                    AppDatabase.getDatabase(HistoryActivity.this).symptomDao().delete(itemToDelete);
                    runOnUiThread(() -> {
                        historyList.remove(position);
                        adapter.notifyItemRemoved(position);
                        Toast.makeText(HistoryActivity.this, "Record deleted", Toast.LENGTH_SHORT).show();
                    });
                });
            }
        }).attachToRecyclerView(rv);
    }

    private void showDeleteConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Are you sure you want to delete all medical history?")
                .setPositiveButton("Delete All", (dialog, which) -> clearAllHistory())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearAllHistory() {
        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase.getDatabase(this).symptomDao().deleteAll();
            runOnUiThread(() -> {
                if (historyList != null) {
                    historyList.clear();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                }
                Toast.makeText(this, "All history cleared", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void loadHistory() {
        Executors.newSingleThreadExecutor().execute(() -> {
            historyList = AppDatabase.getDatabase(this).symptomDao().getAllHistory();
            runOnUiThread(() -> {
                adapter = new HistoryAdapter(historyList);
                rv.setAdapter(adapter);
            });
        });
    }
}