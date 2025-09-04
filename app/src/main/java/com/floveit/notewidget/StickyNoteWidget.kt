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



//        fun saveNormCrop(ctx: Context, id: Int, crop: com.floveit.notewidget.NormCrop) {
//            val s = "${crop.left},${crop.top},${crop.right},${crop.bottom}"
//            ctx.getSharedPreferences(PREFS, 0)
//                .edit().putString(KEY_CROP + id, s).apply()
//        }

        fun saveNormCrop(ctx: Context, id: Int, crop: com.floveit.notewidget.NormCrop) {
            val s = String.format(
                java.util.Locale.US,
                "%.6f,%.6f,%.6f,%.6f",
                crop.left, crop.top, crop.right, crop.bottom
            )
            ctx.getSharedPreferences(PREFS, 0)
                .edit().putString(KEY_CROP + id, s).apply()
        }

//        fun loadNormCrop(ctx: Context, id: Int): com.floveit.notewidget.NormCrop? {
//            val s = ctx.getSharedPreferences(PREFS, 0).getString(KEY_CROP + id, null) ?: return null
//            val p = s.split(',')
//            if (p.size != 4) return null
//            return try {
//                com.floveit.notewidget.NormCrop(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
//            } catch (_: Throwable) { null }
//        }

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
                .apply()
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
            val (posX, posY)  = loadCaptionPos(ctx, appWidgetId)
            val photoZoom     = loadPhotoZoom(ctx, appWidgetId)
            val norm          = loadNormCrop(ctx, appWidgetId) // may be null
            val mode          = loadMode(ctx, appWidgetId)

            // --- Resolve target canvas size from host options ---
            val (w, h) = resolveTargetSizePx(ctx, appWidgetManager, appWidgetId)

            // --- Draw bitmap for the current mode ---
            val effectiveMode = if (mode == WidgetMode.PHOTO && photoUri == null) WidgetMode.NOTE else mode
            val bitmap = when (effectiveMode) {
                WidgetMode.NOTE -> makeNoteOnlyBitmap(ctx, note, w, h)
                WidgetMode.PHOTO -> makePhotoOnlyBitmap(
                    ctx = ctx,
                    uri = photoUri,
                    caption = caption,
                    widthPx = w,
                    heightPx = h,
                    cropToFill = true,
                    userCaptionSp = captionSizeSp,
                    posX = posX,
                    posY = posY,
                    userZoom = photoZoom,
                    norm = norm
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



//        fun updateWidget(ctx: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
//            val note          = loadNote(ctx, appWidgetId)
//            val caption       = loadCaption(ctx, appWidgetId)
//            val photoUri      = loadPhotoUri(ctx, appWidgetId)?.let { Uri.parse(it) }
//            val captionSizeSp = loadCaptionSize(ctx, appWidgetId)
//            val (posX, posY)  = loadCaptionPos(ctx, appWidgetId)
//            val photoZoom = loadPhotoZoom(ctx, appWidgetId)
//            val norm = loadNormCrop(ctx, appWidgetId)
//
//            val (w, h) = resolveTargetSizePx(ctx, appWidgetManager, appWidgetId)
//
//            val mode = loadMode(ctx, appWidgetId)
//            val safeMode = if (mode == WidgetMode.PHOTO && photoUri == null) WidgetMode.NOTE else mode
//
//            val bitmap = when (safeMode) {
//                WidgetMode.NOTE  -> makeNoteOnlyBitmap(ctx, note, w, h)
//                WidgetMode.PHOTO -> makePhotoOnlyBitmap(
//                    ctx, photoUri, caption, w, h,
//                    cropToFill = true,
//                    userCaptionSp = captionSizeSp,
//                    posX = posX, posY = posY,
//                    userZoom = photoZoom,
//                    norm = norm
//                )
//            }
//
////            val views = RemoteViews(ctx.packageName, R.layout.widget_sticky_note).apply {
////                setImageViewBitmap(R.id.ivNote, bitmap)
////                setInt(R.id.ivNote, "setBackgroundColor", Color.TRANSPARENT)
////
////                // Keep the icon explicit (so XML doesn't get overridden later by any default)
////                setImageViewResource(R.id.btnToggle, R.drawable.imagew)
////                setViewVisibility(R.id.btnToggle, android.view.View.VISIBLE)
////
////                // Taps
////                setOnClickPendingIntent(R.id.ivNote, buildConfigPendingIntent(ctx, appWidgetId))
////                runCatching { setOnClickPendingIntent(R.id.btnToggle, buildTogglePendingIntent(ctx, appWidgetId)) }
////            }
//
//            val views = RemoteViews(ctx.packageName, R.layout.widget_sticky_note).apply {
//                setImageViewBitmap(R.id.ivNote, bitmap)
//                setInt(R.id.ivNote, "setBackgroundColor", Color.TRANSPARENT)
//
//                // keep roots tight
//                setViewPadding(R.id.root_layout, 0, 0, 0, 0)
//
//                // Ensure both buttons show icons and are visible
//                setImageViewResource(R.id.btnToggle, android.R.drawable.ic_menu_rotate)
//                setViewVisibility(R.id.btnToggle, android.view.View.VISIBLE)
//
//                // NEW: settings button icon + visibility
//                setImageViewResource(R.id.btnSettings, android.R.drawable.ic_menu_preferences)
//                setViewVisibility(R.id.btnSettings, android.view.View.VISIBLE)
//
//                // ❌ REMOVE this line so the entire widget is NOT clickable anymore:
//                // setOnClickPendingIntent(R.id.ivNote, buildConfigPendingIntent(ctx, appWidgetId))
//
//                // ✅ Keep toggle working
//                runCatching { setOnClickPendingIntent(R.id.btnToggle, buildTogglePendingIntent(ctx, appWidgetId)) }
//
//                // ✅ NEW: Settings button opens configuration
//                runCatching { setOnClickPendingIntent(R.id.btnSettings, buildConfigPendingIntent(ctx, appWidgetId)) }
//            }
//
//
//            // --- Align btnToggle to the card’s corner for THIS mode ---
//            val dm = ctx.resources.displayMetrics
//            fun dp(f: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, f, dm).toInt()
//
//// pick the background used by the drawing code for this mode
////            val bgResForPadding = if (safeMode == WidgetMode.NOTE) {
////                R.drawable.notebg     // your note skin
////            } else {
////                R.drawable.clipbg     // your clip/photo skin
////            }
//            val bgResForPadding = R.drawable.clipbg
//
//// read its 9-patch content insets
//            val np = android.graphics.Rect()
//            val nine = ContextCompat.getDrawable(ctx, bgResForPadding) as? NinePatchDrawable
//            val has9 = nine?.getPadding(np) == true
//
//            val padL = if (has9) np.left   else dp(12f)
//            val padT = if (has9) np.top    else dp(12f)
//            val padR = if (has9) np.right  else dp(12f)
//            val padB = if (has9) np.bottom else dp(12f)
//
//// small visual offset so the button isn’t flush with the card
//            val edgeGap = dp(10f)
//
//// push the overlay in by the same insets, so the button (top|end) hugs the card
//            views.setViewPadding(
//                R.id.overlay_pad,
//                padL + edgeGap,
//                padT + edgeGap,
//                padR + edgeGap,
//                padB + edgeGap
//            )
//
//// Avoid overlap on ultra-slim widgets based on *usable* inner width:
////            val usableW = w - (padL + padR)
////            val btnSize   = dp(48f)
////            val btnBuffer = dp(20f) // 10dp marginEnd + 10dp safety
////            views.setViewVisibility(
////                R.id.btnToggle,
////                if (usableW < (btnSize + btnBuffer)) android.view.View.GONE else android.view.View.VISIBLE
////            )
//
//            val marginEndXml = dp(10f)        // your ImageButton layout_marginEnd
//            val usableW = w - (padL + padR) - 2*edgeGap
//            val btnSize = dp(48f)
//            val btnBuffer = marginEndXml + dp(10f) // keep ~10dp safety beyond the XML margin
//            views.setViewVisibility(
//                R.id.btnToggle,
//                if (usableW < (btnSize + btnBuffer)) View.GONE else View.VISIBLE
//            )
//
//
//            appWidgetManager.updateAppWidget(appWidgetId, views)
//        }


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


//        private fun makeNoteOnlyBitmap(
//            ctx: Context,
//            noteText: String,
//            widthPx: Int,
//            heightPx: Int
//        ): Bitmap {
//            val dm = ctx.resources.displayMetrics
//            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
//            val c = Canvas(bmp)
//            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
//
//            val bg = runCatching { ContextCompat.getDrawable(ctx, R.drawable.notebg) }.getOrNull()
//            bg?.let {
//                it.setBounds(0, 0, widthPx, heightPx)
//                it.draw(c)
//            } ?: run { c.drawColor(Color.rgb(255, 249, 196)) }
//
//            val pad = Rect().also {
//                if (bg is NinePatchDrawable) bg.getPadding(it) else {
//                    val d = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
//                    it.set(d, d, d, d)
//                }
//            }
//
//            val contentW = (widthPx - pad.left - pad.right).coerceAtLeast(0)
//            val contentH = (heightPx - pad.top - pad.bottom).coerceAtLeast(0)
//
//            drawNoteText(ctx, c, noteText, pad.left, pad.top, contentW, contentH)
////            drawPinOverlay(ctx, c, Rect(0, 0, widthPx, heightPx))
////            drawClipOverlay(ctx, c, Rect(0, 0, widthPx, heightPx))
//
////            val padClip = Rect().also {
////                val clipD = ContextCompat.getDrawable(ctx, R.drawable.clipbg)
////                if (clipD is NinePatchDrawable) {
////                    clipD.getPadding(it)
////                } else {
////                    val d = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
////                    it.set(d, d, d, d)
////                }
////            }
////
////// Compute how much the clip overlay needs to shift to visually match notebg
////            val dx = pad.left - padClip.left
////            val dy = pad.top  - padClip.top
////
////// Draw the clip overlay with the offset bounds
////            drawClipOverlay(
////                ctx, c,
////                Rect(dx, dy, widthPx + dx, heightPx + dy)
////            )
//
//            drawClipOverlay(ctx, c, Rect(0, 0, widthPx, heightPx))
//
//            return bmp
//        }
//private fun makeNoteOnlyBitmap(
//    ctx: Context,
//    noteText: String,
//    widthPx: Int,
//    heightPx: Int
//): Bitmap {
//    val dm = ctx.resources.displayMetrics
//    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
//
//    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
//    val c = Canvas(bmp)
//    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
//
//    val bg = AppCompatResources.getDrawable(ctx, R.drawable.notebg)
//    val clip = AppCompatResources.getDrawable(ctx, R.drawable.clipbg)
//
//    // Read note 9-patch padding
//    val pad = Rect().also {
//        if (bg is NinePatchDrawable) bg.getPadding(it) else {
//            val d = dp(12f); it.set(d, d, d, d)
//        }
//    }
//
//    // Compute the same portrait 4:5 rect as Photo mode
//    val contentRect = Rect(pad.left, pad.top, widthPx - pad.right, heightPx - pad.bottom)
//    val TARGET_AR = 4f / 5f
//    val availW = contentRect.width().toFloat()
//    val availH = contentRect.height().toFloat()
//    val stageRect = if (availW / availH > TARGET_AR) {
//        val targetW = (availH * TARGET_AR).toInt().coerceAtLeast(1)
//        val left = contentRect.left + ((availW - targetW) / 2f).toInt()
//        Rect(left, contentRect.top, left + targetW, contentRect.bottom)
//    } else {
//        val targetH = (availW / TARGET_AR).toInt().coerceAtLeast(1)
//        val top = contentRect.top + ((availH - targetH) / 2f).toInt()
//        Rect(contentRect.left, top, contentRect.right, top + targetH)
//    }
//
//    // Draw notepad so that its INNER content == stageRect (exact match with photo box)
//    // If you still want it slightly smaller, inset stageRect by a few dp here.
//    val innerInset = dp(0f) // set to dp(4f..6f) if you want a hair smaller than photo
//    val inner = Rect(
//        stageRect.left + innerInset,
//        stageRect.top + innerInset,
//        stageRect.right - innerInset,
//        stageRect.bottom - innerInset
//    )
//    val noteBounds = Rect(
//        inner.left - pad.left,
//        inner.top - pad.top,
//        inner.right + pad.right,
//        inner.bottom + pad.bottom
//    )
//    bg?.setBounds(noteBounds.left, noteBounds.top, noteBounds.right, noteBounds.bottom)
//    bg?.draw(c)
//
//    // Lay out text inside the inner (note content) area
//    val textW = (inner.width()).coerceAtLeast(1)
//    val textH = (inner.height()).coerceAtLeast(1)
//    drawNoteText(ctx, c, noteText, inner.left, inner.top, textW, textH)
//
//    // Clip overlay on top (full widget); button alignment handled in updateWidget(..)
//    clip?.setBounds(0, 0, widthPx, heightPx)
//    clip?.draw(c)
//
//    return bmp
//}

        private fun makeNoteOnlyBitmap(
            ctx: Context,
            noteText: String,
            widthPx: Int,
            heightPx: Int
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
            drawNoteText(ctx, c, noteText, inner.left, inner.top, textW, textH)

            // CLIP overlay on top
//            clipFg?.setBounds(0, 0, widthPx, heightPx)
            clipFg?.setBounds(noteBounds.left, noteBounds.top, noteBounds.right, noteBounds.bottom)
            clipFg?.draw(c)

            return bmp
        }




//        private fun makePhotoOnlyBitmap(
//            ctx: Context,
//            uri: Uri?,
//            caption: String,
//            widthPx: Int,
//            heightPx: Int,
//            cropToFill: Boolean,
//            userCaptionSp: Float,
//            posX: Float,
//            posY: Float,
//            userZoom: Float,
//            norm: NormCrop?
//        ): Bitmap {
//            val dm = ctx.resources.displayMetrics
//            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
//            val c = Canvas(bmp)
//            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
//
////            val bg = runCatching { ContextCompat.getDrawable(ctx, R.drawable.clipbg) }.getOrNull()
////            if (bg != null) {
////                bg.setBounds(0, 0, widthPx, heightPx)
////                bg.draw(c)
////            } else {
////                c.drawColor(Color.rgb(255, 249, 196))
////            }
////
////            val pad = Rect().also {
////                if (bg is NinePatchDrawable) bg.getPadding(it) else {
////                    val d = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
////                    it.set(d, d, d, d)
////                }
////            }
//
//            val noteBg = AppCompatResources.getDrawable(ctx, R.drawable.notebg)  // e.g. sticky_note_bg_9
//            val clipBg = AppCompatResources.getDrawable(ctx, R.drawable.clipbg)  // e.g. stickynote_clip_9
//
//// 1) Paint both as the background (note first, then clip on top)
//            noteBg?.setBounds(0, 0, widthPx, heightPx)
//            noteBg?.draw(c)
//
//            clipBg?.setBounds(0, 0, widthPx, heightPx)
//            clipBg?.draw(c)
//
//// 2) Compute content padding FROM THE NOTE BACKGROUND
//            val pad = Rect().also { r ->
//                if (noteBg is NinePatchDrawable) noteBg.getPadding(r) else {
//                    val d = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
//                    r.set(d, d, d, d)
//                }
//            }
//
//            val contentW = (widthPx - pad.left - pad.right).coerceAtLeast(0)
//            val contentH = (heightPx - pad.top - pad.bottom).coerceAtLeast(0)
//
//            val contentRect = Rect(pad.left, pad.top, widthPx - pad.right, heightPx - pad.bottom)
//
//// Force a portrait aspect-ratio box (e.g., 3:4) centered inside contentRect
//            val TARGET_AR = 3f / 4f // width / height (portrait)
//            val availW = contentRect.width().toFloat()
//            val availH = contentRect.height().toFloat()
//            val availAR = availW / availH
//
//            val imgRect: Rect = if (availAR > TARGET_AR) {
//                // too wide → clamp width
//                val targetW = (availH * TARGET_AR).toInt()
//                val left = contentRect.left + ((availW - targetW) / 2f).toInt()
//                Rect(left, contentRect.top, left + targetW, contentRect.bottom)
//            } else {
//                // too tall → clamp height
//                val targetH = (availW / TARGET_AR).toInt()
//                val top = contentRect.top + ((availH - targetH) / 2f).toInt()
//                Rect(contentRect.left, top, contentRect.right, top + targetH)
//            }
//
//            val innerPad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, dm).toInt()
//
//
//            val extraStart = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, dm).toInt()
//            val extraTop   = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30f, dm).toInt()
//            val extraEnd   = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, dm).toInt()
//            val extraBot   = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, dm).toInt()
//
//
//            val x = pad.left
//            val y = pad.top
//            val w = (contentW).coerceAtLeast(0)
//            val h = (contentH).coerceAtLeast(0)
//
//            if (uri != null) {
//                val rot = -6f
//                val shrink = shrinkToFitRotation(w, h, rot)
//
//                drawPhotoCardWithCaption(
//                    ctx, c, uri,
//                    x, y, w, h,
//                    caption,
//                    cropToFill = cropToFill,
//                    userCaptionSp = userCaptionSp,
//                    posX = posX,
//                    posY = posY,
//                    rotationDeg = -6f,
//                    shrinkPct = shrink,
//                    userZoom = userZoom,
//                    norm = norm
//                )
////                drawPinOverlay(ctx, c, Rect(x, y, x + w, y + h))
//
//            }
//            drawClipOverlay(ctx, c, Rect(0, 0, widthPx, heightPx))
//            return bmp
//        }


//        private fun makePhotoOnlyBitmap(
//            ctx: Context,
//            uri: Uri?,
//            caption: String,
//            widthPx: Int,
//            heightPx: Int,
//            cropToFill: Boolean,
//            userCaptionSp: Float,
//            posX: Float,
//            posY: Float,
//            userZoom: Float,
//            norm: NormCrop?
//        ): Bitmap {
//            val dm = ctx.resources.displayMetrics
//            val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
//            val canvas = Canvas(out)
//            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
//
//            // Backgrounds
//            val noteBg = AppCompatResources.getDrawable(ctx, R.drawable.notebg)
//            val clipBg = AppCompatResources.getDrawable(ctx, R.drawable.clipbg)
//            noteBg?.setBounds(0, 0, widthPx, heightPx); noteBg?.draw(canvas)
//            clipBg?.setBounds(0, 0, widthPx, heightPx); clipBg?.draw(canvas)
//
//            // Padding from note background
//            val pad = Rect().also { r ->
//                if (noteBg is NinePatchDrawable) noteBg.getPadding(r) else {
//                    val d = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
//                    r.set(d, d, d, d)
//                }
//            }
//
//            // Content area (inside the 9-patch)
//            val contentRect = Rect(pad.left, pad.top, widthPx - pad.right, heightPx - pad.bottom)
//
//            // Force a portrait 3:4 box centered inside contentRect
//            val TARGET_AR = 4f / 5f // width / height (portrait)
//            val availW = contentRect.width().toFloat()
//            val availH = contentRect.height().toFloat()
//            val availAR = availW / availH
//
//            val imgRect = if (availAR > TARGET_AR) {
//                // Too wide → clamp width
//                val targetW = (availH * TARGET_AR).toInt()
//                val left = contentRect.left + ((availW - targetW) / 2f).toInt()
//                Rect(left, contentRect.top, left + targetW, contentRect.bottom)
//            } else {
//                // Too tall → clamp height
//                val targetH = (availW / TARGET_AR).toInt()
//                val top = contentRect.top + ((availH - targetH) / 2f).toInt()
//                Rect(contentRect.left, top, contentRect.right, top + targetH)
//            }
//
//            if (uri != null) {
//                // Keep your small tilt, but make sure it fits that portrait rect
//                val rot = -6f
//                val shrink = shrinkToFitRotation(imgRect.width(), imgRect.height(), rot)  // uses your helper :contentReference[oaicite:1]{index=1}
//
//                // This routine should honor `norm` if present (exact user crop), or center-crop fallback.
//                drawPhotoCardWithCaption(
//                    ctx, canvas, uri,
//                    imgRect.left, imgRect.top, imgRect.width(), imgRect.height(),
//                    caption,
//                    cropToFill = cropToFill,     // fill the portrait rect; no stretching (src->dest with aspect)
//                    userCaptionSp = userCaptionSp,
//                    posX = posX, posY = posY,
//                    rotationDeg = rot,
//                    shrinkPct = shrink,
//                    userZoom = userZoom,
//                    norm = norm                  // when non-null, this wins (exact preview crop)
//                )
//            }
//
//            // Finish with the clip overlay spanning the whole widget
//            drawClipOverlay(ctx, canvas, Rect(0, 0, widthPx, heightPx))  // uses your existing helper :contentReference[oaicite:2]{index=2}
//
//            return out
//        }

//        private fun makePhotoOnlyBitmap(
//            ctx: Context,
//            uri: Uri?,
//            caption: String,
//            widthPx: Int,
//            heightPx: Int,
//            cropToFill: Boolean,
//            userCaptionSp: Float,
//            posX: Float,
//            posY: Float,
//            userZoom: Float,
//            norm: NormCrop?
//        ): Bitmap {
//            val dm = ctx.resources.displayMetrics
//            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
//
//            val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
//            val canvas = Canvas(out)
//            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
//
//            // Load skins
//            val noteBg = AppCompatResources.getDrawable(ctx, R.drawable.notebg)
//            val clipBg = AppCompatResources.getDrawable(ctx, R.drawable.clipbg)
//
//            // Read notepad 9-patch padding
//            val pad = Rect().also { r ->
//                if (noteBg is NinePatchDrawable) noteBg.getPadding(r) else {
//                    val d = dp(12f); r.set(d, d, d, d)
//                }
//            }
//
//            // Content area (inside 9-patch), then the portrait 4:5 box (centered)
//            val contentRect = Rect(pad.left, pad.top, widthPx - pad.right, heightPx - pad.bottom)
//            val TARGET_AR = 4f / 5f
//            val availW = contentRect.width().toFloat()
//            val availH = contentRect.height().toFloat()
//            val imgRect = if (availW / availH > TARGET_AR) {
//                val targetW = (availH * TARGET_AR).toInt().coerceAtLeast(1)
//                val left = contentRect.left + ((availW - targetW) / 2f).toInt()
//                Rect(left, contentRect.top, left + targetW, contentRect.bottom)
//            } else {
//                val targetH = (availW / TARGET_AR).toInt().coerceAtLeast(1)
//                val top = contentRect.top + ((availH - targetH) / 2f).toInt()
//                Rect(contentRect.left, top, contentRect.right, top + targetH)
//            }
//
//            // Make the notepad slightly smaller than the photo rect (so it never sticks out)
//            val noteContentInset = dp(6f) // tweak 4–10dp
//            val noteInner = Rect(
//                imgRect.left + noteContentInset,
//                imgRect.top + noteContentInset,
//                imgRect.right - noteContentInset,
//                imgRect.bottom - noteContentInset
//            )
//            // Expand by 9-patch padding to get drawable bounds
//            val noteBounds = Rect(
//                noteInner.left - pad.left,
//                noteInner.top - pad.top,
//                noteInner.right + pad.right,
//                noteInner.bottom + pad.bottom
//            ).apply {
//                if (left < 0) left = 0
//                if (top < 0) top = 0
//                if (right > widthPx) right = widthPx
//                if (bottom > heightPx) bottom = heightPx
//            }
//            noteBg?.setBounds(noteBounds.left, noteBounds.top, noteBounds.right, noteBounds.bottom)
//            noteBg?.draw(canvas)
//
//            // Photo card into the portrait box
//            if (uri != null) {
//                val rot = -6f
//                val shrink = shrinkToFitRotation(imgRect.width(), imgRect.height(), rot)
//                drawPhotoCardWithCaption(
//                    ctx = ctx,
//                    canvas = canvas,
//                    uri = uri,
//                    x = imgRect.left, y = imgRect.top,
//                    w = imgRect.width(), h = imgRect.height(),
//                    caption = caption,
//                    cropToFill = cropToFill,
//                    userCaptionSp = userCaptionSp,
//                    posX = posX, posY = posY,
//                    rotationDeg = rot,
//                    shrinkPct = shrink,
//                    userZoom = userZoom,
//                    norm = norm
//                )
//            }
//
//            // Clip overlay covers full widget; buttons alignment is handled in updateWidget(..)
//            clipBg?.setBounds(0, 0, widthPx, heightPx)
//            clipBg?.draw(canvas)
//
//            return out
//        }

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
            norm: NormCrop?
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
                val rot = -6f
                val shrink = shrinkToFitRotation(imgRect.width(), imgRect.height(), rot)

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
                    rotationDeg = rot,
                    shrinkPct = shrink,
                    userZoom = userZoom,
                    norm = norm
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



//            private fun drawPhotoCardWithCaption(
//                ctx: Context,
//                canvas: Canvas,
//                uri: Uri,
//                x: Int,
//                y: Int,
//                w: Int,
//                h: Int,
//                caption: String,
//                cropToFill: Boolean = false,
//                userCaptionSp: Float = DEFAULT_CAPTION_SP,
//                posX: Float = DEFAULT_POS_X,
//                posY: Float = DEFAULT_POS_Y,
//                rotationDeg: Float = -6f,   // tilt: negative = left, positive = right
//                shrinkPct: Float,
//                userZoom: Float = 1.0f,
//                norm: NormCrop?
//            ) {
//                val dm = ctx.resources.displayMetrics
//                fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
//
//                // --- rotate/scale only this card block ---
//                val cx = x + w / 2f
//                val cy = y + h / 2f
//                canvas.save()
//                if (rotationDeg != 0f) canvas.rotate(rotationDeg, cx, cy)
//                if (shrinkPct != 1f)   canvas.scale(shrinkPct, shrinkPct, cx, cy)
//
//                // --- Card background ---
//                val corner = dp(10f)
//                val cardRectF = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
//                canvas.drawRoundRect(cardRectF, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
//
//                // Image area (inside card)
//                val pad = dp(6f).toInt()
//                val imgRect = Rect(x + pad, y + pad, x + w - pad, y + h - pad)
//
//
//                runCatching { loadScaledBitmap(ctx, uri, imgRect.width(), imgRect.height()) }.getOrNull()?.let { bmp ->
//                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
//
//                    val dest = imgRect
//                    val destW = dest.width().toFloat()
//                    val destH = dest.height().toFloat()
//                    val cornerPx = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, dm) - TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, dm)).coerceAtLeast(0f)
//
//                    // --- PICK SOURCE WINDOW ---
//                    val src: Rect = if (norm != null) {
//                        // Use the exact visible crop the user chose in preview
//                        val x0 = (norm.left   * bmp.width).toInt().coerceIn(0, bmp.width - 1)
//                        val y0 = (norm.top    * bmp.height).toInt().coerceIn(0, bmp.height - 1)
//                        val x1 = (norm.right  * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
//                        val y1 = (norm.bottom * bmp.height).toInt().coerceIn(y0 + 1, bmp.height)
//                        Rect(x0, y0, x1, y1)
//                    } else if (cropToFill) {
//                        // old center-crop fallback
//                        val srcW = bmp.width
//                        val srcH = bmp.height
//                        val destAR = destW / destH
//                        val srcAR  = srcW.toFloat() / srcH.toFloat()
//                        if (srcAR > destAR) {
//                            val newW = (srcH * destAR).toInt().coerceAtLeast(1)
//                            val left = (srcW - newW) / 2
//                            Rect(left, 0, left + newW, srcH)
//                        } else {
//                            val newH = (srcW / destAR).toInt().coerceAtLeast(1)
//                            val top = (srcH - newH) / 2
//                            Rect(0, top, srcW, top + newH)
//                        }
//                    } else {
//                        // old fit-center fallback (draw later with a dest that letterboxes)
//                        Rect(0, 0, bmp.width, bmp.height)
//                    }
//
//                    // --- DRAW ---
//                    canvas.save()
//                    val clipPath = android.graphics.Path().apply { addRoundRect(RectF(dest), cornerPx, cornerPx, android.graphics.Path.Direction.CW) }
//                    canvas.clipPath(clipPath)
//
//                    if (norm != null || cropToFill) {
//                        // src -> fill dest exactly (no extra auto-crop now)
//                        canvas.drawBitmap(bmp, src, dest, paint)
//                    } else {
//                        // fit-center fallback
//                        val scale = minOf(destW / bmp.width, destH / bmp.height)
//                        val dw = (bmp.width * scale).toInt().coerceAtLeast(1)
//                        val dh = (bmp.height * scale).toInt().coerceAtLeast(1)
//                        val dx = dest.left + (dest.width() - dw) / 2
//                        val dy = dest.top  + (dest.height() - dh) / 2
//                        canvas.drawBitmap(bmp, null, Rect(dx, dy, dx + dw, dy + dh), paint)
//                    }
//                    canvas.restore()
//
//                    bmp.recycle()
//                }
//
//
//                // Card stroke
//                canvas.drawRoundRect(cardRectF, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
//                    style = Paint.Style.STROKE
//                    strokeWidth = dp(1.25f)
//                    color = Color.argb(40, 0, 0, 0)
//                })
//
//                // --- Caption bubble (posX/posY normalized inside imgRect) ---
//                if (caption.isNotBlank()) {
//                    val padH = dp(10f).toInt()
//                    val padV = dp(6f).toInt()
//                    val margin = dp(6f)
//
//                    val maxBoxW = (imgRect.width().toFloat()  - 2f * margin).coerceAtLeast(1f)
//                    val maxBoxH = (imgRect.height().toFloat() - 2f * margin).coerceAtLeast(1f)
//
//                    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
//                        color = Color.WHITE
//                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
//                        setShadowLayer(dp(3f), 0f, dp(1.5f), 0x80000000.toInt())
//                    }
//                    var sp = userCaptionSp.coerceIn(9f, 48f)
//                    tp.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
//
//                    var maxLines = 4
//                    fun buildLayout(availInnerW: Int): StaticLayout {
//                        val wFor = maxOf(1, availInnerW)
//                        @Suppress("DEPRECATION")
//                        return if (Build.VERSION.SDK_INT >= 23) {
//                            StaticLayout.Builder
//                                .obtain(caption, 0, caption.length, tp, wFor)
//                                .setAlignment(Layout.Alignment.ALIGN_CENTER)
//                                .setIncludePad(false)
//                                .setLineSpacing(0f, 1f)
//                                .setEllipsize(TextUtils.TruncateAt.END)
//                                .setMaxLines(maxLines)
//                                .build()
//                        } else {
//                            StaticLayout(caption, tp, wFor, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
//                        }
//                    }
//                    fun StaticLayout.maxLineWidth(): Float {
//                        var m = 0f; for (i in 0 until lineCount) m = maxOf(m, getLineWidth(i)); return m
//                    }
//
//                    val maxInnerW = (maxBoxW - 2 * padH).toInt().coerceAtLeast(1)
//                    var layout = buildLayout(maxInnerW)
//                    fun bubbleInnerW(l: StaticLayout) = l.maxLineWidth().coerceAtLeast(1f)
//                    fun bubbleInnerH(l: StaticLayout) = l.height.toFloat().coerceAtLeast(1f)
//
//                    while ((bubbleInnerW(layout) + 2 * padH > maxBoxW || bubbleInnerH(layout) + 2 * padV > maxBoxH) && sp > 9f) {
//                        sp -= 1f
//                        tp.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
//                        layout = buildLayout(maxInnerW)
//                    }
//                    if (bubbleInnerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
//                        maxLines = 2; layout = buildLayout(maxInnerW)
//                        if (bubbleInnerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
//                            maxLines = 1; layout = buildLayout(maxInnerW)
//                        }
//                    }
//
//                    val innerW = minOf(bubbleInnerW(layout), maxBoxW - 2 * padH)
//                    val innerH = minOf(bubbleInnerH(layout), maxBoxH - 2 * padV)
//                    val bubbleW = innerW + 2 * padH
//                    val bubbleH = innerH + 2 * padV
//
//                    // position by normalized pos in imgRect (rotation-safe because canvas is rotated)
//                    val targetCX = imgRect.left + (imgRect.width() * posX.coerceIn(0f, 1f))
//                    val targetCY = imgRect.top  + (imgRect.height() * posY.coerceIn(0f, 1f))
//                    var left = targetCX - bubbleW / 2f
//                    var top  = targetCY - bubbleH / 2f
//
//                    // clamp within image area
//                    val lowLeft  = imgRect.left.toFloat()  + margin
//                    val highLeft = imgRect.right.toFloat() - margin - bubbleW
//                    val lowTop   = imgRect.top.toFloat()   + margin
//                    val highTop  = imgRect.bottom.toFloat()- margin - bubbleH
//                    if (highLeft >= lowLeft) {
//                        if (left < lowLeft) left = lowLeft
//                        if (left > highLeft) left = highLeft
//                    } else {
//                        left = imgRect.left + (imgRect.width() - bubbleW) / 2f
//                    }
//                    if (highTop >= lowTop) {
//                        if (top < lowTop) top = lowTop
//                        if (top > highTop) top = highTop
//                    } else {
//                        top = lowTop
//                    }
//
//                    // bubble + text
//                    val bubble = RectF(left, top, left + bubbleW, top + bubbleH)
//                    canvas.drawRoundRect(bubble, dp(12f), dp(12f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 })
//
//                    val layoutWidth = layout.width.toFloat().coerceAtLeast(innerW)
//                    val dx = (innerW - layoutWidth) / 2f
//                    canvas.save()
//                    canvas.translate(left + padH + dx, top + padV)
//                    layout.draw(canvas)
//                    canvas.restore()
//                }
//
//                canvas.restore() // undo rotate/scale for this card only
//            }


//        private fun drawPhotoCardWithCaption(
//            ctx: Context,
//            canvas: Canvas,
//            uri: Uri,
//            x: Int, y: Int, w: Int, h: Int,
//            caption: String,
//            cropToFill: Boolean = false,                 // kept for compatibility
//            userCaptionSp: Float = DEFAULT_CAPTION_SP,
//            posX: Float = DEFAULT_POS_X,
//            posY: Float = DEFAULT_POS_Y,
//            rotationDeg: Float = -6f,
//            shrinkPct: Float,
//            userZoom: Float = 1.0f,                      // ignored when norm != null
//            norm: NormCrop?
//        ) {
//            fun dp(v: Float): Float = TypedValue.applyDimension(
//                TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
//            )
//            val dm = ctx.resources.displayMetrics
//            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
//
//            // Load bitmap (use your existing scaler if available)
//            val bmp = runCatching { loadScaledBitmap(ctx, uri, w, h) }.getOrNull()
//                ?: runCatching {
//                    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
//                }.getOrNull() ?: return
//
//            // Build the destination rect (shrinked & rotated card)
//            val cx = x + w / 2f
//            val cy = y + h / 2f
//            val drawW = (w * shrinkPct).toInt().coerceAtLeast(1)
//            val drawH = (h * shrinkPct).toInt().coerceAtLeast(1)
//            val imgRect = Rect(-drawW / 2, -drawH / 2, drawW / 2, drawH / 2)  // will draw at (0,0) after translate
//
//            // Compute src window
//            val destAR = drawW.toFloat() / drawH.toFloat()
//            val src: Rect = if (norm != null) {
//                // Convert saved normalized crop to pixels
//                val bx = bmp.width
//                val by = bmp.height
//                val x0 = (norm.left   * bx).toInt().coerceIn(0, bx - 1)
//                val y0 = (norm.top    * by).toInt().coerceIn(0, by - 1)
//                val x1 = (norm.right  * bx).toInt().coerceIn(x0 + 1, bx)
//                val y1 = (norm.bottom * by).toInt().coerceIn(y0 + 1, by)
//                val chosen = Rect(x0, y0, x1, y1)
//                // Ensure src matches dest aspect **by cropping only** (no stretch)
//                cropToAspectWithin(chosen, bmp.width, bmp.height, destAR)
//            } else {
//                // Fallback: center-crop to match dest aspect
//                val sw = bmp.width; val sh = bmp.height
//                val sAR = sw.toFloat() / sh.toFloat()
//                if (sAR > destAR) {
//                    val newW = (sh * destAR).toInt().coerceAtLeast(1)
//                    val left = (sw - newW) / 2
//                    Rect(left, 0, left + newW, sh)
//                } else {
//                    val newH = (sw / destAR).toInt().coerceAtLeast(1)
//                    val top = (sh - newH) / 2
//                    Rect(0, top, sw, top + newH)
//                }
//            }
//
//            // Draw rotated card
//            canvas.save()
//            canvas.translate(cx, cy)
//            canvas.rotate(rotationDeg)
//
//            // Rounded clip for the photo card
//            val r = dp(10f)
//            val clipPath = android.graphics.Path().apply {
//                addRoundRect(RectF(imgRect), r, r, android.graphics.Path.Direction.CW)
//            }
//            canvas.clipPath(clipPath)
//
//            // Photo (src cropped to dest rect) — uniform scale; no squeeze
//            canvas.drawBitmap(bmp, src, imgRect, paint)
//
//            // Caption bubble + text in rotated coordinates (posX,posY are normalized within imgRect)
//            if (caption.isNotBlank()) {
//                val margin = dp(6f)
//                val padH = dp(10f)
//                val padV = dp(6f)
//
//                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
//                    color = Color.WHITE
//                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
//                    setShadowLayer(dp(3f), 0f, dp(1.5f), 0x80000000.toInt())
//                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, userCaptionSp, dm)
//                }
//
//                var maxLines = 4
//                val maxBoxW = imgRect.width().toFloat()  - 2f * margin
//                val maxBoxH = imgRect.height().toFloat() - 2f * margin
//                val maxInnerW = (maxBoxW - 2 * padH).toInt().coerceAtLeast(1)
//
//                fun buildLayout(wi: Int): StaticLayout {
//                    @Suppress("DEPRECATION")
//                    return if (Build.VERSION.SDK_INT >= 23) {
//                        StaticLayout.Builder
//                            .obtain(caption, 0, caption.length, tp, maxOf(1, wi))
//                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
//                            .setIncludePad(false)
//                            .setLineSpacing(0f, 1f)
//                            .setEllipsize(TextUtils.TruncateAt.END)
//                            .setMaxLines(maxLines)
//                            .build()
//                    } else {
//                        StaticLayout(caption, tp, maxOf(1, wi), Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
//                    }
//                }
//                fun StaticLayout.maxLineWidth(): Float { var m = 0f; for (i in 0 until lineCount) m = maxOf(m, getLineWidth(i)); return m }
//
//                var layout = buildLayout(maxInnerW)
//                fun innerW(l: StaticLayout) = l.maxLineWidth().coerceAtLeast(1f)
//                fun innerH(l: StaticLayout) = l.height.toFloat().coerceAtLeast(1f)
//
//                // downsize to fit
//                while ((innerW(layout) + 2 * padH > maxBoxW || innerH(layout) + 2 * padV > maxBoxH) && tp.textSize > dp(9f)) {
//                    tp.textSize -= dp(1f)
//                    layout = buildLayout(maxInnerW)
//                }
//                if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
//                    maxLines = 2; layout = buildLayout(maxInnerW)
//                    if (innerH(layout) + 2 * padV > maxBoxH && maxLines > 1) {
//                        maxLines = 1; layout = buildLayout(maxInnerW)
//                    }
//                }
//
//                val innerWv = minOf(innerW(layout), maxBoxW - 2 * padH)
//                val innerHv = minOf(innerH(layout), maxBoxH - 2 * padV)
//                val bubbleW = innerWv + 2 * padH
//                val bubbleH = innerHv + 2 * padV
//
//                val targetCX = imgRect.left + (imgRect.width() * posX.coerceIn(0f, 1f))
//                val targetCY = imgRect.top  + (imgRect.height() * posY.coerceIn(0f, 1f))
//                var left = targetCX - bubbleW / 2f
//                var top  = targetCY - bubbleH / 2f
//
//                val lowLeft  = imgRect.left.toFloat()  + margin
//                val highLeft = imgRect.right.toFloat() - margin - bubbleW
//                val lowTop   = imgRect.top.toFloat()   + margin
//                val highTop  = imgRect.bottom.toFloat()- margin - bubbleH
//                if (highLeft >= lowLeft) { if (left < lowLeft) left = lowLeft; if (left > highLeft) left = highLeft }
//                else { left = imgRect.left + (imgRect.width() - bubbleW) / 2f }
//                if (highTop >= lowTop) { if (top < lowTop) top = lowTop; if (top > highTop) top = highTop }
//                else { top = lowTop }
//
//                val bubble = RectF(left, top, left + bubbleW, top + bubbleH)
//                val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
//                val rr = dp(12f)
//                canvas.drawRoundRect(bubble, rr, rr, bubblePaint)
//
//                val layoutWidth = layout.width.toFloat().coerceAtLeast(innerWv)
//                val dx = (innerWv - layoutWidth) / 2f
//                canvas.save()
//                canvas.translate(left + padH + dx, top + padV)
//                layout.draw(canvas)
//                canvas.restore()
//            }
//
//            canvas.restore()
//
//            // recycle if you decode a temp bitmap
//            // bmp.recycle()  // only if you know it's not shared elsewhere
//        }


        private fun drawPhotoCardWithCaption(
            ctx: Context,
            canvas: Canvas,
            uri: Uri,
            x: Int, y: Int, w: Int, h: Int,
            caption: String,
            cropToFill: Boolean = false,                 // kept for compatibility
            userCaptionSp: Float = DEFAULT_CAPTION_SP,
            posX: Float = DEFAULT_POS_X,
            posY: Float = DEFAULT_POS_Y,
            rotationDeg: Float = -6f,
            shrinkPct: Float,
            userZoom: Float = 1.0f,                      // ignored when norm != null
            norm: NormCrop?
        ) {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

            // Slightly wider portrait than 3:4
            val PHOTO_ASPECT = 4f / 5f  // width / height = 0.80


            // Helper: crop a Rect *within* the source to a target aspect (centered; no stretch)
            fun cropRectToAspectWithin(r: Rect, sw: Int, sh: Int, targetAR: Float) {
                val rw = r.width().toFloat().coerceAtLeast(1f)
                val rh = r.height().toFloat().coerceAtLeast(1f)
                val ar = rw / rh
                if (kotlin.math.abs(ar - targetAR) < 1e-3f) return

                if (ar > targetAR) {
                    // too wide → reduce width
                    val newW = (rh * targetAR).toInt().coerceAtLeast(1)
                    val cx = (r.left + r.right) / 2
                    var left = cx - newW / 2
                    if (left < 0) left = 0
                    if (left + newW > sw) left = sw - newW
                    r.set(left, r.top, left + newW, r.bottom)
                } else {
                    // too tall → reduce height
                    val newH = (rw / targetAR).toInt().coerceAtLeast(1)
                    val cy = (r.top + r.bottom) / 2
                    var top = cy - newH / 2
                    if (top < 0) top = 0
                    if (top + newH > sh) top = sh - newH
                    r.set(r.left, top, r.right, top + newH)
                }
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // Load bitmap (use your scaler if you have it)
            val bmp = runCatching { loadScaledBitmap(ctx, uri, w, h) }.getOrNull()
                ?: runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull() ?: return

            // --- rotate/scale only this card block ---
            val cx = x + w / 2f
            val cy = y + h / 2f
            canvas.save()
            canvas.translate(cx, cy)
            if (rotationDeg != 0f) canvas.rotate(rotationDeg)

            // Card rect centered at (0,0) to simplify transforms
            val drawW = (w * shrinkPct).toInt().coerceAtLeast(1)
            val drawH = (h * shrinkPct).toInt().coerceAtLeast(1)
            val cardRect = Rect(-drawW / 2, -drawH / 2, drawW / 2, drawH / 2)

            // Card background
            val corner = dp(10f)
            canvas.drawRoundRect(RectF(cardRect), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
            })

            // Photo container inside card (padding)
            val pad = dp(6f).toInt()
            val container = Rect(
                cardRect.left + pad, cardRect.top + pad,
                cardRect.right - pad, cardRect.bottom - pad
            )

            // Enforce a 4:5 photo area inside the card (centered within container)
            val imgRect = fitAspectInside(container, PHOTO_ASPECT)
            val destAR = imgRect.width().toFloat() / imgRect.height().toFloat()

            // --- PICK SOURCE WINDOW (no stretch) ---
            val src: Rect = if (norm != null) {
                // Use the exact crop the user chose in preview (normalized 0..1)
                val bx = bmp.width
                val by = bmp.height
                val x0 = (norm.left   * bx).toInt().coerceIn(0, bx - 1)
                val y0 = (norm.top    * by).toInt().coerceIn(0, by - 1)
                val x1 = (norm.right  * bx).toInt().coerceIn(x0 + 1, bx)
                val y1 = (norm.bottom * by).toInt().coerceIn(y0 + 1, by)
                Rect(x0, y0, x1, y1).also { cropRectToAspectWithin(it, bx, by, destAR) }
            } else if (cropToFill) {
                // center-crop fallback to match dest aspect
                val sw = bmp.width; val sh = bmp.height
                val sAR = sw.toFloat() / sh.toFloat()
                if (sAR > destAR) {
                    val newW = (sh * destAR).toInt().coerceAtLeast(1)
                    val left = (sw - newW) / 2
                    Rect(left, 0, left + newW, sh)
                } else {
                    val newH = (sw / destAR).toInt().coerceAtLeast(1)
                    val top = (sh - newH) / 2
                    Rect(0, top, sw, top + newH)
                }
            } else {
                // fit-center fallback (draw inside a smaller dest)
                Rect(0, 0, bmp.width, bmp.height)
            }

            // --- DRAW PHOTO ---
            val rImg = (corner - dp(2f)).coerceAtLeast(0f)
            val clipPath = android.graphics.Path().apply {
                addRoundRect(RectF(imgRect), rImg, rImg, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)

            if (norm != null || cropToFill) {
                // Fill imgRect exactly (uniform scale guaranteed by matching aspect)
                canvas.drawBitmap(bmp, src, imgRect, paint)
            } else {
                // Fit-center fallback (letterbox inside imgRect)
                val destW = imgRect.width().toFloat()
                val destH = imgRect.height().toFloat()
                val scale = minOf(destW / bmp.width, destH / bmp.height)
                val dw = (bmp.width * scale).toInt().coerceAtLeast(1)
                val dh = (bmp.height * scale).toInt().coerceAtLeast(1)
                val dx = imgRect.left + (imgRect.width() - dw) / 2
                val dy = imgRect.top  + (imgRect.height() - dh) / 2
                canvas.drawBitmap(bmp, null, Rect(dx, dy, dx + dw, dy + dh), paint)
            }

            canvas.restore()

            // --- THIN WHITE BORDER around the photo area ---
            canvas.drawRoundRect(RectF(imgRect), rImg, rImg, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1f)
                color = Color.WHITE
            })

            // --- Card stroke ---
            canvas.drawRoundRect(RectF(cardRect), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1.25f)
                color = Color.argb(40, 0, 0, 0)
            })

            // --- Caption bubble (posX/posY normalized inside imgRect) ---
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

                // downsize to fit
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

                // position by normalized pos in imgRect (rotation-safe; we're inside rotated canvas)
                val targetCX = imgRect.left + (imgRect.width() * posX.coerceIn(0f, 1f))
                val targetCY = imgRect.top  + (imgRect.height() * posY.coerceIn(0f, 1f))
                var left = targetCX - bubbleW / 2f
                var top  = targetCY - bubbleH / 2f

                // clamp within image area
                val lowLeft  = imgRect.left.toFloat()  + margin
                val highLeft = imgRect.right.toFloat() - margin - bubbleW
                val lowTop   = imgRect.top.toFloat()   + margin
                val highTop  = imgRect.bottom.toFloat()- margin - bubbleH
                if (highLeft >= lowLeft) { if (left < lowLeft) left = lowLeft; if (left > highLeft) left = highLeft }
                else { left = imgRect.left + (imgRect.width() - bubbleW) / 2f }
                if (highTop >= lowTop) { if (top < lowTop) top = lowTop; if (top > highTop) top = highTop }
                else { top = lowTop }

                val bubble = RectF(left, top, left + bubbleW, top + bubbleH)
                canvas.drawRoundRect(bubble, dp(12f), dp(12f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 })

                val layoutWidth = layout.width.toFloat().coerceAtLeast(innerWv)
                val dx = (innerWv - layoutWidth) / 2f
                canvas.save()
                canvas.translate(left + padH + dx, top + padV)
                layout.draw(canvas)
                canvas.restore()
            }

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
    return (sMin * 1.02).toFloat()
}

