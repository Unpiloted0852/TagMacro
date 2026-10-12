package dev.tagmacro

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/**
 * A LinearLayout that spaces its children evenly. Children added without layout params get
 * full width and a top gap (vertical) or wrap content and an end gap (horizontal).
 */
class Stack(ctx: Context, vertical: Boolean, private val gap: Int) : LinearLayout(ctx) {
    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        if (!vertical) gravity = Gravity.CENTER_VERTICAL
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        if (orientation == VERTICAL) {
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap }
        } else {
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = gap }
        }
}

/** The app's colour roles, for light and dark mode. */
class Palette(night: Boolean) {
    private fun c(light: String, dark: String, n: Boolean) = Color.parseColor(if (n) dark else light)

    val background = c("#F3F6F5", "#0E1412", night)
    val surface = c("#FFFFFF", "#18211F", night)
    val surfaceVariant = c("#E7EEEB", "#23302C", night)
    val onSurface = c("#151D1A", "#E1E9E6", night)
    val subtle = c("#56635E", "#A3B3AD", night)
    val outline = c("#D4DDD9", "#33413C", night)
    val primary = c("#1F6B58", "#7FD0B9", night)
    val onPrimary = c("#FFFFFF", "#00382B", night)
    val primaryContainer = c("#CFEDE3", "#1D4D41", night)
    val onPrimaryContainer = c("#06382C", "#C2EFE1", night)
    val danger = c("#B3261E", "#FFB4AB", night)
    val dangerContainer = c("#FBE3E0", "#5A1D18", night)
    val onDangerContainer = c("#5F1410", "#FFDAD5", night)
    val success = c("#2E7D4F", "#86D7A2", night)
    val warning = c("#9A6200", "#F2C26B", night)
}

/** Builds views in code so the whole UI is readable in one place and needs no libraries. */
class Ui(val ctx: Context) {
    val isNight: Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val p = Palette(isNight)

