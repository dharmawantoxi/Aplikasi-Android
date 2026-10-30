package com.dharmawantoxi.rapiuang;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/** CSV is a human-readable export, not an importable database backup. */
public final class CsvExport {
    private CsvExport() { }

    // Quote every text field and neutralize formulas after leading whitespace for spreadsheet apps.
    static String cell(String text) {
        String value = text == null ? "" : text;
        String trimmed = value.trim();
        boolean startsControl = !value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0;
        if (startsControl || (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0)) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public static void write(Writer writer, List<Transaction> transactions) throws IOException {
        writer.write('\ufeff'); // UTF-8 BOM helps spreadsheet programs detect Indonesian text.
        writer.write("Tanggal,Jenis,Kategori,Nama,Nominal (Rp),Catatan\r\n");
        for (Transaction t : transactions) {
            writer.write(cell(t.date));
            writer.write(',');
            writer.write(cell(Transaction.INCOME.equals(t.type) ? "Pemasukan" : "Pengeluaran"));
            writer.write(',');
            writer.write(cell(t.category));
            writer.write(',');
            writer.write(cell(t.title));
            writer.write(',');
            writer.write(Long.toString(t.amount));
            writer.write(',');
            writer.write(cell(t.note));
            writer.write("\r\n");
        }
        writer.flush();
    }
}
