package co.featbit.sample.kotlin

import android.graphics.Typeface
import android.text.*
import android.view.*
import android.widget.*
import androidx.core.widget.NestedScrollView
import co.featbit.android.api.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** View-only forms. Drafts are owned in-process by the session, never saved to bundles. */
class CafeForms(private val activity: MainActivity) {
    private val ui
        get() = activity.ui

    private val session
        get() = activity.session

    private val state
        get() = session.state.value

    private fun text(id: Int) = activity.getString(id)

    private var sheet: BottomSheetDialog? = null
    private var kind: String? = null
    private var userAction: MaterialButton? = null
    private var progress: TextView? = null
    private var formAction: MaterialButton? = null
    private var validateEditor: (() -> Unit)? = null
    private val editorActions = mutableListOf<MaterialButton>()

    fun destroy() {
        sheet?.setOnDismissListener(null)
        sheet?.dismiss()
        sheet = null
    }

    fun update(s: ScreenState) {
        formAction?.isEnabled = s.busy == null && !s.flushPending
        userAction?.isEnabled = s.available && !s.flushPending && session.userChoice != s.user
        progress?.text =
            s.busy
                ?: if (s.waitTimedOut) text(R.string.identity_timeout)
                else text(if (s.local) R.string.no_targeting else R.string.remote_targeting)
        editorActions.forEach { it.isEnabled = s.available }
        validateEditor?.invoke()
        if (session.editorKey == null && kind == "editor") sheet?.dismiss()
        if (!session.userSheet && kind == "users") sheet?.dismiss()
        if (session.userSheet && sheet == null) showUsers()
        else if (session.editorKey != null && sheet == null) showEditor(session.editorKey!!)
    }

    private fun field(
        parent: LinearLayout,
        hint: String,
        value: String,
        secret: Boolean = false,
        multiline: Boolean = false,
        changed: (String) -> Unit,
    ): TextInputLayout =
        with(ui) {
            val wrapper =
                TextInputLayout(
                        activity,
                        null,
                        com.google.android.material.R.attr.textInputOutlinedStyle,
                    )
                    .apply {
                        this.hint = hint
                        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                        if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                    }
            val input =
                TextInputEditText(wrapper.context).apply {
                    setText(value)
                    textSize = 14f
                    isSaveEnabled = false
                    inputType =
                        when {
                            secret ->
                                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            multiline ->
                                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        }
                    if (multiline) {
                        minLines = 5
                        gravity = Gravity.TOP
                    } else maxLines = 1
                    addTextChangedListener(
                        watcher {
                            changed(it)
                            wrapper.error = null
                        }
                    )
                }
            wrapper.addView(input)
            if (secret) {
                wrapper.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                wrapper.isEndIconVisible = true
                input.typeface = Typeface.DEFAULT
            }
            add(parent, wrapper, 12)
            wrapper
        }

