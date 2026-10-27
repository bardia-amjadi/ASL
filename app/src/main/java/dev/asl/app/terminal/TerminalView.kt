package dev.asl.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Renders a [TerminalEmulator] and turns touch/keyboard input into terminal input. A plain View (not Compose)
 * because a correct soft-keyboard InputConnection is only practical on a View.
 */
class TerminalView(context: Context) : View(context) {
    var session: TerminalSession? = null
        set(value) {
            field?.onUpdate = null
            field = value
            scrollOffset = 0
            value?.onUpdate = { postInvalidateOnAnimation() }
            updateGrid()
            invalidate()
        }

    var modifiers: InputModifiers = InputModifiers()

    var theme: TerminalTheme = TerminalThemes.Campbell
        set(value) {
            field = value
            setBackgroundColor(value.bg)
            invalidate()
        }

    /** 0 = follow the program, 1 = block, 2 = underline, 3 = bar. */
    var cursorStyleOverride: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    // text selection (absolute row index, column)
    private var selectMode = false
    private var hasSelection = false
    private var selAnchorRow = 0
    private var selAnchorCol = 0
    private var selEndRow = 0
    private var selEndCol = 0

    var textSizePx: Float = 13f * resources.displayMetrics.scaledDensity
        set(value) {
            field = value.coerceIn(8f * resources.displayMetrics.scaledDensity, 34f * resources.displayMetrics.scaledDensity)
            updateMetrics()
            updateGrid()
            invalidate()
        }

    /** Called when the user pinch-zooms, so the size can be remembered. */
    var onTextSizeChanged: ((Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fillPaint = Paint()
    private var cellW = 10f
    private var cellH = 20f
    private var baseline = 16f
    private var scrollOffset = 0
    private var scrollRemainder = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(theme.bg)
        updateMetrics()
    }

    private fun updateMetrics() {
        paint.textSize = textSizePx
        val fm = paint.fontMetrics
        cellW = paint.measureText("M")
        cellH = ceil(fm.descent - fm.ascent + fm.leading)
        baseline = -fm.ascent
    }

    private fun updateGrid() {
        if (width <= 0 || height <= 0) return
        val cols = max(2, (width / cellW).toInt())
        val rows = max(1, (height / cellH).toInt())
        session?.attachSize(cols, rows)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGrid()
    }

    override fun onDetachedFromWindow() {
        session?.onUpdate = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session?.onUpdate = { postInvalidateOnAnimation() }
    }

    // ---- drawing --------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val s = session ?: return
        val em = s.emulator
        canvas.drawColor(theme.bg)
        synchronized(em) {
            val cols = em.cols
            val rows = em.rows
            val sb = em.scrollbackSize
            scrollOffset = scrollOffset.coerceIn(0, sb)
            val firstAbs = sb - scrollOffset
            for (y in 0 until rows) {
                val row = em.rowAt(firstAbs + y) ?: continue
                drawRow(canvas, row, y, cols, firstAbs + y)
            }
            if (scrollOffset == 0 && em.isCursorVisible) drawCursor(canvas, em.cursorCol, em.cursorRow, if (cursorStyleOverride == 0) em.cursorStyle else cursorStyleOverride - 1)
            if (scrollOffset > 0) drawScrollIndicator(canvas, sb, rows)
        }
    }

    /** Maps the emulator's baked Campbell colours (ANSI 0-15) to the active theme. */
    private fun themed(c: Int): Int {
        if (c == 0 || theme.isBase) return c
        val base = TerminalEmulator.PALETTE
        for (i in 0 until 16) if (c == base[i]) return theme.palette[i]
        return c
    }

    private fun effectiveFg(row: TerminalEmulator.Row, x: Int): Int {
        val fl = row.fl[x].toInt()
        var fg = if (row.fg[x] == 0) theme.fg else themed(row.fg[x])
        if (fl and TerminalEmulator.FLAG_INVERSE != 0) fg = if (row.bg[x] == 0) theme.bg else themed(row.bg[x])
        if (fl and TerminalEmulator.FLAG_DIM != 0) fg = (fg and 0x00FFFFFF) or (0x99 shl 24)
        return fg
    }

    private fun effectiveBg(row: TerminalEmulator.Row, x: Int): Int {
        val fl = row.fl[x].toInt()
        return if (fl and TerminalEmulator.FLAG_INVERSE != 0) {
            if (row.fg[x] == 0) theme.fg else themed(row.fg[x])
        } else {
            themed(row.bg[x]) // 0 = default background (already painted)
        }
    }

