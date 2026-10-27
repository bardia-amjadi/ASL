package dev.asl.app.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.asl.app.terminal.InputModifiers
import dev.asl.app.vnc.RfbClient
import kotlin.math.abs
import kotlin.math.min

/**
 * Shows the VNC framebuffer scaled to fit and turns touch into mouse input:
 * tap = left click, long-press = right click, one-finger drag = left-button drag, two-finger drag = scroll wheel.
 */
class VncView(context: Context) : View(context), RfbClient.Listener {
    var modifiers: InputModifiers = InputModifiers()

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var fbW = 0
    private var fbH = 0
    private val dest = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastClient: RfbClient? = null

    // touch state
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var scrollAcc = 0f
    private var lastScrollY = 0f
    private val longPress = Runnable {
        longPressed = true
        click(downX, downY, 4)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xFF000000.toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DesktopManager.viewerListener = this
        syncFromClient()
    }

    override fun onDetachedFromWindow() {
        if (DesktopManager.viewerListener === this) DesktopManager.viewerListener = null
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    // ---- RfbClient.Listener (reader thread) ----------------------------------------------------------

    override fun onFrame() { postInvalidateOnAnimation() }
    override fun onResize(width: Int, height: Int) { postInvalidateOnAnimation() }
    override fun onBell() {}
    override fun onClosed(reason: String) { postInvalidateOnAnimation() }

    private fun syncFromClient() { postInvalidateOnAnimation() }

    // ---- drawing -----------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val c = DesktopManager.client ?: return
        synchronized(c.lock) {
            val w = c.width()
            val h = c.height()
            if (w <= 0 || h <= 0) return
            var bmp = bitmap
            if (bmp == null || fbW != w || fbH != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap = bmp
                fbW = w
                fbH = h
            }
            bmp!!.setPixels(c.pixels(), 0, w, 0, 0, w, h)
            lastClient = c
        }
        val scale = min(width.toFloat() / fbW, height.toFloat() / fbH)
        val dw = fbW * scale
        val dh = fbH * scale
        dest.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        canvas.drawBitmap(bitmap!!, null, dest, paint)
    }

    /** View coordinates to framebuffer coordinates, or null if outside the picture. */
    private fun toFb(x: Float, y: Float): Pair<Int, Int>? {
        if (fbW == 0 || dest.width() <= 0f) return null
        val fx = ((x - dest.left) / dest.width() * fbW).toInt()
        val fy = ((y - dest.top) / dest.height() * fbH).toInt()
        return fx.coerceIn(0, fbW - 1) to fy.coerceIn(0, fbH - 1)
    }

    // ---- touch -----------------------------------------------------------------------------------------------

    private fun click(x: Float, y: Float, mask: Int) {
        val c = DesktopManager.client ?: return
        val p = toFb(x, y) ?: return
        c.pointer(p.first, p.second, 0)
        c.pointer(p.first, p.second, mask)
        c.pointer(p.first, p.second, 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = DesktopManager.client ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                longPressed = false
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                requestFocus()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                lastScrollY = averageY(event)
                scrollAcc = 0f
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val y = averageY(event)
                    scrollAcc += lastScrollY - y
                    lastScrollY = y
                    val step = 28f
                    while (abs(scrollAcc) >= step) {
                        val up = scrollAcc < 0
                        val p = toFb(event.x, event.y) ?: break
                        val button = if (up) 8 else 16 // X buttons 4 (wheel up) and 5 (wheel down)
                        c.pointer(p.first, p.second, button)
                        c.pointer(p.first, p.second, 0)
                        scrollAcc += if (up) step else -step
                    }
                } else if (!longPressed) {
                    if (!dragging && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                        handler.removeCallbacks(longPress)
                        dragging = true
                        toFb(downX, downY)?.let { c.pointer(it.first, it.second, 1) }
                    }
                    if (dragging) toFb(event.x, event.y)?.let { c.pointer(it.first, it.second, 1) }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    toFb(event.x, event.y)?.let { c.pointer(it.first, it.second, 0) }
                } else if (!longPressed && event.pointerCount == 1) {
                    click(event.x, event.y, 1)
                    showKeyboard()
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (dragging) toFb(event.x, event.y)?.let { c.pointer(it.first, it.second, 0) }
                dragging = false
            }
        }
        return true
    }

    private fun averageY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return sum / e.pointerCount
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---- keyboard ----------------------------------------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                typeText(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) typeText(composing)
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) {
                    composing = ""
                    return true
                }
                repeat(maxOf(1, beforeLength)) { tap(0xFF08) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKey(event.keyCode, event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (handleKey(keyCode, event)) true else super.onKeyDown(keyCode, event)

    private fun keysymFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF0D
        KeyEvent.KEYCODE_DEL -> 0xFF08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
        KeyEvent.KEYCODE_TAB -> 0xFF09
        KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
        KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
        KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
        KeyEvent.KEYCODE_MOVE_END -> 0xFF57
        KeyEvent.KEYCODE_PAGE_UP -> 0xFF55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xFFBE + (keyCode - KeyEvent.KEYCODE_F1)
        else -> null
    }

    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val special = keysymFor(keyCode)
        if (special != null) {
            tap(special, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)
            return true
        }
        val c = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (c != 0 && (c and Int.MIN_VALUE) == 0) {
            tap(RfbClient.keysymForCodePoint(c), event.isCtrlPressed, event.isAltPressed, false)
            return true
        }
        return false
    }

    /** Typed text from the soft keyboard (one key tap per character). */
    fun typeText(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            tap(RfbClient.keysymForCodePoint(cp))
        }
    }

    /** Presses and releases [keysym], wrapped in the active Ctrl/Alt/Shift modifiers (sticky toggles included). */
    fun tap(keysym: Int, ctrlEv: Boolean = false, altEv: Boolean = false, shiftEv: Boolean = false) {
        val c = DesktopManager.client ?: return
        val ctrl = ctrlEv || modifiers.ctrl
        val alt = altEv || modifiers.alt
        if (ctrl) c.key(0xFFE3, true)
        if (alt) c.key(0xFFE9, true)
        if (shiftEv) c.key(0xFFE1, true)
        c.key(keysym, true)
        c.key(keysym, false)
        if (shiftEv) c.key(0xFFE1, false)
        if (alt) c.key(0xFFE9, false)
        if (ctrl) c.key(0xFFE3, false)
        modifiers.clear()
    }
}
