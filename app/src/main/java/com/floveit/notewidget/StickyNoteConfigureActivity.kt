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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
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
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat



class StickyNoteConfigureActivity : AppCompatActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedPhotoUri: Uri? = null

    // Simple, programmatic UI controls (no XML IDs needed)
    private lateinit var noteInput: EditText
    private lateinit var pickPhotoBtn: Button
    private lateinit var captionInput: EditText
    //    private lateinit var previewImage: ImageView
    private lateinit var saveBtn: Button
    private lateinit var preview: CaptionPreviewView

    // keep track of normalized position; default matches widget
    private var captionPosX: Float = 0.5f
    private var captionPosY: Float = 0.80f

    // NOTE text position (new, independent)
    private var notePosX: Float = 0.5f
    private var notePosY: Float = 0.80f

    private val CAPTION_MIN_SP = 9
    private val CAPTION_MAX_SP = 48
    private val CAPTION_DEFAULT_SP = 14
    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomLabel: TextView
    private var photoZoom: Float = 1.0f

    private var rotationDeg: Float = 0f


    private lateinit var inlineCaptionEdit: EditText


    private enum class Mode { PHOTO, NOTE }
    private var mode = Mode.PHOTO

    private lateinit var tabPhoto: TextView
    private lateinit var tabNote: TextView

    // rows we will toggle
    private lateinit var photoSaveRow: LinearLayout
    private lateinit var noteActionRow: LinearLayout



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

        fun selectTab(newMode: Mode) {
            mode = newMode

            // Tab visuals
            tabPhoto.styleTab(selected = (mode == Mode.PHOTO))
            tabNote.styleTab(selected = (mode == Mode.NOTE))

            // Rows
            photoSaveRow.visibility = if (mode == Mode.PHOTO) View.VISIBLE else View.GONE
            noteActionRow.visibility = if (mode == Mode.NOTE) View.VISIBLE else View.GONE

            if (mode == Mode.NOTE) {
                // NOTE MODE
                preview.setNoteMode(true)
                preview.setAllowCropGestures(false)
                preview.setCaptionPlain(true)              // render without bubble
                preview.setCaptionBubbleVisible(false)     // defend if previously editing in photo
                preview.setCaption(noteInput.text?.toString().orEmpty())
                preview.setNoteBackgroundRes(R.drawable.note_with_clip)
                preview.setPosition(notePosX, notePosY)
                preview.setCaptionVisible(true)

                // Use your custom font for note text (change R.font.note_font to your resource)
                val tf = ResourcesCompat.getFont(this, R.font.permanent_marker)  // <-- put your font here
                preview.setCaptionTypeface(tf)
                inlineCaptionEdit.typeface = tf           // while typing, show same font

            } else {
                // PHOTO MODE
                preview.setNoteMode(false)
                preview.setAllowCropGestures(true)
                preview.setCaptionPlain(false)
                preview.setCaptionBubbleVisible(true)
                preview.setCaptionTypeface(null)          // default bold for bubble
                preview.setPosition(captionPosX, captionPosY)
                preview.setCaptionVisible(true)
                // photo background is whatever the user picked; no-op here
            }

            // If inline editor is open when switching, commit it before leaving
            if (::inlineCaptionEdit.isInitialized && inlineCaptionEdit.visibility == View.VISIBLE) {
                commitInlineCaption()
            }
        }


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

        selectTab(Mode.PHOTO)


        rotationDeg = StickyNoteWidget.loadPhotoRotation(this, appWidgetId)  // 0..360
        updateRotationUi()

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

        // Note text pos (new)
        val (savedNoteX, savedNoteY) = StickyNoteWidget.loadNotePos(this, appWidgetId) // ⬅️ new API
        notePosX = savedNoteX
        notePosY = savedNoteY

//        preview.setPosition(captionPosX, captionPosY)
//        preview.setPosition(notePosX, notePosY)

        applyPositionForCurrentMode()

        // Prefill prior data if editing
        noteInput.setText(StickyNoteWidget.loadNote(this, appWidgetId))
        captionInput.setText(StickyNoteWidget.loadCaption(this, appWidgetId))
        StickyNoteWidget.loadPhotoUri(this, appWidgetId)?.let { saved ->
            runCatching { Uri.parse(saved) }.onSuccess { uri ->
                selectedPhotoUri = uri
                updatePreview(uri)
            }
            StickyNoteWidget.loadNormCrop(this, appWidgetId)?.let { norm ->
                val u = ((norm.left + norm.right) / 2f).coerceIn(0f, 1f)
                val v = ((norm.top  + norm.bottom) / 2f).coerceIn(0f, 1f)
                preview.setCropCenter(u, v)
            }
        }

        // Restore caption size & position in preview
        val currentCaptionSp = StickyNoteWidget.loadCaptionSize(this, appWidgetId)

        preview.setCaption(captionInput.text?.toString().orEmpty())
        preview.setCaptionSizeSp(currentCaptionSp)
        preview.onCaptionSizeChanged = { sp ->
            captionInput.textSize = sp
        }
