package com.lagradost.quicknovel.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.CloudStreamTheme.colors
import com.lagradost.quicknovel.discover.discoverCharacterCount
import com.lagradost.quicknovel.discover.isDiscoverBodyValid
import com.lagradost.quicknovel.discover.normalizeDiscoverTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritePostSheet(writer: WritePostState, action: (DiscoverAction) -> Unit) {
    var pickingNovel by rememberSaveable { mutableStateOf(false) }
    var pickingTags by rememberSaveable { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(sheetState = sheet, containerColor = colors.background,
        sheetGesturesEnabled = !writer.posting,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !writer.posting, shouldDismissOnClickOutside = !writer.posting),
        onDismissRequest = { action(DiscoverAction.CloseWriter) }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.discover_write), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                IconButton(enabled = !writer.posting, onClick = { action(DiscoverAction.CloseWriter) }) {
                    Icon(painterResource(R.drawable.close_24px), stringResource(R.string.close))
                }
            }
            when {
                writer.loadingLibrary -> CircularProgressIndicator()
                writer.novels.isEmpty() -> {
                    Text(stringResource(if (writer.error == null) R.string.discover_library_empty else writer.error.text))
                    if (writer.error != null) Button(onClick = { action(DiscoverAction.RetryLibrary) }) {
                        Text(stringResource(R.string.discover_retry))
                    }
                }
                else -> {
                    Button(enabled = !writer.posting, onClick = { pickingNovel = true }) {
                        Text(stringResource(R.string.discover_choose_novel))
                    }
                    writer.novel?.let { novel ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NovelCover(novel.title, novel.cover)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(novel.title, style = MaterialTheme.typography.titleMedium)
                                Text(novel.provider, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        Text(stringResource(R.string.tags), style = MaterialTheme.typography.titleSmall)
                        if (writer.loadingTags) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.discover_loading_tags), style = MaterialTheme.typography.bodySmall)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            writer.tags.forEach { tag ->
                                InputChip(selected = true, enabled = !writer.posting && !writer.loadingTags,
                                    onClick = { action(DiscoverAction.RemoveTag(tag)) }, label = { Text(tagLabel(tag)) },
                                    trailingIcon = {
                                        Icon(painterResource(R.drawable.close_24px), stringResource(R.string.discover_remove_tag, tagLabel(tag)),
                                            Modifier.size(16.dp))
                                    })
                            }
                            if (writer.providerTags.isNotEmpty()) {
                                AssistChip(enabled = !writer.posting && !writer.loadingTags && writer.tags.size < 8,
                                    onClick = { pickingTags = true }, label = { Text(stringResource(R.string.discover_add_tag)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_baseline_add_24), null, Modifier.size(18.dp)) })
                            }
                        }
                        Text(stringResource(R.string.discover_tag_count, writer.tags.size), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    OutlinedTextField(value = writer.body, enabled = !writer.posting,
                        onValueChange = { action(DiscoverAction.Body(it)) }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
                        label = { Text(stringResource(R.string.discover_opinion)) },
                        isError = writer.body.isNotEmpty() && !isDiscoverBodyValid(writer.body),
                        supportingText = { Text(stringResource(R.string.discover_body_count, writer.body.discoverCharacterCount())) })
                    Text(stringResource(R.string.discover_rating_optional), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        (1..5).forEach { star ->
                            IconButton(enabled = !writer.posting, onClick = {
                                action(DiscoverAction.Rating(star.takeUnless { it == writer.rating }))
                            }) {
                                Icon(painterResource(R.drawable.ic_baseline_star_24), stringResource(R.string.discover_rate, star),
                                    tint = if (star <= (writer.rating ?: 0)) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
                        if (writer.rating != null) TextButton(enabled = !writer.posting, onClick = { action(DiscoverAction.Rating(null)) }) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                    writer.error?.let { Text(stringResource(it.text), color = colors.error) }
                    Button(enabled = writer.canPost, onClick = { action(DiscoverAction.Post) }, modifier = Modifier.fillMaxWidth()) {
                        if (writer.posting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(if (writer.posting) R.string.loading else if (writer.error != null) R.string.discover_retry else R.string.discover_post))
                    }
                }
            }
        }
    }

    if (pickingNovel) {
        AlertDialog(containerColor = colors.background, onDismissRequest = { pickingNovel = false },
            title = { Text(stringResource(R.string.discover_choose_novel)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(writer.novels, key = { it.provider to it.url }) { novel ->
                        Row(Modifier.fillMaxWidth().clickable {
                            pickingNovel = false
                            action(DiscoverAction.PickNovel(novel))
                        }, verticalAlignment = Alignment.CenterVertically) {
                            NovelCover(novel.title, novel.cover)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(novel.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(novel.provider, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingNovel = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (pickingTags) {
        AlertDialog(containerColor = colors.background, onDismissRequest = { pickingTags = false },
            title = { Text(stringResource(R.string.discover_add_tag)) },
            text = {
                Column {
                    Text(stringResource(R.string.discover_tag_count, writer.tags.size))
                    FlowRow(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        writer.providerTags.forEach { tag ->
                            val selected = tag in writer.tags
                            val fits = tag in normalizeDiscoverTags(writer.tags + tag)
                            FilterChip(selected = selected, enabled = !writer.posting && (selected || fits),
                                onClick = { action(if (selected) DiscoverAction.RemoveTag(tag) else DiscoverAction.AddTag(tag)) },
                                label = { Text(tagLabel(tag)) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingTags = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}
