package com.dharmawantoxi.rapiuang;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Selection;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class TransactionFormActivity extends Activity {
    private FinanceStore store;
    private long transactionId;
    private Transaction original;
    private String type = Transaction.EXPENSE;
    private String selectedDate;
    private String initialDate;
    private EditText amountInput, titleInput, noteInput;
    private Spinner categoryInput;
    private TextView dateText, expenseTab, incomeTab, saveButton;
    private static final int PICK_PHOTO = 31, TAKE_PHOTO = 32;
    private FinanceStore.PhotoAction photoAction = FinanceStore.PhotoAction.KEEP;
    private String draftName, captureName, processingCaptureName;
    private ImageView photoPreview;
    private TextView photoStatus, choosePhotoButton, takePhotoButton, removePhotoButton, restorePhotoButton;
    private ExecutorService photoExecutor;
    private final Handler photoHandler = new Handler(Looper.getMainLooper());
    private Future<?> importTask;
    private long importGeneration, previewGeneration;
    private boolean photoBusy, destroyed, stateSaved, pendingRechooseMessage;
    private AppLanguage language = AppLanguage.INDONESIAN;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguageSettings.wrap(base));
    }

    private String tr(String indonesian, String english) {
        return language.text(indonesian, english);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        language = LanguageSettings.get(this);
        store = new FinanceStore(this);
        photoExecutor = Executors.newSingleThreadExecutor();
        PhotoFiles.pruneOld(this);
        transactionId = getIntent().getLongExtra("transaction_id", 0);
        if (transactionId > 0) {
            try { original = store.get(transactionId); }
            catch (RuntimeException e) {
                showFatalError(tr("Data transaksi belum bisa dibuka. Coba lagi.", "The transaction could not be opened. Try again."));
                return;
            }
            if (original == null) {
                showFatalError(tr("Transaksi tidak ditemukan atau sudah dihapus.", "The transaction was not found or has already been deleted."));
                return;
            }
        }
        String requestedDate = getIntent().getStringExtra("default_date");
        initialDate = original == null ? (Dates.isValid(requestedDate) ? requestedDate : Dates.today()) : original.date;
        if (savedInstanceState != null) initialDate = savedInstanceState.getString("initial_date", initialDate);
        type = original == null ? Transaction.EXPENSE : original.type;
        selectedDate = initialDate;
        String initialAmount = original == null ? "" : Long.toString(original.amount);
        String initialTitle = original == null ? "" : original.title;
        String initialNote = original == null ? "" : original.note;
        String initialCategory = original == null ? Categories.forType(type)[0] : original.category;
        if (savedInstanceState != null) {
            type = savedInstanceState.getString("type", type);
            selectedDate = savedInstanceState.getString("date", selectedDate);
            initialAmount = savedInstanceState.getString("amount", initialAmount);
            initialTitle = savedInstanceState.getString("title", initialTitle);
            initialNote = savedInstanceState.getString("note", initialNote);
            initialCategory = savedInstanceState.getString("category", initialCategory);
            try { photoAction = FinanceStore.PhotoAction.valueOf(savedInstanceState.getString("photo_action", "KEEP")); }
            catch (IllegalArgumentException ignored) { photoAction = FinanceStore.PhotoAction.KEEP; }
            draftName = savedInstanceState.getString("photo_draft");
            captureName = savedInstanceState.getString("photo_capture");
            pendingRechooseMessage = savedInstanceState.getBoolean("photo_importing", false);
        }
        buildForm(initialAmount, initialTitle, initialCategory, initialNote);
        if (savedInstanceState != null) {
            int start = savedInstanceState.getInt("amount_selection_start", -1);
            int end = savedInstanceState.getInt("amount_selection_end", -1);
            if (start >= 0 && end >= 0) {
                int length = amountInput.length();
                amountInput.setSelection(Math.min(start, length), Math.min(end, length));
            }
        }
        updatePhotoUi();
        requestPhotoPreview();
    }

    private void showFatalError(String message) {
        new AlertDialog.Builder(this).setTitle(tr("Tidak dapat membuka transaksi", "Unable to open transaction"))
                .setMessage(message).setPositiveButton(tr("Tutup", "Close"), (d, w) -> finish())
                .setOnCancelListener(d -> finish()).show();
    }

    private void buildForm(String amount, String title, String category, String note) {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        Ui.setupWindow(this, root);
        setContentView(root);

        LinearLayout header = Ui.row(this);
        Ui.pad(header, 18, 13, 22, 12);
        FrameLayout back = Ui.iconBox(this, "back", Ui.GREEN, Ui.WHITE, 48, 20, 14);
        back.setContentDescription(tr("Kembali", "Back"));
        Ui.clickable(back, Ui.outlined(this, Ui.WHITE, Ui.LINE, 14), v -> onBackPressed());
        header.addView(back);
        LinearLayout headerText = Ui.column(this);
        LinearLayout.LayoutParams headerParams = Ui.weighted(0, -2, 1);
        headerParams.leftMargin = Ui.dp(this, 14);
        headerText.addView(Ui.text(this, original == null ? tr("Catat transaksi", "Add transaction") : tr("Ubah transaksi", "Edit transaction"), 17, Ui.INK, true));
        headerText.addView(Ui.text(this, tr("SATU LANGKAH LEBIH RAPI", "ONE STEP TOWARD CLARITY"), 9, Ui.MUTED, true));
        header.addView(headerText, headerParams);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 22, 10, 22, 24);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, Ui.weighted(-1, 0, 1));

        content.addView(Ui.display(this, original == null ? tr("Buat catatan baru.", "Create a new entry.") : tr("Perbarui catatan.", "Update this entry."), 26));
        content.addView(Ui.gap(this, 4));
        content.addView(Ui.text(this, tr("Setiap transaksi kecil berarti.", "Every small transaction counts."), 13, Ui.MUTED, false));
        content.addView(Ui.gap(this, 19));
        LinearLayout tabs = Ui.row(this);
        tabs.setBackground(Ui.shape(this, 0xffe9eee7, 16));
        Ui.pad(tabs, 4, 4, 4, 4);
        expenseTab = typeTab(tr("Pengeluaran", "Expense"));
        incomeTab = typeTab(tr("Pemasukan", "Income"));
        tabs.addView(expenseTab, Ui.weighted(0, Ui.dp(this, 42), 1));
        tabs.addView(incomeTab, Ui.weighted(0, Ui.dp(this, 42), 1));
        expenseTab.setOnClickListener(v -> changeType(Transaction.EXPENSE));
        incomeTab.setOnClickListener(v -> changeType(Transaction.INCOME));
        content.addView(tabs);
        content.addView(Ui.gap(this, 21));

        content.addView(fieldLabel(tr("NOMINAL TRANSAKSI", "TRANSACTION AMOUNT"), false));
        content.addView(Ui.gap(this, 7));
        LinearLayout amountBox = Ui.row(this);
        amountBox.setBackground(Ui.outlined(this, Ui.PALE, 0xffdbe9d9, 18));
        Ui.pad(amountBox, 17, 5, 17, 5);
        amountBox.addView(Ui.text(this, "Rp", 24, Ui.GREEN, true));
        amountInput = new EditText(this);
        amountInput.setTextSize(27);
        amountInput.setTextColor(Ui.INK);
        amountInput.setHintTextColor(0xffa7b9a9);
        amountInput.setHint("0");
        amountInput.setContentDescription(tr("Nominal transaksi dalam rupiah", "Transaction amount in rupiah"));
        amountInput.setSingleLine(true);
        amountInput.setBackgroundColor(Color.TRANSPARENT);
        // Use a numeric keyboard; retain invalid pasted text so validation can reject it.
        // Do not truncate at 12 digits: an oversized amount must never silently become smaller.
        amountInput.setKeyListener(TextKeyListener.getInstance());
        amountInput.setRawInputType(InputType.TYPE_CLASS_NUMBER);
        amountInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AmountInput.MAX_TEXT_LENGTH)});
        amountInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        amountInput.setPadding(Ui.dp(this, 10), 0, 0, 0);
        amountInput.setText(AmountInput.forDisplay(amount));
        amountInput.setSelection(amountInput.length());
        installAmountFormatting();
        amountBox.addView(amountInput, Ui.weighted(0, Ui.dp(this, 55), 1));
        content.addView(amountBox);
        content.addView(Ui.gap(this, 5));
        content.addView(Ui.text(this, tr("Rupiah penuh, maksimal 12 digit. Titik ribuan otomatis; tanpa koma atau desimal.",
                "Whole rupiah, up to 12 digits. Thousands dots appear automatically; no commas or decimals."), 11, Ui.MUTED, false));
        content.addView(Ui.gap(this, 19));

        content.addView(fieldLabel(tr("NAMA TRANSAKSI", "TRANSACTION NAME"), false));
        content.addView(Ui.gap(this, 7));
        titleInput = input(tr("Contoh: Makan siang", "Example: Lunch"), true, 80);
        titleInput.setContentDescription(tr("Nama transaksi", "Transaction name"));
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        titleInput.setText(title);
        content.addView(inputBox(titleInput, 49));
        content.addView(Ui.gap(this, 17));

        content.addView(fieldLabel(tr("KATEGORI", "CATEGORY"), false));
        content.addView(Ui.gap(this, 7));
        LinearLayout categoryBox = Ui.row(this);
        categoryBox.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 15));
        Ui.pad(categoryBox, 15, 0, 14, 0);
        categoryInput = new Spinner(this);
        categoryInput.setBackgroundColor(Color.TRANSPARENT);
        categoryInput.setContentDescription(tr("Kategori transaksi", "Transaction category"));
        categoryBox.addView(categoryInput, Ui.weighted(0, Ui.dp(this, 49), 1));
        categoryBox.addView(new IconView(this, "down", Ui.GREEN), Ui.lp(Ui.dp(this, 18), Ui.dp(this, 18)));
        categoryBox.setOnClickListener(v -> categoryInput.performClick());
        content.addView(categoryBox);
        content.addView(Ui.gap(this, 17));

        content.addView(fieldLabel(tr("TANGGAL TRANSAKSI", "TRANSACTION DATE"), false));
        content.addView(Ui.gap(this, 7));
        LinearLayout dateBox = Ui.row(this);
        Ui.pad(dateBox, 15, 0, 15, 0);
        Ui.clickable(dateBox, Ui.outlined(this, Ui.WHITE, Ui.LINE, 15), v -> chooseDate());
        dateBox.setMinimumHeight(Ui.dp(this, 49));
        dateBox.addView(new IconView(this, "calendar", Ui.GREEN), Ui.lp(Ui.dp(this, 20), Ui.dp(this, 20)));
        dateText = Ui.text(this, Dates.display(selectedDate, language), 14, Ui.INK, true);
        LinearLayout.LayoutParams dateParams = Ui.weighted(0, -2, 1);
        dateParams.leftMargin = Ui.dp(this, 11);
        dateBox.addView(dateText, dateParams);
        dateBox.addView(new IconView(this, "down", Ui.GREEN), Ui.lp(Ui.dp(this, 18), Ui.dp(this, 18)));
        dateBox.setContentDescription(tr("Pilih tanggal, ", "Choose date, ") + Dates.display(selectedDate, language));
        dateText.setTag(dateBox);
        content.addView(dateBox);
        content.addView(Ui.gap(this, 6));
        String createdHint = original == null
                ? tr("Jam pembuatan dicatat otomatis saat disimpan, sesuai jam perangkat.", "Creation time is recorded automatically using the device time when saved.")
                : tr("Dibuat: ", "Created: ") + CreationTime.display(original.createdAt, language) + tr(". Waktu ini tidak berubah saat diedit.", ". This time does not change when edited.");
        content.addView(Ui.text(this, createdHint, 11, Ui.MUTED, false));
        content.addView(Ui.gap(this, 17));

        content.addView(fieldLabel(tr("CATATAN", "NOTE"), true));
        content.addView(Ui.gap(this, 7));
        noteInput = input(tr("Tambahkan catatan jika perlu", "Add a note if needed"), false, 300);
        noteInput.setContentDescription(tr("Catatan tambahan, opsional", "Additional note, optional"));
        noteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        noteInput.setGravity(Gravity.TOP | Gravity.START);
        noteInput.setText(note);
        LinearLayout noteBox = Ui.column(this);
        noteBox.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 15));
        Ui.pad(noteBox, 15, 12, 15, 12);
        noteBox.addView(noteInput, Ui.lp(-1, Ui.dp(this, 62)));
        content.addView(noteBox);
        content.addView(Ui.gap(this, 19));
        content.addView(fieldLabel(tr("FOTO TRANSAKSI", "TRANSACTION PHOTO"), true));
        content.addView(Ui.gap(this, 7));
        content.addView(Ui.text(this, tr("Satu foto struk atau bukti, boleh dikosongkan. Foto diperkecil dan disimpan di perangkat.", "One optional receipt or proof photo. It is resized and stored on the device."),
                11, Ui.MUTED, false));
        content.addView(Ui.gap(this, 8));
        photoStatus = Ui.text(this, tr("Belum ada foto.", "No photo attached."), 12, Ui.INK, false);
        photoStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(photoStatus);
        content.addView(Ui.gap(this, 8));
        photoPreview = new ImageView(this);
        photoPreview.setContentDescription(tr("Pratinjau foto transaksi", "Transaction photo preview"));
        photoPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoPreview.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 14));
        photoPreview.setVisibility(View.GONE);
        content.addView(photoPreview, Ui.lp(-1, Ui.dp(this, 175)));
        LinearLayout photoActions = Ui.row(this);
        choosePhotoButton = photoButton(tr("Pilih foto", "Choose photo"), v -> pickPhoto());
        takePhotoButton = photoButton(tr("Ambil foto", "Take photo"), v -> takePhoto());
        photoActions.addView(choosePhotoButton, Ui.weighted(0, Ui.dp(this, 48), 1));
        photoActions.addView(Ui.gap(this, 8), Ui.lp(Ui.dp(this, 8), 1));
        photoActions.addView(takePhotoButton, Ui.weighted(0, Ui.dp(this, 48), 1));
        content.addView(photoActions);
        removePhotoButton = photoButton(tr("Hapus foto", "Remove photo"), v -> removePhoto());
        LinearLayout.LayoutParams photoButtonMargin = Ui.lp(-1, Ui.dp(this, 48));
        photoButtonMargin.topMargin = Ui.dp(this, 8);
        content.addView(removePhotoButton, photoButtonMargin);
        restorePhotoButton = photoButton(tr("Gunakan foto semula", "Use original photo"), v -> restorePhoto());
        LinearLayout.LayoutParams restoreMargin = Ui.lp(-1, Ui.dp(this, 48));
        restoreMargin.topMargin = Ui.dp(this, 8);
        content.addView(restorePhotoButton, restoreMargin);
        content.addView(Ui.gap(this, 12));
        content.addView(Ui.text(this, tr("Data hanya tersimpan di perangkat ini.", "Data is stored only on this device."), 11, Ui.MUTED, false));

        LinearLayout footer = Ui.column(this);
        footer.setBackgroundColor(Ui.WHITE);
        Ui.pad(footer, 22, 12, 22, 14);
        saveButton = Ui.text(this, tr("Simpan transaksi", "Save transaction"), 15, Ui.WHITE, true);
        saveButton.setGravity(Gravity.CENTER);
        Ui.clickable(saveButton, Ui.shape(this, Ui.GREEN, 16), v -> save());
        footer.addView(saveButton, Ui.lp(-1, Ui.dp(this, 52)));
        root.addView(footer);

        updateTabs();
        setCategories(category);
    }

    private void installAmountFormatting() {
        amountInput.addTextChangedListener(new TextWatcher() {
            private boolean formatting;
            private String before = "";
            private int start, removed, inserted, oldStart, oldEnd;

            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                if (formatting) return;
                before = text.toString();
                this.start = start;
                removed = count;
                inserted = after;
                oldStart = amountInput.getSelectionStart();
                oldEnd = amountInput.getSelectionEnd();
            }

            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }

            @Override public void afterTextChanged(Editable editable) {
                if (formatting) return;
                int selectionStart = Selection.getSelectionStart(editable);
                int selectionEnd = Selection.getSelectionEnd(editable);
                if (selectionStart < 0) selectionStart = start + inserted;
                if (selectionEnd < 0) selectionEnd = selectionStart;
                AmountInput.Edit edit = AmountInput.afterEdit(before, editable.toString(), start, removed, inserted,
                        oldStart, oldEnd, selectionStart, selectionEnd);
                if (edit.text.contentEquals(editable)) return;
                formatting = true;
                try {
                    editable.replace(0, editable.length(), edit.text);
                    Selection.setSelection(editable, Math.min(edit.selectionStart, editable.length()),
                            Math.min(edit.selectionEnd, editable.length()));
                } finally {
                    formatting = false;
                }
            }
        });
    }

    private TextView typeTab(String label) {
        TextView tab = Ui.text(this, label, 13, Ui.INK, true);
        tab.setGravity(Gravity.CENTER);
        tab.setFocusable(true);
        return tab;
    }

    private View fieldLabel(String title, boolean optional) {
        LinearLayout label = Ui.row(this);
        label.addView(Ui.overline(this, title, Ui.INK), Ui.weighted(0, -2, 1));
        if (optional) label.addView(Ui.text(this, tr("Opsional", "Optional"), 11, Ui.MUTED, false));
        return label;
    }

    private EditText input(String hint, boolean singleLine, int maxLength) {
        EditText input = new EditText(this);
        input.setTextSize(14);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(0xffa0ada2);
        input.setHint(hint);
        input.setSingleLine(singleLine);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(0, 0, 0, 0);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        return input;
    }

    private View inputBox(EditText input, int height) {
        LinearLayout box = Ui.row(this);
        box.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 15));
        Ui.pad(box, 15, 0, 15, 0);
        box.addView(input, Ui.weighted(0, Ui.dp(this, height), 1));
        return box;
    }

    private void updateTabs() {
        boolean expense = Transaction.EXPENSE.equals(type);
        expenseTab.setTextColor(expense ? Ui.WHITE : Ui.MUTED);
        expenseTab.setBackground(Ui.shape(this, expense ? Ui.GREEN : 0x00000000, 13));
        expenseTab.setContentDescription(tr("Pengeluaran", "Expense") + (expense ? tr(", dipilih", ", selected") : ""));
        incomeTab.setTextColor(expense ? Ui.MUTED : Ui.WHITE);
        incomeTab.setBackground(Ui.shape(this, expense ? 0x00000000 : Ui.GREEN, 13));
        incomeTab.setContentDescription(tr("Pemasukan", "Income") + (!expense ? tr(", dipilih", ", selected") : ""));
        if (titleInput != null) titleInput.setHint(expense ? tr("Contoh: Makan siang", "Example: Lunch") : tr("Contoh: Gaji bulanan", "Example: Monthly salary"));
    }

    private void changeType(String newType) {
        if (type.equals(newType)) return;
        String oldCategory = categoryInput.getSelectedItem() == null ? "" : categoryInput.getSelectedItem().toString();
        type = newType;
        updateTabs();
        setCategories(Categories.validForType(type, oldCategory) ? oldCategory : Categories.forType(type)[0]);
    }

    private void setCategories(String selected) {
        String[] categories = Categories.forType(type);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, categories) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView text = Ui.text(TransactionFormActivity.this, Categories.label(getItem(position), language), 14, Ui.INK, true);
                text.setGravity(Gravity.CENTER_VERTICAL);
                return text;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView text = Ui.text(TransactionFormActivity.this, Categories.label(getItem(position), language), 15, Ui.INK, false);
                Ui.pad(text, 18, 13, 18, 13);
                return text;
            }
        };
        categoryInput.setAdapter(adapter);
        int index = Arrays.asList(categories).indexOf(selected);
        categoryInput.setSelection(index < 0 ? 0 : index);
    }

    private String selectedCategory() {
        return categoryInput == null || categoryInput.getSelectedItem() == null ? "" : categoryInput.getSelectedItem().toString();
    }

    private void chooseDate() {
        Calendar selected = Dates.calendarFor(selectedDate);
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) -> {
            selectedDate = Dates.iso(year, month, day);
            dateText.setText(Dates.display(selectedDate, language));
            View parent = (View) dateText.getTag();
            parent.setContentDescription(tr("Pilih tanggal, ", "Choose date, ") + Dates.display(selectedDate, language));
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH));
        dialog.setTitle(tr("Pilih tanggal transaksi", "Choose transaction date"));
        dialog.getDatePicker().setMinDate(Dates.calendarFor("1900-01-01").getTimeInMillis());
        dialog.getDatePicker().setMaxDate(Dates.calendarFor("2100-12-31").getTimeInMillis());
        dialog.show();
    }

    private TextView photoButton(String text, View.OnClickListener click) {
        TextView button = Ui.text(this, text, 13, Ui.GREEN, true);
        button.setGravity(Gravity.CENTER);
        Ui.clickable(button, Ui.outlined(this, Ui.WHITE, Ui.LINE, 14), click);
        return button;
    }

    private boolean hasCurrentPhoto() {
        return photoAction == FinanceStore.PhotoAction.REPLACE
                || (photoAction == FinanceStore.PhotoAction.KEEP && original != null && original.hasPhoto);
    }

    private void updatePhotoUi() {
        if (photoStatus == null) return;
        boolean hasPhoto = hasCurrentPhoto();
        if (photoBusy) photoStatus.setText(tr("Memproses foto…", "Processing photo…"));
        else if (photoAction == FinanceStore.PhotoAction.REMOVE) photoStatus.setText(tr("Foto akan dihapus saat transaksi disimpan.", "The photo will be removed when the transaction is saved."));
        else if (photoAction == FinanceStore.PhotoAction.REPLACE) photoStatus.setText(tr("Foto baru siap disimpan.", "The new photo is ready to save."));
        else if (hasPhoto) photoStatus.setText(tr("Foto tersimpan pada transaksi.", "A photo is saved with this transaction."));
        else photoStatus.setText(tr("Belum ada foto.", "No photo attached."));
        choosePhotoButton.setText(hasPhoto ? tr("Ganti foto", "Replace photo") : tr("Pilih foto", "Choose photo"));
        removePhotoButton.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        restorePhotoButton.setVisibility(original != null && original.hasPhoto
                && photoAction != FinanceStore.PhotoAction.KEEP ? View.VISIBLE : View.GONE);
        for (TextView button : new TextView[]{choosePhotoButton, takePhotoButton, removePhotoButton, restorePhotoButton}) {
            button.setEnabled(!photoBusy);
            button.setAlpha(photoBusy ? .5f : 1f);
        }
        if (saveButton != null) {
            saveButton.setEnabled(!photoBusy);
            saveButton.setAlpha(photoBusy ? .5f : 1f);
        }
    }

    private void setPhotoBitmap(Bitmap bitmap) {
        if (photoPreview != null) {
            photoPreview.setImageBitmap(bitmap);
            photoPreview.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
        }
        // Previously displayed bitmaps are released by Android after rendering completes.
    }

    private void requestPhotoPreview() {
        final long generation = ++previewGeneration;
        if (!hasCurrentPhoto()) { setPhotoBitmap(null); return; }
        final Context context = getApplicationContext();
        final boolean fromDraft = photoAction == FinanceStore.PhotoAction.REPLACE;
        final String currentDraft = draftName;
        final long id = transactionId;
        // Freeze the database choice before the worker runs (also isolates debug UI tests).
        final FinanceStore reader = fromDraft ? null : new FinanceStore(context);
        final WeakReference<TransactionFormActivity> weak = new WeakReference<>(this);
        final Handler handler = photoHandler;
        photoExecutor.execute(() -> {
            Bitmap preview = null;
            try {
                byte[] bytes;
                if (fromDraft) bytes = PhotoProcessor.readDraft(context, currentDraft);
                else {
                    try { bytes = reader.getPhoto(id); } finally { reader.close(); }
                }
                preview = PhotoProcessor.preview(bytes, 600);
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) { }
            final Bitmap result = preview;
            if (!handler.post(() -> {
                TransactionFormActivity activity = weak.get();
                if (activity == null || activity.destroyed || activity.previewGeneration != generation) {
                    if (result != null && !result.isRecycled()) result.recycle();
                    return;
                }
                activity.setPhotoBitmap(result);
                if (result == null && !activity.photoBusy) {
                    if (!fromDraft) activity.photoStatus.setText(activity.tr("Foto tersimpan, tetapi pratinjau tidak tersedia. Foto tidak diubah.", "The photo is saved, but its preview is unavailable. The photo was not changed."));
                    else if (activity.original != null && activity.original.hasPhoto)
                        activity.photoStatus.setText(activity.tr("Foto pilihan tidak tersedia. Pilih ulang atau gunakan foto semula.", "The selected photo is unavailable. Choose it again or use the original photo."));
                    else activity.photoStatus.setText(activity.tr("Foto pilihan tidak tersedia. Pilih ulang atau hapus foto.", "The selected photo is unavailable. Choose it again or remove the photo."));
                }
            }) && result != null && !result.isRecycled()) result.recycle();
        });
    }

    private void pickPhoto() {
        if (photoBusy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_PHOTO); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, tr("Pemilih foto tidak tersedia di perangkat ini.", "A photo picker is not available on this device."), Toast.LENGTH_LONG).show();
        }
    }

    private void takePhoto() {
        if (photoBusy || captureName != null) return;
        try {
            captureName = PhotoFiles.createCapture(this);
            Uri uri = PhotoCaptureProvider.uri(this, captureName);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.setClipData(ClipData.newRawUri(tr("Foto transaksi", "Transaction photo"), uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, TAKE_PHOTO);
        } catch (IOException | ActivityNotFoundException | SecurityException e) {
            discardCapture(captureName);
            captureName = null;
            Toast.makeText(this, tr("Kamera tidak tersedia. Pilih foto dari perangkat.", "The camera is unavailable. Choose a photo from the device."), Toast.LENGTH_LONG).show();
        }
    }

    private void discardCapture(String name) {
        if (!PhotoFiles.validName(name, "capture")) return;
        try { revokeUriPermission(PhotoCaptureProvider.uri(this, name),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        catch (RuntimeException ignored) { }
        PhotoFiles.delete(this, name, "capture");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PHOTO && requestCode != TAKE_PHOTO) return;
        stateSaved = false; // The picker has returned and the activity is resuming.
        if (requestCode == PICK_PHOTO) {
            if (resultCode != RESULT_OK) return;
            Uri uri = data == null ? null : data.getData();
            if (uri == null) { showPhotoError(); return; }
            beginPhotoImport(uri, null);
        } else {
            String capture = captureName;
            captureName = null;
            if (PhotoFiles.validName(capture, "capture")) {
                try { revokeUriPermission(PhotoCaptureProvider.uri(this, capture),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                catch (RuntimeException ignored) { }
            }
            if (resultCode != RESULT_OK) { PhotoFiles.delete(this, capture, "capture"); return; }
            if (!PhotoFiles.validName(capture, "capture")) { showPhotoError(); return; }
            beginPhotoImport(null, capture);
        }
    }

    private void beginPhotoImport(Uri uri, String capture) {
        if (photoBusy) { PhotoFiles.delete(this, capture, "capture"); return; }
        photoBusy = true;
        processingCaptureName = capture;
        updatePhotoUi();
        final long generation = ++importGeneration;
        final Context context = getApplicationContext();
        final WeakReference<TransactionFormActivity> weak = new WeakReference<>(this);
        final Handler handler = photoHandler;
        importTask = photoExecutor.submit(() -> {
            String name = null;
            Bitmap preview = null;
            boolean failed = false;
            try {
                name = capture == null ? PhotoProcessor.importUri(context, uri) : PhotoProcessor.importCapture(context, capture);
                preview = PhotoProcessor.preview(PhotoProcessor.readDraft(context, name), 600);
                if (preview == null) throw new IOException("Pratinjau foto tidak tersedia");
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                failed = true;
            } finally {
                PhotoFiles.delete(context, capture, "capture");
            }
            final String resultName = name;
            final Bitmap resultPreview = preview;
            final boolean resultFailed = failed;
            Runnable deliver = () -> {
                TransactionFormActivity activity = weak.get();
                if (activity == null || activity.destroyed || activity.importGeneration != generation || activity.stateSaved) {
                    PhotoFiles.delete(context, resultName, "draft");
                    if (resultPreview != null && !resultPreview.isRecycled()) resultPreview.recycle();
                    if (activity != null && !activity.destroyed && activity.importGeneration == generation) {
                        activity.photoBusy = false;
                        activity.processingCaptureName = null;
                        activity.pendingRechooseMessage = true;
                        activity.updatePhotoUi();
                    }
                    return;
                }
                activity.photoBusy = false;
                activity.processingCaptureName = null;
                activity.importTask = null;
                if (resultFailed) {
                    PhotoFiles.delete(context, resultName, "draft");
                    if (resultPreview != null && !resultPreview.isRecycled()) resultPreview.recycle();
                    activity.updatePhotoUi();
                    activity.showPhotoError();
                    return;
                }
                PhotoFiles.delete(context, activity.draftName, "draft");
                activity.draftName = resultName;
                activity.photoAction = FinanceStore.PhotoAction.REPLACE;
                activity.previewGeneration++;
                activity.setPhotoBitmap(resultPreview);
                activity.updatePhotoUi();
            };
            if (!handler.post(deliver)) {
                PhotoFiles.delete(context, resultName, "draft");
                if (resultPreview != null && !resultPreview.isRecycled()) resultPreview.recycle();
            }
        });
    }

    private void showPhotoError() {
        new AlertDialog.Builder(this).setTitle(tr("Foto belum bisa dipakai", "Photo cannot be used"))
                .setMessage(tr("Pilih foto yang dapat dibuka dan berukuran maksimal 20 MiB, lalu coba lagi. ", "Choose a photo that can be opened and is no larger than 20 MiB, then try again. ")
                        + tr("Foto baru tidak digunakan; pilihan sebelumnya tetap seperti semula.", "The new photo was not used; the previous selection is unchanged."))
                .setPositiveButton(tr("Tutup", "Close"), null).show();
    }

    private void removePhoto() {
        if (photoBusy) return;
        PhotoFiles.delete(this, draftName, "draft");
        draftName = null;
        photoAction = original != null && original.hasPhoto ? FinanceStore.PhotoAction.REMOVE : FinanceStore.PhotoAction.KEEP;
        previewGeneration++;
        setPhotoBitmap(null);
        updatePhotoUi();
    }

    private void restorePhoto() {
        if (photoBusy || original == null || !original.hasPhoto) return;
        PhotoFiles.delete(this, draftName, "draft");
        draftName = null;
        photoAction = FinanceStore.PhotoAction.KEEP;
        setPhotoBitmap(null);
        updatePhotoUi();
        requestPhotoPreview();
    }

    @Override protected void onResume() {
        super.onResume();
        stateSaved = false;
        if (pendingRechooseMessage) {
            pendingRechooseMessage = false;
            Toast.makeText(this, tr("Pemrosesan foto terhenti. Pilih kembali; foto sebelumnya tidak diubah.", "Photo processing was interrupted. Choose again; the previous photo was not changed."), Toast.LENGTH_LONG).show();
        }
    }

    private void save() {
        String rawAmount = amountInput.getText().toString();
        String title = titleInput.getText().toString();
        String note = noteInput.getText().toString();
        String category = selectedCategory();
        FinanceMath.ValidationError error = FinanceMath.validate(type, rawAmount, title, category, selectedDate, note, language);
        if (error != null) {
            if ("amount".equals(error.field)) { amountInput.setError(error.message); amountInput.requestFocus(); }
            else if ("title".equals(error.field)) { titleInput.setError(error.message); titleInput.requestFocus(); }
            else if ("note".equals(error.field)) { noteInput.setError(error.message); noteInput.requestFocus(); }
            else Toast.makeText(this, error.message, Toast.LENGTH_LONG).show();
            return;
        }
        if (photoBusy) {
            Toast.makeText(this, tr("Tunggu sampai foto selesai diproses.", "Wait until the photo finishes processing."), Toast.LENGTH_SHORT).show();
            return;
        }
        saveButton.setEnabled(false);
        try {
            byte[] photo = photoAction == FinanceStore.PhotoAction.REPLACE
                    ? PhotoProcessor.readDraft(this, draftName) : null;
            Transaction transaction = new Transaction(transactionId, type, Money.parseAmount(rawAmount),
                    title.trim(), category, selectedDate, note.trim());
            store.save(transaction, photoAction, photo);
            Intent result = new Intent();
            result.putExtra("saved_date", selectedDate);
            setResult(RESULT_OK, result);
            Toast.makeText(this, original == null ? tr("Transaksi tersimpan", "Transaction saved") : tr("Transaksi diperbarui", "Transaction updated"), Toast.LENGTH_SHORT).show();
            finish();
        } catch (IOException e) {
            saveButton.setEnabled(true);
            new AlertDialog.Builder(this).setTitle(tr("Foto pilihan tidak tersedia", "Selected photo is unavailable"))
                    .setMessage(tr("Transaksi belum disimpan. Pilih ulang foto, gunakan foto semula, atau hapus foto sebelum menyimpan.", "The transaction was not saved. Choose the photo again, use the original, or remove it before saving."))
                    .setPositiveButton(tr("Tutup", "Close"), null).show();
        } catch (RuntimeException e) {
            saveButton.setEnabled(true);
            new AlertDialog.Builder(this).setTitle(tr("Belum bisa menyimpan", "Unable to save"))
                    .setMessage(tr("Transaksi belum tersimpan. Periksa ruang penyimpanan dan coba lagi.", "The transaction was not saved. Check device storage and try again."))
                    .setPositiveButton(tr("Tutup", "Close"), null).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (amountInput == null) return;
        out.putString("initial_date", initialDate);
        out.putString("type", type);
        out.putString("date", selectedDate);
        out.putString("amount", amountInput.getText().toString());
        out.putInt("amount_selection_start", amountInput.getSelectionStart());
        out.putInt("amount_selection_end", amountInput.getSelectionEnd());
        out.putString("title", titleInput.getText().toString());
        out.putString("note", noteInput.getText().toString());
        out.putString("category", selectedCategory());
        out.putString("photo_action", photoAction.name());
        out.putString("photo_draft", draftName);
        out.putString("photo_capture", captureName);
        out.putBoolean("photo_importing", photoBusy || pendingRechooseMessage);
        stateSaved = true;
    }

    private boolean hasChanges() {
        if (amountInput == null) return false;
        String originalAmount = original == null ? "" : AmountInput.forDisplay(Long.toString(original.amount));
        String originalTitle = original == null ? "" : original.title;
        String originalNote = original == null ? "" : original.note;
        String originalType = original == null ? Transaction.EXPENSE : original.type;
        String originalCategory = original == null ? Categories.forType(originalType)[0] : original.category;
        return !amountInput.getText().toString().equals(originalAmount)
                || !titleInput.getText().toString().equals(originalTitle)
                || !noteInput.getText().toString().equals(originalNote)
                || !type.equals(originalType) || !selectedDate.equals(initialDate)
                || !selectedCategory().equals(originalCategory)
                || photoAction != FinanceStore.PhotoAction.KEEP || photoBusy;
    }

    @Override public void onBackPressed() {
        if (!hasChanges()) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setTitle(tr("Buang perubahan?", "Discard changes?"))
                .setMessage(tr("Perubahan yang belum disimpan akan hilang.", "Unsaved changes will be lost."))
                .setPositiveButton(tr("Buang", "Discard"), (dialog, which) -> finish())
                .setNegativeButton(tr("Lanjutkan mengisi", "Keep editing"), null).show();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        importGeneration++;
        previewGeneration++;
        if (importTask != null) importTask.cancel(true);
        if (photoExecutor != null) photoExecutor.shutdownNow();
        PhotoFiles.delete(this, processingCaptureName, "capture");
        // A stopped activity may be recreated from its saved state even without a rotation.
        boolean keepForRestore = isChangingConfigurations() || (stateSaved && !isFinishing());
        if (!keepForRestore) {
            PhotoFiles.delete(this, draftName, "draft");
            discardCapture(captureName);
        }
        setPhotoBitmap(null);
        if (store != null) store.close();
        super.onDestroy();
    }
}
