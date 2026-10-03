package com.lagradost.quicknovel.ui.discover

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.CloudStreamPrimaryColor
import com.lagradost.quicknovel.compose.CloudStreamTheme
import com.lagradost.quicknovel.compose.CloudStreamTheme.colors
import com.lagradost.quicknovel.compose.CloudStreamThemeMode
import com.lagradost.quicknovel.discover.discoverVoteTarget
import com.lagradost.quicknovel.discover.formatDiscoverScore

/**
 * Reddit-style vote control shared by the Discover feed and the Home "Popular" row. The caller
 * owns the optimistic update, the rollback snackbar and the signed-out sign-in prompt; this only
 * reports the vote the user asked for (-1, 0 or 1) through [onVote].
 */
@Composable
fun VoteControl(
    score: Int,
    myVote: Int,
    onVote: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        VoteArrow(
            icon = R.drawable.ic_baseline_arrow_upward_24,
            contentDescription = stringResource(R.string.discover_vote_up),
            active = myVote == 1,
            onClick = { onVote(discoverVoteTarget(myVote, 1)) },
        )
        Text(
            text = formatDiscoverScore(score),
            color = colors.onBackground,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 30.dp)
                .padding(horizontal = 2.dp),
        )
        VoteArrow(
            icon = R.drawable.ic_baseline_arrow_downward_24,
            contentDescription = stringResource(R.string.discover_vote_down),
            active = myVote == -1,
            onClick = { onVote(discoverVoteTarget(myVote, -1)) },
        )
    }
}

@Composable
private fun VoteArrow(
    @DrawableRes icon: Int,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = if (active) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Preview(name = "VoteControl · Light", showBackground = true)
@Composable
private fun VoteControlLightPreview() = VoteControlPreview(CloudStreamThemeMode.Light)

@Preview(name = "VoteControl · Amoled", showBackground = true)
@Composable
private fun VoteControlAmoledPreview() = VoteControlPreview(CloudStreamThemeMode.AmoledLight)

@Composable
private fun VoteControlPreview(mode: CloudStreamThemeMode) {
    CloudStreamTheme(mode = mode, primaryColor = CloudStreamPrimaryColor.NORMAL) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoteControl(score = 1234, myVote = 1, onVote = {})
            VoteControl(score = 0, myVote = 0, onVote = {})
            VoteControl(score = -12, myVote = -1, onVote = {})
        }
    }
}