    private fun watcher(changed: (String) -> Unit) =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                changed(s.toString())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        }

    fun connection(parent: LinearLayout) =
        with(ui) {
            val d = session.draft
            val mode =
                com.google.android.material.button.MaterialButtonToggleGroup(activity).apply {
                    isSingleSelection = true
                    isSelectionRequired = true
                }
            listOf(true, false).forEach { local ->
                mode.addView(
                    button(
                            text(if (local) R.string.local_demo else R.string.live_connection),
                            d.local != local,
                        ) {
                            d.local = local
                            activity.navigate()
                        }
                        .apply {
                            id = View.generateViewId()
                            isCheckable = true
                            isChecked = d.local == local
                            minWidth = 0
                            minimumWidth = 0
                        },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            }
            add(parent, mode, 8)
            val fields = mutableMapOf<String, TextInputLayout>()
            if (d.local) note(parent, text(R.string.local_explanation), false)
            else {
                fields["sdkKey"] =
                    field(parent, text(R.string.client_key), d.key, secret = true) { d.key = it }
                title(parent, text(R.string.data_mode))
                val radios = RadioGroup(activity)
                listOf(SyncMode.STREAMING, SyncMode.POLLING).forEach { transport ->
                    radios.addView(
                        androidx.appcompat.widget.AppCompatRadioButton(activity).apply {
                            text =
                                transport.name.lowercase().replaceFirstChar { it.uppercase() } +
                                    "\n" +
                                    if (transport == SyncMode.STREAMING)
                                        "Real-time updates over WebSocket"
                                    else "Fetch flags at regular intervals"
                            textSize = 13f
                            minHeight = dp(58)
                            isChecked = d.mode == transport
                            setOnClickListener {
                                d.mode = transport
                                activity.navigate()
                            }
                        }
                    )
                }
                add(parent, radios, 4)
                if (d.mode == SyncMode.STREAMING) {
                    fields["streamingUrl"] =
                        field(parent, text(R.string.streaming_url), d.streaming) {
                                d.streaming = it
                            }
                            .apply { placeholderText = "wss://evaluation.example.com" }
                    add(
                        parent,
                        MaterialSwitch(activity).apply {
                            text = text(R.string.polling_fallback)
                            minHeight = dp(56)
                            isChecked = d.pollingFallback
                            setOnCheckedChangeListener { _, checked ->
                                d.pollingFallback = checked
                                activity.navigate()
                            }
                        },
                        8,
                    )
                    add(
                        parent,
                        label(
                            text(
                                if (d.pollingFallback) R.string.polling_fallback_enabled_help
                                else R.string.polling_fallback_help
                            ),
                            12f,
                            false,
                            color(R.color.cafe_muted),
                        ),
                    )
                }
                if (d.mode == SyncMode.POLLING || d.pollingFallback) {
                    fields["pollingUrl"] =
                        field(parent, text(R.string.polling_url), d.polling) { d.polling = it }
                            .apply { placeholderText = "https://evaluation.example.com" }
                    if (d.mode == SyncMode.POLLING)
                        add(
                            parent,
                            label(
                                text(R.string.direct_polling_help),
                                12f,
                                false,
                                color(R.color.cafe_muted),
                            ),
                            4,
                        )
                }
                add(
                    parent,
                    MaterialSwitch(activity).apply {
                        text = text(R.string.events)
                        minHeight = dp(56)
                        isChecked = d.events
                        setOnCheckedChangeListener { _, checked ->
                            d.events = checked
                            activity.navigate()
                        }
                    },
                    16,
                )
                add(
                    parent,
                    label(text(R.string.events_helper), 12f, false, color(R.color.cafe_muted)),
                )
                if (d.events)
                    fields["eventsUrl"] =
                        field(parent, text(R.string.events_url), d.eventsUrl) { d.eventsUrl = it }
                            .apply { placeholderText = "https://events.example.com" }
                add(
                    parent,
                    label(
                        text(R.string.validate_before_close),
                        12f,
                        false,
                        color(R.color.cafe_muted),
                    ),
                    12,
                )
            }
            formAction =
                button(text(R.string.apply_reconnect)) {
                        val errors = session.validateDraft()
                        if (errors.isNotEmpty()) {
                            errors.forEach { (name, message) -> fields[name]?.error = message }
                            if (errors.keys.any { it !in fields })
                                Snackbar.make(
                                        activity.host,
                                        errors.values.first(),
                                        Snackbar.LENGTH_LONG,
                                    )
                                    .show()
                        } else {
                            session.connect()
                            session.formOpen = false
                            activity.navigate()
                        }
                    }
                    .also { add(parent, it, 22) }
            add(
                parent,
                label(text(R.string.memory_only), 12f, false, color(R.color.cafe_muted)).apply {
                    gravity = Gravity.CENTER
                },
                10,
            )
        }

    fun showUsers() =
        with(ui) {
            if (sheet != null) return
            session.userSheet = true
            val content = column().apply { setPadding(dp(24), dp(18), dp(24), dp(24)) }
            title(content, text(R.string.switch_user), 0)
            val group = RadioGroup(activity)
            session.people.forEachIndexed { index, person ->
                group.addView(
                    androidx.appcompat.widget.AppCompatRadioButton(activity).apply {
                        text = "${person.name}\n${person.key} · plan: ${person.plan}"
                        minHeight = dp(68)
                        textSize = 14f
                        isChecked = session.userChoice == index
                        id = View.generateViewId()
                        setOnClickListener {
                            session.userChoice = index
                            userAction?.isEnabled =
                                state.available && !state.flushPending && index != state.user
                        }
                    }
                )
            }
            add(content, group, 14)
            progress =
                label(
                    state.busy
                        ?: text(
                            if (state.local) R.string.no_targeting else R.string.remote_targeting
                        ),
                    13f,
                    false,
                    color(R.color.cafe_muted),
                )
            add(content, progress!!, 14)
            userAction = button(text(R.string.switch_user)) { session.identify(session.userChoice) }
            userAction!!.isEnabled =
                state.available && !state.flushPending && session.userChoice != state.user
            add(content, userAction!!, 18)
            if (!state.local)
                add(
                    content,
                    label(
                        "An admitted user change remains active if its readiness wait times out.",
                        12f,
                        false,
                        color(R.color.cafe_muted),
                    ),
                    12,
                )
            openSheet(content, "users")
        }

    private fun openSheet(content: View, type: String) {
        kind = type
        sheet =
            BottomSheetDialog(activity).apply {
                val container = ui.column()
                container.addView(
                    com.google.android.material.bottomsheet.BottomSheetDragHandleView(activity)
                )
                container.addView(content)
                setContentView(container)
                setOnDismissListener {
                    sheet = null
                    kind = null
                    userAction = null
                    progress = null
                    editorActions.clear()
                    validateEditor = null
                    if (type == "users") session.userSheet = false else session.editorKey = null
                }
                show()
                findViewById<android.widget.FrameLayout>(
                        com.google.android.material.R.id.design_bottom_sheet
                    )
                    ?.setBackgroundColor(ui.color(R.color.cafe_surface))
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
    }

    fun showEditor(key: String) =
        with(ui) {
            if (sheet != null) return
            val spec = session.specs.first { it.key == key }
            val content = column().apply { setPadding(dp(24), dp(18), dp(24), dp(24)) }
            title(content, text(R.string.edit_local_flag), 0)
            add(content, label(key, 16f, true), 16)
            add(
                content,
                label(
                    "${text(R.string.expected_type)}: ${spec.type.name}",
                    12f,
                    false,
                    color(R.color.cafe_muted),
                ),
                6,
            )
            val input: TextInputLayout?
            if (spec.type == ValueType.BOOLEAN) {
                input = null
                add(
                    content,
                    MaterialSwitch(activity).apply {
                        text = session.editorText
                        isChecked = session.editorText == "true"
                        minHeight = dp(56)
                        setOnCheckedChangeListener { _, checked ->
                            session.editorText = checked.toString()
                            text = session.editorText
                        }
                    },
                    12,
                )
            } else {
                input =
                    field(
                        content,
                        text(R.string.value),
                        session.editorText,
                        multiline = spec.type == ValueType.JSON || spec.type == ValueType.STRING,
                    ) {
                        session.editorText = it
                    }
                if (spec.type == ValueType.JSON) input.editText?.typeface = Typeface.MONOSPACE
                if (spec.type == ValueType.NUMBER)
                    input.editText?.inputType =
                        InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            val warning = label("", 12f, false, color(R.color.cafe_warn))
            add(content, warning, 12)
            if (spec.type == ValueType.NUMBER)
                add(
                    content,
                    label(
                        "Finite values can still be saved.",
                        12f,
                        false,
                        color(R.color.cafe_muted),
                    ),
                    6,
                )
            fun finished(ok: Boolean) {
                if (ok) {
                    session.editorKey = null
                    if (!activity.isDestroyed) sheet?.dismiss()
                }
            }
            val save =
                button(text(R.string.save)) {
                    if (!session.validateValue(spec, session.editorText)) {
                        input?.error = text(R.string.invalid_value)
                        return@button
                    }
                    session.edit(key, session.editorText, completed = ::finished)
                }
            fun validate() {
                val valid = session.validateValue(spec, session.editorText)
                save.isEnabled = valid && state.available
                warning.text =
                    when {
                        !valid -> text(R.string.invalid_value)
                        spec.type == ValueType.NUMBER &&
                            session.editorText.toDouble() !in 0.0..100.0 ->
                            "Outside 0–100. Preview will use 0%."
                        spec.type == ValueType.JSON && Business.menu(session.editorText) == null ->
                            text(R.string.menu_editor_warning)
                        spec.type == ValueType.JSON -> text(R.string.valid_menu)
                        spec.type == ValueType.STRING -> text(R.string.whitespace_preserved)
                        else -> ""
                    }
                val good =
                    valid &&
                        spec.type == ValueType.JSON &&
                        Business.menu(session.editorText) != null
                val neutral = spec.type == ValueType.STRING
                warning.visibility = if (warning.text.isEmpty()) View.GONE else View.VISIBLE
                warning.setTextColor(
                    color(
                        if (good) R.color.cafe_good
                        else if (neutral) R.color.cafe_muted else R.color.cafe_warn
                    )
                )
                val glyph =
                    if (neutral) null
                    else
                        androidx.core.content.ContextCompat.getDrawable(
                                activity,
                                if (good) R.drawable.ic_check else R.drawable.ic_warning,
                            )
                            ?.mutate()
                            ?.apply {
                                setTint(color(if (good) R.color.cafe_good else R.color.cafe_warn))
                            }
                warning.setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
                warning.compoundDrawablePadding = dp(8)
                warning.background =
                    if (neutral) null
                    else shape(color(if (good) R.color.cafe_good_bg else R.color.cafe_warn_bg))
                warning.setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            validateEditor = ::validate
            input?.editText?.addTextChangedListener(watcher { validate() })
            validate()
            add(content, save, 18)
            val remove =
                button(text(R.string.remove_flag), true) {
                    confirm(text(R.string.remove_flag), key) {
                        session.edit(key, completed = ::finished)
                    }
                }
            val restore =
                button(text(R.string.restore_default), true) {
                    session.edit(key, spec.initial, completed = ::finished)
                }
            remove.strokeWidth = 0
            remove.setTextColor(color(R.color.cafe_error))
            restore.strokeWidth = 0
            val secondary = row()
            secondary.addView(remove, LinearLayout.LayoutParams(0, -2, 1f))
            secondary.addView(restore, LinearLayout.LayoutParams(0, -2, 1f))
            add(content, secondary, 8)
            editorActions.addAll(listOf(save, remove, restore))
            add(
                content,
                label(text(R.string.local_only), 12f, false, color(R.color.cafe_muted)),
                12,
            )
            openSheet(NestedScrollView(activity).apply { addView(content) }, "editor")
        }

    fun confirm(title: String, message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> action() }
            .show()
    }
}
