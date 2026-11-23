package org.dwarftsch.meltdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ============================================================================
// Meltdown-spezifische Bausteine auf Basis von Minze & Honig 2.0.
// ============================================================================

/**
 * Kopf der Startansicht wie im Web: oben rechts die Symbol-Knöpfe, darunter
 * der Titel (H1, linksbündig). Anders als [MhKopf] ohne Zurück-Ziel, damit
 * der lange Titel auch bei großer Schrift die volle Breite hat.
 */
@Composable
fun StartKopf(titel: String, symbole: @Composable RowScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Mh.farben.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = Mh.rand),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = symbole,
        )
        Text(
            titel,
            style = MhTypo.h1,
            color = Mh.farben.textStrong,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Mh.abstand3))
    }
}

/**
 * Datenfarbe der Intensität wie im Web: 1–3 Minze, 4–7 Honig, 8–10 Flieder.
 * Steht immer neben der Zahl, Farbe ist nie der einzige Träger.
 */
@Composable
@ReadOnlyComposable
fun intensitaetFarbe(wert: Int): Color = when {
    wert <= 3 -> Mh.farben.data1
    wert <= 7 -> Mh.farben.data2
    else -> Mh.farben.data3
}

/** Intensität im Verlauf: Punkt (10 dp) in der Datenfarbe, daneben die Zahl. */
@Composable
fun IntensitaetsMarke(wert: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Intensität $wert von 10" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MhPunkt(intensitaetFarbe(wert))
        Spacer(Modifier.width(6.dp))
        Text(
            "$wert",
            style = MhTypo.small.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = Mh.farben.textStrong,
        )
    }
}
