package com.floveit.notewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.NinePatchDrawable
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.roundToInt

private const val PHOTO_FRAME_Y_OFFSET_DP = 10f   // tweak to taste


class StickyNoteWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) deleteNote(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == actionToggle(context)) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                toggleMode(context, id)
                val mgr = AppWidgetManager.getInstance(context)
                updateWidget(context, mgr, id)
            }
        }
    }


    companion object {
        private const val PREFS = "sticky_note_prefs"
        private const val DEFAULT_CAPTION_SP = 14f
        private const val DEFAULT_POS_X = 0.5f  // center
        private const val DEFAULT_POS_Y = 0.80f // near bottom
        private const val DEFAULT_ZOOM = 1.0f
        private const val KEY_CROP = "crop_"
        private const val KEY_ROT_DEG = "photo_rot_deg_"
        private const val DEFAULT_ROT_DEG = 0f
        private const val PREF_NOTE_POS_X = "note_pos_x_%d"
        private const val PREF_NOTE_POS_Y = "note_pos_y_%d"




        fun savePhotoRotation(ctx: Context, id: Int, deg: Float) {
            // normalize to [0,360)
            val norm = ((deg % 360f) + 360f) % 360f
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putFloat("$KEY_ROT_DEG$id", norm)
                .apply()
        }

        fun loadPhotoRotation(ctx: Context, id: Int): Float =
            ctx.getSharedPreferences(PREFS, 0)
                .getFloat("$KEY_ROT_DEG$id", DEFAULT_ROT_DEG)


        fun saveNormCrop(ctx: Context, id: Int, crop: com.floveit.notewidget.NormCrop) {
            val s = String.format(
                java.util.Locale.US,
                "%.6f,%.6f,%.6f,%.6f",
                crop.left, crop.top, crop.right, crop.bottom
            )
            ctx.getSharedPreferences(PREFS, 0)
                .edit().putString(KEY_CROP + id, s).apply()
        }

        fun loadNormCrop(ctx: Context, id: Int): com.floveit.notewidget.NormCrop? {
            val s = ctx.getSharedPreferences(PREFS, 0).getString(KEY_CROP + id, null) ?: return null
            val parts = s.split(',')
            if (parts.size != 4) return null
            val l = parts[0].toFloatOrNull() ?: return null
            val t = parts[1].toFloatOrNull() ?: return null
            val r = parts[2].toFloatOrNull() ?: return null
            val b = parts[3].toFloatOrNull() ?: return null
            // clamp defensively
            val lc = l.coerceIn(0f, 1f)
            val tc = t.coerceIn(0f, 1f)
            val rc = r.coerceIn(lc, 1f)
            val bc = b.coerceIn(tc, 1f)
            return com.floveit.notewidget.NormCrop(lc, tc, rc, bc)
        }



        fun savePhotoZoom(ctx: Context, id: Int, zoom: Float) {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putFloat("photo_zoom_$id", zoom.coerceIn(1.0f, 3.0f))
                .apply()
        }
        fun loadPhotoZoom(ctx: Context, id: Int): Float =
            ctx.getSharedPreferences(PREFS, 0)
                .getFloat("photo_zoom_$id", DEFAULT_ZOOM)


        fun saveCaptionPos(ctx: Context, id: Int, x: Float, y: Float) {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putFloat("caption_posx_$id", x.coerceIn(0f, 1f))
                .putFloat("caption_posy_$id", y.coerceIn(0f, 1f))
                .apply()
        }

        fun loadCaptionPos(ctx: Context, id: Int): Pair<Float, Float> {
            val sp = ctx.getSharedPreferences(PREFS, 0)
            val x = sp.getFloat("caption_posx_$id", DEFAULT_POS_X)
            val y = sp.getFloat("caption_posy_$id", DEFAULT_POS_Y)
            return x to y
        }


        fun saveCaptionSize(ctx: Context, id: Int, sp: Float) {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putFloat("caption_size_$id", sp)
                .apply()
        }

        fun loadCaptionSize(ctx: Context, id: Int): Float =
            ctx.getSharedPreferences(PREFS, 0).getFloat("caption_size_$id", DEFAULT_CAPTION_SP)
        // === SharedPrefs helpers ===
        fun saveNote(ctx: Context, id: Int, note: String) {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putString("note_$id", note)
                .apply()
        }
        fun loadNote(ctx: Context, id: Int): String =
            ctx.getSharedPreferences(PREFS, 0).getString("note_$id", "") ?: ""


        fun saveCaption(ctx: Context, id: Int, caption: String) {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putString("caption_$id", caption)
                .apply()
        }
        fun loadCaption(ctx: Context, id: Int): String =
            ctx.getSharedPreferences(PREFS, 0).getString("caption_$id", "") ?: ""

        fun savePhotoUri(ctx: Context, id: Int, uri: String?) {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putString("photo_$id", uri ?: "")
                .apply()
        }
        fun loadPhotoUri(ctx: Context, id: Int): String? =
            ctx.getSharedPreferences(PREFS, 0).getString("photo_$id", "")?.ifBlank { null }

        fun deleteNote(ctx: Context, id: Int) {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .remove("note_$id")
                .remove("caption_$id")
                .remove("photo_$id")
                .remove("caption_size_$id")
                .remove("caption_posx_$id")
                .remove("caption_posy_$id")
                .remove("$KEY_ROT_DEG$id")
                .apply()

        }


        fun saveNotePos(ctx: Context, id: Int, x: Float, y: Float) {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putFloat(PREF_NOTE_POS_X.format(id), x.coerceIn(0f, 1f))
                .putFloat(PREF_NOTE_POS_Y.format(id), y.coerceIn(0f, 1f))
                .apply()
        }

        fun loadNotePos(ctx: Context, id: Int): Pair<Float, Float> {
            val sp = ctx.getSharedPreferences(PREFS, 0)
            val x = sp.getFloat(PREF_NOTE_POS_X.format(id), DEFAULT_POS_X)
            val y = sp.getFloat(PREF_NOTE_POS_Y.format(id), DEFAULT_POS_Y)
            return x to y
        }


        private enum class WidgetMode { NOTE, PHOTO }

        private fun saveMode(ctx: Context, id: Int, mode: WidgetMode) {
            ctx.getSharedPreferences(PREFS, 0)
                .edit().putString("mode_$id", mode.name).apply()
        }
        private fun loadMode(ctx: Context, id: Int): WidgetMode {
            val s = ctx.getSharedPreferences(PREFS, 0).getString("mode_$id", null)
            return runCatching { if (s != null) WidgetMode.valueOf(s) else WidgetMode.NOTE }
                .getOrElse { WidgetMode.NOTE }
        }
        private fun toggleMode(ctx: Context, id: Int): WidgetMode {
            val next = if (loadMode(ctx, id) == WidgetMode.NOTE) WidgetMode.PHOTO else WidgetMode.NOTE
            saveMode(ctx, id, next)
            return next
        }

        private fun actionToggle(ctx: Context) = "${ctx.packageName}.WIDGET_TOGGLE_MODE"

        private fun buildTogglePendingIntent(ctx: Context, id: Int): PendingIntent {
            val intent = Intent(ctx, StickyNoteWidget::class.java).apply {
                action = actionToggle(ctx)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("sticky://toggle/$id")
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(ctx, 10_000 + id, intent, flags)
        }

        fun updateWidget(ctx: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            // --- Load saved state ---
            val note          = loadNote(ctx, appWidgetId)
            val caption       = loadCaption(ctx, appWidgetId)
            val photoUri      = loadPhotoUri(ctx, appWidgetId)?.let { Uri.parse(it) }
            val captionSizeSp = loadCaptionSize(ctx, appWidgetId)
//            val (posX, posY)  = loadCaptionPos(ctx, appWidgetId)
            val (capX, capY)  = loadCaptionPos(ctx, appWidgetId)
            val (noteX, noteY) = loadNotePos(ctx, appWidgetId)
            val photoZoom     = loadPhotoZoom(ctx, appWidgetId)
            val norm          = loadNormCrop(ctx, appWidgetId) // may be null
            val mode          = loadMode(ctx, appWidgetId)

            // --- Resolve target canvas size from host options ---
            val (w, h) = resolveTargetSizePx(ctx, appWidgetManager, appWidgetId)

            // --- Draw bitmap for the current mode ---
            val effectiveMode = if (mode == WidgetMode.PHOTO && photoUri == null) WidgetMode.NOTE else mode
            val userRotDeg = loadPhotoRotation(ctx, appWidgetId)
            val bitmap = when (effectiveMode) {
//                WidgetMode.NOTE -> makeNoteOnlyBitmap(ctx, note, w, h)
                WidgetMode.NOTE  -> makeNoteOnlyBitmap(
                    ctx, note, w, h,
                    posX = noteX, posY = noteY,
                    userCaptionSp = captionSizeSp   // reuse caption size for note text for now
                )
                WidgetMode.PHOTO -> makePhotoOnlyBitmap(
                    ctx = ctx,
                    uri = photoUri,
                    caption = caption,
                    widthPx = w,
                    heightPx = h,
                    cropToFill = true,
                    userCaptionSp = captionSizeSp,
//                    posX = posX,
//                    posY = posY,
                    posX = capX,
                    posY = capY,
                    userZoom = photoZoom,
                    norm = norm,
                    extraRotationDeg = userRotDeg
                )
            }

            // --- Build RemoteViews ---
            val views = RemoteViews(ctx.packageName, R.layout.widget_sticky_note).apply {
                setImageViewBitmap(R.id.ivNote, bitmap)
                setInt(R.id.ivNote, "setBackgroundColor", Color.TRANSPARENT)
                setViewPadding(R.id.root_layout, 0, 0, 0, 0)

                // Buttons
                setImageViewResource(R.id.btnToggle, android.R.drawable.ic_menu_rotate)
                setViewVisibility(R.id.btnToggle, View.VISIBLE)
                setImageViewResource(R.id.btnSettings, android.R.drawable.ic_menu_preferences)
                setViewVisibility(R.id.btnSettings, View.VISIBLE)

                // Clicks
                runCatching { setOnClickPendingIntent(R.id.btnToggle, buildTogglePendingIntent(ctx, appWidgetId)) }
                runCatching { setOnClickPendingIntent(R.id.btnSettings, buildConfigPendingIntent(ctx, appWidgetId)) }
            }

            // --- Anchor overlay to the NOTEPAD BOUNDS (which is also where we draw the clip) ---
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()

            // Read the note layer from the layer-list to get correct 9-patch padding
            val layer = AppCompatResources.getDrawable(ctx, R.drawable.note_with_clip) as? android.graphics.drawable.LayerDrawable
            val noteLayer = layer?.findDrawableByLayerId(R.id.layer_note)

            val pad = Rect().also { r ->
                if (noteLayer is NinePatchDrawable) {
                    noteLayer.getPadding(r)
                } else {
                    val d = dp(12f); r.set(d, d, d, d)
                }
            }

            // Note content area from 9-patch
            val contentRect = Rect(pad.left, pad.top, w - pad.right, h - pad.bottom)

            // Same 4:5 portrait rect centered in the content area
            val TARGET_AR = 4f / 5f
            val availW = contentRect.width().toFloat().coerceAtLeast(1f)
            val availH = contentRect.height().toFloat().coerceAtLeast(1f)
            val imgRect: Rect = if (availW / availH > TARGET_AR) {
                val targetW = (availH * TARGET_AR).toInt().coerceAtLeast(1)
                val left = contentRect.left + ((availW - targetW) / 2f).toInt()
                Rect(left, contentRect.top, left + targetW, contentRect.bottom)
            } else {
                val targetH = (availW / TARGET_AR).toInt().coerceAtLeast(1)
                val top = contentRect.top + ((availH - targetH) / 2f).toInt()
                Rect(contentRect.left, top, contentRect.right, top + targetH)
            }


            // Use the SAME inset you use when drawing the (smaller) note in PHOTO mode
            val noteContentInsetDp = if (effectiveMode == WidgetMode.PHOTO) 6f else 6f
            val insetPx = dp(noteContentInsetDp)

            // Inner content we targeted for note; clamp to widget
            val noteInner = Rect(
                (imgRect.left + insetPx).coerceAtLeast(0),
                (imgRect.top + insetPx).coerceAtLeast(0),
                (imgRect.right - insetPx).coerceAtMost(w),
                (imgRect.bottom - insetPx).coerceAtMost(h)
            )

            // OUTER notepad bounds = inner content expanded by 9-patch padding; clamp to widget
            val noteBounds = Rect(
                (noteInner.left - pad.left).coerceAtLeast(0),
                (noteInner.top - pad.top).coerceAtLeast(0),
                (noteInner.right + pad.right).coerceAtMost(w),
                (noteInner.bottom + pad.bottom).coerceAtMost(h)
            )

            // Small gap so buttons aren’t glued to the corner
            val edgeGap = dp(8f)

            // Padding so overlay_pad’s top|end sits on the notepad/clip top|end
            val padLeft   = (noteBounds.left + edgeGap).coerceAtLeast(0)
            val padTop    = (noteBounds.top + edgeGap).coerceAtLeast(0)
            val padRight  = (w - noteBounds.right + edgeGap).coerceAtLeast(0)
            val padBottom = (h - noteBounds.bottom + edgeGap).coerceAtLeast(0)

            views.setViewPadding(R.id.overlay_pad, padLeft, padTop, padRight, padBottom)

            // Hide the toggle if notepad area is too small (avoid overlap on tiny widgets)
            val minSide = minOf(noteBounds.width(), noteBounds.height())
            val btnSizeApprox = dp(44f) // rough touch target
            views.setViewVisibility(
                R.id.btnToggle,
                if (minSide < btnSizeApprox + dp(8f)) View.GONE else View.VISIBLE
            )

            // --- Push to system ---
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }


        private fun buildConfigPendingIntent(ctx: Context, id: Int): PendingIntent {
            val intent = Intent(ctx, StickyNoteConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("sticky://config/$id")
            }
            val flags = if (Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getActivity(ctx, id, intent, flags)
        }


        private fun resolveTargetSizePx(
            ctx: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ): Pair<Int, Int> {
            val dm = ctx.resources.displayMetrics
            val opts = appWidgetManager.getAppWidgetOptions(appWidgetId)

            val minWdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

            val defaultSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 180f, dm).toInt()

            // ✅ Prefer MIN width/height; fall back to a modest default
            val wPx = if (minWdp > 0) dpToPx(dm, minWdp) else defaultSizePx
            val hPx = if (minHdp > 0) dpToPx(dm, minHdp) else defaultSizePx

            // Soft cap just in case
            val cap = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 600f, dm).toInt()
            return Pair(wPx.coerceAtMost(cap), hPx.coerceAtMost(cap))
        }

        private fun dpToPx(dm: android.util.DisplayMetrics, dp: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), dm).toInt()

        // ADDED: draws centered, multi-line caption with translucent bubble
        private fun drawCaptionOverlay(
            ctx: Context,
            canvas: Canvas,
            text: String,
            within: Rect,
            anchorY: Float = 0.80f // 0.0 = top, 1.0 = bottom
        ) {
            if (text.isBlank()) return

            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

            val padH = dp(10f).toInt()
            val padV = dp(6f).toInt()
            val maxBoxHeight = (within.height() * 0.35f).toInt()

            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(dp(3f), 0f, dp(1.5f), 0x80000000.toInt())
            }

            val availW = (within.width() - padH * 2).coerceAtLeast(1)
            var sp = (within.width() / 18f).coerceIn(14f, 28f)
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)

            fun buildLayout(): StaticLayout {
                @Suppress("DEPRECATION")
                return if (Build.VERSION.SDK_INT >= 23) {
                    StaticLayout.Builder
                        .obtain(text, 0, text.length, paint, availW)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1f)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setMaxLines(4)
                        .build()
                } else {
                    StaticLayout(text, paint, availW, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
                }
            }

            var layout = buildLayout()
            while (layout.height > maxBoxHeight && sp > 10f) {
                sp -= 1f
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
                layout = buildLayout()
            }

            val boxW = layout.width + padH * 2
            val boxH = layout.height + padV * 2

            val anchor = within.top + within.height() * anchorY
            var left = within.left + (within.width() - boxW) / 2f
            var top  = anchor - boxH / 2f

            val margin = dp(6f)
            top  = top.coerceIn(within.top + margin, within.bottom - boxH - margin)
            left = left.coerceIn(within.left + margin, within.right - boxW - margin)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
            val r = dp(12f)
            val bubble = RectF(left, top, left + boxW, top + boxH)
            canvas.drawRoundRect(bubble, r, r, bgPaint)

            canvas.save()
            canvas.translate(left + padH, top + padV)
            layout.draw(canvas)
            canvas.restore()
        }


        private fun dp(context: Context, v: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()


        // Draw the CLIP artwork as a full-frame overlay
        private fun drawClipOverlay(
            context: Context,
            canvas: Canvas,
            bounds: Rect   // pass full widget or inner content area
        ) {
            // IMPORTANT: if your file is stickynote_clip.9.png, the id is stickynote_clip
            val d = AppCompatResources.getDrawable(context, R.drawable.clipbg) ?: return
            d.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
            d.draw(canvas)
        }


        fun makeNoteOnlyBitmap(
            ctx: Context,
            noteText: String,
            widthPx: Int,
            heightPx: Int,
            posX: Float,
            posY: Float,
            userCaptionSp: Float
        ): Bitmap {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()

            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            val layer = AppCompatResources.getDrawable(ctx, R.drawable.note_with_clip)
            val (noteBg, clipFg) = if (layer is LayerDrawable) {
                layer.findDrawableByLayerId(R.id.layer_note) to layer.findDrawableByLayerId(R.id.layer_clip)
            } else {
                AppCompatResources.getDrawable(ctx, R.drawable.notebg) to
                        AppCompatResources.getDrawable(ctx, R.drawable.clipbg)
            }

            val pad = Rect().also {
                if (noteBg is NinePatchDrawable) noteBg.getPadding(it) else {
                    val d = dp(12f); it.set(d, d, d, d)
                }
            }
            val contentW = (widthPx - pad.left - pad.right).coerceAtLeast(0)
            val contentH = (heightPx - pad.top - pad.bottom).coerceAtLeast(0)

            // Same portrait 4:5 box you use in Photo mode
            val contentRect = Rect(pad.left, pad.top, widthPx - pad.right, heightPx - pad.bottom)
            val TARGET_AR = 4f / 5f
            val availW = contentRect.width().toFloat()
            val availH = contentRect.height().toFloat()
            val stageRect = if (availW / availH > TARGET_AR) {
                val targetW = (availH * TARGET_AR).toInt().coerceAtLeast(1)
                val left = contentRect.left + ((availW - targetW) / 2f).toInt()
                Rect(left, contentRect.top, left + targetW, contentRect.bottom)
            } else {
                val targetH = (availW / TARGET_AR).toInt().coerceAtLeast(1)
                val top = contentRect.top + ((availH - targetH) / 2f).toInt()
                Rect(contentRect.left, top, contentRect.right, top + targetH)
            }

            // If you want the note slightly smaller even in note-only, nudge this from 0 to 4–6dp.
            val innerInset = dp(6f)
            val inner = Rect(
                stageRect.left + innerInset,
                stageRect.top + innerInset,
                stageRect.right - innerInset,
                stageRect.bottom - innerInset
            )
            val noteBounds = Rect(
                inner.left - pad.left,
                inner.top - pad.top,
                inner.right + pad.right,
                inner.bottom + pad.bottom
            )

            // NOTE background
            noteBg?.setBounds(noteBounds.left, noteBounds.top, noteBounds.right, noteBounds.bottom)
            noteBg?.draw(c)

            // Text inside inner content
            val textW = (inner.width()).coerceAtLeast(1)
            val textH = (inner.height()).coerceAtLeast(1)
//            drawNoteText(ctx, c, noteText, inner.left, inner.top, textW, textH)
            drawNoteTextAtPos(
                ctx, c, noteText,
                left = pad.left, top = pad.top,
                width = contentW, height = contentH,
                posX = posX, posY = posY,
                userCaptionSp = userCaptionSp
            )


        // CLIP overlay on top
//            clipFg?.setBounds(0, 0, widthPx, heightPx)
            clipFg?.setBounds(noteBounds.left, noteBounds.top, noteBounds.right, noteBounds.bottom)
            clipFg?.draw(c)

            return bmp
        }


        private fun makePhotoOnlyBitmap(
            ctx: Context,
            uri: Uri?,
            caption: String,
            widthPx: Int,
            heightPx: Int,
            cropToFill: Boolean,
            userCaptionSp: Float,
            posX: Float,
            posY: Float,
            userZoom: Float,
            norm: NormCrop?,
            extraRotationDeg: Float
        ): Bitmap {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()

            val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // --- Layer-list that contains note + clip ---
            val layer = AppCompatResources.getDrawable(ctx, R.drawable.noteuf_with_clip)
            val (noteBg, clipFg) = if (layer is LayerDrawable) {
                layer.findDrawableByLayerId(R.id.layer_note) to layer.findDrawableByLayerId(R.id.layer_clip)
            } else {
                // Fallback if someone replaces the XML accidentally
                AppCompatResources.getDrawable(ctx, R.drawable.notebguf) to
                        AppCompatResources.getDrawable(ctx, R.drawable.clipbg)
            }

            // Use the NOTE's 9-patch padding to define content area
            val pad = Rect().also { r ->
                if (noteBg is NinePatchDrawable) noteBg.getPadding(r) else {
                    val d = dp(12f); r.set(d, d, d, d)
                }
            }

            // Portrait 4:5 box centered inside the note content
            val contentRect = Rect(pad.left, pad.top, widthPx - pad.right, heightPx - pad.bottom)
            val TARGET_AR = 4f / 5f
            val availW = contentRect.width().toFloat()
            val availH = contentRect.height().toFloat()
            val imgRect = if (availW / availH > TARGET_AR) {
                val targetW = (availH * TARGET_AR).toInt().coerceAtLeast(1)
                val left = contentRect.left + ((availW - targetW) / 2f).toInt()
                Rect(left, contentRect.top, left + targetW, contentRect.bottom)
            } else {
                val targetH = (availW / TARGET_AR).toInt().coerceAtLeast(1)
                val top = contentRect.top + ((availH - targetH) / 2f).toInt()
                Rect(contentRect.left, top, contentRect.right, top + targetH)
            }

            // Make the NOTEPAD a hair smaller than the photo so it never sticks out
            val noteContentInset = dp(6f) // tweak 4–10dp to taste
            val noteInner = Rect(
                imgRect.left + noteContentInset,
                imgRect.top + noteContentInset,
                imgRect.right - noteContentInset,
                imgRect.bottom - noteContentInset
            )
            val noteBounds = Rect(
                noteInner.left - pad.left,
                noteInner.top - pad.top,
                noteInner.right + pad.right,
                noteInner.bottom + pad.bottom
            ).apply {
                if (left < 0) left = 0
                if (top < 0) top = 0
                if (right > widthPx) right = widthPx
                if (bottom > heightPx) bottom = heightPx
            }

            // 1) NOTE (background)
            noteBg?.setBounds(noteBounds.left, noteBounds.top, noteBounds.right, noteBounds.bottom)
            noteBg?.draw(canvas)

            // 2) PHOTO (sandwiched between note and clip)
            if (uri != null) {
//                val rot = -6f
                val baseTilt = -6f
                val rotCard = baseTilt                    // frame tilt only
                val shrink  = shrinkToFitRotation(imgRect.width(), imgRect.height(), rotCard)

                val yOffPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, PHOTO_FRAME_Y_OFFSET_DP, dm
                ).toInt()

                drawPhotoCardWithCaption(
                    ctx = ctx,
                    canvas = canvas,
                    uri = uri,
                    x = imgRect.left,
//                    y = imgRect.top,
                    y = imgRect.top + yOffPx,
                    w = imgRect.width(),
                    h = imgRect.height(),
                    caption = caption,
                    cropToFill = cropToFill,
                    userCaptionSp = userCaptionSp,
                    posX = posX, posY = posY,
                    rotationDeg = rotCard,
                    shrinkPct = shrink,
                    userZoom = userZoom,
                    norm = norm,
                    photoRotationDeg = extraRotationDeg
                )
            }

            // 3) CLIP (foreground) — draw over the whole widget (your current behavior)
//            clipFg?.setBounds(0, 0, widthPx, heightPx)
            clipFg?.setBounds(noteBounds.left, noteBounds.top, noteBounds.right, noteBounds.bottom)
            clipFg?.draw(canvas)

            return out
        }





        private fun drawNoteText(
        ctx: Context, canvas: Canvas,
        text: String, x: Int, y: Int, width: Int, height: Int
    ) {
        val dm = ctx.resources.displayMetrics
        val innerPad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, dm).toInt()

        // compute available area without permanently translating the canvas
        val availW = (width  - 2*innerPad).coerceAtLeast(1)
        val availH = (height - 2*innerPad).coerceAtLeast(1)

        val extraStart = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, dm).toInt()
        val extraTop   = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, dm).toInt()

        val tf = runCatching { ResourcesCompat.getFont(ctx, R.font.permanent_marker) }.getOrNull()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = tf ?: Typeface.DEFAULT
        }

        var sp = 60f
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
        var layout = makeStaticLayout(text, paint, availW)
        while (layout.height > availH && sp > 6f) {
            sp -= 1f
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
            layout = makeStaticLayout(text, paint, availW)
        }

        // 🔒 Localize all movement
        canvas.save()
        canvas.translate((x + innerPad + extraStart).toFloat(), (y + innerPad + extraTop).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }
        private fun drawNoteTextAtPos(
            ctx: Context,
            canvas: Canvas,
            text: String,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            posX: Float,
            posY: Float,
            userCaptionSp: Float
        ) {
            if (text.isBlank() || width <= 0 || height <= 0) return

            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

            val margin = dp(6f)
            val padH   = dp(10f)
            val padV   = dp(6f)

            val maxBoxW = (width  - 2f * margin).coerceAtLeast(1f)
            val maxBoxH = (height - 2f * margin).coerceAtLeast(1f)
            val maxInnerW = (maxBoxW - 2 * padH).toInt().coerceAtLeast(1)

            val tf = runCatching { ResourcesCompat.getFont(ctx, R.font.permanent_marker) }.getOrNull()

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(40, 40, 40) // note text color
                typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    userCaptionSp.coerceIn(9f, 48f),
                    dm
                )
            }

            var maxLines = 6
            fun buildLayout(availInnerW: Int): StaticLayout {
                @Suppress("DEPRECATION")
                return if (Build.VERSION.SDK_INT >= 23) {
                    StaticLayout.Builder
                        .obtain(text, 0, text.length, tp, maxOf(1, availInnerW))
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1f)
                        .setMaxLines(maxLines)
                        .build()
                } else {
                    StaticLayout(text, tp, maxOf(1, availInnerW), Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
                }
            }
            fun StaticLayout.maxLineWidth(): Float { var m = 0f; for (i in 0 until lineCount) m = maxOf(m, getLineWidth(i)); return m }

            var layout = buildLayout(maxInnerW)
            fun innerW(l: StaticLayout) = l.maxLineWidth().coerceAtLeast(1f)
            fun innerH(l: StaticLayout) = l.height.toFloat().coerceAtLeast(1f)

            // Shrink to fit the content area
            while ((innerW(layout) + 2 * padH > maxBoxW || innerH(layout) + 2 * padV > maxBoxH)
                && tp.textSize > dp(9f)
            ) {
                tp.textSize -= dp(1f)
                layout = buildLayout(maxInnerW)
            }
            if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
                maxLines = 3; layout = buildLayout(maxInnerW)
                if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
                    maxLines = 1; layout = buildLayout(maxInnerW)
                }
            }

            val innerWv = kotlin.math.min(innerW(layout), maxBoxW - 2 * padH)
            val innerHv = kotlin.math.min(innerH(layout), maxBoxH - 2 * padV)

            // Center at normalized pos inside the content area, clamp
            val targetCX = left + (width  * posX.coerceIn(0f, 1f))
            val targetCY = top  + (height * posY.coerceIn(0f, 1f))
            var drawLeft = targetCX - innerWv / 2f
            var drawTop  = targetCY - innerHv / 2f

            val lowLeft  = left.toFloat()             + margin
            val highLeft = (left + width).toFloat()   - margin - innerWv
            val lowTop   = top.toFloat()              + margin
            val highTop  = (top + height).toFloat()   - margin - innerHv

            if (highLeft >= lowLeft) {
                if (drawLeft < lowLeft) drawLeft = lowLeft
                if (drawLeft > highLeft) drawLeft = highLeft
            } else {
                drawLeft = left + (width - innerWv) / 2f
            }
            if (highTop >= lowTop) {
                if (drawTop < lowTop) drawTop = lowTop
                if (drawTop > highTop) drawTop = highTop
            } else {
                drawTop = lowTop
            }

            canvas.save()
            // horizontally center the layout content in our inner box
            val layoutWidth = layout.width.toFloat().coerceAtLeast(innerWv)
            val dx = (innerWv - layoutWidth) / 2f
            canvas.translate(drawLeft + dx, drawTop)
            layout.draw(canvas)
            canvas.restore()
        }


        @Suppress("DEPRECATION")
            private fun makeStaticLayout(text: String, tp: TextPaint, width: Int): StaticLayout {
                return if (Build.VERSION.SDK_INT >= 23) {
                    StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .build()
                } else {
                    StaticLayout(text, tp, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
                }
            }


        private fun drawPhotoCardWithCaption(
            ctx: Context,
            canvas: Canvas,
            uri: Uri,
            x: Int, y: Int, w: Int, h: Int,
            caption: String,
            cropToFill: Boolean = true,                 // we always fill via crop (never stretch)
            userCaptionSp: Float = DEFAULT_CAPTION_SP,
            posX: Float = DEFAULT_POS_X,
            posY: Float = DEFAULT_POS_Y,
            rotationDeg: Float = -6f,                   // frame tilt ONLY
            shrinkPct: Float,                           // shrink for the frame tilt
            userZoom: Float = 1.0f,                     // ignored when norm != null
            norm: NormCrop?,                            // normalized crop rect (0..1) or null
            photoRotationDeg: Float = 0f                // rotate only the photo by ±15°
        ) {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

            // --- Load bitmap (scaled if possible) ---
            val bmp = runCatching { loadScaledBitmap(ctx, uri, w, h) }.getOrNull()
                ?: runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                ?: return
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // --- Card transform: rotate & shrink ONLY the frame/card ---
            val cx = x + w / 2f
            val cy = y + h / 2f
            canvas.save()
            canvas.translate(cx, cy)
            if (rotationDeg != 0f) canvas.rotate(rotationDeg)

            val drawW = (w * shrinkPct).toInt().coerceAtLeast(1)
            val drawH = (h * shrinkPct).toInt().coerceAtLeast(1)
            val cardRect = Rect(-drawW / 2, -drawH / 2, drawW / 2, drawH / 2)

            // Card background
            val corner = dp(10f)
            canvas.drawRoundRect(RectF(cardRect), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

            // --- Photo area inside the card (KEEP 4:5 like preview) ---
            val PHOTO_ASPECT = 4f / 5f
            val pad = dp(10f).toInt()
            val container = Rect(
                cardRect.left + pad,
                cardRect.top + pad,
                cardRect.right - pad,
                cardRect.bottom - (pad + 50f).toInt() // your existing bottom inset
            )

            fun fitAspectInside(r: Rect, ar: Float): Rect {
                val availW = r.width().toFloat()
                val availH = r.height().toFloat()
                return if (availW / availH > ar) {
                    val tw = (availH * ar).toInt().coerceAtLeast(1)
                    val left = r.left + ((availW - tw) / 2f).toInt()
                    Rect(left, r.top, left + tw, r.bottom)
                } else {
                    val th = (availW / ar).toInt().coerceAtLeast(1)
                    val top = r.top + ((availH - th) / 2f).toInt()
                    Rect(r.left, top, r.right, top + th)
                }
            }

            val imgRect = fitAspectInside(container, PHOTO_ASPECT)
            val destAR = imgRect.width().toFloat() / imgRect.height().toFloat()

            // --- Source rect: either from norm (trim to aspect) or center-crop + userZoom ---
            val src: Rect = if (norm != null) {
                val bw = bmp.width; val bh = bmp.height
                val x0 = (norm.left   * bw).toInt().coerceIn(0, bw - 1)
                val y0 = (norm.top    * bh).toInt().coerceIn(0, bh - 1)
                val x1 = (norm.right  * bw).toInt().coerceIn(x0 + 1, bw)
                val y1 = (norm.bottom * bh).toInt().coerceIn(y0 + 1, bh)
                Rect(x0, y0, x1, y1).also { r ->
                    val ar = r.width().toFloat() / r.height().toFloat()
                    if (kotlin.math.abs(ar - destAR) > 1e-3f) {
                        if (ar > destAR) {
                            // too wide → trim width around center
                            val newW = (r.height() * destAR).toInt().coerceAtLeast(1)
                            val cxp = (r.left + r.right) / 2
                            r.set(cxp - newW / 2, r.top, cxp + newW / 2, r.bottom)
                        } else {
                            // too tall → trim height around center
                            val newH = (r.width() / destAR).toInt().coerceAtLeast(1)
                            val cyp = (r.top + r.bottom) / 2
                            r.set(r.left, cyp - newH / 2, r.right, cyp + newH / 2)
                        }
                    }
                }
            } else {
                // Center-crop to dest aspect; then tighten by userZoom (no stretch)
                val bw = bmp.width; val bh = bmp.height
                val bmpAR = bw.toFloat() / bh.toFloat()
                val base = if (bmpAR > destAR) {
                    val newW = (bh * destAR).toInt().coerceAtLeast(1)
                    val left = (bw - newW) / 2
                    Rect(left, 0, left + newW, bh)
                } else {
                    val newH = (bw / destAR).toInt().coerceAtLeast(1)
                    val top = (bh - newH) / 2
                    Rect(0, top, bw, top + newH)
                }
                if (userZoom <= 1f) base else {
                    val z = userZoom.coerceAtMost(3f)
                    val newW = (base.width()  / z).toInt().coerceAtLeast(1)
                    val newH = (base.height() / z).toInt().coerceAtLeast(1)
                    val cxp = (base.left + base.right) / 2
                    val cyp = (base.top + base.bottom) / 2
                    Rect(cxp - newW / 2, cyp - newH / 2, cxp + newW / 2, cyp + newH / 2)
                }
            }

            // --- Draw PHOTO (no stretch): rotate + uniform scale on canvas, dest keeps aspect === src ---
            val rImg = (corner - dp(2f)).coerceAtLeast(0f)
            val clipPath = android.graphics.Path().apply {
                addRoundRect(RectF(imgRect), rImg, rImg, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)

            val imgCX = (imgRect.left + imgRect.right) / 2f
            val imgCY = (imgRect.top + imgRect.bottom) / 2f

            // compute how much to scale so a rotated rect still covers the clip area
            val fit = shrinkToFitRotation(imgRect.width(), imgRect.height(), photoRotationDeg)
            val coverScale = if (kotlin.math.abs(photoRotationDeg) < 0.01f) 1f else 1f / fit

            canvas.save()
            if (photoRotationDeg != 0f) canvas.rotate(photoRotationDeg, imgCX, imgCY)
            if (coverScale != 1f) canvas.scale(coverScale, coverScale, imgCX, imgCY)

            // IMPORTANT: draw to EXACT imgRect; canvas scaling is UNIFORM → no stretch
            canvas.drawBitmap(bmp, src, imgRect, paint)

            canvas.restore()   // undo photo rotate/scale
            canvas.restore()   // undo clip

            // Thin white border around the photo area
            canvas.drawRoundRect(RectF(imgRect), rImg, rImg, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.WHITE
            })

            // Card stroke
            canvas.drawRoundRect(RectF(cardRect), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = dp(1.25f); color = Color.argb(40, 0, 0, 0)
            })

            // --- Caption (same style/logic; normalized pos clamped to imgRect) ---
            if (caption.isNotBlank()) {
                val margin = dp(6f)
                val padH = dp(10f)
                val padV = dp(6f)

                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setShadowLayer(dp(3f), 0f, dp(1.5f), 0x80000000.toInt())
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, userCaptionSp, dm)
                }

                var maxLines = 4
                val maxBoxW = imgRect.width().toFloat()  - 2f * margin
                val maxBoxH = imgRect.height().toFloat() - 2f * margin
                val maxInnerW = (maxBoxW - 2 * padH).toInt().coerceAtLeast(1)

                @Suppress("DEPRECATION")
                fun buildLayout(wi: Int): StaticLayout =
                    if (Build.VERSION.SDK_INT >= 23) {
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

                fun StaticLayout.maxLineWidth(): Float { var m = 0f; for (i in 0 until lineCount) m = maxOf(m, getLineWidth(i)); return m }

                var layout = buildLayout(maxInnerW)
                fun innerW(l: StaticLayout) = l.maxLineWidth().coerceAtLeast(1f)
                fun innerH(l: StaticLayout) = l.height.toFloat().coerceAtLeast(1f)

                while ((innerW(layout) + 2 * padH > maxBoxW || innerH(layout) + 2 * padV > maxBoxH) && tp.textSize > dp(9f)) {
                    tp.textSize -= dp(1f)
                    layout = buildLayout(maxInnerW)
                }
                if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
                    maxLines = 2; layout = buildLayout(maxInnerW)
                    if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
                        maxLines = 1; layout = buildLayout(maxInnerW)
                    }
                }

                val innerWv = minOf(innerW(layout), maxBoxW - 2 * padH)
                val innerHv = minOf(innerH(layout), maxBoxH - 2 * padV)
                val bubbleW = innerWv + 2 * padH
                val bubbleH = innerHv + 2 * padV

                val targetCX = imgRect.left + (imgRect.width() * posX.coerceIn(0f, 1f))
                val targetCY = imgRect.top  + (imgRect.height() * posY.coerceIn(0f, 1f))
                var left = targetCX - bubbleW / 2f
                var top  = targetCY - bubbleH / 2f

                val lowLeft  = imgRect.left.toFloat()  + dp(6f)
                val highLeft = imgRect.right.toFloat() - dp(6f) - bubbleW
                val lowTop   = imgRect.top.toFloat()   + dp(6f)
                val highTop  = imgRect.bottom.toFloat()- dp(6f) - bubbleH
                if (highLeft >= lowLeft) { if (left < lowLeft) left = lowLeft; if (left > highLeft) left = highLeft } else {
                    left = imgRect.left + (imgRect.width() - bubbleW) / 2f
                }
                if (highTop >= lowTop) { if (top < lowTop) top = lowTop; if (top > highTop) top = highTop } else {
                    top = lowTop
                }

                val bubble = RectF(left, top, left + bubbleW, top + bubbleH)
                canvas.drawRoundRect(bubble, dp(12f), dp(12f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 })
                val layoutWidth = layout.width.toFloat().coerceAtLeast(innerWv)
                val dx = (innerWv - layoutWidth) / 2f
                canvas.save()
                canvas.translate(left + padH + dx, top + padV)
                layout.draw(canvas)
                canvas.restore()
            }

            // restore after the frame tilt
            canvas.restore()
        }








        private fun loadScaledBitmap(ctx: Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? {
            return runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { in1 ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(in1, null, opts)
                    var sample = 1
                    while ((opts.outWidth / sample) > reqW * 2 || (opts.outHeight / sample) > reqH * 2) {
                        sample *= 2
                    }
                    ctx.contentResolver.openInputStream(uri)?.use { in2 ->
                        val o = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeStream(in2, null, o)
                    }
                }
            }.getOrNull()
        }

        fun requestAllWidgetsRefresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StickyNoteWidget::class.java))
            for (id in ids) updateWidget(ctx, mgr, id)
        }
    }
}

