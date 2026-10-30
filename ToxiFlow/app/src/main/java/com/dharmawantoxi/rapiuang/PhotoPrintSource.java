package com.dharmawantoxi.rapiuang;

import java.io.IOException;

/** One normalized image at a time; the caller must not assume all photos fit in memory. */
interface PhotoPrintSource {
    byte[] readPhoto(long transactionId) throws IOException;
}