    // Names kept from earlier versions.
    val accent: Int get() = p.primary
    val danger: Int get() = p.danger
    val subtle: Int get() = p.subtle
    val line: Int get() = p.outline
    val cardFill: Int get() = p.surface

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * ctx.resources.displayMetrics.density

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h)

    fun rounded(fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dpf(radiusDp)
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    /** A background with a ripple on touch. */
    fun pressable(fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Int = 0): Drawable {
        val ripple = (p.onSurface and 0x00FFFFFF) or 0x22000000
        val shape = rounded(fill, radiusDp, strokeColor, strokeDp)
        val mask = rounded(Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(ripple), shape, mask)
    }

    // ------------------------------------------------------------------ layout

    fun column(padding: Int = 0, gap: Int = 8): LinearLayout = Stack(ctx, true, dp(gap)).apply {
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun row(vararg views: View): LinearLayout = Stack(ctx, false, dp(8)).apply {
        views.forEach { addView(it) }
    }

    /** An outlined surface card. */
    fun card(): LinearLayout = column(16, 6).apply {
        background = rounded(p.surface, 20f, p.outline, 1)
        layoutParams = lp().apply { topMargin = dp(12) }
    }

    /** A card with a title (and optional subtitle) at the top. */
    fun sectionCard(title: String, subtitle: String? = null): LinearLayout = card().apply {
        addView(text(title, 17f).apply { typeface = medium; layoutParams = lp() })
        if (subtitle != null) addView(note(subtitle))
    }

    fun weighted(v: View): View = v.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
    }

    fun spacer(heightDp: Int): View = View(ctx).apply { layoutParams = lp(h = dp(heightDp)) }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(p.outline)
        layoutParams = lp(h = dp(1)).apply { topMargin = dp(8); bottomMargin = dp(4) }
    }

    // ------------------------------------------------------------------ text

    fun heading(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(p.onSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = medium
        layoutParams = lp().apply { topMargin = dp(4); bottomMargin = dp(2) }
    }

    /** Small coloured label that introduces a group. */
    fun section(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = medium
        letterSpacing = 0.02f
        setTextColor(p.primary)
        layoutParams = lp().apply { topMargin = dp(24); bottomMargin = dp(0) }
    }

    fun text(text: String, sizeSp: Float = 15f): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(p.onSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setLineSpacing(0f, 1.15f)
        layoutParams = lp().apply { topMargin = dp(2) }
    }

    fun title(text: String): TextView = text(text, 16f).apply { typeface = medium }

    fun note(text: String): TextView = text(text, 13f).apply { setTextColor(p.subtle) }

    fun mono(text: String): TextView = text(text, 12f).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    /** Monospaced text in a tinted box, for tag dumps and log details. */
    fun codeBlock(text: String): TextView = mono(text).apply {
        background = rounded(p.surfaceVariant, 12f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = lp().apply { topMargin = dp(8) }
    }

    /** A small rounded label. */
    fun chip(text: String, fill: Int = p.surfaceVariant, fg: Int = p.onSurface): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = medium
        setTextColor(fg)
        background = rounded(fill, 8f)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Coloured dot plus text, for permission and run status. */
    fun status(text: String, ok: Boolean): LinearLayout {
        val dot = View(ctx).apply {
            background = rounded(if (ok) p.success else p.subtle, 5f)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
        }
        val t = text(text, 13f).apply {
            setTextColor(if (ok) p.success else p.subtle)
            typeface = medium
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return row(dot, t)
    }

    /** Circle with a number or symbol, for numbering steps. */
    fun badge(label: String, fill: Int = p.primaryContainer, fg: Int = p.onPrimaryContainer, sizeDp: Int = 28): TextView =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sizeDp >= 40) 20f else 13f)
            typeface = medium
            setTextColor(fg)
            background = rounded(fill, sizeDp / 2f)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    /** Circle with an icon. */
    fun iconBadge(kind: String, fill: Int = p.primaryContainer, fg: Int = p.onPrimaryContainer, sizeDp: Int = 40): View =
        FrameLayout(ctx).apply {
            background = rounded(fill, sizeDp / 2f)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            addView(ImageView(ctx).apply { setImageDrawable(Glyph(kind, fg)) },
                FrameLayout.LayoutParams(dp(sizeDp / 2 + 2), dp(sizeDp / 2 + 2), Gravity.CENTER))
        }

    // ------------------------------------------------------------------ buttons

    private fun baseButton(text: String, onClick: () -> Unit): Button = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        typeface = medium
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setOnClickListener { onClick() }
    }

    /** Filled for the main action on a screen; tonal otherwise. */
    fun button(text: String, primary: Boolean = false, onClick: () -> Unit): Button = baseButton(text, onClick).apply {
        if (primary) {
            background = pressable(p.primary, 24f)
            setTextColor(p.onPrimary)
        } else {
            background = pressable(p.surfaceVariant, 24f)
            setTextColor(p.onSurface)
        }
    }

    /** Low-emphasis action with no background. */
    fun textButton(text: String, onClick: () -> Unit): Button = baseButton(text, onClick).apply {
        background = pressable(Color.TRANSPARENT, 24f)
        setTextColor(p.primary)
    }

    fun dangerButton(text: String, onClick: () -> Unit): Button = baseButton(text, onClick).apply {
        background = pressable(Color.TRANSPARENT, 24f, p.danger, 1)
        setTextColor(p.danger)
    }

    /** Button with an icon before its label. */
    fun iconTextButton(kind: String, text: String, primary: Boolean = false, onClick: () -> Unit): Button =
        button(text, primary, onClick).apply {
            val g = Glyph(kind, if (primary) p.onPrimary else p.onSurface)
            g.setBounds(0, 0, dp(20), dp(20))
            setCompoundDrawablesRelative(g, null, null, null)
            compoundDrawablePadding = dp(8)
        }

    /** Round icon-only button. */
    fun iconButton(kind: String, description: String, tint: Int = p.subtle, onClick: () -> Unit): View =
        FrameLayout(ctx).apply {
            contentDescription = description
            isClickable = true
            isFocusable = true
            background = pressable(Color.TRANSPARENT, 20f)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            addView(ImageView(ctx).apply { setImageDrawable(Glyph(kind, tint)) },
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { onClick() }
            if (Build.VERSION.SDK_INT >= 26) tooltipText = description
        }

    // ------------------------------------------------------------------ inputs

    fun edit(
        value: String, hint: String = "", number: Boolean = false, decimal: Boolean = false,
        multiline: Boolean = false, onChange: ((String) -> Unit)? = null
    ): EditText = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        setTextColor(p.onSurface)
        setHintTextColor(p.subtle)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        inputType = when {
            number && decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            number -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
        } else {
            maxLines = 1
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(p.surface, 12f, p.primary, 2))
            addState(intArrayOf(), rounded(p.surface, 12f, p.outline, 1))
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = lp().apply { topMargin = dp(6) }
        if (onChange != null) {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    onChange(s?.toString() ?: "")
                }
            })
        }
    }

    /** A label above a field. */
    fun label(text: String): TextView = text(text, 13f).apply {
        typeface = medium
        setTextColor(p.subtle)
        layoutParams = lp().apply { topMargin = dp(12) }
    }

    fun check(text: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox = CheckBox(ctx).apply {
        this.text = text
        isChecked = checked
        setTextColor(p.onSurface)
        buttonTintList = ColorStateList.valueOf(p.primary)
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun tintedSwitch(checked: Boolean): Switch = Switch(ctx).apply {
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(p.primary, if (isNight) Color.parseColor("#8B9894") else Color.WHITE))
        trackTintList = ColorStateList(states, intArrayOf(p.primaryContainer, p.outline))
    }

    /** A switch on its own, for compact rows. */
    fun switch(checked: Boolean, description: String, onChange: (Boolean) -> Unit): Switch =
        tintedSwitch(checked).apply {
            contentDescription = description
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }

    /** A settings row: label (and optional explanation) on the left, a switch on the right. */
    fun toggle(text: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit): View {
        val sw = tintedSwitch(checked)
        val labels = column(0, 2)
        labels.addView(text(text, 15f).apply { layoutParams = lp() })
        if (description != null) labels.addView(note(description))
        labels.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            background = pressable(Color.TRANSPARENT, 12f)
            setPadding(0, dp(4), 0, dp(4))
            layoutParams = lp().apply { topMargin = dp(2) }
            isClickable = true
            setOnClickListener { sw.isChecked = !sw.isChecked }
        }
        sw.contentDescription = text
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        r.addView(labels)
        r.addView(sw)
        return r
    }

    /** Radio buttons for a fixed set of options. */
    fun radios(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): RadioGroup {
        val group = RadioGroup(ctx)
        group.orientation = RadioGroup.VERTICAL
        options.forEach { (value, label) ->
            val rb = RadioButton(ctx)
            rb.id = View.generateViewId()
            rb.text = label
            rb.tag = value
            rb.setTextColor(p.onSurface)
            rb.buttonTintList = ColorStateList.valueOf(p.primary)
            rb.minHeight = dp(44)
            group.addView(rb)
            if (value == selected) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { g, id ->
            val v = g.findViewById<RadioButton>(id)?.tag as? String
            if (v != null) onSelect(v)
        }
        return group
    }

    /** Large selectable cards, one per option: (value, title, explanation). */
    fun choiceCards(options: List<Triple<String, String, String>>, selected: String, onSelect: (String) -> Unit): LinearLayout {
        val col = column(0, 8)
        for ((value, title, desc) in options) {
            val on = value == selected
            val item = column(14, 2).apply {
                background = if (on) pressable(p.primaryContainer, 16f, p.primary, 2)
                else pressable(p.surface, 16f, p.outline, 1)
                isClickable = true
                setOnClickListener { if (!on) onSelect(value) }
                contentDescription = "$title. $desc" + if (on) ". Selected" else ""
            }
            val head = row(
                Glyph.view(ctx, if (on) "radio_on" else "radio_off", if (on) p.primary else p.subtle, dp(20)),
                text(title, 15f).apply {
                    typeface = medium
                    setTextColor(if (on) p.onPrimaryContainer else p.onSurface)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            item.addView(head)
            item.addView(note(desc).apply {
                setTextColor(if (on) p.onPrimaryContainer else p.subtle)
                setPadding(dp(28), 0, 0, 0)
            })
            col.addView(item)
        }
        return col
    }

    /** Pill-shaped segmented control. */
    fun segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): LinearLayout {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.surfaceVariant, 22f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = lp().apply { topMargin = dp(8) }
        }
        for ((value, label) in options) {
            val on = value == selected
            val b = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                typeface = medium
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (on) p.onPrimary else p.onSurface)
                background = if (on) pressable(p.primary, 18f) else pressable(Color.TRANSPARENT, 18f)
                minHeight = dp(40)
                isClickable = true
                setOnClickListener { if (!on) onSelect(value) }
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            }
            bar.addView(b)
        }
        return bar
    }

    /** A row of toggleable chips, e.g. days of the week. */
    fun toggleChips(labels: List<String>, isOn: (Int) -> Boolean, onChange: (Int, Boolean) -> Unit): LinearLayout {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp().apply { topMargin = dp(8) }
        }
        labels.forEachIndexed { i, label ->
            var on = isOn(i)
            val t = TextView(ctx)
            fun paint() {
                t.setTextColor(if (on) p.onPrimary else p.onSurface)
                t.background = if (on) pressable(p.primary, 18f) else pressable(p.surface, 18f, p.outline, 1)
                t.contentDescription = label + if (on) ", on" else ", off"
            }
            t.text = label
            t.gravity = Gravity.CENTER
            t.typeface = medium
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            t.isClickable = true
            t.setOnClickListener {
                on = !on
                paint()
                onChange(i, on)
            }
            paint()
            t.layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginEnd = if (i < labels.size - 1) dp(4) else 0
            }
            bar.addView(t)
        }
        return bar
    }

    /**
     * A tappable list row: optional leading view, title, subtitle, optional trailing view.
     * Used in pickers and lists.
     */
    fun listRow(
        title: String, subtitle: String? = null, leading: View? = null, trailing: View? = null,
        highlight: Boolean = false, onClick: (() -> Unit)? = null
    ): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = if (highlight) pressable(p.primaryContainer, 16f, p.primary, 2) else pressable(Color.TRANSPARENT, 16f)
            layoutParams = lp().apply { topMargin = dp(4) }
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        if (leading != null) {
            (leading.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(14)
            r.addView(leading)
        }
        val texts = column(0, 2)
        texts.addView(text(title, 15f).apply {
            typeface = medium
            if (highlight) setTextColor(p.onPrimaryContainer)
            layoutParams = lp()
        })
        if (subtitle != null) texts.addView(note(subtitle).apply { if (highlight) setTextColor(p.onPrimaryContainer) })
        texts.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        r.addView(texts)
        if (trailing != null) r.addView(trailing)
        return r
    }

    /** A thin bar showing how full something is (0..1). Turns red when over 1. */
    fun meter(fraction: Float): View {
        val track = FrameLayout(ctx).apply {
            background = rounded(p.surfaceVariant, 4f)
            layoutParams = lp(h = dp(8)).apply { topMargin = dp(8) }
        }
        val fill = View(ctx).apply { background = rounded(if (fraction > 1f) p.danger else p.primary, 4f) }
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        track.post {
            fill.layoutParams = FrameLayout.LayoutParams(
                (track.width * fraction.coerceIn(0.02f, 1f)).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return track
    }
}

/**
 * Simple line icons drawn in code on a 24×24 grid, so the app ships no icon library.
 */
class Glyph(private val kind: String, color: Int) : Drawable() {

    companion object {
        fun view(ctx: Context, kind: String, color: Int, sizePx: Int): ImageView = ImageView(ctx).apply {
            setImageDrawable(Glyph(kind, color))
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { marginEnd = sizePx / 2 }
        }
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    override fun getIntrinsicWidth() = 72
    override fun getIntrinsicHeight() = 72

    private fun Canvas.poly(vararg pts: Float, close: Boolean = false, paint: Paint = stroke) {
        val path = Path()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            path.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        if (close) path.close()
        drawPath(path, paint)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        with(canvas) {
            when (kind) {
                "bolt" -> poly(13f, 2f, 4f, 14f, 11f, 14f, 10f, 22f, 20f, 10f, 13f, 10f, close = true, paint = fill)
                "tag" -> {
                    drawRoundRect(RectF(3f, 5f, 21f, 19f), 3f, 3f, stroke)
                    drawCircle(8f, 12f, 1.6f, fill)
                    drawArc(RectF(8f, 8f, 16f, 16f), -55f, 110f, false, stroke)
                    drawArc(RectF(6f, 6f, 20f, 18f), -50f, 100f, false, stroke)
                }
                "clock" -> {
                    drawCircle(12f, 12f, 9f, stroke)
                    poly(12f, 7f, 12f, 12f, 15.5f, 14f)
                }
                "sliders" -> {
                    poly(4f, 7f, 20f, 7f); poly(4f, 12f, 20f, 12f); poly(4f, 17f, 20f, 17f)
                    drawCircle(9f, 7f, 2.4f, fill); drawCircle(15f, 12f, 2.4f, fill); drawCircle(8f, 17f, 2.4f, fill)
                }
                "back" -> { poly(19f, 12f, 5f, 12f); poly(11f, 6f, 5f, 12f, 11f, 18f) }
                "plus" -> { poly(12f, 5f, 12f, 19f); poly(5f, 12f, 19f, 12f) }
                "play" -> poly(8f, 5f, 19f, 12f, 8f, 19f, close = true, paint = fill)
                "up" -> poly(6f, 15f, 12f, 9f, 18f, 15f)
                "down" -> poly(6f, 9f, 12f, 15f, 18f, 9f)
                "chevron" -> poly(9f, 6f, 15f, 12f, 9f, 18f)
                "check" -> poly(5f, 12.5f, 10f, 17f, 19f, 7f)
                "trash" -> {
                    poly(4f, 7f, 20f, 7f); poly(10f, 4f, 14f, 4f)
                    drawRoundRect(RectF(6.5f, 7f, 17.5f, 20f), 2f, 2f, stroke)
                    poly(10f, 11f, 10f, 16f); poly(14f, 11f, 14f, 16f)
                }
                "copy" -> {
                    drawRoundRect(RectF(8f, 8f, 20f, 20f), 2f, 2f, stroke)
                    poly(5f, 16f, 4f, 16f, 4f, 4f, 16f, 4f, 16f, 5f)
                }
                "edit" -> {
                    poly(4f, 20f, 8f, 19f, 19f, 8f, 16f, 5f, 5f, 16f, close = true)
                    poly(14f, 7f, 17f, 10f)
                }
                "heart" -> {
                    val path = Path()
                    path.moveTo(12f, 20.5f)
                    path.cubicTo(5f, 15.5f, 2.5f, 12.5f, 2.5f, 8.8f)
                    path.cubicTo(2.5f, 6f, 4.7f, 3.8f, 7.4f, 3.8f)
                    path.cubicTo(9.3f, 3.8f, 11f, 4.9f, 12f, 6.6f)
                    path.cubicTo(13f, 4.9f, 14.7f, 3.8f, 16.6f, 3.8f)
                    path.cubicTo(19.3f, 3.8f, 21.5f, 6f, 21.5f, 8.8f)
                    path.cubicTo(21.5f, 12.5f, 19f, 15.5f, 12f, 20.5f)
                    path.close()
                    drawPath(path, fill)
                }
                "code" -> { poly(8f, 7f, 3f, 12f, 8f, 17f); poly(16f, 7f, 21f, 12f, 16f, 17f); poly(13.5f, 5f, 10.5f, 19f) }
                "info" -> {
                    drawCircle(12f, 12f, 9f, stroke)
                    poly(12f, 11f, 12f, 16.5f)
                    drawCircle(12f, 7.8f, 1.2f, fill)
                }
                "pin" -> {
                    val path = Path()
                    path.moveTo(12f, 21f)
                    path.cubicTo(7f, 15.5f, 5f, 12.5f, 5f, 9.5f)
                    path.cubicTo(5f, 5.5f, 8f, 3f, 12f, 3f)
                    path.cubicTo(16f, 3f, 19f, 5.5f, 19f, 9.5f)
                    path.cubicTo(19f, 12.5f, 17f, 15.5f, 12f, 21f)
                    path.close()
                    drawPath(path, stroke)
                    drawCircle(12f, 9.5f, 2.4f, stroke)
                }
                "wifi" -> {
                    drawArc(RectF(2f, 5f, 22f, 25f), -135f, 90f, false, stroke)
                    drawArc(RectF(5.5f, 8.5f, 18.5f, 21.5f), -135f, 90f, false, stroke)
                    drawCircle(12f, 18f, 1.6f, fill)
                }
                "write" -> { poly(12f, 4f, 12f, 15f); poly(7f, 10f, 12f, 15f, 17f, 10f); poly(5f, 20f, 19f, 20f) }
                "radio_on" -> { drawCircle(12f, 12f, 9f, stroke); drawCircle(12f, 12f, 5f, fill) }
                "radio_off" -> drawCircle(12f, 12f, 9f, stroke)
                "home" -> poly(4f, 11f, 12f, 4f, 20f, 11f, 20f, 20f, 4f, 20f, close = true)
                "moon" -> {
                    val outer = Path().apply { addCircle(12f, 12f, 8.5f, Path.Direction.CW) }
                    val bite = Path().apply { addCircle(16.5f, 8.5f, 7f, Path.Direction.CW) }
                    outer.op(bite, Path.Op.DIFFERENCE)
                    drawPath(outer, fill)
                }
                "sun" -> {
                    drawCircle(12f, 12f, 4f, stroke)
                    for (k in 0 until 8) {
                        val a = Math.toRadians(k * 45.0)
                        val c = Math.cos(a).toFloat()
                        val sn = Math.sin(a).toFloat()
                        poly(12f + 7f * c, 12f + 7f * sn, 12f + 9.5f * c, 12f + 9.5f * sn)
                    }
                }
                "phone" -> { drawRoundRect(RectF(7f, 3f, 17f, 21f), 2.5f, 2.5f, stroke); poly(10.5f, 18f, 13.5f, 18f) }
                "message" -> {
                    drawRoundRect(RectF(3f, 4f, 21f, 16.5f), 3f, 3f, stroke)
                    poly(8f, 16.5f, 7f, 21f, 12.5f, 16.5f)
                }
                "car" -> {
                    drawRoundRect(RectF(3f, 11f, 21f, 17f), 2f, 2f, stroke)
                    poly(6f, 11f, 8f, 6.5f, 16f, 6.5f, 18f, 11f)
                    drawCircle(7.5f, 17.5f, 1.9f, fill); drawCircle(16.5f, 17.5f, 1.9f, fill)
                }
                "lock" -> {
                    drawRoundRect(RectF(5f, 10.5f, 19f, 20.5f), 2.5f, 2.5f, stroke)
                    drawArc(RectF(8f, 3.5f, 16f, 13.5f), 180f, 180f, false, stroke)
                    poly(8f, 8.5f, 8f, 10.5f); poly(16f, 8.5f, 16f, 10.5f)
                    drawCircle(12f, 15.5f, 1.4f, fill)
                }
                "link" -> {
                    drawRoundRect(RectF(3f, 8.5f, 13f, 15.5f), 3.5f, 3.5f, stroke)
                    drawRoundRect(RectF(11f, 8.5f, 21f, 15.5f), 3.5f, 3.5f, stroke)
                }
                "text" -> { poly(5f, 6f, 19f, 6f); poly(12f, 6f, 12f, 19f) }
                "card" -> {
                    drawRoundRect(RectF(3f, 5f, 21f, 19f), 2.5f, 2.5f, stroke)
                    drawCircle(9f, 10.5f, 2.2f, stroke)
                    poly(5.5f, 16f, 9f, 14f, 12.5f, 16f); poly(14.5f, 10f, 18.5f, 10f); poly(14.5f, 13.5f, 18.5f, 13.5f)
                }
                "mail" -> { drawRoundRect(RectF(3f, 5.5f, 21f, 18.5f), 2.5f, 2.5f, stroke); poly(3.5f, 7f, 12f, 13f, 20.5f, 7f) }
                "erase" -> { poly(6f, 6f, 18f, 18f); poly(18f, 6f, 6f, 18f) }
                "book" -> { drawRoundRect(RectF(5f, 3f, 19f, 21f), 2f, 2f, stroke); poly(9f, 3f, 9f, 21f) }
                "bell" -> {
                    val path = Path()
                    path.moveTo(5f, 17f)
                    path.lineTo(19f, 17f)
                    path.cubicTo(17.5f, 15.5f, 17.5f, 13f, 17.5f, 10.5f)
                    path.cubicTo(17.5f, 7f, 15f, 4.5f, 12f, 4.5f)
                    path.cubicTo(9f, 4.5f, 6.5f, 7f, 6.5f, 10.5f)
                    path.cubicTo(6.5f, 13f, 6.5f, 15.5f, 5f, 17f)
                    path.close()
                    drawPath(path, stroke)
                    poly(10.5f, 20f, 13.5f, 20f)
                }
                else -> drawCircle(12f, 12f, 4f, fill)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Draws the app behind the status and navigation bars and pads the root view so nothing is hidden.
 * Behaves the same on Android 8 through 16.
 */
@Suppress("DEPRECATION")
fun setupEdgeToEdge(activity: Activity, root: View, night: Boolean) {
    val w = activity.window
    if (Build.VERSION.SDK_INT >= 30) {
        w.setDecorFitsSystemWindows(false)
        val light = if (night) 0 else
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        w.insetsController?.setSystemBarsAppearance(
            light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
    } else {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (!night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        w.decorView.systemUiVisibility = flags
    }
    if (Build.VERSION.SDK_INT < 35) {
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
    }
    root.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
        }
        insets
    }
}
