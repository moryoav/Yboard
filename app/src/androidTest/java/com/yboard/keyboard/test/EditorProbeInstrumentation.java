package com.yboard.keyboard.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;

/** Runs in the editor app's process, leaving the keyboard's service lifecycle intact. */
public class EditorProbeInstrumentation extends Instrumentation {
    private Bundle args;
    private long down;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    private float arg(String key, String fallback) { return Float.parseFloat(args.getString(key, fallback)); }
    private void event(int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        if (!getUiAutomation().injectInputEvent(e, true)) throw new AssertionError("Touch injection failed");
        e.recycle();
    }
    private AccessibilityNodeInfo editable(AccessibilityNodeInfo node) {
        if (node == null || node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = editable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }
    private String text() {
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            AccessibilityNodeInfo node = editable(window.getRoot());
            if (node != null) return String.valueOf(node.getText());
        }
        AccessibilityNodeInfo node = editable(getUiAutomation().getRootInActiveWindow());
        return node == null ? "" : String.valueOf(node.getText());
    }
    private void tap(float x, float y) {
        down = SystemClock.uptimeMillis();
        event(MotionEvent.ACTION_DOWN, x, y); event(MotionEvent.ACTION_UP, x, y);
        SystemClock.sleep(250);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(info);
            // Shell starts the activity even when Android 16 restricts background app launches.
            String command = "am start -W -n " + getTargetContext().getPackageName()
                + "/com.yboard.keyboard.test.EditorProbeActivity -f 0x10008000 --ei inputType "
                + Integer.parseInt(args.getString("inputType", "131073")) + " --ei imeOptions "
                + Integer.parseInt(args.getString("imeOptions", "0"));
            try (android.os.ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
                 java.io.FileInputStream in = new java.io.FileInputStream(fd.getFileDescriptor())) {
                byte[] buffer = new byte[1024]; while (in.read(buffer) != -1) { }
            }
            SystemClock.sleep(2200);
            String word = args.getString("word", "automation");
            String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
            float[] offsets = {0.5f, 1f, 2f};
            ArrayList<float[]> points = new ArrayList<>();
            for (char c : word.toCharArray()) {
                for (int r = 0; r < rows.length; r++) {
                    int index = rows[r].indexOf(c);
                    if (index >= 0) points.add(new float[]{arg("left", "8") + (index + offsets[r]) * arg("keyWidth", "106.4"),
                        arg("top", "1696.5") + (r + 0.5f) * arg("rowHeight", "141.5")});
                }
            }
            down = SystemClock.uptimeMillis();
            event(MotionEvent.ACTION_DOWN, points.get(0)[0], points.get(0)[1]);
            for (int p = 1; p < points.size(); p++) {
                float[] a = points.get(p - 1), b = points.get(p);
                for (int i = 1; i <= 8; i++) {
                    float t = i / 8f;
                    event(MotionEvent.ACTION_MOVE, a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t);
                    SystemClock.sleep(8);
                }
            }
            float[] end = points.get(points.size() - 1);
            event(MotionEvent.ACTION_UP, end[0], end[1]);
            SystemClock.sleep(400);
            String typed = text();
            if (!typed.equalsIgnoreCase(word)) throw new AssertionError("Expected " + word + ", got " + typed);
            tap(arg("candidateX", "540"), arg("candidateY", "1616"));
            String accepted = text();
            if (!accepted.equalsIgnoreCase(word + " ")) throw new AssertionError("Word acceptance failed: " + accepted);
            tap(arg("candidateX", "540"), arg("candidateY", "1616"));
            String predicted = text();
            if (!predicted.startsWith(accepted) || predicted.length() <= accepted.length() || predicted.trim().equalsIgnoreCase(word + " " + word))
                throw new AssertionError("Next-word prediction failed: " + predicted);
            result.putString("result", "Glide and next-word acceptance passed: " + predicted);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("error", e.toString()); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