    private fun drawRow(canvas: Canvas, row: TerminalEmulator.Row, y: Int, cols: Int, absRow: Int) {
        val top = y * cellH
        // 1) backgrounds, merged into runs
        var x = 0
        while (x < cols) {
            val bg = effectiveBg(row, x)
            var end = x + 1
            while (end < cols && effectiveBg(row, end) == bg) end++
            if (bg != 0) {
                fillPaint.color = bg
                canvas.drawRect(x * cellW, top, end * cellW, top + cellH, fillPaint)
            }
            x = end
        }
        // selection highlight
        if (hasSelection) {
            val (r1, c1, r2, c2) = selectionBounds()
            if (absRow in r1..r2) {
                val from = if (absRow == r1) c1 else 0
                val to = if (absRow == r2) c2 else cols - 1
                fillPaint.color = theme.selection
                canvas.drawRect(from * cellW, top, (to + 1) * cellW, top + cellH, fillPaint)
            }
        }
        // 2) text
        x = 0
        while (x < cols) {
            val fl = row.fl[x].toInt()
            val cp = row.cp[x]
            if (fl and TerminalEmulator.FLAG_WIDE_TAIL != 0 || cp == ' '.code || cp == 0) {
                if (fl and TerminalEmulator.FLAG_UNDERLINE != 0) drawUnderline(canvas, x, 1, top, effectiveFg(row, x))
                x++
                continue
            }
            val style = fl and STYLE_MASK
            val fg = effectiveFg(row, x)
            if (cp < 0x7F) {
                // run of printable ASCII with identical style: one drawText call
                var end = x + 1
                while (end < cols) {
                    val c2 = row.cp[end]
                    if (c2 < 0x20 || c2 >= 0x7F || (row.fl[end].toInt() and STYLE_MASK) != style || effectiveFg(row, end) != fg) break
                    end++
                }
                val chars = CharArray(end - x) { row.cp[x + it].toChar() }
                applyStyle(style, fg)
                canvas.drawText(chars, 0, chars.size, x * cellW, top + baseline, paint)
                if (style and TerminalEmulator.FLAG_UNDERLINE != 0) drawUnderline(canvas, x, end - x, top, fg)
                if (style and TerminalEmulator.FLAG_STRIKE != 0) drawStrike(canvas, x, end - x, top, fg)
                x = end
            } else {
                val width = if (fl and TerminalEmulator.FLAG_WIDE != 0) 2 else 1
                applyStyle(style, fg)
                canvas.drawText(String(Character.toChars(cp)), x * cellW, top + baseline, paint)
                if (style and TerminalEmulator.FLAG_UNDERLINE != 0) drawUnderline(canvas, x, width, top, fg)
                if (style and TerminalEmulator.FLAG_STRIKE != 0) drawStrike(canvas, x, width, top, fg)
                x += width
            }
        }
    }

    private fun applyStyle(style: Int, fg: Int) {
        paint.color = fg
        paint.isFakeBoldText = style and TerminalEmulator.FLAG_BOLD != 0
        paint.textSkewX = if (style and TerminalEmulator.FLAG_ITALIC != 0) -0.2f else 0f
    }

    private fun drawUnderline(canvas: Canvas, x: Int, n: Int, top: Float, color: Int) {
        fillPaint.color = color
        val y = top + baseline + paint.fontMetrics.descent * 0.4f
        canvas.drawRect(x * cellW, y, (x + n) * cellW, y + max(1f, textSizePx / 14f), fillPaint)
    }

    private fun drawStrike(canvas: Canvas, x: Int, n: Int, top: Float, color: Int) {
        fillPaint.color = color
        val y = top + baseline * 0.62f
        canvas.drawRect(x * cellW, y, (x + n) * cellW, y + max(1f, textSizePx / 14f), fillPaint)
    }

    private fun drawCursor(canvas: Canvas, cx: Int, cy: Int, style: Int) {
        val l = cx * cellW
        val t = cy * cellH
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = theme.cursor
        when {
            !hasFocus() -> {
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = 2f
                canvas.drawRect(l + 1, t + 1, l + cellW - 1, t + cellH - 1, fillPaint)
                fillPaint.style = Paint.Style.FILL
            }
            style == 1 -> canvas.drawRect(l, t + cellH - 3f, l + cellW, t + cellH, fillPaint)
            style == 2 -> canvas.drawRect(l, t, l + 3f, t + cellH, fillPaint)
            else -> canvas.drawRect(l, t, l + cellW, t + cellH, fillPaint)
        }
    }