//        preview.setPosition(savedX, savedY)

        applyPositionForCurrentMode()

        captionInput.textSize = currentCaptionSp

        captionInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                preview.setCaption(s?.toString().orEmpty())  // 👈 live preview text
            }
        })


        preview.onCaptionTap = { u, v ->
            if (mode == Mode.PHOTO) {
                captionPosX = u; captionPosY = v
            } else {
                notePosX = u; notePosY = v
            }
            preview.setPosition(u, v)

            // place inline editor
            preview.mapUVToView(u, v)?.let { pt ->
                val lp = inlineCaptionEdit.layoutParams as android.widget.FrameLayout.LayoutParams
                lp.leftMargin = pt.x.toInt()
                lp.topMargin  = pt.y.toInt()
                inlineCaptionEdit.layoutParams = lp
            }

            // seed with current text
            val seedText = when (mode) {
                Mode.PHOTO -> captionInput.text?.toString().orEmpty()
                Mode.NOTE  -> noteInput.text?.toString().orEmpty()
            }
            inlineCaptionEdit.setText(seedText)

            // hide any preview-drawn text to avoid double rendering
            preview.setCaptionVisible(false)

            inlineCaptionEdit.visibility = View.VISIBLE
            inlineCaptionEdit.requestFocus()
            inlineCaptionEdit.setSelection(inlineCaptionEdit.text?.length ?: 0)

            if (mode == Mode.PHOTO) preview.setCaptionBubbleVisible(false)

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(inlineCaptionEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }




        // Actions
        pickPhotoBtn.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        saveBtn.setOnClickListener { handleSave() }


        // clicks
        tabPhoto.setOnClickListener { selectTab(Mode.PHOTO) }
        tabNote.setOnClickListener  { selectTab(Mode.NOTE) }


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


        // --- Top tabs: Photo / Note ---
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })



        tabPhoto = TextView(this).apply {
            text = "Photo"; styleTab(selected = true)
        }
        tabNote = TextView(this).apply {
            text = "Note"; styleTab(selected = false)
        }
        tabs.addView(tabPhoto)
        tabs.addView(Space(this), LinearLayout.LayoutParams(dp(8), 1))
        tabs.addView(tabNote)


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



        // Stage = preview + inline editor overlay
        val previewStage = android.widget.FrameLayout(this)

        preview = CaptionPreviewView(this).apply {
            contentDescription = "Photo preview with draggable caption"
            minimumWidth  = dp(200)
            minimumHeight = dp(320)
            onPositionChanged = { x, y ->
                if (mode == Mode.PHOTO) {
                    captionPosX = x; captionPosY = y
                } else {
                    notePosX = x; notePosY = y
                }
                // keep inline editor following the caption while editing
                if (::inlineCaptionEdit.isInitialized && inlineCaptionEdit.visibility == View.VISIBLE) {
                    mapUVToView(x, y)?.let { pt ->
                        val lp = inlineCaptionEdit.layoutParams as android.widget.FrameLayout.LayoutParams
                        lp.leftMargin = pt.x.toInt()
                        lp.topMargin  = pt.y.toInt()
                        inlineCaptionEdit.layoutParams = lp
                    }
                }
            }
        }

