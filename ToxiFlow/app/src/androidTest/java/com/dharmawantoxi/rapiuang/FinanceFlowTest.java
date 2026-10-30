package com.dharmawantoxi.rapiuang;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasErrorText;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;

@RunWith(AndroidJUnit4.class)
public final class FinanceFlowTest {
    private static final String TEST_DB = "rapiuang-ui-test.db";
    private Context context;
    private LanguageTestPreference testLanguage;

    @Before public void useIsolatedDatabase() {
        context = ApplicationProvider.getApplicationContext();
        testLanguage = new LanguageTestPreference(context, AppLanguage.INDONESIAN);
        context.deleteDatabase(TEST_DB);
        FinanceStore.useDatabaseForUiTests(context, true);
    }

    @After public void removeIsolatedDatabase() {
        if (testLanguage != null) testLanguage.close();
        FinanceStore.useDatabaseForUiTests(context, false);
        context.deleteDatabase(TEST_DB);
    }

    // Exercise the home screen directly; launcher behavior is covered by SplashActivityTest.
    private ActivityScenario<MainActivity> launchHome() {
        return ActivityScenario.launch(new Intent(context, MainActivity.class));
    }

    private Transaction onlySavedTransaction() {
        FinanceStore read = new FinanceStore(context);
        try {
            java.util.List<Transaction> transactions = read.all();
            assertEquals(1, transactions.size());
            return transactions.get(0);
        } finally {
            read.close();
        }
    }