private fun cropToAspectWithin(r: Rect, bmpW: Int, bmpH: Int, targetAR: Float): Rect {
    var left = r.left.toFloat()
    var top  = r.top.toFloat()
    var right = r.right.toFloat()
    var bottom = r.bottom.toFloat()

    var w = (right - left).toFloat()
    var h = (bottom - top).toFloat()
    if (w <= 0f || h <= 0f) return Rect(0, 0, 1, 1)

    val currentAR = w / h
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f

    if (currentAR > targetAR) {
        // too wide → reduce width
        w = h * targetAR
    } else if (currentAR < targetAR) {
        // too tall → reduce height
        h = w / targetAR
    } // else already matches

    left   = cx - w / 2f
    right  = cx + w / 2f
    top    = cy - h / 2f
    bottom = cy + h / 2f

    // Clamp to original rect bounds (never expand beyond what user picked)
    if (left < r.left)   { right -= (r.left - left); left = r.left.toFloat() }
    if (right > r.right) { left  += (right - r.right); right = r.right.toFloat() }
    if (top < r.top)     { bottom -= (r.top - top); top = r.top.toFloat() }
    if (bottom > r.bottom){ top  += (bottom - r.bottom); bottom = r.bottom.toFloat() }

    // Final safety clamp to bitmap bounds
    left = left.coerceIn(0f, (bmpW - 1).toFloat())
    top  = top.coerceIn(0f, (bmpH - 1).toFloat())
    right = right.coerceIn((left + 1f), bmpW.toFloat())
    bottom = bottom.coerceIn((top + 1f), bmpH.toFloat())

    return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
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


