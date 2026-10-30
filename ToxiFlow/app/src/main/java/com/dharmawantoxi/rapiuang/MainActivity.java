package com.dharmawantoxi.rapiuang;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintJob;
import android.print.PrintManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int FORM_REQUEST = 20;
    private static final int EXPORT_REQUEST = 21;
    private static final int HOME = 0, TRANSACTIONS = 1, CASH = 2, REPORTS = 3;
    private FinanceStore store;
    private LinearLayout root;
    private LinearLayout transactionList;
    private List<Transaction> all = new ArrayList<>();
    private List<Transaction> month = new ArrayList<>();
    private List<DebtRecord> debtRecords = new ArrayList<>();
    private List<SavingsMovement> savingsMovements = new ArrayList<>();
    private BigInteger availableFunds = BigInteger.ZERO;
    private BigInteger savingsBalance = BigInteger.ZERO;
    private int page = HOME;
    private int year, monthIndex;
    private String filter = "";
    private String search = "";
    private boolean hideAmounts;
    private boolean loadFailed;
    private AppLanguage language = AppLanguage.INDONESIAN;
    private final ExecutorService photoReader = Executors.newSingleThreadExecutor();
    private final List<Dialog> photoDialogs = new ArrayList<>();
    private final Handler photoHandler = new Handler(Looper.getMainLooper());

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguageSettings.wrap(base));
    }

    private String tr(String indonesian, String english) {
        return language.text(indonesian, english);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        // The launcher component keeps its original name so existing shortcuts survive updates.
        setTheme(R.style.Theme_ToxiFlow);
        super.onCreate(savedInstanceState);
        language = LanguageSettings.get(this);
        if (savedInstanceState == null && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            startActivity(new Intent(this, SplashActivity.class));
            finish();
            return;
        }
        store = new FinanceStore(this);
        PhotoFiles.pruneOld(this);
        PrintSnapshot.pruneOld(this);
        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        monthIndex = now.get(Calendar.MONTH);
        hideAmounts = getPreferences(MODE_PRIVATE).getBoolean("hide_dashboard_amounts", false);
        if (savedInstanceState != null) {
            page = savedInstanceState.getInt("page", HOME);
            year = savedInstanceState.getInt("year", year);
            monthIndex = savedInstanceState.getInt("month", monthIndex);
            filter = savedInstanceState.getString("filter", "");
            search = savedInstanceState.getString("search", "");
        }
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        Ui.setupWindow(this, root);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (store != null) refresh(); // A launcher redirect can finish before initialization.
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
        out.putInt("year", year);
        out.putInt("month", monthIndex);
        out.putString("filter", filter);
        out.putString("search", search);
    }

    @Override protected void onDestroy() {
        for (Dialog dialog : new ArrayList<>(photoDialogs)) dialog.dismiss();
        photoDialogs.clear();
        photoReader.shutdownNow();
        if (store != null) store.close(); // Launcher redirects before the store is opened.
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (page != HOME && !loadFailed) {
            page = HOME;
            render();
        } else {
            super.onBackPressed();
        }
    }

    private void refresh() {
        try {
            all = store.all();
            month = FinanceMath.forMonth(all, year, monthIndex);
            debtRecords = store.allDebtRecords();
            savingsMovements = store.allSavingsMovements();
            availableFunds = store.availableFunds();
            savingsBalance = store.savingsBalance();
            loadFailed = false;
        } catch (RuntimeException e) {
            loadFailed = true;
        }
        render();
    }

    private void render() {
        root.removeAllViews();
        if (loadFailed) {
            LinearLayout problem = Ui.column(this);
            problem.setGravity(Gravity.CENTER);
            Ui.pad(problem, 28, 20, 28, 20);
            TextView heading = Ui.text(this, tr("Data belum bisa dibuka", "Data could not be opened"), 20, Ui.INK, true);
            TextView description = Ui.text(this, tr("Periksa ruang penyimpanan perangkat, lalu coba lagi. Data tidak dihapus.", "Check device storage and try again. Your data has not been deleted."), 14, Ui.MUTED, false);
            description.setGravity(Gravity.CENTER);
            problem.addView(heading);
            problem.addView(Ui.gap(this, 8));
            problem.addView(description);
            problem.addView(Ui.gap(this, 18));
            TextView retry = Ui.text(this, tr("Coba lagi", "Try again"), 15, Ui.WHITE, true);
            retry.setGravity(Gravity.CENTER);
            Ui.pad(retry, 24, 12, 24, 12);
            Ui.clickable(retry, Ui.shape(this, Ui.GREEN, 14), v -> refresh());
            problem.addView(retry);
            root.addView(problem, Ui.weighted(-1, 0, 1));
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 22, 17, 22, 24);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        if (page == HOME) buildHome(content);
        else if (page == TRANSACTIONS) buildTransactions(content);
        else if (page == CASH) buildCash(content);
        else buildReports(content);
        root.addView(scroll, Ui.weighted(-1, 0, 1));
        root.addView(bottomNavigation(), Ui.lp(-1, Ui.dp(this, 76)));
    }

    private void buildHome(LinearLayout content) {
        content.addView(brandHeader());
        content.addView(Ui.gap(this, 22));
        content.addView(Ui.overline(this, tr("RUANG UNTUK KEUANGANMU", "SPACE FOR YOUR FINANCES"), Ui.GREEN));
        content.addView(Ui.gap(this, 5));
        content.addView(Ui.display(this, tr("Uang lebih rapi,\nhidup lebih tenang.", "Clearer finances,\nmore peace of mind."), 27));
        content.addView(Ui.gap(this, 7));
        content.addView(Ui.text(this, tr("Pelan-pelan, mulai dari satu catatan.", "One step at a time, starting with one entry."), 13, Ui.MUTED, false));
        content.addView(Ui.gap(this, 20));
        content.addView(monthSelector());
        content.addView(Ui.gap(this, 12));
        content.addView(balanceCard());
        content.addView(Ui.gap(this, 14));
        content.addView(addTransactionCallout());
        content.addView(Ui.gap(this, 25));
        content.addView(sectionHeading(tr("Transaksi terbaru", "Recent transactions"), tr("Lihat semua", "See all"), () -> {
            page = TRANSACTIONS;
            filter = "";
            search = "";
            render();
        }));
        content.addView(Ui.gap(this, 4));
        content.addView(Ui.text(this, tr("Di periode ", "In ") + Dates.monthLabel(year, monthIndex, language), 12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 12));
        if (month.isEmpty()) content.addView(emptyState(false));
        else {
            LinearLayout list = Ui.column(this);
            list.setBackground(Ui.shape(this, Ui.WHITE, 20));
            Ui.pad(list, 14, 2, 14, 2);
            int count = Math.min(4, month.size());
            for (int i = 0; i < count; i++) {
                if (i > 0) list.addView(insetDivider());
                list.addView(transactionRow(month.get(i), true));
            }
            content.addView(list);
        }
        content.addView(Ui.gap(this, 18));
        LinearLayout privacy = Ui.row(this);
        privacy.setGravity(Gravity.CENTER);
        privacy.addView(new IconView(this, "lock", Ui.MUTED), Ui.lp(Ui.dp(this, 13), Ui.dp(this, 13)));
        TextView caption = Ui.text(this, tr("  Tersimpan di perangkatmu", "  Stored on your device"), 11, Ui.MUTED, false);
        privacy.addView(caption);
        content.addView(privacy);
    }

    private View brandHeader() {
        LinearLayout row = Ui.row(this);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.mipmap.ic_launcher);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout wordmark = Ui.column(this);
        LinearLayout.LayoutParams brandParams = Ui.weighted(0, -2, 1);
        brandParams.leftMargin = Ui.dp(this, 10);
        row.addView(wordmark, brandParams);
        wordmark.addView(Ui.text(this, "ToxiFlow", 17, Ui.INK, true));
        wordmark.addView(Ui.text(this, tr("CATATAN KEUANGAN PRIBADI", "PERSONAL FINANCE JOURNAL"), 9, Ui.MUTED, true));
        addHeaderActions(row);
        return row;
    }

    private void buildCash(LinearLayout content) {
        content.addView(pageHeader(tr("Dana & tabungan", "Cash & savings"),
                tr("Atur bagian transaksi yang masuk ke atau diambil dari tabungan.",
                        "Set how much of a transaction goes into or comes from savings.")));
        content.addView(Ui.gap(this, 20));
        content.addView(Ui.text(this,
                tr("Transaksi biasa mengubah dana tersedia. Tentukan bagian tabungan langsung di transaksi, atau pindahkan saldo secara terpisah; alokasi dan perpindahan tabungan tidak dihitung sebagai pemasukan/pengeluaran tambahan.",
                        "Ordinary transactions affect available funds. Allocate a portion to savings in the transaction or transfer it separately; savings allocations and transfers are not counted as extra income/expenses."),
                12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 16));
        content.addView(monthSelector());
        content.addView(Ui.gap(this, 12));
        content.addView(cashBalanceCard());
        content.addView(Ui.gap(this, 12));
        content.addView(savingsCard());
        content.addView(Ui.gap(this, 24));
        content.addView(debtSection(DebtRecord.DEBT));
        content.addView(Ui.gap(this, 14));
        content.addView(debtSection(DebtRecord.RECEIVABLE));
        content.addView(Ui.gap(this, 24));
        content.addView(sectionHeading(tr("Pergerakan dana", "Cash activity"), null, null));
        content.addView(Ui.gap(this, 10));
        if (month.isEmpty()) {
            LinearLayout empty = Ui.column(this);
            empty.setBackground(Ui.shape(this, Ui.WHITE, 18));
            Ui.pad(empty, 18, 18, 18, 18);
            empty.addView(Ui.text(this, tr("Belum ada transaksi pada periode ini", "No transactions in this period"), 14, Ui.INK, true));
            empty.addView(Ui.gap(this, 4));
            empty.addView(Ui.text(this, tr("Transaksi biasa memakai dana tersedia kecuali bagian tabungan ditentukan.",
                    "Ordinary transactions use available funds unless a savings portion is specified."), 12, Ui.MUTED, false));
            content.addView(empty);
            content.addView(Ui.gap(this, 14));
            content.addView(addTransactionCallout());
        } else {
            LinearLayout list = Ui.column(this);
            list.setBackground(Ui.shape(this, Ui.WHITE, 18));
            Ui.pad(list, 14, 2, 14, 2);
            for (int i = 0; i < month.size(); i++) {
                if (i > 0) list.addView(insetDivider());
                list.addView(transactionRow(month.get(i), true));
            }
            content.addView(list);
        }
    }

    private View savingsCard() {
        DecorCard card = new DecorCard(this);
        LinearLayout inside = Ui.column(this);
        Ui.pad(inside, 18, 16, 18, 17);
        inside.addView(Ui.overline(this, tr("SALDO TABUNGAN", "SAVINGS BALANCE"), 0xffd8c6f1));
        inside.addView(Ui.gap(this, 4));
        TextView amount = Ui.text(this, Money.format(savingsBalance), 26, Ui.WHITE, true);
        amount.setContentDescription(tr("Saldo tabungan ", "Savings balance ") + Money.format(savingsBalance));
        amount.setTag("savings_balance");
        inside.addView(amount);
        inside.addView(Ui.gap(this, 5));
        inside.addView(Ui.text(this, tr("Terpisah dari dana tersedia dan laporan transaksi.",
                "Separate from available funds and transaction reports."), 11, 0xffd9d1e7, false));
        inside.addView(Ui.gap(this, 13));
        LinearLayout actions = Ui.row(this);
        actions.addView(savingsAction(tr("Tambah tabungan", "Add to savings"), true), Ui.weighted(0, -2, 1));
        View spacer = new View(this);
        actions.addView(spacer, Ui.lp(Ui.dp(this, 8), 1));
        actions.addView(savingsAction(tr("Ambil dari tabungan", "Withdraw savings"), false), Ui.weighted(0, -2, 1));
        inside.addView(actions);
        card.addView(inside, new FrameLayout.LayoutParams(-1, -2));
        return card;
    }

    private TextView savingsAction(String label, boolean deposit) {
        TextView button = Ui.text(this, label, 12, Ui.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(Ui.dp(this, 44));
        Ui.pad(button, 8, 8, 8, 8);
        Ui.clickable(button, Ui.shape(this, deposit ? 0xff5c4d75 : 0xff445c50, 12),
                v -> openSavingsMovement(deposit));
        return button;
    }

    private void openSavingsMovement(boolean deposit) {
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 24, 5, 24, 8);
        content.addView(Ui.text(this, deposit
                ? tr("Dana tersedia saat ini: ", "Available funds: ") + Money.format(availableFunds)
                : tr("Saldo tabungan saat ini: ", "Current savings balance: ") + Money.format(savingsBalance),
                12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 12));
        EditText amount = debtTextField(tr("Nominal rupiah", "Amount in rupiah"),
                tr("Nominal perpindahan tabungan dalam rupiah", "Savings transfer amount in rupiah"), 20,
                android.text.InputType.TYPE_CLASS_NUMBER);
        content.addView(amount, Ui.lp(-1, Ui.dp(this, 50)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(deposit ? tr("Tambah ke tabungan", "Add to savings") : tr("Ambil dari tabungan", "Withdraw from savings"))
                .setView(content).setNegativeButton(tr("Batal", "Cancel"), null)
                .setPositiveButton(deposit ? tr("Simpan", "Save") : tr("Ambil", "Withdraw"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final long value;
            try { value = Money.parseAmount(amount.getText().toString(), language); }
            catch (IllegalArgumentException error) { amount.setError(error.getMessage()); amount.requestFocus(); return; }
            try {
                store.saveSavingsMovement(deposit ? "deposit" : "withdrawal", value, Dates.today());
                dialog.dismiss();
                Toast.makeText(this, deposit ? tr("Tabungan diperbarui", "Savings updated")
                        : tr("Penarikan tabungan tersimpan", "Savings withdrawal saved"), Toast.LENGTH_SHORT).show();
                refresh();
            } catch (IllegalArgumentException error) {
                String message = error.getMessage();
                if (message != null && message.startsWith("Dana tersedia tidak cukup"))
                    message = tr("Dana tersedia tidak cukup untuk ditabung.", "Not enough available funds to add to savings.");
                else if (message != null && message.startsWith("Saldo tabungan tidak cukup"))
                    message = tr("Saldo tabungan tidak cukup untuk diambil.", "Not enough savings to withdraw.");
                else if (message != null && message.startsWith("Nominal atau tanggal"))
                    message = tr("Nominal atau tanggal perpindahan tabungan tidak valid.", "Invalid savings transfer amount or date.");
                amount.setError(message);
                amount.requestFocus();
            } catch (RuntimeException error) { showDebtStorageError(); }
        }));
        dialog.show();
    }

    private View cashBalanceCard() {
        DecorCard card = new DecorCard(this);
        LinearLayout inside = Ui.column(this);
        Ui.pad(inside, 20, 17, 20, 18);
        inside.addView(Ui.overline(this, tr("DANA TERSEDIA", "AVAILABLE FUNDS"), 0xffc6ddca));
        inside.addView(Ui.gap(this, 5));
        BigInteger total = availableFunds;
        TextView amount = Ui.text(this, Money.format(total), 29, Ui.WHITE, true);
        amount.setContentDescription(tr("Dana siap pakai ", "Available funds ") + Money.format(total));
        amount.setTag("cash_balance");
        inside.addView(amount);
        inside.addView(Ui.gap(this, 15));
        FinanceMath.Summary period = FinanceMath.summarize(month);
        LinearLayout flows = Ui.row(this);
        flows.addView(cashPeriodAmount(tr("Pemasukan bulan ini", "Income this month"), period.income, Ui.MINT), Ui.weighted(0, -2, 1));
        flows.addView(cashPeriodAmount(tr("Pengeluaran bulan ini", "Expenses this month"), period.expense, 0xffffcbbc), Ui.weighted(0, -2, 1));
        inside.addView(flows);
        card.addView(inside, new FrameLayout.LayoutParams(-1, -2));
        return card;
    }

    private LinearLayout cashPeriodAmount(String label, BigInteger amount, int color) {
        LinearLayout item = Ui.column(this);
        item.addView(Ui.text(this, label, 10, 0xffc9d9d0, false));
        item.addView(Ui.gap(this, 3));
        item.addView(Ui.text(this, Money.format(amount), 13, color, true));
        return item;
    }

    private View debtSection(String type) {
        boolean debt = DebtRecord.DEBT.equals(type);
        LinearLayout section = Ui.column(this);
        section.addView(sectionHeading(debt ? tr("Hutang", "Debt") : tr("Piutang", "Receivable"),
                debt ? tr("Tambah hutang", "Add debt") : tr("Tambah piutang", "Add receivable"), () -> openDebtForm(type)));
        section.addView(Ui.gap(this, 9));
        List<DebtRecord> matching = new ArrayList<>();
        for (DebtRecord record : debtRecords) if (type.equals(record.type)) matching.add(record);
        if (matching.isEmpty()) {
            LinearLayout empty = Ui.column(this);
            empty.setBackground(Ui.shape(this, Ui.WHITE, 16));
            Ui.pad(empty, 15, 13, 15, 13);
            empty.addView(Ui.text(this, debt ? tr("Belum ada hutang", "No debts yet") : tr("Belum ada piutang", "No receivables yet"), 12, Ui.MUTED, false));
            section.addView(empty);
        } else {
            LinearLayout list = Ui.column(this);
            list.setBackground(Ui.shape(this, Ui.WHITE, 18));
            Ui.pad(list, 13, 2, 13, 2);
            for (int i = 0; i < matching.size(); i++) {
                if (i > 0) list.addView(insetDivider());
                list.addView(debtRow(matching.get(i)));
            }
            section.addView(list);
        }
        return section;
    }

    private View debtRow(DebtRecord record) {
        boolean debt = DebtRecord.DEBT.equals(record.type);
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(Ui.dp(this, 66));
        Ui.pad(row, 3, 9, 3, 9);
        LinearLayout labels = Ui.column(this);
        LinearLayout.LayoutParams labelParams = Ui.weighted(0, -2, 1);
        labelParams.rightMargin = Ui.dp(this, 8);
        labels.addView(Ui.text(this, record.person, 13, Ui.INK, true));
        labels.addView(Ui.text(this, Dates.display(record.date, language)
                + (record.note.isEmpty() ? "" : " · " + record.note), 10, Ui.MUTED, false));
        String remaining = tr("Sisa ", "Remaining ") + Money.format(record.remaining)
                + tr(" dari ", " of ") + Money.format(record.amount);
        labels.addView(Ui.text(this, remaining, 11, record.isSettled() ? Ui.MUTED : (debt ? Ui.CORAL : Ui.GREEN), true));
        row.addView(labels, labelParams);
        TextView action = Ui.text(this, record.isSettled() ? tr("Lunas", "Settled")
                : debt ? tr("Bayar", "Pay") : tr("Terima", "Collect"), 11,
                record.isSettled() ? Ui.MUTED : Ui.GREEN, true);
        action.setGravity(Gravity.CENTER);
        Ui.pad(action, 10, 9, 10, 9);
        if (!record.isSettled()) {
            action.setBackground(Ui.shape(this, Ui.PALE, 10));
            action.setOnClickListener(v -> openDebtPayment(record));
            action.setFocusable(true);
        }
        row.addView(action);
        row.setContentDescription((debt ? tr("Hutang kepada ", "Debt owed to ") : tr("Piutang dari ", "Receivable from "))
                + record.person + ", " + remaining + (record.isSettled() ? tr(", lunas", ", settled") : ""));
        row.setOnClickListener(v -> showDebtRecord(record));
        row.setFocusable(true);
        return row;
    }

    private void openDebtForm(String type) {
        boolean debt = DebtRecord.DEBT.equals(type);
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 24, 5, 24, 8);
        content.addView(Ui.text(this, debt
                ? tr("Uang yang diterima menambah Dana Siap Pakai.", "Money received increases available funds.")
                : tr("Uang yang dipinjamkan mengurangi Dana Siap Pakai.", "Money lent reduces available funds."), 12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 12));
        EditText person = debtTextField(debt ? tr("Nama pemberi hutang", "Lender's name") : tr("Nama peminjam", "Borrower's name"),
                debt ? tr("Nama pemberi hutang", "Lender's name") : tr("Nama peminjam", "Borrower's name"),
                80, android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.addView(person, Ui.lp(-1, Ui.dp(this, 50)));
        content.addView(Ui.gap(this, 10));
        EditText amount = debtTextField(tr("Nominal rupiah", "Amount in rupiah"),
                tr("Nominal hutang/piutang dalam rupiah", "Debt/receivable amount in rupiah"), 20, android.text.InputType.TYPE_CLASS_NUMBER);
        content.addView(amount, Ui.lp(-1, Ui.dp(this, 50)));
        content.addView(Ui.gap(this, 10));
        EditText note = debtTextField(tr("Keterangan (opsional)", "Description (optional)"),
                tr("Keterangan hutang/piutang, opsional", "Optional debt/receivable description"), 300,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.addView(note, Ui.lp(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(debt ? tr("Catat hutang", "Record debt") : tr("Catat piutang", "Record receivable"))
                .setView(content).setNegativeButton(tr("Batal", "Cancel"), null)
                .setPositiveButton(tr("Simpan", "Save"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final long value;
            try { value = Money.parseAmount(amount.getText().toString(), language); }
            catch (IllegalArgumentException error) { amount.setError(error.getMessage()); amount.requestFocus(); return; }
            try {
                store.saveDebtRecord(type, person.getText().toString(), value, Dates.today(), note.getText().toString());
                dialog.dismiss();
                Toast.makeText(this, debt ? tr("Hutang tersimpan", "Debt saved") : tr("Piutang tersimpan", "Receivable saved"), Toast.LENGTH_SHORT).show();
                refresh();
            } catch (IllegalArgumentException error) {
                if (person.getText().toString().trim().isEmpty())
                    person.setError(tr("Nama wajib diisi.", "Name is required."));
                else amount.setError(tr("Data hutang/piutang tidak valid. Periksa kembali nominal dan catatan.",
                        "Invalid debt/receivable details. Check the amount and description."));
            } catch (RuntimeException error) {
                showDebtStorageError();
            }
        }));
        dialog.show();
    }

    private EditText debtTextField(String hint, String description, int maxLength, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setContentDescription(description);
        field.setSingleLine(maxLength != 300);
        if (maxLength == 300) { field.setMinLines(1); field.setMaxLines(3); }
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        field.setInputType(inputType);
        field.setTextSize(15);
        field.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        field.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 12));
        return field;
    }

    private void openDebtPayment(DebtRecord record) {
        boolean debt = DebtRecord.DEBT.equals(record.type);
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 24, 5, 24, 8);
        content.addView(Ui.text(this, tr("Sisa saat ini: ", "Current remaining: ") + Money.format(record.remaining), 12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 10));
        EditText amount = debtTextField(tr("Nominal pembayaran", "Payment amount"),
                tr("Nominal pembayaran dalam rupiah", "Payment amount in rupiah"), 20, android.text.InputType.TYPE_CLASS_NUMBER);
        content.addView(amount, Ui.lp(-1, Ui.dp(this, 50)));
        content.addView(Ui.gap(this, 10));
        EditText note = debtTextField(tr("Keterangan (opsional)", "Description (optional)"),
                tr("Keterangan pembayaran, opsional", "Optional payment description"), 300,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.addView(note, Ui.lp(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(debt ? tr("Bayar hutang", "Repay debt") : tr("Terima pembayaran", "Collect receivable"))
                .setView(content).setNegativeButton(tr("Batal", "Cancel"), null)
                .setPositiveButton(debt ? tr("Bayar", "Pay") : tr("Terima", "Collect"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final long value;
            try { value = Money.parseAmount(amount.getText().toString(), language); }
            catch (IllegalArgumentException error) { amount.setError(error.getMessage()); amount.requestFocus(); return; }
            try {
                store.saveDebtPayment(record.id, value, Dates.today(), note.getText().toString());
                dialog.dismiss();
                Toast.makeText(this, debt ? tr("Pembayaran hutang tersimpan", "Debt payment saved")
                        : tr("Penerimaan piutang tersimpan", "Receivable collection saved"), Toast.LENGTH_SHORT).show();
                refresh();
            } catch (IllegalArgumentException error) {
                amount.setError(tr("Pembayaran melebihi sisa hutang/piutang.",
                        "Payment exceeds the remaining debt/receivable balance."));
                amount.requestFocus();
            } catch (RuntimeException error) { showDebtStorageError(); }
        }));
        dialog.show();
    }

    private void showDebtRecord(DebtRecord record) {
        boolean debt = DebtRecord.DEBT.equals(record.type);
        StringBuilder details = new StringBuilder();
        details.append(tr("Nama: ", "Person: ")).append(record.person)
                .append("\n").append(tr("Pokok: ", "Original amount: ")).append(Money.format(record.amount))
                .append("\n").append(tr("Sisa: ", "Remaining: ")).append(Money.format(record.remaining))
                .append("\n").append(tr("Tanggal: ", "Date: ")).append(Dates.display(record.date, language));
        if (!record.note.isEmpty()) details.append("\n").append(tr("Catatan: ", "Note: ")).append(record.note);
        List<DebtPayment> payments = store.paymentsForDebt(record.id);
        details.append("\n\n").append(tr("Riwayat pembayaran", "Payment history"));
        if (payments.isEmpty()) details.append("\n").append(tr("Belum ada pembayaran.", "No payments yet."));
        else for (DebtPayment payment : payments)
            details.append("\n").append(Dates.display(payment.date, language)).append(" · ")
                    .append(Money.format(payment.amount)).append(payment.note.isEmpty() ? "" : " · " + payment.note);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle((debt ? tr("Hutang · ", "Debt · ") : tr("Piutang · ", "Receivable · ")) + record.person)
                .setMessage(details.toString())
                .setNegativeButton(tr("Tutup", "Close"), null);
        if (!record.isSettled()) builder.setPositiveButton(debt ? tr("Bayar", "Pay") : tr("Terima", "Collect"),
                (dialog, which) -> openDebtPayment(record));
        builder.show();
    }

    private void showDebtStorageError() {
        new AlertDialog.Builder(this).setTitle(tr("Belum bisa menyimpan", "Unable to save"))
                .setMessage(tr("Catatan hutang/piutang belum tersimpan. Periksa ruang penyimpanan dan coba lagi.",
                        "The debt/receivable entry was not saved. Check storage and try again."))
                .setPositiveButton(tr("Tutup", "Close"), null).show();
    }

    private View pageHeader(String title, String subtitle) {
        LinearLayout row = Ui.row(this);
        LinearLayout labels = Ui.column(this);
        labels.addView(Ui.overline(this, tr("TOXIFLOW / CATATANMU", "TOXIFLOW / YOUR RECORDS"), Ui.GREEN));
        labels.addView(Ui.gap(this, 5));
        labels.addView(Ui.display(this, title, 29));
        labels.addView(Ui.gap(this, 3));
        labels.addView(Ui.text(this, subtitle, 13, Ui.MUTED, false));
        row.addView(labels, Ui.weighted(0, -2, 1));
        addHeaderActions(row);
        return row;
    }

    private void addHeaderActions(LinearLayout row) {
        TextView languageButton = Ui.text(this, language == AppLanguage.ENGLISH ? "EN" : "ID", 12, Ui.GREEN, true);
        languageButton.setGravity(Gravity.CENTER);
        languageButton.setTag("language_button");
        languageButton.setContentDescription(tr("Ganti bahasa. Saat ini Bahasa Indonesia", "Change language. Currently English"));
        Ui.clickable(languageButton, Ui.outlined(this, Ui.WHITE, Ui.LINE, 14), v -> chooseLanguage());
        row.addView(languageButton, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 48)));
        row.addView(horizontalGap(8));
        FrameLayout info = Ui.iconBox(this, "info", Ui.GREEN, Ui.WHITE, 48, 21, 14);
        info.setContentDescription(tr("Tentang aplikasi dan ekspor data", "About the app and export data"));
        Ui.clickable(info, Ui.outlined(this, Ui.WHITE, Ui.LINE, 14), v -> showAbout());
        row.addView(info);
    }

    private void chooseLanguage() {
        String[] choices = {"Bahasa Indonesia", "English"};
        int selected = language == AppLanguage.ENGLISH ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Bahasa / Language")
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    dialog.dismiss();
                    AppLanguage chosen = which == 1 ? AppLanguage.ENGLISH : AppLanguage.INDONESIAN;
                    if (chosen == language) return;
                    if (LanguageSettings.save(this, chosen)) recreate();
                    else Toast.makeText(this, tr("Pilihan bahasa belum bisa disimpan.",
                            "Could not save the language choice."), Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .show();
    }

    private View monthSelector() {
        LinearLayout row = Ui.row(this);
        TextView label = Ui.overline(this, tr("PERIODE", "PERIOD"), Ui.MUTED);
        row.addView(label, Ui.weighted(0, -2, 1));
        LinearLayout selector = Ui.row(this);
        selector.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 14));
        FrameLayout previous = Ui.iconBox(this, "back", Ui.GREEN, Ui.WHITE, 48, 17, 13);
        previous.setContentDescription(tr("Bulan sebelumnya", "Previous month"));
        Ui.clickable(previous, Ui.shape(this, Ui.WHITE, 13), v -> changeMonth(-1));
        selector.addView(previous);
        TextView date = Ui.text(this, Dates.monthLabel(year, monthIndex, language), 13, Ui.INK, true);
        date.setGravity(Gravity.CENTER);
        date.setMinWidth(Ui.dp(this, 116));
        date.setContentDescription(tr("Pilih periode, saat ini ", "Choose period, currently ") + Dates.monthLabel(year, monthIndex, language));
        Ui.clickable(date, Ui.shape(this, Ui.WHITE, 12), v -> chooseMonth());
        selector.addView(date, Ui.lp(-2, Ui.dp(this, 48)));
        FrameLayout next = Ui.iconBox(this, "next", Ui.GREEN, Ui.WHITE, 48, 17, 13);
        next.setContentDescription(tr("Bulan berikutnya", "Next month"));
        Ui.clickable(next, Ui.shape(this, Ui.WHITE, 13), v -> changeMonth(1));
        selector.addView(next);
        row.addView(selector);
        return row;
    }

    private void changeMonth(int direction) {
        int newYear = year, newMonth = monthIndex + direction;
        if (newMonth < 0) { newMonth = 11; newYear--; }
        if (newMonth > 11) { newMonth = 0; newYear++; }
        if (newYear < Dates.MIN_YEAR || newYear > Dates.MAX_YEAR) return;
        year = newYear;
        monthIndex = newMonth;
        month = FinanceMath.forMonth(all, year, monthIndex);
        render();
    }

    private void chooseMonth() {
        DatePickerDialog dialog = new DatePickerDialog(this, (view, y, m, d) -> {
            year = y;
            monthIndex = m;
            month = FinanceMath.forMonth(all, year, monthIndex);
            render();
        }, year, monthIndex, 1);
        dialog.setTitle(tr("Pilih bulan dan tahun", "Choose month and year"));
        dialog.getDatePicker().setMinDate(Dates.calendarFor("1900-01-01").getTimeInMillis());
        dialog.getDatePicker().setMaxDate(Dates.calendarFor("2100-12-31").getTimeInMillis());
        dialog.show();
    }

    private View balanceCard() {
        FinanceMath.Summary selected = FinanceMath.summarize(month);
        DecorCard card = new DecorCard(this);
        LinearLayout inside = Ui.column(this);
        Ui.pad(inside, 21, 18, 21, 19);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.overline(this, tr("DANA SIAP PAKAI", "AVAILABLE FUNDS"), 0xffc6ddca), Ui.weighted(0, -2, 1));
        FrameLayout eye = Ui.iconBox(this, hideAmounts ? "eyeoff" : "eye", 0xffd5e9d8, 0x22ffffff, 36, 19, 12);
        eye.setContentDescription(hideAmounts ? tr("Tampilkan nominal di beranda", "Show amounts on the home screen") : tr("Sembunyikan nominal di beranda", "Hide amounts on the home screen"));
        Ui.clickable(eye, Ui.shape(this, 0x22ffffff, 12), v -> {
            hideAmounts = !hideAmounts;
            getPreferences(MODE_PRIVATE).edit().putBoolean("hide_dashboard_amounts", hideAmounts).apply();
            render();
        });
        top.addView(eye);
        inside.addView(top);
        TextView amount = Ui.text(this, hideAmounts ? "Rp ••••••" : Money.format(availableFunds), 30, Ui.WHITE, true);
        amount.setContentDescription(hideAmounts ? tr("Saldo disembunyikan", "Balance hidden") : tr("Dana siap pakai ", "Available funds ") + Money.format(availableFunds));
        inside.addView(amount);
        inside.addView(Ui.gap(this, 2));
        inside.addView(Ui.text(this, tr("Dana tersedia di luar saldo tabungan; pokok hutang/piutang tidak masuk laporan", "Available funds exclude savings; debt principal is outside reports"), 11, 0xffbed1c2, false));
        inside.addView(Ui.gap(this, 17));
        View line = new View(this);
        line.setBackgroundColor(0x38d5ead8);
        inside.addView(line, Ui.lp(-1, Ui.dp(this, 1)));
        inside.addView(Ui.gap(this, 15));
        LinearLayout subtotals = Ui.row(this);
        subtotals.setGravity(Gravity.TOP);
        subtotals.addView(cardSubtotal("income", tr("Pemasukan", "Income"), selected.income, 0xffc8e9ce), Ui.weighted(0, -2, 1));
        View space = new View(this);
        subtotals.addView(space, Ui.lp(Ui.dp(this, 8), 1));
        subtotals.addView(cardSubtotal("expense", tr("Pengeluaran", "Expense"), selected.expense, 0xfff4cbbc), Ui.weighted(0, -2, 1));
        inside.addView(subtotals);
        inside.addView(Ui.gap(this, 11));
        inside.addView(Ui.text(this, tr("Ringkasan periode terpilih", "Selected period summary"), 10, 0xffafc8b6, false));
        card.addView(inside, new FrameLayout.LayoutParams(-1, -2));
        return card;
    }

    private View cardSubtotal(String icon, String label, BigInteger amount, int color) {
        LinearLayout group = Ui.column(this);
        LinearLayout name = Ui.row(this);
        FrameLayout iconBox = Ui.iconBox(this, icon, color, 0x22ffffff, 25, 15, 9);
        name.addView(iconBox);
        TextView text = Ui.text(this, label, 11, 0xffd9e9da, false);
        LinearLayout.LayoutParams params = Ui.lp(-2, -2);
        params.leftMargin = Ui.dp(this, 7);
        name.addView(text, params);
        group.addView(name);
        group.addView(Ui.gap(this, 6));
        TextView value = Ui.text(this, hideAmounts ? "Rp ••••••" : Money.format(amount), 15, Ui.WHITE, true);
        group.addView(value);
        return group;
    }

    private View addTransactionCallout() {
        LinearLayout button = Ui.row(this);
        Ui.pad(button, 14, 11, 14, 11);
        Ui.clickable(button, Ui.shape(this, Ui.GREEN, 18), v -> openForm(null));
        button.setContentDescription(tr("Catat transaksi baru", "Add a new transaction"));
        button.addView(Ui.iconBox(this, "plus", Ui.DARK, Ui.MINT, 38, 19, 12));
        LinearLayout labels = Ui.column(this);
        LinearLayout.LayoutParams p = Ui.weighted(0, -2, 1);
        p.leftMargin = Ui.dp(this, 12);
        labels.addView(Ui.text(this, tr("Catat transaksi", "Add transaction"), 14, Ui.WHITE, true));
        labels.addView(Ui.text(this, tr("Pemasukan atau pengeluaran", "Income or expense"), 11, 0xffc5ddca, false));
        button.addView(labels, p);
        button.addView(new IconView(this, "next", Ui.MINT), Ui.lp(Ui.dp(this, 19), Ui.dp(this, 19)));
        return button;
    }

    private View sectionHeading(String title, String action, Runnable onAction) {
        LinearLayout row = Ui.row(this);
        row.addView(Ui.text(this, title, 17, Ui.INK, true), Ui.weighted(0, -2, 1));
        if (action != null) {
            TextView link = Ui.text(this, action + "  ›", 12, Ui.GREEN, true);
            Ui.pad(link, 8, 8, 0, 8);
            link.setOnClickListener(v -> onAction.run());
            link.setFocusable(true);
            row.addView(link);
        }
        return row;
    }

    private View emptyState(boolean filtered) {
        LinearLayout empty = Ui.row(this);
        Ui.pad(empty, 16, 20, 16, 20);
        empty.setBackground(Ui.shape(this, Ui.WHITE, 20));
        empty.addView(Ui.iconBox(this, filtered ? "search" : "wallet", Ui.GREEN, Ui.PALE, 48, 24, 16));
        LinearLayout labels = Ui.column(this);
        LinearLayout.LayoutParams params = Ui.weighted(0, -2, 1);
        params.leftMargin = Ui.dp(this, 14);
        labels.addView(Ui.text(this, filtered ? tr("Tidak ada hasil", "No results") : tr("Belum ada transaksi", "No transactions yet"), 14, Ui.INK, true));
        labels.addView(Ui.gap(this, 3));
        labels.addView(Ui.text(this, filtered ? tr("Coba kata kunci atau filter lain.", "Try another search term or filter.") : tr("Catatan pertamamu akan muncul di sini.", "Your first entry will appear here."), 12, Ui.MUTED, false));
        empty.addView(labels, params);
        return empty;
    }

    private void buildTransactions(LinearLayout content) {
        content.addView(pageHeader(tr("Transaksi", "Transactions"), tr("Semua catatan, dalam satu tempat.", "All your records in one place.")));
        content.addView(Ui.gap(this, 24));
        content.addView(monthSelector());
        content.addView(Ui.gap(this, 18));
        LinearLayout chips = Ui.row(this);
        chips.addView(filterChip(tr("Semua", "All"), ""), Ui.weighted(0, -2, 1));
        chips.addView(horizontalGap(6));
        chips.addView(filterChip(tr("Pemasukan", "Income"), Transaction.INCOME), Ui.weighted(0, -2, 1.4f));
        chips.addView(horizontalGap(6));
        chips.addView(filterChip(tr("Pengeluaran", "Expense"), Transaction.EXPENSE), Ui.weighted(0, -2, 1.5f));
        content.addView(chips);
        content.addView(Ui.gap(this, 14));
        LinearLayout searchBox = Ui.row(this);
        searchBox.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 15));
        Ui.pad(searchBox, 14, 0, 12, 0);
        searchBox.addView(new IconView(this, "search", Ui.MUTED), Ui.lp(Ui.dp(this, 19), Ui.dp(this, 19)));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(13);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.MUTED);
        input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        input.setHint(tr("Cari nama, kategori, atau catatan", "Search names, categories, or notes"));
        input.setContentDescription(tr("Cari transaksi", "Search transactions"));
        input.setPadding(Ui.dp(this, 10), 0, 0, 0);
        input.setText(search);
        searchBox.addView(input, Ui.weighted(0, Ui.dp(this, 48), 1));
        content.addView(searchBox);
        content.addView(Ui.gap(this, 23));
        transactionList = Ui.column(this);
        content.addView(transactionList);
        updateTransactionList();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                search = s.toString();
                updateTransactionList();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private View horizontalGap(int width) {
        View gap = new View(this);
        gap.setLayoutParams(Ui.lp(Ui.dp(this, width), 1));
        return gap;
    }

    private View filterChip(String title, String value) {
        boolean selected = value.equals(filter);
        TextView chip = Ui.text(this, title, 12, selected ? Ui.WHITE : Ui.GREEN, true);
        chip.setGravity(Gravity.CENTER);
        Ui.pad(chip, 13, 9, 13, 9);
        chip.setContentDescription(tr("Filter ", "Filter ") + title + (selected ? tr(", dipilih", ", selected") : ""));
        Ui.clickable(chip, selected ? Ui.shape(this, Ui.GREEN, 13) : Ui.outlined(this, Ui.WHITE, Ui.LINE, 13), v -> {
            filter = value;
            render();
        });
        return chip;
    }

    private void updateTransactionList() {
        if (transactionList == null) return;
        transactionList.removeAllViews();
        List<Transaction> filtered = FinanceMath.filter(month, filter, search, language);
        if (filtered.isEmpty()) {
            boolean filtering = !filter.isEmpty() || !search.trim().isEmpty();
            transactionList.addView(emptyState(filtering));
            if (!filtering) {
                transactionList.addView(Ui.gap(this, 14));
                transactionList.addView(addTransactionCallout());
            }
            return;
        }
        TextView count = Ui.overline(this, filtered.size() + tr(" TRANSAKSI",
                filtered.size() == 1 ? " TRANSACTION" : " TRANSACTIONS"), Ui.MUTED);
        transactionList.addView(count);
        transactionList.addView(Ui.gap(this, 12));
        String lastDate = "";
        LinearLayout dayCard = null;
        for (Transaction transaction : filtered) {
            if (!transaction.date.equals(lastDate)) {
                if (!lastDate.isEmpty()) transactionList.addView(Ui.gap(this, 18));
                String label = transaction.date.equals(Dates.today()) ? tr("Hari ini · ", "Today · ") + Dates.display(transaction.date, language) : Dates.display(transaction.date, language);
                transactionList.addView(Ui.text(this, label, 12, Ui.MUTED, true));
                transactionList.addView(Ui.gap(this, 8));
                dayCard = Ui.column(this);
                dayCard.setBackground(Ui.shape(this, Ui.WHITE, 20));
                Ui.pad(dayCard, 14, 2, 14, 2);
                transactionList.addView(dayCard);
                lastDate = transaction.date;
            } else if (dayCard != null) dayCard.addView(insetDivider());
            if (dayCard != null) dayCard.addView(transactionRow(transaction, false));
        }
        transactionList.addView(Ui.gap(this, 18));
        transactionList.addView(addTransactionCallout());
    }

    private View insetDivider() {
        LinearLayout row = Ui.row(this);
        row.addView(horizontalGap(56));
        row.addView(Ui.divider(this), Ui.weighted(0, Ui.dp(this, 1), 1));
        return row;
    }

    private View transactionRow(Transaction t, boolean showDate) {
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(Ui.dp(this, 72));
        Ui.pad(row, 0, 8, 0, 8);
        boolean income = Transaction.INCOME.equals(t.type);
        row.addView(Ui.iconBox(this, Ui.categoryIcon(t.category), Ui.categoryColor(t.category), Ui.categoryBackground(t.category), 43, 22, 15));
        LinearLayout text = Ui.column(this);
        LinearLayout.LayoutParams middle = Ui.weighted(0, -2, 1);
        middle.leftMargin = Ui.dp(this, 12);
        middle.rightMargin = Ui.dp(this, 7);
        TextView title = Ui.text(this, t.title, 14, Ui.INK, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);
        text.addView(Ui.gap(this, 2));
        TextView subtitle = Ui.text(this, Categories.label(t.category, language) + (showDate ? " · " + Dates.displayShort(t.date, language) : "")
                + (t.hasPhoto ? tr(" · Foto", " · Photo") : ""), 11, Ui.MUTED, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(subtitle);
        text.addView(Ui.gap(this, 2));
        TextView created = Ui.text(this, CreationTime.listLabel(t.createdAt, language), 11, Ui.MUTED, false);
        created.setMaxLines(1);
        created.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(created);
        row.addView(text, middle);
        boolean masked = showDate && hideAmounts;
        TextView amount = Ui.text(this, masked ? "••••••" : Money.signed(t.amount, income), 13, income ? Ui.GREEN : Ui.INK, true);
        amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        amount.setMaxWidth(Ui.dp(this, 145));
        row.addView(amount, Ui.lp(-2, -2));
        row.setContentDescription(t.title + ", " + Categories.label(t.category, language) + ", " + (income ? tr("pemasukan ", "income ") : tr("pengeluaran ", "expense "))
                + (masked ? tr("nominal disembunyikan", "amount hidden") : Money.format(t.amount))
                + tr(", tanggal transaksi ", ", transaction date ") + Dates.display(t.date, language) + tr(", dibuat ", ", created ") + CreationTime.display(t.createdAt, language)
                + (t.hasPhoto ? tr(", ada foto terlampir", ", photo attached") : "") + tr(". Ketuk untuk detail.", ". Tap for details."));
        row.setOnClickListener(v -> showTransaction(t));
        row.setFocusable(true);
        return row;
    }

    private void buildReports(LinearLayout content) {
        content.addView(pageHeader(tr("Laporan", "Reports"), tr("Kenali ke mana uangmu mengalir.", "See where your money goes.")));
        content.addView(Ui.gap(this, 24));
        content.addView(monthSelector());
        content.addView(Ui.gap(this, 12));
        content.addView(printReportButton());
        content.addView(Ui.gap(this, 18));
        FinanceMath.Summary summary = FinanceMath.summarize(month);
        LinearLayout flow = Ui.column(this);
        flow.setBackground(Ui.shape(this, Ui.WHITE, 21));
        Ui.pad(flow, 19, 19, 19, 20);
        flow.addView(Ui.overline(this, tr("RINGKASAN PERIODE", "PERIOD SUMMARY"), Ui.MUTED));
        flow.addView(Ui.gap(this, 5));
        flow.addView(Ui.text(this, tr("Arus kas", "Cash flow"), 18, Ui.INK, true));
        flow.addView(Ui.gap(this, 5));
        flow.addView(Ui.text(this, tr("Pemasukan dan pengeluaran yang dicatat.", "Recorded income and expenses."), 12, Ui.MUTED, false));
        flow.addView(Ui.gap(this, 20));
        BigInteger totalFlow = summary.income.add(summary.expense);
        int share = FinanceMath.percent(summary.income, totalFlow);
        int background = totalFlow.signum() == 0 ? Ui.LINE : 0xffe9ae9b;
        flow.addView(new ProgressStrip(this, share, 0xff6ca984, background), Ui.lp(-1, Ui.dp(this, 9)));
        flow.addView(Ui.gap(this, 18));
        flow.addView(reportAmountRow(tr("Pemasukan", "Income"), summary.income, 0xff6ca984));
        flow.addView(Ui.gap(this, 12));
        flow.addView(reportAmountRow(tr("Pengeluaran", "Expense"), summary.expense, 0xffe9ae9b));
        flow.addView(Ui.gap(this, 16));
        flow.addView(Ui.divider(this));
        flow.addView(Ui.gap(this, 14));
        LinearLayout balance = Ui.row(this);
        balance.addView(Ui.text(this, tr("Selisih periode", "Period balance"), 13, Ui.INK, true), Ui.weighted(0, -2, 1));
        balance.addView(Ui.text(this, Money.format(summary.balance), 16, summary.balance.signum() < 0 ? Ui.CORAL : Ui.GREEN, true));
        flow.addView(balance);
        content.addView(flow);
        content.addView(Ui.gap(this, 12));
        content.addView(savingsActivityCard());
        FinanceInsights insights = FinanceInsights.forMonth(year, monthIndex, all, language);
        content.addView(Ui.gap(this, 24));
        content.addView(sectionHeading(tr("Sorotan bulanan", "Monthly insights"), null, null));
        content.addView(Ui.gap(this, 5));
        content.addView(Ui.text(this, tr("Berdasarkan transaksi di bulan terpilih.", "Based on transactions in the selected month."), 12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 12));
        content.addView(insightHighlights(insights));
        content.addView(Ui.gap(this, 24));
        content.addView(sectionHeading(tr("Pengeluaran berulang", "Recurring expenses"), null, null));
        content.addView(Ui.gap(this, 5));
        content.addView(Ui.text(this, tr("Nama dan kategori sama: minimal dua kali bulan ini atau pada dua bulan dalam rentang yang diperiksa.", "Same name and category: at least twice this month, or in two months within the checked window."), 12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 12));
        content.addView(recurringInsightCard(insights));
        content.addView(Ui.gap(this, 24));
        content.addView(sectionHeading(tr("Saran sederhana", "Simple suggestions"), null, null));
        content.addView(Ui.gap(this, 12));
        content.addView(suggestionCard(insights));
        content.addView(Ui.gap(this, 24));
        content.addView(sectionHeading(tr("Pengeluaran per kategori", "Expenses by category"), null, null));
        content.addView(Ui.gap(this, 5));
        content.addView(Ui.text(this, tr("Lihat bagian terbesar pengeluaranmu.", "See your largest expense categories."), 12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 13));
        List<FinanceMath.CategoryTotal> categories = FinanceMath.expenseCategories(month);
        if (categories.isEmpty()) {
            LinearLayout empty = Ui.column(this);
            empty.setGravity(Gravity.CENTER);
            Ui.pad(empty, 20, 24, 20, 24);
            empty.setBackground(Ui.shape(this, Ui.WHITE, 20));
            empty.addView(Ui.iconBox(this, "chart", Ui.GREEN, Ui.PALE, 45, 22, 15));
            empty.addView(Ui.gap(this, 10));
            TextView label = Ui.text(this, tr("Belum ada pengeluaran", "No expenses yet"), 14, Ui.INK, true);
            label.setGravity(Gravity.CENTER);
            empty.addView(label);
            empty.addView(Ui.gap(this, 3));
            TextView help = Ui.text(this, tr("Laporan akan muncul setelah kamu mencatatnya.", "The report will appear after you record expenses."), 12, Ui.MUTED, false);
            help.setGravity(Gravity.CENTER);
            empty.addView(help);
            content.addView(empty);
        } else {
            LinearLayout card = Ui.column(this);
            card.setBackground(Ui.shape(this, Ui.WHITE, 20));
            Ui.pad(card, 16, 5, 16, 6);
            for (int i = 0; i < categories.size(); i++) {
                if (i > 0) card.addView(Ui.divider(this));
                card.addView(categoryReport(categories.get(i), summary.expense));
            }
            content.addView(card);
        }
        content.addView(Ui.gap(this, 16));
        content.addView(addTransactionCallout());
    }

    private View savingsActivityCard() {
        MonthlyReport report = MonthlyReport.forMonth(year, monthIndex, all, savingsMovements, language);
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.shape(this, Ui.WHITE, 20));
        Ui.pad(card, 17, 16, 17, 16);
        card.addView(Ui.overline(this, tr("TABUNGAN BULAN INI", "SAVINGS THIS MONTH"), Ui.GREEN));
        card.addView(Ui.gap(this, 7));
        card.addView(reportAmountRow(tr("Saldo awal tabungan", "Opening savings balance"), report.savingsOpeningBalance, Ui.MUTED));
        card.addView(reportAmountRow(tr("Ditambahkan ke tabungan", "Added to savings"), report.savingsAdded, 0xff6ca984));
        card.addView(reportAmountRow(tr("Diambil dari tabungan", "Withdrawn from savings"), report.savingsWithdrawn, 0xffe9ae9b));
        LinearLayout closing = Ui.row(this);
        closing.addView(Ui.text(this, tr("Saldo akhir tabungan", "Closing savings balance"), 12, Ui.INK, true), Ui.weighted(0, -2, 1));
        closing.addView(Ui.text(this, Money.format(report.savingsClosingBalance), 13, Ui.GREEN, true));
        card.addView(Ui.gap(this, 5));
        card.addView(closing);
        boolean hasActivity = false;
        for (Transaction transaction : month) if (transaction.savingsAmount > 0) hasActivity = true;
        hasActivity |= !report.savingsMovements.isEmpty();
        if (!hasActivity) {
            card.addView(Ui.gap(this, 5));
            card.addView(Ui.text(this, tr("Belum ada aktivitas tabungan pada periode ini.", "No savings activity in this period."), 11, Ui.MUTED, false));
        } else {
            card.addView(Ui.gap(this, 8));
            card.addView(Ui.divider(this));
            for (Transaction transaction : month) if (transaction.savingsAmount > 0) {
                boolean income = Transaction.INCOME.equals(transaction.type);
                card.addView(savingsEventRow(Dates.displayShort(transaction.date, language) + " · " + transaction.title
                        + (income ? tr(" · masuk ke tabungan", " · added to savings") : tr(" · dibayar dari tabungan", " · paid from savings")),
                        transaction.savingsAmount, income));
            }
            for (SavingsMovement movement : report.savingsMovements) {
                boolean deposit = SavingsMovement.DEPOSIT.equals(movement.type);
                card.addView(savingsEventRow(Dates.displayShort(movement.date, language)
                        + (deposit ? tr(" · pindah ke tabungan", " · transfer to savings")
                        : tr(" · pindah ke dana tersedia", " · transfer to available funds")), movement.amount, deposit));
            }
        }
        return card;
    }

    private View savingsEventRow(String label, long value, boolean added) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(this, label, 11, Ui.MUTED, false), Ui.weighted(0, -2, 1));
        row.addView(Ui.text(this, (added ? "+ " : "− ") + Money.format(value), 12,
                added ? Ui.GREEN : Ui.CORAL, true));
        return row;
    }

    private View insightHighlights(FinanceInsights insights) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.shape(this, Ui.WHITE, 20));
        Ui.pad(card, 17, 17, 17, 17);
        card.addView(Ui.overline(this, tr("PENGELUARAN TERBESAR", "LARGEST EXPENSE"), Ui.MUTED));
        card.addView(Ui.gap(this, 7));
        if (insights.largestExpense == null) {
            card.addView(Ui.text(this, tr("Belum ada pengeluaran pada periode ini.", "No expenses in this period."), 13, Ui.MUTED, false));
        } else {
            Transaction t = insights.largestExpense;
            card.addView(Ui.text(this, tr("Transaksi: ", "Transaction: ") + t.title, 14, Ui.INK, true));
            card.addView(Ui.gap(this, 3));
            card.addView(Ui.text(this, Money.format(t.amount) + " · " + Categories.label(t.category, language) + " · " + Dates.display(t.date, language),
                    12, Ui.MUTED, false));
            if (insights.largestExpenseCategory != null) {
                FinanceMath.CategoryTotal category = insights.largestExpenseCategory;
                int percent = FinanceMath.percent(category.amount, insights.summary.expense);
                card.addView(Ui.gap(this, 6));
                card.addView(Ui.text(this, tr("Kategori terbesar: ", "Largest category: ") + Categories.label(category.category, language) + " · "
                        + Money.format(category.amount) + " (" + percent + tr("% dari pengeluaran)", "% of expenses)"), 12, Ui.INK, false));
            }
        }
        card.addView(Ui.gap(this, 16));
        card.addView(Ui.divider(this));
        card.addView(Ui.gap(this, 16));
        card.addView(Ui.overline(this, tr("PEMASUKAN TERBESAR", "LARGEST INCOME"), Ui.MUTED));
        card.addView(Ui.gap(this, 7));
        if (insights.largestIncome == null) {
            card.addView(Ui.text(this, tr("Belum ada pemasukan pada periode ini.", "No income in this period."), 13, Ui.MUTED, false));
        } else {
            Transaction t = insights.largestIncome;
            card.addView(Ui.text(this, tr("Transaksi: ", "Transaction: ") + t.title, 14, Ui.INK, true));
            card.addView(Ui.gap(this, 3));
            card.addView(Ui.text(this, Money.format(t.amount) + " · " + Categories.label(t.category, language) + " · " + Dates.display(t.date, language),
                    12, Ui.MUTED, false));
            FinanceInsights.Group source = insights.largestIncomeSource;
            if (source != null) {
                card.addView(Ui.gap(this, 6));
                card.addView(Ui.text(this, tr("Sumber terbesar: ", "Largest source: ") + source.title + " · " + Categories.label(source.category, language)
                        + " · " + Money.format(source.total) + " (" + source.count + tr(" transaksi bulan ini)",
                        source.count == 1 ? " transaction this month)" : " transactions this month)"),
                        12, Ui.INK, false));
                card.addView(Ui.gap(this, 3));
                card.addView(Ui.text(this, tr("Sumber dikelompokkan menurut nama dan kategori yang sama.", "Sources are grouped by the same name and category."), 10, Ui.MUTED, false));
            }
        }
        return card;
    }

    private View recurringInsightCard(FinanceInsights insights) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.shape(this, Ui.WHITE, 20));
        Ui.pad(card, 17, 16, 17, 16);
        if (insights.recurringExpenses.isEmpty()) {
            card.addView(Ui.text(this, tr("Belum ada pengeluaran berulang yang terdeteksi pada periode ini.", "No recurring expenses were detected in this period."), 13, Ui.MUTED, false));
        } else {
            int shown = Math.min(FinanceInsights.MAX_RECURRING_SHOWN, insights.recurringExpenses.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) { card.addView(Ui.gap(this, 12)); card.addView(Ui.divider(this)); card.addView(Ui.gap(this, 12)); }
                FinanceInsights.Group group = insights.recurringExpenses.get(i);
                card.addView(Ui.text(this, group.title + " · " + Categories.label(group.category, language), 14, Ui.INK, true));
                card.addView(Ui.gap(this, 3));
                card.addView(Ui.text(this, tr("Total bulan ini: ", "Total this month: ") + Money.format(group.total), 13, Ui.INK, true));
                card.addView(Ui.gap(this, 3));
                card.addView(Ui.text(this, insights.recurringEvidence(group), 11, Ui.MUTED, false));
            }
            if (insights.recurringExpenses.size() > shown) {
                card.addView(Ui.gap(this, 10));
                card.addView(Ui.text(this, tr("Menampilkan ", "Showing ") + shown + tr(" dari ", " of ") + insights.recurringExpenses.size()
                        + tr(" teratas berdasarkan total bulan ini.", " top entries by this month’s total."), 11, Ui.MUTED, false));
            }
        }
        card.addView(Ui.gap(this, 10));
        card.addView(Ui.text(this, tr("Rentang: bulan terpilih dan hingga dua bulan sebelumnya. Pola ini tidak memastikan jadwal rutin.", "Window: the selected month and up to two previous months. This pattern does not guarantee a regular schedule."),
                11, Ui.MUTED, false));
        return card;
    }

    private View suggestionCard(FinanceInsights insights) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.shape(this, Ui.PALE, 20));
        Ui.pad(card, 17, 16, 17, 16);
        for (int i = 0; i < insights.suggestions.size(); i++) {
            if (i > 0) card.addView(Ui.gap(this, 9));
            card.addView(Ui.text(this, "• " + insights.suggestions.get(i), 12, Ui.INK, false));
        }
        card.addView(Ui.gap(this, 12));
        card.addView(Ui.text(this, tr("Saran otomatis berdasarkan catatan yang tersedia, bukan nasihat keuangan profesional.", "Automatic suggestions are based on available records and are not professional financial advice."),
                11, Ui.MUTED, false));
        return card;
    }

    private View printReportButton() {
        LinearLayout button = Ui.row(this);
        Ui.pad(button, 14, 12, 14, 12);
        button.setContentDescription(tr("Cetak laporan bulanan ", "Print monthly report for ") + Dates.monthLabel(year, monthIndex, language));
        Ui.clickable(button, Ui.outlined(this, Ui.WHITE, Ui.LINE, 18), v -> confirmMonthlyPrint());
        button.addView(Ui.iconBox(this, "print", Ui.GREEN, Ui.PALE, 42, 21, 13));
        LinearLayout labels = Ui.column(this);
        LinearLayout.LayoutParams params = Ui.weighted(0, -2, 1);
        params.leftMargin = Ui.dp(this, 12);
        labels.addView(Ui.text(this, tr("Cetak laporan bulanan", "Print monthly report"), 14, Ui.INK, true));
        labels.addView(Ui.gap(this, 3));
        labels.addView(Ui.text(this, tr("Cetak atau simpan sebagai PDF", "Print or save as PDF"), 11, Ui.MUTED, false));
        button.addView(labels, params);
        button.addView(new IconView(this, "next", Ui.GREEN), Ui.lp(Ui.dp(this, 19), Ui.dp(this, 19)));
        return button;
    }

    private void confirmMonthlyPrint() {
        // Fix the selected month; a consistent snapshot is captured when printing is prepared.
        MonthlyReport report = MonthlyReport.forMonth(year, monthIndex, all, savingsMovements, language);
        CheckBox includePhotos = new CheckBox(this);
        includePhotos.setText(tr("Sertakan foto sebagai lampiran", "Include photos as appendices"));
        includePhotos.setChecked(true);
        String disclosure = tr("Laporan memuat seluruh transaksi, alokasi dan perpindahan tabungan, saldo awal/akhir tabungan, nominal, waktu pembuatan, dan catatan pada periode ini, ",
                        "The report includes all transactions, savings allocations and transfers, opening/closing savings balances, amounts, creation times, and notes in this period, ")
                + tr("termasuk nominal yang disembunyikan di beranda.\n\n", "including amounts hidden on the home screen.\n\n")
                + tr("Jika dipilih, foto ikut dicetak atau disimpan sebagai halaman lampiran di PDF. ", "If selected, photos are printed or saved as appendix pages in the PDF. ")
                + tr("Foto dan data diberikan kepada layanan cetak yang dipilih. CSV tidak memuat gambar.\n\n", "Photos and data are provided to the selected print service. CSV does not include images.\n\n")
                + tr("Pola berulang juga menghitung keberadaan transaksi dengan nama dan kategori yang sama ", "Recurring patterns also count transactions with the same name and category ")
                + tr("pada hingga dua bulan sebelumnya.\n\n", "in up to two previous months.\n\n")
                + tr("Maksimal ", "Maximum ") + PhotoPrintPolicy.MAX_PHOTOS_PER_WRITE
                + tr(" foto per proses cetak; pilih rentang halaman lebih kecil bila perlu.", " photos per print job; select a smaller page range if needed.");
        // A single scrollable custom panel keeps both the checkbox and action buttons accessible
        // on short screens; a separate long message plus custom panel can crowd out the buttons.
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 24, 4, 24, 16);
        content.addView(includePhotos);
        content.addView(Ui.gap(this, 10));
        content.addView(Ui.text(this, disclosure, 14, Ui.INK, false));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(tr("Cetak ", "Print ") + report.period() + "?")
                .setView(scroll)
                .setNegativeButton(tr("Batal", "Cancel"), null)
                .setPositiveButton(tr("Lanjutkan", "Continue"), (dialog, which) -> startMonthlyPrint(report, includePhotos.isChecked()))
                .show();
    }

    private void startMonthlyPrint(MonthlyReport report, boolean includePhotos) {
        PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
        if (manager == null) {
            showPrintUnavailable();
            return;
        }
        MonthlyPrintAdapter adapter = new MonthlyPrintAdapter(this, report, includePhotos);
        try {
            PrintAttributes defaults = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setMinMargins(new PrintAttributes.Margins(500, 500, 500, 500))
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build();
            PrintJob job = manager.print("ToxiFlow - " + report.period(), adapter, defaults);
            if (job == null) {
                adapter.onFinish();
                showPrintUnavailable();
            }
        } catch (RuntimeException e) {
            adapter.onFinish();
            showPrintUnavailable();
        }
    }

    private void showPrintUnavailable() {
        new AlertDialog.Builder(this).setTitle(tr("Belum bisa mencetak", "Unable to print"))
                .setMessage(tr("Dialog cetak Android tidak dapat dibuka. Pastikan layanan cetak tersedia di perangkat, lalu coba lagi.", "The Android print dialog could not be opened. Check that the device’s print service is available and try again."))
                .setPositiveButton(tr("Tutup", "Close"), null).show();
    }

    private View reportAmountRow(String title, BigInteger amount, int dotColor) {
        LinearLayout row = Ui.row(this);
        View dot = new View(this);
        dot.setBackground(Ui.shape(this, dotColor, 20));
        row.addView(dot, Ui.lp(Ui.dp(this, 9), Ui.dp(this, 9)));
        TextView name = Ui.text(this, title, 13, Ui.MUTED, false);
        LinearLayout.LayoutParams p = Ui.weighted(0, -2, 1);
        p.leftMargin = Ui.dp(this, 9);
        row.addView(name, p);
        row.addView(Ui.text(this, Money.format(amount), 14, Ui.INK, true));
        return row;
    }

    private View categoryReport(FinanceMath.CategoryTotal category, BigInteger total) {
        LinearLayout row = Ui.row(this);
        Ui.pad(row, 0, 14, 0, 14);
        row.addView(Ui.iconBox(this, Ui.categoryIcon(category.category), Ui.categoryColor(category.category),
                Ui.categoryBackground(category.category), 40, 20, 14));
        LinearLayout info = Ui.column(this);
        LinearLayout.LayoutParams params = Ui.weighted(0, -2, 1);
        params.leftMargin = Ui.dp(this, 12);
        row.addView(info, params);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, Categories.label(category.category, language), 13, Ui.INK, true), Ui.weighted(0, -2, 1));
        head.addView(Ui.text(this, Money.format(category.amount), 13, Ui.INK, true));
        info.addView(head);
        info.addView(Ui.gap(this, 8));
        LinearLayout progress = Ui.row(this);
        int percent = FinanceMath.percent(category.amount, total);
        progress.addView(new ProgressStrip(this, percent, Ui.categoryColor(category.category), Ui.LINE), Ui.weighted(0, Ui.dp(this, 6), 1));
        TextView percentage = Ui.text(this, percent + "%", 10, Ui.MUTED, true);
        percentage.setGravity(Gravity.END);
        progress.addView(percentage, Ui.lp(Ui.dp(this, 37), -2));
        info.addView(progress);
        row.setContentDescription(Categories.label(category.category, language) + ", " + Money.format(category.amount) + ", " + percent + tr(" persen dari pengeluaran", " percent of expenses"));
        return row;
    }

    private View bottomNavigation() {
        LinearLayout container = Ui.column(this);
        container.setBackgroundColor(Ui.WHITE);
        container.addView(Ui.divider(this));
        LinearLayout nav = Ui.row(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(navItem("home", tr("Beranda", "Home"), page == HOME, () -> { page = HOME; render(); }), Ui.weighted(0, -1, 1));
        nav.addView(navItem("list", tr("Transaksi", "Transactions"), page == TRANSACTIONS, () -> { page = TRANSACTIONS; render(); }), Ui.weighted(0, -1, 1));
        nav.addView(navItem("plus", tr("Catat", "Add"), false, () -> openForm(null)), Ui.weighted(0, -1, 1));
        nav.addView(navItem("wallet", tr("Dana", "Cash"), page == CASH, () -> { page = CASH; render(); }), Ui.weighted(0, -1, 1));
        nav.addView(navItem("chart", tr("Laporan", "Reports"), page == REPORTS, () -> { page = REPORTS; render(); }), Ui.weighted(0, -1, 1));
        container.addView(nav, Ui.weighted(-1, 0, 1));
        return container;
    }

    private View navItem(String icon, String label, boolean selected, Runnable action) {
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        item.setContentDescription(label + (selected ? tr(", halaman aktif", ", current page") : ""));
        item.setOnClickListener(v -> action.run());
        item.setFocusable(true);
        if ("plus".equals(icon)) item.addView(Ui.iconBox(this, icon, Ui.WHITE, Ui.GREEN, 32, 18, 11));
        else {
            FrameLayout iconContainer = new FrameLayout(this);
            if (selected) iconContainer.setBackground(Ui.shape(this, Ui.PALE, 11));
            IconView image = new IconView(this, icon, selected ? Ui.GREEN : Ui.MUTED);
            iconContainer.addView(image, new FrameLayout.LayoutParams(Ui.dp(this, 21), Ui.dp(this, 21), Gravity.CENTER));
            item.addView(iconContainer, Ui.lp(Ui.dp(this, 42), Ui.dp(this, 32)));
        }
        TextView text = Ui.text(this, label, 10, selected || "plus".equals(icon) ? Ui.GREEN : Ui.MUTED, selected);
        text.setGravity(Gravity.CENTER);
        item.addView(text);
        return item;
    }

    private void showTransaction(Transaction t) {
        boolean income = Transaction.INCOME.equals(t.type);
        String details = (income ? tr("Pemasukan", "Income") : tr("Pengeluaran", "Expense")) + " · " + Categories.label(t.category, language) + "\n\n"
                + Money.format(t.amount)
                + (t.savingsAmount == 0 ? "" : tr("\n" + (income ? "Masuk ke tabungan: " : "Diambil dari tabungan: "),
                        "\n" + (income ? "Added to savings: " : "Paid from savings: ")) + Money.format(t.savingsAmount))
                + tr("\nTanggal transaksi: ", "\nTransaction date: ") + Dates.display(t.date, language)
                + tr("\nDibuat: ", "\nCreated: ") + CreationTime.display(t.createdAt, language)
                + (t.note.isEmpty() ? "" : tr("\n\nCatatan: ", "\n\nNote: ") + t.note);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(t.title)
                .setPositiveButton(tr("Ubah", "Edit"), (dialog, which) -> openForm(t))
                .setNeutralButton(tr("Hapus", "Delete"), (dialog, which) -> confirmDelete(t))
                .setNegativeButton(tr("Tutup", "Close"), null);
        if (!t.hasPhoto) {
            builder.setMessage(details).show();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 24, 4, 24, 12);
        content.addView(Ui.text(this, details, 14, Ui.INK, false));
        content.addView(Ui.gap(this, 14));
        TextView status = Ui.text(this, tr("Memuat foto…", "Loading photo…"), 12, Ui.MUTED, false);
        content.addView(status);
        content.addView(Ui.gap(this, 6));
        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumbnail.setContentDescription(tr("Pratinjau foto terlampir", "Attached photo preview"));
        thumbnail.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 14));
        content.addView(thumbnail, Ui.lp(-1, Ui.dp(this, 150)));
        TextView view = Ui.text(this, tr("Lihat foto", "View photo"), 13, Ui.GREEN, true);
        view.setGravity(Gravity.CENTER);
        Ui.clickable(view, Ui.outlined(this, Ui.WHITE, Ui.LINE, 14), v -> showFullPhoto(t.id));
        LinearLayout.LayoutParams viewMargin = Ui.lp(-1, Ui.dp(this, 48));
        viewMargin.topMargin = Ui.dp(this, 8);
        content.addView(view, viewMargin);
        scroll.addView(content);
        AlertDialog dialog = builder.setView(scroll).create();
        dialog.show();
        loadPhoto(t.id, 600, dialog, thumbnail, status);
    }

    private void showFullPhoto(long id) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(0xff142019);
        Ui.pad(root, 12, 12, 12, 12);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                int pad = Ui.dp(this, 12);
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
                return insets;
            });
        }
        LinearLayout header = Ui.row(this);
        header.addView(Ui.text(this, tr("Foto transaksi", "Transaction photo"), 17, Ui.WHITE, true), Ui.weighted(0, -2, 1));
        TextView close = Ui.text(this, tr("Tutup", "Close"), 14, Ui.WHITE, true);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(tr("Tutup foto", "Close photo"));
        Ui.clickable(close, Ui.shape(this, 0xff365443, 12), v -> dialog.dismiss());
        header.addView(close, Ui.lp(Ui.dp(this, 70), Ui.dp(this, 48)));
        root.addView(header);
        TextView status = Ui.text(this, tr("Memuat foto…", "Loading photo…"), 12, Ui.WHITE, false);
        Ui.pad(status, 4, 8, 4, 8);
        root.addView(status);
        PhotoZoomView photo = new PhotoZoomView(this);
        root.addView(photo, Ui.weighted(-1, 0, 1));
        TextView hint = Ui.text(this, tr("Cubit atau gunakan tombol untuk memperbesar, lalu geser foto.", "Pinch or use the buttons to zoom, then drag the photo."), 11, 0xffd0ded2, false);
        hint.setGravity(Gravity.CENTER);
        Ui.pad(hint, 0, 8, 0, 8);
        root.addView(hint);
        LinearLayout controls = Ui.row(this);
        TextView out = photoZoomButton("−", tr("Perkecil foto", "Zoom out"), () -> photo.zoomBy(.7f));
        TextView reset = photoZoomButton(tr("Atur ulang", "Reset"), tr("Atur ulang ukuran foto", "Reset photo zoom"), photo::resetZoom);
        TextView in = photoZoomButton("+", tr("Perbesar foto", "Zoom in"), () -> photo.zoomBy(1.5f));
        controls.addView(out, Ui.weighted(0, Ui.dp(this, 48), 1));
        LinearLayout.LayoutParams middle = Ui.weighted(0, Ui.dp(this, 48), 2);
        middle.leftMargin = Ui.dp(this, 8); middle.rightMargin = Ui.dp(this, 8);
        controls.addView(reset, middle);
        controls.addView(in, Ui.weighted(0, Ui.dp(this, 48), 1));
        root.addView(controls);
        dialog.setContentView(root);
        dialog.show();
        loadPhoto(id, PhotoPolicy.MAX_SIDE, dialog, photo, status);
    }

    private TextView photoZoomButton(String text, String description, Runnable action) {
        TextView button = Ui.text(this, text, 16, Ui.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        Ui.clickable(button, Ui.shape(this, 0xff365443, 12), v -> action.run());
        return button;
    }

    /** Reads one bounded BLOB on a worker; a dismissed screen never retains its bitmap. */
    private void loadPhoto(long id, int size, Dialog dialog, ImageView target, TextView status) {
        Context context = getApplicationContext();
        // Freeze the database choice before the worker runs (also isolates debug UI tests).
        final FinanceStore reader = new FinanceStore(context);
        photoDialogs.add(dialog);
        dialog.setOnDismissListener(d -> {
            photoDialogs.remove(dialog);
            if (target instanceof PhotoZoomView) ((PhotoZoomView) target).setPhoto(null);
            else target.setImageDrawable(null);
            // Let Android release previously displayed bitmaps after its render thread is done.
        });
        photoReader.execute(() -> {
            Bitmap image = null;
            try {
                byte[] bytes;
                try { bytes = reader.getPhoto(id); } finally { reader.close(); }
                image = PhotoProcessor.preview(bytes, size);
            } catch (RuntimeException | OutOfMemoryError ignored) { }
            final Bitmap result = image;
            if (!photoHandler.post(() -> {
                if (isFinishing() || isDestroyed() || !dialog.isShowing()) {
                    if (result != null && !result.isRecycled()) result.recycle();
                    return;
                }
                if (result == null) {
                    status.setText(tr("Foto tidak dapat dibuka. Data transaksi tetap tersimpan.", "The photo could not be opened. The transaction remains saved."));
                    return;
                }
                if (target instanceof PhotoZoomView) ((PhotoZoomView) target).setPhoto(result);
                else target.setImageBitmap(result);
                status.setText(tr("Foto terlampir", "Photo attached"));
            }) && result != null && !result.isRecycled()) result.recycle();
        });
    }

    private void confirmDelete(Transaction t) {
        new AlertDialog.Builder(this)
                .setTitle(tr("Hapus transaksi?", "Delete transaction?"))
                .setMessage("“" + t.title + tr("” akan dihapus dari catatan dan saldo", "” will be deleted from your records and balance")
                        + (t.hasPhoto ? tr(" beserta foto terlampir", " along with its attached photo") : "") + tr(". Tindakan ini tidak dapat dibatalkan.", ". This cannot be undone."))
                .setPositiveButton(tr("Hapus", "Delete"), (dialog, which) -> {
                    try {
                        if (store.delete(t.id)) Toast.makeText(this, tr("Transaksi dihapus", "Transaction deleted"), Toast.LENGTH_SHORT).show();
                        else Toast.makeText(this, tr("Transaksi tidak ditemukan", "Transaction not found"), Toast.LENGTH_LONG).show();
                        refresh();
                    } catch (RuntimeException e) {
                        new AlertDialog.Builder(this).setTitle(tr("Belum bisa menghapus", "Unable to delete"))
                                .setMessage(tr("Transaksi belum dihapus. Periksa ruang penyimpanan dan coba lagi.", "The transaction was not deleted. Check device storage and try again."))
                                .setPositiveButton(tr("Tutup", "Close"), null).show();
                    }
                })
                .setNegativeButton(tr("Batal", "Cancel"), null).show();
    }

    private void openForm(Transaction transaction) {
        Intent intent = new Intent(this, TransactionFormActivity.class);
        if (transaction != null) intent.putExtra("transaction_id", transaction.id);
        else intent.putExtra("default_date", Dates.dateInMonth(year, monthIndex, Dates.today()));
        startActivityForResult(intent, FORM_REQUEST);
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(tr("Tentang ToxiFlow", "About ToxiFlow"))
                .setMessage(tr("Catatan sederhana untuk pemasukan dan pengeluaran.\n\n", "A simple record of income and expenses.\n\n")
                        + tr("Data disimpan hanya di perangkat ini, tanpa akun atau koneksi internet. ", "Data is stored only on this device, without an account or internet connection. ")
                        + tr("Database dan foto tidak dienkripsi. Data akan hilang jika aplikasi dihapus atau datanya dibersihkan.\n\n", "The database and photos are not encrypted. Data is lost if the app is uninstalled or its data is cleared.\n\n")
                        + tr("Dana siap pakai menghitung semua transaksi, termasuk tanggal mendatang. Hutang yang diterima dan piutang yang dipinjamkan mengubah cash; pokoknya tidak masuk laporan pemasukan/pengeluaran. ", "Available funds include all transactions, including future dates. Debt received and receivables lent affect cash; their principal is excluded from income/expense reports. ")
                        + tr("Ringkasan dan laporan mengikuti periode yang dipilih.\n\n", "Summaries and reports follow the selected period.\n\n")
                        + tr("Jam pembuatan transaksi dicatat sesuai waktu perangkat saat disimpan dan tidak berubah saat diedit. ", "Creation time is recorded using the device time when a transaction is saved and does not change when edited. ")
                        + tr("Jam transaksi lama tidak tersedia.\n\n", "Creation time is unavailable for older transactions.\n\n")
                        + tr("Sorotan dan saran dibuat secara lokal dari catatan yang tersedia, bukan nasihat keuangan profesional.\n\n", "Insights and suggestions are generated locally from available records and are not professional financial advice.\n\n")
                        + tr("Ekspor CSV membuat salinan yang tidak terenkripsi untuk dibuka di aplikasi spreadsheet; ", "CSV export creates an unencrypted copy for spreadsheet apps; ")
                        + tr("file CSV belum dapat diimpor kembali ke aplikasi dan tidak memuat gambar foto.\n\n", "CSV files cannot yet be imported back into the app and do not contain photo images.\n\n")
                        + tr("Cetak laporan bulanan melalui halaman Laporan. Dokumen dan PDF berisi nominal ", "Print a monthly report from Reports. Printed documents and PDFs contain amounts ")
                        + tr("serta catatan; foto opsional ditambahkan sebagai halaman lampiran. Data dapat dibagikan ", "and notes; optional photos are added as appendix pages. Data may be shared ")
                        + tr("ke layanan cetak yang kamu pilih.", "with the print service you choose."))
                .setPositiveButton(tr("Mengerti", "Got it"), null)
                .setNeutralButton(tr("Ekspor CSV", "Export CSV"), (dialog, which) -> requestExport())
                .show();
    }

    private void requestExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "ToxiFlow-" + Dates.today() + ".csv");
        try {
            startActivityForResult(intent, EXPORT_REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, tr("Tidak ada pemilih file untuk ekspor.", "No file picker is available for export."), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == FORM_REQUEST) {
            String date = data.getStringExtra("saved_date");
            if (Dates.isValid(date)) {
                year = Integer.parseInt(date.substring(0, 4));
                monthIndex = Integer.parseInt(date.substring(5, 7)) - 1;
            }
            // onResume refreshes the list and keeps the current tab.
        } else if (requestCode == EXPORT_REQUEST && data.getData() != null) {
            exportCsv(data.getData());
        }
    }

    private void exportCsv(Uri uri) {
        try {
            List<Transaction> snapshot = store.all();
            OutputStream stream = getContentResolver().openOutputStream(uri, "wt");
            if (stream == null) throw new IOException("Penyedia file tidak membuka tujuan");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                CsvExport.write(writer, snapshot, language);
            }
            Toast.makeText(this, tr("CSV berhasil diekspor", "CSV exported successfully"), Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException e) {
            new AlertDialog.Builder(this).setTitle(tr("Ekspor belum berhasil", "Export failed"))
                    .setMessage(tr("Coba pilih lokasi lain. File yang belum lengkap mungkin tertinggal di lokasi tujuan.", "Try another location. An incomplete file may remain at the destination."))
                    .setPositiveButton(tr("Tutup", "Close"), null).show();
        }
    }
}