    private fun drawScrollIndicator(canvas: Canvas, scrollback: Int, rows: Int) {
        val total = (scrollback + rows).toFloat()
        val barH = max(24f, height * rows / total)
        val barTop = (height - barH) * (1f - scrollOffset.toFloat() / scrollback)
        fillPaint.color = 0x66FFFFFF
        canvas.drawRoundRect(width - 6f, barTop, width - 2f, barTop + barH, 2f, 2f, fillPaint)
    }

    // ---- touch ----------------------------------------------------------------------------------

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (hasSelection) {
                    hasSelection = false
                    invalidate()
                }
                if (mouseActive()) {
                    sendMouse(0, e.x, e.y, release = false)
                    sendMouse(0, e.x, e.y, release = true)
                }
                requestFocus()
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(this@TerminalView, InputMethodManager.SHOW_IMPLICIT)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showTerminalMenu()
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                scrollRemainder += distanceY
                val lines = (scrollRemainder / cellH).toInt()
                if (lines == 0) return true
                scrollRemainder -= lines * cellH
                val em = session?.emulator ?: return true
                if (mouseActive()) {
                    // Programs that asked for mouse reporting get wheel events.
                    repeat(minOf(abs(lines), 5)) { sendMouse(if (lines < 0) 64 else 65, e2.x, e2.y, release = false) }
                } else if (em.isAltScreen) {
                    // Full-screen apps (less, vim, htop): translate drag into arrow keys.
                    val key = if (lines < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                    val seq = KeyEncoder.encode(key, em.isAppCursorKeys, shift = false, alt = false, ctrl = false) ?: return true
                    repeat(minOf(abs(lines), 5)) { session?.write(seq.toByteArray()) }
                } else {
                    scrollOffset -= lines
                    invalidate()
                }
                return true
            }
        },
    )

    /** Invoked on long-press; the host decides (we use it for paste). */
    var onLongPressListener: (() -> Unit)? = null

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                textSizePx *= detector.scaleFactor
                onTextSizeChanged?.invoke(textSizePx)
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selectMode) {
            handleSelectTouch(event)
            return true
        }
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    // ---- keyboard ---------------------------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                typed(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) typed(composing)
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) {
                    composing = ""
                    return true
                }
                repeat(maxOf(1, beforeLength)) { sendRaw("\u007f") }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) return handleKey(event.keyCode, event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (handleKey(keyCode, event)) true else super.onKeyDown(keyCode, event)

    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val em = session?.emulator ?: return false
        if (event.isCtrlPressed && event.isShiftPressed) {
            if (keyCode == KeyEvent.KEYCODE_C) {
                copySelectionOrScreen()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_V) {
                pasteFromClipboard()
                return true
            }
        }
        val ctrl = event.isCtrlPressed || modifiers.ctrl
        val alt = event.isAltPressed || modifiers.alt
        val seq = KeyEncoder.encode(keyCode, em.isAppCursorKeys, event.isShiftPressed, alt && keyCode != KeyEvent.KEYCODE_ALT_LEFT, ctrl)
        if (seq != null) {
            sendRaw(seq)
            modifiers.clear()
            return true
        }
        val mask = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK
        val c = event.getUnicodeChar(event.metaState and mask.inv())
        if (c != 0 && (c and KeyCharacterMapCombining) == 0) {
            typed(String(Character.toChars(c)), event.isCtrlPressed, event.isAltPressed)
            return true
        }
        return false
    }

    /** Text typed on any keyboard; applies sticky Ctrl/Alt. */
    fun typed(text: String, ctrlEv: Boolean = false, altEv: Boolean = false) {
        if (text.isEmpty()) return
        val ctrl = ctrlEv || modifiers.ctrl
        val alt = altEv || modifiers.alt
        var out = text
        if (ctrl && out.length == 1) KeyEncoder.ctrlChar(out[0])?.let { out = it.toString() }
        if (alt) out = "\u001b$out"
        modifiers.clear()
        sendRaw(out)
    }

    /** Sends bytes to the shell and jumps back to the live screen. */
    fun sendRaw(text: String) {
        scrollOffset = 0
        session?.write(text.toByteArray(Charsets.UTF_8))
        postInvalidateOnAnimation()
    }

    // ---- mouse reporting, selection, clipboard ---------------------------------------------------------

    private fun mouseActive(): Boolean = (session?.emulator?.mouseMode ?: 0) != 0

    private fun sendMouse(button: Int, x: Float, y: Float, release: Boolean) {
        val em = session?.emulator ?: return
        val col = (x / cellW).toInt().coerceIn(0, em.cols - 1) + 1
        val row = (y / cellH).toInt().coerceIn(0, em.rows - 1) + 1
        val seq = if (em.isMouseSgr) {
            "\u001b[<$button;$col;$row${if (release) 'm' else 'M'}"
        } else if (col < 223 && row < 223) {
            "\u001b[M${(32 + (if (release) 3 else button)).toChar()}${(32 + col).toChar()}${(32 + row).toChar()}"
        } else {
            return
        }
        session?.write(seq.toByteArray(Charsets.ISO_8859_1))
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val em = session!!.emulator
        val col = (x / cellW).toInt().coerceIn(0, em.cols - 1)
        val rowOnScreen = (y / cellH).toInt().coerceIn(0, em.rows - 1)
        val firstAbs = em.scrollbackSize - scrollOffset
        return (firstAbs + rowOnScreen) to col
    }

    private fun selectionBounds(): List<Int> {
        val aFirst = selAnchorRow < selEndRow || (selAnchorRow == selEndRow && selAnchorCol <= selEndCol)
        return if (aFirst) listOf(selAnchorRow, selAnchorCol, selEndRow, selEndCol) else listOf(selEndRow, selEndCol, selAnchorRow, selAnchorCol)
    }

    private fun handleSelectTouch(event: MotionEvent) {
        if (session == null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (r, c) = cellAt(event.x, event.y)
                selAnchorRow = r; selAnchorCol = c; selEndRow = r; selEndCol = c
                hasSelection = true
            }
            MotionEvent.ACTION_MOVE -> {
                val (r, c) = cellAt(event.x, event.y)
                selEndRow = r; selEndCol = c
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectMode = false
                val menu = PopupMenu(context, this)
                menu.menu.add(0, 1, 0, "Copy")
                menu.menu.add(0, 2, 1, "Cancel")
                menu.setOnMenuItemClickListener {
                    if (it.itemId == 1) copySelectionOrScreen()
                    hasSelection = false
                    invalidate()
                    true
                }
                menu.setOnDismissListener { invalidate() }
                menu.show()
            }
        }
        invalidate()
    }

    private fun selectedText(): String {
        val em = session?.emulator ?: return ""
        val (r1, c1, r2, c2) = selectionBounds()
        val sb = StringBuilder()
        synchronized(em) {
            for (abs in r1..r2) {
                val row = em.rowAt(abs) ?: continue
                val from = if (abs == r1) c1 else 0
                val to = if (abs == r2) c2 else row.cp.size - 1
                val line = StringBuilder()
                for (x in from..minOf(to, row.cp.size - 1)) {
                    if (row.fl[x].toInt() and TerminalEmulator.FLAG_WIDE_TAIL != 0) continue
                    line.appendCodePoint(if (row.cp[x] == 0) ' '.code else row.cp[x])
                }
                sb.append(line.toString().trimEnd())
                if (abs < r2) sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun copySelectionOrScreen() {
        val text = if (hasSelection) selectedText() else session?.emulator?.screenText().orEmpty()
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    private fun pasteFromClipboard() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) session?.paste(text)
    }

    private fun showTerminalMenu() {
        val menu = PopupMenu(context, this)
        menu.menu.add(0, 1, 0, "Select text")
        menu.menu.add(0, 2, 1, "Paste")
        menu.menu.add(0, 3, 2, "Copy screen")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    selectMode = true
                    hasSelection = false
                }
                2 -> pasteFromClipboard()
                3 -> {
                    hasSelection = false
                    copySelectionOrScreen()
                }
            }
            true
        }
        menu.show()
    }

    private companion object {
        const val KeyCharacterMapCombining = 0x80000000.toInt()
        const val STYLE_MASK = TerminalEmulator.FLAG_BOLD or TerminalEmulator.FLAG_ITALIC or TerminalEmulator.FLAG_UNDERLINE or
            TerminalEmulator.FLAG_DIM or TerminalEmulator.FLAG_STRIKE
    }
}
