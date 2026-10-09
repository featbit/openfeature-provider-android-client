package co.featbit.sample.java;

import static co.featbit.sample.java.CafeModel.*;

import android.content.res.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import co.featbit.android.api.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.android.material.snackbar.Snackbar;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends AppCompatActivity {
    SampleSession session;
    CafeViews ui;
    CafeForms forms;
    FrameLayout host;
    private MaterialToolbar toolbar;
    private BottomNavigationView nav;
    private NavigationRailView rail;
    private NestedScrollView scroll;
    private String rendered = "", lastMessage;
    private boolean updatingNavigation;
    private Snackbar feedback;
    private Registration observation;

    private boolean wide() {
        return getResources().getConfiguration().screenWidthDp >= 600;
    }

    private boolean bigText() {
        return getResources().getConfiguration().fontScale > 1.15f;
    }

    private int contentWidth() {
        return wide()
                ? Math.min(620, getResources().getConfiguration().screenWidthDp - 80)
                : getResources().getConfiguration().screenWidthDp;
    }

    private String text(int id) {
        return getString(id);
    }

    static String titleCase(Object value) {
        if (value == null) return "—";
        String s = value.toString().toLowerCase(Locale.US);
        return s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private LinearLayout.LayoutParams iconSize() {
        return new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
    }

    private TextView muted(String t, int size) {
        return ui.label(t, size, false, ui.color(R.color.cafe_muted));
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        session = ((CafeApplication) getApplication()).session;
        ui = new CafeViews(this);
        forms = new CafeForms(this);
        setContentView(R.layout.activity_main);
        androidx.core.view.WindowInsetsControllerCompat insets =
                androidx.core.view.WindowCompat.getInsetsController(
                        getWindow(), getWindow().getDecorView());
        boolean light =
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        != Configuration.UI_MODE_NIGHT_YES;
        insets.setAppearanceLightStatusBars(light);
        insets.setAppearanceLightNavigationBars(light);
        toolbar = findViewById(R.id.toolbar);
        host = findViewById(R.id.page_host);
        nav = findViewById(R.id.navigation);
        rail = findViewById(R.id.rail);
        ColorStateList ink =
                new ColorStateList(
                        new int[][] {{android.R.attr.state_checked}, {}},
                        new int[] {ui.color(R.color.cafe_primary), ui.color(R.color.cafe_muted)});
        nav.setItemActiveIndicatorColor(ColorStateList.valueOf(ui.color(R.color.cafe_tonal)));
        rail.setItemActiveIndicatorColor(ColorStateList.valueOf(ui.color(R.color.cafe_tonal)));
        nav.setItemIconTintList(ink);
        nav.setItemTextColor(ink);
        rail.setItemIconTintList(ink);
        rail.setItemTextColor(ink);
        findViewById(R.id.root)
                .getViewTreeObserver()
                .addOnDrawListener(
                        new ViewTreeObserver.OnDrawListener() {
                            boolean scheduled;

                            public void onDraw() {
                                if (scheduled) return;
                                scheduled = true;
                                host.post(
                                        () -> {
                                            host.getRootView()
                                                    .getViewTreeObserver()
                                                    .removeOnDrawListener(this);
                                            session.start();
                                        });
                            }
                        });
        NavigationBarView.OnItemSelectedListener listener =
                item -> {
                    if (!updatingNavigation) {
                        session.destination =
                                item.getItemId() == R.id.nav_flags
                                        ? "Flags"
                                        : item.getItemId() == R.id.nav_inspect ? "Inspect" : "Demo";
                        session.detailKey = null;
                        session.formOpen = false;
                        navigate();
                    }
                    return true;
                };
        nav.setOnItemSelectedListener(listener);
        rail.setOnItemSelectedListener(listener);
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new OnBackPressedCallback(true) {
                            public void handleOnBackPressed() {
                                if (session.formOpen) {
                                    session.formOpen = false;
                                    navigate();
                                } else if (session.detailKey != null) {
                                    session.detailKey = null;
                                    navigate();
                                } else if (!session.destination.equals("Demo")) {
                                    session.destination = "Demo";
                                    navigate();
                                } else {
                                    setEnabled(false);
                                    getOnBackPressedDispatcher().onBackPressed();
                                    setEnabled(true);
                                }
                            }
                        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        session.invalidateReads();
        observation = session.observe(() -> render(session.state));
    }

    @Override
    protected void onStop() {
        session.scrollY = scroll == null ? 0 : scroll.getScrollY();
        if (observation != null) {
            observation.close();
            observation = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        forms.destroy();
        super.onDestroy();
    }

    void navigate() {
        rendered = "";
        session.scrollY = 0;
        render(session.state);
    }

    void openConnection() {
        session.formOpen = true;
        navigate();
    }

    void openFlag(String key) {
        session.detailKey = key;
        navigate();
    }

    void render(ScreenState s) {
        String screen =
                session.formOpen
                        ? "Connection"
                        : session.detailKey != null
                                ? "Detail:" + session.detailKey
                                : session.destination;
        boolean sub = session.formOpen || session.detailKey != null;
        toolbar.setTitle(
                session.formOpen
                        ? text(R.string.connection)
                        : session.detailKey != null
                                ? text(R.string.flag_details)
                                : screen.equals("Demo") ? text(R.string.app_name) : screen);
        toolbar.setNavigationIcon(
                sub || screen.equals("Demo")
                        ? ContextCompat.getDrawable(
                                this, sub ? R.drawable.ic_back : R.drawable.ic_coffee)
                        : null);
        toolbar.setNavigationOnClickListener(
                v -> {
                    if (sub) getOnBackPressedDispatcher().onBackPressed();
                });
        toolbar.getMenu().clear();
        if (!sub && !screen.equals("Flags")) {
            MenuItem item =
                    toolbar.getMenu()
                            .add(text(R.string.connection))
                            .setIcon(R.drawable.ic_settings);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            item.setOnMenuItemClickListener(
                    v -> {
                        openConnection();
                        return true;
                    });
        }
        if (session.detailKey != null && s.local) {
            TextView badge =
                    ui.label(text(R.string.local_demo), 12, false, ui.color(R.color.cafe_primary));
            badge.setBackground(ui.shape(ui.color(R.color.cafe_tonal), 16));
            badge.setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5));
            toolbar.getMenu()
                    .add("")
                    .setActionView(badge)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
        LinearLayout actions = findViewById(R.id.classic_actions);
        actions.removeAllViews();
        actions.setVisibility(
                screen.equals("Demo") && !s.business.compact ? View.VISIBLE : View.GONE);
        if (actions.getVisibility() == View.VISIBLE) {
            actions.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(6));
            MaterialButton order = ui.button(text(R.string.place_order), session::order);
            order.setEnabled(s.available());
            ui.add(actions, order);
            TextView helper = muted(text(R.string.simulated_order), 12);
            helper.setGravity(Gravity.CENTER);
            ui.add(actions, helper, 4);
        }
        nav.setVisibility(!wide() && !sub ? View.VISIBLE : View.GONE);
        rail.setVisibility(wide() && !sub ? View.VISIBLE : View.GONE);
        updatingNavigation = true;
        int id =
                session.destination.equals("Flags")
                        ? R.id.nav_flags
                        : session.destination.equals("Inspect") ? R.id.nav_inspect : R.id.nav_demo;
        nav.setSelectedItemId(id);
        rail.setSelectedItemId(id);
        updatingNavigation = false;
        if (!screen.equals("Connection") || !rendered.equals(screen)) {
            int oldScroll =
                    rendered.equals(screen)
                            ? (scroll == null ? session.scrollY : scroll.getScrollY())
                            : 0;
            host.removeAllViews();
            scroll =
                    (NestedScrollView)
                            getLayoutInflater().inflate(R.layout.page_scroll, host, false);
            host.addView(
                    scroll,
                    new FrameLayout.LayoutParams(
                            wide() ? ui.dp(contentWidth()) : -1, -1, Gravity.CENTER_HORIZONTAL));
            NestedScrollView current = scroll;
            LinearLayout page = current.findViewById(R.id.page_content);
            page.setFocusableInTouchMode(true);
            page.requestFocus();
            if (session.formOpen) forms.connection(page);
            else if (session.detailKey != null) details(page, session.detailKey, s);
            else if (screen.equals("Flags")) flags(page, s);
            else if (screen.equals("Inspect")) inspect(page, s);
            else demo(page, s);
            current.post(
                    () -> {
                        if (scroll == current) current.scrollTo(0, oldScroll);
                    });
            rendered = screen;
        }
        forms.update(s);
        if (s.message != null && !s.message.equals(lastMessage)) {
            lastMessage = s.message;
            if (feedback != null) feedback.dismiss();
            feedback = Snackbar.make(host, s.message, Snackbar.LENGTH_LONG);
            if (nav.getVisibility() == View.VISIBLE) feedback.setAnchorView(nav);
            feedback.setActionTextColor(ui.color(R.color.cafe_on_primary));
            feedback.setAction(R.string.dismiss, v -> session.dismissMessage());
            feedback.show();
        }
        if (s.message == null) {
            lastMessage = null;
            if (feedback != null) feedback.dismiss();
            feedback = null;
        }
    }

    private void status(LinearLayout parent, ScreenState s) {
        ConnectionInformation info = s.status;
        boolean offline = info != null && info.getPauseReasons().contains(PauseReason.OFFLINE),
                paused = info != null && !info.getPauseReasons().isEmpty(),
                confirmed = info != null && info.getRemoteConfirmed(),
                terminal = info != null && info.getStatus() == SyncStatus.TERMINAL;
        boolean good = s.active && s.busy == null && !paused && (s.local || confirmed) && !terminal;
        String heading =
                s.busy != null
                        ? s.busy
                        : !s.active
                                ? text(R.string.not_connected)
                                : offline
                                        ? text(R.string.offline_values)
                                        : paused
                                                ? text(R.string.paused)
                                                : terminal
                                                        ? text(R.string.sync_stopped)
                                                        : s.local
                                                                ? text(R.string.local_demo)
                                                                : confirmed
                                                                        ? "Live · "
                                                                                + titleCase(
                                                                                        info
                                                                                                .getEffectiveMode())
                                                                        : text(
                                                                                R.string
                                                                                        .live_waiting);
        String detail =
                !s.active
                        ? text(R.string.remote_unconfirmed)
                        : s.local
                                ? text(R.string.local_status)
                                : paused
                                        ? joined(info.getPauseReasons())
                                        : confirmed
                                                ? text(R.string.remote_confirmed)
                                                : text(R.string.remote_unconfirmed);
        LinearLayout container = ui.row();
        container.setBackground(
                ui.shape(ui.color(good ? R.color.cafe_good_bg : R.color.cafe_warn_bg)));
        container.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9));
        View dot = new View(this);
        dot.setBackground(ui.shape(ui.color(good ? R.color.cafe_good : R.color.cafe_warn), 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(12), ui.dp(12));
        lp.setMarginEnd(ui.dp(12));
        container.addView(dot, lp);
        LinearLayout texts = ui.column();
        ui.add(
                texts,
                ui.label(
                        heading, 14, true, ui.color(good ? R.color.cafe_good : R.color.cafe_warn)));
        ui.add(texts, muted(detail, 12));
        container.addView(texts, weight());
        container.setContentDescription(heading + ". " + detail);
        container.setOnClickListener(
                v -> {
                    session.destination = "Inspect";
                    session.detailKey = null;
                    navigate();
                });
        ui.add(parent, container);
        if (s.waitTimedOut) ui.note(parent, text(R.string.readiness_timeout));
        if (terminal) {
            ui.note(
                    parent,
                    "Sync stopped · "
                            + (info.getFailure() == null
                                    ? "TERMINAL"
                                    : info.getFailure().getCode()));
            ui.add(parent, ui.button("Reconnect", true, this::openConnection), 8);
        }
        if (!s.active && s.busy == null) {
            ui.add(parent, ui.button(text(R.string.retry), true, session::connect), 10);
            ui.add(
                    parent,
                    ui.button(
                            text(R.string.return_local),
                            () -> {
                                session.draft.local = true;
                                session.connect();
                            }),
                    8);
        }
    }

    private ImageButton shortcut(int label, int index) {
        return ui.icon(
                R.drawable.ic_open, text(label), () -> openFlag(session.specs.get(index).key));
    }

    private void demo(LinearLayout parent, ScreenState s) {
        status(parent, s);
        Person person = session.people.get(s.user);
        LinearLayout user = ui.row();
        TextView avatar =
                ui.label(person.name.substring(0, 1), 23, false, ui.color(R.color.cafe_on_primary));
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(ui.shape(ui.color(R.color.cafe_muted), 30));
        LinearLayout.LayoutParams av = new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42));
        av.setMarginEnd(ui.dp(12));
        user.addView(avatar, av);
        LinearLayout names = ui.column();
        ui.add(names, ui.label(person.name, 16, true));
        ui.add(names, muted("plan: " + person.plan, 12));
        user.addView(names, weight());
        MaterialButton change =
                ui.button(
                        text(R.string.switch_user_short),
                        true,
                        () -> {
                            session.userChoice = s.user;
                            session.userSheet = true;
                            forms.showUsers();
                        });
        change.setEnabled(s.available() && !s.flushPending);
        change.setStrokeWidth(0);
        change.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        user.addView(change, new LinearLayout.LayoutParams(-2, ui.dp(48)));
        ui.add(parent, user, 8);
        Business b = s.business;
        LinearLayout promo = ui.row();
        promo.addView(ui.label(b.promo, 14, true, ui.color(R.color.cafe_primary)), weight());
        promo.addView(shortcut(R.string.view_promo_flag, 1), iconSize());
        ui.add(parent, promo, 4);
        if (b.compact) ui.add(parent, ui.label(text(R.string.hero_title), 28, true));
        ImageView photo = new ImageView(this);
        photo.setImageBitmap(ui.coffee());
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photo.setContentDescription(text(R.string.photo_description));
        photo.setBackground(ui.shape(ui.color(R.color.cafe_surface), 10));
        photo.setClipToOutline(true);
        ui.add(parent, photo, 8, (int) ((contentWidth() - 36) * 187f / 397f));
        LinearLayout product = ui.row();
        product.addView(ui.label(text(R.string.product_name), 22, true), weight());
        TextView badge =
                ui.label(
                        text(b.compact ? R.string.new_checkout : R.string.classic_checkout),
                        11,
                        true,
                        ui.color(R.color.cafe_primary));
        badge.setBackground(ui.shape(ui.color(R.color.cafe_tonal), 20));
        badge.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        product.addView(badge);
        product.addView(shortcut(R.string.view_checkout_flag, 0), iconSize());
        ui.add(parent, product, 4);
        ui.add(parent, muted(text(R.string.product_description), 13));
        if (b.menuInvalid) ui.note(parent, text(R.string.invalid_menu));
        if (b.discountInvalid) ui.note(parent, text(R.string.invalid_discount));
        if (b.usingFallback) ui.add(parent, muted(text(R.string.using_fallback), 12), 6);
        if (b.compact) {
            boolean wrap = bigText() || b.menu.sizes.size() > 3;
            for (CupSize size : b.menu.sizes) wrap |= size.label.length() > 12;
            LinearLayout group = ui.row();
            group.setOrientation(wrap ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            group.setBackground(ui.shape(ui.color(R.color.cafe_surface), 13, true));
            group.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
            for (CupSize size : b.menu.sizes) {
                MaterialButton choice = ui.button(size.label, () -> session.selectSize(size.id));
                choice.setId(View.generateViewId());
                choice.setCheckable(true);
                choice.setChecked(size.id.equals(s.selectedSize));
                choice.setMinWidth(0);
                choice.setMinimumWidth(0);
                choice.setCornerRadius(ui.dp(11));
                choice.setPadding(ui.dp(6), 0, ui.dp(6), 0);
                choice.setBackgroundTintList(
                        ColorStateList.valueOf(
                                ui.color(
                                        choice.isChecked()
                                                ? R.color.cafe_primary
                                                : R.color.cafe_surface)));
                choice.setTextColor(
                        ui.color(
                                choice.isChecked() ? R.color.cafe_on_primary : R.color.cafe_muted));
                group.addView(
                        choice,
                        wrap
                                ? new LinearLayout.LayoutParams(-1, -2)
                                : new LinearLayout.LayoutParams(0, ui.dp(48), 1));
            }
            LinearLayout menu = ui.row();
            menu.addView(group, weight());
            menu.addView(shortcut(R.string.view_menu_flag, 3), iconSize());
            ui.add(parent, menu, 8);
            LinearLayout checkout = ui.row();
            checkout.setOrientation(bigText() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            checkout.setBackground(ui.shape(ui.color(R.color.cafe_surface), 12, true));
            checkout.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
            LinearLayout pricing = ui.column(), amount = ui.row();
            amount.addView(ui.label("$" + b.total().toPlainString(), 24, true));
            ImageButton discount = shortcut(R.string.view_discount_flag, 2);
            pricing.addView(amount);
            if (b.discount > 0) {
                LinearLayout offer = ui.row();
                TextView base = muted("$5.00", 10);
                base.setPaintFlags(
                        base.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                offer.addView(base);
                TextView sale =
                        ui.label(
                                number(b.discount) + "% off",
                                10,
                                false,
                                ui.color(R.color.cafe_primary));
                sale.setBackground(ui.shape(ui.color(R.color.cafe_tonal), 20));
                sale.setPadding(ui.dp(6), ui.dp(3), ui.dp(6), ui.dp(3));
                LinearLayout.LayoutParams saleLp = new LinearLayout.LayoutParams(-2, -2);
                saleLp.setMarginStart(ui.dp(8));
                offer.addView(sale, saleLp);
                offer.addView(discount, iconSize());
                LinearLayout.LayoutParams offerLp = new LinearLayout.LayoutParams(-2, -2);
                offerLp.setMarginStart(ui.dp(6));
                pricing.addView(offer, offerLp);
            } else amount.addView(discount, iconSize());
            checkout.addView(pricing, bigText() ? new LinearLayout.LayoutParams(-1, -2) : weight());
            MaterialButton order = ui.button(text(R.string.place_order), session::order);
            order.setEnabled(s.available());
            LinearLayout.LayoutParams orderLp =
                    new LinearLayout.LayoutParams(bigText() ? -1 : ui.dp(wide() ? 190 : 130), -2);
            if (bigText()) orderLp.topMargin = ui.dp(8);
            else orderLp.setMarginStart(ui.dp(8));
            checkout.addView(order, orderLp);
            ui.add(parent, checkout, 8);
        } else {
            LinearLayout heading = ui.row();
            heading.addView(ui.label(text(R.string.choose_size), 16, true), weight());
            heading.addView(shortcut(R.string.view_menu_flag, 3), iconSize());
            ui.add(parent, heading, 6);
            RadioGroup group = new RadioGroup(this);
            for (CupSize size : b.menu.sizes) {
                androidx.appcompat.widget.AppCompatRadioButton radio =
                        new androidx.appcompat.widget.AppCompatRadioButton(this);
                radio.setText(size.label);
                radio.setId(View.generateViewId());
                radio.setMinHeight(ui.dp(48));
                radio.setChecked(size.id.equals(s.selectedSize));
                radio.setOnClickListener(v -> session.selectSize(size.id));
                group.addView(radio);
            }
            ui.add(parent, group);
            ui.line(parent, 6);
            ui.keyValue(parent, text(R.string.subtotal), "$5.00");
            LinearLayout discount = ui.row(), value = ui.column();
            ui.keyValue(
                    value,
                    "Discount (" + number(b.discount) + "%)",
                    "-$" + new BigDecimal("5.00").subtract(b.total()).toPlainString());
            discount.addView(value, weight());
            discount.addView(shortcut(R.string.view_discount_flag, 2), iconSize());
            ui.add(parent, discount);
            ui.line(parent, 6);
            ui.keyValue(parent, text(R.string.total), "$" + b.total().toPlainString(), true);
        }
        if (b.compact) {
            TextView helper = muted(text(R.string.simulated_order), 12);
            helper.setGravity(Gravity.CENTER);
            ui.add(parent, helper, 8);
        }
        if (s.order != null)
            ui.add(
                    parent,
                    muted(
                            text(R.string.last_order)
                                    + ": "
                                    + s.order.size
                                    + " · $"
                                    + s.order.amount
                                    + " · "
                                    + s.order.result,
                            12),
                    12);
    }

    private String number(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private void flags(LinearLayout parent, ScreenState s) {
        status(parent, s);
        ui.title(parent, text(R.string.four_flags));
        ui.add(
                parent,
                muted(text(s.local ? R.string.local_editable : R.string.live_read_only), 12),
                4);
        MenuItem search =
                toolbar.getMenu().add(R.string.search_flags).setIcon(R.drawable.ic_search);
        search.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        search.setOnMenuItemClickListener(
                v -> {
                    EditText input = new EditText(this);
                    input.setHint(text(R.string.search_flags));
                    input.setText(session.filter);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.search_flags)
                            .setView(input)
                            .setPositiveButton(
                                    R.string.search,
                                    (d, w) -> {
                                        session.filter = input.getText().toString();
                                        render(session.state);
                                    })
                            .setNeutralButton(
                                    R.string.clear,
                                    (d, w) -> {
                                        session.filter = "";
                                        render(session.state);
                                    })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    return true;
                });
        int index = 0;
        for (FlagSpec spec : session.specs) {
            if (!spec.key.toLowerCase(Locale.US).contains(session.filter.toLowerCase(Locale.US)))
                continue;
            View item = getLayoutInflater().inflate(R.layout.flag_row, parent, false);
            ((TextView) item.findViewById(R.id.flag_key)).setText(spec.key);
            ((TextView) item.findViewById(R.id.flag_type))
                    .setText(spec.type == ValueType.JSON ? "JSON" : titleCase(spec.type));
            String raw =
                    s.snapshot.containsKey(spec.key) ? s.snapshot.get(spec.key).getValue() : null;
            String summary = raw;
            if (spec.type == ValueType.JSON && raw != null) {
                CafeModel.Menu menu = Business.menu(raw);
                if (menu != null) {
                    String label = "";
                    for (CupSize size : menu.sizes)
                        if (size.id.equals(menu.defaultSize)) label = size.label;
                    summary = menu.sizes.size() + " sizes · " + label + " default";
                }
            }
            ((TextView) item.findViewById(R.id.flag_value))
                    .setText(
                            (summary == null ? text(R.string.missing_flag) : summary)
                                    + (s.snapshot.containsKey(spec.key)
                                                    && s.snapshot.get(spec.key).error != null
                                            ? " · " + s.snapshot.get(spec.key).error
                                            : ""));
            if (index == 0) item.setBackground(ui.shape(ui.color(R.color.cafe_tonal)));
            item.setOnClickListener(v -> openFlag(spec.key));
            ui.add(parent, item, index == 0 ? 10 : 0);
            if (index != 0) ui.line(parent, 0);
            index++;
        }
        if (index == 0) ui.note(parent, text(R.string.no_matches), false);
        ui.add(parent, muted(text(R.string.no_browse_events), 12), 12);
        if (s.local) {
            MaterialButton restore =
                    ui.button(
                            text(R.string.restore_all),
                            true,
                            () ->
                                    forms.confirm(
                                            text(R.string.restore_all),
                                            text(R.string.restore_all_warning),
                                            () -> session.edit(null, null, true, ok -> {})));
            restore.setEnabled(s.available());
            ui.add(parent, restore, 12);
        }
    }

    private void details(LinearLayout parent, String key, ScreenState s) {
        FlagSpec spec = session.spec(key);
        if (!s.local) status(parent, s);
        LinearLayout card = ui.column();
        card.setBackground(ui.shape(ui.color(R.color.cafe_panel), 12, true));
        card.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        ui.add(card, muted("Flag key", 12));
        ui.add(card, ui.label(key, 16, true), 4);
        ui.line(card, 14);
        ui.title(card, text(R.string.current_snapshot), 14);
        ui.keyValue(
                card,
                text(R.string.value),
                s.snapshot.containsKey(key)
                        ? s.snapshot.get(key).getValue()
                        : text(R.string.missing_flag));
        ui.keyValue(
                card,
                text(R.string.expected_type),
                spec.type == ValueType.JSON ? "JSON" : titleCase(spec.type));
        ui.line(card, 14);
        LinearLayout panel = ui.column();
        ui.add(panel, ui.label(text(R.string.last_evaluation), 14, true));
        ReadRecord read = s.reads.get(key);
        if (read == null) ui.add(panel, muted(text(R.string.not_evaluated), 12), 6);
        else {
            ui.add(panel, muted(read.time + " · " + read.user, 12), 4);
            ui.keyValue(panel, text(R.string.value), read.value);
            ui.keyValue(panel, text(R.string.fallback), read.fallback);
            ui.keyValue(panel, text(R.string.reason), read.reason);
            if (read.stale)
                ui.note(panel, text(R.string.out_of_date) + "\nThe local value may have changed.");
        }
        MaterialButton evaluate =
                ui.button(text(R.string.evaluate), true, () -> session.evaluate(key));
        evaluate.setEnabled(s.available());
        ui.add(panel, evaluate, 12);
        ui.add(card, panel, 14);
        if (s.local) {
            MaterialButton edit =
                    ui.button(
                            text(R.string.edit_local),
                            () -> {
                                session.editorKey = key;
                                session.editorText = session.localValue(key);
                                forms.showEditor(key);
                            });
            edit.setEnabled(s.available());
            ui.add(card, edit, 10);
            MaterialButton restore =
                    ui.button(
                            text(R.string.restore_default),
                            true,
                            () -> session.edit(key, spec.initial));
            restore.setEnabled(s.available());
            restore.setStrokeWidth(0);
            ui.add(card, restore, 4);
        } else ui.note(card, text(R.string.live_read_only), false);
        ui.add(parent, card, 12);
        ui.add(parent, muted(text(R.string.no_browse_events), 12), 12);
    }

    private void inspect(LinearLayout parent, ScreenState s) {
        status(parent, s);
        ui.title(parent, text(R.string.connection));
        ConnectionInformation info = s.status;
        ui.keyValue(
                parent,
                text(R.string.configured),
                titleCase(info == null ? null : info.getConfiguredMode()),
                false,
                R.drawable.ic_wifi,
                false);
        ui.keyValue(
                parent,
                text(R.string.effective),
                titleCase(info == null ? null : info.getEffectiveMode()),
                false,
                R.drawable.ic_sync,
                false);
        if (!s.local
                && info != null
                && info.getConfiguredMode() == SyncMode.STREAMING
                && info.getEffectiveMode() == SyncMode.POLLING
                && info.getRecovery() != RecoveryStatus.NONE)
            ui.note(parent, text(R.string.polling_fallback_active), false);
        ui.keyValue(
                parent,
                text(R.string.local_data),
                text(
                        info != null && info.getLocalDataAvailable()
                                ? R.string.available
                                : R.string.unavailable),
                false,
                R.drawable.ic_database,
                false);
        ui.keyValue(
                parent,
                text(R.string.remote_confirmed),
                text(info != null && info.getRemoteConfirmed() ? R.string.yes : R.string.no),
                false,
                R.drawable.ic_check,
                false);
        ui.line(parent);
        MaterialSwitch offline = new MaterialSwitch(this);
        offline.setText(text(R.string.offline_mode));
        offline.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_plane, 0, 0, 0);
        offline.setCompoundDrawablePadding(ui.dp(16));
        offline.setMinHeight(ui.dp(56));
        offline.setChecked(info != null && info.getPauseReasons().contains(PauseReason.OFFLINE));
        offline.setEnabled(s.available() && !s.flushPending);
        offline.setOnCheckedChangeListener((v, checked) -> session.offline(checked));
        ui.add(parent, offline);
        ui.line(parent, 0);
        ui.title(parent, text(R.string.events));
        MaterialButton flush =
                ui.button(
                        text(s.flushPending ? R.string.flushing : R.string.flush_events),
                        true,
                        session::flush);
        flush.setEnabled(s.available() && s.events && !s.flushPending);
        flush.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_events));
        ui.add(parent, flush, 8);
        if (!s.events)
            ui.add(
                    parent,
                    muted(text(s.local ? R.string.events_local : R.string.events_disabled), 12),
                    6);
        ui.keyValue(
                parent,
                text(R.string.last_track),
                s.lastTrack == null ? text(R.string.no_operation) : s.lastTrack,
                false,
                0,
                "ACCEPTED".equals(s.lastTrack));
        ui.add(parent, muted(text(R.string.accepted_hint), 11), 4);
        ui.keyValue(
                parent,
                text(R.string.last_flush),
                s.lastFlush == null ? text(R.string.no_operation) : s.lastFlush,
                false,
                0,
                "ALL_DELIVERED".equals(s.lastFlush));
        ui.add(parent, muted(text(R.string.delivery_hint), 11), 4);
        ui.line(parent);
        ui.title(parent, text(R.string.recent_activity));
        for (int i = s.history.size() - 1; i >= Math.max(0, s.history.size() - 3); i--) {
            ActivityEntry e = s.history.get(i);
            LinearLayout row = ui.row();
            ImageView clock = new ImageView(this);
            clock.setImageResource(R.drawable.ic_clock);
            clock.setImageTintList(ColorStateList.valueOf(ui.color(R.color.cafe_muted)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
            lp.setMarginEnd(ui.dp(12));
            row.addView(clock, lp);
            TextView time = muted(e.time, 12);
            time.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.addView(time, new LinearLayout.LayoutParams(ui.dp(80), -2));
            row.addView(ui.label(e.message, 12), weight());
            ui.add(parent, row, 8);
        }
        if (s.history.isEmpty()) ui.add(parent, muted(text(R.string.no_activity), 12), 8);
        ui.title(parent, text(R.string.diagnostics));
        ui.keyValue(parent, "OpenFeature status", session.providerStatus());
        ui.keyValue(parent, "FeatBit connection", info == null ? "—" : info.getStatus().name());
        ui.keyValue(
                parent,
                text(R.string.pause_reasons),
                info == null
                        ? "—"
                        : info.getPauseReasons().isEmpty()
                                ? "None"
                                : joined(info.getPauseReasons()));
        ui.keyValue(
                parent,
                text(R.string.last_success),
                time(info == null ? null : info.getLastSuccessAtMillis()));
        ui.keyValue(
                parent,
                text(R.string.last_failure),
                time(info == null ? null : info.getLastFailureAtMillis()));
        ui.keyValue(
                parent,
                text(R.string.failure),
                info == null || info.getFailure() == null ? "—" : info.getFailure().getCode());
        ui.keyValue(
                parent, text(R.string.recovery), info == null ? "—" : info.getRecovery().name());
        ui.keyValue(
                parent,
                text(R.string.candidate_failure),
                info == null || info.getCandidateFailure() == null
                        ? "—"
                        : info.getCandidateFailure().getCode());
        ui.keyValue(parent, text(R.string.implementation), "Java · " + session.sdkVersion());
        ui.add(parent, ui.button(text(R.string.open_connection), true, this::openConnection), 16);
        if (s.history.size() > 3)
            ui.add(
                    parent,
                    ui.button(
                            text(R.string.all_activity),
                            true,
                            () -> {
                                StringBuilder message = new StringBuilder();
                                for (int i = s.history.size() - 1; i >= 0; i--) {
                                    ActivityEntry e = s.history.get(i);
                                    if (message.length() > 0) message.append('\n');
                                    message.append(e.time).append("  ").append(e.message);
                                }
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle(R.string.recent_activity)
                                        .setMessage(message)
                                        .setPositiveButton(R.string.close, null)
                                        .show();
                            }),
                    8);
    }

    private String time(Long v) {
        return v == null ? "—" : new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(v));
    }

    private String joined(Collection<?> values) {
        StringBuilder s = new StringBuilder();
        for (Object v : values) {
            if (s.length() > 0) s.append(", ");
            s.append(v);
        }
        return s.toString();
    }
}
