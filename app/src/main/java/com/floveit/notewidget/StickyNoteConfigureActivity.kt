package com.floveit.notewidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity


class StickyNoteConfigureActivity : AppCompatActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedPhotoUri: Uri? = null

    // Simple, programmatic UI controls (no XML IDs needed)
    private lateinit var noteInput: EditText
    private lateinit var pickPhotoBtn: Button
    private lateinit var captionInput: EditText
//    private lateinit var previewImage: ImageView
    private lateinit var saveBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var clearPhotoBtn: Button
    private lateinit var captionSizeSeek: SeekBar
    private lateinit var captionSizeLabel: TextView
    private lateinit var preview: CaptionPreviewView

    // keep track of normalized position; default matches widget
    private var captionPosX: Float = 0.5f
    private var captionPosY: Float = 0.80f

    private val CAPTION_MIN_SP = 9
    private val CAPTION_MAX_SP = 48
    private val CAPTION_DEFAULT_SP = 14
    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomLabel: TextView
    private var photoZoom: Float = 1.0f



    // Storage Access Framework picker
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Persist long-term read permission so the widget can render after reboot
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            selectedPhotoUri = uri
            updatePreview(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED) // default if user backs out


        // Get the widget ID or bail out
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish(); return
        }


        // Build a super minimal UI in code
        setContentView(buildUi())



        val savedZoom = StickyNoteWidget.loadPhotoZoom(this, appWidgetId) // 1.0..3.0
        photoZoom = savedZoom
        val pct = (savedZoom * 100).toInt().coerceIn(100, 300)
        zoomSeek.progress = pct
        zoomLabel.text = "$pct%"
        preview.setCropZoom(savedZoom)

