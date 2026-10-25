package com.example.decklink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var hid: HidController
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var dot: View
    private var overlay: View? = null
    private var askedForPermissions = false
    private var repeater: Runnable? = null
    private var repeaterView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.statusBarColor = BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = BG
        hid = HidController(this).apply {
            onStatus = { message -> runOnUiThread { if (::status.isInitialized) status.text = message } }
            onLink = { linked -> runOnUiThread { updateDot(linked) } }
        }
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        ensureBluetooth()
    }

    override fun onResume() {
        super.onResume()
        ensureBluetooth()
    }

    override fun onDestroy() {
        stopRepeat()
        hid.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay != null) hideController() else super.onBackPressed()
    }

    /* ------------------------------------------------------------ behörighet */

    private fun ensureBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = REQUIRED_PERMISSIONS.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                if (!askedForPermissions) {
                    askedForPermissions = true
                    requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
                } else if (::status.isInitialized) {
                    status.text = "Bluetooth-behörighet saknas – ge den i appens inställningar"
                }
                return
            }
        }
        hid.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH) ensureBluetooth()
    }

    /* --------------------------------------------------------------- skärmen */

    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("DeckLink", 28f, TEXT, medium = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        dot = View(this).apply { background = circle(DOT_OFF) }
        header.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        column.addView(header)

        column.addView(text("Bluetooth-kontroll för MediaLibrary", 13f, MUTED).apply {
            setPadding(0, dp(3), 0, dp(16))
        })

        status = text("Startar…", 13f, TEXT).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(SURFACE, dp(14))
        }
        column.addView(status)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(pill("Aktivera", primary = true) { hid.start() }, weighted(dp(46)))
        actions.addView(pill("Synlig") { hid.makeDiscoverable() }, weighted(dp(46), start = dp(8)))
        actions.addView(pill("Anslut") { hid.connectToHost() }, weighted(dp(46), start = dp(8)))
        column.addView(actions)

        val pad = TouchpadView(this).apply {
            onMove = { dx, dy -> hid.mouse(dx, dy) }
            onClick = {
                hid.mouse(0, 0, buttons = 1)
                postDelayed({ hid.mouse(0, 0, buttons = 0) }, 45)
            }
            onScroll = { steps -> hid.mouse(0, 0, wheel = -steps) }
        }
        column.addView(pad, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })

        column.addView(text("Tryck = klick · två fingrar = scroll", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(14))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(SURFACE, dp(18))
            isClickable = true
            setOnClickListener { showController() }
        }
        val cardTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cardTitle.addView(text("Spelkontroll", 17f, TEXT, medium = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        cardTitle.addView(text("›", 24f, ACCENT, medium = true))
        card.addView(cardTitle)
        card.addView(text("D-pad, A och B – plus paus, spolning och helskärm", 12.5f, MUTED).apply {
            setPadding(0, dp(3), 0, 0)
        })
        column.addView(card)

        val keyboardCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(SURFACE, dp(18))
            isClickable = true
            setOnClickListener { showKeyboard() }
        }
        val keyboardTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keyboardTitle.addView(text("Tangentbord", 17f, TEXT, medium = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        keyboardTitle.addView(text("›", 24f, ACCENT, medium = true))
        keyboardCard.addView(keyboardTitle)
        keyboardCard.addView(text("Skriv i sökfält, magnetlänkar och inställningar", 12.5f, MUTED).apply {
            setPadding(0, dp(3), 0, 0)
        })
        column.addView(keyboardCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        column.addView(text(
            "Första gången: tryck Synlig och parkoppla DeckLink i Windows Bluetooth.",
            11.5f, MUTED,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })

        root.addView(column)
        return root
    }

    /* ------------------------------------------------------------ spelkontroll */

    private fun showController() {
        if (overlay != null) return
        val layer = FrameLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            isClickable = true
            setOnClickListener { hideController() }
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(18))
            background = rounded(SURFACE, dp(26))
            isClickable = true
        }

        sheet.addView(View(this).apply { background = rounded(0xFF3A3A48.toInt(), dp(3)) },
            LinearLayout.LayoutParams(dp(44), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            })

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(text("Spelkontroll", 20f, TEXT, medium = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        titleRow.addView(text("✕", 15f, MUTED, medium = true).apply {
            gravity = Gravity.CENTER
            background = circle(0xFF2A2A36.toInt())
            isClickable = true
            setOnClickListener { hideController() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        sheet.addView(titleRow)

        sheet.addView(text("Styr menyn med pilarna, A väljer och B går tillbaka.", 12.5f, MUTED).apply {
            setPadding(0, dp(2), 0, dp(14))
        })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dpad = GridLayout(this).apply { columnCount = 3; rowCount = 3 }
        dpad.addView(key("▲", HidController.KEY_UP), dpadCell(0, 1))
        dpad.addView(key("◀", HidController.KEY_LEFT), dpadCell(1, 0))
        dpad.addView(View(this).apply { background = circle(0xFF20202B.toInt()) },
            dpadCell(1, 1).apply { width = dp(34); height = dp(34) })
        dpad.addView(key("▶", HidController.KEY_RIGHT), dpadCell(1, 2))
        dpad.addView(key("▼", HidController.KEY_DOWN), dpadCell(2, 1))
        body.addView(dpad, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val ab = FrameLayout(this)
        ab.addView(
            roundKey("A", HidController.KEY_ENTER, accent = true),
            FrameLayout.LayoutParams(dp(82), dp(82)).apply { gravity = Gravity.TOP or Gravity.START },
        )
        ab.addView(
            roundKey("B", HidController.KEY_ESCAPE, accent = false),
            FrameLayout.LayoutParams(dp(70), dp(70)).apply { gravity = Gravity.BOTTOM or Gravity.END },
        )
        body.addView(ab, LinearLayout.LayoutParams(dp(104), dp(176)))
        sheet.addView(body)

        sheet.addView(View(this).apply { setBackgroundColor(0x1FFFFFFF) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            })

        sheet.addView(text("SPELARE", 10.5f, MUTED, medium = true).apply { letterSpacing = 0.16f })

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        seekRow.addView(key("−30", HidController.KEY_J), weighted(dp(46)))
        seekRow.addView(key("−10", HidController.KEY_LEFT), weighted(dp(46), start = dp(6)))
        seekRow.addView(key("Paus", HidController.KEY_K, primary = true), weighted(dp(46), start = dp(6)))
        seekRow.addView(key("+10", HidController.KEY_RIGHT), weighted(dp(46), start = dp(6)))
        seekRow.addView(key("+30", HidController.KEY_L), weighted(dp(46), start = dp(6)))
        sheet.addView(seekRow)

        val extraRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        extraRow.addView(key("Helskärm", HidController.KEY_F), weighted(dp(42)))
        extraRow.addView(key("Stäng spelaren", HidController.KEY_ESCAPE), weighted(dp(42), start = dp(6)))
        sheet.addView(extraRow)

        sheet.addView(text(
            "Paus och spolning fungerar när videon är öppen i MediaLibrary på datorn.",
            11f, MUTED,
        ).apply { setPadding(0, dp(10), 0, 0) })

        layer.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        sheet.translationY = dp(420).toFloat()
        sheet.animate().translationY(0f).setDuration(220).start()
        overlay = layer
    }

    /* ------------------------------------------------------------ tangentbord */

    private var kbShift = false
    private var kbHost: LinearLayout? = null

    private fun showKeyboard() {
        if (overlay != null) return
        kbShift = false
        val layer = FrameLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            isClickable = true
            setOnClickListener { hideController() }
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(16))
            background = rounded(SURFACE, dp(26))
            isClickable = true
        }

        sheet.addView(View(this).apply { background = rounded(0xFF3A3A48.toInt(), dp(3)) },
            LinearLayout.LayoutParams(dp(44), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            })

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(text("Tangentbord", 20f, TEXT, medium = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        titleRow.addView(text("✕", 15f, MUTED, medium = true).apply {
            gravity = Gravity.CENTER
            background = circle(0xFF2A2A36.toInt())
            isClickable = true
            setOnClickListener { hideController() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        sheet.addView(titleRow)

        sheet.addView(text(
            "Välj ett textfält i MediaLibrary med A, skriv här och lämna fältet med B.",
            12.5f, MUTED,
        ).apply { setPadding(0, dp(2), 0, dp(12)) })

        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(keys)
        kbHost = keys
        buildKeyboard(keys)

        sheet.addView(text(
            "Skickar riktiga tangenttryck till datorn. Å, Ä och Ö ligger på samma plats som på ett svenskt tangentbord.",
            11f, MUTED,
        ).apply { setPadding(0, dp(12), 0, 0) })

        layer.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(6), dp(6), dp(6), dp(6))
        })
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        sheet.translationY = dp(420).toFloat()
        sheet.animate().translationY(0f).setDuration(220).start()
        overlay = layer
    }

    private fun buildKeyboard(host: LinearLayout) {
        host.removeAllViews()
        for (row in KEY_ROWS) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (ch in row) line.addView(charKey(ch), kbParams(1f))
            host.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(shiftKey(), kbParams(1.4f))
        bottom.addView(actionKey("Mellanrum", HidController.KEY_SPACE, 12f), kbParams(3.6f, dp(6)))
        bottom.addView(actionKey("⌫", HidController.KEY_BACKSPACE, 20f), kbParams(1.4f, dp(6)))
        bottom.addView(actionKey("⏎", HidController.KEY_ENTER, 20f, repeat = false), kbParams(1.4f, dp(6)))
        bottom.addView(actionKey("Esc", HidController.KEY_ESCAPE, 13f, repeat = false), kbParams(1.4f, dp(6)))
        host.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun charKey(base: Char): TextView {
        val ch = if (kbShift) shifted(base) else base
        val view = text(ch.toString(), 19f, Color.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            background = rounded(SURFACE_HI, dp(12))
            minimumHeight = 0
        }
        HidController.usageFor(ch)?.let { (usage, modifiers) -> bindKey(view, usage, modifiers) }
        return view
    }

    private fun shifted(ch: Char): Char = when {
        ch.isLetter() -> ch.uppercaseChar()
        ch == '0' -> ')'
        ch in '1'..'9' -> SHIFT_DIGITS[ch - '1']
        else -> ch
    }

    private fun shiftKey(): TextView {
        val view = text("⇧", 20f, if (kbShift) Color.WHITE else TEXT, medium = true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (kbShift) ACCENT else SURFACE_HI, dp(12))
        }
        view.setOnClickListener {
            kbShift = !kbShift
            kbHost?.let { buildKeyboard(it) }
        }
        return view
    }

    private fun actionKey(label: String, usage: Int, size: Float, repeat: Boolean = true): TextView {
        val view = text(label, size, TEXT, medium = true).apply {
            gravity = Gravity.CENTER
            background = rounded(SURFACE_HI, dp(12))
            minimumHeight = 0
        }
        bindKey(view, usage, 0, repeat)
        return view
    }

    private fun kbParams(weight: Float, start: Int = 0) = LinearLayout.LayoutParams(0, dp(46), weight).apply {
        marginStart = start
    }

    private fun hideController() {
        stopRepeat()
        kbHost = null
        val layer = overlay ?: return
        overlay = null
        layer.animate().alpha(0f).setDuration(140).withEndAction { root.removeView(layer) }.start()
    }

    /* --------------------------------------------------------------- widgets */

    private fun key(label: String, usage: Int, primary: Boolean = false): TextView {
        val compact = label.length > 2
        val view = text(label, if (compact) 13.5f else 19f, Color.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (primary) ACCENT else SURFACE_HI, dp(if (compact) 13 else 18))
            minHeight = 0
            minimumHeight = 0
        }
        bindKey(view, usage)
        return view
    }

    private fun roundKey(label: String, usage: Int, accent: Boolean): TextView {
        val view = text(label, 24f, if (accent) Color.WHITE else TEXT, medium = true).apply {
            gravity = Gravity.CENTER
            background = circle(if (accent) ACCENT else SURFACE_HI)
        }
        bindKey(view, usage)
        return view
    }

    /** Knapp som skickar en tangent och repeterar när den hålls nere. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindKey(view: View, usage: Int, modifiers: Int = 0, repeat: Boolean = true) {
        view.setOnTouchListener { pressed, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).start()
                    hid.tap(usage, modifiers)
                    if (repeat) startRepeat(pressed, usage, modifiers)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressed.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    stopRepeat()
                }
            }
            true
        }
    }

    private fun startRepeat(view: View, usage: Int, modifiers: Int = 0) {
        stopRepeat()
        val runner = object : Runnable {
            override fun run() {
                hid.tap(usage, modifiers)
                view.postDelayed(this, 95)
            }
        }
        repeater = runner
        repeaterView = view
        view.postDelayed(runner, 380)
    }

    private fun stopRepeat() {
        repeater?.let { runner -> repeaterView?.removeCallbacks(runner) }
        repeater = null
        repeaterView = null
    }

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 13.5f, if (primary) Color.WHITE else TEXT, medium = true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (primary) ACCENT else SURFACE_HI, dp(14))
            setOnClickListener { onClick() }
        }

    private fun text(value: String, size: Float, color: Int, medium: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun updateDot(linked: Boolean) {
        dot.background = circle(if (linked) OK else DOT_OFF)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dpadCell(row: Int, column: Int) = GridLayout.LayoutParams(
        GridLayout.spec(row),
        GridLayout.spec(column),
    ).apply {
        width = dp(58)
        height = dp(58)
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun weighted(height: Int, start: Int = 0) = LinearLayout.LayoutParams(0, height, 1f).apply {
        marginStart = start
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val REQUEST_BLUETOOTH = 71

        /** Tangenter i tangentbordslayouten – rad för rad. */
        val KEY_ROWS = arrayOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnmåäö")
        const val SHIFT_DIGITS = "!@#$%^&*()"
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )

        val BG = 0xFF0C0C12.toInt()
        val SURFACE = 0xFF17171F.toInt()
        val SURFACE_HI = 0xFF23232E.toInt()
        val ACCENT = 0xFFFF2D55.toInt()
        val TEXT = 0xFFF3F3F7.toInt()
        val MUTED = 0xFF8E8E9A.toInt()
        val OK = 0xFF38D39F.toInt()
        val DOT_OFF = 0xFF44445A.toInt()
    }
}
