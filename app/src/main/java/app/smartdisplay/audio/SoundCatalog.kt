package app.smartdisplay.audio

import app.smartdisplay.R

data class SoundClip(
    val resId: Int,
    val title: String,
    val group: String,
    val playbackVolume: Float,
)

/**
 * サウンドテストに出す音。
 * `res/raw` に音を足したら、ここへタイトルと再生音量を足す。
 * 未登録の raw も「その他」として出す。
 */
fun soundTestClips(): List<SoundClip> {
    val known = knownClips()
    val knownIds = known.map { it.resId }.toSet()
    val extras = R.raw::class.java.fields.mapNotNull { field ->
        val resId = runCatching { field.getInt(null) }.getOrNull() ?: return@mapNotNull null
        if (resId in knownIds) {
            null
        } else {
            val volume = if (field.name.startsWith("jihou_")) ChimeVolume else EffectVolume
            SoundClip(
                resId = resId,
                title = field.name,
                group = "その他",
                playbackVolume = volume,
            )
        }
    }.sortedBy { it.title }
    return known + extras
}

private fun knownClips(): List<SoundClip> {
    return listOf(
        clip(R.raw.tap, "タップ", "効果音", EffectVolume),
        clip(R.raw.beep, "ビープ", "効果音", EffectVolume),
        clip(R.raw.notice, "通知", "効果音", EffectVolume),
        clip(R.raw.jihou_normal_1, "通常 0分", "時報", ChimeVolume),
        clip(R.raw.jihou_normal_2, "通常 15分", "時報", ChimeVolume),
        clip(R.raw.jihou_normal_3, "通常 30分", "時報", ChimeVolume),
        clip(R.raw.jihou_normal_4, "通常 45分", "時報", ChimeVolume),
        clip(R.raw.jihou_intro_kakko, "0時 かっこう", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_story02, "3時 ストーリー", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_story01, "8時 ストーリー", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_takarajima1, "10時 宝島", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_kusakeiba, "12時 草競馬", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_takarajima2, "12時30分 宝島", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_takarajima3, "13時30分 宝島", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_tetris, "18時 テトリス", "イントロ", ChimeVolume),
    )
}

private fun clip(resId: Int, title: String, group: String, playbackVolume: Float): SoundClip {
    return SoundClip(
        resId = resId,
        title = title,
        group = group,
        playbackVolume = playbackVolume,
    )
}

private const val ChimeVolume = 0.72f
private const val EffectVolume = 1f
