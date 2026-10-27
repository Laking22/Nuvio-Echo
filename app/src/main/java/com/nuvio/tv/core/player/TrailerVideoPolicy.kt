package com.nuvio.tv.core.player

/**
 * Trailers render through a TextureView on top of the UI, so a 4K trailer costs a few hundred MB of decoder and GPU
 * memory that full-screen playback never pays. Trailer quality (Layout → Home Layout) picks the cap: Auto matches it to
 * what the trailer plays on, at most 1080p.
 */
object TrailerVideoPolicy {
    const val MAX_TRAILER_HEIGHT_WITHOUT_4K = 1080

    /** Tallest trailer video to play; [Int.MAX_VALUE] means no cap. */
    fun maxTrailerVideoHeight(allow4k: Boolean): Int =
        if (allow4k) Int.MAX_VALUE else MAX_TRAILER_HEIGHT_WITHOUT_4K

    /** Trailer quality setting: match the picture to what the trailer plays on. */
    const val AUTO = 0
    /** The trailer quality setting's choices: [AUTO], then the tallest picture to fetch (2160 means no cap). */
    val QUALITIES = listOf(AUTO, 480, 720, 1080, 2160)
    /** A trailer played full screen, or on a surface whose size isn't given. */
    private const val FULL_SCREEN_HEIGHT = 1080

    /**
     * The tallest trailer picture to fetch with the trailer [quality] setting, for a surface [surfaceHeightPx] tall
     * (null: full screen). Auto takes the smallest quality class at least as tall as the surface, so a card's trailer
     * isn't fetched and decoded at 1080p only to be shown 400 px tall.
     */
    fun heightFor(quality: Int, surfaceHeightPx: Int?): Int = when {
        quality == AUTO -> {
            val surface = surfaceHeightPx ?: FULL_SCREEN_HEIGHT
            when {
                surface <= 480 -> 480
                surface <= 720 -> 720
                else -> FULL_SCREEN_HEIGHT
            }
        }
        quality >= 2160 -> Int.MAX_VALUE
        else -> quality
    }
}

/**
 * How tall, in pixels, the surface a trailer is being fetched for is: put in the coroutine context around a
 * [com.nuvio.tv.data.trailer.TrailerService] call, so Auto trailer quality can match it without every lookup in
 * between passing it along.
 */
class TrailerSurface(val heightPx: Int) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<TrailerSurface>
}
