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
        clip(R.raw.alert1, "警報1", "効果音", EffectVolume),
        clip(R.raw.alert2, "警報2", "効果音", EffectVolume),
        clip(R.raw.jihou_sunset, "日の入り", "効果音", EffectVolume),
        clip(R.raw.jihou_normal_1, "通常 0分", "時報", ChimeVolume),
        clip(R.raw.jihou_normal_2, "通常 15分", "時報", ChimeVolume),
        clip(R.raw.jihou_normal_3, "通常 30分", "時報", ChimeVolume),
        clip(R.raw.jihou_normal_4, "通常 45分", "時報", ChimeVolume),
        clip(R.raw.jihou_weekend_1, "土日 0分", "時報", ChimeVolume),
        clip(R.raw.jihou_weekend_2, "土日 15分", "時報", ChimeVolume),
        clip(R.raw.jihou_weekend_3, "土日 30分", "時報", ChimeVolume),
        clip(R.raw.jihou_weekend_4, "土日 45分", "時報", ChimeVolume),
        clip(R.raw.jihou_christmas_1, "クリスマス 0分", "時報", ChimeVolume),
        clip(R.raw.jihou_christmas_2, "クリスマス 15分", "時報", ChimeVolume),
        clip(R.raw.jihou_christmas_3, "クリスマス 30分", "時報", ChimeVolume),
        clip(R.raw.jihou_christmas_4, "クリスマス 45分", "時報", ChimeVolume),
        clip(R.raw.jihou_holiday_1, "祝日 0分", "時報", ChimeVolume),
        clip(R.raw.jihou_holiday_2, "祝日 15分", "時報", ChimeVolume),
        clip(R.raw.jihou_holiday_3, "祝日 30分", "時報", ChimeVolume),
        clip(R.raw.jihou_holiday_4, "祝日 45分", "時報", ChimeVolume),
        clip(R.raw.jihou_intro_kakko, "0時 かっこう", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_story02, "3時 ストーリー", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_story01, "8時 ストーリー", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_takarajima1, "10時 宝島", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_kusakeiba, "12時 草競馬", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_takarajima2, "12時30分 宝島", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_takarajima3, "13時30分 宝島", "イントロ", ChimeVolume),
        clip(R.raw.jihou_intro_tetris, "18時 テトリス", "イントロ", ChimeVolume),
        clip(R.raw.away_alert_ora, "オラはにんきもの", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_omedetou_christmas, "おめでとうクリスマス", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_mickey, "ミッキーマウスマーチ", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_umi_stage, "海ステージ", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_ginga999, "銀河鉄道999", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_ima, "今", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_coffee_rumba, "コーヒールンバ", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_megalovania, "Megalovania", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_jingle_bell, "ジングル・ベル", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_awatenbou, "あわてんぼうのサンタクロース", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_small_world, "It's a small world", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_winnie, "Winnie The Pooh", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_santa_town, "サンタが街にやってくる", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_reiwa, "令和", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_rudolph, "赤鼻のトナカイ", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_zip_a_dee, "Zip A Dee Doo Dah", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_hes_a_pirate, "He's a Pirate", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_mario_land_2, "スーパーマリオランド2", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_zelda_overworld, "ゼルダの伝説 地上", "外出アラート", EffectVolume),
        clip(R.raw.away_alert_kakkou, "かっこう", "外出アラート", EffectVolume),
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