// the inline editor that sits over the image
        inlineCaptionEdit = EditText(this).apply {
            // visual style = look like text on the photo
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = false
            maxLines = 3
            setTextColor(textPrimary)
            setHintTextColor(hintColor)
            visibility = View.GONE
            // initial text size mirrors current caption size
            setTextSize(TypedValue.COMPLEX_UNIT_SP,
                StickyNoteWidget.loadCaptionSize(this@StickyNoteConfigureActivity, appWidgetId))
            // mirror into preview as the user types
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {}
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Only mirror while editing Photo mode (bubble is hidden during edit)
                    if ((context as? StickyNoteConfigureActivity)?.mode == StickyNoteConfigureActivity.Mode.PHOTO) {
                        preview.setCaption(s?.toString().orEmpty())
                    }
                }
            })
        }

        previewStage.addView(preview, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        previewStage.addView(inlineCaptionEdit, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        previewCard.addView(previewStage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(6) })


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
//        zoomRow.addView(zoomTitle, LinearLayout.LayoutParams(
//            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
//        ))

        zoomLabel = TextView(this).apply {
            text = "100%"
            setTextColor(textSecondary)   // OK here; in-scope
            textSize = 12f
        }
//        zoomRow.addView(zoomLabel)

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
//        controls.addView(captionRowTitle)

        captionInput = EditText(this).apply {
            hint = "Write caption on photo"
            maxLines = 2
            setTextColor(textPrimary)
            setHintTextColor(hintColor)
            backgroundTintList = ColorStateList.valueOf(textSecondary)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        // Buttons helpers
        fun Button.stylize() {
            isAllCaps = false
            setTextColor(textPrimary)
            backgroundTintList = ColorStateList.valueOf(btnTint)
        }

        noteInput = EditText(this).apply { visibility = View.GONE }


        photoSaveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        controls.addView(photoSaveRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        pickPhotoBtn = Button(this).apply {
            text = "Pick Photo"
            stylize()
        }
        photoSaveRow.addView(pickPhotoBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { rightMargin = dp(6) })

        saveBtn = Button(this).apply {
            text = "Save"
            stylize()
        }
        photoSaveRow.addView(saveBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = dp(6) })

        //note row

        noteActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            visibility = View.GONE
        }
        controls.addView(noteActionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val noteSaveBtn = Button(this).apply { text = "Save"; isAllCaps = false; setTextColor(textPrimary); backgroundTintList = ColorStateList.valueOf(btnTint) }
        val noteCancelBtn = Button(this).apply { text = "Cancel"; isAllCaps = false; setTextColor(textPrimary); backgroundTintList = ColorStateList.valueOf(btnTint) }

        noteActionRow.addView(noteSaveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        noteActionRow.addView(noteCancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })

// in onCreate:
        noteSaveBtn.setOnClickListener { handleSave() }
        noteCancelBtn.setOnClickListener { handleCancel() }



        return root
    }

    fun TextView.styleTab(selected: Boolean) {
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setTextColor(if (selected) Color.WHITE else Color.parseColor("#B3FFFFFF"))
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (selected) Color.parseColor("#1E1E1E") else Color.TRANSPARENT)
        }
    }


    private fun updatePreview(uri: Uri) {
        preview.setImageUri(uri)
        preview.setCaption(captionInput.text?.toString().orEmpty())
        applyPositionForCurrentMode()
    }


    private fun applyPositionForCurrentMode() {
        if (mode == Mode.NOTE) {
            preview.setPosition(notePosX, notePosY)
        } else {
            preview.setPosition(captionPosX, captionPosY)
        }
    }

    private fun updateRotationUi() {
        preview.setRotationDeg(rotationDeg)   // see step 4
    }

    // StickyNoteConfigureActivity (class body)
    private fun showImeForCaption() {
        // focus the hidden/compact caption input and show the keyboard
        captionInput.requestFocus()
        captionInput.setSelection(captionInput.text?.length ?: 0)
        captionInput.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(captionInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }


    private fun commitInlineCaption() {
        if (!::inlineCaptionEdit.isInitialized) return
        val text = inlineCaptionEdit.text?.toString().orEmpty()

        if (mode == Mode.PHOTO) {
            captionInput.setText(text)
            preview.setCaption(text)
            preview.setCaptionBubbleVisible(true)
        } else {
            noteInput.setText(text)
            preview.setCaption(text)         // will render plain with custom font
            preview.setCaptionPlain(true)
        }

        // show preview caption again (no duplication now)
        preview.setCaptionVisible(true)

        inlineCaptionEdit.visibility = View.GONE
        inlineCaptionEdit.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(inlineCaptionEdit.windowToken, 0)
    }




    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::inlineCaptionEdit.isInitialized && inlineCaptionEdit.visibility == View.VISIBLE) {
            if (ev.action == MotionEvent.ACTION_DOWN) {
                val loc = IntArray(2)
                inlineCaptionEdit.getLocationOnScreen(loc)
                val left = loc[0]
                val top  = loc[1]
                val right = left + inlineCaptionEdit.width
                val bottom = top + inlineCaptionEdit.height

                val x = ev.rawX.toInt()
                val y = ev.rawY.toInt()
                val inside = x in left..right && y in top..bottom

                if (!inside) {
                    // User tapped outside -> commit inline edit, restore bubble (preview only)
                    commitInlineCaption()
                    return true // consume so preview.onCaptionTap won't fire
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }


    private fun handleSave() {
        val noteText = noteInput.text?.toString().orEmpty().ifBlank { "(empty)" }

        val caption = when {
            ::inlineCaptionEdit.isInitialized && inlineCaptionEdit.visibility == View.VISIBLE ->
                inlineCaptionEdit.text?.toString().orEmpty()
            else -> captionInput.text?.toString().orEmpty()
        }
        preview.setCaption(caption)

        // Show correct preview style post-edit (bubble only for Photo mode)
        if (mode == Mode.PHOTO) {
            preview.setCaptionBubbleVisible(true)
        } else {
            preview.setCaptionPlain(true)
        }

        inlineCaptionEdit.visibility = View.GONE
        inlineCaptionEdit.clearFocus()

        // Caption size
        val chosenCaptionSp = preview.getCaptionSizeSp()
        StickyNoteWidget.saveCaptionSize(this, appWidgetId, chosenCaptionSp)

        // Persist positions (once each, with clamping)
        // Persist positions based on what's currently in the preview
        val (viewX, viewY) = preview.getCaptionPosition()

        if (mode == Mode.PHOTO) {
            StickyNoteWidget.saveCaptionPos(this, appWidgetId, viewX.coerceIn(0f, 1f), viewY.coerceIn(0f, 1f))
            // keep the last known note position too (so it persists when user later switches)
            StickyNoteWidget.saveNotePos(this, appWidgetId, notePosX.coerceIn(0f, 1f), notePosY.coerceIn(0f, 1f))
        } else {
            StickyNoteWidget.saveNotePos(this, appWidgetId, viewX.coerceIn(0f, 1f), viewY.coerceIn(0f, 1f))
            // do NOT overwrite the photo caption position here
        }


        // Persist text & photo state
        StickyNoteWidget.saveNote(this, appWidgetId, noteText)
        StickyNoteWidget.saveCaption(this, appWidgetId, caption)
        StickyNoteWidget.savePhotoUri(this, appWidgetId, selectedPhotoUri?.toString())
        StickyNoteWidget.savePhotoZoom(this, appWidgetId, preview.getCropZoom())

        // Persist crop + rotation
        preview.exportVisibleNormCrop()?.let { StickyNoteWidget.saveNormCrop(this, appWidgetId, it) }
        StickyNoteWidget.savePhotoRotation(this, appWidgetId, preview.getRotationDeg())

        // Update widget
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
    private val imgRect = Rect()
    fun getCropZoom(): Float = userZoom


    // crop aspect (widget portrait)
    private val TARGET_AR = 4f / 5f               // width / height

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

    var onCaptionTap: ((u: Float, v: Float) -> Unit)? = null

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private var rotationDeg: Float = 0f

    private var lastDragX = 0f
    private var lastDragY = 0f

    private var pinchStartAngle = 0f
    private var pinchStartRotationDeg = 0f

    // caption state
    private var caption: String = ""
    private var captionSp: Float = 14f
    private var posX: Float = 0.5f
    private var posY: Float = 0.80f
    private val padH = dp(10f).toInt()
    private val padV = dp(6f).toInt()
    private val margin = dp(6f)

    // add min/max for caption SP and a callback to notify UI
    private val captionMinSp = 9f
    private val captionMaxSp = 48f
    var onCaptionSizeChanged: ((Float) -> Unit)? = null


    private var showCaptionBubble = true

    // ---- Note mode state ----
    private var noteMode = false
    fun setNoteMode(enabled: Boolean) { noteMode = enabled; invalidate() }

    // background for Note mode
    private var noteBg: android.graphics.drawable.Drawable? = null

    fun getCaptionPosition(): Pair<Float, Float> = posX to posY
    fun setNoteBackgroundRes(@DrawableRes resId: Int) {
        noteBg = ContextCompat.getDrawable(context, resId)
        invalidate()
    }

    // caption visibility (used to suppress drawing while inline editor is visible)
    private var captionVisible = true
    fun setCaptionVisible(visible: Boolean) { captionVisible = visible; invalidate() }

    // caption typeface (use custom font in Note mode)
    private var captionTypeface: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    fun setCaptionTypeface(tf: Typeface?) {
        captionTypeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        invalidate()
    }


    private var allowCropGestures = true
    fun setAllowCropGestures(allow: Boolean) { allowCropGestures = allow }

    private var drawCaptionPlain = false   // draw text without bubble
    fun setCaptionPlain(plain: Boolean) { drawCaptionPlain = plain; invalidate() }


    fun setCaptionBubbleVisible(visible: Boolean) {
        showCaptionBubble = visible
        invalidate()
    }


    fun mapUVToView(u: Float, v: Float): android.graphics.PointF? {
        val fit = lastFitRect ?: return null
        // In note mode, treat the stage as our “source”; otherwise map using the bitmap scale
        return if (noteMode || bmp == null) {
            val x = fit.left + u.coerceIn(0f,1f) * fit.width()
            val y = fit.top  + v.coerceIn(0f,1f) * fit.height()
            android.graphics.PointF(x, y)
        } else {
            val b = bmp ?: return null
            val s = fit.width().toFloat() / b.width.toFloat()
            val x = fit.left + (u.coerceIn(0f,1f) * b.width)  * s
            val y = fit.top  + (v.coerceIn(0f,1f) * b.height) * s
            android.graphics.PointF(x, y)
        }
    }



    fun setCropCenter(u: Float, v: Float) {
        cropCenterU = u.coerceIn(0f, 1f)
        cropCenterV = v.coerceIn(0f, 1f)
        invalidate()
    }


    fun getRotationDeg(): Float = rotationDeg

    fun setCaptionSizeSp(sp: Float) {
        captionSp = sp.coerceIn(captionMinSp, captionMaxSp)
        onCaptionSizeChanged?.invoke(captionSp)
        invalidate()
    }
    fun getCaptionSizeSp(): Float = captionSp


    private fun angleBetween(ev: MotionEvent, id1: Int, id2: Int): Float {
        val i1 = ev.findPointerIndex(id1).takeIf { it >= 0 } ?: return 0f
        val i2 = ev.findPointerIndex(id2).takeIf { it >= 0 } ?: return 0f
        val dx = ev.getX(i2) - ev.getX(i1)
        val dy = ev.getY(i2) - ev.getY(i1)
        return Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
    }


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

    fun setRotationDeg(deg: Float) {
        // normalize to [0, 360)
        rotationDeg = ((deg % 360f) + 360f) % 360f
        invalidate()
    }

    fun setCaption(text: String) { caption = text; invalidate() }
//    fun setCaptionSizeSp(sp: Float) { captionSp = sp; invalidate() }
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

        // Build a 4:5 stage centered in the view (used for both modes)
        val availW = imgRect.width().toFloat()
        val availH = imgRect.height().toFloat()
        val TARGET_AR = 4f / 5f
        val stage: Rect = if (availW / availH > TARGET_AR) {
            val targetW = (availH * TARGET_AR).toInt().coerceAtLeast(1)
            val left = imgRect.left + ((availW - targetW) / 2f).toInt()
            Rect(left, imgRect.top, left + targetW, imgRect.bottom)
        } else {
            val targetH = (availW / TARGET_AR).toInt().coerceAtLeast(1)
            val top = imgRect.top + ((availH - targetH) / 2f).toInt()
            Rect(imgRect.left, top, imgRect.right, top + targetH)
        }
        imgRect.set(stage)
        lastFitRect = stage // use stage for hit-testing / mapping in both modes

        if (noteMode) {
            // Draw fixed note background (cover the stage without stretching)
            noteBg?.let { d ->
                val iw = d.intrinsicWidth.takeIf { it > 0 } ?: stage.width()
                val ih = d.intrinsicHeight.takeIf { it > 0 } ?: stage.height()
                val arD = iw.toFloat() / ih.toFloat()
                val arS = stage.width().toFloat() / stage.height().toFloat()

                val dest = if (arD > arS) {
                    // drawable is wider -> match height, crop sides
                    val h = stage.height().toFloat()
                    val w = h * arD
                    val cx = stage.exactCenterX()
                    RectF(cx - w/2f, stage.top.toFloat(), cx + w/2f, stage.bottom.toFloat())
                } else {
                    // drawable is taller -> match width, crop top/bottom
                    val w = stage.width().toFloat()
                    val h = w / arD
                    val cy = stage.exactCenterY()
                    RectF(stage.left.toFloat(), cy - h/2f, stage.right.toFloat(), cy + h/2f)
                }

                d.bounds = Rect(
                    dest.left.toInt(), dest.top.toInt(),
                    dest.right.toInt(), dest.bottom.toInt()
                )
                d.draw(canvas)
            }

        }

        if(!noteMode) {
            bmp?.let { b ->
                // --- constants ---
                val BORDER_DP = 1f
                val TARGET_AR = 4f / 5f // must match widget

                // 0) Build a 4:5 stage centered in the preview view (crop window)
                val availW = imgRect.width().toFloat()
                val availH = imgRect.height().toFloat()
                val stage: Rect = if (availW / availH > TARGET_AR) {
                    val targetW = (availH * TARGET_AR).toInt().coerceAtLeast(1)
                    val left = imgRect.left + ((availW - targetW) / 2f).toInt()
                    Rect(left, imgRect.top, left + targetW, imgRect.bottom)
                } else {
                    val targetH = (availW / TARGET_AR).toInt().coerceAtLeast(1)
                    val top = imgRect.top + ((availH - targetH) / 2f).toInt()
                    Rect(imgRect.left, top, imgRect.right, top + targetH)
                }
                val cropDest = RectF(stage)         // visible photo window
                val cxStage = stage.exactCenterX()
                val cyStage = stage.exactCenterY()
                lastCropDest = cropDest

                // (For hit-testing/debug) isotropic fit of full bitmap into stage (no stretch)
                run {
                    val sFit = minOf(
                        stage.width() / b.width.toFloat(),
                        stage.height() / b.height.toFloat()
                    )
                    val dw = (b.width * sFit).toInt().coerceAtLeast(1)
                    val dh = (b.height * sFit).toInt().coerceAtLeast(1)
                    val left = stage.left + (stage.width() - dw) / 2
                    val top = stage.top + (stage.height() - dh) / 2
                    lastFitRect = Rect(left, top, left + dw, top + dh)
                }

                // 1) Compute a 4:5 crop IN SOURCE from zoom/center (no stretch)
                val cropSrcF = computeCropRectInSource(
                    b.width, b.height,
                    userZoom,
                    cropCenterU, cropCenterV
                ) // MUST return a 4:5 rect in source space
                val cropCX = cropSrcF.centerX()
                val cropCY = cropSrcF.centerY()
                val cropW = cropSrcF.width()
                val cropH = cropSrcF.height()

                // 2) Compute uniform scale to map crop → stage, then add extra scale so rotation won’t reveal corners
                val baseScale = (stage.width()
                    .toFloat() / cropW) // == stage.height / cropH because both are 4:5

                fun coverScaleForRotation(w: Float, h: Float, deg: Float): Float {
                    if (kotlin.math.abs(deg) < 0.01f) return 1f
                    val r = Math.toRadians(deg.toDouble())
                    val c = kotlin.math.abs(kotlin.math.cos(r)).toFloat()
                    val s = kotlin.math.abs(kotlin.math.sin(r)).toFloat()
                    val rw = w * c + h * s
                    val rh = w * s + h * c
                    return maxOf(rw / w, rh / h).coerceAtLeast(1f)
                }

                val coverScale = coverScaleForRotation(
                    stage.width().toFloat(),
                    stage.height().toFloat(),
                    rotationDeg
                )
                val totalScale = baseScale * coverScale

                val m = Matrix().apply {
                    // move crop center to origin in source space
                    postTranslate(-cropCX, -cropCY)
                    // scale uniformly
                    postScale(totalScale, totalScale)
                    // rotate around (0,0), which is the crop center in dest space
                    postRotate(rotationDeg)
                    // move to stage center in screen space
                    postTranslate(cxStage, cyStage)
                }

                // 4) Draw the FULL bitmap once using that matrix (so outside crop stays interactive & consistent)
                canvas.save()
                // Optional: clip to the preview bounds to avoid overdraw outside imgRect
                if (Build.VERSION.SDK_INT >= 26) {
                    val p = Path().apply { addRect(RectF(imgRect), Path.Direction.CW) }
                    canvas.clipPath(p)
                } else {
                    @Suppress("DEPRECATION")
                    canvas.clipRect(imgRect, Region.Op.INTERSECT)
                }
                canvas.concat(m)
                canvas.drawBitmap(
                    b,
                    0f,
                    0f,
                    imgPaint
                ) // draw at source origin; matrix does all mapping
                canvas.restore()

                // 5) Dim ONLY the outside area (dialog effect), keep inside crop normal
                run {
                    val dimPath = Path().apply {
                        fillType = Path.FillType.EVEN_ODD
                        addRect(RectF(imgRect), Path.Direction.CW)   // outer: entire preview area
                        addRect(cropDest, Path.Direction.CW)         // inner hole: crop window
                    }
                    val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0x99000000.toInt() // ~60% black
                        style = Paint.Style.FILL
                    }
                    canvas.drawPath(dimPath, dimPaint)
                }

                // 6) Border around the crop window
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dp(BORDER_DP)
                    color = 0xFFFFFFFF.toInt()
                }
                canvas.drawRect(cropDest, border)
            }
        }

        lastBubbleRect = null
        if (!captionVisible || caption.isBlank()) return
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = captionTypeface   // <-- use custom font when set
            setShadowLayer(dp(3f), 0f, dp(1.5f), 0x80000000.toInt())
        }

        var spx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            captionSp.coerceIn(9f, 48f),
            dm
        )
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
                StaticLayout(
                    caption,
                    tp,
                    maxOf(1, wi),
                    Layout.Alignment.ALIGN_CENTER,
                    1f,
                    0f,
                    false
                )
            }
        }

        fun StaticLayout.maxLineWidth(): Float {
            var m = 0f
            for (i in 0 until lineCount) m = maxOf(m, getLineWidth(i))
            return m
        }

        var layout = buildLayout(maxInnerW)
        fun innerW(l: StaticLayout) = l.maxLineWidth().coerceAtLeast(1f)
        fun innerH(l: StaticLayout) = l.height.toFloat().coerceAtLeast(1f)

