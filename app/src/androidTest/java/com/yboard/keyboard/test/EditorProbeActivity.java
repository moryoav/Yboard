package com.yboard.keyboard.test;

import android.app.Activity;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A separate app's editor. No production app changes or personal text are needed. */
public class EditorProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 48, 24, 24);
        TextView title = new TextView(this);
        title.setText("Yboard input compatibility test");
        column.addView(title);
        EditText editor = new EditText(this);
        editor.setInputType(getIntent().getIntExtra("inputType", 1));
        editor.setImeOptions(getIntent().getIntExtra("imeOptions", 0));
        editor.setHint("Synthetic test text only");
        String initial = getIntent().getStringExtra("text");
        String encoded = getIntent().getStringExtra("text64");
        if (encoded != null) initial = new String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
        if (initial != null) { editor.setText(initial); editor.setSelection(initial.length()); }
        column.addView(editor, new LinearLayout.LayoutParams(-1, 240));
        setContentView(column);
        editor.requestFocus();
        editor.postDelayed(() -> getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT), 500);
    }
}
