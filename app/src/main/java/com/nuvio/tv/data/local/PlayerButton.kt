package com.nuvio.tv.data.local

import androidx.annotation.StringRes
import com.nuvio.tv.R

/**
 * Player buttons that can sit on the main row or behind the arrow, in the order they appear.
 * Play/pause and the arrow itself are always on the row.
 */
enum class PlayerButton(val key: String, @StringRes val labelRes: Int) {
    NEXT_EPISODE("next_episode", R.string.next_episode_label),
    SUBTITLES("subtitles", R.string.cd_subtitles),
    AUDIO("audio", R.string.cd_audio_tracks),
    SOURCES("sources", R.string.cd_sources),
    QUALITY("quality", R.string.cd_quality),
    SWITCH_ENGINE("switch_engine", R.string.cd_switch_player_engine),
    EPISODES("episodes", R.string.cd_episodes),
    SPEED("speed", R.string.cd_playback_speed),
    ASPECT_RATIO("aspect_ratio", R.string.cd_aspect_ratio),
    EXTERNAL_PLAYER("external_player", R.string.cd_open_external_player),
    STREAM_INFO("stream_info", R.string.cd_stream_info),
    REPORT_ISSUE("report_issue", R.string.player_report_issue);

    companion object {
        /** The player's main row; AUDIO is the merged Audio & Quality button there. */
        val newLookMainRow: Set<PlayerButton> = setOf(NEXT_EPISODE, SUBTITLES, AUDIO, EPISODES)
    }
}
