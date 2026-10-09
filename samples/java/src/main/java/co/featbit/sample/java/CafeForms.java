package co.featbit.sample.java;

import static co.featbit.sample.java.CafeModel.*;

import android.graphics.Typeface;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.core.widget.NestedScrollView;
import co.featbit.android.api.*;
import com.google.android.material.bottomsheet.*;
import com.google.android.material.button.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.*;
import java.util.*;

/** Drafts remain in the application session and never enter saved-instance-state bundles. */
final class CafeForms {
    final MainActivity activity;
    final CafeViews ui;
    final SampleSession session;
    private BottomSheetDialog sheet;
    private String kind;
    private MaterialButton userAction, formAction;
    private TextView progress;
    private Runnable validateEditor;
    private final List<MaterialButton> editorActions = new ArrayList<>();

    CafeForms(MainActivity activity) {
        this.activity = activity;
        ui = activity.ui;
        session = activity.session;
    }

    private String text(int id) {
        return activity.getString(id);
    }

    void destroy() {
        if (sheet != null) {
            sheet.setOnDismissListener(null);
            sheet.dismiss();
            sheet = null;
        }
    }

    void update(ScreenState s) {
        if (formAction != null) formAction.setEnabled(s.busy == null && !s.flushPending);
        if (userAction != null)
            userAction.setEnabled(s.available() && !s.flushPending && session.userChoice != s.user);
        if (progress != null)
            progress.setText(
                    s.busy != null
                            ? s.busy
                            : s.waitTimedOut
                                    ? text(R.string.identity_timeout)
                                    : text(
                                            s.local
                                                    ? R.string.no_targeting
                                                    : R.string.remote_targeting));
        for (MaterialButton b : editorActions) b.setEnabled(s.available());
        if (validateEditor != null) validateEditor.run();
        if (sheet != null
                && ((session.editorKey == null && "editor".equals(kind))
                        || (!session.userSheet && "users".equals(kind)))) sheet.dismiss();
        if (session.userSheet && sheet == null) showUsers();
        else if (session.editorKey != null && sheet == null) showEditor(session.editorKey);
    }