private fun shrinkToFitRotation(w: Int, h: Int, rotationDeg: Float): Float {
    // Scale factor so a w×h rectangle, after rotation, still fits inside w×h
    val theta = Math.toRadians(rotationDeg.toDouble())
    val c = Math.abs(Math.cos(theta))
    val s = Math.abs(Math.sin(theta))

    // Rotated bounding box: w' = w*c + h*s,  h' = w*s + h*c
    val wPrime = w * c + h * s
    val hPrime = w * s + h * c

    // scales needed on each axis to fit back into w×h
    val sx = w / wPrime
    val sy = h / hPrime
    val sMin = Math.min(sx, sy)

    // small safety so strokes/shadows don’t touch edges
    return (sMin * 1.00).toFloat()
}

private fun cropToAspectWithin(r: Rect, sw: Int, sh: Int, targetAR: Float) {
    val rw = r.width().toFloat().coerceAtLeast(1f)
    val rh = r.height().toFloat().coerceAtLeast(1f)
    val ar = rw / rh
    if (kotlin.math.abs(ar - targetAR) < 1e-3f) return

    if (ar > targetAR) {
        // too wide → trim width
        val newW = (rh * targetAR).toInt()
        val cx = (r.left + r.right) / 2
        r.set(cx - newW / 2, r.top, cx + newW / 2, r.bottom)
    } else {
        // too tall → trim height
        val newH = (rw / targetAR).toInt()
        val cy = (r.top + r.bottom) / 2
        r.set(r.left, cy - newH / 2, r.right, cy + newH / 2)
    }
}


private fun fitAspectInside(container: Rect, aspect: Float): Rect {
    val cw = container.width().toFloat()
    val ch = container.height().toFloat()
    val car = cw / ch
    return if (car > aspect) {
        // container too wide → clamp width
        val w = (ch * aspect).toInt().coerceAtLeast(1)
        val left = container.left + (container.width() - w) / 2
        Rect(left, container.top, left + w, container.bottom)
    } else {
        // container too tall → clamp height
        val h = (cw / aspect).toInt().coerceAtLeast(1)
        val top = container.top + (container.height() - h) / 2
        Rect(container.left, top, container.right, top + h)
    }
}


