package com.dharmawantoxi.rapiuang;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasErrorText;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.Calendar;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public final class LanguageFlowTest {
    private static final String TEST_DB = "rapiuang-ui-test.db";
    private Context context;
    private LanguageTestPreference testLanguage;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        testLanguage = new LanguageTestPreference(context, AppLanguage.INDONESIAN);
        context.deleteDatabase(TEST_DB);
        FinanceStore.useDatabaseForUiTests(context, true);
    }

    @After public void tearDown() {
        FinanceStore.useDatabaseForUiTests(context, false);
        context.deleteDatabase(TEST_DB);
        if (testLanguage != null) testLanguage.close();
    }

    private ActivityScenario<MainActivity> launchHome() {
        return ActivityScenario.launch(new Intent(context, MainActivity.class));
    }

    private byte[] jpeg() {
        Bitmap image = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888);
        try {
            image.eraseColor(Color.GREEN);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 85, out));
            return out.toByteArray();
        } finally { image.recycle(); }
    }

    @Test public void languageSwitchPersistsPagePeriodFilterAndRecordsWithoutChangingPhotos() {
        Calendar previous = Calendar.getInstance();
        previous.add(Calendar.MONTH, -1);
        int year = previous.get(Calendar.YEAR), month = previous.get(Calendar.MONTH);
        String date = Dates.iso(year, month, 5);
        byte[] photo = jpeg();
        FinanceStore seed = new FinanceStore(context);
        final long id;
        final String createdAt;
        try {
            id = seed.save(new Transaction(0, Transaction.EXPENSE, 25000, "Belanja buku", "Belanja", date,
                    "Catatan pribadi"), FinanceStore.PhotoAction.REPLACE, photo);
            createdAt = seed.get(id).createdAt;
        } finally { seed.close(); }

        try (ActivityScenario<MainActivity> scenario = launchHome()) {
            onView(withText("Transaksi")).perform(click());
            onView(withContentDescription("Bulan sebelumnya")).perform(click());
            onView(withText("Pengeluaran")).perform(click());
            onView(withContentDescription("Cari transaksi")).perform(replaceText("Belanja"), closeSoftKeyboard());
            onView(withText("Belanja buku")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription("Ganti bahasa. Saat ini Bahasa Indonesia")).perform(scrollTo(), click());
            onView(withText("English")).perform(click());
            assertEquals(AppLanguage.ENGLISH, LanguageSettings.get(context));
            onView(withContentDescription("Filter Expense, selected")).check(matches(isDisplayed()));
            onView(withHint("Search names, categories, or notes")).check(matches(withText("Belanja")));
            onView(withContentDescription("Choose period, currently " + Dates.monthLabel(year, month, AppLanguage.ENGLISH)))
                    .check(matches(isDisplayed()));
            onView(withContentDescription("Search transactions")).perform(replaceText("shopping"), closeSoftKeyboard());
            onView(withText("Belanja buku")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Shopping · Photo")).perform(scrollTo()).check(matches(isDisplayed()));
            scenario.recreate();
            onView(withContentDescription("Filter Expense, selected")).check(matches(isDisplayed()));
            onView(withHint("Search names, categories, or notes")).check(matches(withText("shopping")));
            onView(withContentDescription("Choose period, currently " + Dates.monthLabel(year, month, AppLanguage.ENGLISH)))
                    .check(matches(isDisplayed()));
        }
        try (ActivityScenario<MainActivity> scenario = launchHome()) {
            onView(withText("Home")).check(matches(isDisplayed()));
            onView(withContentDescription("Change language. Currently English")).perform(click());
            onView(withText("Bahasa Indonesia")).perform(click());
            onView(withText("Beranda")).check(matches(isDisplayed()));
            assertEquals(AppLanguage.INDONESIAN, LanguageSettings.get(context));
        }
        FinanceStore reader = new FinanceStore(context);
        try {
            Transaction saved = reader.get(id);
            assertNotNull(saved);
            assertEquals(Transaction.EXPENSE, saved.type);
            assertEquals(25000L, saved.amount);
            assertEquals("Belanja buku", saved.title);
            assertEquals("Belanja", saved.category);
            assertEquals(date, saved.date);
            assertEquals("Catatan pribadi", saved.note);
            assertEquals(createdAt, saved.createdAt);
            assertTrue(saved.hasPhoto);
            assertArrayEquals(photo, reader.getPhoto(id));
        } finally { reader.close(); }
    }

    @Test public void englishFormDisplaysTranslatedCategoriesButSavesCanonicalValues() {
        assertTrue(LanguageSettings.save(context, AppLanguage.ENGLISH));
        try (ActivityScenario<TransactionFormActivity> scenario = ActivityScenario.launch(
                new Intent(context, TransactionFormActivity.class))) {
            onView(withContentDescription("Transaction amount in rupiah")).perform(replaceText("12.500"), closeSoftKeyboard());
            onView(withHint("Example: Lunch")).perform(replaceText("Nasi uduk"), closeSoftKeyboard());
            onView(withText("Save transaction")).perform(click());
            onView(withContentDescription("Transaction amount in rupiah"))
                    .check(matches(hasErrorText("Enter a whole rupiah amount without dots or commas (maximum 12 digits).")));
            onView(withContentDescription("Transaction amount in rupiah")).perform(scrollTo(), replaceText("25000"), closeSoftKeyboard());
            onView(withContentDescription("Transaction category")).perform(scrollTo(), click());
            onView(withText("Shopping")).perform(click());
            onView(withContentDescription("Additional note, optional")).perform(scrollTo(), replaceText("Tidak diubah"), closeSoftKeyboard());
            onView(withText("Save transaction")).perform(click());
        }
        FinanceStore reader = new FinanceStore(context);
        final Transaction saved;
        try {
            assertEquals(1, reader.all().size());
            saved = reader.all().get(0);
            assertEquals("Belanja", saved.category);
            assertEquals("Nasi uduk", saved.title);
            assertEquals("Tidak diubah", saved.note);
            assertEquals(25000L, saved.amount);
        } finally { reader.close(); }
        assertTrue(LanguageSettings.save(context, AppLanguage.INDONESIAN));
        Intent edit = new Intent(context, TransactionFormActivity.class).putExtra("transaction_id", saved.id);
        try (ActivityScenario<TransactionFormActivity> scenario = ActivityScenario.launch(edit)) {
            onView(withText("Belanja")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Nasi uduk")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription("Catatan tambahan, opsional"))
                    .perform(scrollTo()).check(matches(withText("Tidak diubah")));
        }
    }

    @Test public void defaultAndLocalizedContextDoNotChangeDeviceConfiguration() {
        assertTrue(context.getSharedPreferences(LanguageSettings.PREFS, Context.MODE_PRIVATE).edit()
                .remove(LanguageSettings.KEY).commit());
        assertEquals(AppLanguage.INDONESIAN, LanguageSettings.get(context));
        Configuration simulatedSystem = new Configuration(context.getResources().getConfiguration());
        simulatedSystem.setLocale(Locale.FRENCH);
        Context base = context.createConfigurationContext(simulatedSystem);
        assertTrue(LanguageSettings.save(context, AppLanguage.ENGLISH));
        Context english = LanguageSettings.wrap(base);
        assertEquals("en", english.getResources().getConfiguration().getLocales().get(0).getLanguage());
        assertEquals("en", Locale.getDefault().getLanguage());
        assertEquals("fr", base.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    @Test public void splashUsesPersistedLanguage() {
        assertTrue(LanguageSettings.save(context, AppLanguage.ENGLISH));
        try (ActivityScenario<SplashActivity> scenario = ActivityScenario.launch(SplashActivity.class)) {
            scenario.onActivity(activity -> {
                View decor = activity.getWindow().getDecorView();
                TextView loading = decor.findViewWithTag("splash_loading");
                ProgressBar progress = decor.findViewWithTag("splash_progress");
                assertNotNull(loading);
                assertNotNull(progress);
                assertEquals("Loading app...", loading.getText().toString());
                assertEquals("Opening animation", progress.getContentDescription().toString());
                assertEquals("en", activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
                assertEquals("en", Locale.getDefault().getLanguage());
            });
        }
    }
}