// shrink text if needed to fit overall bounds
        while ((innerW(layout) + 2 * padH > maxBoxW || innerH(layout) + 2 * padV > maxBoxH) && spx > dp(9f)) {
            spx -= dp(1f)
            tp.textSize = spx
            layout = buildLayout(maxInnerW)
        }
        if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
            maxLines = 2; layout = buildLayout(maxInnerW)
            if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
                maxLines = 1; layout = buildLayout(maxInnerW)
            }
        }

        val innerWv = kotlin.math.min(innerW(layout), maxBoxW - 2 * padH)
        val innerHv = kotlin.math.min(innerH(layout), maxBoxH - 2 * padV)

// target center (normalized posX/posY inside image rect)
        val targetCX = imgRect.left + (imgRect.width() * posX.coerceIn(0f, 1f))
        val targetCY = imgRect.top  + (imgRect.height() * posY.coerceIn(0f, 1f))

// ---- Plain text mode (no bubble) ----
        if (drawCaptionPlain) {
            var left = targetCX - innerWv / 2f
            var top  = targetCY - innerHv / 2f

            val lowLeft  = imgRect.left.toFloat()  + margin
            val highLeft = imgRect.right.toFloat() - margin - innerWv
            val lowTop   = imgRect.top.toFloat()   + margin
            val highTop  = imgRect.bottom.toFloat()- margin - innerHv

            if (highLeft >= lowLeft) {
                if (left < lowLeft) left = lowLeft
                if (left > highLeft) left = highLeft
            } else {
                left = imgRect.left + (imgRect.width() - innerWv) / 2f
            }
            if (highTop >= lowTop) {
                if (top < lowTop) top = lowTop
                if (top > highTop) top = highTop
            } else {
                top = lowTop
            }

            // use text box for hit-testing/drag
            lastBubbleRect = RectF(left, top, left + innerWv, top + innerHv)

            // draw centered text (no bubble)
            val layoutWidth = layout.width.toFloat().coerceAtLeast(innerWv)
            val dx = (innerWv - layoutWidth) / 2f
            canvas.save()
            canvas.translate(left + dx, top)
            layout.draw(canvas)
            canvas.restore()
            return
        }

