package com.example.floatingkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import kotlin.math.roundToInt
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View.MeasureSpec
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating on-screen keyboard implemented as a real Android IME.
 *
 * 中文输入：全拼 / 简拼 + 候选栏，选词次数写进本地偏好，实现"越用越顺手"。
 */
class FloatingIme : InputMethodService() {

    private var shiftOn = false
    private val letterKeys = mutableListOf<TextView>()
    private var shiftKey: TextView? = null

    /** 拼音输入缓冲区，如 "nihao" */
    private var buffer = ""
    private var zhMode = true
    /** 0 = 字母层，1 = 中文符号，2 = 英文(ASCII)符号 */
    private var layer = LAYER_LETTERS
    private lateinit var keyPanel: LinearLayout

    private lateinit var dict: PinyinDict
    private lateinit var prefs: SharedPreferences
    private val usageCache = HashMap<String, Int>()

    /** 键盘整体缩放比例（0.55 ~ 1.6），平板上默认更小 */
    private var uiScale = 1f
    private var panelView: LinearLayout? = null
    private var panelWidth = 0
    private var candidateBar: HorizontalScrollView? = null
    private var candidateRow: LinearLayout? = null

    /** Last floating position (px). -1 means "not positioned yet". */
    private var posX = -1
    private var posY = -1

    /** 长按退格连续删除 */
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatAction: Runnable? = null

