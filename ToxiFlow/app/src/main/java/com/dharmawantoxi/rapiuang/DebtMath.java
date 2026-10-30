package com.dharmawantoxi.rapiuang;

import java.math.BigInteger;
import java.util.List;

/** Pure validation and cash-flow calculations for debt and receivable records. */
final class DebtMath {
    private DebtMath() { }

    static void validateNew(String type, String person, long amount, String date, String note) {
        if (!DebtRecord.DEBT.equals(type) && !DebtRecord.RECEIVABLE.equals(type))
            throw new IllegalArgumentException("Pilih hutang atau piutang.");
        if (person == null || person.trim().isEmpty() || person.trim().length() > 80)
            throw new IllegalArgumentException("Nama wajib diisi dan maksimal 80 karakter.");
        if (amount <= 0 || amount > Money.MAX_TRANSACTION)
            throw new IllegalArgumentException("Nominal harus lebih besar dari Rp0 dan maksimal Rp999.999.999.999.");
        if (!Dates.isValid(date)) throw new IllegalArgumentException("Tanggal hutang/piutang tidak valid.");
        if (note == null || note.length() > 300)
            throw new IllegalArgumentException("Keterangan maksimal 300 karakter.");
    }

    static void validatePayment(long amount, long remaining, String date, String note) {
        if (amount <= 0 || amount > Money.MAX_TRANSACTION)
            throw new IllegalArgumentException("Nominal harus lebih besar dari Rp0 dan maksimal Rp999.999.999.999.");
        if (remaining <= 0 || amount > remaining)
            throw new IllegalArgumentException("Pembayaran melebihi sisa hutang/piutang.");
        if (!Dates.isValid(date)) throw new IllegalArgumentException("Tanggal pembayaran tidak valid.");
        if (note == null || note.length() > 300)
            throw new IllegalArgumentException("Keterangan maksimal 300 karakter.");
    }

    /** Debt received adds cash; lending on receivable reduces it. */
    static BigInteger openingCashEffect(String type, long amount) {
        BigInteger value = BigInteger.valueOf(amount);
        return DebtRecord.DEBT.equals(type) ? value : value.negate();
    }

    /** Repaying debt reduces cash; collecting a receivable adds it back. */
    static BigInteger paymentCashEffect(String type, long amount) {
        BigInteger value = BigInteger.valueOf(amount);
        return DebtRecord.DEBT.equals(type) ? value.negate() : value;
    }

    static BigInteger cashAdjustment(List<DebtRecord> records, List<DebtPayment> payments) {
        BigInteger result = BigInteger.ZERO;
        for (DebtRecord record : records)
            result = result.add(openingCashEffect(record.type, record.amount));
        for (DebtPayment payment : payments) {
            DebtRecord record = null;
            for (DebtRecord candidate : records) if (candidate.id == payment.recordId) { record = candidate; break; }
            if (record != null) result = result.add(paymentCashEffect(record.type, payment.amount));
        }
        return result;
    }
}
