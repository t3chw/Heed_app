package com.bol.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import com.bol.app.ui.theme.DarkHeedColors
import com.bol.app.ui.theme.LightHeedColors
import com.bol.app.MainActivity
import com.bol.app.R

/**
 * Heed home-screen widget — a one-tap shortcut to start recording.
 *
 * Layout: round mascot avatar (left), "Heed" wordmark (centre,
 * flex), brand-green circular mic button (right). The whole row is
 * a single click target — no separate buttons. Tapping launches
 * MainActivity with `open_recording = true`, which fires the
 * shared [com.bol.app.service.RecordingSignal.requestStart] flag;
 * HomeScreen's LaunchedEffect picks it up and runs the same
 * permission-check + `recordingViewModel.startRecording()` flow
 * the in-app MicPill uses, so the user lands directly in the
 * recording focus mode — no modal sheet.
 *
 * Glance can't render the in-app BolMascot (that's a Compose Canvas;
 * RemoteViews don't support Canvas), so we use the round adaptive
 * launcher icon. Same character, same palette.
 */
class BolWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val tapToRecord = actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    putExtra("open_recording", true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )

            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ImageProvider(R.drawable.widget_background))
                    .padding(10.dp)
                    .clickable(tapToRecord),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.mipmap.ic_launcher_round),
                    contentDescription = "Heed",
                    modifier = GlanceModifier
                        .size(48.dp)
                        .cornerRadius(24.dp),
                )
                Spacer(GlanceModifier.width(10.dp))
                Text(
                    text = "Heed",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = ColorProvider(day = LightHeedColors.textPrimary, night = DarkHeedColors.textPrimary),
                    ),
                )
                Box(
                    modifier = GlanceModifier
                        .size(44.dp)
                        .cornerRadius(22.dp)
                        .background(ColorProvider(day = LightHeedColors.brand, night = DarkHeedColors.brand)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.widget_mic),
                        contentDescription = "Record",
                        modifier = GlanceModifier.size(24.dp),
                    )
                }
            }
        }
    }

    // Glance can't read the Compose theme, so the widget takes the light and dark token values directly (day / night
    // colour providers); the background and mic drawables use the matching values(-night) colour resources.
}
