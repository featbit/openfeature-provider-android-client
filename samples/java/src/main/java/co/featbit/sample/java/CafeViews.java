package co.featbit.sample.java;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import java.io.*;

/** Native view helpers matching the shared design; contains no SDK calls. */
final class CafeViews {
    final Context context;
    private static Bitmap photo;

    CafeViews(Context context) {
        this.context = context;
    }

    int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density);
    }

    int color(int id) {
        return ContextCompat.getColor(context, id);
    }

    GradientDrawable shape(int fill) {
        return shape(fill, 10, false);
    }

    GradientDrawable shape(int fill, int radius) {
        return shape(fill, radius, false);
    }

    GradientDrawable shape(int fill, int radius, boolean stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (stroke) d.setStroke(dp(1), color(R.color.cafe_outline));
        return d;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    TextView label(String text) {
        return label(text, 14, false);
    }

    TextView label(String text, float size) {
        return label(text, size, false);
    }

    TextView label(String text, float size, boolean bold) {
        return label(text, size, bold, color(R.color.cafe_text));
    }

    TextView label(String text, float size, boolean bold, int ink) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(ink);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLineSpacing(dp(2), 1);
        return t;
    }

    void add(LinearLayout p, View c) {
        add(p, c, 0);
    }

    void add(LinearLayout p, View c, int top) {
        add(p, c, top, -2);
    }

    void add(LinearLayout p, View c, int top, int height) {
        LinearLayout.LayoutParams l =
                new LinearLayout.LayoutParams(-1, height >= 0 ? dp(height) : height);
        l.topMargin = dp(top);
        p.addView(c, l);
    }

    void line(LinearLayout p) {
        line(p, 16);
    }

    void line(LinearLayout p, int top) {
        View v = new View(context);
        v.setBackgroundColor(color(R.color.cafe_outline));
        add(p, v, top, 1);
    }

    void title(LinearLayout p, String t) {
        title(p, t, 14);
    }

    void title(LinearLayout p, String t, int top) {
        add(p, label(t, 16, true), top);
    }

    MaterialButton button(String text, Runnable action) {
        return button(text, false, action);
    }

    MaterialButton button(String text, boolean outline, Runnable action) {
        MaterialButton b =
                new MaterialButton(
                        context,
                        null,
                        outline
                                ? com.google.android.material.R.attr.materialButtonOutlinedStyle
                                : com.google.android.material.R.attr.materialButtonStyle);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setCornerRadius(dp(9));
        b.setMinHeight(dp(48));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    ImageButton icon(int drawable, String description, Runnable action) {
        androidx.appcompat.widget.AppCompatImageButton b =
                new androidx.appcompat.widget.AppCompatImageButton(context);
        b.setImageResource(drawable);
        b.setImageTintList(ColorStateList.valueOf(color(R.color.cafe_primary)));
        b.setContentDescription(description);
        b.setBackgroundResource(android.R.color.transparent);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setMinimumWidth(dp(48));
        b.setMinimumHeight(dp(48));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    void note(LinearLayout p, String text) {
        note(p, text, true);
    }

    void note(LinearLayout p, String text, boolean warning) {
        LinearLayout r = row();
        r.setBackground(shape(color(warning ? R.color.cafe_warn_bg : R.color.cafe_panel)));
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (warning) {
            ImageView i = new ImageView(context);
            i.setImageResource(R.drawable.ic_warning);
            i.setImageTintList(ColorStateList.valueOf(color(R.color.cafe_warn)));
            i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
            lp.setMarginEnd(dp(10));
            r.addView(i, lp);
        }
        r.addView(
                label(text, 13, false, color(warning ? R.color.cafe_warn : R.color.cafe_muted)),
                new LinearLayout.LayoutParams(0, -2, 1));
        add(p, r, 12);
    }

    void keyValue(LinearLayout p, String key, String value) {
        keyValue(p, key, value, false, 0, false);
    }

    void keyValue(LinearLayout p, String key, String value, boolean strong) {
        keyValue(p, key, value, strong, 0, false);
    }

    void keyValue(
            LinearLayout p, String key, String value, boolean strong, int glyph, boolean success) {
        LinearLayout r = row();
        if (glyph != 0) {
            ImageView i = new ImageView(context);
            i.setImageResource(glyph);
            i.setImageTintList(ColorStateList.valueOf(color(R.color.cafe_text)));
            i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
            lp.setMarginEnd(dp(16));
            r.addView(i, lp);
        }
        r.addView(
                label(
                        key,
                        strong ? 16 : 13,
                        strong,
                        color(strong ? R.color.cafe_text : R.color.cafe_muted)),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView t =
                label(
                        value,
                        strong ? 22 : 13,
                        strong,
                        color(success ? R.color.cafe_good : R.color.cafe_text));
        t.setGravity(Gravity.START);
        t.setTextIsSelectable(true);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        add(p, r, 8);
    }

    @SuppressWarnings("deprecation")
    Bitmap coffee() {
        if (photo == null)
            try (InputStream stream = context.getAssets().open("cafe-reference.png")) {
                BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(stream, false);
                photo =
                        decoder.decodeRegion(
                                new Rect(83, 411, 480, 598), new BitmapFactory.Options());
                decoder.recycle();
            } catch (IOException ex) {
                throw new IllegalStateException("Missing shared coffee image", ex);
            }
        return photo;
    }
}
