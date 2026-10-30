package com.dharmawantoxi.rapiuang;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasErrorText;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class AmountInputFlowTest {
    private static final String DB = "rapiuang-ui-test.db";
    private static final String AMOUNT = "Nominal transaksi dalam rupiah";
    private Context context;
    private LanguageTestPreference testLanguage;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        testLanguage = new LanguageTestPreference(context, AppLanguage.INDONESIAN);
        context.deleteDatabase(DB);
        FinanceStore.useDatabaseForUiTests(context, true);
    }

    @After public void tearDown() {
        FinanceStore.useDatabaseForUiTests(context, false);
        context.deleteDatabase(DB);
        if (testLanguage != null) testLanguage.close();
    }

    private ActivityScenario<TransactionFormActivity> form(long id) {
        Intent intent = new Intent(context, TransactionFormActivity.class);
        if (id > 0) intent.putExtra("transaction_id", id);
        return ActivityScenario.launch(intent);
    }

    private EditText amount(View root) {
        if (root instanceof EditText && AMOUNT.equals(root.getContentDescription())) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText result = amount(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    @Test public void groupedExpenseAndIncomeSaveExactValues() {
        try (ActivityScenario<TransactionFormActivity> scenario = form(0)) {
            onView(withContentDescription(AMOUNT)).perform(typeText("1234567"), closeSoftKeyboard())
                    .check(matches(withText("1.234.567")));
            onView(withHint("Contoh: Makan siang")).perform(typeText("Belanja"), closeSoftKeyboard());
            onView(withText("Simpan transaksi")).perform(click());
        }
        try (ActivityScenario<TransactionFormActivity> scenario = form(0)) {
            onView(withText("Pemasukan")).perform(click());
            onView(withContentDescription(AMOUNT)).perform(replaceText("1.000.000"), closeSoftKeyboard())
                    .check(matches(withText("1.000.000")));
            onView(withHint("Contoh: Gaji bulanan")).perform(typeText("Gaji"), closeSoftKeyboard());
            onView(withText("Simpan transaksi")).perform(click());
        }
        FinanceStore store = new FinanceStore(context);
        try {
            List<Transaction> transactions = store.all();
            assertEquals(2, transactions.size());
            for (Transaction transaction : transactions) {
                if (Transaction.EXPENSE.equals(transaction.type)) assertEquals(1_234_567L, transaction.amount);
                else { assertEquals(Transaction.INCOME, transaction.type); assertEquals(1_000_000L, transaction.amount); }
            }
        } finally { store.close(); }
    }

    @Test public void cursorEditingAndSeparatorDeletionKeepExpectedDigits() {
        try (ActivityScenario<TransactionFormActivity> scenario = form(0)) {
            scenario.onActivity(activity -> {
                EditText input = amount(activity.getWindow().getDecorView());
                assertNotNull(input);
                input.requestFocus();
                input.setText("12345");
                assertEquals("12.345", input.getText().toString());
                input.setSelection(2);
                input.getText().insert(2, "9");
                assertEquals("129.345", input.getText().toString());
                assertEquals(3, input.getSelectionStart());

                input.setText("12345");
                input.setSelection(3); // Immediately after the dot.
                input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                assertEquals("1.345", input.getText().toString());
                assertEquals(1, input.getSelectionStart());

                input.setText("12345");
                input.setSelection(2); // Immediately before the dot.
                input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL));
                input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL));
                assertEquals("1.245", input.getText().toString());
                assertEquals(3, input.getSelectionStart());
            });
        }
    }

    @Test public void softKeyboardInputAndBackspaceUseTheSameGrouping() {
        try (ActivityScenario<TransactionFormActivity> scenario = form(0)) {
            scenario.onActivity(activity -> {
                EditText input = amount(activity.getWindow().getDecorView());
                assertNotNull(input);
                input.requestFocus();
                InputConnection keyboard = input.onCreateInputConnection(new EditorInfo());
                assertNotNull(keyboard);
                for (char digit : "12345".toCharArray()) assertTrue(keyboard.commitText(String.valueOf(digit), 1));
                assertEquals("12.345", input.getText().toString());
                assertEquals(6, input.getSelectionStart());
                input.setSelection(3);
                assertTrue(keyboard.deleteSurroundingText(1, 0));
                assertEquals("1.345", input.getText().toString());
                assertEquals(1, input.getSelectionStart());
                assertTrue(keyboard.commitText("9", 1));
                assertEquals("19.345", input.getText().toString());
                assertEquals(2, input.getSelectionStart());
            });
        }
    }

    @Test public void recreationRestoresFormattedAmountAndCursor() {
        try (ActivityScenario<TransactionFormActivity> scenario = form(0)) {
            scenario.onActivity(activity -> {
                EditText input = amount(activity.getWindow().getDecorView());
                assertNotNull(input);
                input.setText("1234567");
                input.setSelection(4);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                EditText input = amount(activity.getWindow().getDecorView());
                assertNotNull(input);
                assertEquals("1.234.567", input.getText().toString());
                assertEquals(4, input.getSelectionStart());
                assertEquals(4, input.getSelectionEnd());
                input.setSelection(2, 6);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                EditText input = amount(activity.getWindow().getDecorView());
                assertNotNull(input);
                assertEquals("1.234.567", input.getText().toString());
                assertEquals(2, input.getSelectionStart());
                assertEquals(6, input.getSelectionEnd());
            });
        }
    }

    @Test public void editingAnExistingAmountDoesNotCountFormattingAsAChange() {
        FinanceStore store = new FinanceStore(context);
        final long id;
        try {
            id = store.save(new Transaction(0, Transaction.EXPENSE, 50000, "Catatan lama", "Belanja", Dates.today(), ""));
        } finally { store.close(); }
        try (ActivityScenario<TransactionFormActivity> scenario = form(id)) {
            onView(withContentDescription(AMOUNT)).check(matches(withText("50.000")))
                    .perform(replaceText("50000"), closeSoftKeyboard())
                    .check(matches(withText("50.000")));
            final boolean[] finishing = {false};
            scenario.onActivity(activity -> {
                activity.onBackPressed();
                finishing[0] = activity.isFinishing();
            });
            assertTrue("Formatting-only edits should exit without a discard prompt", finishing[0]);
        }
        store = new FinanceStore(context);
        try { assertEquals(50000L, store.get(id).amount); } finally { store.close(); }
    }

    @Test public void malformedAndOversizedInputAreNotSilentlySaved() {
        try (ActivityScenario<TransactionFormActivity> scenario = form(0)) {
            onView(withHint("Contoh: Makan siang")).perform(typeText("Makan"), closeSoftKeyboard());
            for (String invalid : new String[]{"12.34", "12,50", "1000000000000"}) {
                String shown = invalid.equals("1000000000000") ? "1.000.000.000.000" : invalid;
                onView(withContentDescription(AMOUNT)).perform(scrollTo(), replaceText(invalid), closeSoftKeyboard())
                        .check(matches(withText(shown)));
                onView(withText("Simpan transaksi")).perform(click());
                onView(withContentDescription(AMOUNT))
                        .check(matches(hasErrorText("Masukkan rupiah utuh (maksimal 12 digit); gunakan titik hanya sebagai pemisah ribuan, tanpa koma atau desimal.")));
                FinanceStore store = new FinanceStore(context);
                try { assertTrue(store.all().isEmpty()); } finally { store.close(); }
            }
            onView(withContentDescription(AMOUNT)).check(matches(withText("1.000.000.000.000")));
        }
    }
}