    @Test public void recordIncomePersistsAndCanBeDeleted() {
        try (ActivityScenario<MainActivity> scenario = launchHome()) {
            onView(withText("ToxiFlow")).check(matches(isDisplayed()));
            onView(withText("Catat transaksi")).perform(scrollTo(), click());
            onView(withText("Pemasukan")).perform(click());
            onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(typeText("1000000"), closeSoftKeyboard());
            onView(withHint("Contoh: Gaji bulanan")).perform(typeText("Gaji bulanan"), closeSoftKeyboard());
            onView(withText("Jam pembuatan dicatat otomatis saat disimpan, sesuai jam perangkat."))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Simpan transaksi")).perform(click());
            Transaction saved = onlySavedTransaction();
            assertTrue(CreationTime.isValid(saved.createdAt));
            onView(withText(CreationTime.listLabel(saved.createdAt))).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Gaji bulanan")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("+ Rp1.000.000")).check(matches(isDisplayed()));
            scenario.recreate();
            onView(withText("Gaji bulanan")).perform(scrollTo()).check(matches(isDisplayed())).perform(click());
            onView(withText("Pemasukan · Gaji\n\nRp1.000.000\nTanggal transaksi: " + Dates.display(saved.date)
                    + "\nDibuat: " + CreationTime.display(saved.createdAt))).check(matches(isDisplayed()));
            onView(withText("Hapus")).perform(click());
            onView(withText("Hapus")).perform(click());
            onView(withText("Belum ada transaksi")).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test public void expenseCanBeEditedAndAppearsInReport() {
        try (ActivityScenario<MainActivity> scenario = launchHome()) {
            onView(withText("Catat transaksi")).perform(scrollTo(), click());
            onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(typeText("25000"), closeSoftKeyboard());
            onView(withHint("Contoh: Makan siang")).perform(typeText("Makan malam"), closeSoftKeyboard());
            onView(withText("Simpan transaksi")).perform(click());
            String createdAt = onlySavedTransaction().createdAt;
            assertTrue(CreationTime.isValid(createdAt));
            onView(withText("Laporan")).perform(click());
            onView(withText("Makan & minum")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Transaksi")).perform(click());
            onView(withText("Makan malam")).perform(scrollTo(), click());
            onView(withText("Ubah")).perform(click());
            onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(replaceText("40000"), closeSoftKeyboard());
            onView(withText("Simpan transaksi")).perform(click());
            assertEquals(createdAt, onlySavedTransaction().createdAt);
            onView(withText("− Rp40.000")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Laporan")).perform(click());
            onView(withText("Kategori terbesar: Makan & minum · Rp40.000 (100% dari pengeluaran)"))
                    .perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test public void invalidPastedNominalIsNotSilentlySaved() {
        try (ActivityScenario<MainActivity> scenario = launchHome()) {
            onView(withText("Catat transaksi")).perform(scrollTo(), click());
            onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(replaceText("12.500"), closeSoftKeyboard());
            onView(withHint("Contoh: Makan siang")).perform(typeText("Makan"), closeSoftKeyboard());
            onView(withText("Simpan transaksi")).perform(click());
            onView(withContentDescription("Nominal transaksi dalam rupiah"))
                    .check(matches(hasErrorText("Masukkan nominal rupiah tanpa titik atau koma (maksimal 12 digit).")));
            onView(withText("Simpan transaksi")).check(matches(isDisplayed()));
            pressBack();
            onView(withText("Buang")).perform(click());
        }
    }

    @Test public void oldTransactionsShowThatCreationTimeWasNotRecorded() {
        SQLiteDatabase legacy = context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null);
        try {
            legacy.execSQL("CREATE TABLE transactions (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "type TEXT NOT NULL, amount INTEGER NOT NULL, title TEXT NOT NULL, "
                    + "category TEXT NOT NULL, date TEXT NOT NULL, note TEXT NOT NULL DEFAULT '')");
            legacy.execSQL("CREATE INDEX transactions_date ON transactions(date DESC, _id DESC)");
            legacy.execSQL("INSERT INTO transactions (type,amount,title,category,date,note) VALUES (?,?,?,?,?,?)",
                    new Object[]{"expense", 500, "Catatan lama", "Lainnya", Dates.today(), ""});
            legacy.setVersion(1);
        } finally {
            legacy.close();
        }
        try (ActivityScenario<MainActivity> scenario = launchHome()) {
            onView(withText("Jam pembuatan tidak tercatat")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Catatan lama")).perform(scrollTo(), click());
            onView(withText("Pengeluaran · Lainnya\n\nRp500\nTanggal transaksi: " + Dates.display(Dates.today())
                    + "\nDibuat: Tidak tercatat")).check(matches(isDisplayed()));
        }
    }

    @Test public void reportShowsLargestRepeatedAndIncomeForSelectedMonth() {
        Calendar now = Calendar.getInstance();
        String day1 = Dates.iso(now.get(Calendar.YEAR), now.get(Calendar.MONTH), 5);
        String day2 = Dates.iso(now.get(Calendar.YEAR), now.get(Calendar.MONTH), 10);
        Calendar previous = (Calendar) now.clone();
        previous.add(Calendar.MONTH, -1);
        String prior = Dates.iso(previous.get(Calendar.YEAR), previous.get(Calendar.MONTH), 5);
        FinanceStore seed = new FinanceStore(context);
        try {
            seed.save(new Transaction(0, Transaction.EXPENSE, 10000, "Internet", "Tagihan", day1, ""));
            seed.save(new Transaction(0, Transaction.EXPENSE, 30000, "internet", "Tagihan", day2, ""));
            seed.save(new Transaction(0, Transaction.EXPENSE, 7000, "Internet", "Tagihan", prior, ""));
            seed.save(new Transaction(0, Transaction.EXPENSE, 75000, "Buku", "Pendidikan", day1, ""));
            seed.save(new Transaction(0, Transaction.INCOME, 100000, "Gaji", "Gaji", day1, ""));
            seed.save(new Transaction(0, Transaction.INCOME, 60000, "Usaha kecil", "Usaha", day1, ""));
            seed.save(new Transaction(0, Transaction.INCOME, 60000, "Usaha kecil", "Usaha", day2, ""));
        } finally {
            seed.close();
        }
        try (ActivityScenario<MainActivity> scenario = launchHome()) {
            onView(withText("Laporan")).perform(click());
            onView(withText("Sorotan bulanan")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Transaksi: Buku")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Transaksi: Gaji")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Sumber terbesar: Usaha kecil · Usaha · Rp120.000 (2 transaksi bulan ini)"))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("internet · Tagihan")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Total bulan ini: Rp40.000")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("2 kali bulan ini · tercatat dalam 2 dari 3 bulan yang diperiksa"))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Saran sederhana")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription("Bulan sebelumnya")).perform(scrollTo(), click());
            onView(withText("Transaksi: Internet")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Belum ada pengeluaran berulang yang terdeteksi pada periode ini."))
                    .perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test public void printActionConfirmsSelectedMonthAndCanBeCancelled() {
        Calendar selected = Calendar.getInstance();
        selected.add(Calendar.MONTH, -1);
        String period = Dates.monthLabel(selected.get(Calendar.YEAR), selected.get(Calendar.MONTH));
        try (ActivityScenario<MainActivity> scenario = launchHome()) {
            onView(withText("Laporan")).perform(click());
            onView(withContentDescription("Bulan sebelumnya")).perform(click());
            onView(withContentDescription("Cetak laporan bulanan " + period)).perform(scrollTo(), click());
            onView(withText("Cetak " + period + "?")).check(matches(isDisplayed()));
            onView(withText("Sertakan foto sebagai lampiran")).check(matches(isChecked())).perform(click())
                    .check(matches(isNotChecked()));
            onView(withText(containsString("Foto dan data diberikan kepada layanan cetak yang dipilih")))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Lanjutkan")).check(matches(isDisplayed()));
            onView(withText("Batal")).perform(click());
            onView(withText("Cetak laporan bulanan")).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }
}
