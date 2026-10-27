package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.TmdbReview
import com.nuvio.tv.data.remote.api.TmdbReviewAuthor
import com.nuvio.tv.data.repository.TitleReviewsRepository.Companion.toReview
import com.nuvio.tv.domain.model.TraktCommentReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleReviewsRepositoryTest {

    private fun review(id: Long, author: String, text: String, isReview: Boolean = false, at: String? = null) =
        TraktCommentReview(id = id, authorDisplayName = author, comment = text, review = isReview, createdAt = at)

    @Test
    fun `TMDB reviews become plain-text reviews with a rounded score`() {
        val r = TmdbReview(
            id = "5dfa", author = "gimly", authorDetails = TmdbReviewAuthor(name = "", username = "gimly", rating = 8.6),
            content = "**Great** fun. See [my blog](https://x.y) for more.\r\n\r\n\r\n\r\nBye", createdAt = "2019-12-28T10:00:00.000Z"
        ).toReview()!!
        assertEquals("gimly", r.authorDisplayName)
        assertNull(r.authorUsername)
        assertEquals("Great fun. See my blog for more.\n\nBye", r.comment)
        assertEquals(9, r.rating)
    }

    @Test
    fun `empty TMDB reviews are dropped`() {
        assertNull(TmdbReview(id = "1", author = "a", content = "   ").toReview())
    }

    @Test
    fun `Trakt reviews come first, then TMDB newest first, without repeats`() {
        val merged = TitleReviewsRepository.mergeReviews(
            trakt = listOf(review(1, "Ann", "short comment"), review(2, "Bob", "a real review", isReview = true)),
            tmdb = listOf(
                review(3, "Cy", "old", at = "2019-01-01"),
                review(4, "Dee", "new", at = "2021-01-01"),
                review(5, "bob", "a real review")
            )
        )
        assertEquals(listOf(2L, 1L, 4L, 3L), merged.map { it.id })
    }
}
