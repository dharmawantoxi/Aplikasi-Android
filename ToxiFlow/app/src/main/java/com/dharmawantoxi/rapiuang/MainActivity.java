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
    private static final int HOME = 0, TRANSACTIONS = 1, REPORTS = 2;
    private FinanceStore store;
    private LinearLayout root;
    private LinearLayout transactionList;
    private List<Transaction> all = new ArrayList<>();
    private List<Transaction> month = new ArrayList<>();
    private int page = HOME;
    private int year, monthIndex;
    private String filter = "";
    private String search = "";
    private boolean hideAmounts;
    private boolean loadFailed;
    private final ExecutorService photoReader = Executors.newSingleThreadExecutor();
    private final List<Dialog> photoDialogs = new ArrayList<>();
    private final Handler photoHandler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        refresh();
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
        store.close();
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
            TextView heading = Ui.text(this, "Data belum bisa dibuka", 20, Ui.INK, true);
            TextView description = Ui.text(this, "Periksa ruang penyimpanan perangkat, lalu coba lagi. Data tidak dihapus.", 14, Ui.MUTED, false);
            description.setGravity(Gravity.CENTER);
            problem.addView(heading);
            problem.addView(Ui.gap(this, 8));
            problem.addView(description);
            problem.addView(Ui.gap(this, 18));
            TextView retry = Ui.text(this, "Coba lagi", 15, Ui.WHITE, true);
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
        else buildReports(content);
        root.addView(scroll, Ui.weighted(-1, 0, 1));
        root.addView(bottomNavigation(), Ui.lp(-1, Ui.dp(this, 76)));
    }

    private void buildHome(LinearLayout content) {
        content.addView(brandHeader());
        content.addView(Ui.gap(this, 22));
        content.addView(Ui.overline(this, "RUANG UNTUK KEUANGANMU", Ui.GREEN));
        content.addView(Ui.gap(this, 5));
        content.addView(Ui.display(this, "Uang lebih rapi,\nhidup lebih tenang.", 27));
        content.addView(Ui.gap(this, 7));
        content.addView(Ui.text(this, "Pelan-pelan, mulai dari satu catatan.", 13, Ui.MUTED, false));
        content.addView(Ui.gap(this, 20));
        content.addView(monthSelector());
        content.addView(Ui.gap(this, 12));
        content.addView(balanceCard());
        content.addView(Ui.gap(this, 14));
        content.addView(addTransactionCallout());
        content.addView(Ui.gap(this, 25));
        content.addView(sectionHeading("Transaksi terbaru", "Lihat semua", () -> {
            page = TRANSACTIONS;
            filter = "";
            search = "";
            render();
        }));
        content.addView(Ui.gap(this, 4));
        content.addView(Ui.text(this, "Di periode " + Dates.monthLabel(year, monthIndex), 12, Ui.MUTED, false));
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
        TextView caption = Ui.text(this, "  Tersimpan di perangkatmu", 11, Ui.MUTED, false);
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
        wordmark.addView(Ui.text(this, "CATATAN KEUANGAN PRIBADI", 9, Ui.MUTED, true));
        FrameLayout info = Ui.iconBox(this, "info", Ui.GREEN, Ui.WHITE, 48, 21, 14);
        info.setContentDescription("Tentang aplikasi dan ekspor data");
        Ui.clickable(info, Ui.outlined(this, Ui.WHITE, Ui.LINE, 14), v -> showAbout());
        row.addView(info);
        return row;
    }

    private View pageHeader(String title, String subtitle) {
        LinearLayout row = Ui.row(this);
        LinearLayout labels = Ui.column(this);
        labels.addView(Ui.overline(this, "TOXIFLOW / CATATANMU", Ui.GREEN));
        labels.addView(Ui.gap(this, 5));
        labels.addView(Ui.display(this, title, 29));
        labels.addView(Ui.gap(this, 3));
        labels.addView(Ui.text(this, subtitle, 13, Ui.MUTED, false));
        row.addView(labels, Ui.weighted(0, -2, 1));
        FrameLayout info = Ui.iconBox(this, "info", Ui.GREEN, Ui.WHITE, 48, 21, 14);
        info.setContentDescription("Tentang aplikasi dan ekspor data");
        Ui.clickable(info, Ui.outlined(this, Ui.WHITE, Ui.LINE, 14), v -> showAbout());
        row.addView(info);
        return row;
    }

    private View monthSelector() {
        LinearLayout row = Ui.row(this);
        TextView label = Ui.overline(this, "PERIODE", Ui.MUTED);
        row.addView(label, Ui.weighted(0, -2, 1));
        LinearLayout selector = Ui.row(this);
        selector.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 14));
        FrameLayout previous = Ui.iconBox(this, "back", Ui.GREEN, Ui.WHITE, 48, 17, 13);
        previous.setContentDescription("Bulan sebelumnya");
        Ui.clickable(previous, Ui.shape(this, Ui.WHITE, 13), v -> changeMonth(-1));
        selector.addView(previous);
        TextView date = Ui.text(this, Dates.monthLabel(year, monthIndex), 13, Ui.INK, true);
        date.setGravity(Gravity.CENTER);
        date.setMinWidth(Ui.dp(this, 116));
        date.setContentDescription("Pilih periode, saat ini " + Dates.monthLabel(year, monthIndex));
        Ui.clickable(date, Ui.shape(this, Ui.WHITE, 12), v -> chooseMonth());
        selector.addView(date, Ui.lp(-2, Ui.dp(this, 48)));
        FrameLayout next = Ui.iconBox(this, "next", Ui.GREEN, Ui.WHITE, 48, 17, 13);
        next.setContentDescription("Bulan berikutnya");
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
        dialog.setTitle("Pilih bulan dan tahun");
        dialog.getDatePicker().setMinDate(Dates.calendarFor("1900-01-01").getTimeInMillis());
        dialog.getDatePicker().setMaxDate(Dates.calendarFor("2100-12-31").getTimeInMillis());
        dialog.show();
    }

    private View balanceCard() {
        FinanceMath.Summary total = FinanceMath.summarize(all);
        FinanceMath.Summary selected = FinanceMath.summarize(month);
        DecorCard card = new DecorCard(this);
        LinearLayout inside = Ui.column(this);
        Ui.pad(inside, 21, 18, 21, 19);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.overline(this, "SALDO KESELURUHAN", 0xffc6ddca), Ui.weighted(0, -2, 1));
        FrameLayout eye = Ui.iconBox(this, hideAmounts ? "eyeoff" : "eye", 0xffd5e9d8, 0x22ffffff, 36, 19, 12);
        eye.setContentDescription(hideAmounts ? "Tampilkan nominal di beranda" : "Sembunyikan nominal di beranda");
        Ui.clickable(eye, Ui.shape(this, 0x22ffffff, 12), v -> {
            hideAmounts = !hideAmounts;
            getPreferences(MODE_PRIVATE).edit().putBoolean("hide_dashboard_amounts", hideAmounts).apply();
            render();
        });
        top.addView(eye);
        inside.addView(top);
        TextView amount = Ui.text(this, hideAmounts ? "Rp ••••••" : Money.format(total.balance), 30, Ui.WHITE, true);
        amount.setContentDescription(hideAmounts ? "Saldo disembunyikan" : "Saldo keseluruhan " + Money.format(total.balance));
        inside.addView(amount);
        inside.addView(Ui.gap(this, 2));
        inside.addView(Ui.text(this, "Dari semua transaksi yang dicatat", 11, 0xffbed1c2, false));
        inside.addView(Ui.gap(this, 17));
        View line = new View(this);
        line.setBackgroundColor(0x38d5ead8);
        inside.addView(line, Ui.lp(-1, Ui.dp(this, 1)));
        inside.addView(Ui.gap(this, 15));
        LinearLayout subtotals = Ui.row(this);
        subtotals.setGravity(Gravity.TOP);
        subtotals.addView(cardSubtotal("income", "Pemasukan", selected.income, 0xffc8e9ce), Ui.weighted(0, -2, 1));
        View space = new View(this);
        subtotals.addView(space, Ui.lp(Ui.dp(this, 8), 1));
        subtotals.addView(cardSubtotal("expense", "Pengeluaran", selected.expense, 0xfff4cbbc), Ui.weighted(0, -2, 1));
        inside.addView(subtotals);
        inside.addView(Ui.gap(this, 11));
        inside.addView(Ui.text(this, "Ringkasan periode terpilih", 10, 0xffafc8b6, false));
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
        button.setContentDescription("Catat transaksi baru");
        button.addView(Ui.iconBox(this, "plus", Ui.DARK, Ui.MINT, 38, 19, 12));
        LinearLayout labels = Ui.column(this);
        LinearLayout.LayoutParams p = Ui.weighted(0, -2, 1);
        p.leftMargin = Ui.dp(this, 12);
        labels.addView(Ui.text(this, "Catat transaksi", 14, Ui.WHITE, true));
        labels.addView(Ui.text(this, "Pemasukan atau pengeluaran", 11, 0xffc5ddca, false));
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
        labels.addView(Ui.text(this, filtered ? "Tidak ada hasil" : "Belum ada transaksi", 14, Ui.INK, true));
        labels.addView(Ui.gap(this, 3));
        labels.addView(Ui.text(this, filtered ? "Coba kata kunci atau filter lain." : "Catatan pertamamu akan muncul di sini.", 12, Ui.MUTED, false));
        empty.addView(labels, params);
        return empty;
    }

    private void buildTransactions(LinearLayout content) {
        content.addView(pageHeader("Transaksi", "Semua catatan, dalam satu tempat."));
        content.addView(Ui.gap(this, 24));
        content.addView(monthSelector());
        content.addView(Ui.gap(this, 18));
        LinearLayout chips = Ui.row(this);
        chips.addView(filterChip("Semua", ""), Ui.weighted(0, -2, 1));
        chips.addView(horizontalGap(6));
        chips.addView(filterChip("Pemasukan", Transaction.INCOME), Ui.weighted(0, -2, 1.4f));
        chips.addView(horizontalGap(6));
        chips.addView(filterChip("Pengeluaran", Transaction.EXPENSE), Ui.weighted(0, -2, 1.5f));
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
        input.setHint("Cari nama, kategori, atau catatan");
        input.setContentDescription("Cari transaksi");
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
        chip.setContentDescription("Filter " + title + (selected ? ", dipilih" : ""));
        Ui.clickable(chip, selected ? Ui.shape(this, Ui.GREEN, 13) : Ui.outlined(this, Ui.WHITE, Ui.LINE, 13), v -> {
            filter = value;
            render();
        });
        return chip;
    }

    private void updateTransactionList() {
        if (transactionList == null) return;
        transactionList.removeAllViews();
        List<Transaction> filtered = FinanceMath.filter(month, filter, search);
        if (filtered.isEmpty()) {
            boolean filtering = !filter.isEmpty() || !search.trim().isEmpty();
            transactionList.addView(emptyState(filtering));
            if (!filtering) {
                transactionList.addView(Ui.gap(this, 14));
                transactionList.addView(addTransactionCallout());
            }
            return;
        }
        TextView count = Ui.overline(this, filtered.size() + " TRANSAKSI", Ui.MUTED);
        transactionList.addView(count);
        transactionList.addView(Ui.gap(this, 12));
        String lastDate = "";
        LinearLayout dayCard = null;
        for (Transaction transaction : filtered) {
            if (!transaction.date.equals(lastDate)) {
                if (!lastDate.isEmpty()) transactionList.addView(Ui.gap(this, 18));
                String label = transaction.date.equals(Dates.today()) ? "Hari ini · " + Dates.display(transaction.date) : Dates.display(transaction.date);
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
        TextView subtitle = Ui.text(this, t.category + (showDate ? " · " + Dates.displayShort(t.date) : "")
                + (t.hasPhoto ? " · Foto" : ""), 11, Ui.MUTED, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(subtitle);
        text.addView(Ui.gap(this, 2));
        TextView created = Ui.text(this, CreationTime.listLabel(t.createdAt), 11, Ui.MUTED, false);
        created.setMaxLines(1);
        created.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(created);
        row.addView(text, middle);
        boolean masked = showDate && hideAmounts;
        TextView amount = Ui.text(this, masked ? "••••••" : Money.signed(t.amount, income), 13, income ? Ui.GREEN : Ui.INK, true);
        amount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        amount.setMaxWidth(Ui.dp(this, 145));
        row.addView(amount, Ui.lp(-2, -2));
        row.setContentDescription(t.title + ", " + t.category + ", " + (income ? "pemasukan " : "pengeluaran ")
                + (masked ? "nominal disembunyikan" : Money.format(t.amount))
                + ", tanggal transaksi " + Dates.display(t.date) + ", dibuat " + CreationTime.display(t.createdAt)
                + (t.hasPhoto ? ", ada foto terlampir" : "") + ". Ketuk untuk detail.");
        row.setOnClickListener(v -> showTransaction(t));
        row.setFocusable(true);
        return row;
    }

    private void buildReports(LinearLayout content) {
        content.addView(pageHeader("Laporan", "Kenali ke mana uangmu mengalir."));
        content.addView(Ui.gap(this, 24));
        content.addView(monthSelector());
        content.addView(Ui.gap(this, 12));
        content.addView(printReportButton());
        content.addView(Ui.gap(this, 18));
        FinanceMath.Summary summary = FinanceMath.summarize(month);
        LinearLayout flow = Ui.column(this);
        flow.setBackground(Ui.shape(this, Ui.WHITE, 21));
        Ui.pad(flow, 19, 19, 19, 20);
        flow.addView(Ui.overline(this, "RINGKASAN PERIODE", Ui.MUTED));
        flow.addView(Ui.gap(this, 5));
        flow.addView(Ui.text(this, "Arus kas", 18, Ui.INK, true));
        flow.addView(Ui.gap(this, 5));
        flow.addView(Ui.text(this, "Pemasukan dan pengeluaran yang dicatat.", 12, Ui.MUTED, false));
        flow.addView(Ui.gap(this, 20));
        BigInteger totalFlow = summary.income.add(summary.expense);
        int share = FinanceMath.percent(summary.income, totalFlow);
        int background = totalFlow.signum() == 0 ? Ui.LINE : 0xffe9ae9b;
        flow.addView(new ProgressStrip(this, share, 0xff6ca984, background), Ui.lp(-1, Ui.dp(this, 9)));
        flow.addView(Ui.gap(this, 18));
        flow.addView(reportAmountRow("Pemasukan", summary.income, 0xff6ca984));
        flow.addView(Ui.gap(this, 12));
        flow.addView(reportAmountRow("Pengeluaran", summary.expense, 0xffe9ae9b));
        flow.addView(Ui.gap(this, 16));
        flow.addView(Ui.divider(this));
        flow.addView(Ui.gap(this, 14));
        LinearLayout balance = Ui.row(this);
        balance.addView(Ui.text(this, "Selisih periode", 13, Ui.INK, true), Ui.weighted(0, -2, 1));
        balance.addView(Ui.text(this, Money.format(summary.balance), 16, summary.balance.signum() < 0 ? Ui.CORAL : Ui.GREEN, true));
        flow.addView(balance);
        content.addView(flow);
        FinanceInsights insights = FinanceInsights.forMonth(year, monthIndex, all);
        content.addView(Ui.gap(this, 24));
        content.addView(sectionHeading("Sorotan bulanan", null, null));
        content.addView(Ui.gap(this, 5));
        content.addView(Ui.text(this, "Berdasarkan transaksi di bulan terpilih.", 12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 12));
        content.addView(insightHighlights(insights));
        content.addView(Ui.gap(this, 24));
        content.addView(sectionHeading("Pengeluaran berulang", null, null));
        content.addView(Ui.gap(this, 5));
        content.addView(Ui.text(this, "Nama dan kategori sama: minimal dua kali bulan ini atau pada dua bulan dalam rentang yang diperiksa.", 12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 12));
        content.addView(recurringInsightCard(insights));
        content.addView(Ui.gap(this, 24));
        content.addView(sectionHeading("Saran sederhana", null, null));
        content.addView(Ui.gap(this, 12));
        content.addView(suggestionCard(insights));
        content.addView(Ui.gap(this, 24));
        content.addView(sectionHeading("Pengeluaran per kategori", null, null));
        content.addView(Ui.gap(this, 5));
        content.addView(Ui.text(this, "Lihat bagian terbesar pengeluaranmu.", 12, Ui.MUTED, false));
        content.addView(Ui.gap(this, 13));
        List<FinanceMath.CategoryTotal> categories = FinanceMath.expenseCategories(month);
        if (categories.isEmpty()) {
            LinearLayout empty = Ui.column(this);
            empty.setGravity(Gravity.CENTER);
            Ui.pad(empty, 20, 24, 20, 24);
            empty.setBackground(Ui.shape(this, Ui.WHITE, 20));
            empty.addView(Ui.iconBox(this, "chart", Ui.GREEN, Ui.PALE, 45, 22, 15));
            empty.addView(Ui.gap(this, 10));
            TextView label = Ui.text(this, "Belum ada pengeluaran", 14, Ui.INK, true);
            label.setGravity(Gravity.CENTER);
            empty.addView(label);
            empty.addView(Ui.gap(this, 3));
            TextView help = Ui.text(this, "Laporan akan muncul setelah kamu mencatatnya.", 12, Ui.MUTED, false);
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

    private View insightHighlights(FinanceInsights insights) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.shape(this, Ui.WHITE, 20));
        Ui.pad(card, 17, 17, 17, 17);
        card.addView(Ui.overline(this, "PENGELUARAN TERBESAR", Ui.MUTED));
        card.addView(Ui.gap(this, 7));
        if (insights.largestExpense == null) {
            card.addView(Ui.text(this, "Belum ada pengeluaran pada periode ini.", 13, Ui.MUTED, false));
        } else {
            Transaction t = insights.largestExpense;
            card.addView(Ui.text(this, "Transaksi: " + t.title, 14, Ui.INK, true));
            card.addView(Ui.gap(this, 3));
            card.addView(Ui.text(this, Money.format(t.amount) + " · " + t.category + " · " + Dates.display(t.date),
                    12, Ui.MUTED, false));
            if (insights.largestExpenseCategory != null) {
                FinanceMath.CategoryTotal category = insights.largestExpenseCategory;
                int percent = FinanceMath.percent(category.amount, insights.summary.expense);
                card.addView(Ui.gap(this, 6));
                card.addView(Ui.text(this, "Kategori terbesar: " + category.category + " · "
                        + Money.format(category.amount) + " (" + percent + "% dari pengeluaran)", 12, Ui.INK, false));
            }
        }
        card.addView(Ui.gap(this, 16));
        card.addView(Ui.divider(this));
        card.addView(Ui.gap(this, 16));
        card.addView(Ui.overline(this, "PEMASUKAN TERBESAR", Ui.MUTED));
        card.addView(Ui.gap(this, 7));
        if (insights.largestIncome == null) {
            card.addView(Ui.text(this, "Belum ada pemasukan pada periode ini.", 13, Ui.MUTED, false));
        } else {
            Transaction t = insights.largestIncome;
            card.addView(Ui.text(this, "Transaksi: " + t.title, 14, Ui.INK, true));
            card.addView(Ui.gap(this, 3));
            card.addView(Ui.text(this, Money.format(t.amount) + " · " + t.category + " · " + Dates.display(t.date),
                    12, Ui.MUTED, false));
            FinanceInsights.Group source = insights.largestIncomeSource;
            if (source != null) {
                card.addView(Ui.gap(this, 6));
                card.addView(Ui.text(this, "Sumber terbesar: " + source.title + " · " + source.category
                        + " · " + Money.format(source.total) + " (" + source.count + " transaksi bulan ini)",
                        12, Ui.INK, false));
                card.addView(Ui.gap(this, 3));
                card.addView(Ui.text(this, "Sumber dikelompokkan menurut nama dan kategori yang sama.", 10, Ui.MUTED, false));
            }
        }
        return card;
    }

    private View recurringInsightCard(FinanceInsights insights) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.shape(this, Ui.WHITE, 20));
        Ui.pad(card, 17, 16, 17, 16);
        if (insights.recurringExpenses.isEmpty()) {
            card.addView(Ui.text(this, "Belum ada pengeluaran berulang yang terdeteksi pada periode ini.", 13, Ui.MUTED, false));
        } else {
            int shown = Math.min(FinanceInsights.MAX_RECURRING_SHOWN, insights.recurringExpenses.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) { card.addView(Ui.gap(this, 12)); card.addView(Ui.divider(this)); card.addView(Ui.gap(this, 12)); }
                FinanceInsights.Group group = insights.recurringExpenses.get(i);
                card.addView(Ui.text(this, group.title + " · " + group.category, 14, Ui.INK, true));
                card.addView(Ui.gap(this, 3));
                card.addView(Ui.text(this, "Total bulan ini: " + Money.format(group.total), 13, Ui.INK, true));
                card.addView(Ui.gap(this, 3));
                card.addView(Ui.text(this, insights.recurringEvidence(group), 11, Ui.MUTED, false));
            }
            if (insights.recurringExpenses.size() > shown) {
                card.addView(Ui.gap(this, 10));
                card.addView(Ui.text(this, "Menampilkan " + shown + " dari " + insights.recurringExpenses.size()
                        + " teratas berdasarkan total bulan ini.", 11, Ui.MUTED, false));
            }
        }
        card.addView(Ui.gap(this, 10));
        card.addView(Ui.text(this, "Rentang: bulan terpilih dan hingga dua bulan sebelumnya. Pola ini tidak memastikan jadwal rutin.",
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
        card.addView(Ui.text(this, "Saran otomatis berdasarkan catatan yang tersedia, bukan nasihat keuangan profesional.",
                11, Ui.MUTED, false));
        return card;
    }

    private View printReportButton() {
        LinearLayout button = Ui.row(this);
        Ui.pad(button, 14, 12, 14, 12);
        button.setContentDescription("Cetak laporan bulanan " + Dates.monthLabel(year, monthIndex));
        Ui.clickable(button, Ui.outlined(this, Ui.WHITE, Ui.LINE, 18), v -> confirmMonthlyPrint());
        button.addView(Ui.iconBox(this, "print", Ui.GREEN, Ui.PALE, 42, 21, 13));
        LinearLayout labels = Ui.column(this);
        LinearLayout.LayoutParams params = Ui.weighted(0, -2, 1);
        params.leftMargin = Ui.dp(this, 12);
        labels.addView(Ui.text(this, "Cetak laporan bulanan", 14, Ui.INK, true));
        labels.addView(Ui.gap(this, 3));
        labels.addView(Ui.text(this, "Cetak atau simpan sebagai PDF", 11, Ui.MUTED, false));
        button.addView(labels, params);
        button.addView(new IconView(this, "next", Ui.GREEN), Ui.lp(Ui.dp(this, 19), Ui.dp(this, 19)));
        return button;
    }

    private void confirmMonthlyPrint() {
        // Fix the selected month; a consistent snapshot is captured when printing is prepared.
        MonthlyReport report = MonthlyReport.forMonth(year, monthIndex, all);
        CheckBox includePhotos = new CheckBox(this);
        includePhotos.setText("Sertakan foto sebagai lampiran");
        includePhotos.setChecked(true);
        String disclosure = "Laporan memuat seluruh transaksi, nominal, waktu pembuatan, dan catatan pada periode ini, "
                + "termasuk nominal yang disembunyikan di beranda.\n\n"
                + "Jika dipilih, foto ikut dicetak atau disimpan sebagai halaman lampiran di PDF. "
                + "Foto dan data diberikan kepada layanan cetak yang dipilih. CSV tidak memuat gambar.\n\n"
                + "Pola berulang juga menghitung keberadaan transaksi dengan nama dan kategori yang sama "
                + "pada hingga dua bulan sebelumnya.\n\n"
                + "Maksimal " + PhotoPrintPolicy.MAX_PHOTOS_PER_WRITE
                + " foto per proses cetak; pilih rentang halaman lebih kecil bila perlu.";
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
                .setTitle("Cetak " + report.period() + "?")
                .setView(scroll)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Lanjutkan", (dialog, which) -> startMonthlyPrint(report, includePhotos.isChecked()))
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
        new AlertDialog.Builder(this).setTitle("Belum bisa mencetak")
                .setMessage("Layanan cetak Android tidak tersedia. Periksa layanan cetak perangkat dan coba lagi.")
                .setPositiveButton("Tutup", null).show();
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
        head.addView(Ui.text(this, category.category, 13, Ui.INK, true), Ui.weighted(0, -2, 1));
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
        row.setContentDescription(category.category + ", " + Money.format(category.amount) + ", " + percent + " persen dari pengeluaran");
        return row;
    }

    private View bottomNavigation() {
        LinearLayout container = Ui.column(this);
        container.setBackgroundColor(Ui.WHITE);
        container.addView(Ui.divider(this));
        LinearLayout nav = Ui.row(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(navItem("home", "Beranda", page == HOME, () -> { page = HOME; render(); }), Ui.weighted(0, -1, 1));
        nav.addView(navItem("list", "Transaksi", page == TRANSACTIONS, () -> { page = TRANSACTIONS; render(); }), Ui.weighted(0, -1, 1));
        nav.addView(navItem("plus", "Catat", false, () -> openForm(null)), Ui.weighted(0, -1, 1));
        nav.addView(navItem("chart", "Laporan", page == REPORTS, () -> { page = REPORTS; render(); }), Ui.weighted(0, -1, 1));
        container.addView(nav, Ui.weighted(-1, 0, 1));
        return container;
    }

    private View navItem(String icon, String label, boolean selected, Runnable action) {
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        item.setContentDescription(label + (selected ? ", halaman aktif" : ""));
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
        String details = (income ? "Pemasukan" : "Pengeluaran") + " · " + t.category + "\n\n"
                + Money.format(t.amount) + "\nTanggal transaksi: " + Dates.display(t.date)
                + "\nDibuat: " + CreationTime.display(t.createdAt)
                + (t.note.isEmpty() ? "" : "\n\nCatatan: " + t.note);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(t.title)
                .setPositiveButton("Ubah", (dialog, which) -> openForm(t))
                .setNeutralButton("Hapus", (dialog, which) -> confirmDelete(t))
                .setNegativeButton("Tutup", null);
        if (!t.hasPhoto) {
            builder.setMessage(details).show();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 24, 4, 24, 12);
        content.addView(Ui.text(this, details, 14, Ui.INK, false));
        content.addView(Ui.gap(this, 14));
        TextView status = Ui.text(this, "Memuat foto…", 12, Ui.MUTED, false);
        content.addView(status);
        content.addView(Ui.gap(this, 6));
        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumbnail.setContentDescription("Pratinjau foto terlampir");
        thumbnail.setBackground(Ui.outlined(this, Ui.WHITE, Ui.LINE, 14));
        content.addView(thumbnail, Ui.lp(-1, Ui.dp(this, 150)));
        TextView view = Ui.text(this, "Lihat foto", 13, Ui.GREEN, true);
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
        header.addView(Ui.text(this, "Foto transaksi", 17, Ui.WHITE, true), Ui.weighted(0, -2, 1));
        TextView close = Ui.text(this, "Tutup", 14, Ui.WHITE, true);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Tutup foto");
        Ui.clickable(close, Ui.shape(this, 0xff365443, 12), v -> dialog.dismiss());
        header.addView(close, Ui.lp(Ui.dp(this, 70), Ui.dp(this, 48)));
        root.addView(header);
        TextView status = Ui.text(this, "Memuat foto…", 12, Ui.WHITE, false);
        Ui.pad(status, 4, 8, 4, 8);
        root.addView(status);
        PhotoZoomView photo = new PhotoZoomView(this);
        root.addView(photo, Ui.weighted(-1, 0, 1));
        TextView hint = Ui.text(this, "Cubit atau gunakan tombol untuk memperbesar, lalu geser foto.", 11, 0xffd0ded2, false);
        hint.setGravity(Gravity.CENTER);
        Ui.pad(hint, 0, 8, 0, 8);
        root.addView(hint);
        LinearLayout controls = Ui.row(this);
        TextView out = photoZoomButton("−", "Perkecil foto", () -> photo.zoomBy(.7f));
        TextView reset = photoZoomButton("Atur ulang", "Atur ulang ukuran foto", photo::resetZoom);
        TextView in = photoZoomButton("+", "Perbesar foto", () -> photo.zoomBy(1.5f));
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
                    status.setText("Foto tidak dapat dibuka. Data transaksi tetap tersimpan.");
                    return;
                }
                if (target instanceof PhotoZoomView) ((PhotoZoomView) target).setPhoto(result);
                else target.setImageBitmap(result);
                status.setText("Foto terlampir");
            }) && result != null && !result.isRecycled()) result.recycle();
        });
    }

    private void confirmDelete(Transaction t) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus transaksi?")
                .setMessage("“" + t.title + "” akan dihapus dari catatan dan saldo"
                        + (t.hasPhoto ? " beserta foto terlampir" : "") + ". Tindakan ini tidak dapat dibatalkan.")
                .setPositiveButton("Hapus", (dialog, which) -> {
                    try {
                        if (store.delete(t.id)) Toast.makeText(this, "Transaksi dihapus", Toast.LENGTH_SHORT).show();
                        else Toast.makeText(this, "Transaksi tidak ditemukan", Toast.LENGTH_LONG).show();
                        refresh();
                    } catch (RuntimeException e) {
                        new AlertDialog.Builder(this).setTitle("Belum bisa menghapus")
                                .setMessage("Transaksi belum dihapus. Periksa ruang penyimpanan dan coba lagi.")
                                .setPositiveButton("Tutup", null).show();
                    }
                })
                .setNegativeButton("Batal", null).show();
    }

    private void openForm(Transaction transaction) {
        Intent intent = new Intent(this, TransactionFormActivity.class);
        if (transaction != null) intent.putExtra("transaction_id", transaction.id);
        else intent.putExtra("default_date", Dates.dateInMonth(year, monthIndex, Dates.today()));
        startActivityForResult(intent, FORM_REQUEST);
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Tentang ToxiFlow")
                .setMessage("Catatan sederhana untuk pemasukan dan pengeluaran.\n\n"
                        + "Data disimpan hanya di perangkat ini, tanpa akun atau koneksi internet. "
                        + "Database dan foto tidak dienkripsi. Data akan hilang jika aplikasi dihapus atau datanya dibersihkan.\n\n"
                        + "Saldo keseluruhan menghitung semua transaksi yang dicatat, termasuk tanggal mendatang. "
                        + "Ringkasan dan laporan mengikuti periode yang dipilih.\n\n"
                        + "Jam pembuatan transaksi dicatat sesuai waktu perangkat saat disimpan dan tidak berubah saat diedit. "
                        + "Jam transaksi lama tidak tersedia.\n\n"
                        + "Sorotan dan saran dibuat secara lokal dari catatan yang tersedia, bukan nasihat keuangan profesional.\n\n"
                        + "Ekspor CSV membuat salinan yang tidak terenkripsi untuk dibuka di aplikasi spreadsheet; "
                        + "file CSV belum dapat diimpor kembali ke aplikasi dan tidak memuat gambar foto.\n\n"
                        + "Cetak laporan bulanan melalui halaman Laporan. Dokumen dan PDF berisi nominal "
                        + "serta catatan; foto opsional ditambahkan sebagai halaman lampiran. Data dapat dibagikan "
                        + "ke layanan cetak yang kamu pilih.")
                .setPositiveButton("Mengerti", null)
                .setNeutralButton("Ekspor CSV", (dialog, which) -> requestExport())
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
            Toast.makeText(this, "Tidak ada pemilih file untuk ekspor.", Toast.LENGTH_LONG).show();
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
                CsvExport.write(writer, snapshot);
            }
            Toast.makeText(this, "CSV berhasil diekspor", Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException e) {
            new AlertDialog.Builder(this).setTitle("Ekspor belum berhasil")
                    .setMessage("Coba pilih lokasi lain. File yang belum lengkap mungkin tertinggal di lokasi tujuan.")
                    .setPositiveButton("Tutup", null).show();
        }
    }
}
