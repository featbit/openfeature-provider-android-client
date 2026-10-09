package co.featbit.sample.kotlin

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** Small native View construction helpers; no SDK calls or business logic. */
class CafeViews(val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun color(id: Int) = ContextCompat.getColor(context, id)

    fun shape(fill: Int, radius: Int = 10, stroke: Boolean = false) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke) setStroke(dp(1), color(R.color.cafe_outline))
        }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row() =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun label(
        text: String,
        size: Float = 14f,
        bold: Boolean = false,
        ink: Int = color(R.color.cafe_text),
    ) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    fun add(parent: LinearLayout, child: View, top: Int = 0, height: Int = -2) {
        parent.addView(
            child,
            LinearLayout.LayoutParams(-1, if (height >= 0) dp(height) else height).apply {
                topMargin = dp(top)
            },
        )
    }

    fun line(parent: LinearLayout, top: Int = 16) =
        add(parent, View(context).apply { setBackgroundColor(color(R.color.cafe_outline)) }, top, 1)

    fun title(parent: LinearLayout, text: String, top: Int = 14) =
        add(parent, label(text, 16f, true), top)

    fun button(text: String, outline: Boolean = false, action: () -> Unit) =
        MaterialButton(
                context,
                null,
                if (outline) com.google.android.material.R.attr.materialButtonOutlinedStyle
                else com.google.android.material.R.attr.materialButtonStyle,
            )
            .apply {
                this.text = text
                textSize = 14f
                isAllCaps = false
                cornerRadius = dp(9)
                minHeight = dp(48)
                insetTop = 0
                insetBottom = 0
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { action() }
            }

    fun icon(drawable: Int, description: String, action: () -> Unit) =
        androidx.appcompat.widget.AppCompatImageButton(context).apply {
            setImageResource(drawable)
            imageTintList = ColorStateList.valueOf(color(R.color.cafe_primary))
            contentDescription = description
            setBackgroundResource(android.R.color.transparent)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { action() }
        }

    fun note(parent: LinearLayout, text: String, warning: Boolean = true) {
        val container =
            row().apply {
                background = shape(color(if (warning) R.color.cafe_warn_bg else R.color.cafe_panel))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        if (warning)
            container.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_warning)
                    imageTintList = ColorStateList.valueOf(color(R.color.cafe_warn))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) },
            )
        container.addView(
            label(text, 13f, false, color(if (warning) R.color.cafe_warn else R.color.cafe_muted)),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        add(parent, container, 12)
    }

    fun keyValue(
        parent: LinearLayout,
        key: String,
        value: String,
        strong: Boolean = false,
        glyph: Int = 0,
        success: Boolean = false,
    ) {
        val row = row()
        if (glyph != 0)
            row.addView(
                ImageView(context).apply {
                    setImageResource(glyph)
                    imageTintList = ColorStateList.valueOf(color(R.color.cafe_text))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(16) },
            )
        row.addView(
            label(
                key,
                if (strong) 16f else 13f,
                strong,
                color(if (strong) R.color.cafe_text else R.color.cafe_muted),
            ),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        row.addView(
            label(
                    value,
                    if (strong) 22f else 13f,
                    strong,
                    color(if (success) R.color.cafe_good else R.color.cafe_text),
                )
                .apply {
                    gravity = Gravity.START
                    setTextIsSelectable(true)
                },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        add(parent, row, 8)
    }

    /** Display the approved concept's original photo region, without regenerating its artwork. */
    fun coffee(): android.graphics.Bitmap =
        photo
            ?: context.assets.open("cafe-reference.png").use { stream ->
                @Suppress("DEPRECATION")
                val decoder = android.graphics.BitmapRegionDecoder.newInstance(stream, false)!!
                decoder
                    .decodeRegion(
                        android.graphics.Rect(83, 411, 480, 598),
                        android.graphics.BitmapFactory.Options(),
                    )
                    .also {
                        decoder.recycle()
                        photo = it
                    }
            }

    companion object {
        private var photo: android.graphics.Bitmap? = null
    }
}
