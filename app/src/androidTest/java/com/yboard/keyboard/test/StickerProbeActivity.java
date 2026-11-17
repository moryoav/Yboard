package com.yboard.keyboard.test;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** A different app receives the real IME's content, including Android's temporary URI permission. */
public class StickerProbeActivity extends Activity {
    volatile int received;
    volatile String result = "Waiting for sticker";
    EditText editor;

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 48, 24, 24);
        TextView status = new TextView(this);
        status.setContentDescription("Sticker handoff status");
        boolean denied = false;
        try (InputStream ignored = getContentResolver().openInputStream(android.net.Uri.parse(
                "content://com.yboard.keyboard.stickers/test_sticker/coffee_test.webp"))) { }
        catch (SecurityException expected) { denied = true; }
        catch (Exception error) { result = "ERROR: " + error; }
        if (!denied) result = "ERROR: Provider was readable without a URI grant";
        status.setText(result);
        column.addView(status);
        TextView location = new TextView(this);
        location.setContentDescription("Sticker button location");
        location.setText("Waiting for keyboard");
        column.addView(location);
        if (android.os.Build.VERSION.SDK_INT >= 30) column.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            if (bottom > 0) {
                android.graphics.Rect screen = getWindowManager().getCurrentWindowMetrics().getBounds();
                float density = getResources().getDisplayMetrics().density;
                location.setText(Math.round(screen.right - 75 * density) + "," + Math.round(screen.bottom - bottom + 23 * density));
            } else location.setText("Waiting for keyboard");
            return insets;
        });
        ImageView image = new ImageView(this);
        image.setBackgroundColor(0xff282828);
        column.addView(image, new LinearLayout.LayoutParams(360, 360));
        String mode = getIntent().getStringExtra("mode");
        editor = new EditText(this) {
            @Override public InputConnection onCreateInputConnection(EditorInfo attrs) {
                InputConnection base = super.onCreateInputConnection(attrs);
                attrs.contentMimeTypes = "none".equals(mode) ? new String[0]
                    : new String[]{"png".equals(mode) ? "image/png" : "wildcard".equals(mode) ? "image/*" : "image/webp"};
                return new InputConnectionWrapper(base, false) {
                    @Override public boolean commitContent(InputContentInfo content, int flags, Bundle opts) {
                        received++;
                        if ("reject".equals(mode)) { result = "Rejected as requested"; status.setText(result); return false; }
                        try {
                            if ((flags & 1) == 0) throw new AssertionError("Missing read permission flag");
                            content.requestPermission();
                            if (!content.getDescription().hasMimeType("image/webp")) throw new AssertionError("Wrong MIME type");
                            if (!"image/webp".equals(getContentResolver().getType(content.getContentUri()))) throw new AssertionError("Provider MIME mismatch");
                            byte[] bytes;
                            try (InputStream input = getContentResolver().openInputStream(content.getContentUri());
                                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                                byte[] buffer = new byte[4096]; int n;
                                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                                bytes = output.toByteArray();
                            }
                            if (bytes.length > 100 * 1024 || bytes.length < 12
                                || !new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                                || !new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) throw new AssertionError("Invalid WebP file");
                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (bitmap == null || bitmap.getWidth() != 512 || bitmap.getHeight() != 512 || !bitmap.hasAlpha()
                                || Color.alpha(bitmap.getPixel(0, 0)) != 0 || Color.alpha(bitmap.getPixel(511, 511)) != 0)
                                throw new AssertionError("Wrong dimensions or transparency");
                            image.setImageBitmap(bitmap);
                            result = "Received #" + received + " transparent 512x512 WebP (" + bytes.length + " bytes) with read permission";
                            status.setText(result);
                            return true;
                        } catch (Throwable error) {
                            result = "ERROR: " + error;
                            status.setText(result);
                            return false;
                        } finally { content.releasePermission(); }
                    }
                };
            }
        };
        editor.setInputType(131073);
        editor.setContentDescription("Sticker draft");
        editor.setText("Keep this draft");
        editor.setSelection(editor.length());
        column.addView(editor, new LinearLayout.LayoutParams(-1, 180));
        setContentView(column);
        editor.requestFocus();
        editor.postDelayed(() -> getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT), 400);
    }
}
