package co.featbit.sample.kotlin

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import co.featbit.android.api.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.snackbar.Snackbar
import java.math.BigDecimal
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    val session
        get() = (application as CafeApplication).session

    val state
        get() = session.state.value

    lateinit var ui: CafeViews
    lateinit var host: FrameLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var nav: BottomNavigationView
    private lateinit var rail: NavigationRailView
    private var scroll: NestedScrollView? = null
    private var rendered = ""
    private var updatingNavigation = false
    private var lastMessage: String? = null
    private var feedback: Snackbar? = null
    lateinit var forms: CafeForms
    private val wide
        get() = resources.configuration.screenWidthDp >= 600

    private val contentWidth
        get() =
            if (wide) minOf(620, resources.configuration.screenWidthDp - 80)
            else resources.configuration.screenWidthDp

    private val bigText
        get() = resources.configuration.fontScale > 1.15f

    fun text(id: Int) = getString(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = CafeViews(this)
        forms = CafeForms(this)
        setContentView(R.layout.activity_main)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            val light =
                resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        toolbar = findViewById(R.id.toolbar)
        host = findViewById(R.id.page_host)
        nav = findViewById(R.id.navigation)
        rail = findViewById(R.id.rail)
        val navigationInk =
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ui.color(R.color.cafe_primary), ui.color(R.color.cafe_muted)),
            )
        nav.itemActiveIndicatorColor = ColorStateList.valueOf(ui.color(R.color.cafe_tonal))
        rail.itemActiveIndicatorColor = ColorStateList.valueOf(ui.color(R.color.cafe_tonal))
        nav.itemIconTintList = navigationInk
        nav.itemTextColor = navigationInk
        rail.itemIconTintList = navigationInk
        rail.itemTextColor = navigationInk
        findViewById<View>(R.id.root)
            .viewTreeObserver
            .addOnDrawListener(
                object : android.view.ViewTreeObserver.OnDrawListener {
                    private var scheduled = false

                    override fun onDraw() {
                        if (scheduled) return
                        scheduled = true
                        host.post {
                            host.rootView.viewTreeObserver.removeOnDrawListener(this)
                            session.start()
                        }
                    }
                }
            )
        val listener: (MenuItem) -> Boolean = { item ->
            if (!updatingNavigation) {
                session.destination =
                    when (item.itemId) {
                        R.id.nav_flags -> "Flags"
                        R.id.nav_inspect -> "Inspect"
                        else -> "Demo"
                    }
                session.detailKey = null
                session.formOpen = false
                navigate()
            }
            true
        }
        nav.setOnItemSelectedListener(listener)
        rail.setOnItemSelectedListener(listener)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        session.formOpen -> {
                            session.formOpen = false
                            navigate()
                        }
                        session.detailKey != null -> {
                            session.detailKey = null
                            navigate()
                        }
                        session.destination != "Demo" -> {
                            session.destination = "Demo"
                            navigate()
                        }
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            },
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.invalidateReads()
                session.state.collect { render(it) }
            }
        }
    }

    override fun onStop() {
        session.scrollY = scroll?.scrollY ?: 0
        super.onStop()
    }

    override fun onDestroy() {
        forms.destroy()
        super.onDestroy()
    }

    fun navigate() {
        rendered = ""
        session.scrollY = 0
        render(state)
    }

    fun openConnection() {
        session.formOpen = true
        navigate()
    }

    fun openFlag(key: String) {
        session.detailKey = key
        navigate()
    }

    fun render(s: ScreenState): Unit =
        with(ui) {
            val screen =
                if (session.formOpen) "Connection"
                else session.detailKey?.let { "Detail:$it" } ?: session.destination
            val sub = session.formOpen || session.detailKey != null
            toolbar.title =
                if (session.formOpen) text(R.string.connection)
                else if (session.detailKey != null) text(R.string.flag_details)
                else if (screen == "Demo") text(R.string.app_name) else screen
            toolbar.navigationIcon =
                if (sub || screen == "Demo")
                    ContextCompat.getDrawable(
                        this@MainActivity,
                        if (sub) R.drawable.ic_back else R.drawable.ic_coffee,
                    )
                else null
            toolbar.setNavigationOnClickListener {
                if (sub) onBackPressedDispatcher.onBackPressed()
            }
            toolbar.menu.clear()
            if (!sub && screen != "Flags")
                toolbar.menu.add(text(R.string.connection)).setIcon(R.drawable.ic_settings).apply {
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    setOnMenuItemClickListener {
                        openConnection()
                        true
                    }
                }
            if (session.detailKey != null && s.local)
                toolbar.menu.add("").apply {
                    actionView =
                        label(text(R.string.local_demo), 12f, false, color(R.color.cafe_primary))
                            .apply {
                                background = shape(color(R.color.cafe_tonal), 16)
                                setPadding(dp(10), dp(5), dp(10), dp(5))
                            }
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            val actions = findViewById<LinearLayout>(R.id.classic_actions)
            actions.removeAllViews()
            actions.visibility =
                if (screen == "Demo" && !s.business.compact) View.VISIBLE else View.GONE
            if (actions.visibility == View.VISIBLE) {
                actions.setPadding(dp(18), dp(8), dp(18), dp(6))
                add(
                    actions,
                    button(text(R.string.place_order)) { session.order() }
                        .apply { isEnabled = s.available },
                )
                add(
                    actions,
                    label(text(R.string.simulated_order), 12f, false, color(R.color.cafe_muted))
                        .apply { gravity = Gravity.CENTER },
                    4,
                )
            }
            nav.visibility = if (!wide && !sub) View.VISIBLE else View.GONE
            rail.visibility = if (wide && !sub) View.VISIBLE else View.GONE
            updatingNavigation = true
            val id =
                when (session.destination) {
                    "Flags" -> R.id.nav_flags
                    "Inspect" -> R.id.nav_inspect
                    else -> R.id.nav_demo
                }
            nav.selectedItemId = id
            rail.selectedItemId = id
            updatingNavigation = false
            if (screen != "Connection" || rendered != screen) {
                val oldScroll = if (rendered == screen) scroll?.scrollY ?: session.scrollY else 0
                host.removeAllViews()
                scroll =
                    layoutInflater.inflate(R.layout.page_scroll, host, false) as NestedScrollView
                host.addView(
                    scroll,
                    FrameLayout.LayoutParams(
                        if (wide) dp(contentWidth) else -1,
                        -1,
                        Gravity.CENTER_HORIZONTAL,
                    ),
                )
                val currentScroll = scroll!!
                val page = currentScroll.findViewById<LinearLayout>(R.id.page_content)
                page.isFocusableInTouchMode = true
                page.requestFocus()
                when {
                    session.formOpen -> forms.connection(page)
                    session.detailKey != null -> details(page, session.detailKey!!, s)
                    screen == "Flags" -> flags(page, s)
                    screen == "Inspect" -> inspect(page, s)
                    else -> demo(page, s)
                }
                currentScroll.post {
                    if (scroll === currentScroll) currentScroll.scrollTo(0, oldScroll)
                }
                rendered = screen
            }
            forms.update(s)
            if (s.message != null && s.message != lastMessage) {
                lastMessage = s.message
                feedback?.dismiss()
                feedback =
                    Snackbar.make(host, s.message, Snackbar.LENGTH_LONG).apply {
                        if (nav.visibility == View.VISIBLE) anchorView = nav
                        setActionTextColor(color(R.color.cafe_on_primary))
                        setAction(R.string.dismiss) { session.dismissMessage() }
                        show()
                    }
            }
            if (s.message == null) {
                lastMessage = null
                feedback?.dismiss()
                feedback = null
            }
        }

    private fun status(parent: LinearLayout, s: ScreenState): Unit =
        with(ui) {
            val info = s.status
            val offline = info?.pauseReasons?.contains(PauseReason.OFFLINE) == true
            val paused = info?.pauseReasons?.isNotEmpty() == true
            val good =
                s.active &&
                    s.busy == null &&
                    !paused &&
                    (s.local || info?.remoteConfirmed == true) &&
                    info?.status != SyncStatus.TERMINAL
            val heading =
                when {
                    s.busy != null -> s.busy
                    !s.active -> text(R.string.not_connected)
                    offline -> text(R.string.offline_values)
                    paused -> text(R.string.paused)
                    info?.status == SyncStatus.TERMINAL -> text(R.string.sync_stopped)
                    s.local -> text(R.string.local_demo)
                    info?.remoteConfirmed == true ->
                        "Live · ${info.effectiveMode.name.lowercase().replaceFirstChar { it.uppercase() }}"
                    else -> text(R.string.live_waiting)
                }
            val detail =
                when {
                    !s.active -> text(R.string.remote_unconfirmed)
                    s.local -> text(R.string.local_status)
                    paused -> info!!.pauseReasons.joinToString { it.name }
                    info?.remoteConfirmed == true -> text(R.string.remote_confirmed)
                    else -> text(R.string.remote_unconfirmed)
                }
            val container =
                row().apply {
                    background =
                        shape(color(if (good) R.color.cafe_good_bg else R.color.cafe_warn_bg))
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                }
            container.addView(
                View(this@MainActivity).apply {
                    background =
                        shape(color(if (good) R.color.cafe_good else R.color.cafe_warn), 20)
                },
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) },
            )
            val texts = column()
            add(
                texts,
                label(heading, 14f, true, color(if (good) R.color.cafe_good else R.color.cafe_warn)),
            )
            add(texts, label(detail, 12f, false, color(R.color.cafe_muted)))
            container.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            container.contentDescription = "$heading. $detail"
            container.setOnClickListener {
                session.destination = "Inspect"
                session.detailKey = null
                navigate()
            }
            add(parent, container)
            if (s.waitTimedOut) note(parent, text(R.string.readiness_timeout))
            if (info?.status == SyncStatus.TERMINAL) {
                note(parent, "Sync stopped · ${info.failure?.code ?: "TERMINAL"}")
                add(parent, button("Reconnect", true) { openConnection() }, 8)
            }
            if (!s.active && s.busy == null) {
                add(parent, button(text(R.string.retry), true) { session.connect() }, 10)
                add(
                    parent,
                    button(text(R.string.return_local)) {
                        session.draft.local = true
                        session.connect()
                    },
                    8,
                )
            }
        }

    private fun demo(parent: LinearLayout, s: ScreenState): Unit =
        with(ui) {
            status(parent, s)
            val person = session.people[s.user]
            val user = row()
            user.addView(
                label(person.name.take(1), 23f, false, color(R.color.cafe_on_primary)).apply {
                    gravity = Gravity.CENTER
                    background = shape(color(R.color.cafe_muted), 30)
                },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) },
            )
            val names = column()
            add(names, label(person.name, 16f, true))
            add(names, label("plan: ${person.plan}", 12f, false, color(R.color.cafe_muted)))
            user.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            user.addView(
                button(text(R.string.switch_user_short), true) {
                        session.userChoice = s.user
                        session.userSheet = true
                        forms.showUsers()
                    }
                    .apply {
                        isEnabled = s.available && !s.flushPending
                        strokeWidth = 0
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    },
                LinearLayout.LayoutParams(-2, dp(48)),
            )
            add(parent, user, 8)
            val b = s.business
            val promo = row()
            promo.addView(
                label(b.promo, 14f, true, color(R.color.cafe_primary)),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            promo.addView(
                icon(R.drawable.ic_open, text(R.string.view_promo_flag)) {
                    openFlag(session.specs[1].key)
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
            add(parent, promo, 4)
            if (b.compact) add(parent, label(text(R.string.hero_title), 28f, true))
            add(
                parent,
                ImageView(this@MainActivity).apply {
                    setImageBitmap(coffee())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = text(R.string.photo_description)
                    background = shape(color(R.color.cafe_surface), 10)
                    clipToOutline = true
                },
                8,
                ((contentWidth - 36) * 187f / 397f).toInt(),
            )
            val product = row()
            product.addView(
                label(text(R.string.product_name), 22f, true),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            product.addView(
                label(
                        text(if (b.compact) R.string.new_checkout else R.string.classic_checkout),
                        11f,
                        true,
                        color(R.color.cafe_primary),
                    )
                    .apply {
                        background = shape(color(R.color.cafe_tonal), 20)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                    }
            )
            product.addView(
                icon(R.drawable.ic_open, text(R.string.view_checkout_flag)) {
                    openFlag(session.specs[0].key)
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
            add(parent, product, 4)
            add(
                parent,
                label(text(R.string.product_description), 13f, false, color(R.color.cafe_muted)),
            )
            if (b.menuInvalid) note(parent, text(R.string.invalid_menu))
            if (b.discountInvalid) note(parent, text(R.string.invalid_discount))
            if (b.usingFallback)
                add(
                    parent,
                    label(text(R.string.using_fallback), 12f, false, color(R.color.cafe_muted)),
                    6,
                )
            if (b.compact) {
                val wrapChoices =
                    bigText || b.menu.sizes.size > 3 || b.menu.sizes.any { it.label.length > 12 }
                val group =
                    LinearLayout(this@MainActivity).apply {
                        orientation =
                            if (wrapChoices) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                        background = shape(color(R.color.cafe_surface), 13, true)
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                b.menu.sizes.forEach { size ->
                    val choice =
                        button(size.label, false) { session.selectSize(size.id) }
                            .apply {
                                id = View.generateViewId()
                                isCheckable = true
                                isChecked = size.id == s.selectedSize
                                minWidth = 0
                                minimumWidth = 0
                                cornerRadius = dp(11)
                                setPadding(dp(6), 0, dp(6), 0)
                                backgroundTintList =
                                    ColorStateList.valueOf(
                                        color(
                                            if (isChecked) R.color.cafe_primary
                                            else R.color.cafe_surface
                                        )
                                    )
                                setTextColor(
                                    color(
                                        if (isChecked) R.color.cafe_on_primary
                                        else R.color.cafe_muted
                                    )
                                )
                            }
                    group.addView(
                        choice,
                        if (wrapChoices) LinearLayout.LayoutParams(-1, -2)
                        else LinearLayout.LayoutParams(0, dp(48), 1f),
                    )
                }
                val menu = row()
                menu.addView(group, LinearLayout.LayoutParams(0, -2, 1f))
                menu.addView(
                    icon(R.drawable.ic_open, text(R.string.view_menu_flag)) {
                        openFlag(session.specs[3].key)
                    },
                    LinearLayout.LayoutParams(dp(48), dp(48)),
                )
                add(parent, menu, 8)
                val checkout =
                    LinearLayout(this@MainActivity).apply {
                        orientation =
                            if (bigText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = shape(color(R.color.cafe_surface), 12, true)
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                    }
                val pricing = column()
                val amount = row()
                amount.addView(label("$${b.total.toPlainString()}", 24f, true))
                val discountLink =
                    icon(R.drawable.ic_open, text(R.string.view_discount_flag)) {
                        openFlag(session.specs[2].key)
                    }
                pricing.addView(amount)
                if (b.discount > 0) {
                    val offer = row()
                    offer.addView(
                        label("$5.00", 10f, false, color(R.color.cafe_muted)).apply {
                            paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                        }
                    )
                    offer.addView(
                        label("${number(b.discount)}% off", 10f, false, color(R.color.cafe_primary))
                            .apply {
                                background = shape(color(R.color.cafe_tonal), 20)
                                setPadding(dp(6), dp(3), dp(6), dp(3))
                            },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
                    )
                    offer.addView(discountLink, LinearLayout.LayoutParams(dp(48), dp(48)))
                    pricing.addView(
                        offer,
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
                    )
                } else {
                    amount.addView(discountLink, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
                checkout.addView(
                    pricing,
                    if (bigText) LinearLayout.LayoutParams(-1, -2)
                    else LinearLayout.LayoutParams(0, -2, 1f),
                )
                checkout.addView(
                    button(text(R.string.place_order)) { session.order() }
                        .apply { isEnabled = s.available },
                    if (bigText) LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                    else
                        LinearLayout.LayoutParams(dp(if (wide) 190 else 130), -2).apply {
                            marginStart = dp(8)
                        },
                )
                add(parent, checkout, 8)
            } else {
                val menuHeading = row()
                menuHeading.addView(
                    label(text(R.string.choose_size), 16f, true),
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                menuHeading.addView(
                    icon(R.drawable.ic_open, text(R.string.view_menu_flag)) {
                        openFlag(session.specs[3].key)
                    },
                    LinearLayout.LayoutParams(dp(48), dp(48)),
                )
                add(parent, menuHeading, 6)
                val group = RadioGroup(this@MainActivity)
                b.menu.sizes.forEach { size ->
                    group.addView(
                        androidx.appcompat.widget.AppCompatRadioButton(this@MainActivity).apply {
                            text = size.label
                            id = View.generateViewId()
                            minHeight = dp(48)
                            isChecked = size.id == s.selectedSize
                            setOnClickListener { session.selectSize(size.id) }
                        }
                    )
                }
                add(parent, group)
                line(parent, 6)
                keyValue(parent, text(R.string.subtotal), "$5.00")
                val discount = row()
                val discountValue = column()
                keyValue(
                    discountValue,
                    "Discount (${number(b.discount)}%)",
                    "-$${BigDecimal("5.00").subtract(b.total).toPlainString()}",
                )
                discount.addView(discountValue, LinearLayout.LayoutParams(0, -2, 1f))
                discount.addView(
                    icon(R.drawable.ic_open, text(R.string.view_discount_flag)) {
                        openFlag(session.specs[2].key)
                    },
                    LinearLayout.LayoutParams(dp(48), dp(48)),
                )
                add(parent, discount)
                line(parent, 6)
                keyValue(parent, text(R.string.total), "$${b.total.toPlainString()}", true)
            }
            if (b.compact)
                add(
                    parent,
                    label(text(R.string.simulated_order), 12f, false, color(R.color.cafe_muted))
                        .apply { gravity = Gravity.CENTER },
                    8,
                )
            s.order?.let {
                add(
                    parent,
                    label(
                        "${text(R.string.last_order)}: ${it.size} · $${it.amount} · ${it.result}",
                        12f,
                        false,
                        color(R.color.cafe_muted),
                    ),
                    12,
                )
            }
        }

    private fun number(value: Double) =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun flags(parent: LinearLayout, s: ScreenState): Unit =
        with(ui) {
            status(parent, s)
            title(parent, text(R.string.four_flags))
            add(
                parent,
                label(
                    text(if (s.local) R.string.local_editable else R.string.live_read_only),
                    12f,
                    false,
                    color(R.color.cafe_muted),
                ),
                4,
            )
            toolbar.menu.add(R.string.search_flags).setIcon(R.drawable.ic_search).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener {
                    val input =
                        EditText(this@MainActivity).apply {
                            hint = text(R.string.search_flags)
                            setText(session.filter)
                        }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.search_flags)
                        .setView(input)
                        .setPositiveButton(R.string.search) { _, _ ->
                            session.filter = input.text.toString()
                            render(state)
                        }
                        .setNeutralButton(R.string.clear) { _, _ ->
                            session.filter = ""
                            render(state)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            }
            session.specs
                .filter { it.key.contains(session.filter, true) }
                .forEachIndexed { index, spec ->
                    val item = layoutInflater.inflate(R.layout.flag_row, parent, false)
                    item.findViewById<TextView>(R.id.flag_key).text = spec.key
                    item.findViewById<TextView>(R.id.flag_type).text =
                        if (spec.type == ValueType.JSON) "JSON"
                        else spec.type.name.lowercase().replaceFirstChar { it.uppercase() }
                    val raw = s.snapshot[spec.key]?.value
                    val summary =
                        if (spec.type == ValueType.JSON && raw != null)
                            Business.menu(raw)?.let {
                                "${it.sizes.size} sizes · ${it.sizes.first { size -> size.id == it.defaultSize }.label} default"
                            } ?: raw
                        else raw
                    item.findViewById<TextView>(R.id.flag_value).text =
                        (summary ?: text(R.string.missing_flag)) +
                            (s.snapshot[spec.key]?.error?.let { " · $it" } ?: "")
                    if (index == 0) item.background = shape(color(R.color.cafe_tonal))
                    item.setOnClickListener { openFlag(spec.key) }
                    add(parent, item, if (index == 0) 10 else 0)
                    if (index != 0) line(parent, 0)
                }
            if (session.specs.none { it.key.contains(session.filter, true) })
                note(parent, text(R.string.no_matches), false)
            add(
                parent,
                label(text(R.string.no_browse_events), 12f, false, color(R.color.cafe_muted)),
                12,
            )
            if (s.local)
                add(
                    parent,
                    button(text(R.string.restore_all), true) {
                            forms.confirm(
                                text(R.string.restore_all),
                                text(R.string.restore_all_warning),
                            ) {
                                session.edit(null, restoreAll = true)
                            }
                        }
                        .apply { isEnabled = s.available },
                    12,
                )
        }

    private fun evaluation(parent: LinearLayout, spec: FlagSpec, s: ScreenState): Unit =
        with(ui) {
            add(parent, label(text(R.string.last_evaluation), 14f, true))
            val read = s.reads[spec.key]
            if (read == null)
                add(
                    parent,
                    label(text(R.string.not_evaluated), 12f, false, color(R.color.cafe_muted)),
                    6,
                )
            else {
                add(
                    parent,
                    label("${read.time} · ${read.user}", 12f, false, color(R.color.cafe_muted)),
                    4,
                )
                keyValue(parent, text(R.string.value), read.value)
                keyValue(parent, text(R.string.fallback), read.fallback)
                keyValue(parent, text(R.string.reason), read.reason)
                if (read.stale)
                    note(parent, "${text(R.string.out_of_date)}\nThe local value may have changed.")
            }
            add(
                parent,
                button(text(R.string.evaluate), true) { session.evaluate(spec.key) }
                    .apply { isEnabled = s.available },
                12,
            )
        }

    private fun details(parent: LinearLayout, key: String, s: ScreenState): Unit =
        with(ui) {
            val spec = session.specs.first { it.key == key }
            if (!s.local) status(parent, s)
            val card =
                column().apply {
                    background = shape(color(R.color.cafe_panel), 12, true)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                }
            add(card, label("Flag key", 12f, false, color(R.color.cafe_muted)))
            add(card, label(spec.key, 16f, true), 4)
            line(card, 14)
            title(card, text(R.string.current_snapshot), 14)
            keyValue(
                card,
                text(R.string.value),
                s.snapshot[key]?.value ?: text(R.string.missing_flag),
            )
            keyValue(
                card,
                text(R.string.expected_type),
                if (spec.type == ValueType.JSON) "JSON"
                else spec.type.name.lowercase().replaceFirstChar { it.uppercase() },
            )
            line(card, 14)
            val panel = column()
            evaluation(panel, spec, s)
            add(card, panel, 14)
            if (s.local) {
                add(
                    card,
                    button(text(R.string.edit_local)) {
                            session.editorKey = key
                            session.editorText = session.localValue(key)
                            forms.showEditor(key)
                        }
                        .apply { isEnabled = s.available },
                    10,
                )
                add(
                    card,
                    button(text(R.string.restore_default), true) { session.edit(key, spec.initial) }
                        .apply {
                            isEnabled = s.available
                            strokeWidth = 0
                        },
                    4,
                )
            } else note(card, text(R.string.live_read_only), false)
            add(parent, card, 12)
            add(
                parent,
                label(text(R.string.no_browse_events), 12f, false, color(R.color.cafe_muted)),
                12,
            )
        }

    private fun inspect(parent: LinearLayout, s: ScreenState): Unit =
        with(ui) {
            status(parent, s)
            title(parent, text(R.string.connection))
            val info = s.status
            keyValue(
                parent,
                text(R.string.configured),
                info?.configuredMode?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—",
                glyph = R.drawable.ic_wifi,
            )
            keyValue(
                parent,
                text(R.string.effective),
                info?.effectiveMode?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—",
                glyph = R.drawable.ic_sync,
            )
            if (
                !s.local &&
                    info?.configuredMode == SyncMode.STREAMING &&
                    info.effectiveMode == SyncMode.POLLING &&
                    info.recovery != RecoveryStatus.NONE
            )
                note(parent, text(R.string.polling_fallback_active), false)
            keyValue(
                parent,
                text(R.string.local_data),
                text(
                    if (info?.localDataAvailable == true) R.string.available
                    else R.string.unavailable
                ),
                glyph = R.drawable.ic_database,
            )
            keyValue(
                parent,
                text(R.string.remote_confirmed),
                text(if (info?.remoteConfirmed == true) R.string.yes else R.string.no),
                glyph = R.drawable.ic_check,
            )
            line(parent)
            add(
                parent,
                MaterialSwitch(this@MainActivity).apply {
                    text = text(R.string.offline_mode)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_plane, 0, 0, 0)
                    compoundDrawablePadding = dp(16)
                    minHeight = dp(56)
                    isChecked = info?.pauseReasons?.contains(PauseReason.OFFLINE) == true
                    isEnabled = s.available && !s.flushPending
                    setOnCheckedChangeListener { _, checked -> session.offline(checked) }
                },
            )
            line(parent, 0)
            title(parent, text(R.string.events))
            add(
                parent,
                button(
                        text(if (s.flushPending) R.string.flushing else R.string.flush_events),
                        true,
                    ) {
                        session.flush()
                    }
                    .apply {
                        isEnabled = s.available && s.events && !s.flushPending
                        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_events)
                    },
                8,
            )
            if (!s.events)
                add(
                    parent,
                    label(
                        text(if (s.local) R.string.events_local else R.string.events_disabled),
                        12f,
                        false,
                        color(R.color.cafe_muted),
                    ),
                    6,
                )
            keyValue(
                parent,
                text(R.string.last_track),
                s.lastTrack ?: text(R.string.no_operation),
                success = s.lastTrack == "ACCEPTED",
            )
            add(
                parent,
                label(text(R.string.accepted_hint), 11f, false, color(R.color.cafe_muted)),
                4,
            )
            keyValue(
                parent,
                text(R.string.last_flush),
                s.lastFlush ?: text(R.string.no_operation),
                success = s.lastFlush == "ALL_DELIVERED",
            )
            add(
                parent,
                label(text(R.string.delivery_hint), 11f, false, color(R.color.cafe_muted)),
                4,
            )
            line(parent)
            title(parent, text(R.string.recent_activity))
            s.history.takeLast(3).reversed().forEach { entry ->
                val r = row()
                r.addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_clock)
                        imageTintList = ColorStateList.valueOf(color(R.color.cafe_muted))
                    },
                    LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) },
                )
                r.addView(
                    label(entry.time, 12f, false, color(R.color.cafe_muted)).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                    },
                    LinearLayout.LayoutParams(dp(80), -2),
                )
                r.addView(label(entry.message, 12f), LinearLayout.LayoutParams(0, -2, 1f))
                add(parent, r, 8)
            }
            if (s.history.isEmpty())
                add(
                    parent,
                    label(text(R.string.no_activity), 12f, false, color(R.color.cafe_muted)),
                    8,
                )
            title(parent, text(R.string.diagnostics))
            keyValue(parent, "OpenFeature status", session.providerStatus())
            keyValue(parent, "FeatBit connection", info?.status?.name ?: "—")
            keyValue(
                parent,
                text(R.string.pause_reasons),
                info?.pauseReasons?.joinToString { it.name }?.ifEmpty { "None" } ?: "—",
            )
            keyValue(parent, text(R.string.last_success), time(info?.lastSuccessAtMillis))
            keyValue(parent, text(R.string.last_failure), time(info?.lastFailureAtMillis))
            keyValue(parent, text(R.string.failure), info?.failure?.code ?: "—")
            keyValue(parent, text(R.string.recovery), info?.recovery?.name ?: "—")
            keyValue(parent, text(R.string.candidate_failure), info?.candidateFailure?.code ?: "—")
            keyValue(parent, text(R.string.implementation), "Kotlin · ${session.sdkVersion()}")
            add(parent, button(text(R.string.open_connection), true) { openConnection() }, 16)
            if (s.history.size > 3)
                add(
                    parent,
                    button(text(R.string.all_activity), true) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.recent_activity)
                            .setMessage(
                                s.history.reversed().joinToString("\n") {
                                    "${it.time}  ${it.message}"
                                }
                            )
                            .setPositiveButton(R.string.close, null)
                            .show()
                    },
                    8,
                )
        }

    private fun time(value: Long?) =
        value?.let { java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(it)) }
            ?: "—"
}
