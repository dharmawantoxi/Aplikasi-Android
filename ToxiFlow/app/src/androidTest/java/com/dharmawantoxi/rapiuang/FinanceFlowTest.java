package com.dharmawantoxi.rapiuang;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FinanceFlowTest {
    private static final String TEST_DB = "rapiuang-ui-test.db";
    private Context context;

    @Before public void useIsolatedDatabase() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(TEST_DB);
        FinanceStore.useDatabaseForUiTests(context, true);
    }

    @After public void removeIsolatedDatabase() {
        FinanceStore.useDatabaseForUiTests(context, false);
        context.deleteDatabase(TEST_DB);
    }

    @Test public void recordIncomePersistsAndCanBeDeleted() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("ToxiFlow")).check(matches(isDisplayed()));
            onView(withText("Catat transaksi")).perform(scrollTo(), click());
            onView(withText("Pemasukan")).perform(click());
            onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(typeText("1000000"), closeSoftKeyboard());
            onView(withHint("Contoh: Gaji bulanan")).perform(typeText("Gaji bulanan"), closeSoftKeyboard());
            onView(withText("Simpan transaksi")).perform(click());
            onView(withText("Gaji bulanan")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("+ Rp1.000.000")).check(matches(isDisplayed()));
            scenario.recreate();
            onView(withText("Gaji bulanan")).perform(scrollTo()).check(matches(isDisplayed())).perform(click());
            onView(withText("Hapus")).perform(click());
            onView(withText("Hapus")).perform(click());
            onView(withText("Belum ada transaksi")).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test public void expenseCanBeEditedAndAppearsInReport() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Catat transaksi")).perform(scrollTo(), click());
            onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(typeText("25000"), closeSoftKeyboard());
            onView(withHint("Contoh: Makan siang")).perform(typeText("Makan malam"), closeSoftKeyboard());
            onView(withText("Simpan transaksi")).perform(click());
            onView(withText("Laporan")).perform(click());
            onView(withText("Makan & minum")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Transaksi")).perform(click());
            onView(withText("Makan malam")).perform(scrollTo(), click());
            onView(withText("Ubah")).perform(click());
            onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(replaceText("40000"), closeSoftKeyboard());
            onView(withText("Simpan transaksi")).perform(click());
            onView(withText("− Rp40.000")).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test public void invalidPastedNominalIsNotSilentlySaved() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
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
}