// One listener
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(100, 300)
                photoZoom = clamped / 100f
                zoomLabel.text = "${clamped}%"
                preview.setCropZoom(photoZoom)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


        val (savedX, savedY) = StickyNoteWidget.loadCaptionPos(this, appWidgetId)
        captionPosX = savedX
        captionPosY = savedY
        preview.setPosition(captionPosX, captionPosY)

        // Prefill prior data if editing
        noteInput.setText(StickyNoteWidget.loadNote(this, appWidgetId))
        captionInput.setText(StickyNoteWidget.loadCaption(this, appWidgetId))
        StickyNoteWidget.loadPhotoUri(this, appWidgetId)?.let { saved ->
            runCatching { Uri.parse(saved) }.onSuccess { uri ->
                selectedPhotoUri = uri
                updatePreview(uri)
            }
        }

        // Restore caption size & position in preview
        val currentCaptionSp = StickyNoteWidget.loadCaptionSize(this, appWidgetId)

        preview.setCaption(captionInput.text?.toString().orEmpty())
        preview.setCaptionSizeSp(currentCaptionSp)
        preview.setPosition(savedX, savedY)

        captionInput.textSize = currentCaptionSp
        captionSizeLabel.text = "Caption size: ${currentCaptionSp.toInt()}sp"
        captionSizeSeek.progress = currentCaptionSp.toInt().coerceIn(CAPTION_MIN_SP, CAPTION_MAX_SP)

        captionSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sp = progress.coerceIn(CAPTION_MIN_SP, CAPTION_MAX_SP).toFloat()
                captionInput.textSize = sp      // live preview
                captionSizeLabel.text = "Caption size: ${sp.toInt()}sp"
                preview.setCaptionSizeSp(sp)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        captionInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                preview.setCaption(s?.toString().orEmpty())  // 👈 live preview text
            }
        })



        // Actions
        pickPhotoBtn.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        clearPhotoBtn.setOnClickListener {
            selectedPhotoUri = null
            preview.clearImage()
//            previewImage.setImageDrawable(null)
            Toast.makeText(this, "Photo cleared", Toast.LENGTH_SHORT).show()
        }
        saveBtn.setOnClickListener { handleSave() }
        cancelBtn.setOnClickListener { handleCancel() }

    }



    private fun buildUi(): View {
        fun dp(v: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

        // Colors (black UI with white text)
        val bgBlack      = Color.parseColor("#000000")
        val cardDark     = Color.parseColor("#121212")
        val strokeDark   = Color.parseColor("#33FFFFFF")
        val textPrimary  = Color.parseColor("#FFFFFF")
        val textSecondary= Color.parseColor("#B3FFFFFF")
        val hintColor    = Color.parseColor("#80FFFFFF")
        val btnTint      = Color.parseColor("#1E1E1E")

        // Root: vertical, fills the screen (no ScrollView)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgBlack)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ----- Preview block (takes leftover space via weight) -----
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(cardDark)
                setStroke(dp(1), strokeDark)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(previewCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, /* weight */ 1f
        ).apply { bottomMargin = dp(10) })

        val previewTitle = TextView(this).apply {
            text = "Preview"
            setTextColor(textSecondary)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
        }
        previewCard.addView(previewTitle)


        preview = CaptionPreviewView(this).apply {
            contentDescription = "Photo preview with draggable caption"
            minimumWidth  = dp(200)
            minimumHeight = dp(320)
            onPositionChanged = { x, y ->
                captionPosX = x
                captionPosY = y
            }
        }

        previewCard.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, /* weight */ 1f
        ).apply {
            topMargin = dp(6)
        })

        // ----- Controls block (compact; wrap content) -----
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(cardDark)
                setStroke(dp(1), strokeDark)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(zoomRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        val zoomTitle = TextView(this).apply {
            text = "Zoom"
            setTextColor(textSecondary)   // OK here; in-scope
            textSize = 12f
        }
        zoomRow.addView(zoomTitle, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        zoomLabel = TextView(this).apply {
            text = "100%"
            setTextColor(textSecondary)   // OK here; in-scope
            textSize = 12f
        }
        zoomRow.addView(zoomLabel)

        zoomSeek = SeekBar(this).apply {
            max = 300        // 100..300%
            progress = 100
        }

        // Caption input
        val captionRowTitle = TextView(this).apply {
            text = "Caption"
            setTextColor(textSecondary)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
        }
        controls.addView(captionRowTitle)

        captionInput = EditText(this).apply {
            hint = "Write caption on photo"
            maxLines = 2
            setTextColor(textPrimary)
            setHintTextColor(hintColor)
            backgroundTintList = ColorStateList.valueOf(textSecondary)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        controls.addView(captionInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // Caption size (label + value + seek)
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(sizeRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        val sizeLabel = TextView(this).apply {
            text = "Size"
            setTextColor(textSecondary)
            textSize = 12f
        }
        sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        captionSizeLabel = TextView(this).apply {
            text = "14sp"
            setTextColor(textSecondary)
            textSize = 12f
        }
        sizeRow.addView(captionSizeLabel)

        captionSizeSeek = SeekBar(this).apply {
            // Uses your existing constants in codebase:
            // CAPTION_MAX_SP and CAPTION_DEFAULT_SP
            max = CAPTION_MAX_SP
            progress = CAPTION_DEFAULT_SP
        }
        controls.addView(captionSizeSeek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })

        // Note (kept but compact: 2 lines)
        val noteTitle = TextView(this).apply {
            text = "Note (optional)"
            setTextColor(textSecondary)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
        }
        controls.addView(noteTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
//
//        controls.addView(zoomSeek, LinearLayout.LayoutParams(
//            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
//        ).apply { topMargin = dp(2) })

        noteInput = EditText(this).apply {
            hint = "Write your note…"
            minLines = 1
            maxLines = 2
            setTextColor(textPrimary)
            setHintTextColor(hintColor)
            backgroundTintList = ColorStateList.valueOf(textSecondary)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        controls.addView(noteInput)

        // Buttons helpers
        fun Button.stylize() {
            isAllCaps = false
            setTextColor(textPrimary)
            backgroundTintList = ColorStateList.valueOf(btnTint)
        }

        // Photo actions
        val photoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        controls.addView(photoRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val pickPhotoBtn = Button(this).apply {
            text = "Pick Photo"
            stylize()
        }.also { this.pickPhotoBtn = it }
        photoRow.addView(pickPhotoBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { rightMargin = dp(6) })

        val clearPhotoBtn = Button(this).apply {
            text = "Clear Photo"
            stylize()
        }.also { this.clearPhotoBtn = it }
        photoRow.addView(clearPhotoBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = dp(6) })

        // Save / Cancel
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        controls.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        val saveBtn = Button(this).apply {
            text = "Save"
            stylize()
        }.also { this.saveBtn = it }
        actionRow.addView(saveBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { rightMargin = dp(6) })

        val cancelBtn = Button(this).apply {
            text = "Cancel"
            stylize()
        }.also { this.cancelBtn = it }
        actionRow.addView(cancelBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = dp(6) })

        return root
    }

//    private fun drawWidgetBitmap(
//        context: Context,
//        appWidgetId: Int,
//        source: Bitmap,        // original decoded photo
//        outW: Int,
//        outH: Int,
//        caption: String?
//    ): Bitmap {
//        val canvasBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
//        val c = Canvas(canvasBmp)
//        c.drawColor(Color.TRANSPARENT)
//
//        val norm = loadNormCrop(context, appWidgetId)
//
//        val srcToDraw: Bitmap = if (norm != null) {
//            val x0 = (norm.left   * source.width).toInt().coerceIn(0, source.width - 1)
//            val y0 = (norm.top    * source.height).toInt().coerceIn(0, source.height - 1)
//            val x1 = (norm.right  * source.width).toInt().coerceIn(x0 + 1, source.width)
//            val y1 = (norm.bottom * source.height).toInt().coerceIn(y0 + 1, source.height)
//            val cw = (x1 - x0).coerceAtLeast(1)
//            val ch = (y1 - y0).coerceAtLeast(1)
//            try { Bitmap.createBitmap(source, x0, y0, cw, ch) } catch (_: Throwable) { source }
//        } else {
//            source
//        }
//
//        val scaled = if (srcToDraw.width != outW || srcToDraw.height != outH) {
//            Bitmap.createScaledBitmap(srcToDraw, outW, outH, true)
//        } else srcToDraw
//
//        c.drawBitmap(scaled, 0f, 0f, null)
//
//        // draw your existing caption overlay here:
//        caption?.let { drawCaptionOverlay(c, it, outW, outH) }
//
//        if (scaled !== srcToDraw && srcToDraw !== source) srcToDraw.recycle()
//        return canvasBmp
//    }


    private fun updatePreview(uri: Uri) {
        preview.setImageUri(uri)
        preview.setCaption(captionInput.text?.toString().orEmpty())
        // set size from slider if you have one
        val sp = captionSizeSeek.progress.coerceIn(CAPTION_MIN_SP, CAPTION_MAX_SP).toFloat()
        preview.setCaptionSizeSp(sp)
        preview.setPosition(captionPosX, captionPosY)
    }
//    private fun computeVisibleNormCropFromPreview(iv: ImageView): StickyNoteWidget.NormCrop? {
//        val d = iv.drawable ?: return null
//        if (iv.width == 0 || iv.height == 0) return null
//
//        // Drawable (original image) bounds
//        val dw = d.intrinsicWidth.toFloat()
//        val dh = d.intrinsicHeight.toFloat()
//        val drawableRect = android.graphics.RectF(0f, 0f, dw, dh)
//
//        // View rect in view coordinates
//        val viewRect = android.graphics.RectF(0f, 0f, iv.width.toFloat(), iv.height.toFloat())
//
//        // Map viewRect back to drawable space using inverse of the current image matrix
//        val m = android.graphics.Matrix(iv.imageMatrix)
//        val inv = android.graphics.Matrix()
//        if (!m.invert(inv)) return null
//        inv.mapRect(viewRect)
//
//        // Clamp to drawable bounds
//        val visible = android.graphics.RectF(
//            viewRect.left.coerceIn(drawableRect.left, drawableRect.right),
//            viewRect.top.coerceIn(drawableRect.top, drawableRect.bottom),
//            viewRect.right.coerceIn(drawableRect.left, drawableRect.right),
//            viewRect.bottom.coerceIn(drawableRect.top, drawableRect.bottom),
//        )
//
//        // Normalize
//        val left   = (visible.left   / dw).coerceIn(0f, 1f)
//        val top    = (visible.top    / dh).coerceIn(0f, 1f)
//        val right  = (visible.right  / dw).coerceIn(0f, 1f)
//        val bottom = (visible.bottom / dh).coerceIn(0f, 1f)
//
//        // Ensure left<right, top<bottom (defensive)
//        if (right - left < 0.001f || bottom - top < 0.001f) return null
//
//        return StickyNoteWidget.NormCrop(left, top, right, bottom)
//    }

    private fun handleSave() {
        val noteText = noteInput.text?.toString().orEmpty().ifBlank { "(empty)" }
        val caption = captionInput.text?.toString().orEmpty()

        val chosenCaptionSp = captionSizeSeek.progress.coerceIn(CAPTION_MIN_SP, CAPTION_MAX_SP).toFloat()
        StickyNoteWidget.saveCaptionSize(this, appWidgetId, chosenCaptionSp)

        StickyNoteWidget.saveNote(this, appWidgetId, noteText)
        StickyNoteWidget.saveCaption(this, appWidgetId, caption)
        StickyNoteWidget.savePhotoUri(this, appWidgetId, selectedPhotoUri?.toString())
        StickyNoteWidget.saveCaptionPos(this, appWidgetId, captionPosX, captionPosY)

        val zoom = (zoomSeek.progress.coerceAtLeast(100)) / 100f
        StickyNoteWidget.savePhotoZoom(this, appWidgetId, zoom)

//        val crop = preview.exportVisibleNormCrop()
//        if (crop != null) {
//            StickyNoteWidget.saveNormCrop(this, appWidgetId, crop)
//        }
        val crop = preview.exportVisibleNormCrop()
        if (crop != null) {
            StickyNoteWidget.saveNormCrop(this, appWidgetId, crop)
        }


        StickyNoteWidget.updateWidget(this, AppWidgetManager.getInstance(this), appWidgetId)

        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    private fun handleCancel() {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    // --- small UI helpers ---
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun lpMatchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun lpMatch(h: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, h
    )
    private fun space(h: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }
}


// Inside StickyNoteConfigureActivity.kt (same package/file as before)
private class CaptionPreviewView(context: Context) : View(context) {

    // --- utils / drawing ---
    private val dm = resources.displayMetrics
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
    private val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var bmp: Bitmap? = null
    private val imgRect = Rect()                   // preview uses the whole view

    // caption state
    private var caption: String = ""
    private var captionSp: Float = 14f
    private var posX: Float = 0.5f
    private var posY: Float = 0.80f
    private val padH = dp(10f).toInt()
    private val padV = dp(6f).toInt()
    private val margin = dp(6f)

    // crop aspect (widget portrait)
    private val TARGET_AR = 5f / 6f               // width / height

    // zoom == crop tightness (1.0 = largest crop, 3.0 = tight)
    private var userZoom: Float = 1.0f
    fun setCropZoom(z: Float) { userZoom = z.coerceIn(1.0f, 3.0f); invalidate() }

    // crop center (normalized in FULL SOURCE space, 0..1)
    private var cropCenterU = 0.5f
    private var cropCenterV = 0.5f

    // caches for mapping/hit testing
    private var lastFitRect: Rect? = null         // where full image is drawn (fit)
    private var lastCropDest: RectF? = null
    private var lastBubbleRect: RectF? = null

    // notify activity when caption moves
    var onPositionChanged: ((Float, Float) -> Unit)? = null

    fun clearImage() {
        bmp?.recycle(); bmp = null
        invalidate()
    }

    fun setImageUri(uri: Uri) {
        bmp?.recycle()
        bmp = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        // reset center for new image
        cropCenterU = 0.5f; cropCenterV = 0.5f
        invalidate()
    }

    fun setCaption(text: String) { caption = text; invalidate() }
    fun setCaptionSizeSp(sp: Float) { captionSp = sp; invalidate() }
    fun setPosition(x: Float, y: Float) {
        posX = x.coerceIn(0f, 1f); posY = y.coerceIn(0f, 1f); invalidate()
    }

    private fun computeImgRect(viewW: Int, viewH: Int) {
        // use the whole view (no extra borders)
        imgRect.set(0, 0, viewW, viewH)
    }

    /** Fit the entire image inside imgRect (no crop). */
    private fun computeFitRect(b: Bitmap): Rect {
        val areaW = imgRect.width().toFloat()
        val areaH = imgRect.height().toFloat()
        val s = kotlin.math.min(areaW / b.width, areaH / b.height)
        val w = (b.width * s).toInt().coerceAtLeast(1)
        val h = (b.height * s).toInt().coerceAtLeast(1)
        val left = imgRect.left + (imgRect.width() - w) / 2
        val top  = imgRect.top  + (imgRect.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** Given source size + zoom, return the crop RECT in SOURCE space. */
    private fun computeCropRectInSource(sw: Int, sh: Int, zoom: Float, u: Float, v: Float): RectF {
        val z = zoom.coerceIn(1f, 3f)

        // largest TARGET_AR rect that fits inside full source (z = 1)
        val (maxW, maxH) = if (sw.toFloat() / sh >= TARGET_AR) {
            (sh * TARGET_AR) to sh.toFloat()
        } else {
            sw.toFloat() to (sw / TARGET_AR)
        }

        val w = maxW / z
        val h = maxH / z

        val halfW = w / 2f
        val halfH = h / 2f

        // desired center in source coords
        var cx = (u.coerceIn(0f, 1f) * sw)
        var cy = (v.coerceIn(0f, 1f) * sh)

        // clamp so the crop stays fully inside the source
        cx = cx.coerceIn(halfW, sw - halfW)
        cy = cy.coerceIn(halfH, sh - halfH)

        return RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    /** Map a screen point to SOURCE coords using the last fit mapping. */
    private fun destToSource(x: Float, y: Float): Pair<Float, Float>? {
        val b = bmp ?: return null
        val fit = lastFitRect ?: return null
        val s = fit.width().toFloat() / b.width.toFloat()  // uniform scale
        val sx = (x - fit.left) / s
        val sy = (y - fit.top)  / s
        return sx to sy
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        computeImgRect(width, height)

        bmp?.let { b ->
            // --- 1) Draw a blurred version of the FULL-FIT image as background ---
            val fitRect = computeFitRect(b)
            lastFitRect = fitRect

            val wFit = fitRect.width().coerceAtLeast(1)
            val hFit = fitRect.height().coerceAtLeast(1)
            if (wFit > 0 && hFit > 0) {
                val temp = Bitmap.createBitmap(wFit, hFit, Bitmap.Config.ARGB_8888)
                val cTmp = Canvas(temp)
                cTmp.drawBitmap(b, null, Rect(0, 0, wFit, hFit), imgPaint)

                // soft blur by downscale/upscale
                val smallW = (wFit / 10).coerceAtLeast(1)
                val smallH = (hFit / 10).coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(temp, smallW, smallH, true)
                val blurred = Bitmap.createScaledBitmap(small, wFit, hFit, true)
                small.recycle(); temp.recycle()

                // draw blurred full-fit
                canvas.drawBitmap(blurred, null, fitRect, null)
                blurred.recycle()
            }

            // --- 2) Compute crop (in SOURCE), then map to DEST (fitRect) ---
            val cropSrc = computeCropRectInSource(b.width, b.height, userZoom, cropCenterU, cropCenterV)
            val scale = lastFitRect!!.width().toFloat() / b.width.toFloat()
            val cropDest = RectF(
                fitRect.left + cropSrc.left   * scale,
                fitRect.top  + cropSrc.top    * scale,
                fitRect.left + cropSrc.right  * scale,
                fitRect.top  + cropSrc.bottom * scale
            )
            lastCropDest = cropDest

            // --- 3) Draw the sharp full-fit image ONLY inside the crop window ---
            canvas.save()
            if (Build.VERSION.SDK_INT >= 26) {
                val path = Path().apply { addRect(cropDest, Path.Direction.CW) }
                canvas.clipPath(path)
            } else {
                @Suppress("DEPRECATION")
                canvas.clipRect(cropDest.left, cropDest.top, cropDest.right, cropDest.bottom, android.graphics.Region.Op.INTERSECT)
            }
            canvas.drawBitmap(b, null, fitRect, imgPaint)  // sharp layer
            canvas.restore()

            // --- 4) Add dialog-style black scrim outside the crop (inside fitRect) ---
            canvas.save()
            if (Build.VERSION.SDK_INT >= 26) {
                val path = Path().apply {
                    addRect(RectF(fitRect), Path.Direction.CW)
                    addRect(cropDest, Path.Direction.CCW) // subtract crop
                }
                canvas.clipPath(path)
            } else {
                @Suppress("DEPRECATION")
                canvas.clipRect(cropDest.left, cropDest.top, cropDest.right, cropDest.bottom, android.graphics.Region.Op.DIFFERENCE)
                @Suppress("DEPRECATION")
                canvas.clipRect(fitRect, android.graphics.Region.Op.INTERSECT)
            }
            canvas.drawRect(fitRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }) // ~40% black
            canvas.restore()

            // optional thin border around crop
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x66FFFFFF
            }
            canvas.drawRect(cropDest, border)
        }

        // --- 5) Caption bubble + text ---
        lastBubbleRect = null
        if (caption.isBlank()) return

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(dp(3f), 0f, dp(1.5f), 0x80000000.toInt())
        }
        var spx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, captionSp.coerceIn(9f, 48f), dm)
        tp.textSize = spx

        var maxLines = 4
        val maxBoxW = (imgRect.width().toFloat()  - 2f * margin).coerceAtLeast(1f)
        val maxBoxH = (imgRect.height().toFloat() - 2f * margin).coerceAtLeast(1f)
        val maxInnerW = (maxBoxW - 2 * padH).toInt().coerceAtLeast(1)

        fun buildLayout(wi: Int): StaticLayout {
            @Suppress("DEPRECATION")
            return if (Build.VERSION.SDK_INT >= 23) {
                StaticLayout.Builder
                    .obtain(caption, 0, caption.length, tp, maxOf(1, wi))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1f)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setMaxLines(maxLines)
                    .build()
            } else {
                StaticLayout(caption, tp, maxOf(1, wi), Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
            }
        }
        fun StaticLayout.maxLineWidth(): Float { var m = 0f; for (i in 0 until lineCount) m = maxOf(m, getLineWidth(i)); return m }

        var layout = buildLayout(maxInnerW)
        fun innerW(l: StaticLayout) = l.maxLineWidth().coerceAtLeast(1f)
        fun innerH(l: StaticLayout) = l.height.toFloat().coerceAtLeast(1f)

        while ((innerW(layout) + 2 * padH > maxBoxW || innerH(layout) + 2 * padV > maxBoxH) && spx > dp(9f)) {
            spx -= dp(1f); tp.textSize = spx; layout = buildLayout(maxInnerW)
        }
        if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
            maxLines = 2; layout = buildLayout(maxInnerW)
            if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
                maxLines = 1; layout = buildLayout(maxInnerW)
            }
        }

        val innerWv = kotlin.math.min(innerW(layout), maxBoxW - 2 * padH)
        val innerHv = kotlin.math.min(innerH(layout), maxBoxH - 2 * padV)
        val bubbleW = innerWv + 2 * padH
        val bubbleH = innerHv + 2 * padV

        val targetCX = imgRect.left + (imgRect.width() * posX.coerceIn(0f, 1f))
        val targetCY = imgRect.top  + (imgRect.height() * posY.coerceIn(0f, 1f))
        var left = targetCX - bubbleW / 2f
        var top  = targetCY - bubbleH / 2f

        val lowLeft  = imgRect.left.toFloat()  + margin
        val highLeft = imgRect.right.toFloat() - margin - bubbleW
        val lowTop   = imgRect.top.toFloat()   + margin
        val highTop  = imgRect.bottom.toFloat()- margin - bubbleH
        if (highLeft >= lowLeft) { if (left < lowLeft) left = lowLeft; if (left > highLeft) left = highLeft }
        else { left = imgRect.left + (imgRect.width() - bubbleW) / 2f }
        if (highTop >= lowTop) { if (top < lowTop) top = lowTop; if (top > highTop) top = highTop }
        else { top = lowTop }

        val bubble = RectF(left, top, left + bubbleW, top + bubbleH)
        lastBubbleRect = bubble

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        val r = dp(12f)
        canvas.drawRoundRect(bubble, r, r, bubblePaint)

        val layoutWidth = layout.width.toFloat().coerceAtLeast(innerWv)
        val dx = (innerWv - layoutWidth) / 2f
        canvas.save()
        canvas.translate(left + padH + dx, top + padV)
        layout.draw(canvas)
        canvas.restore()
    }

    // --- interaction: drag caption / move crop / pinch-resize crop ---
    private enum class DragMode { NONE, CAPTION, MOVE_CROP, RESIZE_CROP }
    private var dragMode = DragMode.NONE

    private var pointerId1 = -1
    private var pointerId2 = -1
    private var pinchStartDist = 0f
    private var pinchStartZoom = 1f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bmp == null) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitBubble = lastBubbleRect?.contains(event.x, event.y) == true
                dragMode = if (hitBubble && caption.isNotBlank()) DragMode.CAPTION else DragMode.MOVE_CROP
                pointerId1 = event.getPointerId(0)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    // start pinch
                    dragMode = DragMode.RESIZE_CROP
                    pointerId1 = event.getPointerId(0)
                    pointerId2 = event.getPointerId(1)
                    pinchStartDist = distanceBetween(event, pointerId1, pointerId2)
                    if (pinchStartDist < 1f) pinchStartDist = 1f
                    pinchStartZoom = userZoom
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    DragMode.CAPTION -> {
                        val x = event.x.coerceIn(imgRect.left.toFloat(), imgRect.right.toFloat())
                        val y = event.y.coerceIn(imgRect.top.toFloat(),  imgRect.bottom.toFloat())
                        posX = ((x - imgRect.left) / imgRect.width().toFloat()).coerceIn(0f, 1f)
                        posY = ((y - imgRect.top)  / imgRect.height().toFloat()).coerceIn(0f, 1f)
                        onPositionChanged?.invoke(posX, posY)
                        invalidate()
                    }

                    DragMode.MOVE_CROP -> {
                        val (sx, sy) = destToSource(event.x, event.y) ?: return true
                        val sw = bmp!!.width.toFloat()
                        val sh = bmp!!.height.toFloat()

                        // compute current crop half-sizes to clamp center fully inside source
                        val (maxW, maxH) = if (sw / sh >= TARGET_AR) (sh * TARGET_AR) to sh else sw to (sw / TARGET_AR)
                        val w = maxW / userZoom
                        val h = maxH / userZoom
                        val halfW = w / 2f
                        val halfH = h / 2f

                        val cx = sx.coerceIn(halfW, sw - halfW)
                        val cy = sy.coerceIn(halfH, sh - halfH)

                        cropCenterU = (cx / sw).coerceIn(0f, 1f)
                        cropCenterV = (cy / sh).coerceIn(0f, 1f)
                        invalidate()
                    }

                    DragMode.RESIZE_CROP -> {
                        if (event.pointerCount < 2) return true
                        val dist = distanceBetween(event, pointerId1, pointerId2).coerceAtLeast(1f)
                        val scale = dist / pinchStartDist
                        // Fingers apart (scale>1) => enlarge crop => lower zoom
                        var newZoom = (pinchStartZoom / scale)
                        newZoom = newZoom.coerceIn(1.0f, 3.0f)

                        // clamp center for the new zoom so crop stays inside image
                        val sw = bmp!!.width.toFloat()
                        val sh = bmp!!.height.toFloat()
                        val (maxW, maxH) = if (sw / sh >= TARGET_AR) (sh * TARGET_AR) to sh else sw to (sw / TARGET_AR)
                        val w = maxW / newZoom
                        val h = maxH / newZoom
                        val halfW = w / 2f
                        val halfH = h / 2f

                        var cx = cropCenterU * sw
                        var cy = cropCenterV * sh
                        cx = cx.coerceIn(halfW, sw - halfW)
                        cy = cy.coerceIn(halfH, sh - halfH)

                        cropCenterU = (cx / sw).coerceIn(0f, 1f)
                        cropCenterV = (cy / sh).coerceIn(0f, 1f)
                        userZoom = newZoom
                        invalidate()
                    }

                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // when one finger of the pinch is lifted, fall back to MOVE_CROP with the remaining finger
                if (dragMode == DragMode.RESIZE_CROP) {
                    dragMode = DragMode.MOVE_CROP
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                pointerId1 = -1; pointerId2 = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun distanceBetween(ev: MotionEvent, id1: Int, id2: Int): Float {
        val i1 = ev.findPointerIndex(id1).takeIf { it >= 0 } ?: return 1f
        val i2 = ev.findPointerIndex(id2).takeIf { it >= 0 } ?: return 1f
        val dx = ev.getX(i2) - ev.getX(i1)
        val dy = ev.getY(i2) - ev.getY(i1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Export the exact 0..1 crop (source coords) that matches the preview. */
    fun exportVisibleNormCrop(): NormCrop? {
        val b = bmp ?: return null
        val r = computeCropRectInSource(b.width, b.height, userZoom, cropCenterU, cropCenterV)
        val sw = b.width.toFloat()
        val sh = b.height.toFloat()
        return NormCrop(
            (r.left   / sw).coerceIn(0f, 1f),
            (r.top    / sh).coerceIn(0f, 1f),
            (r.right  / sw).coerceIn(0f, 1f),
            (r.bottom / sh).coerceIn(0f, 1f)
        )
    }
}