// ---- Bubble mode (Photo) ----
        if (!showCaptionBubble) return

        val bubbleW = innerWv + 2 * padH
        val bubbleH = innerHv + 2 * padV

        var left = targetCX - bubbleW / 2f
        var top  = targetCY - bubbleH / 2f

        val lowLeft  = imgRect.left.toFloat()  + margin
        val highLeft = imgRect.right.toFloat() - margin - bubbleW
        val lowTop   = imgRect.top.toFloat()   + margin
        val highTop  = imgRect.bottom.toFloat()- margin - bubbleH
        if (highLeft >= lowLeft) {
            if (left < lowLeft) left = lowLeft
            if (left > highLeft) left = highLeft
        } else {
            left = imgRect.left + (imgRect.width() - bubbleW) / 2f
        }
        if (highTop >= lowTop) {
            if (top < lowTop) top = lowTop
            if (top > highTop) top = highTop
        } else {
            top = lowTop
        }

        val bubble = RectF(left, top, left + bubbleW, top + bubbleH)
        lastBubbleRect = bubble

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        val r = dp(12f)
        canvas.drawRoundRect(bubble, r, r, bubblePaint)

// center the layout inside the bubble
        val layoutWidth = layout.width.toFloat().coerceAtLeast(innerWv)
        val dx = (innerWv - layoutWidth) / 2f
        canvas.save()
        canvas.translate(left + padH + dx, top + padV)
        layout.draw(canvas)
        canvas.restore()

    }

    // --- interaction: drag caption / move crop / pinch-resize crop ---
    private enum class DragMode { NONE, CAPTION, MOVE_CROP, RESIZE_CROP, RESIZE_CAPTION }
    private var dragMode = DragMode.NONE

    private var pointerId1 = -1
    private var pointerId2 = -1
    private var pinchStartDist = 0f
    private var pinchStartZoom = 1f
    private var pinchStartCaptionSp = 14f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bmp == null) return false

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                val hitBubble = lastBubbleRect?.contains(event.x, event.y) == true
                // ⬇️ If crop gestures are disabled (NOTE mode), don't allow MOVE_CROP
                dragMode = when {
                    hitBubble && caption.isNotBlank() -> DragMode.CAPTION
                    allowCropGestures                -> DragMode.MOVE_CROP
                    else                             -> DragMode.NONE
                }
                pointerId1 = event.getPointerId(0)

                // tap detection
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()

                // drag delta baseline
                lastDragX = event.x
                lastDragY = event.y
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    pointerId1 = event.getPointerId(0)
                    pointerId2 = event.getPointerId(1)

                    val i1 = event.findPointerIndex(pointerId1).coerceAtLeast(0)
                    val i2 = event.findPointerIndex(pointerId2).coerceAtLeast(0)
                    val midX = (event.getX(i1) + event.getX(i2)) / 2f
                    val midY = (event.getY(i1) + event.getY(i2)) / 2f
                    val pinchOnCaption = (lastBubbleRect?.contains(midX, midY) == true) && caption.isNotBlank()

                    pinchStartDist = distanceBetween(event, pointerId1, pointerId2).coerceAtLeast(1f)

                    // ⬇️ If crop gestures are disabled (NOTE mode): always resize caption on pinch
                    if (!allowCropGestures) {
                        dragMode = DragMode.RESIZE_CAPTION
                        pinchStartCaptionSp = captionSp
                        return true
                    }

                    if (pinchOnCaption) {
                        dragMode = DragMode.RESIZE_CAPTION
                        pinchStartCaptionSp = captionSp
                    } else {
                        dragMode = DragMode.RESIZE_CROP
                        pinchStartZoom = userZoom
                        pinchStartAngle = angleBetween(event, pointerId1, pointerId2)
                        pinchStartRotationDeg = rotationDeg
                    }
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
                        // ⬇️ Ignore MOVE_CROP when crop gestures are disabled (NOTE mode)
                        if (!allowCropGestures) return true

                        // delta pan in DEST pixels -> convert to SOURCE delta so image moves under fixed frame
                        val dxPx = event.x - lastDragX
                        val dyPx = event.y - lastDragY
                        lastDragX = event.x
                        lastDragY = event.y

                        val b = bmp ?: return true
                        val fit = lastFitRect ?: return true
                        val s = (fit.width().toFloat() / b.width.toFloat()).coerceAtLeast(1e-6f) // dest px per source px

                        // move image the same way the finger moves → shift crop center OPPOSITE in source
                        val duSrc = -(dxPx / s) / b.width.toFloat()   // normalized 0..1 delta
                        val dvSrc = -(dyPx / s) / b.height.toFloat()

                        // adjust center and clamp so crop stays fully inside
                        val sw = b.width.toFloat()
                        val sh = b.height.toFloat()
                        val (maxW, maxH) = if (sw / sh >= TARGET_AR) (sh * TARGET_AR) to sh else sw to (sw / TARGET_AR)
                        val w = maxW / userZoom
                        val h = maxH / userZoom
                        val halfW = w / 2f
                        val halfH = h / 2f

                        var cx = cropCenterU * sw + duSrc * sw
                        var cy = cropCenterV * sh + dvSrc * sh
                        cx = cx.coerceIn(halfW, sw - halfW)
                        cy = cy.coerceIn(halfH, sh - halfH)

                        cropCenterU = (cx / sw).coerceIn(0f, 1f)
                        cropCenterV = (cy / sh).coerceIn(0f, 1f)
                        invalidate()
                    }

                    DragMode.RESIZE_CROP -> {
                        // ⬇️ Ignore RESIZE_CROP when crop gestures are disabled (NOTE mode)
                        if (!allowCropGestures) return true

                        // pinch zoom
                        val dist = distanceBetween(event, pointerId1, pointerId2).coerceAtLeast(1f)
                        val scale = dist / pinchStartDist
                        var newZoom = (pinchStartZoom * scale).coerceIn(1.0f, 3.0f)

                        // clamp center for new zoom
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

                        // twist rotation (two-finger angle delta)
                        val ang = angleBetween(event, pointerId1, pointerId2)
                        val delta = ang - pinchStartAngle
                        rotationDeg = (pinchStartRotationDeg + delta + 3600f) % 360f

                        invalidate()
                    }

                    DragMode.RESIZE_CAPTION -> {
                        // If one finger lifted, ignore this move
                        val i1 = event.findPointerIndex(pointerId1)
                        val i2 = event.findPointerIndex(pointerId2)
                        if (event.pointerCount < 2 || i1 < 0 || i2 < 0) return true

                        val dist = distanceBetween(event, pointerId1, pointerId2).coerceAtLeast(1f)
                        val scale = dist / pinchStartDist
                        val newSp = (pinchStartCaptionSp * scale).coerceIn(captionMinSp, captionMaxSp)
                        if (newSp != captionSp) {
                            captionSp = newSp
                            onCaptionSizeChanged?.invoke(captionSp)
                            invalidate()
                        }
                    }

                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (dragMode == DragMode.RESIZE_CROP) {
                    // fall back to MOVE_CROP with remaining finger
                    dragMode = if (allowCropGestures) DragMode.MOVE_CROP else DragMode.NONE
                    val idx = if (event.getPointerId(event.actionIndex) == pointerId1) 0 else 1
                    lastDragX = event.getX(idx)
                    lastDragY = event.getY(idx)
                } else if (dragMode == DragMode.RESIZE_CAPTION) {
                    // stop pinch-resizing the caption; keep single-finger dragging on caption
                    dragMode = DragMode.CAPTION
                    pointerId2 = -1
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Single-tap → tell Activity where to place the caption and open IME
                val dt = System.currentTimeMillis() - downTime
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (dt < 250 && dx < touchSlop && dy < touchSlop && event.pointerCount == 1) {
                    val b = bmp
                    val fit = lastFitRect
                    if (b != null && fit != null) {
                        val s = fit.width().toFloat() / b.width.toFloat()
                        val u = (((event.x - fit.left) / s) / b.width).coerceIn(0f, 1f)
                        val v = (((event.y - fit.top)  / s) / b.height).coerceIn(0f, 1f)
                        onCaptionTap?.invoke(u, v)
                    }
                }
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