    // -------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        dict = PinyinDict(this)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.all.forEach { (k, v) ->
            if (k.startsWith(PREFIX) && v is Int) usageCache[k.substring(PREFIX.length)] = v
        }
        uiScale = prefs.getFloat(KEY_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)

        // 预热词典，避免第一次按键时才加载（108k 条约 300~600ms）
        Thread { runCatching { dict.ensureLoaded() } }.start()
    }

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView()")
        letterKeys.clear()

        val dm = resources.displayMetrics
        if (posX < 0 || posY < 0) {
            posX = dp(12)
            posY = (dm.heightPixels * 0.56f).toInt()
        }
        panelWidth = computePanelWidth()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF16181F.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }

        root.addView(buildTopBar())
        root.addView(buildCandidateBar())

        // 键盘区可以整体重建，用于字母层 / 符号层切换
        keyPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(keyPanel)
        buildKeyPanel()
        panelView = root

        // IME 窗口铺满整个屏幕，面板只是浮在其中的一块。
        // 这样系统画的"切换输入法/收起键盘"按钮会落在屏幕边缘，压不到我们的按键。
        //
        // 注意：InputMethodService 是用 WRAP_CONTENT 高度把输入视图加进去的，
        // 容器高度会变成「topMargin + 面板高度」。面板一往下拖就超出容器被裁掉
        // （表现为拖动时键盘逐渐消失）。所以这里强制按屏幕尺寸测量。
        val dm2 = resources.displayMetrics
        return object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(dm2.widthPixels, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dm2.heightPixels, MeasureSpec.EXACTLY)
                )
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            addView(root, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = posX
                topMargin = posY
            })
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        Log.d(TAG, "onWindowShown()")
        // 系统在显示 IME 窗口时会重置窗口属性，必须等它真正显示后再改成浮动面板
        window?.window?.decorView?.post { applyFloatingLayout() }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        clearBuffer(commit = false)
        stopRepeatDelete()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopRepeatDelete()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * 默认实现会在系统认为"接了物理键盘"时拒绝弹软键盘（模拟器/平板常中招），
     * 这里强制允许，Gboard 也是这么干的。
     */
    override fun onEvaluateInputViewShown(): Boolean = true

    private fun applyFloatingLayout() {
        val w: Window = window?.window ?: return
        val lp = w.attributes

        // 全屏透明窗口：系统按钮去屏幕边缘，面板位置由内部 margin 控制
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        w.attributes = lp
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        clampPanelPosition()
        applyPanelPosition()
        Log.d(TAG, "window fullscreen; panel at=$posX,$posY w=$panelWidth")
    }

    private fun computePanelWidth(): Int {
        val dm = resources.displayMetrics
        val screenDp = dm.widthPixels / dm.density
        // 手机：约 94% 屏宽；平板：封顶 480dp（再大按键就大得离谱了）
        val baseDp = minOf(screenDp * 0.94f, 480f)
        return (baseDp * uiScale * dm.density).toInt()
            .coerceIn(dp(180), dm.widthPixels - dp(16))
    }

    /** 调整尺寸后重建按键（按键高度/字号都跟 uiScale 走） */
    private fun rebuildForScale() {
        panelWidth = computePanelWidth()
        buildKeyPanel()
        clampPanelPosition()
        applyPanelPosition()
        // 重新布局后才拿到真实高度，再钳一次，避免底部超出屏幕
        panelView?.post {
            clampPanelPosition()
            applyPanelPosition()
        }
    }

    private fun clampPanelPosition() {
        val dm = resources.displayMetrics
        val h = panelView?.height?.takeIf { it > 0 } ?: dp((240 * uiScale).toInt())
        posX = posX.coerceIn(0, (dm.widthPixels - panelWidth).coerceAtLeast(0))
        posY = posY.coerceIn(0, (dm.heightPixels - h - dp(56)).coerceAtLeast(0))
    }

    private fun applyPanelPosition() {
        val p = panelView ?: return
        if (p.parent == null) return   // 窗口已隐藏时面板会被摘下，避免 NPE
        val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = posX
        lp.topMargin = posY
        lp.width = panelWidth
        p.layoutParams = lp
    }

    /**
     * 面板不贴底，告诉系统"键盘只占很上面"没用——直接报告不占空间，
     * 避免 adjustResize 的 app 被挤成一条。
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val dm = resources.displayMetrics

        // 1) 不让 app 因为"键盘"而缩小：浮动面板不占底部空间
        outInsets.contentTopInsets = dm.heightPixels
        outInsets.visibleTopInsets = dm.heightPixels

        // 2) 触摸区域显式限定为面板矩形。
        //    窗口是全屏的，不这么设的话系统会把整个屏幕都算成键盘，app 就点不动了。
        val p = panelView
        val left = posX
        val top = posY          // 触摸区坐标是窗口相对的，不要加状态栏高度
        val height = p?.height?.takeIf { it > 0 } ?: dp(260)

        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(left, top, left + panelWidth, top + height)
    }

    // ------------------------------------------------------------------ chrome

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(26)
            )
        }

        val handle = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(0x88FFFFFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(4))
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val resizer = makeResizeGrip()

        val switcher = TextView(this).apply {
            text = "换"
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(24)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setOnClickListener {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
        }

        val toggle = TextView(this).apply {
            // 必须按当前状态初始化，否则界面重建后会显示「中」而实际是英文
            text = if (zhMode) "中" else "En"
            gravity = Gravity.CENTER
            setTextColor(if (zhMode) 0xFF8AB4F8.toInt() else 0x99FFFFFF.toInt())
            textSize = 14f
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(24))
            setOnClickListener {
                zhMode = !zhMode
                if (!zhMode) clearBuffer(commit = true)
                text = if (zhMode) "中" else "En"
                setTextColor(if (zhMode) 0xFF8AB4F8.toInt() else 0x99FFFFFF.toInt())
            }
        }

        val hide = TextView(this).apply {
            text = "▾"
            gravity = Gravity.CENTER
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(24)).apply {
                setMargins(dp(4), 0, dp(6), 0)
            }
            setOnClickListener { requestHideSelf(0) }
        }

        bar.addView(handle)
        bar.addView(spacer)
        bar.addView(resizer)
        bar.addView(switcher)
        bar.addView(toggle)
        bar.addView(hide)
        attachDrag(bar)
        return bar
    }

    private fun buildCandidateBar(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // 用 INVISIBLE 而不是 GONE：候选栏始终占位，打字时键盘不会跳动
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(row)
        candidateBar = scroll
        candidateRow = row
        return scroll
    }

    // ------------------------------------------------------------------ keys

    private fun buildRowView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun buildRow(labels: List<String>, letters: Boolean = true): View {
        val row = buildRowView()
        labels.forEach { label ->
            val key = if (letters) {
                makeKey(label, 1f, KEY_BG, 18f) { onLetter(label) }
            } else {
                makeKey(label, 1f, KEY_BG, 16f) { onSymbol(label) }
            }
            if (letters) letterKeys.add(key)
            row.addView(key)
        }
        return row
    }

    /** 重建整个键盘区：字母层 / 符号层 */
    private fun buildKeyPanel() {
        letterKeys.clear()
        shiftKey = null
        keyPanel.removeAllViews()

        when (layer) {
            LAYER_LETTERS -> {
                keyPanel.addView(buildRow("qwertyuiop".map { it.toString() }))
                keyPanel.addView(buildRow("asdfghjkl".map { it.toString() }))
                keyPanel.addView(buildThirdRow())
                keyPanel.addView(buildLettersBottomRow())
            }
            LAYER_SYMBOLS_ZH -> {
                keyPanel.addView(buildRow("1234567890".map { it.toString() }, letters = false))
                keyPanel.addView(buildRow(listOf("！", "？", "，", "。", "、", "；", "：", "“", "”", "～"), letters = false))
                keyPanel.addView(buildRow(listOf("（", "）", "《", "》", "…", "—", "·", "@", "#", "%"), letters = false))
                keyPanel.addView(buildSymbolsBottomRow(LAYER_EN_SYMBOLS, "ASCII"))
            }
            else -> {
                // 英文/ASCII 符号页：和电脑键盘基本对齐
                keyPanel.addView(buildRow(listOf("!", "\"", "#", "$", "%", "&", "'", "(", ")", "*"), letters = false))
                keyPanel.addView(buildRow(listOf("+", ",", "-", ".", "/", ":", ";", "<", "=", ">"), letters = false))
                keyPanel.addView(buildRow(listOf("?", "@", "[", "\\", "]", "~", "|", "_", "{", "}"), letters = false))
                keyPanel.addView(buildSymbolsBottomRow(LAYER_SYMBOLS_ZH, "中文"))
            }
        }
    }

    private fun switchLayer(to: Int) {
        if (layer == to) return
        if (buffer.isNotEmpty()) clearBuffer(commit = true)
        layer = to
        buildKeyPanel()
    }

    private fun buildLettersBottomRow(): View = buildRowView().apply {
        addView(makeKey("?123", 1.4f, KEY_FN_BG, 15f) { switchLayer(LAYER_SYMBOLS_ZH) })
        addView(makeKey("空格", 3.2f, KEY_BG, 17f) { onSpace() })
        addView(makeKey("，", 1f, KEY_BG, 18f) { onSymbol("，") })
        addView(makeKey("。", 1f, KEY_BG, 18f) { onSymbol("。") })
        addView(makeKey("Enter", 1.6f, KEY_ACTION_BG, 14f) { onEnter() })
    }

    /** 符号页底排：ABC / 空格 / 退格 / 切到另一套符号 / 回车 */
    private fun buildSymbolsBottomRow(other: Int, otherLabel: String): View = buildRowView().apply {
        addView(makeKey("ABC", 1.3f, KEY_FN_BG, 15f) { switchLayer(LAYER_LETTERS) })
        addView(makeKey("空格", 2.6f, KEY_BG, 17f) { onSpace() })
        addView(makeBackspaceKey(1.3f, 18f))
        addView(makeKey(otherLabel, 1.4f, KEY_FN_BG, 13f) { switchLayer(other) })
        addView(makeKey("Enter", 1.5f, KEY_ACTION_BG, 14f) { onEnter() })
    }

    /**
     * 上栏的缩放按钮：左右拖动改大小，松手后重建按键。
     * 放在上栏而不是底排，是因为底部靠近手势导航区，拖动会被系统抢走。
     */
    private fun makeResizeGrip(): View {
        val grip = TextView(this).apply {
            text = "⤢"
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(24)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }
        grip.setOnClickListener(null)
        grip.setOnTouchListener(object : View.OnTouchListener {
            private var startScale = 1f
            private var startRawX = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startScale = uiScale
                        startRawX = event.rawX
                        grip.setTextColor(0xFF8AB4F8.toInt())
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = (event.rawX - startRawX) / 400f
                        uiScale = (startScale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
                        panelWidth = computePanelWidth()
                        clampPanelPosition()
                        applyPanelPosition()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        grip.setTextColor(0xCCFFFFFF.toInt())
                        prefs.edit().putFloat(KEY_SCALE, uiScale).apply()
                        rebuildForScale()
                        return true
                    }
                }
                return false
            }
        })
        return grip
    }

    /** 右下角留空：系统会在这里画"切换输入法"的地球按钮 */
    private fun cornerSpacer(): View = View(this).apply {
        // 系统会在输入法窗口的左右下角画图标，留出足够的空档防误触
        layoutParams = LinearLayout.LayoutParams(dp(62), 1)
    }

    private fun buildThirdRow(): View {
        val row = buildRowView()

        val shift = makeKey("⇧", 1.5f, KEY_FN_BG, 18f) {
            shiftOn = !shiftOn
            refreshLetters()
        }
        shiftKey = shift
        row.addView(shift)

        "zxcvbnm".forEach { c ->
            val key = makeKey(c.toString(), 1f, KEY_BG, 18f) { onLetter(c.toString()) }
            letterKeys.add(key)
            row.addView(key)
        }

        row.addView(makeBackspaceKey(1.5f, 18f))
        return row
    }

    /** 退格键：按下即删一个，按住不放则连续删除 */
    private fun makeBackspaceKey(weight: Float, sizeSp: Float): TextView {
        val key = makeKey("⌫", weight, KEY_FN_BG, sizeSp) {}
        key.setOnClickListener(null)
        key.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    onBackspace()
                    startRepeatDelete()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopRepeatDelete()
                    true
                }
                else -> false
            }
        }
        return key
    }

    private fun startRepeatDelete() {
        stopRepeatDelete()
        val action = object : Runnable {
            override fun run() {
                onBackspace()
                repeatHandler.postDelayed(this, REPEAT_INTERVAL)
            }
        }
        repeatAction = action
        repeatHandler.postDelayed(action, REPEAT_DELAY)
    }

    private fun stopRepeatDelete() {
        repeatAction?.let { repeatHandler.removeCallbacks(it) }
        repeatAction = null
    }

    private fun makeKey(
        label: String,
        weight: Float,
        bg: Int,
        sizeSp: Float,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = (sizeSp * uiScale).coerceAtLeast(9f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        background = keyDrawable(bg)
        layoutParams = LinearLayout.LayoutParams(0, dp((42 * uiScale).toInt()), weight).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick() }
    }

    private fun keyDrawable(color: Int): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(color)
        }
        return RippleDrawable(ColorStateList.valueOf(0x59FFFFFF), shape, null)
    }

    private fun refreshLetters() {
        letterKeys.forEach { tv ->
            val base = tv.text.toString()
            tv.text = if (shiftOn) base.uppercase() else base.lowercase()
        }
        shiftKey?.setTextColor(if (shiftOn) 0xFF8AB4F8.toInt() else Color.WHITE)
    }

    // ------------------------------------------------------------ input logic

    private fun onLetter(c: String) {
        val ic = currentInputConnection
        Log.d(TAG, "onLetter($c) ic=${ic != null} zh=$zhMode")
        if (!zhMode) {
            val text = if (shiftOn) c.uppercase() else c
            ic?.commitText(text, 1)
            if (shiftOn) {
                shiftOn = false
                refreshLetters()
            }
            return
        }
        buffer += c.lowercase()
        ic?.setComposingText(buffer, 1)
        refreshCandidates()
    }

    private fun onSymbol(s: String) {
        if (buffer.isNotEmpty()) clearBuffer(commit = true)
        currentInputConnection?.commitText(s, 1)
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        if (buffer.isEmpty()) {
            ic.commitText(" ", 1)
            return
        }
        // 有候选就上屏第一个，否则原样上屏字母
        val first = (0 until (candidateRow?.childCount ?: 0))
            .mapNotNull { candidateRow?.getChildAt(it) as? TextView }
            .firstOrNull()?.text?.toString()
        commitBufferTo(first ?: buffer)
    }

    private fun onEnter() {
        if (buffer.isNotEmpty()) clearBuffer(commit = true)
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
        if (action != 0 && action != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (buffer.isNotEmpty()) {
            buffer = buffer.dropLast(1)
            if (buffer.isEmpty()) ic.finishComposingText() else ic.setComposingText(buffer, 1)
            refreshCandidates()
            return
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    /** 把缓冲区内容上屏（默认上屏第一个候选）。 */
    private fun commitBufferTo(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (text != buffer) bumpUsage(text)
        buffer = ""
        refreshCandidates()
    }

    private fun clearBuffer(commit: Boolean) {
        if (buffer.isEmpty()) return
        if (commit) currentInputConnection?.commitText(buffer, 1)
        else currentInputConnection?.finishComposingText()
        buffer = ""
        refreshCandidates()
    }

    // ------------------------------------------------------------- candidates

    private fun refreshCandidates() {
        val bar = candidateBar ?: return
        val row = candidateRow ?: return

        if (!zhMode || buffer.isEmpty()) {
            bar.visibility = View.INVISIBLE
            row.removeAllViews()
            return
        }

        val list = dict.query(buffer, ::usageOf, 80)
        row.removeAllViews()
        if (list.isEmpty()) {
            bar.visibility = View.INVISIBLE
            return
        }

        bar.visibility = View.VISIBLE
        list.forEachIndexed { idx, word ->
            val tv = TextView(this).apply {
                text = word
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(if (idx == 0) 0xFF8AB4F8.toInt() else Color.WHITE)
                setPadding(dp(13), dp(8), dp(13), dp(8))
                isClickable = true
                setOnClickListener {
                    currentInputConnection?.commitText(word, 1)
                    bumpUsage(word)
                    buffer = ""
                    refreshCandidates()
                }
            }
            row.addView(tv)
        }
    }

    private fun usageOf(word: String): Int = usageCache[word] ?: 0

    private fun bumpUsage(word: String) {
        val n = (usageCache[word] ?: 0) + 1
        usageCache[word] = n
        if (n <= 300) prefs.edit().putInt(PREFIX + word, n).apply()
    }

    // ------------------------------------------------------------------ drag

    private fun attachDrag(v: View) {
        v.setOnTouchListener(object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val panel = panelView ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        dragging = true
                        panel.translationX = 0f
                        panel.translationY = 0f
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging) return false
                        // 用 translation 跟手：只做渲染位移，不触发重新布局。
                        // 同时把位移钳制在屏幕内，否则面板会滑出窗口被裁掉（拖到底就看不见了）。
                        val dm = resources.displayMetrics
                        val h = panel.height.takeIf { it > 0 } ?: 0
                        val minTx = (-posX).toFloat()
                        val maxTx = (dm.widthPixels - panelWidth - posX).toFloat().coerceAtLeast(minTx)
                        val minTy = (-posY).toFloat()
                        val maxTy = (dm.heightPixels - h - dp(56) - posY).toFloat().coerceAtLeast(minTy)

                        panel.translationX = (event.rawX - startRawX).coerceIn(minTx, maxTx)
                        panel.translationY = (event.rawY - startRawY).coerceIn(minTy, maxTy)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!dragging) return false
                        dragging = false
                        // 位移已经在拖动时钳制过了，直接换算成实际坐标提交
                        posX += panel.translationX.roundToInt()
                        posY += panel.translationY.roundToInt()
                        panel.translationX = 0f
                        panel.translationY = 0f
                        commitPosition()
                        return true
                    }
                }
                return false
            }
        })
    }

    /** Clamp to the screen and push the new position to the IME window. */
    private fun commitPosition() {
        val dm = resources.displayMetrics
        val height = panelView?.height?.takeIf { it > 0 } ?: dp(240)
        posX = posX.coerceIn(0, (dm.widthPixels - panelWidth).coerceAtLeast(0))
        // 底部留出系统按钮的高度，面板拖到最下面也不会压到它们
        posY = posY.coerceIn(0, (dm.heightPixels - height - dp(56)).coerceAtLeast(0))
        applyPanelPosition()
        Log.d(TAG, "drag committed -> at=$posX,$posY")
    }

    // ------------------------------------------------------------------ misc

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "FloatingIme"
        const val PREFS = "learn"
        const val PREFIX = "u:"
        const val KEY_BG = 0x26FFFFFF
        const val KEY_FN_BG = 0x1AFFFFFF
        const val KEY_ACTION_BG = 0x4D8AB4F8
        const val LAYER_LETTERS = 0
        const val LAYER_SYMBOLS_ZH = 1
        const val LAYER_EN_SYMBOLS = 2
        const val KEY_SCALE = "ui_scale"
        const val MIN_SCALE = 0.55f
        const val MAX_SCALE = 1.6f
        const val REPEAT_DELAY = 380L      // 长按多久开始连删
        const val REPEAT_INTERVAL = 55L    // 连删间隔
    }
}


























