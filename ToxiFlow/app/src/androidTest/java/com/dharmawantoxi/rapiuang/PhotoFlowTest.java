package com.dharmawantoxi.rapiuang;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
public final class PhotoFlowTest {
    private static final String TEST_DB = "rapiuang-ui-test.db";
    private static final int PICK_PHOTO = 31;
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(TEST_DB);
        FinanceStore.useDatabaseForUiTests(context, true);
    }

    @After public void tearDown() {
        FinanceStore.useDatabaseForUiTests(context, false);
        context.deleteDatabase(TEST_DB);
    }

    private byte[] jpeg() { return jpeg(Color.rgb(110, 160, 120)); }

    private byte[] jpeg(int color) {
        Bitmap image = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888);
        try {
            image.eraseColor(color);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 85, output));
            return output.toByteArray();
        } finally { image.recycle(); }
    }

    private Transaction onlyTransaction() {
        FinanceStore reader = new FinanceStore(context);
        try { assertEquals(1, reader.all().size()); return reader.all().get(0); }
        finally { reader.close(); }
    }

    private byte[] photo(long id) {
        FinanceStore reader = new FinanceStore(context);
        try { return reader.getPhoto(id); } finally { reader.close(); }
    }

    /** Espresso doesn't automatically wait for our image worker; inspect the form on the UI thread. */
    private void waitForForm(ActivityScenario<TransactionFormActivity> scenario, String text, boolean preview) {
        long until = SystemClock.uptimeMillis() + 15000;
        final boolean[] found = {false};
        while (SystemClock.uptimeMillis() < until && !found[0]) {
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                found[0] = hasText(root, text) && (!preview || hasPreview(root));
            });
            if (!found[0]) SystemClock.sleep(50);
        }
        assertTrue("Timed out waiting for form: " + text, found[0]);
    }

    private boolean hasText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    private boolean hasPreview(View view) {
        if (view instanceof ImageView && "Pratinjau foto transaksi".contentEquals(view.getContentDescription() == null
                ? "" : view.getContentDescription()))
            return view.getVisibility() == View.VISIBLE && ((ImageView) view).getDrawable() != null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasPreview(group.getChildAt(i))) return true;
        }
        return false;
    }

    @Test public void newIncomeCanAttachPhotoAndKeepDraftAcrossRecreation() throws Exception {
        String capture = PhotoFiles.createCapture(context);
        try {
            try (FileOutputStream output = new FileOutputStream(PhotoFiles.file(context, capture, "capture"))) {
                output.write(jpeg());
            }
            Uri uri = PhotoCaptureProvider.uri(context, capture);
            Intent form = new Intent(context, TransactionFormActivity.class).putExtra("default_date", Dates.today());
            try (ActivityScenario<TransactionFormActivity> scenario = ActivityScenario.launch(form)) {
                onView(withText("Pemasukan")).perform(click());
                onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(typeText("125000"), closeSoftKeyboard());
                onView(withHint("Contoh: Gaji bulanan")).perform(typeText("Bonus berfoto"), closeSoftKeyboard());
                // Deliver a local content URI just as the system picker does, without relying on another app.
                scenario.onActivity(activity -> activity.onActivityResult(PICK_PHOTO, Activity.RESULT_OK, new Intent().setData(uri)));
                waitForForm(scenario, "Foto baru siap disimpan.", true);
                scenario.recreate();
                waitForForm(scenario, "Foto baru siap disimpan.", true);
                onView(withText("Simpan transaksi")).perform(click());
            }
            Transaction saved = onlyTransaction();
            assertEquals(Transaction.INCOME, saved.type);
            assertEquals(125000, saved.amount);
            assertEquals("Bonus berfoto", saved.title);
            assertTrue(saved.hasPhoto);
            assertTrue(PhotoPolicy.validStoredJpeg(photo(saved.id)));
        } finally { PhotoFiles.delete(context, capture, "capture"); }
    }

    @Test public void expensePhotoCanBeViewedKeptOnEditAndRemoved() {
        byte[] original = jpeg();
        FinanceStore seed = new FinanceStore(context);
        long id;
        try {
            id = seed.save(new Transaction(0, Transaction.EXPENSE, 25000, "Struk kopi", "Makan & minum",
                    Dates.today(), "bukti"), FinanceStore.PhotoAction.REPLACE, original);
        } finally { seed.close(); }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Struk kopi")).perform(scrollTo(), click());
            onView(withText("Lihat foto")).perform(scrollTo(), click());
            onView(withContentDescription("Perbesar foto")).check(matches(isDisplayed())).perform(click());
            onView(withContentDescription("Tutup foto")).perform(click());
            onView(withText("Ubah")).perform(click());
            onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(replaceText("30000"), closeSoftKeyboard());
            onView(withText("Simpan transaksi")).perform(click());
            assertArrayEquals(original, photo(id));
            assertEquals(30000, onlyTransaction().amount);
            onView(withText("Struk kopi")).perform(scrollTo(), click());
            onView(withText("Ubah")).perform(click());
            onView(withText("Hapus foto")).perform(scrollTo(), click());
            onView(withText("Foto akan dihapus saat transaksi disimpan.")).perform(scrollTo()).check(matches(isDisplayed()));
            assertArrayEquals(original, photo(id)); // Removal is not committed before Save.
            onView(withText("Simpan transaksi")).perform(click());
            assertNull(photo(id));
            assertFalse(onlyTransaction().hasPhoto);
            assertEquals(30000, onlyTransaction().amount);
        }
    }

    @Test public void replacementCanBeRestoredOrSavedAndDiscardedRemovalDoesNotCommit() throws Exception {
        byte[] original = jpeg();
        FinanceStore seed = new FinanceStore(context);
        long id;
        try {
            id = seed.save(new Transaction(0, Transaction.INCOME, 70000, "Bukti bonus", "Gaji",
                    Dates.today(), ""), FinanceStore.PhotoAction.REPLACE, original);
        } finally { seed.close(); }
        String capture = PhotoFiles.createCapture(context);
        try {
            try (FileOutputStream output = new FileOutputStream(PhotoFiles.file(context, capture, "capture"))) {
                output.write(jpeg(Color.RED));
            }
            Uri uri = PhotoCaptureProvider.uri(context, capture);
            Intent form = new Intent(context, TransactionFormActivity.class).putExtra("transaction_id", id);
            try (ActivityScenario<TransactionFormActivity> scenario = ActivityScenario.launch(form)) {
                waitForForm(scenario, "Foto tersimpan pada transaksi.", true);
                scenario.onActivity(activity -> activity.onActivityResult(PICK_PHOTO, Activity.RESULT_OK, new Intent().setData(uri)));
                waitForForm(scenario, "Foto baru siap disimpan.", true);
                onView(withText("Gunakan foto semula")).perform(scrollTo(), click());
                waitForForm(scenario, "Foto tersimpan pada transaksi.", true);
                onView(withText("Simpan transaksi")).perform(click());
            }
            assertArrayEquals(original, photo(id));
            try (ActivityScenario<TransactionFormActivity> scenario = ActivityScenario.launch(form)) {
                waitForForm(scenario, "Foto tersimpan pada transaksi.", true);
                scenario.onActivity(activity -> activity.onActivityResult(PICK_PHOTO, Activity.RESULT_OK, new Intent().setData(uri)));
                waitForForm(scenario, "Foto baru siap disimpan.", true);
                onView(withText("Simpan transaksi")).perform(click());
            }
            byte[] replacement = photo(id);
            assertTrue(PhotoPolicy.validStoredJpeg(replacement));
            assertFalse(java.util.Arrays.equals(original, replacement));
            try (ActivityScenario<TransactionFormActivity> scenario = ActivityScenario.launch(form)) {
                waitForForm(scenario, "Foto tersimpan pada transaksi.", true);
                onView(withText("Hapus foto")).perform(scrollTo(), click());
                pressBack();
                onView(withText("Buang")).perform(click());
            }
            assertArrayEquals(replacement, photo(id));
            assertTrue(onlyTransaction().hasPhoto);
        } finally { PhotoFiles.delete(context, capture, "capture"); }
    }

    @Test public void failedReplacementDoesNotEraseAnExistingPhoto() throws Exception {
        byte[] original = jpeg();
        FinanceStore seed = new FinanceStore(context);
        long id;
        try {
            id = seed.save(new Transaction(0, Transaction.EXPENSE, 25000, "Struk asli", "Belanja",
                    Dates.today(), ""), FinanceStore.PhotoAction.REPLACE, original);
        } finally { seed.close(); }
        String empty = PhotoFiles.createCapture(context);
        try {
            Uri uri = PhotoCaptureProvider.uri(context, empty);
            Intent form = new Intent(context, TransactionFormActivity.class).putExtra("transaction_id", id);
            try (ActivityScenario<TransactionFormActivity> scenario = ActivityScenario.launch(form)) {
                waitForForm(scenario, "Foto tersimpan pada transaksi.", true);
                scenario.onActivity(activity -> activity.onActivityResult(PICK_PHOTO, Activity.RESULT_OK, new Intent().setData(uri)));
                waitForForm(scenario, "Foto tersimpan pada transaksi.", true);
                onView(withText("Foto belum bisa dipakai")).check(matches(isDisplayed()));
                onView(withText("Tutup")).perform(click());
                onView(withContentDescription("Nominal transaksi dalam rupiah")).perform(replaceText("26000"), closeSoftKeyboard());
                onView(withText("Simpan transaksi")).perform(click());
            }
            assertArrayEquals(original, photo(id));
            assertTrue(onlyTransaction().hasPhoto);
            assertEquals(26000, onlyTransaction().amount);
        } finally { PhotoFiles.delete(context, empty, "capture"); }
    }
}