    private TextWatcher watcher(Consumer<String> changed) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                changed.accept(s.toString());
            }

            public void afterTextChanged(Editable e) {}
        };
    }

    private TextInputLayout field(
            LinearLayout parent,
            String hint,
            String value,
            boolean secret,
            boolean multiline,
            Consumer<String> changed) {
        TextInputLayout wrapper =
                new TextInputLayout(
                        activity, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        wrapper.setHint(hint);
        wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(wrapper.getContext());
        input.setText(value);
        input.setTextSize(14);
        input.setSaveEnabled(false);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (secret
                                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                                : multiline
                                        ? InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                        : InputType.TYPE_TEXT_VARIATION_URI));
        if (multiline) {
            input.setMinLines(5);
            input.setGravity(Gravity.TOP);
        } else input.setMaxLines(1);
        input.addTextChangedListener(
                watcher(
                        s -> {
                            changed.accept(s);
                            wrapper.setError(null);
                        }));
        wrapper.addView(input);
        if (secret) {
            wrapper.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
            wrapper.setEndIconVisible(true);
            input.setTypeface(Typeface.DEFAULT);
        }
        ui.add(parent, wrapper, 12);
        return wrapper;
    }

    private TextView help(int id) {
        return ui.label(text(id), 12, false, ui.color(R.color.cafe_muted));
    }

    void connection(LinearLayout parent) {
        ConnectionDraft d = session.draft;
        MaterialButtonToggleGroup mode = new MaterialButtonToggleGroup(activity);
        mode.setSingleSelection(true);
        mode.setSelectionRequired(true);
        for (boolean local : new boolean[] {true, false}) {
            MaterialButton b =
                    ui.button(
                            text(local ? R.string.local_demo : R.string.live_connection),
                            d.local != local,
                            () -> {
                                d.local = local;
                                activity.navigate();
                            });
            b.setId(View.generateViewId());
            b.setCheckable(true);
            b.setChecked(d.local == local);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            mode.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        ui.add(parent, mode, 8);
        Map<String, TextInputLayout> fields = new LinkedHashMap<>();
        if (d.local) ui.note(parent, text(R.string.local_explanation), false);
        else {
            fields.put(
                    "sdkKey",
                    field(parent, text(R.string.client_key), d.key, true, false, v -> d.key = v));
            ui.title(parent, text(R.string.data_mode));
            RadioGroup radios = new RadioGroup(activity);
            for (SyncMode transport : new SyncMode[] {SyncMode.STREAMING, SyncMode.POLLING}) {
                androidx.appcompat.widget.AppCompatRadioButton b =
                        new androidx.appcompat.widget.AppCompatRadioButton(activity);
                b.setText(
                        MainActivity.titleCase(transport)
                                + "\n"
                                + (transport == SyncMode.STREAMING
                                        ? "Real-time updates over WebSocket"
                                        : "Fetch flags at regular intervals"));
                b.setTextSize(13);
                b.setMinHeight(ui.dp(58));
                b.setChecked(d.mode == transport);
                b.setOnClickListener(
                        v -> {
                            d.mode = transport;
                            activity.navigate();
                        });
                radios.addView(b);
            }
            ui.add(parent, radios, 4);
            if (d.mode == SyncMode.STREAMING) {
                TextInputLayout f =
                        field(
                                parent,
                                text(R.string.streaming_url),
                                d.streaming,
                                false,
                                false,
                                v -> d.streaming = v);
                f.setPlaceholderText("wss://evaluation.example.com");
                fields.put("streamingUrl", f);
                MaterialSwitch toggle = new MaterialSwitch(activity);
                toggle.setText(text(R.string.polling_fallback));
                toggle.setMinHeight(ui.dp(56));
                toggle.setChecked(d.pollingFallback);
                toggle.setOnCheckedChangeListener(
                        (v, checked) -> {
                            d.pollingFallback = checked;
                            activity.navigate();
                        });
                ui.add(parent, toggle, 8);
                ui.add(
                        parent,
                        help(
                                d.pollingFallback
                                        ? R.string.polling_fallback_enabled_help
                                        : R.string.polling_fallback_help));
            }
            if (d.mode == SyncMode.POLLING || d.pollingFallback) {
                TextInputLayout f =
                        field(
                                parent,
                                text(R.string.polling_url),
                                d.polling,
                                false,
                                false,
                                v -> d.polling = v);
                f.setPlaceholderText("https://evaluation.example.com");
                fields.put("pollingUrl", f);
                if (d.mode == SyncMode.POLLING)
                    ui.add(parent, help(R.string.direct_polling_help), 4);
            }
            MaterialSwitch events = new MaterialSwitch(activity);
            events.setText(text(R.string.events));
            events.setMinHeight(ui.dp(56));
            events.setChecked(d.events);
            events.setOnCheckedChangeListener(
                    (v, checked) -> {
                        d.events = checked;
                        activity.navigate();
                    });
            ui.add(parent, events, 16);
            ui.add(parent, help(R.string.events_helper));
            if (d.events) {
                TextInputLayout f =
                        field(
                                parent,
                                text(R.string.events_url),
                                d.eventsUrl,
                                false,
                                false,
                                v -> d.eventsUrl = v);
                f.setPlaceholderText("https://events.example.com");
                fields.put("eventsUrl", f);
            }
            ui.add(parent, help(R.string.validate_before_close), 12);
        }
        formAction =
                ui.button(
                        text(R.string.apply_reconnect),
                        () -> {
                            Map<String, String> errors = session.validateDraft();
                            if (!errors.isEmpty()) {
                                for (Map.Entry<String, String> e : errors.entrySet())
                                    if (fields.containsKey(e.getKey()))
                                        fields.get(e.getKey()).setError(e.getValue());
                                    else
                                        Snackbar.make(
                                                        activity.host,
                                                        e.getValue(),
                                                        Snackbar.LENGTH_LONG)
                                                .show();
                            } else {
                                session.connect();
                                session.formOpen = false;
                                activity.navigate();
                            }
                        });
        ui.add(parent, formAction, 22);
        TextView memory = help(R.string.memory_only);
        memory.setGravity(Gravity.CENTER);
        ui.add(parent, memory, 10);
    }

    void showUsers() {
        if (sheet != null) return;
        session.userSheet = true;
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(24));
        ui.title(content, text(R.string.switch_user), 0);
        RadioGroup group = new RadioGroup(activity);
        for (int i = 0; i < session.people.size(); i++) {
            final int index = i;
            Person person = session.people.get(i);
            androidx.appcompat.widget.AppCompatRadioButton b =
                    new androidx.appcompat.widget.AppCompatRadioButton(activity);
            b.setText(person.name + "\n" + person.key + " · plan: " + person.plan);
            b.setMinHeight(ui.dp(68));
            b.setTextSize(14);
            b.setChecked(session.userChoice == i);
            b.setId(View.generateViewId());
            b.setOnClickListener(
                    v -> {
                        session.userChoice = index;
                        if (userAction != null)
                            userAction.setEnabled(
                                    session.state.available()
                                            && !session.state.flushPending
                                            && index != session.state.user);
                    });
            group.addView(b);
        }
        ui.add(content, group, 14);
        progress =
                ui.label(
                        session.state.busy != null
                                ? session.state.busy
                                : text(
                                        session.state.local
                                                ? R.string.no_targeting
                                                : R.string.remote_targeting),
                        13,
                        false,
                        ui.color(R.color.cafe_muted));
        ui.add(content, progress, 14);
        userAction =
                ui.button(text(R.string.switch_user), () -> session.identify(session.userChoice));
        userAction.setEnabled(
                session.state.available()
                        && !session.state.flushPending
                        && session.userChoice != session.state.user);
        ui.add(content, userAction, 18);
        if (!session.state.local)
            ui.add(
                    content,
                    ui.label(
                            "An admitted user change remains active if its readiness wait times out.",
                            12,
                            false,
                            ui.color(R.color.cafe_muted)),
                    12);
        openSheet(content, "users");
    }

    private void openSheet(View content, String type) {
        kind = type;
        sheet = new BottomSheetDialog(activity);
        LinearLayout container = ui.column();
        container.addView(new BottomSheetDragHandleView(activity));
        container.addView(content);
        sheet.setContentView(container);
        sheet.setOnDismissListener(
                v -> {
                    sheet = null;
                    kind = null;
                    userAction = null;
                    progress = null;
                    editorActions.clear();
                    validateEditor = null;
                    if (type.equals("users")) session.userSheet = false;
                    else session.editorKey = null;
                });
        sheet.show();
        FrameLayout background =
                sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (background != null) background.setBackgroundColor(ui.color(R.color.cafe_surface));
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    void showEditor(String key) {
        if (sheet != null) return;
        FlagSpec spec = session.spec(key);
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(24));
        ui.title(content, text(R.string.edit_local_flag), 0);
        ui.add(content, ui.label(key, 16, true), 16);
        ui.add(
                content,
                ui.label(
                        text(R.string.expected_type) + ": " + spec.type.name(),
                        12,
                        false,
                        ui.color(R.color.cafe_muted)),
                6);
        final TextInputLayout input;
        if (spec.type == ValueType.BOOLEAN) {
            input = null;
            MaterialSwitch toggle = new MaterialSwitch(activity);
            toggle.setText(session.editorText);
            toggle.setChecked(session.editorText.equals("true"));
            toggle.setMinHeight(ui.dp(56));
            toggle.setOnCheckedChangeListener(
                    (v, checked) -> {
                        session.editorText = Boolean.toString(checked);
                        toggle.setText(session.editorText);
                    });
            ui.add(content, toggle, 12);
        } else {
            input =
                    field(
                            content,
                            text(R.string.value),
                            session.editorText,
                            false,
                            spec.type == ValueType.JSON || spec.type == ValueType.STRING,
                            v -> session.editorText = v);
            if (spec.type == ValueType.JSON) input.getEditText().setTypeface(Typeface.MONOSPACE);
            if (spec.type == ValueType.NUMBER)
                input.getEditText()
                        .setInputType(
                                InputType.TYPE_CLASS_NUMBER
                                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        TextView warning = ui.label("", 12, false, ui.color(R.color.cafe_warn));
        ui.add(content, warning, 12);
        if (spec.type == ValueType.NUMBER)
            ui.add(
                    content,
                    ui.label(
                            "Finite values can still be saved.",
                            12,
                            false,
                            ui.color(R.color.cafe_muted)),
                    6);
        Consumer<Boolean> finished =
                ok -> {
                    if (ok) {
                        session.editorKey = null;
                        if (!activity.isDestroyed() && sheet != null) sheet.dismiss();
                    }
                };
        MaterialButton save =
                ui.button(
                        text(R.string.save),
                        () -> {
                            if (!session.validateValue(spec, session.editorText)) {
                                if (input != null) input.setError(text(R.string.invalid_value));
                                return;
                            }
                            session.edit(key, session.editorText, false, finished);
                        });
        validateEditor =
                () -> {
                    boolean valid = session.validateValue(spec, session.editorText);
                    save.setEnabled(valid && session.state.available());
                    String message = "";
                    if (!valid) message = text(R.string.invalid_value);
                    else if (spec.type == ValueType.NUMBER
                            && (Double.parseDouble(session.editorText) < 0
                                    || Double.parseDouble(session.editorText) > 100))
                        message = "Outside 0–100. Preview will use 0%.";
                    else if (spec.type == ValueType.JSON)
                        message =
                                text(
                                        Business.menu(session.editorText) == null
                                                ? R.string.menu_editor_warning
                                                : R.string.valid_menu);
                    else if (spec.type == ValueType.STRING)
                        message = text(R.string.whitespace_preserved);
                    warning.setText(message);
                    boolean
                            good =
                                    valid
                                            && spec.type == ValueType.JSON
                                            && Business.menu(session.editorText) != null,
                            neutral = spec.type == ValueType.STRING;
                    warning.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
                    warning.setTextColor(
                            ui.color(
                                    good
                                            ? R.color.cafe_good
                                            : neutral ? R.color.cafe_muted : R.color.cafe_warn));
                    android.graphics.drawable.Drawable glyph =
                            neutral
                                    ? null
                                    : androidx.core.content.ContextCompat.getDrawable(
                                                    activity,
                                                    good
                                                            ? R.drawable.ic_check
                                                            : R.drawable.ic_warning)
                                            .mutate();
                    if (glyph != null)
                        glyph.setTint(ui.color(good ? R.color.cafe_good : R.color.cafe_warn));
                    warning.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            glyph, null, null, null);
                    warning.setCompoundDrawablePadding(ui.dp(8));
                    warning.setBackground(
                            neutral
                                    ? null
                                    : ui.shape(
                                            ui.color(
                                                    good
                                                            ? R.color.cafe_good_bg
                                                            : R.color.cafe_warn_bg)));
                    warning.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
                };
        if (input != null)
            input.getEditText()
                    .addTextChangedListener(
                            watcher(
                                    v -> {
                                        if (validateEditor != null) validateEditor.run();
                                    }));
        validateEditor.run();
        ui.add(content, save, 18);
        MaterialButton remove =
                ui.button(
                        text(R.string.remove_flag),
                        true,
                        () ->
                                confirm(
                                        text(R.string.remove_flag),
                                        key,
                                        () -> session.edit(key, null, false, finished)));
        MaterialButton restore =
                ui.button(
                        text(R.string.restore_default),
                        true,
                        () -> session.edit(key, spec.initial, false, finished));
        remove.setStrokeWidth(0);
        remove.setTextColor(ui.color(R.color.cafe_error));
        restore.setStrokeWidth(0);
        LinearLayout secondary = ui.row();
        secondary.addView(remove, new LinearLayout.LayoutParams(0, -2, 1));
        secondary.addView(restore, new LinearLayout.LayoutParams(0, -2, 1));
        ui.add(content, secondary, 8);
        editorActions.addAll(Arrays.asList(save, remove, restore));
        ui.add(content, help(R.string.local_only), 12);
        NestedScrollView scroll = new NestedScrollView(activity);
        scroll.addView(content);
        openSheet(scroll, "editor");
    }

    void confirm(String title, String message, Runnable action) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (d, w) -> action.run())
                .show();
    }
}
