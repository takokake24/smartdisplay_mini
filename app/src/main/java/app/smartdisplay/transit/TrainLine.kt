package app.smartdisplay.transit

import app.smartdisplay.R

enum class TrainLine(
    val displayName: String,
    val yahooId: Int,
    val iconResId: Int,
) {
    JrChuoRapid(
        displayName = "JR中央快速線",
        yahooId = 38,
        iconResId = R.drawable.ic_line_jr_jc,
    ),
    MetroMarunouchi(
        displayName = "東京メトロ丸ノ内線",
        yahooId = 133,
        iconResId = R.drawable.ic_line_marunouchi,
    ),
}

enum class OperationKind {
    Normal,
    Delay,
    Suspended,
    Resumed,
    Other,
}

data class LineOperation(
    val line: TrainLine,
    val kind: OperationKind,
    val statusLabel: String,
    val detail: String,
    val updatedAtText: String? = null,
)

fun OperationKind.toStatusMark(): app.smartdisplay.notification.NotificationStatusMark {
    return when (this) {
        OperationKind.Normal -> app.smartdisplay.notification.NotificationStatusMark.Normal
        OperationKind.Delay, OperationKind.Resumed, OperationKind.Other ->
            app.smartdisplay.notification.NotificationStatusMark.Caution
        OperationKind.Suspended -> app.smartdisplay.notification.NotificationStatusMark.Stopped
    }
}

fun OperationKind.statusGlyph(): String {
    return when (this) {
        OperationKind.Normal -> "○"
        OperationKind.Delay, OperationKind.Resumed, OperationKind.Other -> "△"
        OperationKind.Suspended -> "×"
    }
}

fun shouldAnnounceTransition(previous: OperationKind?, next: OperationKind): Boolean {
    if (previous == null || previous == next) {
        return false
    }
    return when (next) {
        OperationKind.Delay -> previous != OperationKind.Delay
        OperationKind.Suspended -> previous != OperationKind.Suspended
        OperationKind.Resumed -> previous == OperationKind.Suspended || previous == OperationKind.Delay
        OperationKind.Normal -> previous != OperationKind.Normal
        OperationKind.Other -> previous == OperationKind.Normal
    }
}
