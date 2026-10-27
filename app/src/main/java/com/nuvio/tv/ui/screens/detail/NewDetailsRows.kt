package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TraktCommentReview

private val CardShape = RoundedCornerShape(10.dp)

/** Similar titles or a collection: landscape cards, title and year under each. */
@Composable
internal fun NdTitleRow(
    title: String,
    items: List<MetaPreview>,
    footer: String?,
    onClick: (MetaPreview) -> Unit,
    onLongClick: (MetaPreview) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        NdRowHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items, key = { i, item -> "${item.id}|$i" }) { _, item ->
                NdTitleCard(item = item, onClick = { onClick(item) }, onLongClick = { onLongClick(item) })
            }
        }
        if (footer != null) {
            Text(
                text = footer,
                color = NdText3,
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp, top = 2.dp)
            )
        }
    }
}

@Composable
internal fun NdRowHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 6.dp)) {
        Text(title, color = Color.White, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) {
            Text(subtitle, color = NdText3, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun NdTitleCard(item: MetaPreview, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val image = item.background ?: item.poster
    Column(Modifier.width(220.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .clip(CardShape)
                .background(Color(0xFF111111))
                .then(if (focused) Modifier.border(2.dp, NuvioTheme.colors.FocusRing, CardShape) else Modifier)
                .ndClickable(onFocusChanged = { focused = it }, onLongClick = onLongClick, onClick = onClick)
        ) {
            if (image != null) {
                AsyncImage(
                    model = remember(image) { ImageRequest.Builder(context).data(image).crossfade(true).build() },
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        item.releaseInfo?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = NdText3, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/** Reviews of the movie or the whole show; OK opens the full review, Left/Right in it moves through them. */
@Composable
internal fun NdReviewsRow(
    subtitle: String?,
    reviews: ReviewsState,
    onOpen: (List<TraktCommentReview>, Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        NdRowHeader(stringResource(R.string.nd_row_reviews), subtitle)
        when {
            reviews.items.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(reviews.items, key = { i, r -> "${r.id}|$i" }) { index, review ->
                    NdReviewCard(review = review, width = 300.dp, maxLines = 4, onClick = { onOpen(reviews.items, index) })
                }
            }
            else -> Text(
                text = stringResource(if (reviews.loading || !reviews.loaded) R.string.nd_reviews_loading else R.string.nd_reviews_empty),
                color = NdText2,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(horizontal = 48.dp, vertical = 8.dp)
                    // Focusable, so Down from the rows above lands somewhere and the page can scroll to the end.
                    .ndClickable(onClick = {})
            )
        }
    }
}

@Composable
internal fun NdReviewCard(
    review: TraktCommentReview,
    width: Dp?,
    maxLines: Int,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width).height(152.dp) else Modifier.fillMaxWidth())
            .then(
                if (focused) Modifier.detailGlassLens(shape)
                else Modifier.clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            )
            .ndClickable(onFocusChanged = { focused = it }, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    review.authorDisplayName.trim().take(1).uppercase(),
                    color = Color(0xFFDDDDDD), fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
            Column(Modifier.weight(1f)) {
                Text(review.authorDisplayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                reviewDate(review)?.let { Text(it, color = NdText3, fontSize = 11.sp, maxLines = 1) }
            }
            review.rating?.let { rating ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("★", color = Color(0xFFF5C518), fontSize = 12.sp)
                    Text(stringResource(R.string.nd_review_score, rating), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            text = if (review.hasSpoilerContent) stringResource(R.string.detail_comments_spoiler_hidden) else review.comment,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "12 Mar 2024" from the review's ISO date, in the device's locale. */
private fun reviewDate(review: TraktCommentReview): String? {
    val raw = review.createdAt ?: return null
    val date = runCatching { java.time.OffsetDateTime.parse(raw).toLocalDate() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(raw).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: return null
    return date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}
