package com.dharmawantoxi.rapiuang;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
public final class PhotoProcessorTest {
    private Context context;

    @Before public void setUp() { context = ApplicationProvider.getApplicationContext(); }

    @Test public void normalizesOrientationAndStripsExifMetadata() throws Exception {
        File source = File.createTempFile("photo-source-", ".jpg", context.getCacheDir());
        File output = File.createTempFile("photo-output-", ".jpg", context.getCacheDir());
        String draft = null;
        Bitmap original = Bitmap.createBitmap(1800, 900, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(original);
            canvas.drawColor(Color.RED);
            Paint blue = new Paint(); blue.setColor(Color.BLUE);
            canvas.drawRect(900, 0, 1800, 900, blue);
            try (FileOutputStream stream = new FileOutputStream(source)) {
                assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 95, stream));
            }
            ExifInterface exif = new ExifInterface(source.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
            exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:10:30 12:00:00");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
            exif.saveAttributes();
            try (InputStream input = new FileInputStream(source)) { draft = PhotoProcessor.createDraft(context, input); }
            byte[] jpeg = PhotoProcessor.readDraft(context, draft);
            assertTrue(PhotoPolicy.validStoredJpeg(jpeg));
            assertTrue(jpeg.length <= PhotoPolicy.MAX_STORED_BYTES);
            Bitmap normalized = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            assertNotNull(normalized);
            try {
                assertEquals(800, normalized.getWidth());
                assertEquals(1600, normalized.getHeight());
                int top = normalized.getPixel(normalized.getWidth() / 2, 100);
                int bottom = normalized.getPixel(normalized.getWidth() / 2, normalized.getHeight() - 100);
                assertTrue(Color.red(top) > Color.blue(top));
                assertTrue(Color.blue(bottom) > Color.red(bottom));
            } finally { normalized.recycle(); }
            try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(jpeg); }
            ExifInterface cleaned = new ExifInterface(output.getAbsolutePath());
            assertNull(cleaned.getAttribute(ExifInterface.TAG_DATETIME));
            assertNull(cleaned.getAttribute(ExifInterface.TAG_GPS_LATITUDE));
            assertTrue(cleaned.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    == ExifInterface.ORIENTATION_NORMAL);
        } finally {
            original.recycle();
            PhotoFiles.delete(context, draft, "draft");
            source.delete(); output.delete();
        }
    }

    @Test public void allEightExifOrientationsKeepTheCorrectCorners() throws Exception {
        String[] expected = {"RGBY", "GRYB", "YBGR", "BYRG", "RBGY", "BRYG", "YGBR", "GYRB"};
        Bitmap image = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888);
        File source = File.createTempFile("photo-orient-", ".jpg", context.getCacheDir());
        try {
            Canvas canvas = new Canvas(image);
            Paint paint = new Paint();
            int[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
            for (int i = 0; i < 4; i++) {
                paint.setColor(colors[i]);
                int left = (i % 2) * 40, top = (i / 2) * 30;
                canvas.drawRect(left, top, left + 40, top + 30, paint);
            }
            for (int orientation = 1; orientation <= 8; orientation++) {
                try (FileOutputStream stream = new FileOutputStream(source)) {
                    assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 95, stream));
                }
                ExifInterface exif = new ExifInterface(source.getAbsolutePath());
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
                exif.saveAttributes();
                String draft = null;
                try {
                    try (InputStream input = new FileInputStream(source)) { draft = PhotoProcessor.createDraft(context, input); }
                    byte[] jpeg = PhotoProcessor.readDraft(context, draft);
                    Bitmap actual = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    assertNotNull(actual);
                    try {
                        assertEquals(orientation >= 5 ? 60 : 80, actual.getWidth());
                        assertEquals(orientation >= 5 ? 80 : 60, actual.getHeight());
                        int w = actual.getWidth(), h = actual.getHeight();
                        String corners = "" + colorName(actual.getPixel(w / 4, h / 4))
                                + colorName(actual.getPixel(3 * w / 4, h / 4))
                                + colorName(actual.getPixel(w / 4, 3 * h / 4))
                                + colorName(actual.getPixel(3 * w / 4, 3 * h / 4));
                        assertEquals(expected[orientation - 1], corners);
                    } finally { actual.recycle(); }
                } finally { PhotoFiles.delete(context, draft, "draft"); }
            }
        } finally { image.recycle(); source.delete(); }
    }

    private char colorName(int color) {
        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
        if (red > 150 && green > 150) return 'Y';
        if (red > green && red > blue) return 'R';
        return green > blue ? 'G' : 'B';
    }

    @Test public void providerThatReturnsZeroBytesCanStillMakeProgress() throws Exception {
        Bitmap image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        String draft = null;
        try {
            image.eraseColor(Color.BLUE);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, png));
            ByteArrayInputStream source = new ByteArrayInputStream(png.toByteArray());
            InputStream unusual = new InputStream() {
                @Override public int read() { return source.read(); }
                @Override public int read(byte[] b, int off, int len) { return 0; }
            };
            draft = PhotoProcessor.createDraft(context, unusual);
            assertTrue(PhotoPolicy.validStoredJpeg(PhotoProcessor.readDraft(context, draft)));
        } finally { image.recycle(); PhotoFiles.delete(context, draft, "draft"); }
    }

    @Test public void transparencyIsFlattenedAndNoisyImageFitsTheBound() throws Exception {
        Bitmap transparent = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888);
        String draft = null;
        try {
            transparent.eraseColor(Color.TRANSPARENT);
            transparent.setPixel(40, 30, Color.RED);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            assertTrue(transparent.compress(Bitmap.CompressFormat.PNG, 100, png));
            draft = PhotoProcessor.createDraft(context, new ByteArrayInputStream(png.toByteArray()));
            byte[] image = PhotoProcessor.readDraft(context, draft);
            Bitmap decoded = BitmapFactory.decodeByteArray(image, 0, image.length);
            assertNotNull(decoded);
            try {
                int background = decoded.getPixel(0, 0);
                assertTrue(Color.red(background) > 240 && Color.green(background) > 240 && Color.blue(background) > 240);
            } finally { decoded.recycle(); }
        } finally { transparent.recycle(); PhotoFiles.delete(context, draft, "draft"); }

        Bitmap noisy = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888);
        draft = null;
        try {
            int[] pixels = new int[1200 * 1200];
            Random random = new Random(29);
            for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | random.nextInt(1 << 24);
            noisy.setPixels(pixels, 0, 1200, 0, 0, 1200, 1200);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            assertTrue(noisy.compress(Bitmap.CompressFormat.PNG, 100, png));
            draft = PhotoProcessor.createDraft(context, new ByteArrayInputStream(png.toByteArray()));
            byte[] result = PhotoProcessor.readDraft(context, draft);
            assertTrue(PhotoPolicy.validStoredJpeg(result));
            assertTrue(result.length <= PhotoPolicy.MAX_STORED_BYTES);
            Bitmap preview = PhotoProcessor.preview(result, 600);
            assertNotNull(preview);
            try { assertTrue(Math.max(preview.getWidth(), preview.getHeight()) <= 600); }
            finally { preview.recycle(); }
        } finally { noisy.recycle(); PhotoFiles.delete(context, draft, "draft"); }
    }

    @Test public void oddDimensionsStillProduceABoundedPreview() throws Exception {
        Bitmap odd = Bitmap.createBitmap(1201, 100, Bitmap.Config.ARGB_8888);
        String draft = null;
        try {
            odd.eraseColor(Color.BLUE);
            ByteArrayOutputStream source = new ByteArrayOutputStream();
            assertTrue(odd.compress(Bitmap.CompressFormat.PNG, 100, source));
            draft = PhotoProcessor.createDraft(context, new ByteArrayInputStream(source.toByteArray()));
            Bitmap preview = PhotoProcessor.preview(PhotoProcessor.readDraft(context, draft), 600);
            assertNotNull(preview);
            try { assertTrue(preview.getWidth() <= 600 && preview.getHeight() <= 600); }
            finally { preview.recycle(); }
        } finally { odd.recycle(); PhotoFiles.delete(context, draft, "draft"); }
    }

    @Test public void invalidHugeAndEmptySourcesAreRejected() throws Exception {
        assertImportFails(new ByteArrayInputStream(new byte[0]));
        assertImportFails(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        InputStream tooLarge = new InputStream() {
            long left = PhotoPolicy.MAX_SOURCE_BYTES + 1;
            @Override public int read() { if (left-- <= 0) return -1; return 0; }
            @Override public int read(byte[] b, int offset, int length) {
                if (left <= 0) return -1;
                int n = (int) Math.min(left, length);
                Arrays.fill(b, offset, offset + n, (byte) 0);
                left -= n;
                return n;
            }
        };
        assertImportFails(tooLarge);
        Bitmap extremelyWide = Bitmap.createBitmap(PhotoPolicy.MAX_SOURCE_DIMENSION + 1, 1, Bitmap.Config.ARGB_8888);
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            assertTrue(extremelyWide.compress(Bitmap.CompressFormat.PNG, 100, png));
            assertImportFails(new ByteArrayInputStream(png.toByteArray()));
        } finally { extremelyWide.recycle(); }
        assertNull(PhotoProcessor.preview(new byte[]{1, 2, 3}, 600));
        try { PhotoProcessor.readDraft(context, "../../rapiuang.db"); fail("Invalid draft path accepted"); }
        catch (IOException expected) { }
    }

    private void assertImportFails(InputStream stream) throws Exception {
        try { String name = PhotoProcessor.createDraft(context, stream); PhotoFiles.delete(context, name, "draft");
            fail("Invalid source accepted");
        } catch (IOException expected) { }
    }

    @Test public void captureProviderOnlyGrantsExistingRandomCameraFiles() throws Exception {
        String name = PhotoFiles.createCapture(context);
        Uri uri = PhotoCaptureProvider.uri(context, name);
        try {
            ProviderInfo info = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
            assertNotNull(info);
            assertFalse(info.exported);
            assertTrue(info.grantUriPermissions);
            assertEquals("image/jpeg", context.getContentResolver().getType(uri));
            try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "wt");
                 ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                out.write(new byte[]{1, 2, 3});
            }
            assertEquals(3, PhotoFiles.file(context, name, "capture").length());
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                assertNotNull(cursor);
                assertTrue(cursor.moveToFirst());
                assertEquals("foto-transaksi.jpg", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
                assertEquals(3, cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
            }
            Uri traversal = new Uri.Builder().scheme("content").authority(uri.getAuthority())
                    .appendPath("..").appendPath("rapiuang.db").build();
            try { context.getContentResolver().openFileDescriptor(traversal, "r"); fail("Traversal accepted"); }
            catch (FileNotFoundException | SecurityException expected) { }
            Uri draft = new Uri.Builder().scheme("content").authority(uri.getAuthority())
                    .appendPath(PhotoFiles.newName("draft")).build();
            try { context.getContentResolver().openFileDescriptor(draft, "r"); fail("Draft exposed by capture provider"); }
            catch (FileNotFoundException | SecurityException expected) { }
        } finally { PhotoFiles.delete(context, name, "capture"); }
        try { context.getContentResolver().openFileDescriptor(uri, "r"); fail("Deleted capture exposed"); }
        catch (FileNotFoundException | SecurityException expected) { }
    }

    @Test public void appRequestsNoStorageCameraOrInternetPermission() throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        if (info.requestedPermissions == null) return;
        for (String permission : info.requestedPermissions) {
            assertNotEquals("android.permission.CAMERA", permission);
            assertNotEquals("android.permission.READ_EXTERNAL_STORAGE", permission);
            assertNotEquals("android.permission.READ_MEDIA_IMAGES", permission);
            assertNotEquals("android.permission.INTERNET", permission);
        }
    }
}
